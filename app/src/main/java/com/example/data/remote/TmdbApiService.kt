package com.example.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * All endpoints are relative to the authenticated Cloudflare Worker gateway.
 * These paths must match the route table published by the Worker.
 */
interface TmdbApiService {
    // TMDB Trending (distinct from Popular / New Releases / Upcoming)
    @GET("trending")
    suspend fun getTrendingMovies(
        @Query("type") type: String = "movie",
        @Query("window") window: String = "day",
        @Query("page") page: Int = 1,
        @Query("language") language: String? = null
    ): TmdbResponse<TmdbMovie>

    @GET("trending")
    suspend fun getTrendingSeries(
        @Query("type") type: String = "tv",
        @Query("window") window: String = "day",
        @Query("page") page: Int = 1,
        @Query("language") language: String? = null
    ): TmdbResponse<TmdbSeries>

    // Popular has its own TMDB catalog endpoints. Do not alias these to Trending.
    @GET("movie/popular")
    suspend fun getPopularMovies(
        @Query("page") page: Int = 1,
        @Query("language") language: String? = null,
        @Query("region") region: String? = null
    ): TmdbResponse<TmdbMovie>

    @GET("tv/popular")
    suspend fun getPopularSeries(
        @Query("page") page: Int = 1,
        @Query("language") language: String? = null
    ): TmdbResponse<TmdbSeries>

    @GET("movie/upcoming")
    suspend fun getUpcomingMovies(
        @Query("page") page: Int = 1,
        @Query("language") language: String? = null,
        @Query("region") region: String? = null
    ): TmdbResponse<TmdbMovie>

    // Discover endpoints allow server-side filtering. Results must never fall back
    // to an unrelated Trending page when a category/filter has zero matches.
    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("with_original_language") originalLanguage: String? = null,
        @Query("primary_release_date.gte") releaseDateGte: String? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("language") language: String? = null,
        @Query("region") region: String? = null
    ): TmdbResponse<TmdbMovie>

    @GET("discover/tv")
    suspend fun discoverSeries(
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = "popularity.desc",
        @Query("with_genres") withGenres: String? = null,
        @Query("with_original_language") originalLanguage: String? = null,
        @Query("first_air_date.gte") firstAirDateGte: String? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("language") language: String? = null
    ): TmdbResponse<TmdbSeries>

    @GET("genre/movie/list")
    suspend fun getMovieGenreList(
        @Query("language") language: String? = null
    ): TmdbGenreResponse

    @GET("genre/tv/list")
    suspend fun getTvGenreList(
        @Query("language") language: String? = null
    ): TmdbGenreResponse

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("language") language: String? = null,
        @Query("append_to_response") appendToResponse: String = "credits,translations"
    ): TmdbDataEnvelope<TmdbMovieDetails>

    @GET("movie/{movie_id}/videos")
    suspend fun getMovieVideos(
        @Path("movie_id") movieId: Int,
        @Query("language") language: String? = null,
        @Query("mode") mode: String = "primary"
    ): TmdbDataEnvelope<TmdbVideos>

    @GET("tv/{tv_id}")
    suspend fun getSeriesDetails(
        @Path("tv_id") seriesId: Int,
        @Query("language") language: String? = null,
        @Query("append_to_response") appendToResponse: String = "credits,translations"
    ): TmdbDataEnvelope<TmdbSeriesDetails>

    @GET("tv/{tv_id}/videos")
    suspend fun getSeriesVideos(
        @Path("tv_id") seriesId: Int,
        @Query("language") language: String? = null,
        @Query("mode") mode: String = "primary"
    ): TmdbDataEnvelope<TmdbVideos>

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getSeasonDetails(
        @Path("tv_id") seriesId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("language") language: String? = null
    ): TmdbDataEnvelope<TmdbSeasonDetails>

    // Person
    @GET("person/{person_id}")
    suspend fun getPersonDetails(
        @Path("person_id") personId: Int,
        @Query("language") language: String? = null,
        @Query("append_to_response") appendToResponse: String = "combined_credits,images"
    ): TmdbDataEnvelope<TmdbPersonDetails>

    @GET("person/{person_id}/combined_credits")
    suspend fun getPersonCombinedCredits(
        @Path("person_id") personId: Int,
        @Query("language") language: String? = null
    ): TmdbDataEnvelope<TmdbPersonCombinedCredits>

    // Search
    @GET("search")
    suspend fun searchMulti(
        @Query("query") query: String,
        @Query("page") page: Int = 1,
        @Query("type") type: String? = null,
        @Query("language") language: String? = null
    ): TmdbResponse<TmdbMulti>
}
