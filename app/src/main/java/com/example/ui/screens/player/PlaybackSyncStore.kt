package com.example.ui.screens.player

import android.content.Context
import com.example.data.repository.HistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

object PlaybackSyncStore {
    private val positionMap = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(Dispatchers.IO)

    fun setPosition(key: String, position: Long) {
        if (key.isNotBlank() && position >= 0L) {
            positionMap[key] = position
            if (key.contains("_")) {
                val parentKey = key.substringBeforeLast("_")
                if (parentKey.isNotBlank()) {
                    positionMap[parentKey] = position
                }
            }
        }
    }

    fun setPositionAndPersist(
        context: Context,
        key: String,
        position: Long,
        duration: Long,
        title: String? = null,
        posterUrl: String? = null,
        isMovie: Boolean = true
    ) {
        if (key.isBlank() || duration <= 0L || position < 10000L || position >= duration) return
        setPosition(key, position)
        scope.launch {
            try {
                val repo = HistoryRepository(context)
                val canonicalId = if (!isMovie && key.contains("_")) key.substringBeforeLast("_") else key
                repo.updateWatchProgress(
                    id = canonicalId,
                    position = position,
                    duration = duration,
                    title = title,
                    posterUrl = posterUrl,
                    isMovie = isMovie
                )
                val mediaId = if (key.contains("_")) key.substringBeforeLast("_") else key
                val epId = if (key.contains("_")) key.substringAfterLast("_") else null
                com.example.utils.LastPlaybackStore.updatePosition(
                    context = context,
                    mediaId = mediaId,
                    episodeId = epId,
                    positionMillis = position,
                    durationMillis = duration
                )
            } catch (e: Exception) {
                // Ignore transient errors
            }
        }
    }

    fun getPosition(key: String): Long {
        if (key.isBlank()) return 0L
        return positionMap[key] ?: 0L
    }

    fun clearPosition(key: String) {
        positionMap.remove(key)
    }

    fun clearAll() {
        positionMap.clear()
    }

    fun formatTime(millis: Long): String {
        if (millis <= 0L) return "00:00"
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun getProgressFraction(position: Long, duration: Long): Float {
        if (duration <= 0L || position <= 0L) return 0f
        return (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    }
}
