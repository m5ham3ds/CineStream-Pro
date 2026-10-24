package com.example.domain.search

import com.example.domain.models.CoverageStatus
import com.example.domain.models.MediaVariantMode
import com.example.domain.models.Movie
import com.example.domain.models.ProviderSourceCandidate
import com.example.domain.models.Series
import com.example.domain.models.VariantLanguage

/**
 * Resolved presentation display information for media search results and details screens.
 * Enforces Phase 17 & Phase 19 requirements:
 * - Distinguishes canonical TMDB identity/title/poster from provider-derived title/poster.
 * - Retains variant mode, provider ID, source page URL, confirmed dubbed status, and audio language.
 * - Guarantees non-destructive canonical fallback and candidate-scoped title/poster binding.
 */
data class ResolvedMediaDisplayInfo(
    val canonicalTmdbId: String,
    val canonicalTitle: String,
    val providerTitle: String?,
    val displayTitle: String,
    val variantMode: MediaVariantMode,
    val providerId: String?,
    val sourcePageUrl: String?,
    val isConfirmedDubbed: Boolean,
    val contentType: String,
    val canonicalPosterUrl: String? = null,
    val providerPosterUrl: String? = null,
    val displayPosterUrl: String? = null,
    val audioLanguage: VariantLanguage? = null
)

object MediaDisplayTitleResolver {

    /**
     * Resolves display title, poster, and presentation variant metadata for a Movie.
     */
    fun resolveForMovie(
        movie: Movie,
        isDubbedIntent: Boolean = false,
        dubbedOrder: List<String> = emptyList(),
        preferredProviderId: String? = null
    ): ResolvedMediaDisplayInfo {
        val isDubbedExperience = isDubbedIntent || movie.mode.equals("dubbed", ignoreCase = true)
        return resolveInternal(
            canonicalId = movie.id,
            canonicalTitle = movie.title,
            canonicalPosterUrl = movie.posterUrl,
            contentType = "movie",
            candidates = movie.sourceCandidates,
            isDubbedExperience = isDubbedExperience,
            dubbedOrder = dubbedOrder,
            preferredProviderId = preferredProviderId ?: movie.providerId
        )
    }

    /**
     * Resolves display title, poster, and presentation variant metadata for a Series.
     */
    fun resolveForSeries(
        series: Series,
        isDubbedIntent: Boolean = false,
        dubbedOrder: List<String> = emptyList(),
        preferredProviderId: String? = null,
        contentType: String = "tv"
    ): ResolvedMediaDisplayInfo {
        val isDubbedExperience = isDubbedIntent || series.mode.equals("dubbed", ignoreCase = true)
        return resolveInternal(
            canonicalId = series.id,
            canonicalTitle = series.title,
            canonicalPosterUrl = series.posterUrl,
            contentType = contentType,
            candidates = series.sourceCandidates,
            isDubbedExperience = isDubbedExperience,
            dubbedOrder = dubbedOrder,
            preferredProviderId = preferredProviderId ?: series.providerId
        )
    }

