package com.example.data.db
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.HistoryItem
import kotlinx.coroutines.flow.Flow

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history_items WHERE durationMillis > 0 AND positionMillis >= 2000 ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<HistoryItem>>

    @Query("DELETE FROM history_items WHERE durationMillis <= 0 OR positionMillis < 2000")
    suspend fun deleteInvalidItems()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(item: HistoryItem)
    
    @Query("SELECT * FROM history_items WHERE id = :id LIMIT 1")
    suspend fun getHistoryItemById(id: String): HistoryItem?

    @Query("UPDATE history_items SET positionMillis = :position, durationMillis = :duration, timestamp = :timestamp WHERE id = :id")
    suspend fun updateProgress(id: String, position: Long, duration: Long, timestamp: Long)

    @Query("DELETE FROM history_items WHERE id = :id")
    suspend fun deleteHistoryItemById(id: String)

    @Query("DELETE FROM history_items")
    suspend fun clearHistory()
    @Query("DELETE FROM history_items")
    suspend fun clearAll()
}