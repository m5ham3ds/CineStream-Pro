package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.data.model.WatchedEpisode
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchedEpisodeDao {
    @Query("SELECT * FROM watched_episodes")
    fun getAllWatched(): Flow<List<WatchedEpisode>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(episode: WatchedEpisode)

    @Query("DELETE FROM watched_episodes WHERE id = :id")
    suspend fun deleteById(id: String)
    @Query("DELETE FROM watched_episodes")
    suspend fun clearAll()
}