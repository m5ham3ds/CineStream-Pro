package com.example.ui.screens.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.di.AppContainer
import com.example.domain.models.Movie
import com.example.domain.models.VideoTrailer
import com.example.data.util.TrailerSelectionHelper
import com.example.domain.repository.MediaRepository
import com.example.utils.MediaDetailsCacheManager
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

data class MovieDetailsUiState(
    val isLoading: Boolean = false,
    val movie: Movie? = null,
    val similarMovies: List<Movie> = emptyList(),
    val error: String? = null,
    val primaryTrailer: VideoTrailer? = null,
    val primaryTrailerRequestKey: String? = null,
    val isPrimaryTrailerLoaded: Boolean = false,
    val otherTrailers: List<VideoTrailer> = emptyList(),
    val otherTrailersLoadedFor: String? = null,
    val isLoadingOtherTrailers: Boolean = false,
    val areOtherTrailersExpanded: Boolean = false
)

class MovieDetailsViewModel(
    private val repository: MediaRepository
) : ViewModel() {
    companion object {
        private val cache = java.util.concurrent.ConcurrentHashMap<String, MovieDetailsUiState>()
        private fun localizedKey(id: String, experienceMode: String): String {
            val language = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
            return "${id}_${language}_$experienceMode"
        }

        fun getCachedState(movieId: String, experienceMode: String = "STANDARD"): MovieDetailsUiState? {
            val ids = linkedSetOf(movieId)
            runCatching { ids.add(java.net.URLDecoder.decode(movieId, "UTF-8")) }
            runCatching { ids.add(java.net.URLEncoder.encode(movieId, "UTF-8")) }
            return ids.firstNotNullOfOrNull { cache[localizedKey(it, experienceMode)] }
        }

        fun saveState(movieId: String, state: MovieDetailsUiState, experienceMode: String = "STANDARD") {
            val ids = linkedSetOf(movieId)
            runCatching { ids.add(java.net.URLEncoder.encode(movieId, "UTF-8")) }
            runCatching { ids.add(java.net.URLDecoder.decode(movieId, "UTF-8")) }
            ids.forEach { cache[localizedKey(it, experienceMode)] = state }
        }
    }

    private val _uiState = MutableStateFlow(MovieDetailsUiState())
    val uiState: StateFlow<MovieDetailsUiState> = _uiState.asStateFlow()
    private var networkObserverJob: Job? = null
    private var detailLoadJob: Job? = null
    private var activeMovieRequestKey: String? = null
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
            val videos = repository.getTrailers(mediaId, isMovie = true, language = locale, primaryOnly = true)
            val primary = TrailerSelectionHelper.selectPrimaryTrailer(videos, preferredLanguage = preferred)
            _uiState.update { state ->
                if (state.movie?.id?.toIntOrNull() == mediaId.toIntOrNull()) {
                    state.copy(
                        primaryTrailer = primary,
                        primaryTrailerRequestKey = key,
                        isPrimaryTrailerLoaded = true
                    )
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
            val videos = repository.getTrailers(mediaId, isMovie = true, language = locale, primaryOnly = false)
            val primaryKey = _uiState.value.primaryTrailer?.key
            val others = videos
                .filter { it.type.equals("Trailer", ignoreCase = true) }
                .filterNot { it.key == primaryKey }
                .distinctBy { it.key }
            _uiState.update { state ->
                if (state.movie?.id?.toIntOrNull() == mediaId.toIntOrNull()) {
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

    fun loadMovie(movieId: String, forceRefresh: Boolean = false, experienceMode: String = "STANDARD") {
        val requestKey = "$movieId|$experienceMode"
        if (activeMovieRequestKey == requestKey && detailLoadJob?.isActive == true) return
        if (activeMovieRequestKey != null && activeMovieRequestKey != requestKey) {
            detailLoadJob?.cancel()
            networkObserverJob?.cancel()
            primaryTrailerJob?.cancel()
            otherTrailersJob?.cancel()
            _uiState.value = MovieDetailsUiState()
        }
        activeMovieRequestKey = requestKey
        currentExperienceMode = experienceMode
        val cached = getCachedState(movieId, experienceMode)
        if (cached != null && !forceRefresh) {
            _uiState.value = cached
        }

        val isDubbedExp = experienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || experienceMode.equals("DUBBED", ignoreCase = true)
        val targetVariantMode = if (isDubbedExp) "dubbed" else "subtitled"

        val app = try { AppContainer.application } catch (_: Exception) { null }
        val cachedMovie = if (app != null) {
            MediaDetailsCacheManager.getMovieDetails(app, movieId, variantMode = targetVariantMode)
                ?: try { MediaDetailsCacheManager.getMovieDetails(app, java.net.URLDecoder.decode(movieId, "UTF-8"), variantMode = targetVariantMode) } catch (_: Exception) { null }
                ?: try { MediaDetailsCacheManager.getMovieDetails(app, java.net.URLEncoder.encode(movieId, "UTF-8"), variantMode = targetVariantMode) } catch (_: Exception) { null }
        } else null
        if (cachedMovie != null && _uiState.value.movie == null) {
            _uiState.update { it.copy(movie = cachedMovie, isLoading = false, error = null) }
        }

        detailLoadJob = viewModelScope.launch {
            if (_uiState.value.movie == null) {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            try {
                val movie = repository.getMovieById(movieId)
                if (movie != null) {
                    val currentMovie = _uiState.value.movie
                    val mergedMovie = if (currentMovie != null && (currentMovie.id == movie.id || currentMovie.id.contains(movie.id) || movie.id.contains(currentMovie.id))) {
                        movie.copy(
                            sourceUrl = if (!currentMovie.sourceUrl.isNullOrBlank()) currentMovie.sourceUrl else movie.sourceUrl,
                            providerId = currentMovie.providerId ?: movie.providerId,
                            mode = if (isDubbedExp || currentMovie.mode == "dubbed") "dubbed" else (currentMovie.mode ?: movie.mode),
                            sourceCandidates = (currentMovie.sourceCandidates + movie.sourceCandidates).distinctBy { "${it.providerId}_${it.sourcePageUrl}" }
                        )
                    } else if (isDubbedExp) {
                        movie.copy(mode = "dubbed")
                    } else {
                        movie
                    }
                    if (app != null) {
                        MediaDetailsCacheManager.saveMovieDetails(app, mergedMovie, variantMode = targetVariantMode)
                    }
                    val newState = _uiState.value.copy(movie = mergedMovie, isLoading = false, error = null)
                    _uiState.value = newState
                    saveState(movieId, newState, experienceMode)
                    networkObserverJob?.cancel()
                } else if (_uiState.value.movie == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getMovieDetails(app, movieId, variantMode = targetVariantMode) else null
                    if (fallback != null) {
                        val newState = _uiState.value.copy(movie = fallback, isLoading = false, error = null)
                        _uiState.value = newState
                        saveState(movieId, newState, experienceMode)
                        networkObserverJob?.cancel()
                    } else {
                        // Keep loading skeleton active and wait for internet connection
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(movieId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (_uiState.value.movie == null) {
                    val fallback = if (app != null) MediaDetailsCacheManager.getMovieDetails(app, movieId, variantMode = targetVariantMode) else null
                    if (fallback != null) {
                        val newState = _uiState.value.copy(movie = fallback, isLoading = false, error = null)
                        _uiState.value = newState
                        saveState(movieId, newState, experienceMode)
                        networkObserverJob?.cancel()
                    } else {
                        // Keep loading skeleton active and wait for internet connection
                        _uiState.update { it.copy(isLoading = true, error = null) }
                        startNetworkAutoReload(movieId)
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private fun startNetworkAutoReload(movieId: String) {
        if (networkObserverJob?.isActive == true) return
        val appContext = try { com.example.MyApplication.appContext } catch (_: Throwable) { null } ?: return
        networkObserverJob = viewModelScope.launch {
            val observer = com.example.utils.NetworkConnectivityObserver(appContext)
            observer.observe().collectLatest { isConnected ->
                if (!isConnected) return@collectLatest
                var retryDelayMs = 750L
                // Keep retrying while details are unresolved. Connectivity can remain
                // "connected" while DNS/TMDB/Worker is temporarily unreachable, so a
                // single network-state transition is not a sufficient retry trigger.
                while (isActive && _uiState.value.movie == null) {
                    val online = try {
                        com.example.utils.NetworkUtils.isInternetAvailable(appContext)
                    } catch (_: Throwable) {
                        false
                    }
                    if (online && detailLoadJob?.isActive != true) {
                        loadMovie(movieId, forceRefresh = true, experienceMode = currentExperienceMode)
                    }
                    delay(if (online) retryDelayMs else 1500L)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(15_000L)
                }
            }
        }
    }
}