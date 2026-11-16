package com.example.data.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.domain.AppError
import com.example.domain.AppErrorType
import com.example.domain.TranscriptChunkResult
import com.example.domain.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class AudioChunk(
    val index: Int,
    val file: File,
    val mimeType: String,
    val startOffsetMs: Long,
    val durationMs: Long = 0L,
    val overlapBeforeMs: Long = 0L,
    val sha256: String = "",
    val isTemporary: Boolean
)

object AudioChunker {

    // Default chunk bounds respect provider single-request limits while keeping mobile uploads reliable
    // for recordings of arbitrary multi-hour length.
    const val DEFAULT_MAX_CHUNK_BYTES = 1L * 1024L * 1024L
    const val DEFAULT_MAX_CHUNK_DURATION_MS = 30_000L
    const val DEFAULT_OVERLAP_MS = 1_000L
    const val PCM_16K_BYTES_PER_SEC = 16000L * 2L // 32,000 bytes/sec
    private const val SUSTAINED_SILENCE_RMS_THRESHOLD = 420.0
    private const val SUSTAINED_SILENCE_MIN_FRAMES_MS = 160L

    suspend fun prepareChunks(
        context: Context,
        sourcePath: String,
        mimeType: String,
        jobId: String = "job_${System.currentTimeMillis()}",
        maxChunkBytes: Long = DEFAULT_MAX_CHUNK_BYTES,
        maxChunkDurationMs: Long = DEFAULT_MAX_CHUNK_DURATION_MS,
        overlapMs: Long = DEFAULT_OVERLAP_MS,
        onChunkProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): List<AudioChunk> = withContext(Dispatchers.IO) {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists() || sourceFile.length() <= 0L) {
            throw AudioFileException(AppError(AppErrorType.EMPTY_AUDIO))
        }

        val meta = AudioFileHelper.analyzeAudio(sourcePath, mimeType)
        val durationMs = meta.durationMs
        val fitsSize = sourceFile.length() <= maxChunkBytes
        val fitsDuration = durationMs <= 0L || durationMs <= maxChunkDurationMs

        if (fitsSize && fitsDuration) {
            return@withContext listOf(
                AudioChunk(
                    index = 0,
                    file = sourceFile,
                    mimeType = mimeType,
                    startOffsetMs = 0L,
                    durationMs = durationMs,
                    overlapBeforeMs = 0L,
                    sha256 = AudioFileHelper.computeSha256Hex(sourceFile),
                    isTemporary = false
                )
            )
        }

        val chunkDir = File(context.filesDir, "job_chunks/$jobId").apply { mkdirs() }
        val estimatedTotal = maxOf(
            ((sourceFile.length() / maxChunkBytes.coerceAtLeast(1L)) + 1).toInt(),
            if (durationMs > 0L) ((durationMs / maxChunkDurationMs.coerceAtLeast(1L)) + 1).toInt() else 2
        ).coerceAtLeast(2)
        onChunkProgress(1, estimatedTotal)

        // Primary: Decode to 16kHz mono PCM, detect sustained silence boundaries, normalize gain if too quiet,
        // and carry overlapping audio window across hard splits so continuous speech is never lost.
        val wavChunks = runCatching {
            splitToSilenceAwareOverlappingWavChunks(
                sourceFile = sourceFile,
                chunkDir = chunkDir,
                maxChunkBytes = maxChunkBytes,
                maxChunkDurationMs = maxChunkDurationMs,
                overlapMs = overlapMs,
                estimatedTotal = estimatedTotal,
                onChunkProgress = onChunkProgress
            )
        }.getOrNull()

        if (!wavChunks.isNullOrEmpty()) {
            return@withContext wavChunks
        }

        // Secondary fallback: lossless frame-level MP4/M4A muxing if PCM decoder fails on device
        val muxedChunks = runCatching {
            splitWithMediaMuxer(
                sourceFile = sourceFile,
                chunkDir = chunkDir,
                maxChunkBytes = maxChunkBytes,
                maxChunkDurationUs = maxChunkDurationMs * 1000L,
                estimatedTotal = estimatedTotal,
                onChunkProgress = onChunkProgress
            )
        }.getOrNull()

        if (!muxedChunks.isNullOrEmpty()) {
            return@withContext muxedChunks
        }

