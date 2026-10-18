package com.robert4lyf.hypershop.importing

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.robert4lyf.hypershop.data.ImportJobEntity
import com.robert4lyf.hypershop.data.ListDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ImportInput(
    val url: String,
    val subject: String? = null,
)

data class Job(
    val id: Long,
    val state: JobState,
    val input: ImportInput,
    val result: ImportResult?,
    val createdAt: Long,
)

/**
 * `ImportJob` rows: an import survives the process being killed between the
 * share and the review (PLAN §3.1). The "1 recipe ready to review" card reads
 * [ready].
 */
class ImportJobs(
    private val dao: ListDao,
    private val workManager: () -> WorkManager,
    private val clock: () -> Long,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun enqueue(input: ImportInput): Long {
        val id =
            dao.insertJob(
                ImportJobEntity(0, JobState.QUEUED.name, json.encodeToString(ImportInput.serializer(), input), null, null, clock()),
            )
        schedule(id)
        return id
    }

    /** Unique per job, so calling it again (see [ensureScheduled]) never runs a job twice. */
    private fun schedule(id: Long) {
        val req = OneTimeWorkRequestBuilder<ImportWorker>().setInputData(ImportWorker.input(id)).build()
        workManager().enqueueUniqueWork("import-$id", ExistingWorkPolicy.KEEP, req)
    }

    /**
     * A job still QUEUED when its review opens is scheduled again: the process may
     * have died between saving the job and handing it to WorkManager.
     */
    suspend fun ensureScheduled(id: Long) {
        if (dao.job(id)?.state == JobState.QUEUED.name) schedule(id)
    }

    /** The worker's claim; false when the job is already finished (a WorkManager re-run). */
    suspend fun claim(id: Long): Boolean = dao.claimJob(id) > 0

    /** A job that needs no fetch, straight to review ("Add the in-stock items now"). */
    suspend fun enqueueReady(
        input: ImportInput,
        result: ImportResult,
    ): Long =
        dao.insertJob(
            ImportJobEntity(
                0,
                JobState.READY.name,
                json.encodeToString(ImportInput.serializer(), input),
                json.encodeToString(ImportResult.serializer(), result),
                null,
                clock(),
            ),
        )

    suspend fun get(id: Long): Job? = dao.job(id)?.let(::map)

    /** The job once the worker is done with it (or null when there is no such job). */
    suspend fun awaitFinished(id: Long): Job? =
        dao.observeJob(id).first { it == null || (it.state != JobState.QUEUED.name && it.state != JobState.RUNNING.name) }?.let(::map)

    suspend fun setState(
        id: Long,
        state: JobState,
    ) {
        dao.setJobState(id, state.name)
    }

    suspend fun finish(
        id: Long,
        result: ImportResult,
    ) {
        val state = if (result.error != null && result.lines.isEmpty() && result.pageText == null) JobState.FAILED else JobState.READY
        // One UPDATE, not a read then a write that could undo a concurrent change.
        dao.finishJob(id, state.name, json.encodeToString(ImportResult.serializer(), result), result.error?.name)
    }

    suspend fun done(id: Long) = setState(id, JobState.DONE)

    /**
     * Jobs ready to review, for the list's card: their results are not decoded (a
     * page's text can be tens of KB and this re-emits with every job change).
     */
    val ready: Flow<List<Job>> = dao.observeJobs(JobState.READY.name).map { rows -> rows.map { map(it, withResult = false) } }

    private fun map(
        e: ImportJobEntity,
        withResult: Boolean = true,
    ) = Job(
        e.id,
        JobState.valueOf(e.state),
        json.decodeFromString(ImportInput.serializer(), e.inputRefs),
        e.resultJson?.takeIf { withResult }?.let { json.decodeFromString(ImportResult.serializer(), it) },
        e.createdAt,
    )
}
