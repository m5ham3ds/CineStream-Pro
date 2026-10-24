package com.example.extension.managed.model

import com.example.domain.models.ProviderSourceCandidate

data class SearchRequest(
    val query: String,
    val contentType: ContentType? = null,
    val page: Int = 1,
    val language: String = "ar",
    val isArabicDubbedIntent: Boolean = false
)

data class SearchMediaItem(
    val id: String,
    val title: String,
    val posterUrl: String? = null,
    val url: String,
    val contentType: ContentType,
    val year: String? = null,
    val extraMetadata: Map<String, String> = emptyMap(),
    val providerId: String? = null,
    val sourceCandidates: List<ProviderSourceCandidate> = emptyList(),
    val canonicalPosterUrl: String? = null,
    val canonicalTitle: String? = null
) {
    /**
     * Provider poster URL: strictly reflects provider-derived artwork.
     * Prevents TMDB canonical poster URLs from ever being attributed as provider artwork.
     */
    val providerPosterUrl: String?
        get() = if (providerId != null || sourceCandidates.isNotEmpty() || (!url.contains("themoviedb.org") && url.isNotBlank())) {
            posterUrl
        } else {
            null
        }

    val providerTitle: String get() = title
    val sourcePageUrl: String get() = url
}

data class SearchResult(
    val items: List<SearchMediaItem>,
    val page: Int = 1,
    val hasNextPage: Boolean = false,
    val metadata: Map<String, String> = emptyMap()
)
