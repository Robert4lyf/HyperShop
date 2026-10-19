package com.robert4lyf.hypershop.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import com.robert4lyf.hypershop.catalog.IngredientLibrary
import com.robert4lyf.hypershop.claude.ClaudeClient
import com.robert4lyf.hypershop.claude.GuardedClaude
import com.robert4lyf.hypershop.claude.RingLog
import com.robert4lyf.hypershop.claude.Usage
import com.robert4lyf.hypershop.claude.UsageRecord
import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.data.EpochMarker
import com.robert4lyf.hypershop.data.HyperShopDatabase
import com.robert4lyf.hypershop.data.ListRepository
import com.robert4lyf.hypershop.extract.FetchPolicy
import com.robert4lyf.hypershop.extract.SafeFetcher
import com.robert4lyf.hypershop.importing.HyperShopWorkerFactory
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportJobs
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.Importer
import com.robert4lyf.hypershop.importing.ReviewGate
import com.robert4lyf.hypershop.importing.SharedText
import com.robert4lyf.hypershop.secrets.ApiKeyStore
import com.robert4lyf.hypershop.secrets.InMemoryApiKeyStore
import com.robert4lyf.hypershop.settings.Settings
import com.robert4lyf.hypershop.settings.settingsDataStore
import com.robert4lyf.hypershop.watch.NoWatchLink
import com.robert4lyf.hypershop.watch.WatchLink
import com.robert4lyf.hypershop.watch.WatchSyncCoordinator
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface ShareOutcome {
    data class Review(
        val jobId: Long,
    ) : ShareOutcome

    /** [seq] tells two "no link" shares in a row apart (a StateFlow drops an equal value). */
    data class NoLink(
        val seq: Long,
    ) : ShareOutcome
}

/**
 * Hand-rolled dependency graph (the BangGym pattern): one object on the
 * Application, no Hilt. Tests build their own with an in-memory database, a
 * loosened fetch policy pointed at MockWebServer, and fakes.
 */
