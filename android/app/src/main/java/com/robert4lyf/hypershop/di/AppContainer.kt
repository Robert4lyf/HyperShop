package com.robert4lyf.hypershop.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.work.WorkManager
import com.robert4lyf.hypershop.claude.ClaudeClient
import com.robert4lyf.hypershop.claude.GuardedClaude
import com.robert4lyf.hypershop.claude.RingLog
import com.robert4lyf.hypershop.claude.Usage
import com.robert4lyf.hypershop.claude.UsageRecord
import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.data.ClaudeUsageEntity
import com.robert4lyf.hypershop.data.EpochMarker
import com.robert4lyf.hypershop.data.HyperShopDatabase
import com.robert4lyf.hypershop.data.ListRepository
import com.robert4lyf.hypershop.extract.FetchPolicy
import com.robert4lyf.hypershop.extract.SafeFetcher
import com.robert4lyf.hypershop.importing.HyperShopWorkerFactory
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
import kotlinx.coroutines.launch
import timber.log.Timber
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

    val catalog: Catalog = CatalogLoader.default
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

    /** Shares and pastes waiting for the UI: a job to review, or "no link". */
    val shares = kotlinx.coroutines.flow.MutableStateFlow<ShareOutcome?>(null)

    /** A share or a paste: queue the import, or say there was no link. Returns the job, if any. */
    suspend fun importShared(
        text: String?,
        subject: String?,
    ): Long? {
        val input = SharedText.parse(text, subject)
        if (input == null) {
            shares.value = ShareOutcome.NoLink
            return null
        }
        val id = importJobs.enqueue(input)
        shares.value = ShareOutcome.Review(id)
        return id
    }

    /** The local calendar day, so "today" resets at your midnight. */
    fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()

    private suspend fun recordUsage(
        model: String,
        u: Usage,
    ) {
        val dao = db.dao()
        val date = today().toString()
        val row = dao.usageRow(date, model) ?: ClaudeUsageEntity(date, model, 0, 0, 0, 0, 0)
        dao.putUsage(
            row.copy(
                inputTokens = row.inputTokens + u.inputTokens,
                outputTokens = row.outputTokens + u.outputTokens,
                cacheWrite = row.cacheWrite + u.cacheWriteTokens,
                cacheRead = row.cacheRead + u.cacheReadTokens,
                calls = row.calls + 1,
            ),
        )
    }

    suspend fun usageHistory(): List<UsageRecord> =
        db.dao().usage().map {
            UsageRecord(LocalDate.parse(it.date), it.model, Usage(it.inputTokens, it.outputTokens, it.cacheWrite, it.cacheRead), it.calls)
        }

    /** Start-up housekeeping: the epoch marker, a catalogue re-key, the 7-day purge. */
    fun startUp(marker: EpochMarker) {
        applicationScope.launch {
            repository.checkEpoch(marker)
            repository.rekeyIfCatalogChanged()
            repository.purgeDeleted()
        }
        watchLink.start()
    }
}
