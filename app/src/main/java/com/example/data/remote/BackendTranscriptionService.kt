package com.example.data.remote

import android.util.Log
import com.example.data.audio.AudioChunk
import com.example.data.audio.AudioFileException
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AudioLanguage
import com.example.domain.ChunkProgressItem
import com.example.domain.ChunkStatus
import com.example.domain.JobStatus
import com.example.domain.TranscriptChunkResult
import com.example.domain.TranscriptSegment
import com.example.domain.TranscriptionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

private const val TAG = "BackendTranscribe"

data class RemoteJobCreated(
    val jobId: String,
    val status: String,
    val uploadedChunks: Int,
    val completedChunks: Int,
    val totalChunks: Int
)

data class RemoteUploadStatus(
    val jobId: String,
    val uploadedBytes: Long,
    val totalBytes: Long,
    val uploadedChunkIndices: Set<Int>
)

data class RemoteJobStatus(
    val jobId: String,
    val status: JobStatus,
    val totalChunks: Int,
    val completedChunks: Int,
    val failedChunks: Int,
    val currentChunkIndex: Int,
    val etaSeconds: Long?,
    val error: String?,
    val chunks: List<ChunkProgressItem>,
    val detectedLanguage: String,
    val exactTranscript: String?,
    val cleanTranscript: String?,
    val segments: List<TranscriptSegment>
)

class ProgressRequestBody(
    private val file: File,
    private val contentType: String,
    private val onProgress: (bytesSent: Long, totalBytes: Long, percent: Int) -> Unit
) : RequestBody() {

    override fun contentType() = contentType.toMediaType()

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val total = contentLength().coerceAtLeast(1L)
        val buffer = ByteArray(16 * 1024)
        var uploaded = 0L
        var lastPercent = -1

        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                sink.write(buffer, 0, read)
                uploaded += read
                val percent = ((uploaded * 100L) / total).toInt().coerceIn(0, 100)
                if (percent != lastPercent) {
                    lastPercent = percent
                    onProgress(uploaded, total, percent)
                }
            }
        }
    }
}

class BackendHttpException(
    val httpCode: Int,
    val retryAfterMs: Long,
    appError: AppError
) : AudioFileException(appError)

class BackendTranscriptionService {

