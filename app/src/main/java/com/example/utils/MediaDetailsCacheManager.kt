package com.example.utils

import android.content.Context
import com.example.data.db.AppDatabase
import com.example.domain.models.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MediaDetailsCacheManager {

    private const val CACHE_DIR_NAME = "media_details_cache"

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun languageSuffix(context: Context, language: String? = null): String {
        val raw = language ?: com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
        return if (raw.startsWith("ar", ignoreCase = true)) "ar" else "en"
    }

    fun getMovieFile(context: Context, movieId: String, variantMode: String? = null, language: String? = null): File {
        val safeId = movieId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val lang = languageSuffix(context, language)
        val isDubbed = variantMode?.equals("dubbed", ignoreCase = true) == true ||
            variantMode?.equals("arabic_dubbed", ignoreCase = true) == true || safeId.contains("_dubbed")
        val cleanId = if (isDubbed) safeId.removeSuffix("_dubbed") else safeId
        val suffix = if (isDubbed) "_dubbed" else ""
        return File(getCacheDir(context), "movie_${cleanId}_${lang}${suffix}.json")
    }

    fun getSeriesFile(context: Context, seriesId: String, variantMode: String? = null, language: String? = null): File {
        val safeId = seriesId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val lang = languageSuffix(context, language)
        val isDubbed = variantMode?.equals("dubbed", ignoreCase = true) == true ||
            variantMode?.equals("arabic_dubbed", ignoreCase = true) == true || safeId.contains("_dubbed")
        val cleanId = if (isDubbed) safeId.removeSuffix("_dubbed") else safeId
        val suffix = if (isDubbed) "_dubbed" else ""
        return File(getCacheDir(context), "series_${cleanId}_${lang}${suffix}.json")
    }

    fun hasDetails(context: Context, mediaId: String, isMovie: Boolean, variantMode: String? = null): Boolean {
        val file = if (isMovie) getMovieFile(context, mediaId, variantMode) else getSeriesFile(context, mediaId, variantMode)
        if (file.exists() && file.length() > 0) return true
        if (variantMode == null) {
            val dubbedFile = if (isMovie) getMovieFile(context, mediaId, "dubbed") else getSeriesFile(context, mediaId, "dubbed")
            return dubbedFile.exists() && dubbedFile.length() > 0
        }
        return false
    }

    fun saveMovieDetails(context: Context, movie: Movie, variantMode: String? = null) {
        try {
            val resolvedMode = variantMode ?: movie.mode
            val json = JSONObject().apply {
                put("id", movie.id)
                put("cacheLanguage", languageSuffix(context))
                put("title", movie.title)
                put("originalTitle", movie.originalTitle)
                put("overview", movie.overview)
                put("posterUrl", movie.posterUrl)
                put("backdropUrl", movie.backdropUrl)
                put("year", movie.year)
                put("releaseDate", movie.releaseDate)
                put("rating", movie.rating)
                put("genres", JSONArray(movie.genres))
                put("genreIds", JSONArray(movie.genreIds))
                put("runtime", movie.runtime)
                put("language", movie.language)
                
                val castArray = JSONArray()
                movie.cast.forEach { member ->
                    castArray.put(JSONObject().apply {
                        put("id", member.id)
                        put("name", member.name)
                        put("character", member.character)
                        put("profileUrl", member.profileUrl)
                        put("order", member.order)
                    })
                }
                put("cast", castArray)

                val trailerArray = JSONArray()
                movie.trailers.forEach { trailer ->
                    trailerArray.put(JSONObject().apply {
                        put("name", trailer.name)
                        put("key", trailer.key)
                        put("type", trailer.type)
                        put("isOfficial", trailer.isOfficial)
                        put("site", trailer.site)
                        put("language", trailer.language ?: JSONObject.NULL)
                        put("publishedAt", trailer.publishedAt ?: JSONObject.NULL)
                    })
                }
                put("trailers", trailerArray)

                if (!movie.sourceUrl.isNullOrBlank()) put("sourceUrl", movie.sourceUrl)
                if (!movie.providerId.isNullOrBlank()) put("providerId", movie.providerId)
                if (!resolvedMode.isNullOrBlank()) put("mode", resolvedMode)
                if (movie.sourceCandidates.isNotEmpty()) put("sourceCandidates", serializeCandidates(movie.sourceCandidates))
            }
            getMovieFile(context, movie.id, resolvedMode).writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getMovieDetails(context: Context, movieId: String, variantMode: String? = null): Movie? {
        var file = getMovieFile(context, movieId, variantMode)
        if (!file.exists()) {
            if (variantMode == null) {
                val dubbedFile = getMovieFile(context, movieId, "dubbed")
                if (dubbedFile.exists()) {
                    file = dubbedFile
                } else {
                    return null
                }
            } else {
                return null
            }
        }
        return try {
            val json = JSONObject(file.readText())
            val storedMode = json.optString("mode", "").ifEmpty { null }
            val isRequestedDubbed = variantMode?.equals("dubbed", ignoreCase = true) == true || variantMode?.equals("arabic_dubbed", ignoreCase = true) == true
            val isStoredDubbed = storedMode?.equals("dubbed", ignoreCase = true) == true
            if (variantMode != null && isRequestedDubbed != isStoredDubbed) {
                return null // Strict variant isolation: do not return conflicting variant
            }

            val genresList = mutableListOf<String>()
            val genresJson = json.optJSONArray("genres")
            if (genresJson != null) {
                for (i in 0 until genresJson.length()) {
                    genresList.add(genresJson.getString(i))
                }
            }

            val genreIdsList = mutableListOf<Int>()
            val genreIdsJson = json.optJSONArray("genreIds")
            if (genreIdsJson != null) {
                for (i in 0 until genreIdsJson.length()) {
                    genreIdsList.add(genreIdsJson.getInt(i))
                }
            }
            val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
            val displayGenres = if (genreIdsList.isNotEmpty()) {
                com.example.data.util.TmdbGenreHelper.getMovieGenreNames(genreIdsList, currentLang)
            } else {
                genresList
            }

            val castList = mutableListOf<CastMember>()
            val castJson = json.optJSONArray("cast")
            if (castJson != null) {
                for (i in 0 until castJson.length()) {
                    val c = castJson.getJSONObject(i)
                    castList.add(CastMember(
                        id = c.optString("id", ""),
                        name = c.optString("name", ""),
                        character = c.optString("character", ""),
                        profileUrl = c.optString("profileUrl", ""),
                        order = c.optInt("order", i)
                    ))
                }
            }
            val orderedCast = castList.sortedBy { it.order }

            val trailerList = mutableListOf<VideoTrailer>()
            val trailerJson = json.optJSONArray("trailers")
            if (trailerJson != null) {
                for (i in 0 until trailerJson.length()) {
                    val t = trailerJson.getJSONObject(i)
                    trailerList.add(VideoTrailer(
                        name = t.optString("name", ""),
                        key = t.optString("key", ""),
                        type = t.optString("type", "Trailer"),
                        isOfficial = t.optBoolean("isOfficial", false),
                        site = t.optString("site", "YouTube"),
                        language = if (t.has("language") && !t.isNull("language")) t.optString("language") else null,
                        publishedAt = if (t.has("publishedAt") && !t.isNull("publishedAt")) t.optString("publishedAt") else null
                    ))
                }
            }

            val candidates = deserializeCandidates(json.optJSONArray("sourceCandidates"))

            Movie(
                id = json.optString("id", movieId),
                title = json.optString("title", "Unknown"),
                originalTitle = json.optString("originalTitle", ""),
                overview = json.optString("overview", ""),
                posterUrl = json.optString("posterUrl", ""),
                backdropUrl = json.optString("backdropUrl", ""),
                year = json.optInt("year", 0),
                releaseDate = json.optString("releaseDate", ""),
                rating = json.optDouble("rating", 0.0),
                genres = displayGenres,
                runtime = json.optInt("runtime", 0),
                language = json.optString("language", "ar"),
                cast = orderedCast,
                trailers = trailerList,
                genreIds = genreIdsList,
                sourceUrl = json.optString("sourceUrl", "").ifEmpty { null },
                providerId = json.optString("providerId", "").ifEmpty { null },
                mode = storedMode,
                sourceCandidates = candidates
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getSeasonEpisodesFile(context: Context, seriesId: String, seasonNumber: Int, variantMode: String? = null): File {
        val safeId = seriesId.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        val lang = languageSuffix(context)
        val isDubbed = variantMode?.equals("dubbed", ignoreCase = true) == true ||
            variantMode?.equals("arabic_dubbed", ignoreCase = true) == true || safeId.contains("_dubbed")
        val cleanId = if (isDubbed) safeId.removeSuffix("_dubbed") else safeId
        val suffix = if (isDubbed) "_dubbed" else ""
        return File(getCacheDir(context), "series_${cleanId}_${lang}_season_${seasonNumber}${suffix}_episodes.json")
    }

    fun saveSeriesEpisodes(context: Context, seriesId: String, seasonNumber: Int, episodes: List<Episode>, variantMode: String? = null) {
        if (episodes.isEmpty()) return
        try {
            val episodesArray = JSONArray()
            episodes.forEach { ep ->
                episodesArray.put(JSONObject().apply {
                    put("id", ep.id)
                    put("episodeNumber", ep.episodeNumber)
                    put("seasonNumber", ep.seasonNumber)
                    put("title", ep.title)
                    put("overview", ep.overview)
                    put("thumbnailUrl", ep.thumbnailUrl)
                    put("rating", ep.rating)
                    put("duration", ep.duration)
                })
            }
            val json = JSONObject().apply {
                put("seriesId", seriesId)
                put("seasonNumber", seasonNumber)
                if (!variantMode.isNullOrBlank()) put("mode", variantMode)
                put("episodes", episodesArray)
            }
            getSeasonEpisodesFile(context, seriesId, seasonNumber, variantMode).writeText(json.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveSeriesDetails(context: Context, series: Series, episodes: List<Episode> = emptyList(), seasonNumber: Int = 1, variantMode: String? = null) {
        try {
            val resolvedMode = variantMode ?: series.mode
            val json = JSONObject().apply {
                put("id", series.id)
                put("cacheLanguage", languageSuffix(context))
                put("title", series.title)
                put("overview", series.overview)
                put("posterUrl", series.posterUrl)
                put("backdropUrl", series.backdropUrl)
                put("year", series.year)
                put("firstAirDate", series.firstAirDate)
                put("rating", series.rating)
                put("genres", JSONArray(series.genres))
                put("genreIds", JSONArray(series.genreIds))
                put("originalLanguage", series.originalLanguage ?: "")
                val originCountryArray = JSONArray()
                series.originCountry.forEach { originCountryArray.put(it) }
                put("originCountry", originCountryArray)
                put("creator", series.creator ?: "")
                put("status", series.status)

                val seasonsArray = JSONArray()
                series.seasons.forEach { season ->
                    seasonsArray.put(JSONObject().apply {
                        put("id", season.id)
                        put("seriesId", season.seriesId)
                        put("seasonNumber", season.seasonNumber)
                        put("title", season.title)
                        put("episodeCount", season.episodeCount)
                        put("posterUrl", season.posterUrl)
                    })
                }
                put("seasons", seasonsArray)

                val castArray = JSONArray()
                series.cast.forEach { member ->
                    castArray.put(JSONObject().apply {
                        put("id", member.id)
                        put("name", member.name)
                        put("character", member.character)
                        put("profileUrl", member.profileUrl)
                        put("order", member.order)
                    })
                }
                put("cast", castArray)

                val trailerArray = JSONArray()
                series.trailers.forEach { trailer ->
                    trailerArray.put(JSONObject().apply {
                        put("name", trailer.name)
                        put("key", trailer.key)
                        put("type", trailer.type)
                        put("isOfficial", trailer.isOfficial)
                        put("site", trailer.site)
                        put("language", trailer.language ?: JSONObject.NULL)
                        put("publishedAt", trailer.publishedAt ?: JSONObject.NULL)
                    })
                }
                put("trailers", trailerArray)

                put("episodesSeasonNumber", seasonNumber)
                val episodesArray = JSONArray()
                episodes.forEach { ep ->
                    episodesArray.put(JSONObject().apply {
                        put("id", ep.id)
                        put("episodeNumber", ep.episodeNumber)
                        put("seasonNumber", ep.seasonNumber)
                        put("title", ep.title)
                        put("overview", ep.overview)
                        put("thumbnailUrl", ep.thumbnailUrl)
                        put("rating", ep.rating)
                        put("duration", ep.duration)
                    })
                }
                put("episodes", episodesArray)

                if (!series.sourceUrl.isNullOrBlank()) put("sourceUrl", series.sourceUrl)
                if (!series.providerId.isNullOrBlank()) put("providerId", series.providerId)
                if (!resolvedMode.isNullOrBlank()) put("mode", resolvedMode)
                if (series.sourceCandidates.isNotEmpty()) put("sourceCandidates", serializeCandidates(series.sourceCandidates))
            }
            getSeriesFile(context, series.id, resolvedMode).writeText(json.toString())
            if (episodes.isNotEmpty()) {
                saveSeriesEpisodes(context, series.id, seasonNumber, episodes, resolvedMode)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getSeriesDetails(context: Context, seriesId: String, variantMode: String? = null): Series? {
        var file = getSeriesFile(context, seriesId, variantMode)
        if (!file.exists()) {
            if (variantMode == null) {
                val dubbedFile = getSeriesFile(context, seriesId, "dubbed")
                if (dubbedFile.exists()) {
                    file = dubbedFile
                } else {
                    return null
                }
            } else {
                return null
            }
        }
        return try {
            val json = JSONObject(file.readText())
            val storedMode = json.optString("mode", "").ifEmpty { null }
            val isRequestedDubbed = variantMode?.equals("dubbed", ignoreCase = true) == true || variantMode?.equals("arabic_dubbed", ignoreCase = true) == true
            val isStoredDubbed = storedMode?.equals("dubbed", ignoreCase = true) == true
            if (variantMode != null && isRequestedDubbed != isStoredDubbed) {
                return null // Strict variant isolation: do not return conflicting variant
            }
            val genresList = mutableListOf<String>()
            val genresJson = json.optJSONArray("genres")
            if (genresJson != null) {
                for (i in 0 until genresJson.length()) {
                    genresList.add(genresJson.getString(i))
                }
            }

            val genreIdsList = mutableListOf<Int>()
            val genreIdsJson = json.optJSONArray("genreIds")
            if (genreIdsJson != null) {
                for (i in 0 until genreIdsJson.length()) {
                    genreIdsList.add(genreIdsJson.getInt(i))
                }
            }
            val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
            val displayGenres = if (genreIdsList.isNotEmpty()) {
                com.example.data.util.TmdbGenreHelper.getTvGenreNames(genreIdsList, currentLang)
            } else {
                genresList
            }

            val castList = mutableListOf<CastMember>()
            val castJson = json.optJSONArray("cast")
            if (castJson != null) {
                for (i in 0 until castJson.length()) {
                    val c = castJson.getJSONObject(i)
                    castList.add(CastMember(
                        id = c.optString("id", ""),
                        name = c.optString("name", ""),
                        character = c.optString("character", ""),
                        profileUrl = c.optString("profileUrl", ""),
                        order = c.optInt("order", i)
                    ))
                }
            }
            val orderedCast = castList.sortedBy { it.order }

            val trailersList = mutableListOf<VideoTrailer>()
            val trailersJson = json.optJSONArray("trailers")
            if (trailersJson != null) {
                for (i in 0 until trailersJson.length()) {
                    val t = trailersJson.optJSONObject(i) ?: continue
                    trailersList.add(VideoTrailer(
                        name = t.optString("name", ""),
                        key = t.optString("key", ""),
                        type = t.optString("type", "Trailer"),
                        isOfficial = t.optBoolean("isOfficial", false),
                        site = t.optString("site", "YouTube"),
                        language = if (t.has("language") && !t.isNull("language")) t.optString("language") else null,
                        publishedAt = if (t.has("publishedAt") && !t.isNull("publishedAt")) t.optString("publishedAt") else null
                    ))
                }
            }

            val seasonsList = mutableListOf<Season>()
            val seasonsJson = json.optJSONArray("seasons")
            if (seasonsJson != null) {
                for (i in 0 until seasonsJson.length()) {
                    val s = seasonsJson.getJSONObject(i)
                    seasonsList.add(Season(
                        id = s.optString("id", ""),
                        seriesId = s.optString("seriesId", seriesId),
                        seasonNumber = s.optInt("seasonNumber", 1),
                        title = s.optString("title", "Season ${s.optInt("seasonNumber", 1)}"),
                        episodeCount = s.optInt("episodeCount", 10),
                        posterUrl = s.optString("posterUrl", "")
                    ))
                }
            }

            val originCountryList = mutableListOf<String>()
            val originCountryJson = json.optJSONArray("originCountry")
            if (originCountryJson != null) {
                for (i in 0 until originCountryJson.length()) {
                    originCountryList.add(originCountryJson.getString(i))
                }
            }
            val origLang = json.optString("originalLanguage", "").ifEmpty { null }
            val candidates = deserializeCandidates(json.optJSONArray("sourceCandidates"))

            Series(
                id = json.optString("id", seriesId),
                title = json.optString("title", "Unknown"),
                overview = json.optString("overview", ""),
                posterUrl = json.optString("posterUrl", ""),
                backdropUrl = json.optString("backdropUrl", ""),
                year = json.optInt("year", 0),
                firstAirDate = json.optString("firstAirDate", ""),
                rating = json.optDouble("rating", 0.0),
                genres = displayGenres,
                cast = orderedCast,
                trailers = trailersList,
                seasons = seasonsList,
                creator = json.optString("creator", "").ifEmpty { null },
                status = json.optString("status", "Ongoing"),
                originalLanguage = origLang,
                originCountry = originCountryList,
                genreIds = genreIdsList,
                sourceUrl = json.optString("sourceUrl", "").ifEmpty { null },
                providerId = json.optString("providerId", "").ifEmpty { null },
                mode = json.optString("mode", "").ifEmpty { null },
                sourceCandidates = candidates
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun serializeCandidates(candidates: List<ProviderSourceCandidate>): JSONArray {
        val array = JSONArray()
        candidates.forEach { c ->
            array.put(JSONObject().apply {
                put("providerId", c.providerId)
                put("scraperKey", c.scraperKey)
                put("sourcePageUrl", c.sourcePageUrl)
                put("title", c.title)
                put("mode", c.mode.value)
                put("audioLanguage", c.audioLanguage.code)
                if (c.seasonNumber != null) put("seasonNumber", c.seasonNumber)
                if (c.episodeNumber != null) put("episodeNumber", c.episodeNumber)
                put("status", c.status.name)
                if (!c.streamUrl.isNullOrBlank()) put("streamUrl", c.streamUrl)
                if (c.streamExpiresAt != null) put("streamExpiresAt", c.streamExpiresAt)
                if (!c.errorMessage.isNullOrBlank()) put("errorMessage", c.errorMessage)
                put("timestamp", c.timestamp)
                if (!c.posterUrl.isNullOrBlank()) put("posterUrl", c.posterUrl)
                if (c.contentType != null) put("contentType", c.contentType.name)
            })
        }
        return array
    }

    private fun deserializeCandidates(jsonArray: JSONArray?): List<ProviderSourceCandidate> {
        if (jsonArray == null) return emptyList()
        val list = mutableListOf<ProviderSourceCandidate>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.optJSONObject(i) ?: continue
            val providerId = obj.optString("providerId", "")
            val scraperKey = obj.optString("scraperKey", "")
            val sourcePageUrl = obj.optString("sourcePageUrl", "")
            val title = obj.optString("title", "")
            if (providerId.isBlank() && sourcePageUrl.isBlank()) continue

            val modeStr = obj.optString("mode", "subtitled")
            val mode = MediaVariantMode.fromString(modeStr)
            val langStr = obj.optString("audioLanguage", "ar")
            val lang = VariantLanguage.fromString(langStr)
            val statusStr = obj.optString("status", "FOUND")
            val status = try { CoverageStatus.valueOf(statusStr) } catch (_: Exception) { CoverageStatus.FOUND }
            val posterUrl = if (obj.has("posterUrl") && !obj.isNull("posterUrl")) obj.getString("posterUrl") else null
            val streamUrl = if (obj.has("streamUrl") && !obj.isNull("streamUrl")) obj.getString("streamUrl") else null
            val streamExpiresAt = if (obj.has("streamExpiresAt") && !obj.isNull("streamExpiresAt")) obj.getLong("streamExpiresAt") else null
            val errorMessage = if (obj.has("errorMessage") && !obj.isNull("errorMessage")) obj.getString("errorMessage") else null
            val seasonNumber = if (obj.has("seasonNumber") && !obj.isNull("seasonNumber")) obj.getInt("seasonNumber") else null
            val episodeNumber = if (obj.has("episodeNumber") && !obj.isNull("episodeNumber")) obj.getInt("episodeNumber") else null
            val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
            val contentTypeStr = if (obj.has("contentType") && !obj.isNull("contentType")) obj.getString("contentType") else null
            val contentType = contentTypeStr?.let { try { com.example.extension.managed.model.ContentType.valueOf(it) } catch (_: Exception) { null } }

            list.add(ProviderSourceCandidate(
                providerId = providerId,
                scraperKey = scraperKey,
                sourcePageUrl = sourcePageUrl,
                title = title,
                mode = mode,
                audioLanguage = lang,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                status = status,
                streamUrl = streamUrl,
                streamExpiresAt = streamExpiresAt,
                errorMessage = errorMessage,
                timestamp = timestamp,
                posterUrl = posterUrl,
                contentType = contentType
            ))
        }
        return list
    }

    fun getSeriesEpisodes(context: Context, seriesId: String, seasonNumber: Int, variantMode: String? = null): List<Episode> {
        var seasonFile = getSeasonEpisodesFile(context, seriesId, seasonNumber, variantMode)
        if (!seasonFile.exists() && variantMode == null) {
            val dubbedSeason = getSeasonEpisodesFile(context, seriesId, seasonNumber, "dubbed")
            if (dubbedSeason.exists()) {
                seasonFile = dubbedSeason
            }
        }
        if (seasonFile.exists()) {
            try {
                val json = JSONObject(seasonFile.readText())
                val storedMode = json.optString("mode", "").ifEmpty { null }
                val isRequestedDubbed = variantMode?.equals("dubbed", ignoreCase = true) == true || variantMode?.equals("arabic_dubbed", ignoreCase = true) == true
                val isStoredDubbed = storedMode?.equals("dubbed", ignoreCase = true) == true
                if (variantMode == null || isRequestedDubbed == isStoredDubbed) {
                    val episodesJson = json.optJSONArray("episodes") ?: return emptyList()
                    val result = mutableListOf<Episode>()
                    for (i in 0 until episodesJson.length()) {
                        val ep = episodesJson.getJSONObject(i)
                        result.add(Episode(
                            id = ep.optString("id", ""),
                            episodeNumber = ep.optInt("episodeNumber", 1),
                            title = ep.optString("title", ""),
                            overview = ep.optString("overview", ""),
                            thumbnailUrl = ep.optString("thumbnailUrl", ""),
                            rating = ep.optDouble("rating", 0.0),
                            duration = ep.optInt("duration", 0),
                            seasonNumber = ep.optInt("seasonNumber", seasonNumber)
                        ))
                    }
                    if (result.isNotEmpty()) return result
                }
            } catch (e: Exception) {
                // fall through to main file
            }
        }

        var file = getSeriesFile(context, seriesId, variantMode)
        if (!file.exists() && variantMode == null) {
            val dubbedSeries = getSeriesFile(context, seriesId, "dubbed")
            if (dubbedSeries.exists()) {
                file = dubbedSeries
            }
        }
        if (!file.exists()) return emptyList()
        return try {
            val json = JSONObject(file.readText())
            val storedMode = json.optString("mode", "").ifEmpty { null }
            val isRequestedDubbed = variantMode?.equals("dubbed", ignoreCase = true) == true || variantMode?.equals("arabic_dubbed", ignoreCase = true) == true
            val isStoredDubbed = storedMode?.equals("dubbed", ignoreCase = true) == true
            if (variantMode != null && isRequestedDubbed != isStoredDubbed) {
                return emptyList()
            }
            val savedSeason = json.optInt("episodesSeasonNumber", 1)
            if (savedSeason != seasonNumber) return emptyList()
            val episodesJson = json.optJSONArray("episodes") ?: return emptyList()
            val result = mutableListOf<Episode>()
            for (i in 0 until episodesJson.length()) {
                val ep = episodesJson.getJSONObject(i)
                result.add(Episode(
                    id = ep.optString("id", ""),
                    episodeNumber = ep.optInt("episodeNumber", 1),
                    title = ep.optString("title", ""),
                    overview = ep.optString("overview", ""),
                    thumbnailUrl = ep.optString("thumbnailUrl", ""),
                    rating = ep.optDouble("rating", 0.0),
                    duration = ep.optInt("duration", 0),
                    seasonNumber = ep.optInt("seasonNumber", seasonNumber)
                ))
            }
            result
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getDetailsJson(context: Context, mediaId: String, isMovie: Boolean): String? {
        val file = if (isMovie) getMovieFile(context, mediaId) else getSeriesFile(context, mediaId)
        return if (file.exists()) file.readText() else null
    }

    fun saveDetailsFromJson(context: Context, mediaId: String, isMovie: Boolean, jsonString: String) {
        try {
            val file = if (isMovie) getMovieFile(context, mediaId) else getSeriesFile(context, mediaId)
            file.writeText(jsonString)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Clear cached media details ONLY for items that have NOT been downloaded.
     * Information of any downloaded work is strictly preserved!
     */
    suspend fun clearNonDownloadedCache(context: Context) {
        try {
            val db = AppDatabase.getDatabase(context)
            val downloadedItems = db.downloadDao().getAllItemsSync()
            val protectedMediaIds = downloadedItems.map { it.mediaId }.toSet()

            val dir = getCacheDir(context)
            val files = dir.listFiles() ?: return
            for (f in files) {
                val name = f.name
                val isMovieFile = name.startsWith("movie_")
                val isSeriesFile = name.startsWith("series_")
                if (!isMovieFile && !isSeriesFile) continue

                val idPart = name.removePrefix("movie_").removePrefix("series_").removeSuffix(".json")
                val isProtected = protectedMediaIds.any { idPart.startsWith(it) || it.startsWith(idPart) }
                if (!isProtected) {
                    f.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
