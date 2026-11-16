package com.example.domain

import android.net.Uri
import com.squareup.moshi.JsonClass

enum class AppLanguage(val code: String) {
    BENGALI("bn"),
    ENGLISH("en");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code == code } ?: BENGALI
    }
}

enum class AudioLanguage(val code: String) {
    AUTO("auto"),
    BENGALI("bn"),
    ENGLISH("en"),
    HINDI("hi");

    companion object {
        fun fromCode(code: String?): AudioLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: AUTO
    }
}

enum class TranscriptionMode(val id: String) {
    EXACT("exact"),
    CLEAN("clean");

    companion object {
        fun fromId(id: String?): TranscriptionMode =
            entries.firstOrNull { it.id == id } ?: EXACT
    }
}

enum class ThemePreference(val id: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromId(id: String?): ThemePreference =
            entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class TranscriptionEngine(val id: String) {
    FIREBASE_GEMINI("firebase_gemini"),
    CUSTOM_BACKEND_OPENAI("custom_backend_openai");

    companion object {
        fun fromId(id: String?): TranscriptionEngine =
            entries.firstOrNull { it.id == id } ?: FIREBASE_GEMINI
    }
}

enum class ExportFormat(val extension: String, val mimeType: String) {
    TXT("txt", "text/plain"),
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    SRT("srt", "application/x-subrip")
}

enum class ChunkStatus {
    QUEUED,
    UPLOADING,
    UPLOADED,
    PROCESSING,
    COMPLETED,
    RETRYING,
    FAILED;

    companion object {
        fun fromName(name: String?): ChunkStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: QUEUED
    }
}

enum class JobStatus {
    QUEUED,
    CHUNKING,
    UPLOADING,
    PROCESSING,
    PARTIAL_FAILED,
    COMPLETED,
    FAILED,
    CANCELLED;

    companion object {
        fun fromName(name: String?): JobStatus =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: QUEUED
    }
}

@JsonClass(generateAdapter = true)
data class TranscriptSegment(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String
)

data class AudioMetadata(
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Int,
    val mimeType: String
)

data class SelectedAudioInfo(
    val sourceUri: Uri,
    val localTempPath: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val isRecorded: Boolean,
    val sampleRate: Int = 0,
    val channels: Int = 0
)

enum class RecordingStatus {
    IDLE,
    RECORDING,
    PAUSED,
    SAVED
}

data class RecordingState(
    val status: RecordingStatus = RecordingStatus.IDLE,
    val durationMs: Long = 0L,
    val amplitudes: List<Float> = emptyList(),
    val savedFilePath: String? = null
)

sealed interface TranscriptionStage {
    data object Idle : TranscriptionStage
    data object Validating : TranscriptionStage
    data class Chunking(val current: Int, val total: Int) : TranscriptionStage
    data class Uploading(val chunkIndex: Int, val totalChunks: Int, val uploadPercent: Int) : TranscriptionStage
    data class Processing(val chunkIndex: Int, val totalChunks: Int) : TranscriptionStage
    data object CleaningText : TranscriptionStage
}

data class ChunkProgressItem(
    val index: Int,
    val status: ChunkStatus,
    val startOffsetMs: Long,
    val durationMs: Long,
    val retryCount: Int = 0,
    val error: String? = null
)

data class TranscriptionProgress(
    val jobId: String? = null,
    val isRunning: Boolean = false,
    val jobStatus: JobStatus = JobStatus.QUEUED,
    val fileName: String = "",
    val stage: TranscriptionStage = TranscriptionStage.Idle,
    val uploadPercent: Int = 0,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val failedChunks: Int = 0,
    val currentChunkIndex: Int = 0,
    val etaSeconds: Long? = null,
    val failedChunkIndices: List<Int> = emptyList(),
    val canRetryFailedChunks: Boolean = false,
    val resultTranscriptId: Long? = null,
    val error: AppError? = null
)

enum class AppErrorType {
    NO_INTERNET,
    UNSUPPORTED_FORMAT,
    EMPTY_AUDIO,
    FILE_TOO_LARGE,
    MIC_PERMISSION_DENIED,
    API_AUTH_FAILURE,
    API_RATE_LIMIT,
    SERVER_UNAVAILABLE,
    TRANSCRIPTION_FAILED,
    INSUFFICIENT_STORAGE,
    EXPORT_FAILED,
    NO_TIMESTAMPS_FOR_SRT,
    UNKNOWN
}

data class AppError(
    val type: AppErrorType,
    val detail: String? = null
)

data class AppSettings(
    val appLanguage: AppLanguage = AppLanguage.BENGALI,
    val defaultMode: TranscriptionMode = TranscriptionMode.EXACT,
    val defaultAudioLanguage: AudioLanguage = AudioLanguage.BENGALI,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val saveOriginalAudioAuto: Boolean = true,
    val saveTranscriptsAuto: Boolean = true,
    val autoStartOnAudioSelect: Boolean = true,
    val engine: TranscriptionEngine = TranscriptionEngine.FIREBASE_GEMINI,
    val backendBaseUrl: String = "http://10.0.2.2:3000",
    val backendAuthToken: String = ""
)

data class ConnectionCheckResult(
    val isChecking: Boolean = false,
    val isConnected: Boolean? = null,
    val statusDetail: String = ""
)

data class TranscriptChunkResult(
    val exactText: String,
    val cleanText: String?,
    val detectedLanguage: String,
    val segments: List<TranscriptSegment>
)
