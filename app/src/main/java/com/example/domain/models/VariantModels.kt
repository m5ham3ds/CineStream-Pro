package com.example.domain.models

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.QualitySource

enum class MediaVariantMode(val value: String) {
    SUBTITLED("subtitled"),
    DUBBED("dubbed");

    companion object {
        fun fromString(value: String?): MediaVariantMode {
            return when (value?.trim()?.lowercase()) {
                "dubbed", "dub" -> DUBBED
                else -> SUBTITLED
            }
        }
    }
}

enum class VariantLanguage(val code: String) {
    ARABIC("ar"),
    ENGLISH("en"),
    ORIGINAL("orig");

    companion object {
        fun fromString(code: String?): VariantLanguage {
            return when (code?.trim()?.lowercase()) {
                "ar", "arabic" -> ARABIC
                "en", "english" -> ENGLISH
                else -> ORIGINAL
            }
        }
    }
}

/**
 * Approved Audio Capabilities from Phase 09B Firestore Contract.
 */
enum class AudioCapability {
    ARABIC_DUB,
    ENGLISH_DUB,
    ORIGINAL_AUDIO,
    MULTI_AUDIO;

    companion object {
        fun fromString(value: String?): AudioCapability? {
            if (value.isNullOrBlank()) return null
            val normalized = value.trim().uppercase().replace("-", "_").replace(" ", "_")
            return when (normalized) {
                "ARABIC_DUB", "ARABIC_DUBBED", "ARABICDUB", "ARABICDUBBED", "ARABIC" -> ARABIC_DUB
                "ENGLISH_DUB", "ENGLISH_DUBBED", "ENGLISHDUB", "ENGLISHDUBBED", "ENGLISH" -> ENGLISH_DUB
                "ORIGINAL_AUDIO", "ORIGINALAUDIO", "ORIGINAL" -> ORIGINAL_AUDIO
                "MULTI_AUDIO", "MULTIAUDIO", "MULTI" -> MULTI_AUDIO
                else -> try {
                    valueOf(normalized)
                } catch (_: Exception) {
                    null
                }
            }
        }
    }
}

/**
 * Operational status for search order groups.
 */
enum class GroupStatus {
    ACTIVE,
    MAINTENANCE,
    DISABLED;

    companion object {
        fun fromString(value: String?): GroupStatus {
            if (value.isNullOrBlank()) return MAINTENANCE
            val normalized = value.trim().uppercase().replace("-", "_").replace(" ", "_")
            return when (normalized) {
                "ACTIVE", "ENABLED", "ENABLE", "ON" -> ACTIVE
                "DISABLED", "DISABLE", "OFF" -> DISABLED
                "MAINTENANCE", "MAINT", "PAUSED" -> MAINTENANCE
                else -> try {
                    valueOf(normalized)
                } catch (_: Exception) {
                    MAINTENANCE
                }
            }
        }
    }
}

/**
 * Detailed status of provider search coverage.
 */
enum class CoverageStatus {
    FOUND,
    NOT_FOUND,
    NETWORK_ERROR,
    TIMEOUT,
    CHALLENGE,
    UNSUPPORTED,
    UNKNOWN_CAPABILITY
}

/**
 * Canonical Variant Identity model.
 * Enforces strict anime subtitle-only rule: anime cannot be dubbed.
 */
data class MediaVariantIdentity(
    val canonicalWorkId: String,
    val contentType: ContentType = ContentType.MOVIE,
    val mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
    val audioLanguage: VariantLanguage = VariantLanguage.ARABIC,
    val subtitleLanguage: VariantLanguage? = VariantLanguage.ARABIC
) {
    val isAnime: Boolean get() = contentType == ContentType.ANIME

    init {
        if (isAnime && mode == MediaVariantMode.DUBBED) {
            throw IllegalArgumentException("Anime is strictly subtitle-only. Dubbing is not supported.")
        }
    }
}

/**
 * Individual provider candidate found for a media variant.
 */
data class ProviderSourceCandidate(
    val providerId: String,
    val scraperKey: String,
    val sourcePageUrl: String,
    val title: String,
    val mode: MediaVariantMode = MediaVariantMode.SUBTITLED,
    val audioLanguage: VariantLanguage = VariantLanguage.ARABIC,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val status: CoverageStatus = CoverageStatus.FOUND,
    val streamUrl: String? = null,
    val streamExpiresAt: Long? = null,
    val qualities: List<QualitySource> = emptyList(),
    val errorMessage: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val posterUrl: String? = null,
    val contentType: ContentType? = null,
    val extraMetadata: Map<String, String> = emptyMap()
) {
    val providerTitle: String get() = title
    val providerPosterUrl: String? get() = posterUrl
}

/**
 * Coordinate mapping a specific episode to a variant and provider.
 */
data class EpisodeVariantCoordinate(
    val variantIdentity: MediaVariantIdentity,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val providerId: String,
    val sourcePageUrl: String
)

/**
 * Provider coverage record for persistent coverage caching.
 */
data class ProviderCoverageRecord(
    val providerId: String,
    val scraperKey: String,
    val sourcePageUrl: String? = null,
    val coverageStatus: CoverageStatus = CoverageStatus.FOUND,
    val discoveredAt: Long = System.currentTimeMillis(),
    val streamUrl: String? = null,
    val streamExpiresAt: Long? = null,
    val qualities: List<QualitySource> = emptyList(),
    val errorMessage: String? = null,
    val providerFingerprint: String = "",
    val providerTitle: String? = null,
    val providerPosterUrl: String? = null
) {
    fun isStreamValid(): Boolean {
        if (streamUrl.isNullOrBlank()) return false
        val exp = streamExpiresAt ?: return false
        return System.currentTimeMillis() < exp
    }

    fun isSourcePageValid(): Boolean {
        if (sourcePageUrl.isNullOrBlank()) return false
        val maxAgeMs = 24 * 60 * 60 * 1000L // 24 hours
        return (System.currentTimeMillis() - discoveredAt) < maxAgeMs
    }
}
