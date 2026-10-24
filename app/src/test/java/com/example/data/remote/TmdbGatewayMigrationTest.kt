package com.example.data.remote

import com.example.data.model.HistoryItem
import com.example.data.repository.ContinueWatchingMetadataManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TmdbGatewayMigrationTest {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Before
    fun setup() {
        ContinueWatchingMetadataManager.clearCacheForTesting()
    }

    @Test
    fun testMovieDetailsEnvelopeDeserialization() {
        val json = """
            {
              "ok": true,
              "service": "tmdb",
              "media_type": "movie",
              "data": {
                "id": 27205,
                "title": "Inception",
                "original_title": "Inception",
                "overview": "A skilled thief...",
                "poster_path": "/xlaY2zyzMfkhk0HSC5VUwzoZPU1.jpg",
                "backdrop_path": "/8ZTVqvKDQ8emSGUEMjsS4yHAwrp.jpg",
                "release_date": "2010-07-15",
                "vote_average": 8.4,
                "runtime": 148,
                "original_language": "en"
              }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbDataEnvelope::class.java, TmdbMovieDetails::class.java)
        val adapter = moshi.adapter<TmdbDataEnvelope<TmdbMovieDetails>>(type)
        val envelope = adapter.fromJson(json)

        assertNotNull(envelope)
        assertEquals(true, envelope?.ok)
        assertEquals("tmdb", envelope?.service)
        assertEquals("movie", envelope?.mediaType)

        val movie = envelope?.data
        assertNotNull(movie)
        assertEquals(27205, movie?.id)
        assertEquals("Inception", movie?.title)
        assertEquals(148, movie?.runtime)
        assertEquals("https://image.tmdb.org/t/p/w500/xlaY2zyzMfkhk0HSC5VUwzoZPU1.jpg", movie?.fullPosterUrl)
    }

    @Test
    fun testSeriesDetailsEnvelopeDeserialization() {
        val json = """
            {
              "ok": true,
              "service": "tmdb",
              "media_type": "tv",
              "data": {
                "id": 1399,
                "name": "Game of Thrones",
                "original_name": "Game of Thrones",
                "overview": "Seven noble families...",
                "poster_path": "/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg",
                "backdrop_path": "/suopoADq0k8YZr4dQXcU6p09Ytx.jpg",
                "first_air_date": "2011-04-17",
                "vote_average": 8.4,
                "seasons": [
                  {
                    "id": 3624,
                    "name": "Season 1",
                    "season_number": 1,
                    "episode_count": 10,
                    "poster_path": "/zwAJgo97wwvMr8vg62nG80umMGQ.jpg"
                  }
                ]
              }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbDataEnvelope::class.java, TmdbSeriesDetails::class.java)
        val adapter = moshi.adapter<TmdbDataEnvelope<TmdbSeriesDetails>>(type)
        val envelope = adapter.fromJson(json)

        assertNotNull(envelope)
        assertEquals(true, envelope?.ok)
        val series = envelope?.data
        assertNotNull(series)
        assertEquals(1399, series?.id)
        assertEquals("Game of Thrones", series?.name)
        assertEquals(1, series?.seasons?.size)
        assertEquals(10, series?.seasons?.first()?.episodeCount)
    }

    @Test
    fun testSeasonDetailsEnvelopeDeserialization() {
        val json = """
            {
              "ok": true,
              "service": "tmdb",
              "media_type": "tv",
              "data": {
                "id": 3624,
                "season_number": 1,
                "episodes": [
                  {
                    "id": 63056,
                    "name": "Winter Is Coming",
                    "overview": "Jon Arryn dies...",
                    "episode_number": 1,
                    "still_path": "/9Gvh2x9rD3K0zI8t3jW4xL9xM8p.jpg",
                    "vote_average": 8.0,
                    "runtime": 62
                  }
                ]
              }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbDataEnvelope::class.java, TmdbSeasonDetails::class.java)
        val adapter = moshi.adapter<TmdbDataEnvelope<TmdbSeasonDetails>>(type)
        val envelope = adapter.fromJson(json)

        assertNotNull(envelope)
        assertEquals(1, envelope?.data?.seasonNumber)
        assertEquals(1, envelope?.data?.episodes?.size)
        assertEquals("Winter Is Coming", envelope?.data?.episodes?.first()?.name)
        assertEquals(62, envelope?.data?.episodes?.first()?.runtime)
    }

    @Test
    fun testTrendingListResponseDeserialization() {
        val json = """
            {
              "ok": true,
              "type": "movie",
              "window": "day",
              "page": 1,
              "total_pages": 500,
              "total_results": 10000,
              "results": [
                {
                  "id": 101,
                  "title": "Trending Movie",
                  "overview": "Overview here",
                  "poster_path": "/poster.jpg",
                  "backdrop_path": "/backdrop.jpg",
                  "release_date": "2026-01-01",
                  "vote_average": 7.5
                }
              ]
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbResponse::class.java, TmdbMovie::class.java)
        val adapter = moshi.adapter<TmdbResponse<TmdbMovie>>(type)
        val response = adapter.fromJson(json)

        assertNotNull(response)
        assertEquals(true, response?.ok)
        assertEquals(1, response?.page)
        assertEquals(1, response?.results?.size)
        assertEquals("Trending Movie", response?.results?.first()?.title)
    }

    @Test
    fun testContinueWatchingMetadataManagerWithMockService() = runBlocking {
        val mockApi = object : TmdbApiService {
            override suspend fun getTrendingMovies(type: String, window: String, page: Int, language: String?): TmdbResponse<TmdbMovie> =
                TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)

            override suspend fun getTrendingSeries(type: String, window: String, page: Int, language: String?): TmdbResponse<TmdbSeries> =
                TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)

            override suspend fun getPopularMovies(page: Int, language: String?, region: String?): TmdbResponse<TmdbMovie> =
                TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)

            override suspend fun getPopularSeries(page: Int, language: String?): TmdbResponse<TmdbSeries> =
                TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)

            override suspend fun getUpcomingMovies(page: Int, language: String?, region: String?): TmdbResponse<TmdbMovie> =
                TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)

            override suspend fun discoverMovies(
                page: Int, sortBy: String?, withGenres: String?, originalLanguage: String?,
                releaseDateGte: String?, releaseDateLte: String?, language: String?, region: String?
            ): TmdbResponse<TmdbMovie> = TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)

            override suspend fun discoverSeries(
                page: Int, sortBy: String?, withGenres: String?, originalLanguage: String?,
                firstAirDateGte: String?, firstAirDateLte: String?, language: String?
            ): TmdbResponse<TmdbSeries> = TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)

            override suspend fun getMovieGenreList(language: String?): TmdbGenreResponse = TmdbGenreResponse(emptyList())
            override suspend fun getTvGenreList(language: String?): TmdbGenreResponse = TmdbGenreResponse(emptyList())

            override suspend fun getMovieDetails(movieId: Int, language: String?, appendToResponse: String): TmdbDataEnvelope<TmdbMovieDetails> {
                return TmdbDataEnvelope(
                    ok = true,
                    data = TmdbMovieDetails(
                        id = movieId,
                        title = "Mock Movie $movieId",
                        originalTitle = null,
                        overview = "Mock overview",
                        posterPath = "/poster.jpg",
                        backdropPath = "/backdrop.jpg",
                        releaseDate = "2025-06-01",
                        voteAverage = 9.0,
                        runtime = 135,
                        originalLanguage = "en",
                        genres = emptyList(),
                        credits = null,
                        videos = null
                    )
                )
            }

            override suspend fun getMovieVideos(movieId: Int, language: String?, mode: String): TmdbDataEnvelope<TmdbVideos> =
                TmdbDataEnvelope(ok = true, data = TmdbVideos(emptyList()))

            override suspend fun getSeriesDetails(seriesId: Int, language: String?, appendToResponse: String): TmdbDataEnvelope<TmdbSeriesDetails> {
                return TmdbDataEnvelope(
                    ok = true,
                    data = TmdbSeriesDetails(
                        id = seriesId,
                        name = "Mock Series $seriesId",
                        originalName = null,
                        overview = "Series overview",
                        posterPath = "/series.jpg",
                        backdropPath = "/series_back.jpg",
                        firstAirDate = "2024-03-15",
                        voteAverage = 8.8,
                        genres = emptyList(),
                        credits = null,
                        videos = null,
                        seasons = emptyList(),
                        createdBy = null,
                        status = "Returning Series"
                    )
                )
            }

            override suspend fun getSeriesVideos(seriesId: Int, language: String?, mode: String): TmdbDataEnvelope<TmdbVideos> =
                TmdbDataEnvelope(ok = true, data = TmdbVideos(emptyList()))

            override suspend fun getSeasonDetails(seriesId: Int, seasonNumber: Int, language: String?): TmdbDataEnvelope<TmdbSeasonDetails> =
                TmdbDataEnvelope(ok = true, data = TmdbSeasonDetails(id = 1, seasonNumber = seasonNumber, episodes = emptyList()))

            override suspend fun getPersonDetails(personId: Int, language: String?, appendToResponse: String): TmdbDataEnvelope<TmdbPersonDetails> =
                TmdbDataEnvelope(ok = true, data = TmdbPersonDetails(id = personId, name = "Mock Person $personId"))

            override suspend fun getPersonCombinedCredits(personId: Int, language: String?): TmdbDataEnvelope<TmdbPersonCombinedCredits> =
                TmdbDataEnvelope(ok = true, data = TmdbPersonCombinedCredits(id = personId, cast = emptyList()))

            override suspend fun searchMulti(query: String, page: Int, type: String?, language: String?): TmdbResponse<TmdbMulti> =
                TmdbResponse(ok = true, page = page, results = emptyList(), totalPages = 1, totalResults = 0)
        }

        ContinueWatchingMetadataManager.apiService = mockApi

        val movieItem = HistoryItem(
            id = "550",
            title = "Fallback Title",
            posterUrl = "https://fallback.jpg",
            isMovie = true
        )

        val detail = ContinueWatchingMetadataManager.getOrFetchMetadata(movieItem)
        assertEquals("Mock Movie 550", detail.title)
        assertEquals("2025", detail.year)
        assertEquals("9.0", detail.rating)
        assertEquals(135, detail.runtimeMinutes)
        assertTrue(detail.isMovie)
    }
}
