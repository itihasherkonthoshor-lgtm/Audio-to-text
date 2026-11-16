package com.example.data.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.example.data.audio.AudioChunk
import com.example.data.audio.AudioChunker
import com.example.data.audio.AudioFileException
import com.example.data.audio.AudioFileHelper
import com.example.data.local.TranscriptEntity
import com.example.data.local.TranscriptRepository
import com.example.data.local.TranscriptionChunkEntity
import com.example.data.local.TranscriptionJobDao
import com.example.data.local.TranscriptionJobEntity
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AppSettings
import com.example.domain.AudioLanguage
import com.example.domain.ChunkStatus
import com.example.domain.JobStatus
import com.example.domain.TranscriptChunkResult
import com.example.domain.TranscriptionEngine
import com.example.domain.TranscriptionMode
import com.example.domain.TranscriptionStage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.min
import kotlin.random.Random

private const val TAG = "JobCoordinator"

class TranscriptionCoordinator(
    private val context: Context,
    private val jobDao: TranscriptionJobDao,
    private val transcriptRepo: TranscriptRepository,
    private val firebaseService: FirebaseAiTranscriptionService,
    private val backendService: BackendTranscriptionService
) {

    suspend fun executeDurableJob(
        jobId: String,
        settings: AppSettings,
        onStatusNotification: (title: String, message: String) -> Unit = { _, _ -> }
    ) {
        val initialJob = jobDao.getJob(jobId) ?: return
        if (initialJob.status == JobStatus.COMPLETED.name || initialJob.status == JobStatus.CANCELLED.name) {
            return
        }

        if (!isNetworkAvailable()) {
            markJobFailed(
                job = initialJob,
                status = if (initialJob.completedChunks > 0) JobStatus.PARTIAL_FAILED else JobStatus.FAILED,
                error = AppError(AppErrorType.NO_INTERNET)
            )
            return
        }

        val audioLang = AudioLanguage.fromCode(initialJob.languageCode)
        val mode = TranscriptionMode.fromId(initialJob.modeId)
        val engine = TranscriptionEngine.fromId(initialJob.engineId)

        onStatusNotification(initialJob.fileName, "Preparing audio...")

        // Step 1: Ensure chunks exist in Room & durable disk storage
        var chunkEntities = jobDao.getChunksForJob(jobId)
        if (chunkEntities.isEmpty()) {
            val sourceFile = File(initialJob.sourceFilePath)
            if (!sourceFile.exists() || sourceFile.length() <= 0L) {
                markJobFailed(initialJob, JobStatus.FAILED, AppError(AppErrorType.EMPTY_AUDIO))
                return
            }

            jobDao.updateJob(
                initialJob.copy(
                    status = JobStatus.CHUNKING.name,
                    stageName = "CHUNKING",
                    errorType = null,
                    errorDetail = null,
                    updatedAt = System.currentTimeMillis()
                )
            )

            val maxChunkBytes = when (engine) {
                TranscriptionEngine.FIREBASE_GEMINI -> 1L * 1024L * 1024L
                TranscriptionEngine.CUSTOM_BACKEND_OPENAI -> 8L * 1024L * 1024L
            }
            val maxChunkDurationMs = when (engine) {
                TranscriptionEngine.FIREBASE_GEMINI -> 30_000L
                TranscriptionEngine.CUSTOM_BACKEND_OPENAI -> 120_000L
            }

            val prepared = AudioChunker.prepareChunks(
                context = context,
                sourcePath = initialJob.sourceFilePath,
                mimeType = initialJob.mimeType,
                jobId = jobId,
                maxChunkBytes = maxChunkBytes,
                maxChunkDurationMs = maxChunkDurationMs
            ) { current, total ->
                onStatusNotification(initialJob.fileName, "Splitting $current/$total")
            }

            chunkEntities = prepared.map { chunk ->
                TranscriptionChunkEntity(
                    jobId = jobId,
                    chunkIndex = chunk.index,
                    filePath = chunk.file.absolutePath,
                    mimeType = chunk.mimeType,
                    sizeBytes = chunk.file.length(),
                    sha256 = chunk.sha256.ifBlank { AudioFileHelper.computeSha256Hex(chunk.file) },
                    startOffsetMs = chunk.startOffsetMs,
                    durationMs = chunk.durationMs,
                    overlapBeforeMs = chunk.overlapBeforeMs,
                    isTemporary = chunk.isTemporary,
                    status = ChunkStatus.QUEUED.name
                )
            }
            jobDao.insertChunks(chunkEntities)
            jobDao.updateJob(
                (jobDao.getJob(jobId) ?: initialJob).copy(
                    totalChunks = chunkEntities.size,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // Step 2: Execute via selected engine
        try {
            when (engine) {
                TranscriptionEngine.FIREBASE_GEMINI -> {
                    executeFirebaseChunkPipeline(jobId, audioLang, mode, onStatusNotification)
                }
                TranscriptionEngine.CUSTOM_BACKEND_OPENAI -> {
                    executeCustomBackendAsyncJob(jobId, audioLang, mode, settings, onStatusNotification)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioFileException) {
            val latest = jobDao.getJob(jobId) ?: return
            val hasCompleted = latest.completedChunks > 0
            markJobFailed(
                job = latest,
                status = if (hasCompleted) JobStatus.PARTIAL_FAILED else JobStatus.FAILED,
                error = e.appError
            )
        } catch (e: Exception) {
            Log.e(TAG, "Job $jobId crashed unexpectedly", e)
            val latest = jobDao.getJob(jobId) ?: return
            val hasCompleted = latest.completedChunks > 0
            markJobFailed(
                job = latest,
                status = if (hasCompleted) JobStatus.PARTIAL_FAILED else JobStatus.FAILED,
                error = AppError(AppErrorType.TRANSCRIPTION_FAILED, e.localizedMessage)
            )
        }
    }

    private suspend fun executeFirebaseChunkPipeline(
        jobId: String,
        audioLang: AudioLanguage,
        mode: TranscriptionMode,
        onStatusNotification: (String, String) -> Unit
    ) {
        val job = jobDao.getJob(jobId) ?: return
        var chunks = jobDao.getChunksForJob(jobId).sortedBy { it.chunkIndex }
        val total = chunks.size

        jobDao.updateJob(
            job.copy(
                status = JobStatus.PROCESSING.name,
                stageName = "PROCESSING",
                errorType = null,
                errorDetail = null,
                updatedAt = System.currentTimeMillis()
            )
        )

        var previousContext = ""
        for (entity in chunks) {
            val freshJob = jobDao.getJob(jobId) ?: return
            if (freshJob.status == JobStatus.CANCELLED.name) return

            if (entity.status == ChunkStatus.COMPLETED.name && !entity.exactText.isNullOrBlank()) {
                previousContext = entity.exactText.takeLast(220)
                continue
            }

            val audioChunk = AudioChunk(
                index = entity.chunkIndex,
                file = File(entity.filePath),
                mimeType = entity.mimeType,
                startOffsetMs = entity.startOffsetMs,
                durationMs = entity.durationMs,
                overlapBeforeMs = entity.overlapBeforeMs,
                sha256 = entity.sha256,
                isTemporary = entity.isTemporary
            )

            jobDao.updateJob(
                freshJob.copy(
                    currentChunkIndex = entity.chunkIndex,
                    stageName = "PROCESSING",
                    updatedAt = System.currentTimeMillis()
                )
            )
            onStatusNotification(freshJob.fileName, "Chunk ${entity.chunkIndex + 1} / $total")

            val result = transcribeFirebaseChunkIsolated(
                jobId = jobId,
                entity = entity,
                chunk = audioChunk,
                audioLang = audioLang,
                mode = mode,
                previousContext = previousContext
            )

            if (result != null) {
                previousContext = result.exactText.takeLast(220)
                if (entity.isTemporary) {
                    runCatching { File(entity.filePath).delete() }
                }
            }
        }

        // Verify completion vs partial failure
        chunks = jobDao.getChunksForJob(jobId).sortedBy { it.chunkIndex }
        val completedCount = chunks.count { it.status == ChunkStatus.COMPLETED.name }
        val failedList = chunks.filter { it.status == ChunkStatus.FAILED.name }
        val currentJob = jobDao.getJob(jobId) ?: return

        if (failedList.isNotEmpty() || completedCount < chunks.size) {
            val firstErr = failedList.firstOrNull()?.errorDetail ?: "Some chunks failed"
            jobDao.updateJob(
                currentJob.copy(
                    status = if (completedCount > 0) JobStatus.PARTIAL_FAILED.name else JobStatus.FAILED.name,
                    completedChunks = completedCount,
                    failedChunks = failedList.size,
                    errorType = AppErrorType.TRANSCRIPTION_FAILED.name,
                    errorDetail = "${failedList.size} chunk(s) failed: $firstErr",
                    updatedAt = System.currentTimeMillis()
                )
            )
            return
        }

        // Merge all chunks cleanly in exact order
        val pairs = chunks.map { entity ->
            val chunk = AudioChunk(
                index = entity.chunkIndex,
                file = File(entity.filePath),
                mimeType = entity.mimeType,
                startOffsetMs = entity.startOffsetMs,
                durationMs = entity.durationMs,
                overlapBeforeMs = entity.overlapBeforeMs,
                sha256 = entity.sha256,
                isTemporary = entity.isTemporary
            )
            val res = TranscriptChunkResult(
                exactText = entity.exactText.orEmpty(),
                cleanText = entity.cleanText,
                detectedLanguage = entity.detectedLanguage ?: audioLang.code,
                segments = transcriptRepo.decodeSegments(entity.segmentsJson)
            )
            chunk to res
        }
        var merged = AudioChunker.mergeChunkResults(pairs)

        if (mode == TranscriptionMode.CLEAN && merged.cleanText.isNullOrBlank()) {
            jobDao.updateJob(
                currentJob.copy(stageName = "CLEANING", updatedAt = System.currentTimeMillis())
            )
            val clean = firebaseService.generateCleanFromExact(merged.exactText, merged.detectedLanguage)
            merged = merged.copy(cleanText = clean)
        }

        finalizeSuccessfulTranscript(currentJob, merged, onStatusNotification)
    }

    private suspend fun transcribeFirebaseChunkIsolated(
        jobId: String,
        entity: TranscriptionChunkEntity,
        chunk: AudioChunk,
        audioLang: AudioLanguage,
        mode: TranscriptionMode,
        previousContext: String
    ): TranscriptChunkResult? {
        val maxRetries = 3
        var attempt = 0
        while (true) {
            val startedAt = System.currentTimeMillis()
            jobDao.updateChunk(
                entity.copy(
                    status = if (attempt == 0) ChunkStatus.PROCESSING.name else ChunkStatus.RETRYING.name,
                    retryCount = attempt,
                    errorDetail = null
                )
            )
            try {
                val res = firebaseService.transcribeChunk(chunk, audioLang, mode, previousContext)
                val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1L)
                jobDao.updateChunk(
                    entity.copy(
                        status = ChunkStatus.COMPLETED.name,
                        retryCount = attempt,
                        exactText = res.exactText,
                        cleanText = res.cleanText,
                        detectedLanguage = res.detectedLanguage,
                        segmentsJson = transcriptRepo.encodeSegments(res.segments),
                        processingDurationMs = elapsed,
                        errorDetail = null,
                        permanentError = false
                    )
                )
                refreshJobMetrics(jobId)
                return res
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioFileException) {
                val permanent = e.appError.type == AppErrorType.API_AUTH_FAILURE ||
                    e.appError.type == AppErrorType.EMPTY_AUDIO ||
                    e.appError.type == AppErrorType.UNSUPPORTED_FORMAT ||
                    e.appError.type == AppErrorType.FILE_TOO_LARGE
                if (permanent || attempt >= maxRetries) {
                    jobDao.updateChunk(
                        entity.copy(
                            status = ChunkStatus.FAILED.name,
                            retryCount = attempt,
                            errorDetail = e.appError.detail ?: e.appError.type.name,
                            permanentError = permanent
                        )
                    )
                    refreshJobMetrics(jobId)
                    return null
                }
                attempt++
                val backoff = min(30_000L, 1_500L * (1L shl (attempt - 1))) + Random.nextLong(100, 600)
                Log.w(TAG, "Chunk ${chunk.index} transient error (${e.appError.type}), retry #$attempt in ${backoff}ms")
                delay(backoff)
            }
        }
    }

    private suspend fun executeCustomBackendAsyncJob(
        jobId: String,
        audioLang: AudioLanguage,
        mode: TranscriptionMode,
        settings: AppSettings,
        onStatusNotification: (String, String) -> Unit
    ) {
        val baseUrl = settings.backendBaseUrl
        val token = settings.backendAuthToken
        var job = jobDao.getJob(jobId) ?: return
        var chunks = jobDao.getChunksForJob(jobId).sortedBy { it.chunkIndex }

        // 1. Create or resume remote async job (idempotent on clientRequestId = jobId)
        val remoteJobId = job.remoteJobId ?: run {
            val created = backendService.createOrResumeJob(
                baseUrl = baseUrl,
                authToken = token,
                clientRequestId = jobId,
                fileName = job.fileName,
                mimeType = job.mimeType,
                totalBytes = job.sizeBytes,
                durationMs = job.durationMs,
                sampleRate = job.sampleRate,
                channels = job.channels,
                fileSha256 = job.fileSha256,
                language = audioLang,
                mode = mode,
                totalChunks = chunks.size
            )
            jobDao.updateJob(job.copy(remoteJobId = created.jobId, updatedAt = System.currentTimeMillis()))
            created.jobId
        }

        // 2. Check already-uploaded chunks on backend so interrupted uploads skip completed chunks cleanly
        val uploadStatus = backendService.getUploadStatus(baseUrl, token, remoteJobId)
        val alreadyOnServer = uploadStatus.uploadedChunkIndices

        val totalUploadBytes = chunks.sumOf { it.sizeBytes }.coerceAtLeast(1L)
        var bytesDoneBeforeCurrent = 0L

        job = jobDao.getJob(jobId) ?: return
        jobDao.updateJob(
            job.copy(
                status = JobStatus.UPLOADING.name,
                stageName = "UPLOADING",
                errorType = null,
                errorDetail = null,
                updatedAt = System.currentTimeMillis()
            )
        )

        for (entity in chunks) {
            val latest = jobDao.getJob(jobId) ?: return
            if (latest.status == JobStatus.CANCELLED.name) {
                backendService.deleteRemoteJob(baseUrl, token, remoteJobId)
                return
            }

            if (entity.status == ChunkStatus.COMPLETED.name || entity.chunkIndex in alreadyOnServer) {
                bytesDoneBeforeCurrent += entity.sizeBytes
                if (entity.status == ChunkStatus.QUEUED.name) {
                    jobDao.updateChunk(entity.copy(status = ChunkStatus.UPLOADED.name))
                }
                continue
            }

            val audioChunk = AudioChunk(
                index = entity.chunkIndex,
                file = File(entity.filePath),
                mimeType = entity.mimeType,
                startOffsetMs = entity.startOffsetMs,
                durationMs = entity.durationMs,
                overlapBeforeMs = entity.overlapBeforeMs,
                sha256 = entity.sha256,
                isTemporary = entity.isTemporary
            )

            jobDao.updateChunk(entity.copy(status = ChunkStatus.UPLOADING.name))
            onStatusNotification(latest.fileName, "Uploading ${entity.chunkIndex + 1}/${chunks.size}")

            uploadSingleChunkWithRetry(
                baseUrl = baseUrl,
                token = token,
                remoteJobId = remoteJobId,
                chunk = audioChunk
            ) { sentInChunk, _, _ ->
                val globalPct = (((bytesDoneBeforeCurrent + sentInChunk) * 100L) / totalUploadBytes)
                    .toInt()
                    .coerceIn(0, 100)
                // Throttle Room writes inside progress callback lightly
                if (globalPct != job.uploadPercent && globalPct % 5 == 0) {
                    // Best-effort lightweight notification update handled cleanly below
                }
            }

            bytesDoneBeforeCurrent += entity.sizeBytes
            val pct = ((bytesDoneBeforeCurrent * 100L) / totalUploadBytes).toInt().coerceIn(0, 100)
            jobDao.updateChunk(entity.copy(status = ChunkStatus.UPLOADED.name))
            jobDao.updateJob(
                (jobDao.getJob(jobId) ?: latest).copy(
                    uploadPercent = pct,
                    currentChunkIndex = entity.chunkIndex,
                    updatedAt = System.currentTimeMillis()
                )
            )
        }

        // 3. Enqueue (or retry failed chunks) asynchronously — immediate 202 response!
        val hasFailedLocal = chunks.any { it.status == ChunkStatus.FAILED.name }
        if (hasFailedLocal) {
            backendService.retryFailedChunks(baseUrl, token, remoteJobId)
        } else {
            backendService.enqueueJob(baseUrl, token, remoteJobId)
        }

        job = jobDao.getJob(jobId) ?: return
        jobDao.updateJob(
            job.copy(
                status = JobStatus.PROCESSING.name,
                stageName = "PROCESSING",
                uploadPercent = 100,
                updatedAt = System.currentTimeMillis()
            )
        )

        // 4. Adaptive polling loop (no hard 60s timeout — tracks active progress and handles transient drops!)
        var pollDelayMs = 1500L
        var consecutivePollErrors = 0
        while (true) {
            val latestLocal = jobDao.getJob(jobId) ?: return
            if (latestLocal.status == JobStatus.CANCELLED.name) {
                backendService.deleteRemoteJob(baseUrl, token, remoteJobId)
                return
            }

            val remoteStatus = try {
                val st = backendService.getJobStatus(baseUrl, token, remoteJobId)
                consecutivePollErrors = 0
                st
            } catch (e: CancellationException) {
                throw e
            } catch (e: AudioFileException) {
                if (e.appError.type == AppErrorType.API_AUTH_FAILURE) throw e
                consecutivePollErrors++
                if (consecutivePollErrors >= 12) throw e
                val backoff = min(20_000L, 1_500L * (1L shl min(4, consecutivePollErrors))) + Random.nextLong(200, 800)
                delay(backoff)
                continue
            }

            // Sync remote chunk states into Room
            val currentLocalChunks = jobDao.getChunksForJob(jobId).associateBy { it.chunkIndex }
            remoteStatus.chunks.forEach { rc ->
                currentLocalChunks[rc.index]?.let { local ->
                    if (local.status != rc.status.name || local.errorDetail != rc.error || local.retryCount != rc.retryCount) {
                        jobDao.updateChunk(
                            local.copy(
                                status = rc.status.name,
                                retryCount = rc.retryCount,
                                errorDetail = rc.error
                            )
                        )
                    }
                }
            }

            jobDao.updateJob(
                latestLocal.copy(
                    status = remoteStatus.status.name,
                    stageName = if (remoteStatus.status == JobStatus.QUEUED) "PROCESSING" else remoteStatus.status.name,
                    totalChunks = remoteStatus.totalChunks.coerceAtLeast(latestLocal.totalChunks),
                    completedChunks = remoteStatus.completedChunks,
                    failedChunks = remoteStatus.failedChunks,
                    currentChunkIndex = remoteStatus.currentChunkIndex.coerceAtLeast(0),
                    etaSeconds = remoteStatus.etaSeconds,
                    updatedAt = System.currentTimeMillis()
                )
            )

            onStatusNotification(
                latestLocal.fileName,
                "Chunks ${remoteStatus.completedChunks}/${remoteStatus.totalChunks}"
            )

            when (remoteStatus.status) {
                JobStatus.COMPLETED -> {
                    val merged = TranscriptChunkResult(
                        exactText = remoteStatus.exactTranscript.orEmpty(),
                        cleanText = remoteStatus.cleanTranscript,
                        detectedLanguage = remoteStatus.detectedLanguage,
                        segments = remoteStatus.segments
                    )
                    finalizeSuccessfulTranscript(latestLocal, merged, onStatusNotification)
                    backendService.deleteRemoteJob(baseUrl, token, remoteJobId)
                    return
                }
                JobStatus.PARTIAL_FAILED, JobStatus.FAILED -> {
                    jobDao.updateJob(
                        latestLocal.copy(
                            status = remoteStatus.status.name,
                            completedChunks = remoteStatus.completedChunks,
                            failedChunks = remoteStatus.failedChunks,
                            errorType = AppErrorType.TRANSCRIPTION_FAILED.name,
                            errorDetail = remoteStatus.error,
                            updatedAt = System.currentTimeMillis()
                        )
                    )
                    return
                }
                JobStatus.CANCELLED -> return
                else -> {
                    delay(pollDelayMs)
                    pollDelayMs = min(4_000L, pollDelayMs + 400L)
                }
            }
        }
    }

    private suspend fun uploadSingleChunkWithRetry(
        baseUrl: String,
        token: String,
        remoteJobId: String,
        chunk: AudioChunk,
        onBytesProgress: (Long, Long, Int) -> Unit
    ) {
        var attempt = 0
        while (true) {
            try {
                backendService.uploadChunkStreaming(baseUrl, token, remoteJobId, chunk, onBytesProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackendHttpException) {
                if (e.httpCode in listOf(400, 401, 403, 413, 415) || attempt >= 3) throw e
                attempt++
                val backoff = maxOf(e.retryAfterMs, 1_000L * (1L shl (attempt - 1))) + Random.nextLong(100, 500)
                delay(backoff)
            } catch (e: AudioFileException) {
                if (attempt >= 3) throw e
                attempt++
                delay(1_200L * attempt)
            }
        }
    }

    private suspend fun finalizeSuccessfulTranscript(
        job: TranscriptionJobEntity,
        result: TranscriptChunkResult,
        onStatusNotification: (String, String) -> Unit
    ) {
        val ext = job.fileName.substringAfterLast('.', "m4a")
        val savedAudioPath = if (job.keepOriginalAudio) {
            AudioFileHelper.persistAudioIfRequested(context, job.sourceFilePath, ext)
        } else {
            null
        }

        val cleanTitle = job.fileName.substringBeforeLast('.').ifBlank { job.fileName }
        val entity = TranscriptEntity(
            title = cleanTitle,
            originalFileName = job.fileName,
            audioFilePath = savedAudioPath,
            audioSizeBytes = job.sizeBytes,
            audioDurationMs = job.durationMs,
            requestedLanguage = job.languageCode,
            detectedLanguage = result.detectedLanguage,
            activeMode = job.modeId,
            exactText = result.exactText,
            cleanText = result.cleanText,
            segmentsJson = transcriptRepo.encodeSegments(result.segments),
            engineUsed = job.engineId,
            isSavedToHistory = job.autoSaveTranscript
        )
        val insertedId = transcriptRepo.insert(entity)

        // Delete working source & temp chunks after transcript & audio copy are safely persisted
        AudioFileHelper.cleanupJobFiles(context, job.jobId)

        jobDao.updateJob(
            job.copy(
                status = JobStatus.COMPLETED.name,
                stageName = "COMPLETED",
                uploadPercent = 100,
                completedChunks = job.totalChunks.coerceAtLeast(1),
                failedChunks = 0,
                etaSeconds = 0L,
                resultTranscriptId = insertedId,
                errorType = null,
                errorDetail = null,
                updatedAt = System.currentTimeMillis()
            )
        )
        onStatusNotification(job.fileName, "Completed")
    }

    private suspend fun refreshJobMetrics(jobId: String) {
        val job = jobDao.getJob(jobId) ?: return
        val chunks = jobDao.getChunksForJob(jobId)
        val completed = chunks.filter { it.status == ChunkStatus.COMPLETED.name }
        val failed = chunks.count { it.status == ChunkStatus.FAILED.name }
        val avgMs = completed
            .map { it.processingDurationMs }
            .filter { it > 0L }
            .takeIf { it.isNotEmpty() }
            ?.average()?.toLong() ?: 0L
        val remaining = (chunks.size - completed.size).coerceAtLeast(0)
        val etaSeconds = if (avgMs > 0L) ((remaining * avgMs) / 1000L).coerceAtLeast(1L) else null

        jobDao.updateJob(
            job.copy(
                completedChunks = completed.size,
                failedChunks = failed,
                avgChunkProcessingMs = avgMs,
                etaSeconds = etaSeconds,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun markJobFailed(
        job: TranscriptionJobEntity,
        status: JobStatus,
        error: AppError
    ) {
        jobDao.updateJob(
            job.copy(
                status = status.name,
                stageName = status.name,
                errorType = error.type.name,
                errorDetail = error.detail,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun generateCleanText(
        exactText: String,
        languageCode: String,
        settings: AppSettings
    ): String {
        if (!isNetworkAvailable()) {
            throw AudioFileException(AppError(AppErrorType.NO_INTERNET))
        }
        return when (settings.engine) {
            TranscriptionEngine.FIREBASE_GEMINI ->
                firebaseService.generateCleanFromExact(exactText, languageCode)
            TranscriptionEngine.CUSTOM_BACKEND_OPENAI ->
                backendService.generateCleanFromExact(
                    baseUrl = settings.backendBaseUrl,
                    authToken = settings.backendAuthToken,
                    exactText = exactText,
                    languageCode = languageCode
                )
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
