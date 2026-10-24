package com.example.utils

import android.content.Context
import com.example.domain.models.Movie
import com.example.domain.models.Series
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MediaListDiskCacheManager {

    private const val CACHE_DIR_NAME = "media_lists_cache"

    private fun getCacheDir(context: Context): File {
        val dir = File(context.filesDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun getCacheFile(context: Context, key: String): File {
        val safeKey = key.replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        return File(getCacheDir(context), "${safeKey}.json")
    }

    /**
     * Checks if persistent cache exists and has non-zero size for the specified key.
     */
    fun hasCache(context: Context, key: String): Boolean {
        val file = getCacheFile(context, key)
        return file.exists() && file.length() > 0L
    }

    /** Remove a cached category when the current upstream query validly returns no results. */
    fun clearList(context: Context, key: String) {
        runCatching { getCacheFile(context, key).delete() }
    }

    /**
     * Saves a list of Movies or Series to persistent disk cache in filesDir.
     */
    fun <T> saveList(context: Context, key: String, items: List<T>) {
        if (items.isEmpty()) return
        try {
            val root = JSONObject()
            root.put("timestamp", System.currentTimeMillis())
            val first = items.firstOrNull()
            val isMovie = first is Movie
            root.put("isMovie", isMovie)

            val array = JSONArray()
            for (item in items) {
                if (item is Movie) {
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("originalTitle", item.originalTitle ?: item.title)
                        put("overview", item.overview)
                        put("posterUrl", item.posterUrl)
                        put("backdropUrl", item.backdropUrl)
                        put("year", item.year)
                        put("releaseDate", item.releaseDate ?: "")
                        put("rating", item.rating)
                        val genresArr = JSONArray()
                        item.genres.forEach { genresArr.put(it) }
                        put("genres", genresArr)
                        put("runtime", item.runtime)
                        put("language", item.language)
                        put("country", item.country ?: "")
                        put("director", item.director ?: "")
                    }
                    array.put(obj)
                } else if (item is Series) {
                    val obj = JSONObject().apply {
                        put("id", item.id)
                        put("title", item.title)
                        put("originalTitle", item.originalTitle ?: item.title)
                        put("overview", item.overview)
                        put("posterUrl", item.posterUrl)
                        put("backdropUrl", item.backdropUrl)
                        put("year", item.year)
                        put("firstAirDate", item.firstAirDate ?: "")
                        put("rating", item.rating)
                        val genresArr = JSONArray()
                        item.genres.forEach { genresArr.put(it) }
                        put("genres", genresArr)
                        put("creator", item.creator ?: "")
                        put("status", item.status ?: "")
                        put("originalLanguage", item.originalLanguage ?: "")
                        put("seasonCount", item.seasons.size)
                    }
                    array.put(obj)
                }
            }
            root.put("items", array)

            val tempFile = File(getCacheDir(context), "${key}_tmp.json")
            tempFile.writeText(root.toString())
            val destFile = getCacheFile(context, key)
            if (destFile.exists()) {
                destFile.delete()
            }
            tempFile.renameTo(destFile)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Loads a list of Movies or Series from persistent disk cache.
     * Retains cached data permanently for offline access.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> loadList(context: Context, key: String): List<T>? {
        val file = getCacheFile(context, key)
        if (!file.exists() || file.length() == 0L) return null
        return try {
            val content = file.readText()
            val root = JSONObject(content)
            val isMovie = root.optBoolean("isMovie", true)
            val array = root.optJSONArray("items") ?: return null
            val result = mutableListOf<Any>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val genres = mutableListOf<String>()
                val gArr = obj.optJSONArray("genres")
                if (gArr != null) {
                    for (g in 0 until gArr.length()) {
                        genres.add(gArr.getString(g))
                    }
                }
                if (isMovie) {
                    result.add(
                        Movie(
                            id = obj.optString("id", ""),
                            title = obj.optString("title", ""),
                            originalTitle = obj.optString("originalTitle", obj.optString("title", "")),
                            overview = obj.optString("overview", ""),
                            posterUrl = obj.optString("posterUrl", ""),
                            backdropUrl = obj.optString("backdropUrl", ""),
                            year = obj.optInt("year", 0),
                            releaseDate = obj.optString("releaseDate", "").takeIf { it.isNotBlank() },
                            rating = obj.optDouble("rating", 0.0),
                            genres = genres,
                            runtime = obj.optInt("runtime", 120),
                            language = obj.optString("language", "en"),
                            country = obj.optString("country", "").takeIf { it.isNotBlank() },
                            director = obj.optString("director", "").takeIf { it.isNotBlank() }
                        )
                    )
                } else {
                    val sId = obj.optString("id", "")
                    val sPoster = obj.optString("posterUrl", "")
                    val seasonCount = obj.optInt("seasonCount", 0)
                    result.add(
                        Series(
                            id = sId,
                            title = obj.optString("title", ""),
                            originalTitle = obj.optString("originalTitle", obj.optString("title", "")),
                            overview = obj.optString("overview", ""),
                            posterUrl = sPoster,
                            backdropUrl = obj.optString("backdropUrl", ""),
                            year = obj.optInt("year", 0),
                            firstAirDate = obj.optString("firstAirDate", "").takeIf { it.isNotBlank() },
                            rating = obj.optDouble("rating", 0.0),
                            genres = genres,
                            seasons = if (seasonCount > 0) {
                                List(seasonCount) { idx ->
                                    com.example.domain.models.Season(
                                        id = "${sId}_${idx + 1}",
                                        seriesId = sId,
                                        seasonNumber = idx + 1,
                                        title = "Season ${idx + 1}",
                                        posterUrl = sPoster
                                    )
                                }
                            } else {
                                emptyList()
                            },
                            creator = obj.optString("creator", "").takeIf { it.isNotBlank() },
                            status = obj.optString("status", "").takeIf { it.isNotBlank() },
                            originalLanguage = obj.optString("originalLanguage", "").takeIf { it.isNotBlank() }
                        )
                    )
                }
            }
            result as? List<T>
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Checks if any list in the persistent cache has items.
     * Used to determine whether the user previously connected and has offline data.
     */
    fun hasAnyCachedData(context: Context): Boolean {
        return try {
            val dir = getCacheDir(context)
            val files = dir.listFiles() ?: return false
            files.any { it.isFile && it.name.endsWith(".json") && it.length() > 50L }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Searches all cached movies and series across stored lists for query matching.
     * Allows offline search when there is no internet.
     */
    fun searchCachedMedia(context: Context, query: String): Pair<List<Movie>, List<Series>> {
        val q = query.trim().lowercase()
        if (q.isBlank()) return Pair(emptyList(), emptyList())
        val movies = mutableListOf<Movie>()
        val series = mutableListOf<Series>()
        try {
            val dir = getCacheDir(context)
            val files = dir.listFiles() ?: return Pair(emptyList(), emptyList())
            for (f in files) {
                if (!f.name.endsWith(".json")) continue
                val root = JSONObject(f.readText())
                val isMovie = root.optBoolean("isMovie", true)
                val array = root.optJSONArray("items") ?: continue
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val title = obj.optString("title", "").lowercase()
                    val orig = obj.optString("originalTitle", "").lowercase()
                    if (title.contains(q) || orig.contains(q)) {
                        val genres = mutableListOf<String>()
                        val gArr = obj.optJSONArray("genres")
                        if (gArr != null) {
                            for (g in 0 until gArr.length()) genres.add(gArr.getString(g))
                        }
                        if (isMovie) {
                            movies.add(
                                Movie(
                                    id = obj.optString("id", ""),
                                    title = obj.optString("title", ""),
                                    originalTitle = obj.optString("originalTitle", ""),
                                    overview = obj.optString("overview", ""),
                                    posterUrl = obj.optString("posterUrl", ""),
                                    backdropUrl = obj.optString("backdropUrl", ""),
                                    year = obj.optInt("year", 0),
                                    releaseDate = obj.optString("releaseDate", "").takeIf { it.isNotBlank() },
                                    rating = obj.optDouble("rating", 0.0),
                                    genres = genres,
                                    runtime = obj.optInt("runtime", 120),
                                    language = obj.optString("language", "en")
                                )
                            )
                        } else {
                            val sId = obj.optString("id", "")
                            val sPoster = obj.optString("posterUrl", "")
                            val seasonCount = obj.optInt("seasonCount", 0)
                            series.add(
                                Series(
                                    id = sId,
                                    title = obj.optString("title", ""),
                                    originalTitle = obj.optString("originalTitle", ""),
                                    overview = obj.optString("overview", ""),
                                    posterUrl = sPoster,
                                    backdropUrl = obj.optString("backdropUrl", ""),
                                    year = obj.optInt("year", 0),
                                    firstAirDate = obj.optString("firstAirDate", "").takeIf { it.isNotBlank() },
                                    rating = obj.optDouble("rating", 0.0),
                                    genres = genres,
                                    seasons = if (seasonCount > 0) {
                                        List(seasonCount) { idx ->
                                            com.example.domain.models.Season(
                                                id = "${sId}_${idx + 1}",
                                                seriesId = sId,
                                                seasonNumber = idx + 1,
                                                title = "Season ${idx + 1}",
                                                posterUrl = sPoster
                                            )
                                        }
                                    } else {
                                        emptyList()
                                    },
                                    creator = obj.optString("creator", "").takeIf { it.isNotBlank() },
                                    status = obj.optString("status", "").takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Pair(movies.distinctBy { it.id }, series.distinctBy { it.id })
    }
}
