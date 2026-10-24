package com.example.extension.managed.playback

import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.QualitySource
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Normalized Playback Resolution Result.
 * Represents an authoritative resolution artifact produced by ManagedMediaOrchestrator.
 * Crucial Invariant: STREAM URL != MEDIA IDENTITY.
 */
data class PlaybackResolution(
    val mediaIdentity: String,
    val contentType: ContentType,
    val mediaId: String,
    val seriesId: String? = null,
    val season: Int = 1,
    val episode: Int = 1,
    val providerId: String,
    val sourceRevision: Long = 1L,
    val selectedQuality: String = "Auto",
    val availableQualities: List<QualitySource> = emptyList(),
    val streamUrl: String,
    val streamType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val resolvedAt: Long = System.currentTimeMillis(),
    val lastValidatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long? = null,
    val resolutionRevision: Long = 1L,
    val isStale: Boolean = false,
    val playbackPageUrl: String? = null,
    val scraperKey: String? = null,
    val serverName: String? = null,
    val availableServers: List<String> = emptyList(),
    val serverLinks: Map<String, String> = emptyMap(),
    val mode: String = "subtitled",
    val language: String = "ar"
)

sealed interface ResolutionValidationResult {
    object Valid : ResolutionValidationResult
    data class Invalid(val reason: String) : ResolutionValidationResult
}

/**
 * Canonical Playback Resolution Cache (Phase 07.0 / Wave 4).
 *
 * Responsibilities:
 * 1. Deterministic cache key derivation (Series + Season + Episode + Source + Quality).
 * 2. Strict pre-playback validation (media identity, content type, season/episode, URL structure, TTL).
 * 3. Atomic revision safety (prevents stale background refresh from overwriting newer cache revisions).
 * 4. Safe invalidation triggers (media/episode change, logout, provider disablement).
 */
class PlaybackResolutionCache {

    private val resolutions = ConcurrentHashMap<String, PlaybackResolution>()
    private val revisionGenerator = AtomicLong(1L)

    fun nextRevision(): Long = revisionGenerator.incrementAndGet()

    /**
     * Deterministic Cache Key Contract (Section 9 & Phase 09B-B Section 16):
     * Key must distinguish: MEDIA/SERIES + SEASON + EPISODE + MODE + LANGUAGE + SOURCE + QUALITY
     */
    fun buildKey(
        mediaId: String,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
        sourceOrProvider: String? = null,
        quality: String? = null,
        mode: String? = null,
        language: String? = null
    ): String {
        val cleanMediaId = mediaId.trim()
        val typePart = if (isMovie) "movie" else "series"
        val epPart = if (isMovie) "1:1" else "$season:$episode"
        val modePart = mode?.trim()?.lowercase() ?: "subtitled"
        val langPart = language?.trim()?.lowercase() ?: "ar"
        val sourcePart = sourceOrProvider?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "any"
        val qualityPart = quality?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "auto"
        return "$cleanMediaId:$typePart:$epPart:$modePart:$langPart:$sourcePart:$qualityPart"
    }

    fun buildIdentityPrefix(
        mediaId: String,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
        mode: String? = null,
        language: String? = null
    ): String {
        val cleanMediaId = mediaId.trim()
        val typePart = if (isMovie) "movie" else "series"
        val epPart = if (isMovie) "1:1" else "$season:$episode"
        val modePart = mode?.trim()?.lowercase() ?: "subtitled"
        val langPart = language?.trim()?.lowercase() ?: "ar"
        return "$cleanMediaId:$typePart:$epPart:$modePart:$langPart:"
    }

