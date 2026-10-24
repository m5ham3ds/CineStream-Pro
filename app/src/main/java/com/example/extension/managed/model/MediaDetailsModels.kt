package com.example.extension.managed.model

data class EpisodeItem(
    val id: String,
    val title: String,
    val episodeNumber: Int,
    val seasonNumber: Int = 1,
    val url: String,
    val thumbnailUrl: String? = null,
    val detailReference: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

data class DetailsRequest(
    val url: String,
    val id: String = "",
    val contentType: ContentType? = null,
    val headers: Map<String, String> = emptyMap()
)

data class EpisodesRequest(
    val seriesUrl: String,
    val season: Int = 1,
    val seriesId: String = "",
    val headers: Map<String, String> = emptyMap()
)

data class EpisodeList(
    val episodes: List<EpisodeItem>,
    val seasonNumber: Int = 1,
    val metadata: Map<String, String> = emptyMap()
)

data class MediaDetailsResult(
    val id: String,
    val title: String,
    val description: String? = null,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val contentType: ContentType,
    val episodes: List<EpisodeItem> = emptyList(),
    val seasons: List<Int> = emptyList(),
    val url: String,
    val releaseDate: String? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val rating: Double? = null,
    val runtime: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

typealias ContentDetails = MediaDetailsResult