class AppContainer(
    val context: Context,
    val db: HyperShopDatabase = HyperShopDatabase.build(context),
    dataStore: DataStore<Preferences> = context.settingsDataStore,
    val apiKeyStore: ApiKeyStore = InMemoryApiKeyStore(),
    fetchPolicy: FetchPolicy = FetchPolicy.STRICT,
    claudeBaseUrl: String = "https://api.anthropic.com",
    val watchLink: WatchLink = NoWatchLink,
    val clock: () -> Long = System::currentTimeMillis,
    val workManager: () -> WorkManager = { WorkManager.getInstance(context) },
) {
    val applicationScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default +
                CoroutineExceptionHandler { _, error -> Timber.e(error, "Uncaught in applicationScope") },
        )

    /** The bundled catalogue with your library edits applied (see [library]). */
    val catalog: Catalog = CatalogLoader.default.copy()

    /** Read before the repository and parsers are built, so they never see the catalogue without your edits. */
    val library = IngredientLibrary(File(context.filesDir, "library/edits.json"), catalog)
    val repository = ListRepository(db, catalog, clock)
    val settings = Settings(dataStore)
    val log = RingLog()
    val reviewGate = ReviewGate()
    val ruleParser = RuleParser(catalog)

    val claudeClient =
        ClaudeClient(
            apiKey = { apiKeyStore.get() },
            baseUrl = claudeBaseUrl,
            usageSink = { model, usage -> recordUsage(model, usage) },
            log = log,
        )

    val claude =
        GuardedClaude(
            client = claudeClient,
            guard = { settings.guard() },
            history = { usageHistory() },
            today = { today() },
        )

    val importer =
        Importer(
            fetcher = SafeFetcher(fetchPolicy),
            rules = ruleParser,
            claude = claude,
            hasKey = { apiKeyStore.get() != null },
        )

    val importJobs = ImportJobs(db.dao(), workManager, clock)

    val workerFactory = HyperShopWorkerFactory { this }

    /** Answers the watch and keeps it up to date (step 7.2). */
    val watchSync =
        WatchSyncCoordinator(
            link = watchLink,
            repository = repository,
            catalog = catalog,
            units = { settings.units.first() },
            scope = applicationScope,
            log = log::log,
            clock = clock,
        )

    /**
     * Shares waiting for their review, in order. Held here, not in the UI, so a
     * rotation loses none; the UI opens the head when no review is showing and
     * then calls [reviewOpened].
     */
    val pendingReviews = MutableStateFlow<List<Long>>(emptyList())

    fun queueReview(jobId: Long) {
        pendingReviews.update { if (jobId in it) it else it + jobId }
    }

    fun reviewOpened(jobId: Long) {
        pendingReviews.update { it - jobId }
    }

    /**
     * A share's outcome the UI has to show: "no link", or (from the debug screen)
     * a job to review. One at a time: the UI calls [shareHandled] once it has
     * acted on it, and the next that arrived meanwhile takes its place.
     */
    val shares = MutableStateFlow<ShareOutcome?>(null)
    private val waitingShares = ArrayDeque<ShareOutcome>()
    private var shareSeq = 0L

    private fun offer(outcome: ShareOutcome) {
        synchronized(waitingShares) { if (!shares.compareAndSet(null, outcome)) waitingShares.addLast(outcome) }
    }

    /** The UI has acted on [outcome]: the next waiting share, if any, is offered. */
    fun shareHandled(outcome: ShareOutcome) {
        synchronized(waitingShares) {
            if (shares.compareAndSet(outcome, null)) waitingShares.removeFirstOrNull()?.let { shares.value = it }
        }
    }

    /** Claude parses in flight, by job: a review reopened mid-call joins it rather than paying for a second. */
    private val parsesInFlight = HashMap<Long, Deferred<ImportResult>>()

    /**
     * Runs [parse] in [applicationScope] (leaving the screen mid-call still records
     * the usage and keeps the answer), unless one is already running for [jobId],
     * which is returned instead. Cancelling a waiter never cancels the call.
     */
    fun claudeParse(
        jobId: Long,
        parse: suspend () -> ImportResult,
    ): Deferred<ImportResult> =
        synchronized(parsesInFlight) {
            parsesInFlight[jobId]?.takeIf { it.isActive } ?: applicationScope.async { parse() }.also { d ->
                parsesInFlight[jobId] = d
                d.invokeOnCompletion { synchronized(parsesInFlight) { if (parsesInFlight[jobId] === d) parsesInFlight.remove(jobId) } }
            }
        }

    /**
     * A share or a paste: queue the import, or say there was no link. Text with no
     * link but several lines is read as the ingredients themselves, offline here
     * (Claude runs on the review screen, as for a page). Returns the job, if any.
     */
    suspend fun importShared(
        text: String?,
        subject: String?,
    ): Long? {
        val input = SharedText.parse(text, subject)
        val id =
            if (input != null) {
                importJobs.enqueue(input)
            } else {
                val lines = SharedText.lines(text)
                if (lines == null) {
                    offer(ShareOutcome.NoLink(synchronized(waitingShares) { ++shareSeq }))
                    return null
                }
                val title = subject?.trim()?.takeIf { it.isNotEmpty() }
                val parsed = importer.fromLines(lines, title, allowClaude = false, model = settings.model.first())
                importJobs.enqueueReady(ImportInput("", title), parsed)
            }
        queueReview(id)
        return id
    }

    /** The local calendar day, so "today" resets at your midnight. */
    fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()

    private suspend fun recordUsage(
        model: String,
        u: Usage,
    ) = repository.recordUsage(
        today().toString(),
        model,
        u.inputTokens,
        u.outputTokens,
        u.cacheWriteTokens,
        u.cacheReadTokens,
    )

    suspend fun usageHistory(): List<UsageRecord> =
        db.dao().usage().map {
            UsageRecord(LocalDate.parse(it.date), it.model, Usage(it.inputTokens, it.outputTokens, it.cacheWrite, it.cacheRead), it.calls)
        }

    /** Start-up housekeeping: the epoch marker, a catalogue re-key, the 7-day purge. */
    fun startUp(marker: EpochMarker) {
        // The first frame waits for the theme setting; start reading the settings file now,
        // while the activity is still being created, rather than when it first asks.
        applicationScope.launch { settings.theme.first() }
        applicationScope.launch {
            repository.checkEpoch(marker)
            repository.rekeyIfCatalogChanged()
            repository.purgeDeleted()
            importJobs.finishDiscards()
            repository.purgeOldJobs(clock() - JOB_KEEP_MS)
        }
        // The receiver first, so nothing the link hears is missed.
        watchSync.start()
        watchLink.start()
    }

    companion object {
        /** Finished and failed jobs are purged after this. */
        private const val JOB_KEEP_MS = 7 * 24 * 60 * 60 * 1000L
    }
}
