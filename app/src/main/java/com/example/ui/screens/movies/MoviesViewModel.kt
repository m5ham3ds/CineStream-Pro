package com.example.ui.screens.movies

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.domain.models.Genre
import com.example.domain.models.Movie
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

data class MoviesUiState(
    val isLoading: Boolean = true,
    val trendingMovies: List<Movie> = emptyList(),
    val newReleasesMovies: List<Movie> = emptyList(),
    val movies: List<Movie> = emptyList(),
    val upcomingMovies: List<Movie> = emptyList(),
    val availableGenres: List<Genre> = emptyList(),
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val genreMovies: List<Movie> = emptyList(),
    val isGenreLoading: Boolean = false,
    val error: String? = null
)

class MoviesViewModel(
    private val repository: MediaRepository,
    private val connectivityObserver: NetworkConnectivityObserver? = null
) : ViewModel() {
    private val _uiState = MutableStateFlow(MoviesUiState())
    val uiState: StateFlow<MoviesUiState> = _uiState.asStateFlow()


    private var selectedGenrePageId: Int? = null

    // Dedicated pagers keep the catalogue, releases and selected-genre query isolated.
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
            genrePaginator.getTabStateFlow(1).collect { pageState ->
                val pageItems = pageState.items.mapNotNull { pair -> (pair.first as? Movie) }
                _uiState.update { state ->
                    if (state.selectedGenreId == selectedGenrePageId) state.copy(
                        genreMovies = pageItems,
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
                                loadMovies(forceRefresh = true)
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
        loadMovies()
        loadGenres()
    }

    fun loadGenres() {
        viewModelScope.launch {
            try {
                val genres = repository.getMovieGenres()
                _uiState.update { it.copy(availableGenres = genres) }
            } catch (_: Exception) { }
        }
    }

    fun selectGenre(genre: Genre?) {
        selectedGenrePageId = genre?.id
        if (genre == null) {
            genrePaginator.resetTab(1)
            catalogPaginator.ensureTabLoaded(1)
            _uiState.update {
                it.copy(
                    selectedGenreId = null,
                    selectedGenreName = null,
                    genreMovies = emptyList(),
                    isGenreLoading = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                selectedGenreId = genre.id,
                selectedGenreName = genre.name,
                genreMovies = emptyList(),
                isGenreLoading = true
            )
        }
        genrePaginator.resetTab(1)
        genrePaginator.loadFirstPage(1)
    }

    fun loadMovies(forceRefresh: Boolean = false) {
        initialLoadStarted = true
        val hasExistingData = _uiState.value.movies.isNotEmpty() || _uiState.value.trendingMovies.isNotEmpty()
        if (!hasExistingData || forceRefresh) {
            _uiState.update { it.copy(isLoading = true, error = null) }
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // Keep each TMDB category independent; Arabic-language discovery is not a source
            // for trending, popular, new-release, or upcoming categories.
            var trendingList: List<Movie> = emptyList()
            var newReleasesList: List<Movie> = emptyList()
            var allMoviesList: List<Movie> = emptyList()
            var upcomingList: List<Movie> = emptyList()
            var gotTrending = false
            var gotNewReleases = false
            var gotAll = false
            var gotUpcoming = false

            fun updateCombined() {
                val sortedTrending = trendingList.distinctBy { it.id }
                val sortedNewReleases = newReleasesList.distinctBy { it.id }
                val sortedAllMovies = allMoviesList.distinctBy { it.id }
                val sortedUpcoming = upcomingList.distinctBy { it.id }
                val hasResult = gotTrending || gotNewReleases || gotAll || gotUpcoming
                val hasData = sortedTrending.isNotEmpty() || sortedAllMovies.isNotEmpty() ||
                    sortedNewReleases.isNotEmpty() || sortedUpcoming.isNotEmpty()
                _uiState.update {
                    it.copy(
                        trendingMovies = if (gotTrending) sortedTrending else it.trendingMovies,
                        newReleasesMovies = if (gotNewReleases) sortedNewReleases else it.newReleasesMovies,
                        movies = if (gotAll) sortedAllMovies else it.movies,
                        upcomingMovies = if (gotUpcoming) sortedUpcoming else it.upcomingMovies,
                        isLoading = if (hasResult) false else it.isLoading,
                        error = if (hasData) null else it.error
                    )
                }
            }

            fun handleError(e: Throwable) {
                val state = _uiState.value
                val hasData = state.movies.isNotEmpty() || state.trendingMovies.isNotEmpty()
                if (!hasData) {
                    _uiState.update { it.copy(error = e.message ?: "Failed to load movies", isLoading = false) }
                }
            }

            try {
                supervisorScope {
                    launch {
                        repository.getTrendingMovies().catch { handleError(it) }.collect { list ->
                            trendingList = list
                            gotTrending = true
                            updateCombined()
                        }
                    }
                    launch {
                        repository.getNewReleasesMovies().catch { handleError(it) }.collect { list ->
                            newReleasesList = list
                            gotNewReleases = true
                            updateCombined()
                        }
                    }
                    launch {
                        repository.getMovies().catch { handleError(it) }.collect { list ->
                            allMoviesList = list
                            gotAll = true
                            updateCombined()
                        }
                    }
                    launch {
                        repository.getUpcomingMovies().catch { handleError(it) }.collect { list ->
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
