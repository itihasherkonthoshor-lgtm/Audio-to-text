package com.example.data.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import com.example.domain.RecordingState
import com.example.domain.RecordingStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AudioRecorderManager(private val context: Context) {

    private var mediaRecorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var tickerJob: Job? = null
    private var accumulatedMs: Long = 0L
    private var segmentStartMs: Long = 0L

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun startRecording(scope: CoroutineScope): Result<Unit> {
        return runCatching {
            releaseRecorder()
            val dir = File(context.cacheDir, "recordings").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "Recording_$stamp.m4a")
            outputFile = file

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }

            mediaRecorder = recorder
            accumulatedMs = 0L
            segmentStartMs = System.currentTimeMillis()
            _state.value = RecordingState(
                status = RecordingStatus.RECORDING,
                durationMs = 0L,
                amplitudes = emptyList(),
                savedFilePath = null
            )
            startTicker(scope)
        }.onFailure {
            releaseRecorder()
            _state.value = RecordingState()
        }
    }

    fun pauseRecording(): Result<Unit> = runCatching {
        val recorder = mediaRecorder ?: return@runCatching
        if (_state.value.status != RecordingStatus.RECORDING) return@runCatching
        recorder.pause()
        accumulatedMs += (System.currentTimeMillis() - segmentStartMs).coerceAtLeast(0L)
        tickerJob?.cancel()
        _state.value = _state.value.copy(
            status = RecordingStatus.PAUSED,
            durationMs = accumulatedMs
        )
    }

    fun resumeRecording(scope: CoroutineScope): Result<Unit> = runCatching {
        val recorder = mediaRecorder ?: return@runCatching
        if (_state.value.status != RecordingStatus.PAUSED) return@runCatching
        recorder.resume()
        segmentStartMs = System.currentTimeMillis()
        _state.value = _state.value.copy(status = RecordingStatus.RECORDING)
        startTicker(scope)
    }

    fun stopAndSave(): Result<File> = runCatching {
        tickerJob?.cancel()
        val recorder = mediaRecorder ?: error("Recorder not active")
        val file = outputFile ?: error("Output file missing")

        if (_state.value.status == RecordingStatus.RECORDING) {
            accumulatedMs += (System.currentTimeMillis() - segmentStartMs).coerceAtLeast(0L)
        }

        try {
            recorder.stop()
        } finally {
            recorder.reset()
            recorder.release()
            mediaRecorder = null
        }

        _state.value = _state.value.copy(
            status = RecordingStatus.SAVED,
            durationMs = accumulatedMs,
            savedFilePath = file.absolutePath
        )
        file
    }.onFailure {
        releaseRecorder()
        _state.value = RecordingState()
    }

    fun discard() {
        tickerJob?.cancel()
        releaseRecorder()
        outputFile?.let { runCatching { it.delete() } }
        outputFile = null
        accumulatedMs = 0L
        _state.value = RecordingState()
    }

    fun resetToIdle() {
        tickerJob?.cancel()
        releaseRecorder()
        outputFile = null
        accumulatedMs = 0L
        _state.value = RecordingState()
    }

    private fun startTicker(scope: CoroutineScope) {
        tickerJob?.cancel()
        tickerJob = scope.launch(Dispatchers.Main) {
            while (isActive && _state.value.status == RecordingStatus.RECORDING) {
                val rawAmp = runCatching { mediaRecorder?.maxAmplitude ?: 0 }.getOrDefault(0)
                val normalized = (rawAmp / 32767f).coerceIn(0.04f, 1f)
                val elapsed = accumulatedMs + (System.currentTimeMillis() - segmentStartMs).coerceAtLeast(0L)
                val updatedAmps = (_state.value.amplitudes + normalized).takeLast(48)
                _state.value = _state.value.copy(
                    durationMs = elapsed,
                    amplitudes = updatedAmps
                )
                delay(75L)
            }
        }
    }

    private fun releaseRecorder() {
        mediaRecorder?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.reset() }
            runCatching { recorder.release() }
        }
        mediaRecorder = null
    }
}
