package com.example.ui.screens.anime

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Genre
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import com.example.utils.NetworkConnectivityObserver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

data class AnimeUiState(
    val isLoading: Boolean = true,
    val trendingAnime: List<Series> = emptyList(),
    val newEpisodes: List<Series> = emptyList(),
    val series: List<Series> = emptyList(),
    val upcomingAnime: List<Series> = emptyList(),
    val availableGenres: List<Genre> = emptyList(),
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val genreAnime: List<Series> = emptyList(),
    val isGenreLoading: Boolean = false,
    val error: String? = null
)

class AnimeViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(AnimeUiState())
    val uiState: StateFlow<AnimeUiState> = _uiState.asStateFlow()


    private var selectedGenrePageId: Int? = null

    val catalogPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getPopularMoviesPage(it) },
        fetchSeriesPage = { repository.getPopularSeriesPage(it) },
        fetchAnimePage = { repository.getPopularAnimePage(it) },
        coroutineScope = viewModelScope
    )
    val newReleasesPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getNewReleasesMoviesPage(it) },
        fetchSeriesPage = { repository.getNewReleasesSeriesPage(it) },
        fetchAnimePage = { repository.getNewReleasesAnimePage(it) },
        coroutineScope = viewModelScope
    )
    val genrePaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { page -> selectedGenrePageId?.let { repository.getMoviesByGenrePage(it, page) }
            ?: repository.getPopularMoviesPage(page) },
        fetchSeriesPage = { page -> selectedGenrePageId?.let { repository.getSeriesByGenrePage(it, page) }
            ?: repository.getPopularSeriesPage(page) },
        fetchAnimePage = { page -> selectedGenrePageId?.let { repository.getAnimeByGenrePage(it, page) }
            ?: repository.getPopularAnimePage(page) },
        coroutineScope = viewModelScope
    )

    private var loadJob: Job? = null
    private var genreJob: Job? = null
    private var initialLoadStarted = false

    init {
        viewModelScope.launch {
            genrePaginator.getTabStateFlow(3).collect { pageState ->
                val pageItems = pageState.items.mapNotNull { pair -> (pair.first as? Series) }
                _uiState.update { state ->
                    if (state.selectedGenreId == selectedGenrePageId) state.copy(
                        genreAnime = pageItems,
                        isGenreLoading = pageState.isLoadingFirstPage
                    ) else state
                }
            }
        }
        connectivityObserver?.let { observer ->
            viewModelScope.launch {
                var wasOffline = false
                observer.observe()
                    .distinctUntilChanged()
                    .collect { isConnected ->
                        if (!isConnected) {
                            wasOffline = true
                        } else if (wasOffline) {
                            wasOffline = false
                            if (initialLoadStarted) {
                                loadData(forceRefresh = true)
                                loadGenres()
                            }
                        }
                    }
            }
        }
    }

    fun ensureLoaded() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        loadData()
        loadGenres()
    }

    fun loadGenres() {
        viewModelScope.launch {
            try {
                val genres = repository.getAnimeGenres()
                _uiState.update { it.copy(availableGenres = genres) }
            } catch (_: Exception) { }
        }
    }

    fun selectGenre(genre: Genre?) {
        selectedGenrePageId = genre?.id
        if (genre == null) {
            genrePaginator.resetTab(3)
            catalogPaginator.ensureTabLoaded(3)
            _uiState.update {
                it.copy(
                    selectedGenreId = null,
                    selectedGenreName = null,
                    genreAnime = emptyList(),
                    isGenreLoading = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                selectedGenreId = genre.id,
                selectedGenreName = genre.name,
                genreAnime = emptyList(),
                isGenreLoading = true
            )
        }
        genrePaginator.resetTab(3)
        genrePaginator.loadFirstPage(3)
    }

    private fun handleLoadError(e: Throwable) {
        val state = _uiState.value
        val hasData = state.series.isNotEmpty() || state.trendingAnime.isNotEmpty()
        if (!hasData) {
            _uiState.update { it.copy(error = e.message ?: "Failed to load anime", isLoading = false) }
        }
    }

    fun loadData(forceRefresh: Boolean = false) {
        initialLoadStarted = true
        val hasExistingData = _uiState.value.series.isNotEmpty() || _uiState.value.trendingAnime.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Preserves existing cached anime on refresh without destructive clearCache()
            try {
                supervisorScope {
                    launch {
                        repository.getTrendingAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(trendingAnime = list, isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getNewReleasesAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(newEpisodes = list, isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getAnimeSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(series = list, isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getUpcomingAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(upcomingAnime = list, isLoading = false, error = null) }
                        }
                    }
                }
            } catch (e: Exception) {
                handleLoadError(e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
