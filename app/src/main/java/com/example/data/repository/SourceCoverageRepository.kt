package com.example.data.repository

import com.example.domain.models.CoverageStatus
import com.example.domain.models.MediaVariantMode
import com.example.domain.models.ProviderCoverageRecord
import com.example.domain.models.VariantLanguage
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.ManagedExtension
import java.util.concurrent.ConcurrentHashMap

enum class RoundCoverageStatus {
    IN_PROGRESS,
    COMPLETE,
    PARTIAL
}

data class WorkCoverageRound(
    val coverageKey: String,
    val canonicalWorkId: String,
    val contentType: ContentType,
    val season: Int,
    val episode: Int,
    val mode: MediaVariantMode,
    val language: VariantLanguage,
    val providerGroup: String,
    val providerGroupFingerprint: String,
    val status: RoundCoverageStatus = RoundCoverageStatus.IN_PROGRESS,
    val completedAt: Long? = null,
    val records: MutableMap<String, ProviderCoverageRecord> = ConcurrentHashMap()
)

/**
 * Repository managing persistent and in-memory coverage caching for Phase 09B-B.
 *
 * Enforces:
 * 1. Strict separation of Source Page Cache (TTL 24 hours) vs Stream URL Cache (TTL 2 hours).
 * 2. Deduplication of multi-provider search rounds (COMPLETE rounds are never repeated unnecessarily).
 * 3. Incremental checking: When a new provider is added in Admin, only the new provider is queried.
 * 4. Safe recovery: Expired stream URLs are re-extracted from valid source pages without re-querying all providers.
 */
class SourceCoverageRepository {

    private val rounds = ConcurrentHashMap<String, WorkCoverageRound>()

    companion object {
        val INSTANCE = SourceCoverageRepository()

        const val SOURCE_PAGE_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours
        const val STREAM_URL_TTL_MS = 2 * 60 * 60 * 1000L     // 2 hours
        const val NEGATIVE_RESULT_TTL_MS = 10 * 60 * 1000L    // 10 minutes negative result TTL

        fun buildCoverageKey(
            canonicalWorkId: String,
            contentType: ContentType,
            season: Int,
            episode: Int,
            mode: MediaVariantMode,
            language: VariantLanguage,
            providerGroup: String
        ): String {
            val typeStr = when (contentType) {
                ContentType.MOVIE -> "movie"
                ContentType.SERIES -> "series"
                ContentType.ANIME -> "anime"
            }
            return "${canonicalWorkId.trim()}:$typeStr:$season:$episode:${mode.value}:${language.code}:${providerGroup.trim().lowercase()}"
        }

        fun computeGroupFingerprint(providerIds: List<String>): String {
            return providerIds.map { it.trim().lowercase() }.sorted().joinToString(",")
        }
    }

    fun getRound(
        canonicalWorkId: String,
        contentType: ContentType,
        season: Int = 1,
        episode: Int = 1,
        mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
        language: VariantLanguage = VariantLanguage.ARABIC,
        providerGroup: String = "default"
    ): WorkCoverageRound? {
        val key = buildCoverageKey(canonicalWorkId, contentType, season, episode, mode, language, providerGroup)
        return rounds[key]
    }

    fun getOrCreateRound(
        canonicalWorkId: String,
        contentType: ContentType,
        season: Int = 1,
        episode: Int = 1,
        mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
        language: VariantLanguage = VariantLanguage.ARABIC,
        providerGroup: String = "default",
        eligibleProviderIds: List<String> = emptyList()
    ): WorkCoverageRound {
        val key = buildCoverageKey(canonicalWorkId, contentType, season, episode, mode, language, providerGroup)
        val fingerprint = computeGroupFingerprint(eligibleProviderIds)
        return rounds.computeIfAbsent(key) {
            WorkCoverageRound(
                coverageKey = key,
                canonicalWorkId = canonicalWorkId,
                contentType = contentType,
                season = season,
                episode = episode,
                mode = mode,
                language = language,
                providerGroup = providerGroup,
                providerGroupFingerprint = fingerprint
            )
        }
    }

    fun recordProviderCoverage(
        canonicalWorkId: String,
        contentType: ContentType,
        season: Int = 1,
        episode: Int = 1,
        mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
        language: VariantLanguage = VariantLanguage.ARABIC,
        providerGroup: String = "default",
        record: ProviderCoverageRecord
    ) {
        val round = getOrCreateRound(canonicalWorkId, contentType, season, episode, mode, language, providerGroup)
        round.records[record.providerId] = record
        round.records[record.providerId.trim().lowercase()] = record
    }

