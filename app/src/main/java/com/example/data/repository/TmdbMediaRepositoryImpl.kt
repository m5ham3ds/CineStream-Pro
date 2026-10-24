package com.example.data.repository

import com.example.data.remote.RetrofitClient
import com.example.domain.models.Movie
import com.example.domain.models.PaginatedResult
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.domain.models.CastMember
import com.example.domain.models.VideoTrailer
import com.example.domain.models.Season
import com.example.domain.models.Episode
import com.example.domain.models.PersonDetails
import java.time.LocalDate

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.domain.models.Genre
import java.util.concurrent.ConcurrentHashMap

class TmdbMediaRepositoryImpl : MediaRepository {
    
    private val listCache = ConcurrentHashMap<String, List<*>>()
    private val movieDetailsCache = ConcurrentHashMap<String, Movie>()
    private val seriesDetailsCache = ConcurrentHashMap<String, Series>()
    private val personDetailsCache = ConcurrentHashMap<String, PersonDetails>()
    private val seasonEpisodesCache = ConcurrentHashMap<String, List<Episode>>()

    override fun clearCache() {
        listCache.clear()
        movieDetailsCache.clear()
        seriesDetailsCache.clear()
        personDetailsCache.clear()
        seasonEpisodesCache.clear()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> getCachedListFlow(
        cacheKey: String,
        crossinline fetcher: suspend () -> List<T>
    ): Flow<List<T>> = flow {
        val context = com.example.MyApplication.appContext
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
        // v5 invalidates stale category payloads created before strict date separation,
        // verified ratings and semantic anime/trending filters were enforced.
        val partitionedCacheKey = "catalog_v5_${currentLang}_${cacheKey}"

        var cached = listCache[partitionedCacheKey] as? List<T>
        if (cached == null || cached.isEmpty()) {
            cached = com.example.utils.MediaListDiskCacheManager.loadList<T>(context, partitionedCacheKey)
            if (cached != null && cached.isNotEmpty()) listCache[partitionedCacheKey] = cached
        }

        var hasEmittedCache = false
        if (cached != null && cached.isNotEmpty()) {
            emit(cached)
            hasEmittedCache = true
        }

        try {
            // An empty list is a valid result for a strict category. Do not keep a previous
            // stale list when the current language/category genuinely has no matching items.
            // The home page and category ViewModels can request several feeds at once.
            // Bound upstream concurrency so bursts of authenticated requests do not compete
            // with scrolling/image decoding on slower mobile connections.
            val fresh = tmdbSemaphore.withPermit { fetcher() }
            listCache[partitionedCacheKey] = fresh
            if (fresh.isEmpty()) {
                com.example.utils.MediaListDiskCacheManager.clearList(context, partitionedCacheKey)
            } else {
                com.example.utils.MediaListDiskCacheManager.saveList(context, partitionedCacheKey, fresh)
            }
            emit(fresh)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            val tmdbError = when (e) {
                is retrofit2.HttpException -> when (e.code()) {
                    401, 403 -> com.example.domain.models.TmdbException.AuthException(
                        "Cloudflare Worker Gateway HTTP ${e.code()}: Authentication failed or unauthorized request.", e
                    )
                    504 -> {
                        val isOnline = try { com.example.utils.NetworkUtils.isInternetAvailable(context) } catch (_: Throwable) { false }
                        if (!isOnline) com.example.domain.models.TmdbException.NetworkException(
                            "Connection to Gateway failed: Offline cache miss (HTTP 504).", e
                        ) else com.example.domain.models.TmdbException.ServerException(504, "Gateway server error (HTTP 504).", e)
                    }
                    in 500..599 -> com.example.domain.models.TmdbException.ServerException(
                        e.code(), "Gateway server error (HTTP ${e.code()}).", e
                    )
                    else -> com.example.domain.models.TmdbException.UnknownException(
                        "Gateway HTTP error ${e.code()}: ${e.message()}", e
                    )
                }
                is java.io.IOException -> com.example.domain.models.TmdbException.NetworkException(
                    "Connection to Gateway failed: ${e.message}", e
                )
                is com.example.domain.models.TmdbException -> e
                else -> com.example.domain.models.TmdbException.UnknownException(e.message ?: "Unknown Gateway error", e)
            }

            if (!hasEmittedCache) {
                val fallback = com.example.utils.MediaListDiskCacheManager.loadList<T>(context, partitionedCacheKey)
                if (fallback != null && fallback.isNotEmpty()) {
                    listCache[partitionedCacheKey] = fallback
                    emit(fallback)
                } else {
                    throw tmdbError
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun currentTmdbLanguage(): String {
        val lang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(com.example.MyApplication.appContext)
        return if (lang == "ar") "ar-SA" else "en-US"
    }

    // TMDB date filters use ISO calendar dates. UTC prevents a region/time-zone
    // boundary from moving an item between Coming Soon and New Releases locally.
    private fun todayIso(): String = LocalDate.now(java.time.ZoneOffset.UTC).toString()
    private fun tomorrowIso(): String = LocalDate.now(java.time.ZoneOffset.UTC).plusDays(1).toString()
    private fun recentStartIso(days: Long = 90): String = LocalDate.now(java.time.ZoneOffset.UTC).minusDays(days).toString()

    private fun verifiedTmdbRating(voteAverage: Double?, voteCount: Int?): Double =
        verifiedTmdbRatingValue(voteAverage, voteCount)

    /**
     * Some broken/stale gateways have returned the same hard-coded score for every
     * title in a page. A page of five or more scored titles all sharing one score is
     * treated as a corrupt response: titles remain visible, but the suspicious score
     * is omitted instead of presented as a verified TMDB rating.
     */
    private fun hasSuspiciousUniformRating(ratings: List<Double>): Boolean {
        val valid = ratings.filter { it.isFinite() && it > 0.0 && it <= 10.0 }
        return valid.size >= 5 && valid.map { kotlin.math.round(it * 10.0).toInt() }.distinct().size == 1
    }

    private fun Iterable<com.example.data.remote.TmdbMovie>.toDomainMovies(
        suppressAllRatings: Boolean = false
    ): List<Movie> {
        val values = toList()
        val ratings = values.map { verifiedTmdbRating(it.voteAverage, it.voteCount) }
        val corruptUniformPage = hasSuspiciousUniformRating(ratings)
        return values.distinctBy { it.id }.map {
            it.toDomain(suppressRating = suppressAllRatings || corruptUniformPage)
        }
    }

    private fun Iterable<com.example.data.remote.TmdbSeries>.toDomainSeries(
        suppressAllRatings: Boolean = false
    ): List<Series> {
        val values = toList()
        val ratings = values.map { verifiedTmdbRating(it.voteAverage, it.voteCount) }
        val corruptUniformPage = hasSuspiciousUniformRating(ratings)
        return values.distinctBy { it.id }.map {
            it.toDomain(suppressRating = suppressAllRatings || corruptUniformPage)
        }
    }

    // Date-window keys prevent yesterday's persisted catalog from being shown as if
    // it still belonged to today's Coming Soon / New Releases categories.
    private fun dateWindowCacheKey(category: String, start: String, end: String): String =
        "${category}_v2_${start}_$end"

    override fun getUpcomingMovies(): Flow<List<Movie>> =
        getCachedListFlow(dateWindowCacheKey("upcoming_movies", tomorrowIso(), "future")) {
            fetchUpcomingMovieWindow(targetCount = 18, maxPages = 6)
        }

    /**
     * Use TMDB's dedicated upcoming catalog endpoint, then validate every date on
     * the client as a defensive guard. This prevents a stale/broken Worker response
     * from leaking released movies into Coming Soon. Multiple bounded pages avoid
     * showing a single card when the first upstream page is sparse.
     */
    private suspend fun fetchUpcomingMovieWindow(
        targetCount: Int,
        maxPages: Int
    ): List<Movie> {
        val today = todayIso()
        val unique = linkedMapOf<Int, com.example.data.remote.TmdbMovie>()
        var totalPages = 1

        for (page in 1..maxPages.coerceAtLeast(1)) {
            val response = RetrofitClient.tmdbApi.getUpcomingMovies(
                page = page,
                language = currentTmdbLanguage()
            )
            totalPages = response.totalPages.coerceAtLeast(1)
            response.results
                .filter { movie ->
                    val date = movie.releaseDate
                    !date.isNullOrBlank() && date > today && runCatching { LocalDate.parse(date) }.isSuccess
                }
                .forEach { unique.putIfAbsent(it.id, it) }

            if (unique.size >= targetCount || page >= totalPages) break
        }

        return unique.values.sortedBy { it.releaseDate }.toDomainMovies(suppressAllRatings = true)
    }

    override fun getAnimeSeries(): Flow<List<Series>> = getCachedListFlow("anime_series") {
        val response = RetrofitClient.tmdbApi.discoverSeries(
            page = 1,
            sortBy = "popularity.desc",
            withGenres = "16",
            originalLanguage = "ja",
            language = currentTmdbLanguage()
        )
        response.results
            .filter { it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16) }
            .toDomainSeries()
    }

    override fun getAnimeMovies(): Flow<List<Movie>> = getCachedListFlow("anime_movies") {
        val response = RetrofitClient.tmdbApi.discoverMovies(
            page = 1,
            sortBy = "popularity.desc",
            withGenres = "16",
            originalLanguage = "ja",
            language = currentTmdbLanguage()
        )
        response.results
            .filter { it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16) }
            .toDomainMovies()
    }

    override fun getNewReleasesMovies(): Flow<List<Movie>> = getCachedListFlow(dateWindowCacheKey("new_releases_movies", recentStartIso(), todayIso())) {
        val today = todayIso()
        val start = recentStartIso()
        val response = RetrofitClient.tmdbApi.discoverMovies(
            page = 1,
            sortBy = "primary_release_date.desc",
            releaseDateGte = start,
            releaseDateLte = today,
            language = currentTmdbLanguage()
        )
        response.results
            .filter { !it.releaseDate.isNullOrBlank() && it.releaseDate >= start && it.releaseDate <= today && runCatching { LocalDate.parse(it.releaseDate) }.isSuccess }
            .toDomainMovies()
    }

    override fun getNewReleasesSeries(): Flow<List<Series>> = getCachedListFlow(dateWindowCacheKey("new_releases_series", recentStartIso(), todayIso())) {
        val today = todayIso()
        val start = recentStartIso()
        val response = RetrofitClient.tmdbApi.discoverSeries(
            page = 1,
            sortBy = "first_air_date.desc",
            firstAirDateGte = start,
            firstAirDateLte = today,
            language = currentTmdbLanguage()
        )
        response.results
            .filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start && it.firstAirDate <= today && runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME
            }
            .toDomainSeries()
    }

    override fun getMovies(): Flow<List<Movie>> = getCachedListFlow("popular_movies") {
        RetrofitClient.tmdbApi.getPopularMovies(language = currentTmdbLanguage()).results
            .toDomainMovies()
    }

    override fun getSeries(): Flow<List<Series>> = getCachedListFlow("popular_series") {
        RetrofitClient.tmdbApi.getPopularSeries(language = currentTmdbLanguage()).results
            .filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }
            .toDomainSeries()
    }

    override fun getTrendingMovies(): Flow<List<Movie>> = getCachedListFlow("trending_movies") {
        RetrofitClient.tmdbApi.getTrendingMovies(language = currentTmdbLanguage()).results
            .toDomainMovies()
    }

    override fun getArabicMovies(): Flow<List<Movie>> = getCachedListFlow("arabic_movies") {
        RetrofitClient.tmdbApi.discoverMovies(
            page = 1,
            sortBy = "popularity.desc",
            originalLanguage = "ar",
            language = currentTmdbLanguage()
        ).results
            .filter { it.originalLanguage.equals("ar", ignoreCase = true) }
            .toDomainMovies()
    }

    override fun getArabicSeries(): Flow<List<Series>> = getCachedListFlow("arabic_series") {
        RetrofitClient.tmdbApi.discoverSeries(
            page = 1,
            sortBy = "popularity.desc",
            originalLanguage = "ar",
            language = currentTmdbLanguage()
        ).results
            .filter { it.originalLanguage.equals("ar", ignoreCase = true) }
            .toDomainSeries()
    }

    override fun getUpcomingSeries(): Flow<List<Series>> = getCachedListFlow(dateWindowCacheKey("upcoming_series", tomorrowIso(), "future")) {
        val start = tomorrowIso()
        RetrofitClient.tmdbApi.discoverSeries(
            page = 1,
            sortBy = "first_air_date.asc",
            firstAirDateGte = start,
            language = currentTmdbLanguage()
        ).results
            .filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start &&
                    runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME
            }
            .toDomainSeries(suppressAllRatings = true)
    }