    // Dedicated client for streaming chunk uploads (large uploads never killed prematurely)
    private val uploadClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    // Fast lightweight client for async job enqueue, status polling, retry & health
    private val controlClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()
    }

    suspend fun createOrResumeJob(
        baseUrl: String,
        authToken: String,
        clientRequestId: String,
        fileName: String,
        mimeType: String,
        totalBytes: Long,
        durationMs: Long,
        sampleRate: Int,
        channels: Int,
        fileSha256: String,
        language: AudioLanguage,
        mode: TranscriptionMode,
        totalChunks: Int
    ): RemoteJobCreated = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val payload = JSONObject().apply {
            put("clientRequestId", clientRequestId)
            put("fileName", fileName)
            put("mimeType", mimeType)
            put("totalBytes", totalBytes)
            put("durationMs", durationMs)
            put("sampleRate", sampleRate)
            put("channels", channels)
            put("fileSha256", fileSha256)
            put("language", language.code)
            put("mode", mode.id)
            put("totalChunks", totalChunks)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = requestBuilder("$cleanUrl/api/jobs", authToken).post(payload).build()
        executeControlCall(req) { json ->
            RemoteJobCreated(
                jobId = json.getString("jobId"),
                status = json.optString("status", "awaiting_upload"),
                uploadedChunks = json.optInt("uploadedChunks", 0),
                completedChunks = json.optInt("completedChunks", 0),
                totalChunks = json.optInt("totalChunks", totalChunks)
            )
        }
    }

    suspend fun getUploadStatus(
        baseUrl: String,
        authToken: String,
        remoteJobId: String
    ): RemoteUploadStatus = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val req = requestBuilder("$cleanUrl/api/jobs/$remoteJobId/upload-status", authToken).get().build()
        executeControlCall(req) { json ->
            val arr = json.optJSONArray("uploadedChunkIndices")
            val uploadedSet = mutableSetOf<Int>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    uploadedSet.add(arr.optInt(i))
                }
            }
            RemoteUploadStatus(
                jobId = json.optString("jobId", remoteJobId),
                uploadedBytes = json.optLong("uploadedBytes", 0L),
                totalBytes = json.optLong("totalBytes", 0L),
                uploadedChunkIndices = uploadedSet
            )
        }
    }

    suspend fun uploadChunkStreaming(
        baseUrl: String,
        authToken: String,
        remoteJobId: String,
        chunk: AudioChunk,
        onBytesProgress: (bytesSent: Long, totalBytes: Long, percent: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val body = ProgressRequestBody(chunk.file, chunk.mimeType, onBytesProgress)
        val req = requestBuilder("$cleanUrl/api/jobs/$remoteJobId/chunks/${chunk.index}", authToken)
            .addHeader("X-Chunk-Sha256", chunk.sha256)
            .addHeader("X-Chunk-Mime", chunk.mimeType)
            .addHeader("X-Start-Offset-Ms", chunk.startOffsetMs.toString())
            .addHeader("X-Duration-Ms", chunk.durationMs.toString())
            .addHeader("X-Overlap-Before-Ms", chunk.overlapBeforeMs.toString())
            .put(body)
            .build()

        Log.i(TAG, "Uploading chunk=${chunk.index} size=${chunk.file.length()} remoteJob=$remoteJobId")
        executeWithClient(uploadClient, req) { _ -> Unit }
    }

    suspend fun enqueueJob(
        baseUrl: String,
        authToken: String,
        remoteJobId: String
    ) = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val emptyBody = ByteArray(0).toRequestBody("application/json".toMediaType())
        val req = requestBuilder("$cleanUrl/api/jobs/$remoteJobId/enqueue", authToken).post(emptyBody).build()
        executeControlCall(req) { _ -> Unit }
    }

    suspend fun getJobStatus(
        baseUrl: String,
        authToken: String,
        remoteJobId: String
    ): RemoteJobStatus = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val req = requestBuilder("$cleanUrl/api/jobs/$remoteJobId", authToken).get().build()
        executeControlCall(req) { json ->
            val statusStr = json.optString("status", "queued")
            val mappedStatus = when (statusStr.lowercase()) {
                "awaiting_upload" -> JobStatus.UPLOADING
                "queued" -> JobStatus.QUEUED
                "processing" -> JobStatus.PROCESSING
                "partial_failed" -> JobStatus.PARTIAL_FAILED
                "completed" -> JobStatus.COMPLETED
                "failed" -> JobStatus.FAILED
                "cancelled" -> JobStatus.CANCELLED
                else -> JobStatus.PROCESSING
            }

            val chunkItems = mutableListOf<ChunkProgressItem>()
            val cArray = json.optJSONArray("chunks")
            if (cArray != null) {
                for (i in 0 until cArray.length()) {
                    val obj = cArray.optJSONObject(i) ?: continue
                    chunkItems.add(
                        ChunkProgressItem(
                            index = obj.optInt("index", i),
                            status = ChunkStatus.fromName(obj.optString("status")),
                            startOffsetMs = obj.optLong("startOffsetMs", 0L),
                            durationMs = obj.optLong("durationMs", 0L),
                            retryCount = obj.optInt("retryCount", 0),
                            error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" }
                        )
                    )
                }
            }

            val segments = parseSegments(json)
            val eta = if (json.isNull("etaSeconds")) null else json.optLong("etaSeconds", -1L).takeIf { it >= 0L }
            RemoteJobStatus(
                jobId = json.optString("jobId", remoteJobId),
                status = mappedStatus,
                totalChunks = json.optInt("totalChunks", chunkItems.size),
                completedChunks = json.optInt("completedChunks", 0),
                failedChunks = json.optInt("failedChunks", 0),
                currentChunkIndex = json.optInt("currentChunkIndex", -1),
                etaSeconds = eta,
                error = json.optString("error").takeIf { it.isNotBlank() && it != "null" },
                chunks = chunkItems,
                detectedLanguage = json.optString("detectedLanguage", "bn"),
                exactTranscript = json.optString("exactTranscript").takeIf { it.isNotBlank() && it != "null" },
                cleanTranscript = json.optString("cleanTranscript").takeIf { it.isNotBlank() && it != "null" },
                segments = segments
            )
        }
    }

    suspend fun retryFailedChunks(
        baseUrl: String,
        authToken: String,
        remoteJobId: String,
        chunkIndices: List<Int>? = null
    ) = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val jsonPayload = JSONObject().apply {
            if (!chunkIndices.isNullOrEmpty()) {
                put("chunkIndices", org.json.JSONArray(chunkIndices))
            }
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = requestBuilder("$cleanUrl/api/jobs/$remoteJobId/retry", authToken).post(jsonPayload).build()
        executeControlCall(req) { _ -> Unit }
    }

    suspend fun deleteRemoteJob(
        baseUrl: String,
        authToken: String,
        remoteJobId: String
    ) = withContext(Dispatchers.IO) {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) return@withContext
        val req = requestBuilder("$cleanUrl/api/jobs/$remoteJobId", authToken).delete().build()
        runCatching { controlClient.newCall(req).execute().close() }
    }

    suspend fun transcribeChunkDirect(
        baseUrl: String,
        authToken: String,
        chunk: AudioChunk,
        languageHint: AudioLanguage,
        mode: TranscriptionMode,
        previousContext: String,
        onUploadProgress: (Int) -> Unit
    ): TranscriptChunkResult = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val fileBody = ProgressRequestBody(chunk.file, chunk.mimeType) { _, _, percent ->
            onUploadProgress(percent)
        }
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("audio", chunk.file.name, fileBody)
            .addFormDataPart("language", languageHint.code)
            .addFormDataPart("mode", mode.id)
            .addFormDataPart("includeTimestamps", "true")
            .apply {
                if (previousContext.isNotBlank()) {
                    addFormDataPart("previousContext", previousContext)
                }
            }
            .build()

        val req = requestBuilder("$cleanUrl/api/transcribe", authToken).post(multipart).build()
        executeWithClient(uploadClient, req) { bodyStr ->
            parseResponse(bodyStr, languageHint)
        }
    }

    suspend fun generateCleanFromExact(
        baseUrl: String,
        authToken: String,
        exactText: String,
        languageCode: String
    ): String = withContext(Dispatchers.IO) {
        val cleanUrl = validateBaseUrl(baseUrl)
        val payload = JSONObject().apply {
            put("exactText", exactText)
            put("language", languageCode)
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val req = requestBuilder("$cleanUrl/api/clean", authToken).post(payload).build()
        executeControlCall(req) { json ->
            json.optString("cleanTranscript", exactText).ifBlank { exactText }
        }
    }

    suspend fun checkHealth(baseUrl: String, authToken: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanUrl = validateBaseUrl(baseUrl)
            val req = requestBuilder("$cleanUrl/api/health", authToken).get().build()
            controlClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    error("HTTP ${resp.code}")
                }
                val json = runCatching { JSONObject(body) }.getOrNull()
                val model = json?.optString("model", "gpt-4o-transcribe") ?: "ok"
                "Backend OK ($model)"
            }
        }
    }

    private fun validateBaseUrl(baseUrl: String): String {
        val cleanUrl = baseUrl.trim().trimEnd('/')
        if (cleanUrl.isBlank()) {
            throw AudioFileException(AppError(AppErrorType.SERVER_UNAVAILABLE, "Backend URL is empty"))
        }
        return cleanUrl
    }

    private fun requestBuilder(url: String, authToken: String): Request.Builder {
        val builder = Request.Builder().url(url)
        if (authToken.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer ${authToken.trim()}")
        }
        return builder
    }

    private fun <T> executeControlCall(request: Request, parser: (JSONObject) -> T): T =
        executeWithClient(controlClient, request) { bodyStr ->
            val json = if (bodyStr.isBlank()) JSONObject() else JSONObject(bodyStr)
            parser(json)
        }

    private fun <T> executeWithClient(httpClient: OkHttpClient, request: Request, parser: (String) -> T): T {
        try {
            httpClient.newCall(request).execute().use { response ->
                val bodyStr = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val retryAfterMs = parseRetryAfterMs(response.header("Retry-After"))
                    throw mapHttpError(response.code, bodyStr, retryAfterMs)
                }
                return parser(bodyStr)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AudioFileException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Log.e(TAG, "Backend timeout on ${request.url.encodedPath}", e)
            throw AudioFileException(AppError(AppErrorType.SERVER_UNAVAILABLE, "Network timeout on ${request.url.encodedPath}"))
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Host unreachable", e)
            throw AudioFileException(AppError(AppErrorType.SERVER_UNAVAILABLE, e.message))
        } catch (e: ConnectException) {
            Log.e(TAG, "Connection refused", e)
            throw AudioFileException(AppError(AppErrorType.SERVER_UNAVAILABLE, e.message))
        } catch (e: IOException) {
            Log.e(TAG, "Network IO failure", e)
            throw AudioFileException(AppError(AppErrorType.NO_INTERNET, e.message))
        }
    }

    private fun parseSegments(json: JSONObject): List<TranscriptSegment> {
        val segments = mutableListOf<TranscriptSegment>()
        val segArray = json.optJSONArray("segments") ?: return segments
        for (i in 0 until segArray.length()) {
            val obj = segArray.optJSONObject(i) ?: continue
            val startMs = obj.optLong("startMs", (obj.optDouble("start", -1.0) * 1000.0).toLong())
            val endMs = obj.optLong("endMs", (obj.optDouble("end", -1.0) * 1000.0).toLong())
            val text = obj.optString("text", "").trim()
            if (startMs >= 0L && endMs > startMs && text.isNotBlank()) {
                segments.add(
                    TranscriptSegment(
                        index = segments.size + 1,
                        startMs = startMs,
                        endMs = endMs,
                        text = text
                    )
                )
            }
        }
        return segments
    }

    private fun parseResponse(bodyStr: String, hint: AudioLanguage): TranscriptChunkResult {
        val json = JSONObject(bodyStr)
        val exact = json.optString("exactTranscript", json.optString("text", "")).trim()
        val clean = json.optString("cleanTranscript", "").trim().takeIf { it.isNotBlank() }
        val detected = json.optString("detectedLanguage", hint.code).ifBlank { hint.code }
        val segments = parseSegments(json)

        if (exact.isBlank()) {
            throw AudioFileException(AppError(AppErrorType.TRANSCRIPTION_FAILED, "Empty transcript returned"))
        }

        return TranscriptChunkResult(
            exactText = exact,
            cleanText = clean,
            detectedLanguage = detected,
            segments = segments
        )
    }

    private fun parseRetryAfterMs(header: String?): Long {
        if (header.isNullOrBlank()) return 0L
        val secs = header.trim().toLongOrNull() ?: return 0L
        return (secs * 1000L).coerceIn(0L, 120_000L)
    }

    private fun mapHttpError(code: Int, bodyStr: String, retryAfterMs: Long): BackendHttpException {
        val msg = runCatching { JSONObject(bodyStr).optString("error", bodyStr) }
            .getOrDefault(bodyStr)
            .take(140)
        val type = when (code) {
            401, 403 -> AppErrorType.API_AUTH_FAILURE
            413 -> AppErrorType.FILE_TOO_LARGE
            415 -> AppErrorType.UNSUPPORTED_FORMAT
            429 -> AppErrorType.API_RATE_LIMIT
            in 500..599 -> AppErrorType.SERVER_UNAVAILABLE
            else -> AppErrorType.TRANSCRIPTION_FAILED
        }
        return BackendHttpException(code, retryAfterMs, AppError(type, msg.ifBlank { "HTTP $code" }))
    }
}
