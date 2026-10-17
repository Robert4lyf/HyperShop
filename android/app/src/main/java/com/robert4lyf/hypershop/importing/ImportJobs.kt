package com.robert4lyf.hypershop.importing

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.robert4lyf.hypershop.data.ImportJobEntity
import com.robert4lyf.hypershop.data.ListDao
import kotlinx.coroutines.flow.Flow
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
        val req = OneTimeWorkRequestBuilder<ImportWorker>().setInputData(ImportWorker.input(id)).build()
        workManager().enqueueUniqueWork("import-$id", ExistingWorkPolicy.KEEP, req)
        return id
    }

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

    suspend fun setState(
        id: Long,
        state: JobState,
    ) {
        val j = dao.job(id) ?: return
        dao.putJob(j.copy(state = state.name))
    }

    suspend fun finish(
        id: Long,
        result: ImportResult,
    ) {
        val j = dao.job(id) ?: return
        val state = if (result.error != null && result.lines.isEmpty() && result.pageText == null) JobState.FAILED else JobState.READY
        dao.putJob(
            j.copy(state = state.name, resultJson = json.encodeToString(ImportResult.serializer(), result), error = result.error?.name),
        )
    }

    suspend fun done(id: Long) = setState(id, JobState.DONE)

    val ready: Flow<List<Job>> = dao.observeJobs(JobState.READY.name).map { it.map(::map) }

    private fun map(e: ImportJobEntity) =
        Job(
            e.id,
            JobState.valueOf(e.state),
            json.decodeFromString(ImportInput.serializer(), e.inputRefs),
            e.resultJson?.let { json.decodeFromString(ImportResult.serializer(), it) },
            e.createdAt,
        )
}
