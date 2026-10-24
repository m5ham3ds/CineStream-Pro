package com.example.extension.managed.playback

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.QualitySource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Encapsulates the lifecycle, candidate state, and cancellation of an active playback discovery session.
 */
data class PlaybackSession(
    val mediaId: String,
    val title: String,
    val originalTitle: String? = null,
    val arabicTitle: String? = null,
    val year: String = "",
    val isMovie: Boolean = true,
    val season: Int = 1,
    val episode: Int = 1,
    val contentType: ContentType = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
    val variantMode: String = "subtitled",
    val audioLanguage: String = "ar",
    val sessionJob: Job = Job(),
    val attempt: PlaybackAttempt = PlaybackAttempt.getOrCreate(),
    val generation: Long = 0L,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    private val isCancelled = AtomicBoolean(false)
    val orderedCandidates = CopyOnWriteArrayList<ManagedExtension>()
    val discoveredSources = CopyOnWriteArrayList<PlaybackSource>()
    val discoveredQualities = CopyOnWriteArrayList<QualitySource>()

    val firstPlayableDeferred = CompletableDeferred<PlaybackSource>()

    fun cancel() {
        if (isCancelled.compareAndSet(false, true)) {
            sessionJob.cancel()
            if (!firstPlayableDeferred.isCompleted) {
                firstPlayableDeferred.cancel()
            }
        }
    }

    val isActive: Boolean
        get() = !isCancelled.get() && sessionJob.isActive
}
