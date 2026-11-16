package com.example.data.audio

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.AudioMetadata
import com.example.domain.SelectedAudioInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow

open class AudioFileException(val appError: AppError) : Exception(appError.detail ?: appError.type.name)

object AudioFileHelper {

    val SUPPORTED_EXTENSIONS = setOf(
        "mp3", "m4a", "wav", "mp4", "mpeg", "mpga", "ogg", "oga", "aac", "3gp", "flac", "webm", "amr"
    )

    val SUPPORTED_MIME_PREFIXES = setOf("audio/", "video/mp4", "video/webm", "application/ogg")

    suspend fun importFromUri(context: Context, uri: Uri): SelectedAudioInfo = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var displayName = "audio_${System.currentTimeMillis()}"
        var reportedSize = -1L

        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0) {
                        cursor.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    }
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        reportedSize = cursor.getLong(sizeIdx)
                    }
                }
            }

        val rawMime = resolver.getType(uri).orEmpty()
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
            .ifBlank { MimeTypeMap.getSingleton().getExtensionFromMimeType(rawMime).orEmpty().lowercase(Locale.US) }

        val isMimeSupported = rawMime.isNotBlank() && SUPPORTED_MIME_PREFIXES.any { rawMime.startsWith(it, ignoreCase = true) }
        val isExtSupported = ext in SUPPORTED_EXTENSIONS
        if (!isMimeSupported && !isExtSupported) {
            throw AudioFileException(AppError(AppErrorType.UNSUPPORTED_FORMAT, displayName))
        }

        if (reportedSize > 0 && !hasEnoughStorage(context, reportedSize + 8 * 1024 * 1024L)) {
            throw AudioFileException(AppError(AppErrorType.INSUFFICIENT_STORAGE))
        }

        val pendingDir = File(context.cacheDir, "pending_audio").apply { mkdirs() }
        // Clean stale pending cache files older than 2 hours
        pendingDir.listFiles()?.forEach { f ->
            if (System.currentTimeMillis() - f.lastModified() > 2 * 60 * 60 * 1000L) {
                runCatching { f.delete() }
            }
        }

        val safeExt = ext.ifBlank { "m4a" }
        val tempFile = File(pendingDir, "upload_${System.currentTimeMillis()}.$safeExt")

        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw AudioFileException(AppError(AppErrorType.TRANSCRIPTION_FAILED, "Unable to open audio stream"))

        val actualSize = tempFile.length()
        if (actualSize <= 0L) {
            tempFile.delete()
            throw AudioFileException(AppError(AppErrorType.EMPTY_AUDIO))
        }

        val normalizedMime = normalizeAudioMimeType(rawMime, safeExt)
        val meta = analyzeAudio(tempFile.absolutePath, normalizedMime)

        SelectedAudioInfo(
            sourceUri = uri,
            localTempPath = tempFile.absolutePath,
            displayName = displayName,
            mimeType = normalizedMime,
            sizeBytes = actualSize,
            durationMs = meta.durationMs,
            isRecorded = false,
            sampleRate = meta.sampleRate,
            channels = meta.channels
        )
    }

    suspend fun prepareRecordedFile(file: File, recordedDurationMs: Long): SelectedAudioInfo =
        withContext(Dispatchers.IO) {
            if (!file.exists() || file.length() <= 0L) {
                throw AudioFileException(AppError(AppErrorType.EMPTY_AUDIO))
            }
            val meta = analyzeAudio(file.absolutePath, "audio/mp4")
            val actualDuration = meta.durationMs.takeIf { it > 0L } ?: recordedDurationMs
            SelectedAudioInfo(
                sourceUri = Uri.fromFile(file),
                localTempPath = file.absolutePath,
                displayName = file.name,
                mimeType = "audio/mp4",
                sizeBytes = file.length(),
                durationMs = actualDuration,
                isRecorded = true,
                sampleRate = meta.sampleRate,
                channels = meta.channels
            )
        }

    fun analyzeAudio(filePath: String, mimeTypeHint: String = "audio/mp4"): AudioMetadata {
        var durationMs = 0L
        var sampleRate = 0
        var channels = 0
        var bitRate = 0
        var detectedMime = mimeTypeHint

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(filePath)
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            bitRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                ?.takeIf { it.isNotBlank() }
                ?.let { detectedMime = it }
        } catch (_: Exception) {
        } finally {
            runCatching { retriever.release() }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val trackMime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (trackMime.startsWith("audio/")) {
                    if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    if (durationMs <= 0L && fmt.containsKey(MediaFormat.KEY_DURATION)) {
                        durationMs = (fmt.getLong(MediaFormat.KEY_DURATION) / 1000L).coerceAtLeast(0L)
                    }
                    if (bitRate <= 0 && fmt.containsKey(MediaFormat.KEY_BIT_RATE)) {
                        bitRate = fmt.getInteger(MediaFormat.KEY_BIT_RATE)
                    }
                    break
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { extractor.release() }
        }

        // WAV header fallback if MediaMetadataRetriever / MediaExtractor cannot read headers (e.g. JVM/test or raw WAV)
        if (durationMs <= 0L || sampleRate <= 0) {
            parseWavHeader(File(filePath))?.let { wavMeta ->
                if (durationMs <= 0L) durationMs = wavMeta.durationMs
                if (sampleRate <= 0) sampleRate = wavMeta.sampleRate
                if (channels <= 0) channels = wavMeta.channels
                if (bitRate <= 0) bitRate = wavMeta.bitRate
            }
        }

        return AudioMetadata(
            durationMs = durationMs.coerceAtLeast(0L),
            sampleRate = if (sampleRate > 0) sampleRate else 16000,
            channels = if (channels > 0) channels else 1,
            bitRate = bitRate.coerceAtLeast(0),
            mimeType = detectedMime
        )
    }

    internal fun parseWavHeader(file: File): AudioMetadata? {
        if (!file.exists() || file.length() < 44L) return null
        return runCatching {
            FileInputStream(file).use { fis ->
                val header = ByteArray(44)
                if (fis.read(header) < 44) return@use null
                val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val riff = String(header, 0, 4, Charsets.US_ASCII)
                val wave = String(header, 8, 4, Charsets.US_ASCII)
                if (riff != "RIFF" || wave != "WAVE") return@use null

                val channels = bb.getShort(22).toInt().coerceAtLeast(1)
                val sampleRate = bb.getInt(24).coerceAtLeast(1)
                val byteRate = bb.getInt(28).coerceAtLeast(1)
                val bitsPerSample = bb.getShort(34).toInt().coerceAtLeast(8)
                val payloadBytes = (file.length() - 44L).coerceAtLeast(0L)
                val durationMs = (payloadBytes * 1000L) / byteRate.toLong()
                AudioMetadata(
                    durationMs = durationMs,
                    sampleRate = sampleRate,
                    channels = channels,
                    bitRate = sampleRate * channels * bitsPerSample,
                    mimeType = "audio/wav"
                )
            }
        }.getOrNull()
    }

    /**
     * Copies audio into persistent app filesDir so WorkManager background retries remain safe even if
     * Android evicts cacheDir or reboots the app. Streams via 16KB buffer + computes SHA-256.
     */
    suspend fun copyToDurableJobSource(
        context: Context,
        sourcePath: String,
        jobId: String,
        extensionHint: String
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        val source = File(sourcePath)
        if (!source.exists() || source.length() <= 0L) {
            throw AudioFileException(AppError(AppErrorType.EMPTY_AUDIO))
        }
        val jobsRoot = File(context.filesDir, "pending_jobs/$jobId").apply { mkdirs() }
        val ext = extensionHint.ifBlank { source.extension.ifBlank { "m4a" } }
        val target = File(jobsRoot, "source.$ext")

        if (source.absolutePath == target.absolutePath) {
            return@withContext target to computeSha256Hex(target)
        }

        if (!hasEnoughStorage(context, source.length() + 8 * 1024 * 1024L)) {
            throw AudioFileException(AppError(AppErrorType.INSUFFICIENT_STORAGE))
        }

        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        target to hex
    }

    fun cleanupJobFiles(context: Context, jobId: String) {
        runCatching { File(context.filesDir, "pending_jobs/$jobId").deleteRecursively() }
        runCatching { File(context.filesDir, "job_chunks/$jobId").deleteRecursively() }
    }

    /**
     * Copies audio to permanent app storage only if retained, deduplicating by SHA-256 content hash
     * so identical files are never stored twice unnecessarily.
     */
    suspend fun persistAudioIfRequested(context: Context, sourcePath: String, extensionHint: String): String? =
        withContext(Dispatchers.IO) {
            val source = File(sourcePath)
            if (!source.exists() || source.length() <= 0L) return@withContext null

            if (!hasEnoughStorage(context, source.length() + 4 * 1024 * 1024L)) {
                throw AudioFileException(AppError(AppErrorType.INSUFFICIENT_STORAGE))
            }

            val audioDir = File(context.filesDir, "audio").apply { mkdirs() }
            val hash = sha256Prefix(source)
            val ext = extensionHint.ifBlank { source.extension.ifBlank { "m4a" } }
            val target = File(audioDir, "aud_${hash}.$ext")
            if (target.exists() && target.length() == source.length()) {
                return@withContext target.absolutePath
            }
            source.copyTo(target, overwrite = true)
            target.absolutePath
        }

    fun extractDurationMs(filePath: String): Long = analyzeAudio(filePath).durationMs

    fun hasEnoughStorage(context: Context, requiredBytes: Long): Boolean {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBytes >= requiredBytes
        } catch (_: Exception) {
            true
        }
    }

    fun normalizeAudioMimeType(rawMime: String, ext: String): String {
        if (rawMime.startsWith("audio/")) return rawMime
        return when (ext.lowercase(Locale.US)) {
            "mp3", "mpga" -> "audio/mpeg"
            "m4a", "mp4" -> "audio/mp4"
            "wav" -> "audio/wav"
            "ogg", "oga" -> "audio/ogg"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "webm" -> "audio/webm"
            "3gp" -> "audio/3gpp"
            "amr" -> "audio/amr"
            else -> "audio/mp4"
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt().coerceIn(0, units.lastIndex)
        val value = bytes / 1024.0.pow(digitGroups.toDouble())
        return String.format(Locale.US, if (digitGroups == 0) "%.0f %s" else "%.2f %s", value, units[digitGroups])
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun computeSha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Prefix(file: File): String = computeSha256Hex(file).take(16)
}
