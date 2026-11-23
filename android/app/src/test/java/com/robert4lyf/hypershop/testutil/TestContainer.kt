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
        // A second container in the same test (a backup and its restore): the earlier one's own background work
        // stops, as it always has; its database stays usable. Between tests [windDown] has already run.
        last?.let { previous ->
            runBlocking { withTimeoutOrNull(5_000) { previous.applicationScope.coroutineContext[kotlinx.coroutines.Job]?.cancelAndJoin() } }
        }
        val gate = GatedExecutor()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "ds-${System.nanoTime()}").apply { mkdirs() }
        lateinit var container: AppContainer
        container =
            AppContainer(
                context = context,
                db = HyperShopDatabase.inMemory(context, gate, onQuery),
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
        synchronized(made) { made += container to gate }
        return container
    }

    /**
     * Winds down every container built since the last call. [TestApplication] calls it after every test, once the
     * test's rules have torn the UI down and before Robolectric resets SQLite, so no test has to ask for it.
     *
     * First each container's own scope is stopped and waited for (while its database still answers, so cancelled
     * work can finish). Then its database's gate is shut: that waits for whatever is inside the database on any
     * thread (a view model's cancelled read, Room's refresh after a transaction) and lets nothing in afterwards.
     * Without this, such work ran on into the reset and failed whichever test came next ("Illegal connection
     * pointer"). The database itself is left open: closing it waits on refreshes the gate has turned away.
     */
    fun windDown() {
        val all = synchronized(made) { made.toList().also { made.clear() } }
        // Stop every scope first, then wait: work being cancelled may need the main thread to finish, and this is
        // the main thread, so its looper is kept turning while we wait.
        val jobs = all.mapNotNull { (c, _) -> c.applicationScope.coroutineContext[kotlinx.coroutines.Job] }
        jobs.forEach { it.cancel() }
        waitTurningTheLooper(5_000) { jobs.all { it.isCompleted } }
        for ((_, gate) in all) {
            if (!waitTurningTheLooper(5_000) { gate.shutWhenQuiet(20) }) {
                gate.shutNow()
                System.err.println("TestContainer.windDown: a database was still busy after 5 s; its gate was shut anyway")
                System.err.println(gate.whoIsInside())
            }
        }
        last = null
    }

    /** True once [done] is; false after [timeoutMs]. Runs what is waiting on the main looper between tries. */
    private fun waitTurningTheLooper(
        timeoutMs: Long,
        done: () -> Boolean,
    ): Boolean {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            if (done()) return true
            if (System.nanoTime() > end) return false
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            Thread.sleep(5)
        }
    }

    /** Every container built since the last [windDown], with its database's gate: a test may use two at once. */
    private val made = mutableListOf<Pair<AppContainer, GatedExecutor>>()

    /** The container [create] made last in this test. */
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

/**
 * Room's executor for one test database. It runs each piece of work at once on the calling thread, as the plain
 * `{ it.run() }` did, and knows who is inside: [shutWhenQuiet] waits for them and turns away everything that comes later.
 * Work turned away belongs to a test that has ended (a cancelled coroutine's last step); it is dropped, not failed.
 */
class GatedExecutor : java.util.concurrent.Executor {
    private val lock = java.util.concurrent.locks.ReentrantReadWriteLock()

    @Volatile private var shut = false
    private val holders = java.util.concurrent.ConcurrentHashMap.newKeySet<Thread>()

    override fun execute(task: Runnable) {
        // Shared and re-entrant: a transaction runs its steps through this executor again on its own thread.
        val inside = lock.readLock()
        inside.lock()
        val me = Thread.currentThread()
        val outermost = holders.add(me)
        try {
            if (!shut) {
                // The leak check: slower background database work makes work left running at a test's end likely.
                if (STRESS_MS > 0 && Thread.currentThread().name.startsWith("DefaultDispatcher")) Thread.sleep(STRESS_MS)
                task.run()
            }
        } finally {
            if (outermost) holders.remove(me)
            inside.unlock()
        }
    }

    /** Where the threads now inside the database are, for the message when one will not leave. */
    fun whoIsInside(): String =
        holders.joinToString("\n") { t -> "  ${t.name}:\n" + t.stackTrace.take(40).joinToString("\n") { "    at $it" } }

    /** Shuts the gate once nothing is inside; false (and still open) when work was inside for all of [waitMs]. */
    fun shutWhenQuiet(waitMs: Long): Boolean {
        val alone = lock.writeLock()
        if (!alone.tryLock(waitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) return false
        shut = true
        alone.unlock()
        return true
    }

    /** Shuts the gate whatever is inside: nothing new gets in. */
    fun shutNow() {
        shut = true
    }

    private companion object {
        /** `-Dhs.stress=<ms>` on the test JVM (docs/KNOWN_ISSUES.md): a delay before each background database step. */
        val STRESS_MS: Long = System.getProperty("hs.stress")?.toLongOrNull() ?: 0
    }
}
