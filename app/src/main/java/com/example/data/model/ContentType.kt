package com.example.data.model

/**
 * Canonical Content Types for CineStream Pro Library and Catalog.
 *
 * Allowed values:
 * - "movie": Any TMDB movie, including Anime Movies (medium is movie).
 * - "tv": Regular television series.
 * - "anime": Japanese Anime TV Series.
 */
object ContentType {
    const val MOVIE = "movie"
    const val TV = "tv"
    const val ANIME = "anime"

    val VALID_TYPES = setOf(MOVIE, TV, ANIME)

    fun isValid(type: String?): Boolean {
        if (type == null) return false
        return type in VALID_TYPES
    }

    fun isMovie(type: String?): Boolean {
        return type == MOVIE
    }

    fun normalize(type: String?): String {
        return when (type?.trim()?.lowercase()) {
            ANIME -> ANIME
            TV, "series" -> TV
            MOVIE, "film" -> MOVIE
            else -> throw IllegalArgumentException("Unknown or invalid contentType: '$type'. Allowed: $VALID_TYPES")
        }
    }
}

typealias ContentTypeResolver = com.example.data.util.ContentTypeResolver
