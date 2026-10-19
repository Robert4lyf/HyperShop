package com.robert4lyf.hypershop.importing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.di.AppContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Runs one import in the background: fetch, extract and rule-parse. It calls
 * Claude only if the review screen is in the foreground at that moment;
 * otherwise the result is "Claude pending" and the review screen calls Claude
 * when it opens.
 */
class ImportWorker(
    context: Context,
    params: WorkerParameters,
    private val container: AppContainer,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val jobId = inputData.getLong(KEY_JOB, -1)
        if (jobId < 0) return Result.failure()
        val jobs = container.importJobs
        val job = jobs.get(jobId) ?: return Result.failure()
        // Only a waiting job runs: a WorkManager re-run of one already reviewed or ready
        // must not replace its result (a saved Claude parse would be paid for again).
        if (!jobs.claim(jobId)) return Result.success()
        val input = job.input
        val result =
            try {
                container.importer.fromUrl(
                    url = input.url,
                    fallbackTitle = input.subject,
                    allowClaude = container.reviewGate.visible.value,
                    model = container.settings.model.first(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Never leave the job RUNNING: the review screen would wait on it for ever. That
                // includes an OutOfMemoryError or StackOverflowError on a hostile page (PLAN §3.1:
                // those go to "Select lines manually"); WorkManager would not run the job again.
                ImportResult(input.url, input.subject, null, UnitLocale.METRIC, "MANUAL", error = ImportError.NO_RECIPE)
            }
        jobs.finishRun(jobId, result)
        return Result.success()
    }

    companion object {
        const val KEY_JOB = "job"

        fun input(jobId: Long): Data = Data.Builder().putLong(KEY_JOB, jobId).build()
    }
}

/** WorkManager's factory, built from the container (PLAN §5.3). */
class HyperShopWorkerFactory(
    private val container: () -> AppContainer,
) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? =
        when (workerClassName) {
            ImportWorker::class.java.name -> ImportWorker(appContext, workerParameters, container())
            else -> null
        }
}
