package com.example.data.util

import com.example.domain.models.Genre
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Authoritative TMDB Genre Taxonomy & Localization Helper for Phase 10B.
 *
 * Maps TMDB numerical genre IDs to domain Genre models and localized names:
 * - When application language is English: Returns English genre names.
 * - When application language is Arabic: Returns Arabic genre names.
 * - Dynamic remote TMDB response cache partitioned per language code.
 * - Ensures media identities (tmdbId, canonical data) remain unmutated across language switches.
 */
object TmdbGenreHelper {

    var defaultLanguage: String = "ar"

    fun getCurrentLanguage(context: android.content.Context? = null): String {
        // 1. Check AppCompatDelegate app locales
        try {
            val appLocales = androidx.appcompat.app.AppCompatDelegate.getApplicationLocales()
            if (!appLocales.isEmpty) {
                val tag = appLocales.toLanguageTags().lowercase()
                if (tag.startsWith("en")) return "en"
                if (tag.startsWith("ar")) return "ar"
            }
        } catch (_: Throwable) {}

        // 2. Check context resources configuration locale
        try {
            val ctx = context ?: try { com.example.MyApplication.appContext } catch (_: Throwable) { null }
            if (ctx != null) {
                val configLocale = ctx.resources.configuration.locales.get(0)
                if (configLocale != null) {
                    val lang = configLocale.language.lowercase()
                    if (lang.startsWith("en")) return "en"
                    if (lang.startsWith("ar")) return "ar"
                }
            }
        } catch (_: Throwable) {}

        // 3. Fallback to defaultLanguage ("ar" for Arabic CineStream app)
        return defaultLanguage
    }

    // Official TMDB Movie Genres (ID -> Arabic Name)
    val MOVIE_GENRES_MAP = mapOf(
        28 to "أكشن",
        12 to "مغامرة",
        16 to "رسوم متحركة",
        35 to "كوميديا",
        80 to "جريمة",
        99 to "وثائقي",
        18 to "دراما",
        10751 to "عائلي",
        14 to "فانتازيا",
        36 to "تاريخي",
        27 to "رعب",
        10402 to "موسيقى",
        9648 to "غموض",
        10749 to "رومانسي",
        878 to "خيال علمي",
        10770 to "فيلم تلفزيوني",
        53 to "إثارة",
        10752 to "حرب",
        37 to "غربي"
    )

    // Official TMDB Movie Genres (ID -> English Name)
    val MOVIE_GENRES_EN_MAP = mapOf(
        28 to "Action",
        12 to "Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        14 to "Fantasy",
        36 to "History",
        27 to "Horror",
        10402 to "Music",
        9648 to "Mystery",
        10749 to "Romance",
        878 to "Science Fiction",
        10770 to "TV Movie",
        53 to "Thriller",
        10752 to "War",
        37 to "Western"
    )

    // Official TMDB TV Genres (ID -> Arabic Name)
    val TV_GENRES_MAP = mapOf(
        10759 to "حركة ومغامرة",
        16 to "رسوم متحركة",
        35 to "كوميديا",
        80 to "جريمة",
        99 to "وثائقي",
        18 to "دراما",
        10751 to "عائلي",
        10762 to "أطفال",
        9648 to "غموض",
        10763 to "أخبار",
        10764 to "واقعي",
        10765 to "خيال علمي وفانتازيا",
        10766 to "دراما طويلة",
        10767 to "حوار",
        10768 to "حرب وسياسة",
        37 to "غربي"
    )

    // Official TMDB TV Genres (ID -> English Name)
    val TV_GENRES_EN_MAP = mapOf(
        10759 to "Action & Adventure",
        16 to "Animation",
        35 to "Comedy",
        80 to "Crime",
        99 to "Documentary",
        18 to "Drama",
        10751 to "Family",
        10762 to "Kids",
        9648 to "Mystery",
        10763 to "News",
        10764 to "Reality",
        10765 to "Sci-Fi & Fantasy",
        10766 to "Soap",
        10767 to "Talk",
        10768 to "War & Politics",
        37 to "Western"
    )

    // Specific anime curated genre taxonomy (Arabic)
    val ANIME_GENRES_MAP = mapOf(
        10759 to "أكشن ومغامرات",
        35 to "كوميديا",
        18 to "دراما",
        10765 to "خيال علمي وفانتازيا",
        9648 to "غموض ورعب",
        16 to "رسوم متحركة",
        10751 to "عائلي"
    )