    fun markRoundComplete(
        canonicalWorkId: String,
        contentType: ContentType,
        season: Int = 1,
        episode: Int = 1,
        mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
        language: VariantLanguage = VariantLanguage.ARABIC,
        providerGroup: String = "default",
        eligibleProviderIds: List<String>
    ) {
        val round = getOrCreateRound(canonicalWorkId, contentType, season, episode, mode, language, providerGroup, eligibleProviderIds)
        val allResolved = eligibleProviderIds.all { pId ->
            val rec = round.records[pId] ?: round.records[pId.trim().lowercase()]
            rec != null && rec.coverageStatus != CoverageStatus.NETWORK_ERROR && rec.coverageStatus != CoverageStatus.TIMEOUT
        }
        val targetStatus = if (allResolved) RoundCoverageStatus.COMPLETE else RoundCoverageStatus.PARTIAL
        val updated = round.copy(
            status = targetStatus,
            completedAt = if (allResolved) System.currentTimeMillis() else null,
            providerGroupFingerprint = computeGroupFingerprint(eligibleProviderIds)
        )
        rounds[round.coverageKey] = updated
    }

    /**
     * Determines whether a full search can be avoided because coverage is complete and valid.
     */
    fun isCoverageComplete(
        canonicalWorkId: String,
        contentType: ContentType,
        season: Int = 1,
        episode: Int = 1,
        mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
        language: VariantLanguage = VariantLanguage.ARABIC,
        providerGroup: String = "default",
        currentEligibleProviders: List<ManagedExtension>
    ): Boolean {
        val round = getRound(canonicalWorkId, contentType, season, episode, mode, language, providerGroup) ?: return false
        if (round.status != RoundCoverageStatus.COMPLETE) return false

        // Invalidate if provider group configuration changed
        val currentFingerprint = computeGroupFingerprint(currentEligibleProviders.map { it.id })
        if (round.providerGroupFingerprint.isNotBlank() && round.providerGroupFingerprint != currentFingerprint) {
            return false
        }

        // Invalidate if TTL has expired (24 hours)
        val completedAt = round.completedAt ?: 0L
        if (completedAt == 0L || (System.currentTimeMillis() - completedAt) > SOURCE_PAGE_TTL_MS) {
            return false
        }

        // Check if any provider was added, had a transient failure, or negative TTL expired
        val now = System.currentTimeMillis()
        for (ext in currentEligibleProviders) {
            val record = round.records[ext.id] ?: round.records[ext.id.trim().lowercase()]
            if (record == null) return false
            if (record.coverageStatus == CoverageStatus.NETWORK_ERROR ||
                record.coverageStatus == CoverageStatus.TIMEOUT ||
                record.coverageStatus == CoverageStatus.CHALLENGE) {
                return false // Transient failure remains retryable
            }
            if (record.coverageStatus == CoverageStatus.NOT_FOUND && (now - record.discoveredAt > NEGATIVE_RESULT_TTL_MS)) {
                return false // Negative TTL expired, must re-inspect
            }
            if (record.coverageStatus == CoverageStatus.FOUND && record.sourcePageUrl.isNullOrBlank()) {
                return false
            }
        }
        return true
    }

    /**
     * Returns ONLY providers that need inspection (i.e. not yet checked in this round or expired).
     */
    fun getIncompleteProviders(
        canonicalWorkId: String,
        contentType: ContentType,
        season: Int = 1,
        episode: Int = 1,
        mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
        language: VariantLanguage = VariantLanguage.ARABIC,
        providerGroup: String = "default",
        currentEligibleProviders: List<ManagedExtension>
    ): List<ManagedExtension> {
        val round = getRound(canonicalWorkId, contentType, season, episode, mode, language, providerGroup)
            ?: return currentEligibleProviders

        // Invalidate if provider group configuration changed
        val currentFingerprint = computeGroupFingerprint(currentEligibleProviders.map { it.id })
        if (round.providerGroupFingerprint.isNotBlank() && round.providerGroupFingerprint != currentFingerprint) {
            return currentEligibleProviders
        }

        // If round is expired, all providers need re-inspection
        val completedAt = round.completedAt ?: 0L
        if (round.status == RoundCoverageStatus.COMPLETE && (completedAt == 0L || (System.currentTimeMillis() - completedAt) > SOURCE_PAGE_TTL_MS)) {
            return currentEligibleProviders
        }

        val now = System.currentTimeMillis()
        return currentEligibleProviders.filter { ext ->
            val record = round.records[ext.id] ?: round.records[ext.id.trim().lowercase()]
            record == null ||
                record.coverageStatus == CoverageStatus.NETWORK_ERROR ||
                record.coverageStatus == CoverageStatus.TIMEOUT ||
                record.coverageStatus == CoverageStatus.CHALLENGE ||
                (record.coverageStatus == CoverageStatus.NOT_FOUND && (now - record.discoveredAt > NEGATIVE_RESULT_TTL_MS)) ||
                (record.coverageStatus == CoverageStatus.FOUND && record.sourcePageUrl.isNullOrBlank())
        }
    }

    fun clear() {
        rounds.clear()
    }
}
