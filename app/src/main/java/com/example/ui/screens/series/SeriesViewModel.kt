package com.example.ui.screens.series

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

data class SeriesUiState(
    val isLoading: Boolean = true,
    val trendingSeries: List<Series> = emptyList(),
    val newEpisodes: List<Series> = emptyList(),
    val series: List<Series> = emptyList(),
    val upcomingSeries: List<Series> = emptyList(),
    val availableGenres: List<Genre> = emptyList(),
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val genreSeries: List<Series> = emptyList(),
    val isGenreLoading: Boolean = false,
    val error: String? = null
)

class SeriesViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(SeriesUiState())
    val uiState: StateFlow<SeriesUiState> = _uiState.asStateFlow()


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
            genrePaginator.getTabStateFlow(2).collect { pageState ->
                val pageItems = pageState.items.mapNotNull { pair -> (pair.first as? Series) }
                _uiState.update { state ->
                    if (state.selectedGenreId == selectedGenrePageId) state.copy(
                        genreSeries = pageItems,
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
                                loadSeries(forceRefresh = true)
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
        loadSeries()
        loadGenres()
    }

    fun loadGenres() {
        viewModelScope.launch {
            try {
                val genres = repository.getTvGenres()
                _uiState.update { it.copy(availableGenres = genres) }
            } catch (_: Exception) { }
        }
    }

    fun selectGenre(genre: Genre?) {
        selectedGenrePageId = genre?.id
        if (genre == null) {
            genrePaginator.resetTab(2)
            catalogPaginator.ensureTabLoaded(2)
            _uiState.update {
                it.copy(
                    selectedGenreId = null,
                    selectedGenreName = null,
                    genreSeries = emptyList(),
                    isGenreLoading = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                selectedGenreId = genre.id,
                selectedGenreName = genre.name,
                genreSeries = emptyList(),
                isGenreLoading = true
            )
        }
        genrePaginator.resetTab(2)
        genrePaginator.loadFirstPage(2)
    }

    fun loadSeries(forceRefresh: Boolean = false) {
        initialLoadStarted = true
        val hasExistingData = _uiState.value.series.isNotEmpty() || _uiState.value.trendingSeries.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // TV series, new releases and upcoming series each retain their own TMDB query.
            // Arabic-language discovery is a separate facet and is never appended to these feeds.
            var trendingList: List<Series> = emptyList()
            var newReleasesList: List<Series> = emptyList()
            var allSeriesList: List<Series> = emptyList()
            var upcomingList: List<Series> = emptyList()
            var gotTrending = false
            var gotNewReleases = false
            var gotAll = false
            var gotUpcoming = false

            fun updateCombined() {
                val sortedTrending = trendingList.distinctBy { it.id }
                val sortedNewReleases = newReleasesList.distinctBy { it.id }
                val sortedAllSeries = allSeriesList.distinctBy { it.id }
                val sortedUpcoming = upcomingList.distinctBy { it.id }
                val hasResult = gotTrending || gotNewReleases || gotAll || gotUpcoming
                val hasData = sortedTrending.isNotEmpty() || sortedAllSeries.isNotEmpty() ||
                    sortedNewReleases.isNotEmpty() || sortedUpcoming.isNotEmpty()
                _uiState.update {
                    it.copy(
                        trendingSeries = if (gotTrending) sortedTrending else it.trendingSeries,
                        newEpisodes = if (gotNewReleases) sortedNewReleases else it.newEpisodes,
                        series = if (gotAll) sortedAllSeries else it.series,
                        upcomingSeries = if (gotUpcoming) sortedUpcoming else it.upcomingSeries,
                        isLoading = if (hasResult) false else it.isLoading,
                        error = if (hasData) null else it.error
                    )
                }
            }

            fun handleError(e: Throwable) {
                val state = _uiState.value
                val hasData = state.series.isNotEmpty() || state.trendingSeries.isNotEmpty()
                if (!hasData) {
                    _uiState.update { it.copy(error = e.message ?: "Failed to load series", isLoading = false) }
                }
            }

            try {
                supervisorScope {
                    launch {
                        repository.getTrendingSeries().catch { handleError(it) }.collect { list ->
                            trendingList = list
                            gotTrending = true
                            updateCombined()
                        }
                    }
                    launch {
                        repository.getNewReleasesSeries().catch { handleError(it) }.collect { list ->
                            newReleasesList = list
                            gotNewReleases = true
                            updateCombined()
                        }
                    }
                    launch {
                        repository.getSeries().catch { handleError(it) }.collect { list ->
                            allSeriesList = list
                            gotAll = true
                            updateCombined()
                        }
                    }
                    launch {
                        repository.getUpcomingSeries().catch { handleError(it) }.collect { list ->
                            upcomingList = list
                            gotUpcoming = true
                            updateCombined()
                        }
                    }
                }
            } catch (e: Exception) {
                handleError(e)
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
