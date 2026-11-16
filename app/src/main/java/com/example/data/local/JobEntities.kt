package com.example.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "transcription_jobs")
data class TranscriptionJobEntity(
    @PrimaryKey
    val jobId: String,
    val remoteJobId: String? = null,
    val sourceFilePath: String,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val sampleRate: Int = 16000,
    val channels: Int = 1,
    val fileSha256: String = "",
    val languageCode: String,
    val modeId: String,
    val engineId: String,
    val keepOriginalAudio: Boolean,
    val autoSaveTranscript: Boolean,
    val status: String,
    val stageName: String,
    val uploadPercent: Int = 0,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val failedChunks: Int = 0,
    val currentChunkIndex: Int = 0,
    val avgChunkProcessingMs: Long = 0L,
    val etaSeconds: Long? = null,
    val resultTranscriptId: Long? = null,
    val errorType: String? = null,
    val errorDetail: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "transcription_chunks",
    primaryKeys = ["jobId", "chunkIndex"],
    indices = [Index("jobId")]
)
data class TranscriptionChunkEntity(
    val jobId: String,
    val chunkIndex: Int,
    val filePath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val sha256: String,
    val startOffsetMs: Long,
    val durationMs: Long,
    val overlapBeforeMs: Long,
    val isTemporary: Boolean,
    val status: String,
    val retryCount: Int = 0,
    val exactText: String? = null,
    val cleanText: String? = null,
    val detectedLanguage: String? = null,
    val segmentsJson: String = "[]",
    val processingDurationMs: Long = 0L,
    val errorDetail: String? = null,
    val permanentError: Boolean = false
)

@Dao
interface TranscriptionJobDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertJob(job: TranscriptionJobEntity)

    @Update
    suspend fun updateJob(job: TranscriptionJobEntity)

    @Query("SELECT * FROM transcription_jobs WHERE jobId = :jobId")
    suspend fun getJob(jobId: String): TranscriptionJobEntity?

    @Query("SELECT * FROM transcription_jobs WHERE jobId = :jobId")
    fun observeJob(jobId: String): Flow<TranscriptionJobEntity?>

    @Query(
        "SELECT * FROM transcription_jobs WHERE status NOT IN ('CANCELLED') ORDER BY updatedAt DESC LIMIT 1"
    )
    fun observeLatestJob(): Flow<TranscriptionJobEntity?>

    @Query(
        "SELECT * FROM transcription_jobs WHERE status IN ('QUEUED', 'CHUNKING', 'UPLOADING', 'PROCESSING') ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getLatestActiveJob(): TranscriptionJobEntity?

    @Query("SELECT * FROM transcription_jobs WHERE status IN ('PARTIAL_FAILED', 'FAILED') ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestFailedJob(): TranscriptionJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChunks(chunks: List<TranscriptionChunkEntity>)

    @Update
    suspend fun updateChunk(chunk: TranscriptionChunkEntity)

    @Query("SELECT * FROM transcription_chunks WHERE jobId = :jobId ORDER BY chunkIndex ASC")
    suspend fun getChunksForJob(jobId: String): List<TranscriptionChunkEntity>

    @Query("SELECT * FROM transcription_chunks WHERE jobId = :jobId ORDER BY chunkIndex ASC")
    fun observeChunksForJob(jobId: String): Flow<List<TranscriptionChunkEntity>>

    @Query("SELECT * FROM transcription_chunks WHERE jobId = :jobId AND chunkIndex = :chunkIndex")
    suspend fun getChunk(jobId: String, chunkIndex: Int): TranscriptionChunkEntity?

    @Query("DELETE FROM transcription_chunks WHERE jobId = :jobId")
    suspend fun deleteChunksForJob(jobId: String)

    @Query("DELETE FROM transcription_jobs WHERE jobId = :jobId")
    suspend fun deleteJob(jobId: String)
}
