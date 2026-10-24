package com.example.domain.repository

import com.example.domain.models.Movie
import com.example.domain.models.PaginatedResult
import com.example.domain.models.Series
import kotlinx.coroutines.flow.Flow

interface MediaRepository {
    fun getMovies(): Flow<List<Movie>>
    fun getSeries(): Flow<List<Series>>
    fun getTrendingMovies(): Flow<List<Movie>>
    fun getTrendingSeries(): Flow<List<Series>>
    fun getTrendingAnime(): Flow<List<Series>>

    fun getArabicMovies(): Flow<List<Movie>>
    fun getArabicSeries(): Flow<List<Series>>

    fun getUpcomingMovies(): Flow<List<Movie>>
    fun getUpcomingSeries(): Flow<List<Series>>
    fun getUpcomingAnime(): Flow<List<Series>>
    fun getAnimeSeries(): Flow<List<Series>>
    fun getAnimeMovies(): Flow<List<Movie>>

    fun getNewReleasesMovies(): Flow<List<Movie>>
    fun getNewReleasesSeries(): Flow<List<Series>>
    fun getNewReleasesAnime(): Flow<List<Series>>

    // Phase 05Y-C: TMDB Pagination & Load-More support
    suspend fun getTrendingMoviesPage(page: Int): PaginatedResult<Movie> = PaginatedResult(emptyList(), page, 1)
    suspend fun getTrendingSeriesPage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)
    suspend fun getTrendingAnimePage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)

    suspend fun getPopularMoviesPage(page: Int): PaginatedResult<Movie> = PaginatedResult(emptyList(), page, 1)
    suspend fun getPopularSeriesPage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)
    suspend fun getPopularAnimePage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)

    suspend fun getUpcomingMoviesPage(page: Int): PaginatedResult<Movie> = PaginatedResult(emptyList(), page, 1)
    suspend fun getUpcomingSeriesPage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)
    suspend fun getUpcomingAnimePage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)

    suspend fun getNewReleasesMoviesPage(page: Int): PaginatedResult<Movie> = PaginatedResult(emptyList(), page, 1)
    suspend fun getNewReleasesSeriesPage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)
    suspend fun getNewReleasesAnimePage(page: Int): PaginatedResult<Series> = PaginatedResult(emptyList(), page, 1)

    suspend fun getMovieGenres(): List<com.example.domain.models.Genre> = emptyList()
    suspend fun getTvGenres(): List<com.example.domain.models.Genre> = emptyList()
    suspend fun getAnimeGenres(): List<com.example.domain.models.Genre> = emptyList()
    fun getMoviesByGenre(genreId: Int): Flow<List<Movie>> = kotlinx.coroutines.flow.flowOf(emptyList())
    fun getSeriesByGenre(genreId: Int): Flow<List<Series>> = kotlinx.coroutines.flow.flowOf(emptyList())
    fun getAnimeByGenre(genreId: Int): Flow<List<Series>> = kotlinx.coroutines.flow.flowOf(emptyList())

    // Genre-aware pagination. Each method must preserve its media-type and genre filters
    // across every page instead of filtering an unrelated catalogue after fetching it.
    suspend fun getMoviesByGenrePage(genreId: Int, page: Int): PaginatedResult<Movie> =
        PaginatedResult(emptyList(), page, 1)
    suspend fun getSeriesByGenrePage(genreId: Int, page: Int): PaginatedResult<Series> =
        PaginatedResult(emptyList(), page, 1)
    suspend fun getAnimeByGenrePage(genreId: Int, page: Int): PaginatedResult<Series> =
        PaginatedResult(emptyList(), page, 1)

    suspend fun getMovieById(id: String): Movie?
    suspend fun getSeriesById(id: String): Series?
    suspend fun searchMulti(query: String): Pair<List<Movie>, List<Series>>
    suspend fun getSeasonEpisodes(seriesId: String, seasonNumber: Int): List<com.example.domain.models.Episode>
    suspend fun getPersonDetails(personId: String): com.example.domain.models.PersonDetails?

    /** Fetch video metadata on demand. primaryOnly is used for the initial details screen;
     * the full list is requested only after the user expands secondary trailers. */
    suspend fun getTrailers(
        mediaId: String,
        isMovie: Boolean,
        language: String,
        primaryOnly: Boolean
    ): List<com.example.domain.models.VideoTrailer> = emptyList()
    fun clearCache() {}
}