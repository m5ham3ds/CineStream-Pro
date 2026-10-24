package com.example.data.repository

import com.example.data.model.HistoryItem
import com.example.data.remote.RetrofitClient
import com.example.data.remote.TmdbApiService
import com.example.ui.components.CardMediaDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance, deduplicated metadata manager for Continue Watching (PERF-AD-08).
 *
 * Remediates direct TMDB network requests from within Composable render paths:
 * 1. Thread-safe in-memory cache preventing duplicate TMDB requests for the same media.
 * 2. Active in-flight request tracker ensuring concurrent cards or rapid recompositions await
 *    the same shared deferred task rather than launching redundant network requests.
 * 3. Reactive StateFlow for state observation without recomposition churn.
 * 4. Background preloading decoupled from the Compose rendering lifecycle.
 */
object ContinueWatchingMetadataManager {
    // In-memory cache of resolved metadata by mediaId
    private val metadataCache = ConcurrentHashMap<String, CardMediaDetail>()

    // In-flight active network requests to guarantee deduplication across concurrent cards
    private val inFlightRequests = ConcurrentHashMap<String, Deferred<CardMediaDetail>>()

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // StateFlow emitting current metadata map for reactive UI consumption
    private val _metadataFlow = MutableStateFlow<Map<String, CardMediaDetail>>(emptyMap())
    val metadataFlow: StateFlow<Map<String, CardMediaDetail>> = _metadataFlow.asStateFlow()

    // Test hook: allows injecting mock/spy API service for verification
    internal var apiService: TmdbApiService = RetrofitClient.tmdbApi

    /**
     * Synchronously returns cached metadata if available, otherwise returns the fallback local detail.
     */
    /** Distinguish movie and TV IDs, and keep localized metadata in separate cache entries. */
    fun cacheKeyFor(item: HistoryItem): String {
        val mediaType = if (item.isMovie) "movie" else "tv"
        val language = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
        return "${mediaType}_${item.id}_${language}"
    }

    fun getCachedOrFallback(item: HistoryItem): CardMediaDetail {
        metadataCache[cacheKeyFor(item)]?.let { return it }
        return fallbackDetail(item)
    }

    /**
     * Deduplicated fetch method. If mediaId is currently loading, awaits the existing in-flight task.
     * If already cached, immediately returns without issuing a network call.
     */
    suspend fun getOrFetchMetadata(
        item: HistoryItem
    ): CardMediaDetail = withContext(Dispatchers.IO) {
        val cacheKey = cacheKeyFor(item)
        metadataCache[cacheKey]?.let { return@withContext it }

        val idInt = item.id.toIntOrNull() ?: item.id.substringBefore("_").toIntOrNull()
        if (idInt == null) {
            val fallback = fallbackDetail(item)
            synchronized(lock) {
                metadataCache[cacheKey] = fallback
                updateFlow()
            }
            return@withContext fallback
        }

        val deferred = synchronized(lock) {
            metadataCache[cacheKey]?.let { return@withContext it }
            inFlightRequests.computeIfAbsent(cacheKey) {
                scope.async(Dispatchers.IO) {
                    try {
                        val detail = if (item.isMovie) {
                            val resEnvelope = apiService.getMovieDetails(idInt, appendToResponse = "")
                            val res = resEnvelope.data
                            if (res == null) {
                                fallbackDetail(item)
                            } else {
                                val r = res.voteAverage?.takeIf {
                                    it.isFinite() && it in 0.0..10.0 && (res.voteCount ?: 0) > 0
                                }
                                val runtime = res.runtime ?: 0
                                CardMediaDetail(
                                    title = (res.title ?: res.originalTitle ?: item.title).toString(),
                                    year = (res.releaseDate?.take(4) ?: "").toString(),
                                    rating = r?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
                                    overview = (res.overview ?: "").toString(),
                                    backdropUrl = (if (res.backdropPath != null) "https://image.tmdb.org/t/p/w780${res.backdropPath}" else item.posterUrl).toString(),
                                    isMovie = true,
                                    runtimeMinutes = runtime
                                )
                            }
                        } else {
                            val resEnvelope = apiService.getSeriesDetails(idInt, appendToResponse = "")
                            val res = resEnvelope.data
                            if (res == null) {
                                fallbackDetail(item)
                            } else {
                                val r = res.voteAverage?.takeIf {
                                    it.isFinite() && it in 0.0..10.0 && (res.voteCount ?: 0) > 0
                                }
                                CardMediaDetail(
                                    title = (res.name ?: res.originalName ?: item.title).toString(),
                                    year = (res.firstAirDate?.take(4) ?: "").toString(),
                                    rating = r?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "",
                                    overview = (res.overview ?: "").toString(),
                                    backdropUrl = (if (res.backdropPath != null) "https://image.tmdb.org/t/p/w780${res.backdropPath}" else item.posterUrl).toString(),
                                    isMovie = false,
                                    runtimeMinutes = 0
                                )
                            }
                        }
                        synchronized(lock) {
                            metadataCache[cacheKey] = detail
                            updateFlow()
                        }
                        detail
                    } catch (_: Exception) {
                        val fallback = fallbackDetail(item)
                        synchronized(lock) {
                            metadataCache[cacheKey] = fallback
                            updateFlow()
                        }
                        fallback
                    } finally {
                        synchronized(lock) {
                            inFlightRequests.remove(cacheKey)
                        }
                    }
                }
            }
        }
        deferred.await()
    }

    /**
     * Batch preloading on Dispatchers.IO for a list of items.
     * Only queries items not currently cached and not already in-flight.
     */
    fun preload(items: List<HistoryItem>, scope: CoroutineScope) {
        val uncached = items.filter {
            val key = cacheKeyFor(it)
            !metadataCache.containsKey(key) && !inFlightRequests.containsKey(key)
        }
        if (uncached.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            for (item in uncached) {
                getOrFetchMetadata(item)
            }
        }
    }

    private fun fallbackDetail(item: HistoryItem): CardMediaDetail {
        return CardMediaDetail(
            title = item.title,
            year = "",
            rating = "",
            overview = "",
            backdropUrl = item.posterUrl,
            isMovie = item.isMovie,
            runtimeMinutes = 0
        )
    }

    private fun updateFlow() {
        _metadataFlow.value = HashMap(metadataCache)
    }

    fun isCached(item: HistoryItem): Boolean = metadataCache.containsKey(cacheKeyFor(item))
    fun isLoading(item: HistoryItem): Boolean = inFlightRequests.containsKey(cacheKeyFor(item))

    // Compatibility helpers for older call sites/tests. Use the typed overloads in UI code.
    fun isCached(id: String): Boolean = metadataCache.keys.any { it.substringAfter('_').substringBeforeLast('_') == id }
    fun isLoading(id: String): Boolean = inFlightRequests.keys.any { it.substringAfter('_').substringBeforeLast('_') == id }

    fun clearCacheForTesting() {
        metadataCache.clear()
        inFlightRequests.clear()
        _metadataFlow.value = emptyMap()
        apiService = RetrofitClient.tmdbApi
    }
}
