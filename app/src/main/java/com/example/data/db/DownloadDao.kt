package com.example.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.DownloadItem
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download_items")
    fun getAllItems(): Flow<List<DownloadItem>>

    @Query("SELECT * FROM download_items")
    suspend fun getAllItemsSync(): List<DownloadItem>

    @Query("SELECT * FROM download_items WHERE id = :itemId LIMIT 1")
    fun getItemByIdFlow(itemId: String): Flow<DownloadItem?>

    @Query("SELECT * FROM download_items WHERE id = :itemId LIMIT 1")
    suspend fun getItemById(itemId: String): DownloadItem?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: DownloadItem)

    @Update
    suspend fun updateItem(item: DownloadItem)

    @Delete
    suspend fun deleteItem(item: DownloadItem)
}