    fun put(resolution: PlaybackResolution): PlaybackResolution {
        val key = buildKey(
            mediaId = resolution.mediaId,
            isMovie = resolution.contentType.isMovie,
            season = resolution.season,
            episode = resolution.episode,
            sourceOrProvider = resolution.providerId,
            quality = resolution.selectedQuality,
            mode = resolution.mode,
            language = resolution.language
        )
        val genericKey = buildKey(
            mediaId = resolution.mediaId,
            isMovie = resolution.contentType.isMovie,
            season = resolution.season,
            episode = resolution.episode,
            sourceOrProvider = resolution.providerId,
            quality = "auto",
            mode = resolution.mode,
            language = resolution.language
        )
        val anyProviderKey = buildKey(
            mediaId = resolution.mediaId,
            isMovie = resolution.contentType.isMovie,
            season = resolution.season,
            episode = resolution.episode,
            sourceOrProvider = "any",
            quality = resolution.selectedQuality,
            mode = resolution.mode,
            language = resolution.language
        )
        val baseKey = buildKey(
            mediaId = resolution.mediaId,
            isMovie = resolution.contentType.isMovie,
            season = resolution.season,
            episode = resolution.episode,
            sourceOrProvider = "any",
            quality = "auto",
            mode = resolution.mode,
            language = resolution.language
        )

        resolutions[key] = resolution
        resolutions[genericKey] = resolution
        resolutions[anyProviderKey] = resolution
        resolutions[baseKey] = resolution
        return resolution
    }

    fun get(key: String): PlaybackResolution? = resolutions[key]

    /**
     * Finds and validates a cached resolution according to Section 10 contract.
     */
    fun findValidResolution(
        mediaId: String,
        contentType: ContentType,
        seriesId: String? = null,
        season: Int = 1,
        episode: Int = 1,
        sourceOrProvider: String? = null,
        requestedQuality: String? = null,
        mode: String? = null,
        language: String? = null
    ): PlaybackResolution? {
        val specificKey = buildKey(
            mediaId = mediaId,
            isMovie = contentType.isMovie,
            season = season,
            episode = episode,
            sourceOrProvider = sourceOrProvider,
            quality = requestedQuality,
            mode = mode,
            language = language
        )
        val providerAnyQualityKey = buildKey(
            mediaId = mediaId,
            isMovie = contentType.isMovie,
            season = season,
            episode = episode,
            sourceOrProvider = sourceOrProvider,
            quality = "auto",
            mode = mode,
            language = language
        )
        val anyProviderSpecificQualityKey = buildKey(
            mediaId = mediaId,
            isMovie = contentType.isMovie,
            season = season,
            episode = episode,
            sourceOrProvider = "any",
            quality = requestedQuality,
            mode = mode,
            language = language
        )
        val baseKey = buildKey(
            mediaId = mediaId,
            isMovie = contentType.isMovie,
            season = season,
            episode = episode,
            sourceOrProvider = "any",
            quality = "auto",
            mode = mode,
            language = language
        )

        val candidate = resolutions[specificKey]
            ?: resolutions[providerAnyQualityKey]
            ?: resolutions[anyProviderSpecificQualityKey]
            ?: resolutions[baseKey]
            ?: return null

        val validation = validateResolution(
            cached = candidate,
            mediaId = mediaId,
            contentType = contentType,
            seriesId = seriesId,
            season = season,
            episode = episode,
            sourceOrProvider = sourceOrProvider,
            requestedQuality = requestedQuality,
            mode = mode,
            language = language
        )

        return if (validation is ResolutionValidationResult.Valid) {
            candidate
        } else {
            null
        }
    }

