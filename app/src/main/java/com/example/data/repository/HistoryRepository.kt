package com.example.data.repository

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.data.model.HistoryItem
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HistoryRepository(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val historyDao = db.historyDao()
    private val downloadDao = db.downloadDao()
    private val auth: FirebaseAuth? = try { FirebaseAuth.getInstance() } catch (_: Exception) { null }
    private val firestore: FirebaseFirestore? = try { FirebaseFirestore.getInstance() } catch (_: Exception) { null }

    private val dismissedPrefs = context.getSharedPreferences("history_dismissed_prefs", Context.MODE_PRIVATE)

    init {
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            try {
                historyDao.deleteInvalidItems()
            } catch (_: Exception) {}
        }
    }

    private fun getDismissedIds(): Set<String> {
        return dismissedPrefs.getStringSet("dismissed_ids", emptySet()) ?: emptySet()
    }

    private fun dismissItem(id: String) {
        val current = getDismissedIds().toMutableSet()
        current.add(id)
        if (id.contains("_")) {
            current.add(id.substringBeforeLast("_"))
        }
        dismissedPrefs.edit().putStringSet("dismissed_ids", current).apply()
    }

    private fun unDismissItem(id: String) {
        val current = getDismissedIds().toMutableSet()
        if (current.remove(id) || (id.contains("_") && current.remove(id.substringBeforeLast("_")))) {
            dismissedPrefs.edit().putStringSet("dismissed_ids", current).apply()
        }
    }

    fun getHistoryItems(): Flow<List<HistoryItem>> {
        return historyDao.getAllHistory().map { history ->
            val dismissed = getDismissedIds()
            // Only include items that have actual positive duration and started playback (at least 10s)
            // and have not already reached the end (>95% or within last 15s)
            history.filter { 
                !dismissed.contains(it.id) && 
                !dismissed.contains(it.id.substringBeforeLast("_")) &&
                it.durationMillis > 0L && 
                it.positionMillis >= 2000L &&
                it.positionMillis < (it.durationMillis * 0.95f).toLong() &&
                (it.durationMillis <= 30000L || it.positionMillis < (it.durationMillis - 15000L))
            }.map { item ->
                val isEpisodeOrSeries = !item.isMovie ||
                    item.id.contains("_") ||
                    item.title.contains(" - S") ||
                    item.title.contains(" S") ||
                    item.title.contains("حلقة") ||
                    item.title.contains("الموسم") ||
                    item.title.contains("الحلقة")
                if (isEpisodeOrSeries && item.isMovie) {
                    item.copy(isMovie = false)
                } else {
                    item
                }
            }
        }
    }

    suspend fun addToHistory(item: HistoryItem) {
        if (item.durationMillis <= 0L || item.positionMillis < 2000L) {
            return
        }
        val isEpisodeOrSeries = !item.isMovie ||
            item.id.contains("_") ||
            item.title.contains(" - S") ||
            item.title.contains(" S") ||
            item.title.contains("حلقة") ||
            item.title.contains("الموسم") ||
            item.title.contains("الحلقة")
        val effectiveIsMovie = if (isEpisodeOrSeries) false else item.isMovie
        val canonicalId = if (!effectiveIsMovie && item.id.contains("_")) item.id.substringBeforeLast("_") else item.id
        val itemToSave = item.copy(id = canonicalId, isMovie = effectiveIsMovie)
        unDismissItem(canonicalId)
        historyDao.insertHistory(itemToSave)
        auth?.currentUser?.uid?.let { uid ->
            try {
                firestore?.collection("users")?.document(uid)?.collection("history")?.document(canonicalId)
                    ?.set(itemToSave, com.google.firebase.firestore.SetOptions.merge())
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun getHistoryItem(id: String): HistoryItem? {
        val item = historyDao.getHistoryItemById(id)
        if (item != null) return item
        if (id.contains("_")) {
            return historyDao.getHistoryItemById(id.substringBeforeLast("_"))
        }
        return null
    }

    suspend fun deleteHistoryItem(id: String) {
        dismissItem(id)
        val canonicalId = if (id.contains("_")) id.substringBeforeLast("_") else id
        historyDao.deleteHistoryItemById(id)
        if (id.contains("_")) {
            historyDao.deleteHistoryItemById(canonicalId)
        }
        com.example.ui.screens.player.PlaybackSyncStore.clearPosition(id)
        com.example.ui.screens.player.PlaybackSyncStore.clearPosition(canonicalId)
        com.example.utils.LastPlaybackStore.clearPlayback(context, id)
        if (id.contains("_")) {
            com.example.utils.LastPlaybackStore.clearPlayback(context, canonicalId, id.substringAfter("_"))
            com.example.utils.LastPlaybackStore.clearPlayback(context, canonicalId)
        }
        auth?.currentUser?.uid?.let { uid ->
            try {
                firestore?.collection("users")?.document(uid)?.collection("history")?.document(id)?.delete()
                if (id.contains("_")) {
                    firestore?.collection("users")?.document(uid)?.collection("history")?.document(canonicalId)?.delete()
                }
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    suspend fun updateWatchProgress(
        id: String,
        position: Long,
        duration: Long,
        title: String? = null,
        posterUrl: String? = null,
        isMovie: Boolean = true
    ) {
        if (duration <= 0L || position < 2000L) return
        val canonicalId = id
        val isCompleted = position >= (duration * 0.95f).toLong() || (duration > 30000L && position >= duration - 15000L)
        if (isCompleted) {
            deleteHistoryItem(canonicalId)
            return
        }
        unDismissItem(canonicalId)
        val now = System.currentTimeMillis()
        val isEpisodeOrSeries = !isMovie ||
            id.contains("_") ||
            (title?.contains(" - S") == true) ||
            (title?.contains(" S") == true) ||
            (title?.contains("حلقة") == true) ||
            (title?.contains("الموسم") == true) ||
            (title?.contains("الحلقة") == true)
        val effectiveIsMovie = if (isEpisodeOrSeries) false else isMovie
        val existing = historyDao.getHistoryItemById(canonicalId)
        val itemToSave = if (existing != null) {
            existing.copy(
                id = canonicalId,
                positionMillis = position,
                durationMillis = duration,
                timestamp = now,
                title = if (!title.isNullOrBlank()) title else existing.title,
                posterUrl = if (!posterUrl.isNullOrBlank()) posterUrl else existing.posterUrl,
                isMovie = effectiveIsMovie
            )
        } else {
            HistoryItem(
                id = canonicalId,
                title = title ?: "",
                posterUrl = posterUrl ?: "",
                isMovie = effectiveIsMovie,
                timestamp = now,
                positionMillis = position,
                durationMillis = duration
            )
        }
        historyDao.insertHistory(itemToSave)
        auth?.currentUser?.uid?.let { uid ->
            try {
                firestore?.collection("users")?.document(uid)?.collection("history")?.document(canonicalId)
                    ?.set(itemToSave, com.google.firebase.firestore.SetOptions.merge())
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}