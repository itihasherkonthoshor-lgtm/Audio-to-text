package com.example.data.local

import android.content.Context
import com.example.domain.TranscriptSegment
import com.example.domain.TranscriptionMode
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptRepository(
    private val context: Context,
    private val dao: TranscriptDao
) {
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val segmentListType = Types.newParameterizedType(List::class.java, TranscriptSegment::class.java)
    private val segmentAdapter = moshi.adapter<List<TranscriptSegment>>(segmentListType)

    val allSaved: Flow<List<TranscriptEntity>> = dao.observeAllSaved()
    val itemsWithAudio: Flow<List<TranscriptEntity>> = dao.observeItemsWithAudio()

    fun recentSaved(limit: Int = 5): Flow<List<TranscriptEntity>> = dao.observeRecentSaved(limit)

    fun searchSaved(query: String): Flow<List<TranscriptEntity>> =
        if (query.isBlank()) allSaved else dao.searchSaved(query.trim())

    fun observeById(id: Long): Flow<TranscriptEntity?> = dao.observeById(id)

    suspend fun getById(id: Long): TranscriptEntity? = dao.getById(id)

    suspend fun insert(entity: TranscriptEntity): Long = dao.insert(entity)

    suspend fun saveToHistory(id: Long) {
        val current = dao.getById(id) ?: return
        dao.update(current.copy(isSavedToHistory = true, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updateTextForMode(id: Long, mode: TranscriptionMode, newText: String) {
        val current = dao.getById(id) ?: return
        val updated = when (mode) {
            TranscriptionMode.EXACT -> current.copy(
                exactText = newText,
                activeMode = mode.id,
                isSavedToHistory = true,
                updatedAt = System.currentTimeMillis()
            )
            TranscriptionMode.CLEAN -> current.copy(
                cleanText = newText,
                activeMode = mode.id,
                isSavedToHistory = true,
                updatedAt = System.currentTimeMillis()
            )
        }
        dao.update(updated)
    }

    suspend fun setActiveMode(id: Long, mode: TranscriptionMode, generatedCleanText: String? = null) {
        val current = dao.getById(id) ?: return
        val updated = current.copy(
            activeMode = mode.id,
            cleanText = generatedCleanText ?: current.cleanText,
            updatedAt = System.currentTimeMillis()
        )
        dao.update(updated)
    }

    suspend fun rename(id: Long, newTitle: String) {
        if (newTitle.isBlank()) return
        dao.rename(id, newTitle.trim())
    }

    suspend fun deleteAudioOnly(id: Long) = withContext(Dispatchers.IO) {
        val entity = dao.getById(id) ?: return@withContext
        entity.audioFilePath?.let { path ->
            runCatching { File(path).delete() }
        }
        dao.clearAudioPath(id)
    }

    suspend fun deleteTranscriptAndAudio(id: Long) = withContext(Dispatchers.IO) {
        val entity = dao.getById(id)
        entity?.audioFilePath?.let { path ->
            runCatching { File(path).delete() }
        }
        dao.deleteById(id)
    }

    suspend fun deleteAllAudioFiles() = withContext(Dispatchers.IO) {
        val paths = dao.getAllAudioPaths()
        paths.forEach { path -> runCatching { File(path).delete() } }
        val audioDir = File(context.filesDir, "audio")
        audioDir.listFiles()?.forEach { runCatching { it.delete() } }
        dao.clearAllAudioPaths()
    }

    suspend fun clearAllHistory() = withContext(Dispatchers.IO) {
        val paths = dao.getAllAudioPaths()
        paths.forEach { path -> runCatching { File(path).delete() } }
        val audioDir = File(context.filesDir, "audio")
        audioDir.listFiles()?.forEach { runCatching { it.delete() } }
        dao.deleteAll()
    }

    suspend fun calculateSavedAudioBytes(): Long = withContext(Dispatchers.IO) {
        val audioDir = File(context.filesDir, "audio")
        if (!audioDir.exists()) return@withContext 0L
        audioDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
    }

    fun encodeSegments(segments: List<TranscriptSegment>): String =
        runCatching { segmentAdapter.toJson(segments) }.getOrDefault("[]")

    fun decodeSegments(json: String): List<TranscriptSegment> =
        runCatching {
            if (json.isBlank()) emptyList()
            else segmentAdapter.fromJson(json).orEmpty()
        }.getOrDefault(emptyList())
}
