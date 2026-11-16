package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TranscriptEntity::class,
        TranscriptionJobEntity::class,
        TranscriptionChunkEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transcriptDao(): TranscriptDao
    abstract fun jobDao(): TranscriptionJobDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcription_jobs` (
                        `jobId` TEXT NOT NULL,
                        `remoteJobId` TEXT,
                        `sourceFilePath` TEXT NOT NULL,
                        `fileName` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `sampleRate` INTEGER NOT NULL,
                        `channels` INTEGER NOT NULL,
                        `fileSha256` TEXT NOT NULL,
                        `languageCode` TEXT NOT NULL,
                        `modeId` TEXT NOT NULL,
                        `engineId` TEXT NOT NULL,
                        `keepOriginalAudio` INTEGER NOT NULL,
                        `autoSaveTranscript` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `stageName` TEXT NOT NULL,
                        `uploadPercent` INTEGER NOT NULL,
                        `totalChunks` INTEGER NOT NULL,
                        `completedChunks` INTEGER NOT NULL,
                        `failedChunks` INTEGER NOT NULL,
                        `currentChunkIndex` INTEGER NOT NULL,
                        `avgChunkProcessingMs` INTEGER NOT NULL,
                        `etaSeconds` INTEGER,
                        `resultTranscriptId` INTEGER,
                        `errorType` TEXT,
                        `errorDetail` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`jobId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transcription_chunks` (
                        `jobId` TEXT NOT NULL,
                        `chunkIndex` INTEGER NOT NULL,
                        `filePath` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `sha256` TEXT NOT NULL,
                        `startOffsetMs` INTEGER NOT NULL,
                        `durationMs` INTEGER NOT NULL,
                        `overlapBeforeMs` INTEGER NOT NULL,
                        `isTemporary` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `exactText` TEXT,
                        `cleanText` TEXT,
                        `detectedLanguage` TEXT,
                        `segmentsJson` TEXT NOT NULL,
                        `processingDurationMs` INTEGER NOT NULL,
                        `errorDetail` TEXT,
                        `permanentError` INTEGER NOT NULL,
                        PRIMARY KEY(`jobId`, `chunkIndex`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transcription_chunks_jobId` ON `transcription_chunks` (`jobId`)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "audiotext_pro.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
