package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.domain.TranscriptionMode

@Entity(tableName = "transcripts")
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val originalFileName: String,
    val audioFilePath: String?,
    val audioSizeBytes: Long,
    val audioDurationMs: Long,
    val requestedLanguage: String,
    val detectedLanguage: String,
    val activeMode: String = TranscriptionMode.EXACT.id,
    val exactText: String,
    val cleanText: String? = null,
    val segmentsJson: String = "[]",
    val engineUsed: String,
    val isSavedToHistory: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun textForMode(mode: TranscriptionMode): String = when (mode) {
        TranscriptionMode.EXACT -> exactText
        TranscriptionMode.CLEAN -> cleanText?.takeIf { it.isNotBlank() } ?: exactText
    }
}
