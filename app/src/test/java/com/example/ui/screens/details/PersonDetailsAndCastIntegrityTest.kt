package com.example.ui.screens.details

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.remote.TmdbCast
import com.example.data.remote.TmdbCredits
import com.example.data.remote.TmdbDataEnvelope
import com.example.data.remote.TmdbMovieDetails
import com.example.data.remote.TmdbMulti
import com.example.data.remote.TmdbPersonCombinedCredits
import com.example.data.remote.TmdbPersonDetails
import com.example.data.remote.TmdbSeriesDetails
import com.example.data.repository.TmdbMediaRepositoryImpl
import com.example.data.repository.toDomainDetails
import com.example.domain.models.CastMember
import com.example.domain.models.Movie
import com.example.domain.models.PersonDetails
import com.example.domain.models.Series
import com.example.utils.MediaDetailsCacheManager
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PersonDetailsAndCastIntegrityTest {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPersonDetailsEnvelopeDeserialization_fullContract() {
        val json = """
            {
              "ok": true,
              "service": "tmdb",
              "media_type": "person",
              "resource": "details",
              "language": "en-US",
              "data": {
                "id": 18918,
                "name": "Dwayne Johnson",
                "original_name": "Dwayne Douglas Johnson",
                "biography": "Dwayne Douglas Johnson, also known as The Rock...",
                "birthday": "1972-05-02",
                "deathday": null,
                "gender": 2,
                "place_of_birth": "Hayward, California, USA",
                "known_for_department": "Acting",
                "popularity": 85.4,
                "profile_path": "/cgoy7t5ge0.jpg",
                "combined_credits": {
                  "id": 18918,
                  "cast": [
                    {
                      "id": 277834,
                      "media_type": "movie",
                      "title": "Moana",
                      "original_title": "Moana",
                      "overview": "In Ancient Polynesia...",
                      "poster_path": "/z4.jpg",
                      "release_date": "2016-11-23",
                      "vote_average": 7.6,
                      "character": "Maui (voice)"
                    },
                    {
                      "id": 63333,
                      "media_type": "tv",
                      "name": "Ballers",
                      "original_name": "Ballers",
                      "overview": "A series centered around a group of football players...",
                      "poster_path": "/b8.jpg",
                      "first_air_date": "2015-06-21",
                      "vote_average": 7.1,
                      "character": "Spencer Strasmore"
                    }
                  ],
                  "crew": []
                }
              }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbDataEnvelope::class.java, TmdbPersonDetails::class.java)
        val adapter = moshi.adapter<TmdbDataEnvelope<TmdbPersonDetails>>(type)
        val envelope = adapter.fromJson(json)

        assertNotNull(envelope)
        assertEquals(true, envelope?.ok)
        assertEquals("tmdb", envelope?.service)
        assertEquals("person", envelope?.mediaType)
        assertEquals("details", envelope?.resource)
        assertEquals("en-US", envelope?.language)

        val data = envelope?.data
        assertNotNull(data)
        assertEquals(18918, data?.id)
        assertEquals("Dwayne Johnson", data?.name)
        assertEquals("1972-05-02", data?.birthday)
        assertNull(data?.deathday)
        assertEquals(2, data?.gender)
        assertEquals("Hayward, California, USA", data?.placeOfBirth)
        assertEquals("Acting", data?.knownForDepartment)
        assertEquals("https://image.tmdb.org/t/p/w500/cgoy7t5ge0.jpg", data?.fullProfileUrl)

        val credits = data?.combinedCredits
        assertNotNull(credits)
        assertEquals(2, credits?.cast?.size)

        val movieItem = credits?.cast?.first { it.mediaType == "movie" }
        assertEquals("Moana", movieItem?.title)
        assertEquals("Maui (voice)", movieItem?.character)

        val tvItem = credits?.cast?.first { it.mediaType == "tv" }
        assertEquals("Ballers", tvItem?.name)
        assertEquals("Spencer Strasmore", tvItem?.character)
    }

    @Test
    fun testPersonCombinedCreditsEnvelopeDeserialization() {
        val json = """
            {
              "ok": true,
              "service": "tmdb",
              "media_type": "person",
              "resource": "combined_credits",
              "language": "ar-SA",
              "data": {
                "id": 18918,
                "cast": [
                  {
                    "id": 10159,
                    "media_type": "movie",
                    "title": "The Rundown",
                    "poster_path": "/rundown.jpg",
                    "release_date": "2003-09-26",
                    "vote_average": 6.4
                  }
                ],
                "crew": []
              }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbDataEnvelope::class.java, TmdbPersonCombinedCredits::class.java)
        val adapter = moshi.adapter<TmdbDataEnvelope<TmdbPersonCombinedCredits>>(type)
        val envelope = adapter.fromJson(json)

        assertNotNull(envelope)
        assertEquals(true, envelope?.ok)
        assertEquals("combined_credits", envelope?.resource)
        assertEquals(1, envelope?.data?.cast?.size)
        assertEquals(10159, envelope?.data?.cast?.first()?.id)
    }

    @Test
    fun testCastBillingOrderPreservationInMovieDetails() {
        val details = TmdbMovieDetails(
            id = 100,
            title = "Test Cast Film",
            originalTitle = "Test Cast Film",
            overview = "Overview",
            posterPath = null,
            backdropPath = null,
            releaseDate = "2024-01-01",
            voteAverage = 7.5,
            runtime = 100,
            originalLanguage = "en",
            genres = emptyList(),
            credits = TmdbCredits(
                cast = listOf(
                    TmdbCast(id = 301, name = "Third Billed", character = "Role C", profilePath = null, order = 2),
                    TmdbCast(id = 101, name = "Lead Star", character = "Role A", profilePath = "/lead.jpg", order = 0),
                    TmdbCast(id = 201, name = "Second Billed", character = "Role B", profilePath = null, order = 1),
                    TmdbCast(id = 401, name = "", character = "Unnamed", profilePath = null, order = 3) // Blank name should be filtered
                ),
                crew = emptyList()
            ),
            videos = null
        )

        val domainMovie = details.toDomainDetails()

        assertEquals(3, domainMovie.cast.size)
        assertEquals("Lead Star", domainMovie.cast[0].name)
        assertEquals(0, domainMovie.cast[0].order)
        assertEquals("Second Billed", domainMovie.cast[1].name)
        assertEquals(1, domainMovie.cast[1].order)
        assertEquals("Third Billed", domainMovie.cast[2].name)
        assertEquals(2, domainMovie.cast[2].order)
    }

    @Test
    fun testCastBillingOrderPreservationInSeriesDetails() {
        val details = TmdbSeriesDetails(
            id = 200,
            name = "Test Cast Series",
            overview = "Overview",
            posterPath = null,
            backdropPath = null,
            firstAirDate = "2023-01-01",
            voteAverage = 8.0,
            genres = emptyList(),
            credits = TmdbCredits(
                cast = listOf(
                    TmdbCast(id = 502, name = "Supporting", character = "Role 2", profilePath = null, order = 1),
                    TmdbCast(id = 501, name = "Lead Actor", character = "Role 1", profilePath = null, order = 0)
                ),
                crew = emptyList()
            ),
            videos = null,
            seasons = emptyList(),
            createdBy = emptyList(),
            status = "Ended"
        )

        val domainSeries = details.toDomainDetails()

        assertEquals(2, domainSeries.cast.size)
        assertEquals("Lead Actor", domainSeries.cast[0].name)
        assertEquals(0, domainSeries.cast[0].order)
        assertEquals("Supporting", domainSeries.cast[1].name)
        assertEquals(1, domainSeries.cast[1].order)
    }

    @Test
    fun testMediaDetailsCacheManager_movieCastOrderRoundTrip() {
        val movie = Movie(
            id = "test_movie_cache_order",
            title = "Cached Movie",
            overview = "Overview",
            posterUrl = "http://poster.jpg",
            backdropUrl = "http://backdrop.jpg",
            year = 2024,
            rating = 8.0,
            genres = listOf("Action"),
            runtime = 120,
            cast = listOf(
                CastMember(id = "c3", name = "Actor Three", character = "Role 3", profileUrl = null, order = 2),
                CastMember(id = "c1", name = "Actor One", character = "Role 1", profileUrl = null, order = 0),
                CastMember(id = "c2", name = "Actor Two", character = "Role 2", profileUrl = null, order = 1)
            )
        )

        MediaDetailsCacheManager.saveMovieDetails(context, movie)
        val loaded = MediaDetailsCacheManager.getMovieDetails(context, "test_movie_cache_order")

        assertNotNull(loaded)
        assertEquals(3, loaded?.cast?.size)
        // Verify deterministic ascending order restoration from disk cache
        assertEquals("c1", loaded?.cast?.get(0)?.id)
        assertEquals(0, loaded?.cast?.get(0)?.order)
        assertEquals("c2", loaded?.cast?.get(1)?.id)
        assertEquals(1, loaded?.cast?.get(1)?.order)
        assertEquals("c3", loaded?.cast?.get(2)?.id)
        assertEquals(2, loaded?.cast?.get(2)?.order)
    }

    @Test
    fun testMediaDetailsCacheManager_seriesCastOrderRoundTrip() {
        val series = Series(
            id = "test_series_cache_order",
            title = "Cached Series",
            overview = "Overview",
            posterUrl = "http://poster.jpg",
            backdropUrl = "http://backdrop.jpg",
            year = 2024,
            rating = 8.5,
            genres = listOf("Drama"),
            cast = listOf(
                CastMember(id = "s2", name = "Star Two", character = "Role B", profileUrl = null, order = 1),
                CastMember(id = "s1", name = "Star One", character = "Role A", profileUrl = null, order = 0)
            )
        )

        MediaDetailsCacheManager.saveSeriesDetails(context, series)
        val loaded = MediaDetailsCacheManager.getSeriesDetails(context, "test_series_cache_order")

        assertNotNull(loaded)
        assertEquals(2, loaded?.cast?.size)
        assertEquals("s1", loaded?.cast?.get(0)?.id)
        assertEquals(0, loaded?.cast?.get(0)?.order)
        assertEquals("s2", loaded?.cast?.get(1)?.id)
        assertEquals(1, loaded?.cast?.get(1)?.order)
    }

    @Test
    fun testMediaDetailsCacheManager_legacyCacheWithoutOrderField() {
        // Manually write JSON cache without "order" attribute to simulate legacy cache
        val legacyJson = JSONObject().apply {
            put("id", "legacy_movie")
            put("title", "Legacy Film")
            put("overview", "Legacy Overview")
            put("posterUrl", "")
            put("backdropUrl", "")
            put("year", 2022)
            put("rating", 7.0)
            put("genres", JSONArray())
            put("genreIds", JSONArray())
            put("runtime", 90)
            put("language", "en")
            put("cast", JSONArray().apply {
                put(JSONObject().apply {
                    put("id", "leg1")
                    put("name", "First Legacy Actor")
                    put("character", "Role 1")
                    // Note: NO "order" field
                })
                put(JSONObject().apply {
                    put("id", "leg2")
                    put("name", "Second Legacy Actor")
                    put("character", "Role 2")
                    // Note: NO "order" field
                })
            })
            put("trailers", JSONArray())
        }

        MediaDetailsCacheManager.saveDetailsFromJson(context, "legacy_movie", true, legacyJson.toString())
        val loaded = MediaDetailsCacheManager.getMovieDetails(context, "legacy_movie")

        assertNotNull(loaded)
        assertEquals(2, loaded?.cast?.size)
        // Verify index-based fallback preserves file order
        assertEquals("leg1", loaded?.cast?.get(0)?.id)
        assertEquals(0, loaded?.cast?.get(0)?.order)
        assertEquals("leg2", loaded?.cast?.get(1)?.id)
        assertEquals(1, loaded?.cast?.get(1)?.order)
    }

    @Test
    fun testMissingOptionalFieldsInPersonDetails() {
        val dto = TmdbPersonDetails(
            id = 99999,
            name = "Indie Actor",
            originalName = null,
            alsoKnownAs = emptyList(),
            biography = null,
            profilePath = null,
            birthday = null,
            deathday = null,
            gender = null,
            placeOfBirth = null,
            knownForDepartment = null,
            popularity = null,
            combinedCredits = null
        )

        assertNull(dto.fullProfileUrl)

        val person = PersonDetails(
            id = dto.id.toString(),
            name = dto.name ?: "Unknown",
            biography = dto.biography ?: "",
            profileUrl = dto.fullProfileUrl,
            birthday = dto.birthday,
            placeOfBirth = dto.placeOfBirth,
            knownFor = dto.knownForDepartment,
            movies = emptyList(),
            series = emptyList()
        )

        assertEquals("99999", person.id)
        assertEquals("Indie Actor", person.name)
        assertEquals("", person.biography)
        assertNull(person.profileUrl)
        assertNull(person.birthday)
        assertTrue(person.movies.isEmpty())
        assertTrue(person.series.isEmpty())
    }

    @Test
    fun testInvalidPersonIdHandling() = runBlocking {
        val repo = TmdbMediaRepositoryImpl()
        // Non-numeric person ID should immediately return null safely without throwing
        val result = repo.getPersonDetails("invalid_non_numeric_id")
        assertNull(result)

        val providerResult = repo.getPersonDetails("provider|unknown_actor")
        assertNull(providerResult)
    }
}
