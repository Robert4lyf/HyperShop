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
import com.robert4lyf.hypershop.importing.Importer
import com.robert4lyf.hypershop.importing.ReviewGate
import com.robert4lyf.hypershop.importing.SharedText
import com.robert4lyf.hypershop.secrets.ApiKeyStore
import com.robert4lyf.hypershop.secrets.InMemoryApiKeyStore
import com.robert4lyf.hypershop.settings.Settings
import com.robert4lyf.hypershop.settings.settingsDataStore
import com.robert4lyf.hypershop.watch.NoWatchLink
import com.robert4lyf.hypershop.watch.WatchLink
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

    data object NoLink : ShareOutcome
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

    /**
     * The share or paste waiting for the UI: a job to review, or "no link". One at
     * a time: the UI calls [shareHandled] once it has acted on it, and the next
     * share that arrived meanwhile (two quick shares) takes its place.
     */
    val shares = MutableStateFlow<ShareOutcome?>(null)
    private val waitingShares = ArrayDeque<ShareOutcome>()

    private fun offer(outcome: ShareOutcome) {
        synchronized(waitingShares) { if (!shares.compareAndSet(null, outcome)) waitingShares.addLast(outcome) }
    }

    /** The UI has acted on [outcome]: the next waiting share, if any, is offered. */
    fun shareHandled(outcome: ShareOutcome) {
        synchronized(waitingShares) {
            if (shares.compareAndSet(outcome, null)) waitingShares.removeFirstOrNull()?.let { shares.value = it }
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
                    offer(ShareOutcome.NoLink)
                    return null
                }
                val title = subject?.trim()?.takeIf { it.isNotEmpty() }
                val parsed = importer.fromLines(lines, title, allowClaude = false, model = settings.model.first())
                importJobs.enqueueReady(ImportInput("", title), parsed)
            }
        offer(ShareOutcome.Review(id))
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
        }
        watchLink.start()
    }
}
