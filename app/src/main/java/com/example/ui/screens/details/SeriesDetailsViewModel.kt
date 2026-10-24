package com.example.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppContainer
import com.example.domain.models.Episode
import com.example.domain.models.Season
import com.example.domain.models.Series
import com.example.domain.models.VideoTrailer
import com.example.data.util.TrailerSelectionHelper
import com.example.domain.repository.MediaRepository
import com.example.utils.MediaDetailsCacheManager
import com.example.utils.NetworkUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SeriesDetailsUiState(
    val isLoading: Boolean = false,
    val series: Series? = null,
    val error: String? = null,
    val selectedSeason: Season? = null,
    val episodes: List<Episode> = emptyList(),
    val isEpisodesLoading: Boolean = false,
    val visibleEpisodesCount: Int = 10,
    val isLoadingMore: Boolean = false,
    val primaryTrailer: VideoTrailer? = null,
    val primaryTrailerRequestKey: String? = null,
    val isPrimaryTrailerLoaded: Boolean = false,
    val otherTrailers: List<VideoTrailer> = emptyList(),
    val otherTrailersLoadedFor: String? = null,
    val isLoadingOtherTrailers: Boolean = false,
    val areOtherTrailersExpanded: Boolean = false
) {
    val isSeasonSynced: Boolean
        get() {
            val s = selectedSeason ?: return true
            if (episodes.isEmpty()) return true
            return episodes.all { it.seasonNumber == s.seasonNumber }
        }
}

