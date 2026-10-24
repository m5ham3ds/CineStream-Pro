package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.SupportMessage
import kotlinx.coroutines.flow.Flow

@Dao
interface SupportDao {
    @Query("SELECT * FROM support_messages ORDER BY timestamp ASC")
    fun getAllMessages(): Flow<List<SupportMessage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: SupportMessage)
    
    @Query("SELECT COUNT(*) FROM support_messages")
    suspend fun getMessageCount(): Int

    @Query("DELETE FROM support_messages")
    suspend fun clearMessages()
}
