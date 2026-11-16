package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptDao {

    @Query("SELECT * FROM transcripts WHERE isSavedToHistory = 1 ORDER BY createdAt DESC")
    fun observeAllSaved(): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE isSavedToHistory = 1 ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentSaved(limit: Int): Flow<List<TranscriptEntity>>

    @Query(
        """
        SELECT * FROM transcripts
        WHERE isSavedToHistory = 1
          AND (title LIKE '%' || :query || '%'
            OR originalFileName LIKE '%' || :query || '%'
            OR exactText LIKE '%' || :query || '%'
            OR cleanText LIKE '%' || :query || '%'
            OR detectedLanguage LIKE '%' || :query || '%')
        ORDER BY createdAt DESC
        """
    )
    fun searchSaved(query: String): Flow<List<TranscriptEntity>>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    fun observeById(id: Long): Flow<TranscriptEntity?>

    @Query("SELECT * FROM transcripts WHERE id = :id")
    suspend fun getById(id: Long): TranscriptEntity?

    @Query("SELECT * FROM transcripts WHERE audioFilePath IS NOT NULL AND audioFilePath != '' ORDER BY createdAt DESC")
    fun observeItemsWithAudio(): Flow<List<TranscriptEntity>>

    @Query("SELECT audioFilePath FROM transcripts WHERE audioFilePath IS NOT NULL AND audioFilePath != ''")
    suspend fun getAllAudioPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: TranscriptEntity): Long

    @Update
    suspend fun update(entity: TranscriptEntity)

    @Query("UPDATE transcripts SET title = :newTitle, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, newTitle: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transcripts SET audioFilePath = NULL, updatedAt = :updatedAt WHERE id = :id")
    suspend fun clearAudioPath(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE transcripts SET audioFilePath = NULL, updatedAt = :updatedAt")
    suspend fun clearAllAudioPaths(updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM transcripts WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transcripts")
    suspend fun deleteAll()
}