    // Specific anime curated genre taxonomy (English)
    val ANIME_GENRES_EN_MAP = mapOf(
        10759 to "Action & Adventure",
        35 to "Comedy",
        18 to "Drama",
        10765 to "Sci-Fi & Fantasy",
        9648 to "Mystery & Horror",
        16 to "Animation",
        10751 to "Family"
    )

    // Language-partitioned dynamic cache: language code ("en", "ar") -> (GenreId -> Name)
    private val dynamicMovieGenresByLang = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()
    private val dynamicTvGenresByLang = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

    fun registerMovieGenres(genres: List<Genre>, language: String = getCurrentLanguage()) {
        val langKey = if (language.startsWith("ar", ignoreCase = true)) "ar" else "en"
        val langMap = dynamicMovieGenresByLang.computeIfAbsent(langKey) { ConcurrentHashMap() }
        genres.forEach { langMap[it.id] = it.name }
    }

    fun registerTvGenres(genres: List<Genre>, language: String = getCurrentLanguage()) {
        val langKey = if (language.startsWith("ar", ignoreCase = true)) "ar" else "en"
        val langMap = dynamicTvGenresByLang.computeIfAbsent(langKey) { ConcurrentHashMap() }
        genres.forEach { langMap[it.id] = it.name }
    }

    fun getMovieGenreNames(genreIds: List<Int>?, language: String = getCurrentLanguage()): List<String> {
        if (genreIds.isNullOrEmpty()) return emptyList()
        val isArabic = language.startsWith("ar", ignoreCase = true)
        val langKey = if (isArabic) "ar" else "en"
        val dynamicMap = dynamicMovieGenresByLang[langKey]

        return genreIds.mapNotNull { id ->
            if (isArabic) {
                dynamicMap?.get(id)
                    ?: MOVIE_GENRES_MAP[id]
                    ?: MOVIE_GENRES_EN_MAP[id]
            } else {
                dynamicMap?.get(id)
                    ?: MOVIE_GENRES_EN_MAP[id]
                    ?: MOVIE_GENRES_MAP[id]
            }
        }
    }

    fun getTvGenreNames(genreIds: List<Int>?, language: String = getCurrentLanguage()): List<String> {
        if (genreIds.isNullOrEmpty()) return emptyList()
        val isArabic = language.startsWith("ar", ignoreCase = true)
        val langKey = if (isArabic) "ar" else "en"
        val dynamicMap = dynamicTvGenresByLang[langKey]

        return genreIds.mapNotNull { id ->
            if (isArabic) {
                dynamicMap?.get(id)
                    ?: TV_GENRES_MAP[id]
                    ?: TV_GENRES_EN_MAP[id]
            } else {
                dynamicMap?.get(id)
                    ?: TV_GENRES_EN_MAP[id]
                    ?: TV_GENRES_MAP[id]
            }
        }
    }

    fun getMovieGenres(language: String = getCurrentLanguage()): List<Genre> {
        val isArabic = language.startsWith("ar", ignoreCase = true)
        val langKey = if (isArabic) "ar" else "en"
        val dynamicMap = dynamicMovieGenresByLang[langKey]

        val baseMap = if (isArabic) MOVIE_GENRES_MAP else MOVIE_GENRES_EN_MAP
        return baseMap.map { (id, name) ->
            Genre(id = id, name = dynamicMap?.get(id) ?: name)
        }
    }

    fun getTvGenres(language: String = getCurrentLanguage()): List<Genre> {
        val isArabic = language.startsWith("ar", ignoreCase = true)
        val langKey = if (isArabic) "ar" else "en"
        val dynamicMap = dynamicTvGenresByLang[langKey]

        val baseMap = if (isArabic) TV_GENRES_MAP else TV_GENRES_EN_MAP
        return baseMap.map { (id, name) ->
            Genre(id = id, name = dynamicMap?.get(id) ?: name)
        }
    }

    fun getAnimeGenres(language: String = getCurrentLanguage()): List<Genre> {
        val isArabic = language.startsWith("ar", ignoreCase = true)
        val baseMap = if (isArabic) ANIME_GENRES_MAP else ANIME_GENRES_EN_MAP
        return baseMap.map { (id, name) ->
            Genre(id = id, name = name)
        }
    }
}
