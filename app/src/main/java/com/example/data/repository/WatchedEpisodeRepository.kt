package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.WatchedEpisode
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow

class WatchedEpisodeRepository(context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val dao = db.watchedEpisodeDao()
    private val auth = FirebaseAuth.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    fun getAllWatched(): Flow<List<WatchedEpisode>> = dao.getAllWatched()

    suspend fun markAsWatched(id: String) {
        val episode = WatchedEpisode(id)
        dao.insert(episode)
        auth.currentUser?.uid?.let { uid ->
            try {
                firestore.collection("users").document(uid).collection("watched_episodes").document(id)
                    .set(episode, com.google.firebase.firestore.SetOptions.merge())
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun markAsUnwatched(id: String) {
        dao.deleteById(id)
        auth.currentUser?.uid?.let { uid ->
            try {
                firestore.collection("users").document(uid).collection("watched_episodes").document(id).delete()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}