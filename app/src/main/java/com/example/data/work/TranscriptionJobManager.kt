package com.example.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.data.audio.AudioFileHelper
import com.example.data.local.TranscriptionChunkEntity
import com.example.data.local.TranscriptionJobDao
import com.example.data.local.TranscriptionJobEntity
import com.example.data.remote.BackendTranscriptionService
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AppSettings
import com.example.domain.AudioLanguage
import com.example.domain.ChunkStatus
import com.example.domain.JobStatus
import com.example.domain.SelectedAudioInfo
import com.example.domain.TranscriptionMode
import com.example.domain.TranscriptionProgress
import com.example.domain.TranscriptionStage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.util.UUID

class TranscriptionJobManager(
    private val context: Context,
    private val jobDao: TranscriptionJobDao,
    private val backendService: BackendTranscriptionService
) {

    private val workManager: WorkManager by lazy { WorkManager.getInstance(context) }

    suspend fun enqueueNewJob(
        audio: SelectedAudioInfo,
        audioLanguage: AudioLanguage,
        mode: TranscriptionMode,
        keepOriginalAudio: Boolean,
        settings: AppSettings
    ): String {
        val jobId = "job_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val ext = audio.displayName.substringAfterLast('.', "m4a")
        val (durableFile, fileSha256) = AudioFileHelper.copyToDurableJobSource(
            context = context,
            sourcePath = audio.localTempPath,
            jobId = jobId,
            extensionHint = ext
        )
        // Clean original cache temp copy once durable source copy is written
        if (audio.localTempPath.contains("/cache/") && audio.localTempPath != durableFile.absolutePath) {
            runCatching { File(audio.localTempPath).delete() }
        }

        val entity = TranscriptionJobEntity(
            jobId = jobId,
            sourceFilePath = durableFile.absolutePath,
            fileName = audio.displayName,
            mimeType = audio.mimeType,
            sizeBytes = audio.sizeBytes,
            durationMs = audio.durationMs,
            sampleRate = audio.sampleRate,
            channels = audio.channels,
            fileSha256 = fileSha256,
            languageCode = audioLanguage.code,
            modeId = mode.id,
            engineId = settings.engine.id,
            keepOriginalAudio = keepOriginalAudio,
            autoSaveTranscript = settings.saveTranscriptsAuto,
            status = JobStatus.QUEUED.name,
            stageName = "VALIDATING"
        )
        jobDao.insertJob(entity)
        enqueueWorker(jobId, ExistingWorkPolicy.REPLACE)
        return jobId
    }

    suspend fun resumeActiveJobIfNeeded(): String? {
        val active = jobDao.getLatestActiveJob() ?: return null
        enqueueWorker(active.jobId, ExistingWorkPolicy.KEEP)
        return active.jobId
    }

    suspend fun retryFailedChunks(jobId: String) {
        val job = jobDao.getJob(jobId) ?: return
        val chunks = jobDao.getChunksForJob(jobId)
        chunks.filter { it.status == ChunkStatus.FAILED.name || it.status == ChunkStatus.RETRYING.name }
            .forEach { failedChunk ->
                jobDao.updateChunk(
                    failedChunk.copy(
                        status = ChunkStatus.QUEUED.name,
                        errorDetail = null,
                        permanentError = false
                    )
                )
            }
        jobDao.updateJob(
            job.copy(
                status = JobStatus.QUEUED.name,
                stageName = "PROCESSING",
                failedChunks = 0,
                errorType = null,
                errorDetail = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        enqueueWorker(jobId, ExistingWorkPolicy.REPLACE)
    }

    suspend fun cancelJob(jobId: String, settings: AppSettings) {
        workManager.cancelUniqueWork(uniqueWorkName(jobId))
        val job = jobDao.getJob(jobId)
        if (job != null) {
            jobDao.updateJob(
                job.copy(
                    status = JobStatus.CANCELLED.name,
                    stageName = "CANCELLED",
                    updatedAt = System.currentTimeMillis()
                )
            )
            if (!job.remoteJobId.isNullOrBlank()) {
                backendService.deleteRemoteJob(settings.backendBaseUrl, settings.backendAuthToken, job.remoteJobId)
            }
        }
        AudioFileHelper.cleanupJobFiles(context, jobId)
    }

    suspend fun dismissErrorJob(jobId: String, settings: AppSettings) {
        val job = jobDao.getJob(jobId) ?: return
        jobDao.updateJob(
            job.copy(
                status = JobStatus.CANCELLED.name,
                updatedAt = System.currentTimeMillis()
            )
        )
        if (!job.remoteJobId.isNullOrBlank()) {
            backendService.deleteRemoteJob(settings.backendBaseUrl, settings.backendAuthToken, job.remoteJobId)
        }
        AudioFileHelper.cleanupJobFiles(context, jobId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeLatestProgress(): Flow<TranscriptionProgress> =
        jobDao.observeLatestJob().flatMapLatest { job ->
            if (job == null) {
                flowOf(TranscriptionProgress())
            } else {
                jobDao.observeChunksForJob(job.jobId).combine(flowOf(job)) { chunks, latestJob ->
                    mapToProgress(latestJob, chunks)
                }
            }
        }

    internal fun mapToProgress(
        job: TranscriptionJobEntity,
        chunks: List<TranscriptionChunkEntity>
    ): TranscriptionProgress {
        val status = JobStatus.fromName(job.status)
        val isRunning = status == JobStatus.QUEUED ||
            status == JobStatus.CHUNKING ||
            status == JobStatus.UPLOADING ||
            status == JobStatus.PROCESSING

        val total = job.totalChunks.coerceAtLeast(chunks.size)
        val completed = chunks.count { it.status == ChunkStatus.COMPLETED.name }
            .coerceAtLeast(job.completedChunks)
        val failedEntities = chunks.filter { it.status == ChunkStatus.FAILED.name }
        val failedCount = failedEntities.size.coerceAtLeast(job.failedChunks)
        val currentNum = (job.currentChunkIndex + 1).coerceIn(1, total.coerceAtLeast(1))

        val stage: TranscriptionStage = when {
            !isRunning -> TranscriptionStage.Idle
            job.stageName == "VALIDATING" -> TranscriptionStage.Validating
            job.stageName == "CHUNKING" || status == JobStatus.CHUNKING ->
                TranscriptionStage.Chunking(currentNum, total.coerceAtLeast(2))
            job.stageName == "UPLOADING" || status == JobStatus.UPLOADING ->
                TranscriptionStage.Uploading(currentNum, total.coerceAtLeast(1), job.uploadPercent)
            job.stageName == "CLEANING" -> TranscriptionStage.CleaningText
            else -> TranscriptionStage.Processing(currentNum, total.coerceAtLeast(1))
        }

        val errType = job.errorType?.let { runCatching { AppErrorType.valueOf(it) }.getOrNull() }
        val appError = if (status == JobStatus.PARTIAL_FAILED || status == JobStatus.FAILED) {
            AppError(errType ?: AppErrorType.TRANSCRIPTION_FAILED, job.errorDetail)
        } else {
            null
        }

        return TranscriptionProgress(
            jobId = job.jobId,
            isRunning = isRunning,
            jobStatus = status,
            fileName = job.fileName,
            stage = stage,
            uploadPercent = job.uploadPercent,
            totalChunks = total,
            completedChunks = completed,
            failedChunks = failedCount,
            currentChunkIndex = job.currentChunkIndex,
            etaSeconds = job.etaSeconds,
            failedChunkIndices = failedEntities.map { it.chunkIndex },
            canRetryFailedChunks = failedCount > 0 && completed > 0,
            resultTranscriptId = job.resultTranscriptId,
            error = appError
        )
    }

    private fun enqueueWorker(jobId: String, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_JOB_ID to jobId))
            .setConstraints(constraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(uniqueWorkName(jobId), policy, request)
    }

    private fun uniqueWorkName(jobId: String) = "audiotext_job_$jobId"
}
