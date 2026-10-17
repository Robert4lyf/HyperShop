package com.robert4lyf.hypershop.importing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.robert4lyf.hypershop.di.AppContainer
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
        jobs.setState(jobId, JobState.RUNNING)
        val input = job.input
        val result =
            container.importer.fromUrl(
                url = input.url,
                fallbackTitle = input.subject,
                allowClaude = container.reviewGate.visible.value,
                model = container.settings.model.first(),
            )
        jobs.finish(jobId, result)
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
