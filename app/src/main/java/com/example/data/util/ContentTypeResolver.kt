package com.example.data.util

import com.example.data.model.ContentType
import com.example.domain.models.Movie
import com.example.domain.models.Series

/**
 * Centralized Canonical ContentType Resolver.
 *
 * Rules:
 * - MOVIE: Any TMDB Movie, including Anime Movies (medium is movie).
 * - TV: Television series that are not Japanese Anime.
 * - ANIME: Strictly Japanese Anime TV Series (Animation + Japanese origin/language).
 *
 * Strictly prohibits title-based heuristics.
 */
object ContentTypeResolver {
    const val GENRE_ANIMATION_ID = 16
    const val GENRE_ANIMATION_NAME = "Animation"
    const val LANGUAGE_JAPANESE = "ja"
    const val COUNTRY_JAPAN = "JP"

    /**
     * Resolves canonical content type from explicit media type and metadata.
     */
    fun resolve(
        isMovie: Boolean,
        genreIds: List<Int>? = null,
        genreNames: List<String>? = null,
        originalLanguage: String? = null,
        originCountries: List<String>? = null
    ): String {
        // Invariant: All movies (including animated Japanese movies) map to "movie"
        if (isMovie) {
            return ContentType.MOVIE
        }

        // TV / Series path: Check Animation genre
        val hasAnimationGenre = (genreIds?.contains(GENRE_ANIMATION_ID) == true) ||
                (genreNames?.any { it.equals(GENRE_ANIMATION_NAME, ignoreCase = true) } == true)

        if (!hasAnimationGenre) {
            return ContentType.TV
        }

        // Must have Japanese identity (language "ja" or origin country "JP")
        val isJapanese = originalLanguage?.equals(LANGUAGE_JAPANESE, ignoreCase = true) == true ||
                originCountries?.any { it.equals(COUNTRY_JAPAN, ignoreCase = true) } == true

        return if (isJapanese) {
            ContentType.ANIME
        } else {
            // Chinese animation (Donghua), Korean animation (Aeni), Western animation (The Simpsons) -> tv
            ContentType.TV
        }
    }

    /**
     * Resolves content type for a Domain Movie.
     */
    fun resolveMovie(movie: Movie): String = ContentType.MOVIE

    /**
     * Resolves content type for a Domain Series.
     */
    fun resolveSeries(series: Series): String {
        return resolve(
            isMovie = false,
            genreNames = series.genres,
            originalLanguage = series.originalLanguage,
            originCountries = series.originCountry
        )
    }

    /**
     * Resolves content type for a TmdbSeries DTO.
     */
    fun resolveTmdbSeries(series: com.example.data.remote.TmdbSeries): String {
        return resolve(
            isMovie = false,
            genreIds = series.genreIds,
            originalLanguage = series.originalLanguage,
            originCountries = series.originCountry
        )
    }

    /**
     * Resolves content type for TmdbSeriesDetails DTO.
     */
    fun resolveTmdbSeriesDetails(details: com.example.data.remote.TmdbSeriesDetails): String {
        return resolve(
            isMovie = false,
            genreNames = details.genres?.map { it.name },
            originalLanguage = details.originalLanguage,
            originCountries = details.originCountry
        )
    }
}
