package com.example.extension.managed.model

enum class ContentType {
    MOVIE,
    SERIES,
    ANIME;

    val isMovie: Boolean
        get() = this == MOVIE

    val isSeries: Boolean
        get() = this == SERIES

    val isAnime: Boolean
        get() = this == ANIME

    val canonicalKey: String
        get() = when (this) {
            MOVIE -> "movie"
            SERIES -> "tv"
            ANIME -> "anime"
        }

    val searchOrderKey: String
        get() = when (this) {
            MOVIE -> "movie"
            SERIES -> "tv"
            ANIME -> "anime"
        }

    companion object {
        fun from(raw: String?, isMovieFallback: Boolean = true): ContentType {
            if (raw.isNullOrBlank()) {
                return if (isMovieFallback) MOVIE else SERIES
            }
            return when (raw.trim().lowercase()) {
                "anime" -> ANIME
                "tv", "series", "shows", "tv_shows" -> SERIES
                "movie", "movies", "film", "films" -> MOVIE
                else -> if (isMovieFallback) MOVIE else SERIES
            }
        }
    }
}