    /**
     * Strict Cache Validation Contract (Section 10).
     */
    fun validateResolution(
        cached: PlaybackResolution,
        mediaId: String,
        contentType: ContentType,
        seriesId: String? = null,
        season: Int = 1,
        episode: Int = 1,
        sourceOrProvider: String? = null,
        requestedQuality: String? = null,
        mode: String? = null,
        language: String? = null
    ): ResolutionValidationResult {
        if (mode != null && !cached.mode.equals(mode, ignoreCase = true)) {
            return ResolutionValidationResult.Invalid("Mode mismatch: requested=$mode, cached=${cached.mode}")
        }
        if (language != null && !cached.language.equals(language, ignoreCase = true)) {
            return ResolutionValidationResult.Invalid("Language mismatch: requested=$language, cached=${cached.language}")
        }
        // 1. Current media identity matches cache
        if (cached.mediaId != mediaId) {
            return ResolutionValidationResult.Invalid("Media identity mismatch: requested=$mediaId, cached=${cached.mediaId}")
        }

        // 2. Content type matches (Movies must match Movies, episodic Series and Anime are mutually compatible)
        val typesCompatible = cached.contentType == contentType || (!cached.contentType.isMovie && !contentType.isMovie)
        if (!typesCompatible) {
            return ResolutionValidationResult.Invalid("ContentType mismatch: requested=$contentType, cached=${cached.contentType}")
        }

        // 3. Series ID matches if applicable
        if (seriesId != null && cached.seriesId != null && cached.seriesId != seriesId) {
            return ResolutionValidationResult.Invalid("SeriesId mismatch: requested=$seriesId, cached=${cached.seriesId}")
        }

        // 4. Season matches if applicable
        if (!contentType.isMovie && cached.season != season) {
            return ResolutionValidationResult.Invalid("Season mismatch: requested=$season, cached=${cached.season}")
        }

        // 5. Episode matches if applicable
        if (!contentType.isMovie && cached.episode != episode) {
            return ResolutionValidationResult.Invalid("Episode mismatch: requested=$episode, cached=${cached.episode}")
        }

        // 6. Source/provider matches current request (if explicit source specified)
        if (!sourceOrProvider.isNullOrBlank() && sourceOrProvider != "any") {
            val matchesProvider = cached.providerId.equals(sourceOrProvider, ignoreCase = true) ||
                (cached.scraperKey != null && cached.scraperKey.equals(sourceOrProvider, ignoreCase = true))
            if (!matchesProvider) {
                return ResolutionValidationResult.Invalid("Provider mismatch: requested=$sourceOrProvider, cached=${cached.providerId}")
            }
        }

        // 7. Requested quality is represented (if not Auto or blank)
        if (!requestedQuality.isNullOrBlank() && !requestedQuality.equals("Auto", ignoreCase = true)) {
            val hasQuality = cached.selectedQuality.equals(requestedQuality, ignoreCase = true) ||
                cached.availableQualities.any { it.label.equals(requestedQuality, ignoreCase = true) }
            if (!hasQuality) {
                return ResolutionValidationResult.Invalid("Requested quality $requestedQuality not found in cached resolution")
            }
        }

        // 8. Stream URL is non-empty
        if (cached.streamUrl.isBlank()) {
            return ResolutionValidationResult.Invalid("Stream URL is empty")
        }

        // 9. Stream URL has valid structural format
        if (!isValidStreamUrl(cached.streamUrl)) {
            return ResolutionValidationResult.Invalid("Stream URL is structurally invalid: ${cached.streamUrl}")
        }

        // 10. Cache is not explicitly invalidated
        if (cached.isStale) {
            return ResolutionValidationResult.Invalid("Cached resolution is marked stale")
        }

        // 11. URL is not known to be expired
        val now = System.currentTimeMillis()
        if (cached.expiresAt != null && now >= cached.expiresAt) {
            return ResolutionValidationResult.Invalid("Stream URL expired at ${cached.expiresAt} (current=$now)")
        }

        // 12. Check signed URL token expiry parameter if present in query
        val tokenExpiry = parseUrlTokenExpiry(cached.streamUrl)
        if (tokenExpiry != null && now >= tokenExpiry) {
            return ResolutionValidationResult.Invalid("Signed stream URL token expired at $tokenExpiry (current=$now)")
        }

        return ResolutionValidationResult.Valid
    }

