package com.example.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Genre
import com.example.domain.models.Movie
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

data class HomeUiState(
    val isLoading: Boolean = true,
    val trendingMovies: List<Movie> = emptyList(),
    val trendingSeries: List<Series> = emptyList(),
    val actionMovies: List<Movie> = emptyList(),
    val allMovies: List<Movie> = emptyList(),
    val allSeries: List<Series> = emptyList(),
    val animeSeries: List<Series> = emptyList(),
    val trendingAnime: List<Series> = emptyList(),
    val popularMovies: List<Movie> = emptyList(),
    val popularSeries: List<Series> = emptyList(),
    val popularAnime: List<Series> = emptyList(),
    val upcomingSeries: List<Series> = emptyList(),
    val upcomingAnime: List<Series> = emptyList(),
    val newReleasesAnime: List<Series> = emptyList(),
    val upcomingMovies: List<Movie> = emptyList(),
    val newReleasesMovies: List<Movie> = emptyList(),
    val newReleasesSeries: List<Series> = emptyList(),
    val arabicMovies: List<Movie> = emptyList(),
    val arabicSeries: List<Series> = emptyList(),
    val error: String? = null
)

class HomeViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Phase 05Y-C: Real TMDB Paginated List Controllers
    val trendingPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getTrendingMoviesPage(it) },
        fetchSeriesPage = { repository.getTrendingSeriesPage(it) },
        fetchAnimePage = { repository.getTrendingAnimePage(it) },
        coroutineScope = viewModelScope
    )

    val popularPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getPopularMoviesPage(it) },
        fetchSeriesPage = { repository.getPopularSeriesPage(it) },
        fetchAnimePage = { repository.getPopularAnimePage(it) },
        coroutineScope = viewModelScope
    )

    val upcomingPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getUpcomingMoviesPage(it) },
        fetchSeriesPage = { repository.getUpcomingSeriesPage(it) },
        fetchAnimePage = { repository.getUpcomingAnimePage(it) },
        coroutineScope = viewModelScope
    )

    val newReleasesPaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getNewReleasesMoviesPage(it) },
        fetchSeriesPage = { repository.getNewReleasesSeriesPage(it) },
        fetchAnimePage = { repository.getNewReleasesAnimePage(it) },
        coroutineScope = viewModelScope
    )


    // Home's genre grids have independent server-filtered cursors so choosing a genre
    // never falls through to unrelated Popular/Trending records.
    private var homeMovieGenreId: Int? = null
    private var homeSeriesGenreId: Int? = null
    private var homeAnimeGenreId: Int? = null

    val movieGenrePaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { page -> homeMovieGenreId?.let { repository.getMoviesByGenrePage(it, page) }
            ?: repository.getPopularMoviesPage(page) },
        fetchSeriesPage = { repository.getPopularSeriesPage(it) },
        fetchAnimePage = { repository.getPopularAnimePage(it) },
        coroutineScope = viewModelScope
    )
    val seriesGenrePaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getPopularMoviesPage(it) },
        fetchSeriesPage = { page -> homeSeriesGenreId?.let { repository.getSeriesByGenrePage(it, page) }
            ?: repository.getPopularSeriesPage(page) },
        fetchAnimePage = { repository.getPopularAnimePage(it) },
        coroutineScope = viewModelScope
    )
    val animeGenrePaginator = com.example.data.repository.TmdbListPaginator(
        fetchMoviesPage = { repository.getPopularMoviesPage(it) },
        fetchSeriesPage = { repository.getPopularSeriesPage(it) },
        fetchAnimePage = { page -> homeAnimeGenreId?.let { repository.getAnimeByGenrePage(it, page) }
            ?: repository.getPopularAnimePage(page) },
        coroutineScope = viewModelScope
    )

    fun selectHomeMovieGenre(genre: Genre?) {
        homeMovieGenreId = genre?.id
        movieGenrePaginator.resetTab(1)
        if (genre == null) popularPaginator.ensureTabLoaded(1) else movieGenrePaginator.loadFirstPage(1)
    }

    fun selectHomeSeriesGenre(genre: Genre?) {
        homeSeriesGenreId = genre?.id
        seriesGenrePaginator.resetTab(2)
        if (genre == null) popularPaginator.ensureTabLoaded(2) else seriesGenrePaginator.loadFirstPage(2)
    }

    fun selectHomeAnimeGenre(genre: Genre?) {
        homeAnimeGenreId = genre?.id
        animeGenrePaginator.resetTab(3)
        if (genre == null) popularPaginator.ensureTabLoaded(3) else animeGenrePaginator.loadFirstPage(3)
    }

    private var loadJob: Job? = null
    private var initialLoadStarted = false

    init {
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
                                trendingPaginator.retryFailedTabs()
                                popularPaginator.retryFailedTabs()
                                upcomingPaginator.retryFailedTabs()
                                newReleasesPaginator.retryFailedTabs()
                            }
                        }
                    }
            }
        }
    }

    private fun handleLoadError(e: Throwable) {
        val state = _uiState.value
        val hasData = state.trendingMovies.isNotEmpty() || state.allMovies.isNotEmpty()
        if (!hasData) {
            val isOfflineOrCacheMiss = e is com.example.domain.models.TmdbException.NetworkException ||
                    e is java.io.IOException ||
                    !com.example.utils.NetworkUtils.isInternetAvailable(com.example.MyApplication.appContext)
            _uiState.update { 
                it.copy(
                    error = if (isOfflineOrCacheMiss) null else (e.message ?: "Failed to load content"),
                    isLoading = false
                ) 
            }
        }
    }

    fun ensureLoaded() {
        if (initialLoadStarted) return
        initialLoadStarted = true
        loadData()
    }

    fun loadData(forceRefresh: Boolean = false) {
        initialLoadStarted = true
        val hasExistingData = _uiState.value.trendingMovies.isNotEmpty() || _uiState.value.allMovies.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Preserves existing cached data on refresh without destructive clearCache()
            try {
                supervisorScope {
                    launch {
                        repository.getTrendingMovies().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(trendingMovies = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getAnimeSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(animeSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getTrendingSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(trendingSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getMovies().catch { handleLoadError(it) }.collect { list ->
                            val action = list.filter { m -> m.genreIds.contains(28) || m.genres.any { name -> name.equals("Action", ignoreCase = true) || name.equals("أكشن", ignoreCase = true) } }
                            _uiState.update {
                                it.copy(
                                    allMovies = list.take(15),
                                    popularMovies = list.take(15),
                                    actionMovies = action.take(15),
                                    isLoading = false,
                                    error = null
                                )
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingMovies().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(upcomingMovies = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getNewReleasesMovies().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(newReleasesMovies = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getNewReleasesSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(newReleasesSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getTrendingAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(trendingAnime = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update {
                                it.copy(
                                    allSeries = list.take(15),
                                    popularSeries = list.take(15),
                                    isLoading = false,
                                    error = null
                                )
                            }
                        }
                    }
                    launch {
                        repository.getUpcomingSeries().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(upcomingSeries = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getUpcomingAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(upcomingAnime = list.take(15), isLoading = false, error = null) }
                        }
                    }
                    launch {
                        repository.getNewReleasesAnime().catch { handleLoadError(it) }.collect { list ->
                            _uiState.update { it.copy(newReleasesAnime = list.take(15), isLoading = false, error = null) }
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
