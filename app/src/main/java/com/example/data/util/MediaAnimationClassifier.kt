package com.example.data.util

import com.example.domain.models.Movie
import com.example.domain.models.Series

/**
 * Authoritative Media Animation & Anime Classifier for Phase 10B.
 *
 * Distinguishes Japanese Anime from Western / Worldwide Animation:
 * - ANIME: Japanese animation with Japanese origin (language "ja", country "JP", or explicit "Anime" genre).
 * - ANIMATION (e.g. Disney's Moana, Frozen, Coco, Pixar, DreamWorks): Has Animation genre but
 *   Western/non-Japanese origin. NEVER treated as Anime. Fully eligible for Arabic Dubbing.
 */
object MediaAnimationClassifier {

    /**
     * Determines whether a Movie is genuine Japanese Anime.
     * Western animated movies (like Moana) return false so they can be dubbed.
     */
    fun isAnime(movie: Movie): Boolean {
        // 1. Explicit Anime genre
        if (movie.genres.any { it.equals("Anime", ignoreCase = true) || it.equals("أنمي", ignoreCase = true) }) {
            return true
        }

        // 2. Japanese origin or Japanese original language
        val isJapaneseIdentity = movie.language.equals("ja", ignoreCase = true) ||
                movie.country.equals("JP", ignoreCase = true)

        val hasAnimationGenre = movie.genres.any {
            it.contains("Animation", ignoreCase = true) ||
            it.contains("رسوم متحركة", ignoreCase = true)
        } || movie.genreIds.contains(ContentTypeResolver.GENRE_ANIMATION_ID)

        if (hasAnimationGenre && isJapaneseIdentity) {
            return true
        }

        // 3. Known anime titles check via AnimePlaybackStore
        val ctx = try { com.example.MyApplication.appContext } catch (_: Throwable) { null }
        if (ctx != null && com.example.data.repository.AnimePlaybackStore.isAnime(ctx, movie.id, movie.title)) {
            return true
        }

        return false
    }

    /**
     * Determines whether a Series is genuine Japanese Anime.
     */
    fun isAnime(series: Series): Boolean {
        // 1. Explicit Anime genre
        if (series.genres.any { it.equals("Anime", ignoreCase = true) || it.equals("أنمي", ignoreCase = true) }) {
            return true
        }

        // 2. Japanese origin or language
        val isJapaneseIdentity = series.originalLanguage.equals("ja", ignoreCase = true) ||
                series.originCountry.any { it.equals("JP", ignoreCase = true) }

        val hasAnimationGenre = series.genres.any {
            it.contains("Animation", ignoreCase = true) ||
            it.contains("رسوم متحركة", ignoreCase = true)
        } || series.genreIds.contains(ContentTypeResolver.GENRE_ANIMATION_ID)

        if (hasAnimationGenre && isJapaneseIdentity) {
            return true
        }

        val ctx = try { com.example.MyApplication.appContext } catch (_: Throwable) { null }
        if (ctx != null && com.example.data.repository.AnimePlaybackStore.isAnime(ctx, series.id, series.title)) {
            return true
        }

        return false
    }
}