    override fun getUpcomingAnime(): Flow<List<Series>> = getCachedListFlow(dateWindowCacheKey("upcoming_anime", tomorrowIso(), "future")) {
        val start = tomorrowIso()
        RetrofitClient.tmdbApi.discoverSeries(
            page = 1,
            sortBy = "first_air_date.asc",
            withGenres = "16",
            originalLanguage = "ja",
            firstAirDateGte = start,
            language = currentTmdbLanguage()
        ).results
            .filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start &&
                    runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16)
            }
            .toDomainSeries(suppressAllRatings = true)
    }

    override fun getNewReleasesAnime(): Flow<List<Series>> = getCachedListFlow(dateWindowCacheKey("new_releases_anime", recentStartIso(), todayIso())) {
        val today = todayIso()
        val start = recentStartIso()
        RetrofitClient.tmdbApi.discoverSeries(
            page = 1,
            sortBy = "first_air_date.desc",
            withGenres = "16",
            originalLanguage = "ja",
            firstAirDateGte = start,
            firstAirDateLte = today,
            language = currentTmdbLanguage()
        ).results
            .filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start && it.firstAirDate <= today && runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16)
            }
            .toDomainSeries()
    }

    override fun getTrendingSeries(): Flow<List<Series>> = getCachedListFlow("trending_series") {
        RetrofitClient.tmdbApi.getTrendingSeries(language = currentTmdbLanguage()).results
            .filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }
            .toDomainSeries()
    }

    private val trendingAnimePriorityIds = ConcurrentHashMap.newKeySet<String>()

    override fun getTrendingAnime(): Flow<List<Series>> = getCachedListFlow("trending_anime_window_v3") {
        fetchTrendingAnimeFeed().items
    }

    /**
     * TMDB cannot filter a global Trending endpoint by genre, so collect a small,
     * bounded window from both daily and weekly TV Trending and then strictly retain
     * Japanese Animation titles. This keeps the section semantically Trending; it
     * deliberately does not fill the row with Popular catalog items.
     */
    private suspend fun fetchTrendingAnimeFeed(): PaginatedResult<Series> {
        // TMDB's global Trending endpoint cannot filter by genre. Scan one page
        // of daily and weekly Trending first, then use the dedicated Popular Anime
        // catalog only as a bounded backfill when the true trending set is too small.
        // Every item remains strictly verified as Japanese Animation (genre 16).
        val daily = fetchTrendingAnimeWindow(
            localPage = 1,
            upstreamPagesPerWindow = 1,
            window = "day"
        )
        val weekly = fetchTrendingAnimeWindow(
            localPage = 1,
            upstreamPagesPerWindow = 1,
            window = "week"
        )

        val actualTrending = (daily.items + weekly.items)
            .distinctBy { it.id }
        val popularBackfill = if (actualTrending.size < 20) {
            fetchAnimeCatalogPage(1)
        } else {
            PaginatedResult(emptyList(), 1, 1, 0)
        }
        val combined = (actualTrending + popularBackfill.items)
            .distinctBy { it.id }
            .take(20)

        trendingAnimePriorityIds.clear()
        trendingAnimePriorityIds.addAll(combined.map { it.id })

        return PaginatedResult(
            items = combined,
            page = 1,
            totalPages = maxOf(daily.totalPages, weekly.totalPages, popularBackfill.totalPages).coerceAtLeast(1),
            totalResults = maxOf(actualTrending.size, popularBackfill.totalResults)
        )
    }

    private suspend fun fetchAnimeCatalogPage(page: Int): PaginatedResult<Series> {
        val response = RetrofitClient.tmdbApi.discoverSeries(
            page = page.coerceAtLeast(1),
            sortBy = "popularity.desc",
            withGenres = "16",
            originalLanguage = "ja",
            language = currentTmdbLanguage()
        )
        val filtered = response.results
            .filter {
                it.genreIds.orEmpty().contains(16) &&
                    it.originalLanguage.equals("ja", ignoreCase = true)
            }
            .distinctBy { it.id }
        return PaginatedResult(
            items = filtered.toDomainSeries(),
            page = response.page,
            totalPages = response.totalPages.coerceAtLeast(1),
            totalResults = response.totalResults
        )
    }

    /**
     * TMDB Trending is global and not genre-filterable. Scan a bounded window of
     * the selected day/week feed, then apply strict anime predicates. Local pages
     * map to disjoint single-page TMDB windows for daily and weekly Trending; the
     * dedicated popular-anime catalog backfills additional pages without admitting
     * non-anime shows or duplicate IDs.
     */
    private suspend fun fetchTrendingAnimeWindow(
        localPage: Int,
        upstreamPagesPerWindow: Int,
        window: String
    ): PaginatedResult<Series> {
        val safeLocalPage = localPage.coerceAtLeast(1)
        val startRemotePage = (safeLocalPage - 1) * upstreamPagesPerWindow + 1
        val candidates = linkedMapOf<Int, com.example.data.remote.TmdbSeries>()
        var remoteTotalPages = startRemotePage + upstreamPagesPerWindow - 1

        for (remotePage in startRemotePage until (startRemotePage + upstreamPagesPerWindow)) {
            if (remotePage > remoteTotalPages) break
            val response = RetrofitClient.tmdbApi.getTrendingSeries(
                page = remotePage,
                window = window,
                language = currentTmdbLanguage()
            )
            remoteTotalPages = response.totalPages.coerceAtLeast(1)
            response.results
                .filter {
                    it.genreIds.orEmpty().contains(16) &&
                        it.originalLanguage.equals("ja", ignoreCase = true)
                }
                .forEach { candidates.putIfAbsent(it.id, it) }
        }

        val mapped = candidates.values.toDomainSeries()
        val localTotalPages = kotlin.math.ceil(
            remoteTotalPages.toDouble() / upstreamPagesPerWindow.toDouble()
        ).toInt().coerceAtLeast(1)
        return PaginatedResult(
            items = mapped,
            page = safeLocalPage,
            totalPages = localTotalPages,
            totalResults = mapped.size
        )
    }

    // Paginated endpoints use the same semantic filters as the home/category feeds.
    override suspend fun getTrendingMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getTrendingMovies(page = page, language = currentTmdbLanguage())
            PaginatedResult(response.results.toDomainMovies(), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getTrendingSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getTrendingSeries(page = page, language = currentTmdbLanguage())
            val filtered = response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }
            PaginatedResult(filtered.toDomainSeries(), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getTrendingAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            if (page <= 1) {
                fetchTrendingAnimeFeed()
            } else {
                val daily = fetchTrendingAnimeWindow(
                    localPage = page,
                    upstreamPagesPerWindow = 1,
                    window = "day"
                )
                val weekly = fetchTrendingAnimeWindow(
                    localPage = page,
                    upstreamPagesPerWindow = 1,
                    window = "week"
                )
                val popular = fetchAnimeCatalogPage(page)
                val items = (daily.items + weekly.items + popular.items)
                    .distinctBy { it.id }
                    .filterNot { it.id in trendingAnimePriorityIds }
                    .take(20)
                trendingAnimePriorityIds.addAll(items.map { it.id })
                PaginatedResult(
                    items = items,
                    page = page,
                    totalPages = maxOf(daily.totalPages, weekly.totalPages, popular.totalPages).coerceAtLeast(1),
                    totalResults = maxOf(daily.totalResults + weekly.totalResults, popular.totalResults)
                )
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getPopularMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getPopularMovies(page = page, language = currentTmdbLanguage())
            PaginatedResult(response.results.toDomainMovies(), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getPopularSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val response = RetrofitClient.tmdbApi.getPopularSeries(page = page, language = currentTmdbLanguage())
            val filtered = response.results.filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME }
            PaginatedResult(filtered.toDomainSeries(), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getPopularAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            fetchAnimeCatalogPage(page)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getUpcomingMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val today = todayIso()
            val response = RetrofitClient.tmdbApi.getUpcomingMovies(
                page = page,
                language = currentTmdbLanguage()
            )
            val filtered = response.results.filter { movie ->
                val date = movie.releaseDate
                !date.isNullOrBlank() && date > today && runCatching { LocalDate.parse(date) }.isSuccess
            }
            PaginatedResult(filtered.toDomainMovies(suppressAllRatings = true), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getUpcomingSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val start = tomorrowIso()
            val response = RetrofitClient.tmdbApi.discoverSeries(
                page = page, sortBy = "first_air_date.asc", firstAirDateGte = start,
                language = currentTmdbLanguage()
            )
            val filtered = response.results.filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start &&
                    runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME
            }
            PaginatedResult(filtered.toDomainSeries(suppressAllRatings = true), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getUpcomingAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val start = tomorrowIso()
            val response = RetrofitClient.tmdbApi.discoverSeries(
                page = page, sortBy = "first_air_date.asc", withGenres = "16", originalLanguage = "ja",
                firstAirDateGte = start, language = currentTmdbLanguage()
            )
            val filtered = response.results.filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start &&
                    runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16)
            }
            PaginatedResult(filtered.toDomainSeries(suppressAllRatings = true), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getNewReleasesMoviesPage(page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        try {
            val today = todayIso()
            val start = recentStartIso()
            val response = RetrofitClient.tmdbApi.discoverMovies(
                page = page, sortBy = "primary_release_date.desc", releaseDateGte = start,
                releaseDateLte = today, language = currentTmdbLanguage()
            )
            val filtered = response.results.filter {
                !it.releaseDate.isNullOrBlank() && it.releaseDate >= start && it.releaseDate <= today && runCatching { LocalDate.parse(it.releaseDate) }.isSuccess
            }
            PaginatedResult(filtered.toDomainMovies(), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getNewReleasesSeriesPage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val today = todayIso()
            val start = recentStartIso()
            val response = RetrofitClient.tmdbApi.discoverSeries(
                page = page, sortBy = "first_air_date.desc", firstAirDateGte = start,
                firstAirDateLte = today, language = currentTmdbLanguage()
            )
            val filtered = response.results.filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start && it.firstAirDate <= today && runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME
            }
            PaginatedResult(filtered.toDomainSeries(), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    override suspend fun getNewReleasesAnimePage(page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        try {
            val today = todayIso()
            val start = recentStartIso()
            val response = RetrofitClient.tmdbApi.discoverSeries(
                page = page, sortBy = "first_air_date.desc", withGenres = "16", originalLanguage = "ja",
                firstAirDateGte = start, firstAirDateLte = today, language = currentTmdbLanguage()
            )
            val filtered = response.results.filter {
                !it.firstAirDate.isNullOrBlank() && it.firstAirDate >= start && it.firstAirDate <= today && runCatching { LocalDate.parse(it.firstAirDate) }.isSuccess &&
                    it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16)
            }
            PaginatedResult(filtered.toDomainSeries(), response.page, response.totalPages, response.totalResults)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw e
        }
    }

    private val tmdbSemaphore = kotlinx.coroutines.sync.Semaphore(5)
    
    private suspend fun getRealSeasonCount(seriesId: Int): Int {
        return try {
            tmdbSemaphore.withPermit {
                RetrofitClient.tmdbApi.getSeriesDetails(seriesId).data?.seasons?.size ?: 0
            }
        } catch (e: Exception) {
            0
        }
    }

    override suspend fun getMovieById(id: String): Movie? = withContext(Dispatchers.IO) {
        val context = com.example.MyApplication.appContext
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
        val localizedCacheKey = "${id}_${currentLang}"
        movieDetailsCache[localizedCacheKey]?.let { return@withContext it }
        val diskCached = com.example.utils.MediaDetailsCacheManager.getMovieDetails(context, id)
        if (diskCached != null) movieDetailsCache[localizedCacheKey] = diskCached

        if (id.toIntOrNull() == null) {
            val cached = diskCached ?: com.example.utils.MediaDetailsCacheManager.getMovieDetails(context, id)
            if (cached != null) {
                movieDetailsCache[localizedCacheKey] = cached
                return@withContext cached
            }
            if (id.startsWith("provider|")) {
                val parts = id.split("|")
                val title = parts.getOrNull(2) ?: "Unknown"
                val thumb = parts.getOrNull(3) ?: ""
                val movie = Movie(
                    id = id,
                    title = title,
                    originalTitle = title,
                    overview = "Content from provider ${parts.getOrNull(1)}",
                    posterUrl = thumb,
                    backdropUrl = thumb,
                    year = 0,
                    releaseDate = null,
                    rating = 0.0,
                    genres = emptyList(),
                    runtime = 0,
                    language = "en",
                    cast = emptyList(),
                    trailers = emptyList()
                )
                movieDetailsCache[localizedCacheKey] = movie
                return@withContext movie
            }
            return@withContext null
        }
        try {
            val tmdbLangCode = if (currentLang == "ar") "ar-SA" else "en-US"
            val response = RetrofitClient.tmdbApi.getMovieDetails(id.toInt(), language = tmdbLangCode)
            val movie = response.data?.toDomainDetails() ?: throw IllegalStateException("Empty movie data")
            movieDetailsCache[localizedCacheKey] = movie
            com.example.utils.MediaDetailsCacheManager.saveMovieDetails(context, movie)
            movie
        } catch (e: Exception) {
            diskCached ?: com.example.utils.MediaDetailsCacheManager.getMovieDetails(context, id)
        }
    }

    override suspend fun getSeriesById(id: String): Series? = withContext(Dispatchers.IO) {
        val context = com.example.MyApplication.appContext
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
        val localizedCacheKey = "${id}_${currentLang}"
        seriesDetailsCache[localizedCacheKey]?.let { return@withContext it }
        val diskCached = com.example.utils.MediaDetailsCacheManager.getSeriesDetails(context, id)
        if (diskCached != null) seriesDetailsCache[localizedCacheKey] = diskCached

        if (id.toIntOrNull() == null) {
            val cached = diskCached ?: com.example.utils.MediaDetailsCacheManager.getSeriesDetails(context, id)
            if (cached != null) {
                seriesDetailsCache[localizedCacheKey] = cached
                return@withContext cached
            }
            if (id.startsWith("provider|")) {
                val parts = id.split("|")
                val title = parts.getOrNull(2) ?: "Unknown"
                val thumb = parts.getOrNull(3) ?: ""
                val series = Series(
                    id = id,
                    title = title,
                    overview = "Content from provider ${parts.getOrNull(1)}",
                    posterUrl = thumb,
                    backdropUrl = thumb,
                    year = 0,
                    firstAirDate = null,
                    rating = 0.0,
                    genres = emptyList(),
                    cast = emptyList(),
                    trailers = emptyList(),
                    seasons = listOf(
                        com.example.domain.models.Season(
                            id = "${id}_1",
                            seriesId = id,
                            seasonNumber = 1,
                            title = "Season 1",
                            posterUrl = thumb,
                            episodeCount = 0
                        )
                    ),
                    creator = parts.getOrNull(1),
                    status = "Unknown"
                )
                seriesDetailsCache[localizedCacheKey] = series
                return@withContext series
            }
            return@withContext null
        }
        try {
            val tmdbLangCode = if (currentLang == "ar") "ar-SA" else "en-US"
            val response = RetrofitClient.tmdbApi.getSeriesDetails(id.toInt(), language = tmdbLangCode)
            val series = response.data?.toDomainDetails() ?: throw IllegalStateException("Empty series data")
            seriesDetailsCache[localizedCacheKey] = series
            com.example.utils.MediaDetailsCacheManager.saveSeriesDetails(context, series)
            series
        } catch (e: Exception) {
            diskCached ?: com.example.utils.MediaDetailsCacheManager.getSeriesDetails(context, id)
        }
    }

    override suspend fun getMovieGenres(): List<Genre> = withContext(Dispatchers.IO) {
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(com.example.MyApplication.appContext)
        try {
            val genres = RetrofitClient.tmdbApi.getMovieGenreList(language = currentTmdbLanguage())
                .genres.map { Genre(it.id, it.name) }
            if (genres.isNotEmpty()) com.example.data.util.TmdbGenreHelper.registerMovieGenres(genres, currentLang)
            genres.ifEmpty { com.example.data.util.TmdbGenreHelper.getMovieGenres(currentLang) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.example.data.util.TmdbGenreHelper.getMovieGenres(currentLang)
        }
    }

    override suspend fun getTvGenres(): List<Genre> = withContext(Dispatchers.IO) {
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(com.example.MyApplication.appContext)
        try {
            val genres = RetrofitClient.tmdbApi.getTvGenreList(language = currentTmdbLanguage())
                .genres.map { Genre(it.id, it.name) }
            if (genres.isNotEmpty()) com.example.data.util.TmdbGenreHelper.registerTvGenres(genres, currentLang)
            genres.ifEmpty { com.example.data.util.TmdbGenreHelper.getTvGenres(currentLang) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            com.example.data.util.TmdbGenreHelper.getTvGenres(currentLang)
        }
    }

    override suspend fun getAnimeGenres(): List<Genre> = withContext(Dispatchers.IO) {
        com.example.data.util.TmdbGenreHelper.getAnimeGenres()
    }

    override fun getMoviesByGenre(genreId: Int): Flow<List<Movie>> = getCachedListFlow("movies_genre_$genreId") {
        RetrofitClient.tmdbApi.discoverMovies(
            page = 1, sortBy = "popularity.desc", withGenres = genreId.toString(),
            language = currentTmdbLanguage()
        ).results
            .filter { it.genreIds.orEmpty().contains(genreId) }
            .distinctBy { it.id }
            .toDomainMovies()
    }

    override fun getSeriesByGenre(genreId: Int): Flow<List<Series>> = getCachedListFlow("series_genre_$genreId") {
        RetrofitClient.tmdbApi.discoverSeries(
            page = 1, sortBy = "popularity.desc", withGenres = genreId.toString(),
            language = currentTmdbLanguage()
        ).results
            .filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME && it.genreIds.orEmpty().contains(genreId) }
            .distinctBy { it.id }
            .toDomainSeries()
    }

    override fun getAnimeByGenre(genreId: Int): Flow<List<Series>> = getCachedListFlow("anime_genre_$genreId") {
        val genreFilter = if (genreId == 16) "16" else "16,$genreId"
        RetrofitClient.tmdbApi.discoverSeries(
            page = 1, sortBy = "popularity.desc", withGenres = genreFilter,
            originalLanguage = "ja", language = currentTmdbLanguage()
        ).results
            .filter {
                it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16) &&
                    (genreId == 16 || it.genreIds.orEmpty().contains(genreId))
            }
            .distinctBy { it.id }
            .toDomainSeries()
    }


    override suspend fun getMoviesByGenrePage(genreId: Int, page: Int): PaginatedResult<Movie> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.tmdbApi.discoverMovies(
            page = page.coerceAtLeast(1), sortBy = "popularity.desc",
            withGenres = genreId.toString(), language = currentTmdbLanguage()
        )
        val filtered = response.results
            .filter { it.genreIds.orEmpty().contains(genreId) }
            .distinctBy { it.id }
        PaginatedResult(filtered.toDomainMovies(), response.page, response.totalPages.coerceAtLeast(1), response.totalResults)
    }

    override suspend fun getSeriesByGenrePage(genreId: Int, page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.tmdbApi.discoverSeries(
            page = page.coerceAtLeast(1), sortBy = "popularity.desc",
            withGenres = genreId.toString(), language = currentTmdbLanguage()
        )
        val filtered = response.results
            .filter { ContentTypeResolver.resolveTmdbSeries(it) != ContentType.ANIME && it.genreIds.orEmpty().contains(genreId) }
            .distinctBy { it.id }
        PaginatedResult(filtered.toDomainSeries(), response.page, response.totalPages.coerceAtLeast(1), response.totalResults)
    }

    override suspend fun getAnimeByGenrePage(genreId: Int, page: Int): PaginatedResult<Series> = withContext(Dispatchers.IO) {
        val genreFilter = if (genreId == 16) "16" else "16,$genreId"
        val response = RetrofitClient.tmdbApi.discoverSeries(
            page = page.coerceAtLeast(1), sortBy = "popularity.desc", withGenres = genreFilter,
            originalLanguage = "ja", language = currentTmdbLanguage()
        )
        val filtered = response.results
            .filter {
                it.originalLanguage.equals("ja", ignoreCase = true) && it.genreIds.orEmpty().contains(16) &&
                    (genreId == 16 || it.genreIds.orEmpty().contains(genreId))
            }
            .distinctBy { it.id }
        PaginatedResult(filtered.toDomainSeries(), response.page, response.totalPages.coerceAtLeast(1), response.totalResults)
    }

    override suspend fun getTrailers(
        mediaId: String,
        isMovie: Boolean,
        language: String,
        primaryOnly: Boolean
    ): List<VideoTrailer> = withContext(Dispatchers.IO) {
        val id = mediaId.toIntOrNull() ?: return@withContext emptyList()
        val locale = if (language.startsWith("ar", ignoreCase = true)) "ar-SA" else "en-US"
        try {
            val envelope = if (isMovie) {
                RetrofitClient.tmdbApi.getMovieVideos(id, language = locale, mode = if (primaryOnly) "primary" else "all")
            } else {
                RetrofitClient.tmdbApi.getSeriesVideos(id, language = locale, mode = if (primaryOnly) "primary" else "all")
            }
            val excludedTrailerTitle = Regex(
                "\\b(featurette|behind[ -]?the[ -]?scenes|bts|interview|deleted scene|tv spot|teaser|making of|sneak peek|clip|promo|promotional)\\b",
                RegexOption.IGNORE_CASE
            )
            val videos = envelope.data?.results.orEmpty()
                .asSequence()
                .filter {
                    it.site.equals("YouTube", ignoreCase = true) &&
                        it.key.isNotBlank() &&
                        it.type.equals("Trailer", ignoreCase = true) &&
                        !excludedTrailerTitle.containsMatchIn(it.name)
                }
                .distinctBy { it.key }
                .map {
                    VideoTrailer(
                        name = it.name,
                        key = it.key,
                        type = it.type,
                        isOfficial = it.official == true,
                        site = it.site,
                        publishedAt = it.publishedAt,
                        language = it.iso6391
                    )
                }
                .toList()
            if (!primaryOnly) return@withContext videos
            val preferred = if (locale.startsWith("ar")) "ar" else "en"
            com.example.data.util.TrailerSelectionHelper.selectPrimaryTrailer(videos, preferredLanguage = preferred)
                ?.let(::listOf)
                .orEmpty()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>> = withContext(Dispatchers.IO) {
        val context = com.example.MyApplication.appContext
        try {
            val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
            val tmdbLang = if (currentLang == "ar") "ar-SA" else "en-US"
            val response = RetrofitClient.tmdbApi.searchMulti(query = query, language = tmdbLang)
            val movies = mutableListOf<Movie>()
            val series = mutableListOf<Series>()
            
            response.results.forEach { item ->
                if (item.mediaType == "movie") {
                    val yearInt = item.releaseDate?.take(4)?.toIntOrNull() ?: 0
                    val rawGenreIds = item.genreIds ?: emptyList()
                    movies.add(Movie(
                        id = item.id.toString(),
                        title = item.title ?: "Unknown",
                        originalTitle = item.originalTitle ?: item.title,
                        overview = item.overview ?: "",
                        posterUrl = item.fullPosterUrl,
                        backdropUrl = item.fullBackdropUrl,
                        year = yearInt,
                        releaseDate = item.releaseDate,
                        rating = verifiedTmdbRating(item.voteAverage, item.voteCount),
                        genres = com.example.data.util.TmdbGenreHelper.getMovieGenreNames(rawGenreIds, currentLang),
                        runtime = 120,
                        language = item.originalLanguage ?: "en",
                        country = item.originCountry?.firstOrNull(),
                        genreIds = rawGenreIds
                    ))
                } else if (item.mediaType == "tv") {
                    val yearInt = item.firstAirDate?.take(4)?.toIntOrNull() ?: 0
                    val rawGenreIds = item.genreIds ?: emptyList()
                    series.add(Series(
                        id = item.id.toString(),
                        title = item.name ?: "Unknown",
                        originalTitle = item.originalName ?: item.name,
                        overview = item.overview ?: "",
                        posterUrl = item.fullPosterUrl,
                        backdropUrl = item.fullBackdropUrl,
                        year = yearInt,
                        firstAirDate = item.firstAirDate,
                        rating = verifiedTmdbRating(item.voteAverage, item.voteCount),
                        genres = com.example.data.util.TmdbGenreHelper.getTvGenreNames(rawGenreIds, currentLang),
                        seasons = emptyList(),
                        originalLanguage = item.originalLanguage,
                        originCountry = item.originCountry ?: emptyList(),
                        genreIds = rawGenreIds
                    ))
                }
            }
            Pair(movies, series)
        } catch (e: Exception) {
            // Offline fallback: Search all locally cached media
            val offlineResults = com.example.utils.MediaListDiskCacheManager.searchCachedMedia(context, query)
            offlineResults
        }
    }
    
    // Extension functions to map from TMDB models to Domain models
    private fun com.example.data.remote.TmdbMovie.toDomain(suppressRating: Boolean = false): Movie {
        val yearInt = releaseDate?.take(4)?.toIntOrNull() ?: 0
        val rawGenreIds = genreIds ?: emptyList()
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
        return Movie(
            id = id.toString(),
            title = title ?: "Unknown",
            originalTitle = originalTitle,
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            releaseDate = releaseDate,
            rating = if (suppressRating) 0.0 else verifiedTmdbRating(voteAverage, voteCount),
            genres = com.example.data.util.TmdbGenreHelper.getMovieGenreNames(rawGenreIds, currentLang),
            runtime = 120,
            language = originalLanguage ?: "en",
            country = originCountry?.firstOrNull(),
            genreIds = rawGenreIds
        )
    }
    
    private fun com.example.data.remote.TmdbSeries.toDomain(suppressRating: Boolean = false): Series {
        val yearInt = firstAirDate?.take(4)?.toIntOrNull() ?: 0
        val rawGenreIds = genreIds ?: emptyList()
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
        return Series(
            id = id.toString(),
            title = name ?: "Unknown",
            originalTitle = originalName ?: name,
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            firstAirDate = firstAirDate,
            rating = if (suppressRating) 0.0 else verifiedTmdbRating(voteAverage, voteCount),
            genres = com.example.data.util.TmdbGenreHelper.getTvGenreNames(rawGenreIds, currentLang),
            seasons = emptyList(),
            originalLanguage = originalLanguage,
            originCountry = originCountry ?: emptyList(),
            genreIds = rawGenreIds
        )
    }

    override suspend fun getPersonDetails(personId: String): PersonDetails? = withContext(Dispatchers.IO) {
        val idInt = personId.toIntOrNull() ?: return@withContext null
        val context = com.example.MyApplication.appContext
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
        val tmdbLang = if (currentLang == "ar") "ar-SA" else "en-US"
        val partitionedCacheKey = "${personId}_$currentLang"

        // 1. Check in-memory cache
        personDetailsCache[partitionedCacheKey]?.let { return@withContext it }

        // 2. Authenticated request to Cloudflare Worker
        try {
            val response = RetrofitClient.tmdbApi.getPersonDetails(idInt, language = tmdbLang)
            val dto = response.data ?: return@withContext null

            // If combined credits are not present in person details payload, fetch separately
            val combinedCredits = dto.combinedCredits ?: try {
                RetrofitClient.tmdbApi.getPersonCombinedCredits(idInt, language = tmdbLang).data
            } catch (_: Exception) {
                null
            }

            val rawCast = combinedCredits?.cast ?: emptyList()

            // Separate movies vs series and map to domain models
            val moviesList = mutableListOf<Movie>()
            val seriesList = mutableListOf<Series>()

            for (item in rawCast) {
                val isMovie = when (item.mediaType?.lowercase()) {
                    "movie" -> true
                    "tv" -> false
                    else -> item.title != null || item.releaseDate != null
                }

                if (isMovie) {
                    val yearInt = item.releaseDate?.take(4)?.toIntOrNull() ?: 0
                    val rawGenreIds = item.genreIds ?: emptyList()
                    val title = item.title ?: item.originalTitle ?: "Unknown"
                    moviesList.add(
                        Movie(
                            id = item.id.toString(),
                            title = title,
                            originalTitle = item.originalTitle ?: title,
                            overview = item.overview ?: "",
                            posterUrl = item.fullPosterUrl,
                            backdropUrl = item.fullBackdropUrl,
                            year = yearInt,
                            releaseDate = item.releaseDate,
                            rating = verifiedTmdbRating(item.voteAverage, item.voteCount),
                            genres = com.example.data.util.TmdbGenreHelper.getMovieGenreNames(rawGenreIds, currentLang),
                            runtime = 120,
                            language = item.originalLanguage ?: "en",
                            country = item.originCountry?.firstOrNull(),
                            cast = emptyList(),
                            trailers = emptyList(),
                            genreIds = rawGenreIds
                        )
                    )
                } else {
                    val yearInt = item.firstAirDate?.take(4)?.toIntOrNull() ?: 0
                    val rawGenreIds = item.genreIds ?: emptyList()
                    val title = item.name ?: item.originalName ?: "Unknown"
                    seriesList.add(
                        Series(
                            id = item.id.toString(),
                            title = title,
                            originalTitle = item.originalName ?: title,
                            overview = item.overview ?: "",
                            posterUrl = item.fullPosterUrl,
                            backdropUrl = item.fullBackdropUrl,
                            year = yearInt,
                            firstAirDate = item.firstAirDate,
                            rating = verifiedTmdbRating(item.voteAverage, item.voteCount),
                            genres = com.example.data.util.TmdbGenreHelper.getTvGenreNames(rawGenreIds, currentLang),
                            cast = emptyList(),
                            trailers = emptyList(),
                            seasons = emptyList(),
                            creator = null,
                            status = null,
                            originalLanguage = item.originalLanguage,
                            originCountry = item.originCountry ?: emptyList(),
                            genreIds = rawGenreIds
                        )
                    )
                }
            }

            // Deterministic ordering: known years first (descending), then rating descending, then title
            val sortedMovies = moviesList
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<Movie> { if (it.year > 0) it.year else -1 }
                        .thenByDescending { it.rating }
                        .thenBy { it.title }
                )

            val sortedSeries = seriesList
                .distinctBy { it.id }
                .sortedWith(
                    compareByDescending<Series> { if (it.year > 0) it.year else -1 }
                        .thenByDescending { it.rating }
                        .thenBy { it.title }
                )

            val person = PersonDetails(
                id = dto.id.toString(),
                name = dto.name ?: dto.originalName ?: "Unknown",
                biography = dto.biography ?: "",
                profileUrl = dto.fullProfileUrl,
                birthday = dto.birthday,
                placeOfBirth = dto.placeOfBirth,
                knownFor = dto.knownForDepartment,
                movies = sortedMovies,
                series = sortedSeries,
                deathday = dto.deathday,
                gender = dto.gender,
                popularity = dto.popularity
            )

            personDetailsCache[partitionedCacheKey] = person
            person
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 404) {
                null
            } else if (e.code() == 502 && e.response()?.errorBody()?.string()?.contains("404") == true) {
                null
            } else {
                throw e
            }
        } catch (e: Exception) {
            throw e
        }
    }

    override suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<Episode> = withContext(Dispatchers.IO) {
        val languageKey = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(com.example.MyApplication.appContext)
        val cacheKey = "${seriesId}_${languageKey}_$seasonNumber"
        seasonEpisodesCache[cacheKey]?.let { return@withContext it }
        try {
            val response = RetrofitClient.tmdbApi.getSeasonDetails(
                seriesId.toInt(), seasonNumber, language = currentTmdbLanguage()
            )
            val episodes = response.data?.episodes?.map {
                Episode(
                    id = it.id.toString(),
                    episodeNumber = it.episodeNumber,
                    title = it.name ?: "Unknown",
                    overview = it.overview ?: "",
                    thumbnailUrl = it.fullStillUrl ?: "",
                    duration = it.runtime ?: 45,
                    rating = verifiedTmdbRating(it.voteAverage, null),
                    seasonNumber = seasonNumber
                )
            } ?: emptyList()
            if (episodes.isNotEmpty()) {
                seasonEpisodesCache[cacheKey] = episodes
            }
            episodes
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// File-level variant is used by the top-level DTO detail-mapping extensions below.
// The repository member helper delegates here as well, keeping rating validation consistent.
private fun verifiedTmdbRatingValue(voteAverage: Double?, voteCount: Int?): Double {
    val score = voteAverage ?: return 0.0
    if (!score.isFinite() || score !in 0.0..10.0 || (voteCount ?: 0) <= 0) return 0.0
    return score
}

internal fun com.example.data.remote.TmdbMovieDetails.toDomainDetails(): Movie {
        val yearInt = releaseDate?.take(4)?.toIntOrNull() ?: 0
        val rawGenreIds = genres?.map { it.id } ?: emptyList()
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
        val localizedGenres = if (rawGenreIds.isNotEmpty()) {
            com.example.data.util.TmdbGenreHelper.getMovieGenreNames(rawGenreIds, currentLang)
        } else {
            genres?.map { it.name } ?: emptyList()
        }
        return Movie(
            id = id.toString(),
            title = title ?: "Unknown",
            originalTitle = originalTitle,
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            releaseDate = releaseDate,
            rating = verifiedTmdbRatingValue(voteAverage, voteCount),
            genres = localizedGenres,
            runtime = runtime ?: 120,
            language = originalLanguage ?: "en",
            cast = credits?.cast?.filter { !it.name.isNullOrBlank() }?.sortedBy { it.order ?: 0 }?.distinctBy { it.id }?.take(20)?.map { CastMember(it.id.toString(), it.name ?: "", it.character ?: "", it.fullProfileUrl, order = it.order ?: 0) } ?: emptyList(),
            trailers = videos?.results?.filter { it.site.equals("YouTube", ignoreCase = true) }?.map {
                VideoTrailer(
                    name = it.name,
                    key = it.key,
                    type = it.type,
                    isOfficial = it.official ?: false,
                    site = it.site,
                    publishedAt = it.publishedAt,
                    language = it.iso6391
                )
            } ?: emptyList(),
            genreIds = rawGenreIds
        )
    }

    internal fun com.example.data.remote.TmdbSeriesDetails.toDomainDetails(): Series {
        val yearInt = firstAirDate?.take(4)?.toIntOrNull() ?: 0
        val rawGenreIds = genres?.map { it.id } ?: emptyList()
        val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
        val localizedGenres = if (rawGenreIds.isNotEmpty()) {
            com.example.data.util.TmdbGenreHelper.getTvGenreNames(rawGenreIds, currentLang)
        } else {
            genres?.map { it.name } ?: emptyList()
        }
        return Series(
            id = id.toString(),
            title = name ?: "Unknown",
            originalTitle = originalName ?: name,
            overview = overview ?: "",
            posterUrl = fullPosterUrl,
            backdropUrl = fullBackdropUrl,
            year = yearInt,
            firstAirDate = firstAirDate,
            rating = verifiedTmdbRatingValue(voteAverage, voteCount),
            genres = localizedGenres,
            cast = credits?.cast?.filter { !it.name.isNullOrBlank() }?.sortedBy { it.order ?: 0 }?.distinctBy { it.id }?.take(20)?.map { CastMember(it.id.toString(), it.name ?: "", it.character ?: "", it.fullProfileUrl, order = it.order ?: 0) } ?: emptyList(),
            trailers = videos?.results?.filter { it.site.equals("YouTube", ignoreCase = true) }?.map {
                VideoTrailer(
                    name = it.name,
                    key = it.key,
                    type = it.type,
                    isOfficial = it.official ?: false,
                    site = it.site,
                    publishedAt = it.publishedAt,
                    language = it.iso6391
                )
            } ?: emptyList(),
            seasons = seasons?.map { 
                Season(
                    id = it.id.toString(),
                    seriesId = this.id.toString(),
                    seasonNumber = it.seasonNumber,
                    title = it.name,
                    posterUrl = it.fullPosterUrl ?: fullPosterUrl,
                    episodeCount = it.episodeCount
                ) 
            } ?: emptyList(),
            creator = createdBy?.firstOrNull()?.name,
            status = status,
            originalLanguage = originalLanguage,
            originCountry = originCountry ?: emptyList(),
            genreIds = rawGenreIds
        )
    }