    /**
     * Core resolution logic adhering to Phase 17 & Phase 19 contracts.
     */
    fun resolveInternal(
        canonicalId: String,
        canonicalTitle: String,
        contentType: String,
        candidates: List<ProviderSourceCandidate>,
        isDubbedExperience: Boolean,
        dubbedOrder: List<String>,
        preferredProviderId: String?,
        canonicalPosterUrl: String? = null
    ): ResolvedMediaDisplayInfo {
        if (!isDubbedExperience) {
            val subCandidate = candidates.firstOrNull { it.providerId == preferredProviderId }
                ?: candidates.firstOrNull { it.status == CoverageStatus.FOUND && it.sourcePageUrl.isNotBlank() }
                ?: candidates.firstOrNull()
            val providerTitle = subCandidate?.title?.trim()?.takeIf { it.isNotBlank() }
            val providerPoster = subCandidate?.posterUrl?.trim()?.takeIf { it.isNotBlank() }
            return ResolvedMediaDisplayInfo(
                canonicalTmdbId = canonicalId,
                canonicalTitle = canonicalTitle,
                providerTitle = providerTitle,
                displayTitle = canonicalTitle,
                variantMode = subCandidate?.mode ?: MediaVariantMode.SUBTITLED,
                providerId = preferredProviderId ?: subCandidate?.providerId,
                sourcePageUrl = subCandidate?.sourcePageUrl,
                isConfirmedDubbed = false,
                contentType = contentType,
                canonicalPosterUrl = canonicalPosterUrl,
                providerPosterUrl = providerPoster,
                displayPosterUrl = canonicalPosterUrl,
                audioLanguage = subCandidate?.audioLanguage ?: VariantLanguage.ORIGINAL
            )
        }

        // Validate eligible Arabic-dubbed candidates:
        // - Variant mode DUBBED
        // - Coverage status FOUND
        // - Non-blank source page URL
        val eligibleCandidates = candidates.filter { candidate ->
            candidate.mode == MediaVariantMode.DUBBED &&
            candidate.status == CoverageStatus.FOUND &&
            candidate.sourcePageUrl.isNotBlank()
        }

        val bestCandidate = selectBestDubbedCandidate(
            candidates = eligibleCandidates,
            dubbedOrder = dubbedOrder,
            preferredProviderId = preferredProviderId
        )

        return if (bestCandidate != null) {
            val candidateTitle = bestCandidate.title.trim().takeIf { it.isNotBlank() }
            val providerPoster = bestCandidate.posterUrl?.trim()?.takeIf { it.isNotBlank() }
            ResolvedMediaDisplayInfo(
                canonicalTmdbId = canonicalId,
                canonicalTitle = canonicalTitle,
                providerTitle = candidateTitle,
                displayTitle = candidateTitle ?: canonicalTitle,
                variantMode = MediaVariantMode.DUBBED,
                providerId = bestCandidate.providerId,
                sourcePageUrl = bestCandidate.sourcePageUrl,
                isConfirmedDubbed = true,
                contentType = contentType,
                canonicalPosterUrl = canonicalPosterUrl,
                providerPosterUrl = providerPoster,
                displayPosterUrl = providerPoster ?: canonicalPosterUrl,
                audioLanguage = bestCandidate.audioLanguage
            )
        } else {
            // Fall back safely to canonical TMDB title and poster without fabricating titles or posters
            ResolvedMediaDisplayInfo(
                canonicalTmdbId = canonicalId,
                canonicalTitle = canonicalTitle,
                providerTitle = null,
                displayTitle = canonicalTitle,
                variantMode = MediaVariantMode.DUBBED,
                providerId = preferredProviderId,
                sourcePageUrl = null,
                isConfirmedDubbed = false,
                contentType = contentType,
                canonicalPosterUrl = canonicalPosterUrl,
                providerPosterUrl = null,
                displayPosterUrl = canonicalPosterUrl,
                audioLanguage = VariantLanguage.ARABIC
            )
        }
    }

    /**
     * Resolves presentation metadata directly from an explicitly chosen ProviderSourceCandidate.
     */
    fun resolveForCandidate(
        candidate: ProviderSourceCandidate,
        canonicalId: String,
        canonicalTitle: String,
        canonicalPosterUrl: String? = null,
        contentType: String = "movie"
    ): ResolvedMediaDisplayInfo {
        val isDubbed = candidate.mode == MediaVariantMode.DUBBED
        val candidateTitle = candidate.title.trim().takeIf { it.isNotBlank() }
        val providerPoster = candidate.posterUrl?.trim()?.takeIf { it.isNotBlank() }
        val displayTitle = if (isDubbed && !candidateTitle.isNullOrBlank()) candidateTitle else canonicalTitle
        val displayPoster = if (isDubbed && !providerPoster.isNullOrBlank()) providerPoster else canonicalPosterUrl

        return ResolvedMediaDisplayInfo(
            canonicalTmdbId = canonicalId,
            canonicalTitle = canonicalTitle,
            providerTitle = candidateTitle,
            displayTitle = displayTitle,
            variantMode = candidate.mode,
            providerId = candidate.providerId,
            sourcePageUrl = candidate.sourcePageUrl,
            isConfirmedDubbed = isDubbed && candidate.status == CoverageStatus.FOUND && candidate.sourcePageUrl.isNotBlank(),
            contentType = contentType,
            canonicalPosterUrl = canonicalPosterUrl,
            providerPosterUrl = providerPoster,
            displayPosterUrl = displayPoster,
            audioLanguage = candidate.audioLanguage
        )
    }

    /**
     * Selects candidate based on preferredProviderId if matched, then authoritative dubbedOrder,
     * maintaining stable deterministic tie-breaking.
     */
    fun selectBestDubbedCandidate(
        candidates: List<ProviderSourceCandidate>,
        dubbedOrder: List<String>,
        preferredProviderId: String?
    ): ProviderSourceCandidate? {
        if (candidates.isEmpty()) return null

        // 1. If preferred provider exists and has an eligible candidate, use it
        if (!preferredProviderId.isNullOrBlank()) {
            val preferred = candidates.firstOrNull { it.providerId == preferredProviderId }
            if (preferred != null) return preferred
        }

        // 2. Order by authoritative dubbed order list
        if (dubbedOrder.isNotEmpty()) {
            val candidateWithIndex = candidates.map { candidate ->
                val index = dubbedOrder.indexOf(candidate.providerId)
                candidate to (if (index >= 0) index else Int.MAX_VALUE)
            }
            val minOrder = candidateWithIndex.minOfOrNull { it.second } ?: Int.MAX_VALUE
            if (minOrder != Int.MAX_VALUE) {
                return candidateWithIndex.first { it.second == minOrder }.first
            }
        }

        // 3. Fallback to first verified eligible candidate
        return candidates.firstOrNull()
    }
}
