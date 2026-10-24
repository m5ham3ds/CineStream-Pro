package com.example.data.util

import com.example.domain.models.VideoTrailer

/**
 * Deterministic primary trailer selector adhering to Phase 23.2 guidelines.
 *
 * Policy:
 * 1. Only considers videos hosted on YouTube with valid keys.
 * 2. Strictly prioritizes videos with type == "Trailer" (never labels a teaser, featurette, or clip as main trailer).
 * 3. Prefers videos explicitly flagged as official (isOfficial == true).
 * 4. Prefers matching the active application language, then "en", then any.
 * 5. Uses publication date (latest first) to break ties.
 * 6. Returns null if no suitable trailer is found.
 */
object TrailerSelectionHelper {

    fun selectPrimaryTrailer(
        trailers: List<VideoTrailer>,
        preferredLanguage: String = "en"
    ): VideoTrailer? {
        if (trailers.isEmpty()) return null

        val youtubeVideos = trailers.filter {
            it.site.equals("YouTube", ignoreCase = true) && it.key.isNotBlank()
        }
        if (youtubeVideos.isEmpty()) return null

        // Strictly prioritize type == "Trailer"
        val officialTrailers = youtubeVideos.filter {
            it.type.equals("Trailer", ignoreCase = true)
        }

        // If no video with type == "Trailer" exists, do not guess or label non-trailers
        val candidatePool = if (officialTrailers.isNotEmpty()) officialTrailers else emptyList()
        if (candidatePool.isEmpty()) return null

        val normLang = preferredLanguage.lowercase().take(2)

        return candidatePool.sortedWith(
            compareByDescending<VideoTrailer> { it.isOfficial }
                .thenByDescending { it.language?.lowercase()?.startsWith(normLang) == true }
                .thenByDescending { it.language?.lowercase()?.startsWith("en") == true }
                .thenByDescending { it.publishedAt ?: "" }
        ).firstOrNull()
    }
}
