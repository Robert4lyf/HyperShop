package com.robert4lyf.hypershop.testutil

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.robert4lyf.hypershop.data.HyperShopDatabase
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.extract.FetchPolicy
import com.robert4lyf.hypershop.importing.Job
import com.robert4lyf.hypershop.importing.JobState
import com.robert4lyf.hypershop.secrets.InMemoryApiKeyStore
import com.robert4lyf.hypershop.watch.FakeWatchLink
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockWebServer
import java.io.File

/**
 * A container for tests: in-memory Room, a throwaway DataStore, an in-memory
 * key, a fake watch, fetching allowed only from [pages] (MockWebServer on
 * loopback), and Claude pointed at [claude].
 */
object TestContainer {
    fun create(
        pages: MockWebServer? = null,
        claude: MockWebServer? = null,
        key: String? = null,
        clock: () -> Long = ticking(),
        ready: Boolean = true,
        /** Every SQL statement as it runs (QueryBudgetTest). */
        onQuery: ((String) -> Unit)? = null,
    ): AppContainer {
        // The last test's container may still be finishing work on its own scope (a view model cleared at the end
        // of a test discards its import, which cancels WorkManager work). Left running, that write lands in the
        // next test's freshly reset WorkManager database and fails whichever test runs next ("Illegal connection
        // pointer"). Wind it down first, so every test starts from quiet.
        last?.let { previous ->
            runBlocking { withTimeoutOrNull(5_000) { previous.applicationScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() } }
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "ds-${System.nanoTime()}").apply { mkdirs() }
        lateinit var container: AppContainer
        container =
            AppContainer(
                context = context,
                db = HyperShopDatabase.inMemory(context, { it.run() }, onQuery),
                dataStore = PreferenceDataStoreFactory.create { File(dir, "settings.preferences_pb") },
                apiKeyStore = InMemoryApiKeyStore(key),
                fetchPolicy = pages?.let { loopbackOnly(it.port) } ?: FetchPolicy.STRICT,
                claudeBaseUrl = claude?.url("")?.toString()?.trimEnd('/') ?: "http://127.0.0.1:9",
                watchLink = FakeWatchLink(),
                clock = clock,
                workManager = { WorkManager.getInstance(context) },
            )
        val config =
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(container.workerFactory)
                .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, config)
        // Tests that do not go through startUp start ready; StartupTest covers the barrier itself.
        if (ready) container.startup.value = com.robert4lyf.hypershop.di.Startup.Ready
        last = container
        return container
    }

    /** The container [create] made last: wound down before the next one is built. */
    @Volatile private var last: AppContainer? = null
}

/** Waits for a background import to finish (CoroutineWorker runs off the test thread). */
suspend fun AppContainer.awaitJob(
    id: Long,
    timeoutMs: Long = 10_000,
): Job =
    withTimeout(timeoutMs) {
        var j = importJobs.get(id)!!
        while (j.state == JobState.QUEUED || j.state == JobState.RUNNING) {
            delay(20)
            j = importJobs.get(id)!!
        }
        j
    }

private fun loopbackOnly(port: Int) = FetchPolicy(allowHttp = true, allowLoopback = true, allowedPorts = setOf(port))

/** A clock that moves on a millisecond per reading, so undo stamps never collide. */
fun ticking(start: Long = 1_760_000_000_000L): () -> Long {
    val t = java.util.concurrent.atomic.AtomicLong(start)
    return { t.getAndIncrement() }
}
