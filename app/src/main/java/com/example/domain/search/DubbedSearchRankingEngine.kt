package com.example.domain.search

import com.example.domain.models.CoverageStatus
import com.example.domain.models.MediaVariantMode
import com.example.domain.models.Movie
import com.example.domain.models.Series

/**
 * Intelligent Dubbed Search Engine & Relevant Result Ranker for CineStream.
 *
 * Implements Phase 19.5 Specifications:
 * 1. Understands media title intent separately from dubbed modifier words.
 * 2. Primary tier: Confirmed dubbed version of the requested work itself (Rank 1).
 * 3. Secondary tier: Standard matching results for requested work when no dubbed version is available (Rank 2).
 *    Never drops the requested work or forces unrelated dubbed items ahead of it.
 * 4. Tertiary tier: Relevant dubbed alternative suggestions ONLY if they share genuine nominal/textual
 *    overlap with the user's clean query (Rank 3).
 * 5. Rejects unrelated dubbed items (zero token overlap) from being displayed as suggestions.
 * 6. Ensures normal search queries preserve their exact regular ranking without alteration.
 * 7. Entirely generic: NO hardcoded movie or series titles.
 */
object DubbedSearchRankingEngine {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "of", "and", "in", "to", "for", "on", "with", "at", "by", "from",
        "ال", "و", "في", "من", "عن", "مع", "علي", "على", "الى", "إلى", "لا"
    )

    private const val MIN_RELEVANCE_THRESHOLD = 0.35

    /**
     * Checks if a movie has a verified, confirmed dubbed source candidate.
     */
    fun hasConfirmedDubbedSource(movie: Movie): Boolean {
        return movie.sourceCandidates.any {
            it.mode == MediaVariantMode.DUBBED &&
            it.status == CoverageStatus.FOUND &&
            it.sourcePageUrl.isNotBlank()
        } || (movie.mode.equals("dubbed", ignoreCase = true) && !movie.sourceUrl.isNullOrBlank())
    }

    /**
     * Checks if a series has a verified, confirmed dubbed source candidate.
     */
    fun hasConfirmedDubbedSource(series: Series): Boolean {
        return series.sourceCandidates.any {
            it.mode == MediaVariantMode.DUBBED &&
            it.status == CoverageStatus.FOUND &&
            it.sourcePageUrl.isNotBlank()
        } || (series.mode.equals("dubbed", ignoreCase = true) && !series.sourceUrl.isNullOrBlank())
    }

    /**
     * Checks if a movie has unverified candidate metadata.
     */
    fun hasUnverifiedCandidate(movie: Movie): Boolean {
        if (hasConfirmedDubbedSource(movie)) return false
        return movie.sourceCandidates.any {
            it.mode == MediaVariantMode.DUBBED
        }
    }

    /**
     * Checks if a series has unverified candidate metadata.
     */
    fun hasUnverifiedCandidate(series: Series): Boolean {
        if (hasConfirmedDubbedSource(series)) return false
        return series.sourceCandidates.any {
            it.mode == MediaVariantMode.DUBBED
        }
    }

    /**
     * Checks if a candidate is the primary requested work matching [cleanQuery].
     */
    fun isPrimaryWorkMatch(
        cleanQuery: String,
        title: String,
        originalTitle: String? = null,
        year: Int = 0
    ): Boolean {
        val qNorm = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(cleanQuery))
        if (qNorm.isBlank()) return false

        val tNorm = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(title))
        val oNorm = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(originalTitle ?: ""))

        if (tNorm.isNotBlank() && tNorm == qNorm) return true
        if (oNorm.isNotBlank() && oNorm == qNorm) return true

        // Check using MediaTitleMatcher algorithm
        val dummyMovie = Movie(
            id = "0",
            title = title,
            originalTitle = originalTitle ?: "",
            overview = "",
            posterUrl = "",
            backdropUrl = "",
            year = year,
            rating = 0.0,
            genres = emptyList(),
            runtime = 0
        )
        if (MediaTitleMatcher.matchesMovie(cleanQuery, dummyMovie)) return true

        // Check token equivalence if numbers match
        val qNums = MediaTitleMatcher.extractNumberTokens(qNorm)
        val tNums = MediaTitleMatcher.extractNumberTokens(tNorm)
        if (qNums == tNums && qNorm.length >= 3 && tNorm.length >= 3) {
            val qWords = qNorm.split(" ").filter { it.length >= 2 && it !in STOP_WORDS }
            val tWords = tNorm.split(" ").filter { it.length >= 2 && it !in STOP_WORDS }
            if (qWords.isNotEmpty() && tWords.isNotEmpty() && qWords.toSet() == tWords.toSet()) {
                return true
            }
        }

        return false
    }

    fun isPrimaryWorkMatch(cleanQuery: String, movie: Movie): Boolean {
        return isPrimaryWorkMatch(cleanQuery, movie.title, movie.originalTitle, movie.year)
    }

    fun isPrimaryWorkMatch(cleanQuery: String, series: Series): Boolean {
        return isPrimaryWorkMatch(cleanQuery, series.title, series.originalTitle, series.year)
    }

    /**
     * Calculates semantic and textual relevance score between clean query and candidate title.
     * Returns a score in range [0.0, 1.0].
     */
    fun calculateRelevanceScore(
        cleanQuery: String,
        title: String,
        originalTitle: String? = null
    ): Double {
        val qNorm = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(cleanQuery))
        if (qNorm.isBlank()) return 0.0

        val tNorm = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(title))
        val oNorm = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(originalTitle ?: ""))

        val qWords = qNorm.split(" ").filter { it.length >= 2 && it !in STOP_WORDS }.toSet()
        if (qWords.isEmpty()) return 0.0

        val tWords = tNorm.split(" ").filter { it.length >= 2 && it !in STOP_WORDS }.toSet()
        val oWords = oNorm.split(" ").filter { it.length >= 2 && it !in STOP_WORDS }.toSet()
        val candWords = tWords + oWords

        val shared = qWords intersect candWords
        if (shared.isEmpty()) {
            // Check substring containment if length is substantial
            if (qNorm.length >= 4 && (tNorm.contains(qNorm) || oNorm.contains(qNorm))) {
                return 0.6
            }
            return 0.0
        }

        val overlapRatio = shared.size.toDouble() / qWords.size
        // Bonus for sharing multiple significant tokens
        val multiWordBonus = if (shared.size >= 2) 0.2 else 0.0
        return (overlapRatio + multiWordBonus).coerceIn(0.0, 1.0)
    }

    /**
     * Determines whether a candidate qualifies as a legitimate relevant dubbed alternative suggestion.
     */
    fun isRelevantDubbedAlternative(
        cleanQuery: String,
        title: String,
        originalTitle: String? = null
    ): Boolean {
        val score = calculateRelevanceScore(cleanQuery, title, originalTitle)
        return score >= MIN_RELEVANCE_THRESHOLD
    }

    /**
     * Ranks a list of Movies when dubbed intent is active:
     * - Tier 1: Primary match for cleanQuery WITH confirmed dubbed source (Priority 1)
     * - Tier 2: Primary match for cleanQuery WITHOUT confirmed dubbed source (Priority 2)
     * - Tier 3: Relevant dubbed alternative suggestions (Priority 3)
     * - Tier 4: Other standard matches (Priority 4)
     * - Filters out completely unrelated dubbed items (0 relevance to query).
     */
    fun rankMoviesForDubbedIntent(
        movies: List<Movie>,
        cleanQuery: String
    ): List<Movie> {
        if (movies.isEmpty()) return emptyList()
        val qClean = cleanQuery.trim()

        if (qClean.isBlank()) {
            // General dubbed browsing without a specific work name: prioritize confirmed dubbed
            return movies.sortedWith(
                compareByDescending<Movie> { hasConfirmedDubbedSource(it) }
                    .thenByDescending { hasUnverifiedCandidate(it) }
            )
        }

        val tier1PrimaryDubbed = mutableListOf<Movie>()
        val tier2PrimaryStandard = mutableListOf<Movie>()
        val tier3RelevantDubbed = mutableListOf<Pair<Movie, Double>>()
        val tier4OtherMatches = mutableListOf<Movie>()

        for (movie in movies) {
            val isPrimary = isPrimaryWorkMatch(qClean, movie)
            val isDubbed = hasConfirmedDubbedSource(movie)

            if (isPrimary && isDubbed) {
                tier1PrimaryDubbed.add(movie)
            } else if (isPrimary) {
                tier2PrimaryStandard.add(movie)
            } else if (isDubbed) {
                val relevance = calculateRelevanceScore(qClean, movie.title, movie.originalTitle)
                if (relevance >= MIN_RELEVANCE_THRESHOLD) {
                    tier3RelevantDubbed.add(movie to relevance)
                }
                // If relevance < threshold, omit from dubbed suggestions (never show random dubbed items!)
            } else {
                tier4OtherMatches.add(movie)
            }
        }

        val sortedTier3 = tier3RelevantDubbed.sortedByDescending { it.second }.map { it.first }

        return (tier1PrimaryDubbed + tier2PrimaryStandard + sortedTier3 + tier4OtherMatches).distinctBy { it.id }
    }

    /**
     * Ranks a list of Series when dubbed intent is active.
     */
    fun rankSeriesForDubbedIntent(
        seriesList: List<Series>,
        cleanQuery: String
    ): List<Series> {
        if (seriesList.isEmpty()) return emptyList()
        val qClean = cleanQuery.trim()

        if (qClean.isBlank()) {
            return seriesList.sortedWith(
                compareByDescending<Series> { hasConfirmedDubbedSource(it) }
                    .thenByDescending { hasUnverifiedCandidate(it) }
            )
        }

        val tier1PrimaryDubbed = mutableListOf<Series>()
        val tier2PrimaryStandard = mutableListOf<Series>()
        val tier3RelevantDubbed = mutableListOf<Pair<Series, Double>>()
        val tier4OtherMatches = mutableListOf<Series>()

        for (series in seriesList) {
            val isPrimary = isPrimaryWorkMatch(qClean, series)
            val isDubbed = hasConfirmedDubbedSource(series)

            if (isPrimary && isDubbed) {
                tier1PrimaryDubbed.add(series)
            } else if (isPrimary) {
                tier2PrimaryStandard.add(series)
            } else if (isDubbed) {
                val relevance = calculateRelevanceScore(qClean, series.title, series.originalTitle)
                if (relevance >= MIN_RELEVANCE_THRESHOLD) {
                    tier3RelevantDubbed.add(series to relevance)
                }
            } else {
                tier4OtherMatches.add(series)
            }
        }

        val sortedTier3 = tier3RelevantDubbed.sortedByDescending { it.second }.map { it.first }

        return (tier1PrimaryDubbed + tier2PrimaryStandard + sortedTier3 + tier4OtherMatches).distinctBy { it.id }
    }

    /**
     * Centralized unified ranker for SearchBarDropdown.
     */
    fun rankDropdownResults(
        movies: List<Movie>,
        series: List<Series>,
        cleanQuery: String,
        isDubbedIntent: Boolean
    ): List<Pair<Any, Boolean>> {
        if (!isDubbedIntent) {
            return movies.map { it to true } + series.map { it to false }
        }

        val rankedMovies = rankMoviesForDubbedIntent(movies, cleanQuery)
        val rankedSeries = rankSeriesForDubbedIntent(series, cleanQuery)

        // Merge movie and series maintaining tier priorities
        val moviePairs = rankedMovies.map { it to true }
        val seriesPairs = rankedSeries.map { it to false }

        val qClean = cleanQuery.trim()
        return (moviePairs + seriesPairs).filter { (item, isMovie) ->
            val title = if (isMovie) (item as Movie).title else (item as Series).title
            val orig = if (isMovie) (item as Movie).originalTitle else (item as Series).originalTitle
            val isDubbed = if (isMovie) hasConfirmedDubbedSource(item as Movie) else hasConfirmedDubbedSource(item as Series)
            val isPrimary = if (qClean.isNotBlank()) isPrimaryWorkMatch(qClean, title, orig) else false
            if (isDubbed && !isPrimary) {
                calculateRelevanceScore(qClean, title, orig) >= MIN_RELEVANCE_THRESHOLD
            } else {
                true
            }
        }.sortedWith(
            compareBy<Pair<Any, Boolean>> { (item, isMovie) ->
                val title = if (isMovie) (item as Movie).title else (item as Series).title
                val orig = if (isMovie) (item as Movie).originalTitle else (item as Series).originalTitle
                val isDubbed = if (isMovie) hasConfirmedDubbedSource(item as Movie) else hasConfirmedDubbedSource(item as Series)
                val isPrimary = if (qClean.isNotBlank()) isPrimaryWorkMatch(qClean, title, orig) else false

                when {
                    isPrimary && isDubbed -> 0 // Tier 1: Primary Confirmed Dubbed
                    isPrimary -> 1            // Tier 2: Primary Standard
                    isDubbed -> 2             // Tier 3: Relevant Dubbed Alternative
                    else -> 3                 // Tier 4: Other general matches
                }
            }.thenByDescending { (item, isMovie) ->
                val title = if (isMovie) (item as Movie).title else (item as Series).title
                val orig = if (isMovie) (item as Movie).originalTitle else (item as Series).originalTitle
                calculateRelevanceScore(qClean, title, orig)
            }
        )
    }

    /**
     * Generic ranker for Unified presentation items (such as SearchScreen results).
     */
    fun <T> rankUnifiedMedia(
        items: List<T>,
        cleanQuery: String,
        isDubbedIntent: Boolean,
        titleExtractor: (T) -> String,
        canonicalTitleExtractor: (T) -> String?,
        isDubbedExtractor: (T) -> Boolean
    ): List<T> {
        if (!isDubbedIntent || items.isEmpty()) return items
        val qClean = cleanQuery.trim()
        if (qClean.isBlank()) {
            return items.sortedByDescending { isDubbedExtractor(it) }
        }

        return items.filter { item ->
            val title = titleExtractor(item)
            val orig = canonicalTitleExtractor(item)
            val isDubbed = isDubbedExtractor(item)
            val isPrimary = isPrimaryWorkMatch(qClean, title, orig)
            if (isDubbed && !isPrimary) {
                calculateRelevanceScore(qClean, title, orig) >= MIN_RELEVANCE_THRESHOLD
            } else {
                true
            }
        }.sortedWith(
            compareBy<T> { item ->
                val title = titleExtractor(item)
                val orig = canonicalTitleExtractor(item)
                val isDubbed = isDubbedExtractor(item)
                val isPrimary = isPrimaryWorkMatch(qClean, title, orig)

                when {
                    isPrimary && isDubbed -> 0 // Tier 1: Primary Confirmed Dubbed
                    isPrimary -> 1            // Tier 2: Primary Standard
                    isDubbed -> 2             // Tier 3: Relevant Dubbed Alternative
                    else -> 3                 // Tier 4: Other general matches
                }
            }.thenByDescending { item ->
                val title = titleExtractor(item)
                val orig = canonicalTitleExtractor(item)
                calculateRelevanceScore(qClean, title, orig)
            }
        )
    }
}