class SeriesDetailsViewModel(
    private val repository: MediaRepository
) : ViewModel() {
    companion object {
        private val cache = mutableMapOf<String, SeriesDetailsUiState>()
        private val seasonEpisodesCache = mutableMapOf<String, List<Episode>>()
        private fun localizedKey(id: String, experienceMode: String): String {
            val language = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
            return "${id}_${language}_$experienceMode"
        }

        fun getCachedState(seriesId: String, experienceMode: String = "STANDARD"): SeriesDetailsUiState? {
            val ids = linkedSetOf(seriesId)
            runCatching { ids.add(java.net.URLDecoder.decode(seriesId, "UTF-8")) }
            runCatching { ids.add(java.net.URLEncoder.encode(seriesId, "UTF-8")) }
            return ids.firstNotNullOfOrNull { cache[localizedKey(it, experienceMode)] }
        }

        fun saveState(seriesId: String, state: SeriesDetailsUiState, experienceMode: String = "STANDARD") {
            // Guarantee cache safety (Part 7): never save mismatched season/episodes state
            val safeState = if (state.selectedSeason != null && state.episodes.isNotEmpty()) {
                val expectedSeason = state.selectedSeason.seasonNumber
                val hasMismatch = state.episodes.any { it.seasonNumber != expectedSeason }
                if (hasMismatch) {
                    val syncedEpisodes = state.episodes.filter { it.seasonNumber == expectedSeason }
                    state.copy(episodes = syncedEpisodes)
                } else state
            } else state

            val ids = linkedSetOf(seriesId)
            runCatching { ids.add(java.net.URLEncoder.encode(seriesId, "UTF-8")) }
            runCatching { ids.add(java.net.URLDecoder.decode(seriesId, "UTF-8")) }
            ids.forEach { cache[localizedKey(it, experienceMode)] = safeState }
        }

        fun updateAndCache(
            seriesId: String,
            transform: (SeriesDetailsUiState) -> SeriesDetailsUiState,
            experienceMode: String = "STANDARD"
        ) {
            val current = cache[localizedKey(seriesId, experienceMode)] ?: SeriesDetailsUiState()
            saveState(seriesId, transform(current), experienceMode)
        }

        private fun localizedEpisodeKey(seriesId: String, seasonNumber: Int, experienceMode: String): String {
            val language = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
            return "${seriesId}_${language}_s${seasonNumber}_$experienceMode"
        }

        fun getCachedEpisodes(seriesId: String, seasonNumber: Int, experienceMode: String = "STANDARD"): List<Episode>? =
            seasonEpisodesCache[localizedEpisodeKey(seriesId, seasonNumber, experienceMode)]

        fun saveCachedEpisodes(seriesId: String, seasonNumber: Int, episodes: List<Episode>, experienceMode: String = "STANDARD") {
            if (episodes.isNotEmpty()) {
                val validEpisodes = episodes.filter { it.seasonNumber == seasonNumber }
                if (validEpisodes.isNotEmpty()) {
                    seasonEpisodesCache[localizedEpisodeKey(seriesId, seasonNumber, experienceMode)] = validEpisodes
                }
            }
        }

        fun clearCache(seriesId: String? = null) {
            if (seriesId != null) {
                val statePrefix = "${seriesId}_"
                cache.keys.removeAll { it == seriesId || it.startsWith(statePrefix) }
                seasonEpisodesCache.keys.removeAll { it.startsWith(statePrefix) }
            } else {
                cache.clear()
                seasonEpisodesCache.clear()
            }
        }
    }

    private val _uiState = MutableStateFlow(SeriesDetailsUiState())
    val uiState: StateFlow<SeriesDetailsUiState> = _uiState.asStateFlow()
    private var networkObserverJob: Job? = null
    private var detailLoadJob: Job? = null
    private var activeSeriesRequestKey: String? = null
    private var episodeLoadingJob: Job? = null
    private var primaryTrailerJob: Job? = null
    private var otherTrailersJob: Job? = null
    private var otherTrailersRequestKey: String? = null
    private var currentExperienceMode: String = "STANDARD"

    fun loadPrimaryTrailer(mediaId: String, language: String, cachedTrailers: List<VideoTrailer> = emptyList()) {
        val locale = if (language.startsWith("ar", ignoreCase = true)) "ar-SA" else "en-US"
        val key = "${mediaId}_$locale"
        val current = _uiState.value
        if (current.primaryTrailerRequestKey == key && current.isPrimaryTrailerLoaded) return
        if (primaryTrailerJob?.isActive == true && current.primaryTrailerRequestKey == key) return

        val preferred = if (locale.startsWith("ar")) "ar" else "en"
        val cachedPrimary = TrailerSelectionHelper.selectPrimaryTrailer(cachedTrailers, preferredLanguage = preferred)
        if (cachedPrimary != null) {
            _uiState.update {
                it.copy(
                    primaryTrailer = cachedPrimary,
                    primaryTrailerRequestKey = key,
                    isPrimaryTrailerLoaded = true,
                    otherTrailers = emptyList(),
                    otherTrailersLoadedFor = null,
                    areOtherTrailersExpanded = false,
                    isLoadingOtherTrailers = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                primaryTrailerRequestKey = key,
                isPrimaryTrailerLoaded = false,
                primaryTrailer = null,
                otherTrailers = emptyList(),
                otherTrailersLoadedFor = null,
                areOtherTrailersExpanded = false,
                isLoadingOtherTrailers = false
            )
        }
        primaryTrailerJob?.cancel()
        primaryTrailerJob = viewModelScope.launch {
            val videos = repository.getTrailers(mediaId, isMovie = false, language = locale, primaryOnly = true)
            val primary = TrailerSelectionHelper.selectPrimaryTrailer(videos, preferredLanguage = preferred)
            _uiState.update { state ->
                if (state.series?.id?.toIntOrNull() == mediaId.toIntOrNull()) {
                    state.copy(primaryTrailer = primary, primaryTrailerRequestKey = key, isPrimaryTrailerLoaded = true)
                } else state
            }
        }
    }

    fun showOtherTrailers(mediaId: String, language: String) {
        val locale = if (language.startsWith("ar", ignoreCase = true)) "ar-SA" else "en-US"
        val key = "${mediaId}_$locale"
        if (_uiState.value.otherTrailersLoadedFor == key) {
            _uiState.update { it.copy(areOtherTrailersExpanded = true) }
            return
        }
        if (otherTrailersJob?.isActive == true && otherTrailersRequestKey == key) return

        otherTrailersRequestKey = key
        _uiState.update { it.copy(areOtherTrailersExpanded = true, isLoadingOtherTrailers = true) }
        otherTrailersJob?.cancel()
        otherTrailersJob = viewModelScope.launch {
            val videos = repository.getTrailers(mediaId, isMovie = false, language = locale, primaryOnly = false)
            val primaryKey = _uiState.value.primaryTrailer?.key
            val others = videos
                .filter { it.type.equals("Trailer", ignoreCase = true) }
                .filterNot { it.key == primaryKey }
                .distinctBy { it.key }
            _uiState.update { state ->
                if (state.series?.id?.toIntOrNull() == mediaId.toIntOrNull()) {
                    state.copy(
                        otherTrailers = others,
                        otherTrailersLoadedFor = key,
                        isLoadingOtherTrailers = false,
                        areOtherTrailersExpanded = true
                    )
                } else state
            }
        }
    }

    fun hideOtherTrailers() {
        _uiState.update { it.copy(areOtherTrailersExpanded = false) }
    }

    fun loadSeries(seriesId: String, forceRefresh: Boolean = false, experienceMode: String = "STANDARD") {
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        val requestKey = "$targetId|$experienceMode"
        if (activeSeriesRequestKey == requestKey && detailLoadJob?.isActive == true) return
        if (activeSeriesRequestKey != null && activeSeriesRequestKey != requestKey) {
            detailLoadJob?.cancel()
            networkObserverJob?.cancel()
            episodeLoadingJob?.cancel()
            primaryTrailerJob?.cancel()
            otherTrailersJob?.cancel()
            _uiState.value = SeriesDetailsUiState()
        }
        activeSeriesRequestKey = requestKey
        currentExperienceMode = experienceMode
        val isDubbedExp = experienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || experienceMode.equals("DUBBED", ignoreCase = true)
        val targetVariantMode = if (isDubbedExp) "dubbed" else "subtitled"
        val app = try { AppContainer.application } catch (_: Exception) { null }

        // 1. In-memory Companion cache restoration with strict season/episode synchronization
        val cached = getCachedState(targetId, experienceMode)
        if (cached != null && !forceRefresh) {
            val preservedSeason = cached.selectedSeason
                ?: cached.series?.seasons?.firstOrNull { it.seasonNumber > 0 }
                ?: cached.series?.seasons?.firstOrNull()
            val initialEpisodes = if (preservedSeason != null) {
                (getCachedEpisodes(targetId, preservedSeason.seasonNumber, experienceMode)
                    ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, preservedSeason.seasonNumber, variantMode = targetVariantMode) else null)
                    ?: emptyList()).filter { it.seasonNumber == preservedSeason.seasonNumber }
            } else emptyList()

            val state = cached.copy(
                selectedSeason = preservedSeason,
                episodes = initialEpisodes,
                isEpisodesLoading = preservedSeason != null && initialEpisodes.isEmpty()
            )
            _uiState.value = state
            if (preservedSeason != null && initialEpisodes.isEmpty()) {
                loadEpisodes(targetId, preservedSeason.seasonNumber)
            }
        }

        // 2. Persistent disk cache fallback
        val persistentCachedSeries = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId, variantMode = targetVariantMode) else null
        if (persistentCachedSeries != null && _uiState.value.series == null) {
            val initialSeason = persistentCachedSeries.seasons.firstOrNull { it.seasonNumber > 0 } ?: persistentCachedSeries.seasons.firstOrNull()
            val initialEpisodes = if (app != null && initialSeason != null) {
                (getCachedEpisodes(targetId, initialSeason.seasonNumber, experienceMode)
                    ?: MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, initialSeason.seasonNumber, variantMode = targetVariantMode))
                    .filter { it.seasonNumber == initialSeason.seasonNumber }
            } else emptyList()

            val state = SeriesDetailsUiState(
                series = persistentCachedSeries,
                selectedSeason = initialSeason,
                episodes = initialEpisodes,
                isEpisodesLoading = initialSeason != null && initialEpisodes.isEmpty(),
                isLoading = false
            )
            _uiState.value = state
            saveState(targetId, state, experienceMode)
            if (initialSeason != null && initialEpisodes.isEmpty()) {
                loadEpisodes(targetId, initialSeason.seasonNumber)
            }
        }

        // 3. Network fetch with atomic state synchronization
        detailLoadJob = viewModelScope.launch {
            if (_uiState.value.series == null) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val series = repository.getSeriesById(targetId)
                if (series != null) {
                    val initialSeason = series.seasons.firstOrNull { it.seasonNumber > 0 } ?: series.seasons.firstOrNull()
                    // If user selected a season while network was in flight, preserve their selection
                    val currentSelected = _uiState.value.selectedSeason
                    val targetSeason = if (currentSelected != null && series.seasons.any { it.seasonNumber == currentSelected.seasonNumber }) {
                        series.seasons.first { it.seasonNumber == currentSelected.seasonNumber }
                    } else {
                        initialSeason
                    }
                    val targetSeasonNumber = targetSeason?.seasonNumber ?: 1

                    val episodesForSeason = if (targetSeason != null) {
                        (getCachedEpisodes(targetId, targetSeasonNumber, experienceMode)
                            ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, targetSeasonNumber, variantMode = targetVariantMode) else null)
                            ?: emptyList()).filter { it.seasonNumber == targetSeasonNumber }
                    } else emptyList()

                    val isEpisodesLoading = targetSeason != null && episodesForSeason.isEmpty()
                    val currentSeries = _uiState.value.series
                    val mergedSeries = if (currentSeries != null && (currentSeries.id == series.id || currentSeries.id.contains(series.id) || series.id.contains(currentSeries.id))) {
                        series.copy(
                            sourceUrl = if (!currentSeries.sourceUrl.isNullOrBlank()) currentSeries.sourceUrl else series.sourceUrl,
                            providerId = currentSeries.providerId ?: series.providerId,
                            mode = if (isDubbedExp || currentSeries.mode == "dubbed") "dubbed" else (currentSeries.mode ?: series.mode),
                            sourceCandidates = (currentSeries.sourceCandidates + series.sourceCandidates).distinctBy { "${it.providerId}_${it.sourcePageUrl}" }
                        )
                    } else if (isDubbedExp) {
                        series.copy(mode = "dubbed")
                    } else {
                        series
                    }
                    val newState = _uiState.value.copy(
                        series = mergedSeries,
                        isLoading = false,
                        selectedSeason = targetSeason,
                        episodes = episodesForSeason,
                        isEpisodesLoading = isEpisodesLoading,
                        error = null
                    )
                    _uiState.value = newState
                    saveState(targetId, newState, experienceMode)
                    networkObserverJob?.cancel()

                    if (app != null) {
                        if (targetSeason != null && episodesForSeason.isNotEmpty()) {
                            MediaDetailsCacheManager.saveSeriesDetails(app, mergedSeries, episodesForSeason, targetSeasonNumber, variantMode = targetVariantMode)
                        } else {
                            MediaDetailsCacheManager.saveSeriesDetails(app, mergedSeries, variantMode = targetVariantMode)
                        }
                    }

                    if (targetSeason != null && episodesForSeason.isEmpty()) {
                        loadEpisodes(targetId, targetSeasonNumber)
                    }
                } else if (_uiState.value.series == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId, variantMode = targetVariantMode) else null
                    if (fallback != null) {
                        val initialSeason = fallback.seasons.firstOrNull { it.seasonNumber > 0 } ?: fallback.seasons.firstOrNull()
                        val targetSeasonNumber = initialSeason?.seasonNumber ?: 1
                        val episodesForSeason = if (initialSeason != null) {
                            (getCachedEpisodes(targetId, targetSeasonNumber, experienceMode)
                                ?: MediaDetailsCacheManager.getSeriesEpisodes(app!!, targetId, targetSeasonNumber, variantMode = targetVariantMode))
                                .filter { it.seasonNumber == targetSeasonNumber }
                        } else emptyList()

                        val newState = _uiState.value.copy(
                            series = fallback,
                            isLoading = false,
                            selectedSeason = initialSeason,
                            episodes = episodesForSeason,
                            isEpisodesLoading = initialSeason != null && episodesForSeason.isEmpty(),
                            error = null
                        )
                        _uiState.value = newState
                        saveState(targetId, newState, experienceMode)
                        networkObserverJob?.cancel()

                        if (initialSeason != null && episodesForSeason.isEmpty()) {
                            loadEpisodes(targetId, targetSeasonNumber)
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(targetId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (_uiState.value.series == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getSeriesDetails(app, targetId, variantMode = targetVariantMode) else null
                    if (fallback != null) {
                        val initialSeason = fallback.seasons.firstOrNull { it.seasonNumber > 0 } ?: fallback.seasons.firstOrNull()
                        val targetSeasonNumber = initialSeason?.seasonNumber ?: 1
                        val episodesForSeason = if (initialSeason != null) {
                            (getCachedEpisodes(targetId, targetSeasonNumber, experienceMode)
                                ?: MediaDetailsCacheManager.getSeriesEpisodes(app!!, targetId, targetSeasonNumber, variantMode = targetVariantMode))
                                .filter { it.seasonNumber == targetSeasonNumber }
                        } else emptyList()

                        val newState = _uiState.value.copy(
                            series = fallback,
                            isLoading = false,
                            selectedSeason = initialSeason,
                            episodes = episodesForSeason,
                            isEpisodesLoading = initialSeason != null && episodesForSeason.isEmpty(),
                            error = null
                        )
                        _uiState.value = newState
                        saveState(targetId, newState, experienceMode)
                        networkObserverJob?.cancel()

                        if (initialSeason != null && episodesForSeason.isEmpty()) {
                            loadEpisodes(targetId, targetSeasonNumber)
                        }
                    } else {
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(targetId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun startNetworkAutoReload(seriesId: String) {
        if (networkObserverJob?.isActive == true) return
        val appContext = try { com.example.MyApplication.appContext } catch (_: Throwable) { null } ?: return
        networkObserverJob = viewModelScope.launch {
            val observer = com.example.utils.NetworkConnectivityObserver(appContext)
            observer.observe().collectLatest { isConnected ->
                if (!isConnected) return@collectLatest
                var retryDelayMs = 750L
                // Retry repeatedly while the page is unresolved, even if Android
                // still reports an active network while the gateway is recovering.
                while (isActive && _uiState.value.series == null) {
                    val online = try {
                        NetworkUtils.isInternetAvailable(appContext)
                    } catch (_: Throwable) {
                        false
                    }
                    if (online && detailLoadJob?.isActive != true) {
                        loadSeries(seriesId, forceRefresh = true, experienceMode = currentExperienceMode)
                    }
                    delay(if (online) retryDelayMs else 1500L)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(15_000L)
                }
            }
        }
    }

    fun triggerInitialEpisodesLoad() {
        val currentSeries = _uiState.value.series ?: return
        val currentSeason = _uiState.value.selectedSeason ?: return
        val targetId = if (currentSeries.id.contains("_")) currentSeries.id.substringBeforeLast("_") else currentSeries.id
        val episodesMatchCurrentSeason = _uiState.value.episodes.isNotEmpty() && _uiState.value.episodes.all { it.seasonNumber == currentSeason.seasonNumber }
        if (!episodesMatchCurrentSeason && !_uiState.value.isEpisodesLoading) {
            loadEpisodes(targetId, currentSeason.seasonNumber)
        }
    }

    private fun updateAndCache(
        seriesId: String,
        experienceMode: String = currentExperienceMode,
        transform: (SeriesDetailsUiState) -> SeriesDetailsUiState
    ) {
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        _uiState.update { transform(it) }
        saveState(targetId, _uiState.value, experienceMode)
    }

    fun selectSeason(season: Season) {
        val currentSeries = _uiState.value.series ?: return
        val targetId = if (currentSeries.id.contains("_")) currentSeries.id.substringBeforeLast("_") else currentSeries.id
        val app = try { AppContainer.application } catch (_: Exception) { null }
        val variantMode = if (currentExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || currentExperienceMode.equals("DUBBED", ignoreCase = true)) "dubbed" else "subtitled"

        // Cancel previous episode loading job to prevent race conditions (Part 10)
        episodeLoadingJob?.cancel()

        // Check if episodes for this season are already cached and validate season identity (Part 8, Part 9)
        val cachedForSeason = (getCachedEpisodes(targetId, season.seasonNumber, currentExperienceMode)
            ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, season.seasonNumber, variantMode = variantMode) else null)
            ?: emptyList()).filter { it.seasonNumber == season.seasonNumber }

        val isCached = cachedForSeason.isNotEmpty()

        // Deterministic state transition (Part 5):
        // 1. selectedSeason = season
        // 2. episodes = cachedForSeason (if available) OR emptyList() (immediately invalidating old season episodes)
        // 3. isEpisodesLoading = !isCached
        updateAndCache(targetId, currentExperienceMode) {
            it.copy(
                selectedSeason = season,
                episodes = cachedForSeason,
                isEpisodesLoading = !isCached,
                visibleEpisodesCount = 10,
                isLoadingMore = false
            )
        }

        // Fetch fresh episodes if not cached
        if (!isCached) {
            loadEpisodes(targetId, season.seasonNumber)
        }
    }

    fun loadMoreEpisodes(context: android.content.Context) {
        val currentSeries = _uiState.value.series ?: return
        val targetId = if (currentSeries.id.contains("_")) currentSeries.id.substringBeforeLast("_") else currentSeries.id
        if (_uiState.value.isLoadingMore) return
        
        viewModelScope.launch {
            updateAndCache(targetId, currentExperienceMode) { it.copy(isLoadingMore = true) }
            
            if (!NetworkUtils.isInternetAvailable(context)) {
                delay(300)
                updateAndCache(targetId, currentExperienceMode) { it.copy(isLoadingMore = false, error = context.getString(com.example.R.string.no_internet_load_episodes)) }
                return@launch
            }
            
            delay(350)
            updateAndCache(targetId, currentExperienceMode) { it.copy(visibleEpisodesCount = it.visibleEpisodesCount + 10, isLoadingMore = false) }
        }
    }

    fun loadEpisodesForSeason(seriesId: String, seasonNumber: Int) {
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        loadEpisodes(targetId, seasonNumber)
    }

    private fun loadEpisodes(seriesId: String, seasonNumber: Int) {
        val app = try { AppContainer.application } catch (_: Exception) { null }
        val targetId = if (seriesId.contains("_")) seriesId.substringBeforeLast("_") else seriesId
        val variantMode = if (currentExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || currentExperienceMode.equals("DUBBED", ignoreCase = true)) "dubbed" else "subtitled"

        episodeLoadingJob?.cancel()
        episodeLoadingJob = viewModelScope.launch {
            // Invalidate episodes if active state has mismatching season episodes (Part 5 & Part 11)
            val currentSelectedSeason = _uiState.value.selectedSeason
            if (currentSelectedSeason?.seasonNumber == seasonNumber) {
                val hasMismatch = _uiState.value.episodes.any { it.seasonNumber != seasonNumber }
                if (hasMismatch || _uiState.value.episodes.isEmpty()) {
                    updateAndCache(targetId, currentExperienceMode) {
                        it.copy(
                            episodes = if (hasMismatch) emptyList() else it.episodes,
                            isEpisodesLoading = true,
                            visibleEpisodesCount = 10
                        )
                    }
                }
            }
            try {
                val rawEpisodes = repository.getSeasonEpisodes(targetId, seasonNumber)
                // Validate episode season identity (Part 9)
                val episodes = rawEpisodes.map {
                    if (it.seasonNumber == seasonNumber) it else it.copy(seasonNumber = seasonNumber)
                }

                if (episodes.isNotEmpty()) {
                    saveCachedEpisodes(targetId, seasonNumber, episodes, currentExperienceMode)
                    if (app != null) {
                        MediaDetailsCacheManager.saveSeriesEpisodes(app, targetId, seasonNumber, episodes, variantMode = variantMode)
                        val curr = _uiState.value.series
                        if (curr != null) {
                            MediaDetailsCacheManager.saveSeriesDetails(app, curr, episodes, seasonNumber, variantMode = variantMode)
                        }
                    }
                    // Validate: Only update active UI state if selected season is STILL this seasonNumber! (Part 10)
                    if (_uiState.value.selectedSeason?.seasonNumber == seasonNumber) {
                        updateAndCache(targetId, currentExperienceMode) {
                            it.copy(
                                episodes = episodes,
                                isEpisodesLoading = false
                            )
                        }
                    }
                } else {
                    val fallback = (getCachedEpisodes(targetId, seasonNumber, currentExperienceMode)
                        ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, seasonNumber, variantMode = variantMode) else null)
                        ?: emptyList()).filter { it.seasonNumber == seasonNumber }
                    if (_uiState.value.selectedSeason?.seasonNumber == seasonNumber) {
                        updateAndCache(targetId, currentExperienceMode) {
                            it.copy(
                                episodes = fallback,
                                isEpisodesLoading = false
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                val fallback = (getCachedEpisodes(targetId, seasonNumber, currentExperienceMode)
                    ?: (if (app != null) MediaDetailsCacheManager.getSeriesEpisodes(app, targetId, seasonNumber, variantMode = variantMode) else null)
                    ?: emptyList()).filter { it.seasonNumber == seasonNumber }
                if (_uiState.value.selectedSeason?.seasonNumber == seasonNumber) {
                    updateAndCache(targetId, currentExperienceMode) {
                        it.copy(
                            episodes = fallback,
                            isEpisodesLoading = false
                        )
                    }
                }
            }
        }
    }
}
