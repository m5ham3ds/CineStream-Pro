package com.example.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TmdbDataEnvelope<T>(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "service") val service: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "resource") val resource: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "data") val data: T? = null
)

@JsonClass(generateAdapter = true)
data class TmdbResponse<T>(
    @Json(name = "ok") val ok: Boolean? = null,
    @Json(name = "page") val page: Int = 1,
    @Json(name = "results") val results: List<T> = emptyList(),
    @Json(name = "total_pages") val totalPages: Int = 1,
    @Json(name = "total_results") val totalResults: Int = 0
)

@JsonClass(generateAdapter = true)
data class TmdbMovie(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList(),
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = emptyList()
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbSeries(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList(),
    @Json(name = "origin_country") val originCountry: List<String>? = emptyList(),
    @Json(name = "original_language") val originalLanguage: String? = null
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbMulti(
    @Json(name = "id") val id: Int,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "title") val title: String? = null, // Used for movies
    @Json(name = "original_title") val originalTitle: String? = null, // Used for movies
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,   // Used for series
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = emptyList(),
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = emptyList(),
    @Json(name = "character") val character: String? = null,
    @Json(name = "order") val order: Int? = null,
    @Json(name = "popularity") val popularity: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbGenreResponse(
    @Json(name = "genres") val genres: List<TmdbGenre> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbCast(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "character") val character: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "order") val order: Int? = null
) {
    val fullProfileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w185$it" }
}

@JsonClass(generateAdapter = true)
data class TmdbCrew(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "job") val job: String? = null,
    @Json(name = "department") val department: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCredits(
    @Json(name = "cast") val cast: List<TmdbCast>? = emptyList(),
    @Json(name = "crew") val crew: List<TmdbCrew>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbVideo(
    @Json(name = "id") val id: String,
    @Json(name = "key") val key: String,
    @Json(name = "name") val name: String,
    @Json(name = "site") val site: String,
    @Json(name = "type") val type: String,
    @Json(name = "official") val official: Boolean? = false,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "iso_639_1") val iso6391: String? = null,
    @Json(name = "iso_3166_1") val iso31661: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbVideos(
    @Json(name = "results") val results: List<TmdbVideo>
)

@JsonClass(generateAdapter = true)
data class TmdbSeason(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String,
    @Json(name = "season_number") val seasonNumber: Int,
    @Json(name = "episode_count") val episodeCount: Int,
    @Json(name = "poster_path") val posterPath: String?
) {
    val fullPosterUrl: String?
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
}

@JsonClass(generateAdapter = true)
data class TmdbCreator(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbMovieDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "original_title") val originalTitle: String?,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "runtime") val runtime: Int?,
    @Json(name = "original_language") val originalLanguage: String?,
    @Json(name = "genres") val genres: List<TmdbGenre>?,
    @Json(name = "credits") val credits: TmdbCredits?,
    @Json(name = "videos") val videos: TmdbVideos?,
    @Json(name = "origin_country") val originCountry: List<String>? = emptyList(),
    @Json(name = "vote_count") val voteCount: Int? = null
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbSeriesDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "genres") val genres: List<TmdbGenre>?,
    @Json(name = "credits") val credits: TmdbCredits?,
    @Json(name = "videos") val videos: TmdbVideos?,
    @Json(name = "seasons") val seasons: List<TmdbSeason>?,
    @Json(name = "created_by") val createdBy: List<TmdbCreator>?,
    @Json(name = "status") val status: String?,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = emptyList(),
    @Json(name = "vote_count") val voteCount: Int? = null
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" } ?: ""
        
    val fullBackdropUrl: String
        get() = backdropPath?.let { "https://image.tmdb.org/t/p/w780$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbEpisode(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "episode_number") val episodeNumber: Int,
    @Json(name = "still_path") val stillPath: String?,
    @Json(name = "vote_average") val voteAverage: Double?,
    @Json(name = "runtime") val runtime: Int?
) {
    val fullStillUrl: String?
        get() = stillPath?.let { "https://image.tmdb.org/t/p/w300$it" }
}

@JsonClass(generateAdapter = true)
data class TmdbSeasonDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "season_number") val seasonNumber: Int,
    @Json(name = "episodes") val episodes: List<TmdbEpisode>?
)


@JsonClass(generateAdapter = true)
data class TmdbPersonMovieCredit(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String?,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "release_date") val releaseDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbPersonSeriesCredit(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String?,
    @Json(name = "poster_path") val posterPath: String?,
    @Json(name = "backdrop_path") val backdropPath: String?,
    @Json(name = "first_air_date") val firstAirDate: String?,
    @Json(name = "vote_average") val voteAverage: Double?
) {
    val fullPosterUrl: String
        get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" } ?: ""
}

@JsonClass(generateAdapter = true)
data class TmdbPersonCombinedCredits(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "cast") val cast: List<TmdbMulti>? = emptyList(),
    @Json(name = "crew") val crew: List<TmdbCrew>? = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbPersonDetails(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String?,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "also_known_as") val alsoKnownAs: List<String>? = emptyList(),
    @Json(name = "biography") val biography: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "birthday") val birthday: String? = null,
    @Json(name = "deathday") val deathday: String? = null,
    @Json(name = "gender") val gender: Int? = null,
    @Json(name = "place_of_birth") val placeOfBirth: String? = null,
    @Json(name = "known_for_department") val knownForDepartment: String? = null,
    @Json(name = "popularity") val popularity: Double? = null,
    @Json(name = "combined_credits") val combinedCredits: TmdbPersonCombinedCredits? = null
) {
    val fullProfileUrl: String?
        get() = profilePath?.let { "https://image.tmdb.org/t/p/w500$it" }
}