    /**
     * Atomically merges new discovered qualities into existing resolution.
     */
    fun mergeQualities(
        key: String,
        newQualities: List<QualitySource>,
        newRevision: Long
    ): Boolean {
        val existing = resolutions[key] ?: return false
        if (newRevision <= existing.resolutionRevision) {
            return false // Reject stale revision update
        }
        val mergedList = (existing.availableQualities + newQualities)
            .distinctBy { it.label.lowercase().trim() }
        val updated = existing.copy(
            availableQualities = mergedList,
            resolutionRevision = newRevision,
            lastValidatedAt = System.currentTimeMillis()
        )
        resolutions[key] = updated
        return true
    }

    /**
     * Atomically updates stream URL for an existing resolution (URL rotation).
     * Enforces Revision Safety (Section 34): older revision cannot overwrite newer.
     */
    fun updateStreamUrl(
        key: String,
        newUrl: String,
        newRevision: Long,
        newExpiresAt: Long? = null
    ): Boolean {
        val existing = resolutions[key] ?: return false
        if (newRevision <= existing.resolutionRevision) {
            return false // Reject stale revision overwrite
        }
        val updated = existing.copy(
            streamUrl = newUrl,
            expiresAt = newExpiresAt ?: existing.expiresAt,
            resolutionRevision = newRevision,
            lastValidatedAt = System.currentTimeMillis()
        )
        resolutions[key] = updated
        return true
    }

    fun markValidated(key: String) {
        val existing = resolutions[key] ?: return
        resolutions[key] = existing.copy(
            lastValidatedAt = System.currentTimeMillis(),
            isStale = false
        )
    }

    fun markStale(key: String) {
        val existing = resolutions[key] ?: return
        resolutions[key] = existing.copy(isStale = true)
    }

    fun invalidateMedia(mediaId: String) {
        val iterator = resolutions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.mediaId == mediaId || entry.key.startsWith("$mediaId:")) {
                iterator.remove()
            }
        }
    }

    fun invalidateEpisode(mediaId: String, season: Int, episode: Int) {
        val prefix = "$mediaId:series:$season:$episode:"
        val iterator = resolutions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.startsWith(prefix) ||
                (entry.value.mediaId == mediaId && entry.value.season == season && entry.value.episode == episode)) {
                iterator.remove()
            }
        }
    }

    fun invalidateProvider(providerId: String) {
        val iterator = resolutions.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.providerId.equals(providerId, ignoreCase = true) ||
                entry.value.scraperKey.equals(providerId, ignoreCase = true)) {
                iterator.remove()
            }
        }
    }

    fun invalidateAll() {
        resolutions.clear()
    }

    val size: Int
        get() = resolutions.size

    companion object {
        fun isValidStreamUrl(url: String?): Boolean {
            if (url.isNullOrBlank()) return false
            val trimmed = url.trim()
            if (trimmed.startsWith("auto_extract://")) return false
            if (trimmed.contains(" ") || trimmed.contains("\n") || trimmed.contains("\r")) return false
            return trimmed.startsWith("http://") ||
                trimmed.startsWith("https://") ||
                trimmed.startsWith("file://") ||
                trimmed.startsWith("content://") ||
                trimmed.startsWith("local_offline_file://")
        }

        fun parseUrlTokenExpiry(url: String): Long? {
            try {
                val uri = URI(url)
                val query = uri.query ?: return null
                val params = query.split("&")
                for (p in params) {
                    val eq = p.indexOf('=')
                    if (eq > 0) {
                        val key = p.substring(0, eq).lowercase()
                        val value = p.substring(eq + 1)
                        if (key == "exp" || key == "expires" || key == "token_exp" || key == "token_expiry") {
                            val num = value.toLongOrNull() ?: continue
                            // If timestamp is in seconds (10 digits), convert to millis
                            return if (num < 100_000_000_000L) num * 1000L else num
                        }
                    }
                }
            } catch (_: Throwable) {}
            return null
        }
    }
}
