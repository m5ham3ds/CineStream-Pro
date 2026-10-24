package com.example.extension.managed.playback

import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase 05Q.5-C: Unified Playback Attempt Cancellation Context.
 *
 * Holds attempt-scoped identity and tracks candidates cancelled within the same playback attempt.
 * Ensures that if Candidate A is cancelled (e.g. by tapping 'X' during a Cloudflare challenge),
 * Candidate A is permanently skipped within this attempt across:
 * - PlaybackOrchestrator candidate loop
 * - Fallback discoverServers / inspectAndCacheMedia
 * - Cached server fallthrough extraction
 *
 * A new user interaction (e.g. tapping Play anew or Retrying) creates a NEW PlaybackAttempt, allowing
 * Candidate A to be retried if requested by the user.
 */
class PlaybackAttempt(
    val attemptId: String = "attempt_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
) {
    private val cancelledCandidates = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun cancelCandidate(candidateId: String?) {
        if (!candidateId.isNullOrBlank()) {
            val lower = candidateId.trim().lowercase()
            cancelledCandidates.add(lower)
            val cleanKey = lower.removePrefix("ext_").removePrefix("scraper_")
            cancelledCandidates.add(cleanKey)
            cancelledCandidates.add("ext_$cleanKey")
        }
    }

    fun isCandidateCancelled(candidateId: String?): Boolean {
        if (candidateId.isNullOrBlank()) return false
        val lower = candidateId.trim().lowercase()
        val clean = lower.removePrefix("ext_").removePrefix("scraper_")
        return cancelledCandidates.contains(lower) || cancelledCandidates.contains(clean)
    }

    val cancelledCandidateIds: Set<String>
        get() = cancelledCandidates.toSet()

    val hasCancelledCandidates: Boolean
        get() = cancelledCandidates.isNotEmpty()

    companion object {
        private val currentAttemptRef = AtomicReference<PlaybackAttempt?>(null)

        fun createNew(): PlaybackAttempt {
            val attempt = PlaybackAttempt()
            currentAttemptRef.set(attempt)
            return attempt
        }

        fun current(): PlaybackAttempt? = currentAttemptRef.get()

        fun getOrCreate(): PlaybackAttempt {
            val existing = currentAttemptRef.get()
            if (existing != null) return existing
            val newAttempt = PlaybackAttempt()
            return if (currentAttemptRef.compareAndSet(null, newAttempt)) newAttempt else currentAttemptRef.get() ?: newAttempt
        }

        fun clearCurrent() {
            currentAttemptRef.set(null)
        }
    }
}
