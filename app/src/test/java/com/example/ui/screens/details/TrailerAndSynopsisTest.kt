package com.example.ui.screens.details

import com.example.data.remote.TmdbCast
import com.example.data.remote.TmdbCredits
import com.example.data.remote.TmdbDataEnvelope
import com.example.data.remote.TmdbMovieDetails
import com.example.data.remote.TmdbSeriesDetails
import com.example.data.remote.TmdbVideo
import com.example.data.remote.TmdbVideos
import com.example.data.repository.toDomainDetails
import com.example.data.util.TrailerSelectionHelper
import com.example.domain.models.CastMember
import com.example.domain.models.VideoTrailer
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrailerAndSynopsisTest {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Test
    fun testTrailerSelection_prefersOfficialTrailer() {
        val trailers = listOf(
            VideoTrailer(name = "Fan Made Trailer", key = "fan123", type = "Trailer", isOfficial = false, site = "YouTube"),
            VideoTrailer(name = "Official Trailer", key = "off123", type = "Trailer", isOfficial = true, site = "YouTube")
        )

        val primary = TrailerSelectionHelper.selectPrimaryTrailer(trailers)
        assertNotNull(primary)
        assertEquals("off123", primary?.key)
        assertTrue(primary?.isOfficial == true)
    }

    @Test
    fun testTrailerSelection_prefersYouTubeSite() {
        val trailers = listOf(
            VideoTrailer(name = "Vimeo Trailer", key = "vim123", type = "Trailer", isOfficial = true, site = "Vimeo"),
            VideoTrailer(name = "YouTube Trailer", key = "yt123", type = "Trailer", isOfficial = true, site = "YouTube")
        )

        val primary = TrailerSelectionHelper.selectPrimaryTrailer(trailers)
        assertNotNull(primary)
        assertEquals("yt123", primary?.key)
    }

    @Test
    fun testTrailerSelection_prefersTrailerTypeOverTeaserOrClip() {
        val trailers = listOf(
            VideoTrailer(name = "Behind the Scenes", key = "bts123", type = "Behind the Scenes", isOfficial = true, site = "YouTube"),
            VideoTrailer(name = "Official Trailer", key = "trl123", type = "Trailer", isOfficial = true, site = "YouTube"),
            VideoTrailer(name = "Teaser", key = "tsr123", type = "Teaser", isOfficial = true, site = "YouTube")
        )

        val primary = TrailerSelectionHelper.selectPrimaryTrailer(trailers)
        assertNotNull(primary)
        assertEquals("trl123", primary?.key)
    }

    @Test
    fun testTrailerSelection_prefersLanguageMatch() {
        val trailers = listOf(
            VideoTrailer(name = "English Trailer", key = "en123", type = "Trailer", isOfficial = true, site = "YouTube", language = "en"),
            VideoTrailer(name = "Arabic Trailer", key = "ar123", type = "Trailer", isOfficial = true, site = "YouTube", language = "ar")
        )

        val primaryAr = TrailerSelectionHelper.selectPrimaryTrailer(trailers, preferredLanguage = "ar")
        assertNotNull(primaryAr)
        assertEquals("ar123", primaryAr?.key)

        val primaryEn = TrailerSelectionHelper.selectPrimaryTrailer(trailers, preferredLanguage = "en")
        assertNotNull(primaryEn)
        assertEquals("en123", primaryEn?.key)
    }

    @Test
    fun testTrailerSelection_breaksTieByPublishDateRecency() {
        val trailers = listOf(
            VideoTrailer(name = "Old Official Trailer", key = "old123", type = "Trailer", isOfficial = true, site = "YouTube", publishedAt = "2023-01-01T00:00:00.000Z"),
            VideoTrailer(name = "New Official Trailer", key = "new123", type = "Trailer", isOfficial = true, site = "YouTube", publishedAt = "2024-05-15T12:00:00.000Z")
        )

        val primary = TrailerSelectionHelper.selectPrimaryTrailer(trailers)
        assertNotNull(primary)
        assertEquals("new123", primary?.key)
    }

    @Test
    fun testTrailerSelection_filtersInvalidAndEmptyKeys() {
        val trailers = listOf(
            VideoTrailer(name = "Blank Key", key = "   ", type = "Trailer", isOfficial = true, site = "YouTube"),
            VideoTrailer(name = "Valid Key", key = "valid_key", type = "Trailer", isOfficial = true, site = "YouTube")
        )

        val primary = TrailerSelectionHelper.selectPrimaryTrailer(trailers)
        assertNotNull(primary)
        assertEquals("valid_key", primary?.key)
    }

    @Test
    fun testTrailerSelection_emptyListReturnsNull() {
        val primary = TrailerSelectionHelper.selectPrimaryTrailer(emptyList())
        assertNull(primary)
    }

    @Test
    fun testMovieDetailsWithCreditsAndVideosDeserialization() {
        val json = """
            {
              "ok": true,
              "service": "tmdb",
              "media_type": "movie",
              "data": {
                "id": 277834,
                "title": "Moana",
                "original_title": "Moana",
                "overview": "An adventurous teenager sails out on a daring mission...",
                "poster_path": "/45k88g5g5iJj7G9x.jpg",
                "backdrop_path": "/moana_backdrop.jpg",
                "release_date": "2016-11-23",
                "vote_average": 7.6,
                "runtime": 107,
                "credits": {
                  "cast": [
                    {
                      "id": 1564846,
                      "name": "Auli'i Cravalho",
                      "character": "Moana (voice)",
                      "profile_path": "/profile1.jpg",
                      "order": 0
                    },
                    {
                      "id": 18918,
                      "name": "Dwayne Johnson",
                      "character": "Maui (voice)",
                      "profile_path": "/profile2.jpg",
                      "order": 1
                    }
                  ],
                  "crew": []
                },
                "videos": {
                  "results": [
                    {
                      "id": "vid1",
                      "name": "Official US Trailer",
                      "key": "LKFuXETZUsI",
                      "site": "YouTube",
                      "type": "Trailer",
                      "official": true,
                      "published_at": "2016-09-15T16:00:00.000Z",
                      "iso_639_1": "en",
                      "iso_3166_1": "US"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbDataEnvelope::class.java, TmdbMovieDetails::class.java)
        val adapter = moshi.adapter<TmdbDataEnvelope<TmdbMovieDetails>>(type)
        val envelope = adapter.fromJson(json)

        assertNotNull(envelope)
        val movie = envelope?.data
        assertNotNull(movie)
        assertEquals(277834, movie?.id)
        assertEquals("Moana", movie?.title)

        // Verify cast deserialization
        val cast = movie?.credits?.cast
        assertNotNull(cast)
        assertEquals(2, cast?.size)
        assertEquals("Auli'i Cravalho", cast?.get(0)?.name)
        assertEquals(0, cast?.get(0)?.order)
        assertEquals("https://image.tmdb.org/t/p/w185/profile1.jpg", cast?.get(0)?.fullProfileUrl)

        // Verify video deserialization
        val videos = movie?.videos?.results
        assertNotNull(videos)
        assertEquals(1, videos?.size)
        val vid = videos?.get(0)
        assertEquals("LKFuXETZUsI", vid?.key)
        assertEquals(true, vid?.official)
        assertEquals("2016-09-15T16:00:00.000Z", vid?.publishedAt)

        // Verify domain mapping
        val domainMovie = movie!!.toDomainDetails()
        assertEquals(2, domainMovie.cast.size)
        assertEquals("Auli'i Cravalho", domainMovie.cast[0].name)
        assertEquals(0, domainMovie.cast[0].order)
        assertEquals(1, domainMovie.trailers.size)
        assertEquals("LKFuXETZUsI", domainMovie.trailers[0].key)
        assertTrue(domainMovie.trailers[0].isOfficial)
        assertEquals("YouTube", domainMovie.trailers[0].site)
    }

    @Test
    fun testCastMemberFallbackGracefullyHandlesMissingProfile() {
        val tmdbCast = TmdbCast(
            id = 999,
            name = "Unknown Actor",
            character = "Extra",
            profilePath = null,
            order = 5
        )

        assertNull(tmdbCast.fullProfileUrl)

        val castMember = CastMember(
            id = tmdbCast.id.toString(),
            name = tmdbCast.name ?: "",
            character = tmdbCast.character ?: "",
            profileUrl = tmdbCast.fullProfileUrl,
            order = tmdbCast.order ?: 0
        )

        assertEquals("999", castMember.id)
        assertEquals("Unknown Actor", castMember.name)
        assertNull(castMember.profileUrl)
        assertEquals(5, castMember.order)
    }

    @Test
    fun testSeriesDetailsWithCreditsAndVideosDeserialization() {
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
                "credits": {
                  "cast": [
                    {
                      "id": 1223786,
                      "name": "Emilia Clarke",
                      "character": "Daenerys Targaryen",
                      "profile_path": "/emilia.jpg",
                      "order": 0
                    }
                  ],
                  "crew": []
                },
                "videos": {
                  "results": [
                    {
                      "id": "got_vid",
                      "name": "Season 1 Official Trailer",
                      "key": "KPLWWIOCOOQ",
                      "site": "YouTube",
                      "type": "Trailer",
                      "official": true,
                      "published_at": "2011-03-03T18:00:00.000Z"
                    }
                  ]
                }
              }
            }
        """.trimIndent()

        val type = Types.newParameterizedType(TmdbDataEnvelope::class.java, TmdbSeriesDetails::class.java)
        val adapter = moshi.adapter<TmdbDataEnvelope<TmdbSeriesDetails>>(type)
        val envelope = adapter.fromJson(json)

        assertNotNull(envelope)
        val series = envelope?.data
        assertNotNull(series)
        assertEquals(1399, series?.id)

        val domainSeries = series!!.toDomainDetails()
        assertEquals(1, domainSeries.cast.size)
        assertEquals("Emilia Clarke", domainSeries.cast[0].name)
        assertEquals(1, domainSeries.trailers.size)
        assertEquals("KPLWWIOCOOQ", domainSeries.trailers[0].key)
        assertTrue(domainSeries.trailers[0].isOfficial)
    }
}