        throw AudioFileException(AppError(AppErrorType.TRANSCRIPTION_FAILED, "Unable to decode or split audio file"))
    }

    /**
     * Pure Kotlin streaming splitter operating on 16kHz 16-bit mono PCM byte frames.
     * Supports sustained silence splitting, 1s overlap tail buffer on non-silent boundaries,
     * and accurate startOffsetMs / durationMs / overlapBeforeMs tracking for multi-hour recordings.
     */
    internal class StreamingPcmChunkWriter(
        private val chunkDir: File,
        private val maxChunkBytes: Long,
        private val maxChunkDurationMs: Long,
        private val overlapMs: Long,
        private val estimatedTotal: Int,
        private val onChunkProgress: (Int, Int) -> Unit
    ) {
        private val maxDurationSecs = (maxChunkDurationMs / 1000L).coerceAtLeast(10L)
        private val targetMaxPcmBytes = minOf(
            (maxChunkBytes - 44L).coerceAtLeast(PCM_16K_BYTES_PER_SEC * 10L),
            PCM_16K_BYTES_PER_SEC * maxDurationSecs
        ).coerceAtLeast(PCM_16K_BYTES_PER_SEC * 10L)
        private val minChunkPcmBytes = (targetMaxPcmBytes * 0.55).toLong()
        private val overlapBytesTarget = ((overlapMs.coerceAtLeast(0L) * PCM_16K_BYTES_PER_SEC) / 1000L)
            .let { if (it % 2L != 0L) it - 1L else it }
            .toInt()
            .coerceAtMost((targetMaxPcmBytes / 3L).toInt())

        private val chunks = mutableListOf<AudioChunk>()
        private val currentPcm = ByteArrayOutputStream()
        private var chunkIndex = 0
        private var chunkStartTimelineMs = 0L
        private var currentOverlapBeforeMs = 0L
        private var accumulatedTimelineSamples = 0L
        private var quietAccumMs = 0L

        fun feed(pcm16kMono: ByteArray) {
            if (pcm16kMono.isEmpty()) return
            var offset = 0
            // Feed in ~40ms subframes (1280 bytes at 16kHz mono) so silence windows are resolved finely
            val stepBytes = 1280
            while (offset < pcm16kMono.size) {
                val len = minOf(stepBytes, pcm16kMono.size - offset)
                val frame = pcm16kMono.copyOfRange(offset, offset + len)
                offset += len

                currentPcm.write(frame)
                val frameSamples = len / 2L
                accumulatedTimelineSamples += frameSamples
                val frameDurationMs = (frameSamples * 1000L) / 16000L

                val rms = computeRms(frame)
                if (rms < SUSTAINED_SILENCE_RMS_THRESHOLD) {
                    quietAccumMs += frameDurationMs
                } else {
                    quietAccumMs = 0L
                }

                val sizeSoFar = currentPcm.size().toLong()
                val sustainedSilenceSplit = sizeSoFar >= minChunkPcmBytes &&
                    quietAccumMs >= SUSTAINED_SILENCE_MIN_FRAMES_MS
                val hardLimitSplit = sizeSoFar >= targetMaxPcmBytes

                if (sustainedSilenceSplit || hardLimitSplit) {
                    flushCurrentChunk(carryOverlap = !sustainedSilenceSplit)
                }
            }
        }

        fun finish(): List<AudioChunk> {
            if (currentPcm.size() > 0) {
                flushCurrentChunk(carryOverlap = false)
            }
            return chunks.toList()
        }

        private fun flushCurrentChunk(carryOverlap: Boolean) {
            val rawPcm = currentPcm.toByteArray()
            if (rawPcm.isEmpty()) return

            val normalizedPcm = normalizeQuietPcm(rawPcm)
            val wavFile = File(chunkDir, "chunk_%04d.wav".format(chunkIndex))
            writeWavFile(wavFile, normalizedPcm, 16000)

            val chunkDurationMs = ((rawPcm.size / 2L) * 1000L) / 16000L
            val sha = AudioFileHelper.computeSha256Hex(wavFile)
            chunks.add(
                AudioChunk(
                    index = chunkIndex,
                    file = wavFile,
                    mimeType = "audio/wav",
                    startOffsetMs = chunkStartTimelineMs,
                    durationMs = chunkDurationMs,
                    overlapBeforeMs = currentOverlapBeforeMs,
                    sha256 = sha,
                    isTemporary = true
                )
            )

            val totalWrittenTimelineMs = (accumulatedTimelineSamples * 1000L) / 16000L
            currentPcm.reset()
            quietAccumMs = 0L
            chunkIndex++
            onChunkProgress(chunkIndex + 1, estimatedTotal.coerceAtLeast(chunkIndex + 1))

            if (carryOverlap && overlapBytesTarget > 0 && rawPcm.size > overlapBytesTarget) {
                val tailStart = rawPcm.size - overlapBytesTarget
                val tail = rawPcm.copyOfRange(tailStart, rawPcm.size)
                currentPcm.write(tail)
                val tailMs = ((tail.size / 2L) * 1000L) / 16000L
                currentOverlapBeforeMs = tailMs
                chunkStartTimelineMs = (totalWrittenTimelineMs - tailMs).coerceAtLeast(0L)
            } else {
                currentOverlapBeforeMs = 0L
                chunkStartTimelineMs = totalWrittenTimelineMs
            }
        }
    }

    private fun splitToSilenceAwareOverlappingWavChunks(
        sourceFile: File,
        chunkDir: File,
        maxChunkBytes: Long,
        maxChunkDurationMs: Long,
        overlapMs: Long,
        estimatedTotal: Int,
        onChunkProgress: (Int, Int) -> Unit
    ): List<AudioChunk> {
        val extractor = MediaExtractor()
        extractor.setDataSource(sourceFile.absolutePath)
        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = format
                break
            }
        }
        if (audioTrackIndex == -1 || inputFormat == null) {
            extractor.release()
            return emptyList()
        }

        val mime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        extractor.selectTrack(audioTrackIndex)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(inputFormat, null, null, 0)
        codec.start()

        val sourceSampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            44100
        }
        val sourceChannels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
        } else {
            1
        }

        val writer = StreamingPcmChunkWriter(
            chunkDir = chunkDir,
            maxChunkBytes = maxChunkBytes,
            maxChunkDurationMs = maxChunkDurationMs,
            overlapMs = overlapMs,
            estimatedTotal = estimatedTotal,
            onChunkProgress = onChunkProgress
        )

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)
                    if (outBuf != null && info.size > 0) {
                        val rawPcm = ByteArray(info.size)
                        outBuf.position(info.offset)
                        outBuf.get(rawPcm)
                        val downsampled = downsampleTo16kMono(rawPcm, sourceSampleRate, sourceChannels)
                        writer.feed(downsampled)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }
            return writer.finish()
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            extractor.release()
        }
    }

    private fun splitWithMediaMuxer(
        sourceFile: File,
        chunkDir: File,
        maxChunkBytes: Long,
        maxChunkDurationUs: Long,
        estimatedTotal: Int,
        onChunkProgress: (Int, Int) -> Unit
    ): List<AudioChunk> {
        val extractor = MediaExtractor()
        extractor.setDataSource(sourceFile.absolutePath)
        var audioTrackIndex = -1
        var audioFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val trackMime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (trackMime.startsWith("audio/")) {
                audioTrackIndex = i
                audioFormat = format
                break
            }
        }
        if (audioTrackIndex == -1 || audioFormat == null) {
            extractor.release()
            return emptyList()
        }

        val trackMime = audioFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        if (trackMime != MediaFormat.MIMETYPE_AUDIO_AAC && !trackMime.contains("mp4a")) {
            extractor.release()
            return emptyList()
        }

        extractor.selectTrack(audioTrackIndex)
        val chunks = mutableListOf<AudioChunk>()
        val buffer = ByteBuffer.allocate(256 * 1024)
        val bufferInfo = MediaCodec.BufferInfo()

        var chunkIndex = 0
        var currentFile = File(chunkDir, "chunk_%04d.m4a".format(chunkIndex))
        var muxer = MediaMuxer(currentFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxTrack = muxer.addTrack(audioFormat)
        muxer.start()

        var firstSampleUs = -1L
        var chunkStartUs = -1L
        var lastSampleUs = 0L
        var accumulatedBytes = 0L

        try {
            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                if (firstSampleUs < 0L) firstSampleUs = sampleTimeUs
                if (chunkStartUs < 0L) chunkStartUs = sampleTimeUs
                lastSampleUs = sampleTimeUs

                val elapsedUs = sampleTimeUs - chunkStartUs
                val shouldRotate = (elapsedUs >= maxChunkDurationUs ||
                    accumulatedBytes + sampleSize >= (maxChunkBytes * 0.85).toLong()) &&
                    accumulatedBytes > 0L

                if (shouldRotate) {
                    muxer.stop()
                    muxer.release()
                    chunks.add(
                        AudioChunk(
                            index = chunkIndex,
                            file = currentFile,
                            mimeType = "audio/mp4",
                            startOffsetMs = ((chunkStartUs - firstSampleUs) / 1000L).coerceAtLeast(0L),
                            durationMs = (elapsedUs / 1000L).coerceAtLeast(0L),
                            overlapBeforeMs = 0L,
                            sha256 = AudioFileHelper.computeSha256Hex(currentFile),
                            isTemporary = true
                        )
                    )
                    chunkIndex++
                    onChunkProgress(chunkIndex + 1, estimatedTotal.coerceAtLeast(chunkIndex + 1))
                    currentFile = File(chunkDir, "chunk_%04d.m4a".format(chunkIndex))
                    muxer = MediaMuxer(currentFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    muxTrack = muxer.addTrack(audioFormat)
                    muxer.start()
                    chunkStartUs = sampleTimeUs
                    accumulatedBytes = 0L
                }

                bufferInfo.offset = 0
                bufferInfo.size = sampleSize
                bufferInfo.presentationTimeUs = (sampleTimeUs - chunkStartUs).coerceAtLeast(0L)
                bufferInfo.flags = extractor.sampleFlags
                muxer.writeSampleData(muxTrack, buffer, bufferInfo)
                accumulatedBytes += sampleSize
                extractor.advance()
            }

            muxer.stop()
            muxer.release()
            if (currentFile.exists() && currentFile.length() > 0L && accumulatedBytes > 0L) {
                chunks.add(
                    AudioChunk(
                        index = chunkIndex,
                        file = currentFile,
                        mimeType = "audio/mp4",
                        startOffsetMs = ((chunkStartUs - firstSampleUs) / 1000L).coerceAtLeast(0L),
                        durationMs = ((lastSampleUs - chunkStartUs) / 1000L).coerceAtLeast(0L),
                        overlapBeforeMs = 0L,
                        sha256 = AudioFileHelper.computeSha256Hex(currentFile),
                        isTemporary = true
                    )
                )
            } else {
                currentFile.delete()
            }
        } finally {
            extractor.release()
        }
        return chunks
    }

    internal fun downsampleTo16kMono(pcm16: ByteArray, srcRate: Int, channels: Int): ByteArray {
        val shortCount = pcm16.size / 2
        val bb = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = shortCount / channels.coerceAtLeast(1)
        if (frameCount <= 0) return ByteArray(0)

        val mono = ShortArray(frameCount)
        for (i in 0 until frameCount) {
            var sum = 0
            for (c in 0 until channels.coerceAtLeast(1)) {
                sum += bb.short.toInt()
            }
            mono[i] = (sum / channels.coerceAtLeast(1)).toShort()
        }

        if (srcRate == 16000) {
            val out = ByteBuffer.allocate(mono.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            mono.forEach { out.putShort(it) }
            return out.array()
        }

        val ratio = srcRate.toDouble() / 16000.0
        val outLen = (mono.size / ratio).toInt().coerceAtLeast(1)
        val out = ByteBuffer.allocate(outLen * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outLen) {
            val srcIdx = (i * ratio).toInt().coerceIn(0, mono.lastIndex)
            out.putShort(mono[srcIdx])
        }
        return out.array()
    }

    private fun normalizeQuietPcm(pcm16: ByteArray): ByteArray {
        if (pcm16.size < 2) return pcm16
        val bb = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        val count = pcm16.size / 2
        var peak = 0
        for (i in 0 until count) {
            val v = abs(bb.short.toInt())
            if (v > peak) peak = v
        }
        // Boost only very quiet speech recordings safely (up to 4x) without clipping loud tracks
        if (peak !in 180 until 6000) return pcm16
        val gain = (16000.0 / peak).coerceIn(1.2, 4.0)
        bb.rewind()
        val out = ByteBuffer.allocate(pcm16.size).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            val scaled = (bb.short.toInt() * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.putShort(scaled.toShort())
        }
        return out.array()
    }

    private fun computeRms(pcm16: ByteArray): Double {
        if (pcm16.size < 2) return 0.0
        val bb = ByteBuffer.wrap(pcm16).order(ByteOrder.LITTLE_ENDIAN)
        val count = pcm16.size / 2
        var sumSq = 0.0
        for (i in 0 until count) {
            val s = bb.short.toDouble()
            sumSq += s * s
        }
        return sqrt(sumSq / count)
    }

    internal fun writeWavFile(outFile: File, pcmData: ByteArray, sampleRate: Int) {
        val dataLen = pcmData.size.toLong()
        val totalDataLen = dataLen + 36L
        val byteRate = sampleRate * 2L
        FileOutputStream(outFile).use { fos ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray())
                putInt(totalDataLen.toInt())
                put("WAVE".toByteArray())
                put("fmt ".toByteArray())
                putInt(16)
                putShort(1)
                putShort(1)
                putInt(sampleRate)
                putInt(byteRate.toInt())
                putShort(2)
                putShort(16)
                put("data".toByteArray())
                putInt(dataLen.toInt())
            }
            fos.write(header.array())
            fos.write(pcmData)
        }
    }

    fun cleanupChunks(chunks: List<AudioChunk>) {
        chunks.filter { it.isTemporary }.forEach { chunk ->
            runCatching { chunk.file.delete() }
            chunk.file.parentFile?.let { parent ->
                if ((parent.name.startsWith("audio_chunks_") || parent.parentFile?.name == "job_chunks") &&
                    parent.listFiles().isNullOrEmpty()
                ) {
                    runCatching { parent.delete() }
                }
            }
        }
    }

    fun mergeChunkResults(
        chunkPairs: List<Pair<AudioChunk, TranscriptChunkResult>>
    ): TranscriptChunkResult {
        if (chunkPairs.isEmpty()) {
            return TranscriptChunkResult("", null, "auto", emptyList())
        }
        if (chunkPairs.size == 1) {
            val (chunk, res) = chunkPairs.first()
            val shifted = res.segments.mapIndexed { idx, seg ->
                seg.copy(
                    index = idx + 1,
                    startMs = seg.startMs + chunk.startOffsetMs,
                    endMs = seg.endMs + chunk.startOffsetMs
                )
            }
            return res.copy(segments = shifted)
        }

        val sorted = chunkPairs.sortedBy { it.first.index }
        val exactBuilder = StringBuilder()
        val cleanBuilder = StringBuilder()
        var hasAnyClean = false
        val mergedSegments = mutableListOf<TranscriptSegment>()
        var segmentCounter = 1
        val detectedLang = sorted.map { it.second.detectedLanguage }
            .firstOrNull { it.isNotBlank() && it != "auto" } ?: "auto"

        for ((chunk, res) in sorted) {
            val appendedExact = appendWithBoundaryDedup(exactBuilder.toString(), res.exactText)
            exactBuilder.clear()
            exactBuilder.append(appendedExact)

            val cleanChunk = res.cleanText
            if (!cleanChunk.isNullOrBlank()) {
                hasAnyClean = true
                val appendedClean = appendWithBoundaryDedup(cleanBuilder.toString(), cleanChunk)
                cleanBuilder.clear()
                cleanBuilder.append(appendedClean)
            }

            val overlapGuardMs = (chunk.overlapBeforeMs - 200L).coerceAtLeast(0L)
            res.segments.forEach { seg ->
                if (seg.endMs <= seg.startMs || seg.text.isBlank()) return@forEach
                val midPoint = (seg.startMs + seg.endMs) / 2L
                if (chunk.index > 0 && overlapGuardMs > 0L && midPoint < overlapGuardMs) {
                    return@forEach
                }
                mergedSegments.add(
                    TranscriptSegment(
                        index = segmentCounter++,
                        startMs = seg.startMs + chunk.startOffsetMs,
                        endMs = seg.endMs + chunk.startOffsetMs,
                        text = seg.text
                    )
                )
            }
        }

        return TranscriptChunkResult(
            exactText = exactBuilder.toString().trim(),
            cleanText = if (hasAnyClean) cleanBuilder.toString().trim() else null,
            detectedLanguage = detectedLang,
            segments = mergedSegments
        )
    }

    internal fun appendWithBoundaryDedup(existing: String, incoming: String): String {
        val left = existing.trimEnd()
        val right = incoming.trimStart()
        if (left.isBlank()) return right
        if (right.isBlank()) return left

        val leftWords = left.split(Regex("\\s+"))
        val rightWords = right.split(Regex("\\s+"))
        val maxCheck = minOf(12, leftWords.size, rightWords.size)
        val normalizeWord: (String) -> String = { w ->
            w.trim(',', '.', '?', '!', '।', ':', ';', '"', '\'', '(', ')', '[', ']').lowercase()
        }
        var overlapCount = 0
        for (k in maxCheck downTo 1) {
            val leftTail = leftWords.takeLast(k).map(normalizeWord)
            val rightHead = rightWords.take(k).map(normalizeWord)
            if (leftTail.all { it.isNotEmpty() } && leftTail == rightHead) {
                overlapCount = k
                break
            }
        }
        return if (overlapCount > 0) {
            val remainder = rightWords.drop(overlapCount).joinToString(" ")
            if (remainder.isBlank()) left else "$left $remainder"
        } else {
            "$left $right"
        }
    }
}
