package com.example.extension.managed.runtime

import com.example.domain.models.MediaVariantMode
import com.example.domain.models.ProviderSourceCandidate
import com.example.domain.models.VariantLanguage
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ManagedExtension
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

data class WorkDiscoveryCheckpoint(
    val workId: String,
    val contentType: ContentType,
    val variantMode: MediaVariantMode,
    val audioLanguage: VariantLanguage,
    val season: Int,
    val episode: Int,
    val completedProviderIds: Set<String>,
    val candidates: List<ProviderSourceCandidate>,
    val savedTimestamp: Long = System.currentTimeMillis()
) {
    fun isExpired(timeoutMs: Long = 5 * 60 * 1000L): Boolean {
        return (System.currentTimeMillis() - savedTimestamp) > timeoutMs
    }

    fun matches(
        otherWorkId: String,
        otherMode: MediaVariantMode,
        otherLanguage: VariantLanguage,
        otherSeason: Int,
        otherEpisode: Int
    ): Boolean {
        return workId == otherWorkId &&
                variantMode == otherMode &&
                audioLanguage == otherLanguage &&
                season == otherSeason &&
                episode == otherEpisode
    }
}

/**
 * Manages Discovery Session Lifecycle & 5-Minute Resume Timeout (Phase 09B-B Section 10).
 *
 * Rules:
 * 1. Active on Work Page: Tracks provider progress while user is inspecting work.
 * 2. On Leaving Page: Immediately stops new network requests; saves checkpoint for up to 5 minutes.
 * 3. On Return + Press Play within 5 min: Resumes from the first incomplete provider without re-querying completed ones.
 * 4. Return without pressing Play: DOES NOT auto-resume.
 * 5. After 5 minutes: Checkpoint expires; session resources are released; completed cache is preserved.
 * 6. Work / Episode Change: Checkpoint for previous work is canceled and discarded.
 */
class WorkDiscoverySessionManager {

    companion object {
        val INSTANCE = WorkDiscoverySessionManager()
        const val RESUME_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
    }

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeCheckpoint: WorkDiscoveryCheckpoint? = null

    private val inFlightCandidates = ConcurrentHashMap<String, ProviderSourceCandidate>()
    private val completedProviders = ConcurrentHashMap.newKeySet<String>()

    fun registerActiveJob(job: Job) {
        activeJob?.cancel()
        activeJob = job
    }

    fun recordCompletedProvider(providerId: String, candidate: ProviderSourceCandidate? = null) {
        completedProviders.add(providerId)
        if (candidate != null) {
            inFlightCandidates[providerId] = candidate
        }
    }

    fun onLeaveWorkPage(
        workId: String,
        contentType: ContentType,
        variantMode: MediaVariantMode,
        audioLanguage: VariantLanguage,
        season: Int = 1,
        episode: Int = 1
    ) {
        // 1. Immediately cancel in-flight network requests
        activeJob?.cancel()
        activeJob = null

        // 2. Save checkpoint valid for up to 5 minutes
        activeCheckpoint = WorkDiscoveryCheckpoint(
            workId = workId,
            contentType = contentType,
            variantMode = variantMode,
            audioLanguage = audioLanguage,
            season = season,
            episode = episode,
            completedProviderIds = completedProviders.toSet(),
            candidates = inFlightCandidates.values.toList(),
            savedTimestamp = System.currentTimeMillis()
        )
    }

    /**
     * Called when the user presses Play after returning.
     * Evaluates whether the session can be resumed within the 5-minute timeout window.
     */
    fun resumeOnPlay(
        workId: String,
        variantMode: MediaVariantMode,
        audioLanguage: VariantLanguage,
        season: Int,
        episode: Int,
        allEligibleProviders: List<ManagedExtension>
    ): List<ManagedExtension>? {
        val cp = activeCheckpoint ?: return null

        // 1. Check if checkpoint matches work, variant, and episode
        if (!cp.matches(workId, variantMode, audioLanguage, season, episode)) {
            clearCheckpoint()
            return null
        }

        // 2. Check 5-minute timeout
        if (cp.isExpired(RESUME_TIMEOUT_MS)) {
            clearCheckpoint()
            return null
        }

        // 3. Return only providers that have NOT yet been completed
        val remaining = allEligibleProviders.filter { ext ->
            !cp.completedProviderIds.contains(ext.id)
        }

        return remaining
    }

    fun onWorkChanged(newWorkId: String, season: Int = 1, episode: Int = 1) {
        val cp = activeCheckpoint
        if (cp != null && (cp.workId != newWorkId || cp.season != season || cp.episode != episode)) {
            clearCheckpoint()
        }
    }

    fun clearCheckpoint() {
        activeJob?.cancel()
        activeJob = null
        activeCheckpoint = null
        inFlightCandidates.clear()
        completedProviders.clear()
    }

    fun getCheckpoint(): WorkDiscoveryCheckpoint? = activeCheckpoint
}
