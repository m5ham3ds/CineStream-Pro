package com.example.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.util.ContentTypeResolver
import com.example.domain.models.Genre
import com.example.domain.models.MediaVariantMode
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.repository.MediaRepository
import com.example.domain.search.MediaTitleMatcher
import com.example.domain.search.SearchTriggerParser
import com.example.di.AppContainer
import com.example.domain.models.CoverageStatus
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.SearchMediaItem
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.example.extension.managed.trace.Phase05HLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuickSearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val movieResults: List<Movie> = emptyList(),
    val seriesResults: List<Series> = emptyList(),
    val isDubbedIntent: Boolean = false
)

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val movieResults: List<Movie> = emptyList(),
    val seriesResults: List<Series> = emptyList(),
    val trendingNow: List<Movie> = emptyList(),
    val selectedContentType: String = "ALL", // "ALL", "MOVIE", "TV", "ANIME"
    val selectedGenreId: Int? = null,
    val selectedGenreName: String? = null,
    val movieGenres: List<Genre> = emptyList(),
    val tvGenres: List<Genre> = emptyList(),
    val animeGenres: List<Genre> = emptyList(),
    val isDubbedIntent: Boolean = false
) {
    val filteredMovieResults: List<Movie>
        get() {
            if (selectedContentType == "TV") return emptyList()
            var list = movieResults
            if (selectedContentType == "ANIME") {
                list = list.filter { m ->
                    val isAnimation = m.genreIds.contains(16) || m.genres.any { it.contains("Animation", ignoreCase = true) || it.contains("رسوم متحركة") || it.contains("أنمي") }
                    val isJapanese = m.language == "ja" || m.country == "JP" || m.genres.any { it.contains("أنمي", ignoreCase = true) || it.contains("Anime", ignoreCase = true) }
                    isAnimation && isJapanese
                }
            }
            if (selectedGenreId == null) return list
            val gid = selectedGenreId
            val gname = selectedGenreName
            return list.filter { m ->
                m.genreIds.contains(gid) || (gname != null && m.genres.any { it.equals(gname, ignoreCase = true) })
            }
        }

    val filteredSeriesResults: List<Series>
        get() {
            if (selectedContentType == "MOVIE") return emptyList()
            var list = seriesResults
            if (selectedContentType == "TV") {
                list = list.filter { ContentTypeResolver.resolveSeries(it) != com.example.data.model.ContentType.ANIME }
            } else if (selectedContentType == "ANIME") {
                list = list.filter { ContentTypeResolver.resolveSeries(it) == com.example.data.model.ContentType.ANIME }
            }
            if (selectedGenreId == null) return list
            val gid = selectedGenreId
            val gname = selectedGenreName
            return list.filter { s ->
                s.genreIds.contains(gid) || (gname != null && s.genres.any { it.equals(gname, ignoreCase = true) })
            }
        }

    val currentAvailableGenres: List<Genre>
        get() {
            return when (selectedContentType) {
                "MOVIE" -> movieGenres
                "TV" -> tvGenres
                "ANIME" -> animeGenres
                else -> (movieGenres + tvGenres).distinctBy { it.id }
            }
        }
}

@OptIn(FlowPreview::class)
class SearchViewModel(
    private val repository: MediaRepository,
    private val managedOrchestrator: ManagedMediaOrchestrator? = null,
    private val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
) : ViewModel() {
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _quickSearchState = MutableStateFlow(QuickSearchUiState())
    val quickSearchState: StateFlow<QuickSearchUiState> = _quickSearchState.asStateFlow()

    private val queryFlow = MutableStateFlow("")
    private val quickQueryFlow = MutableStateFlow("")
    private var searchJob: Job? = null
    private var quickSearchJob: Job? = null
    private var searchGeneration: Long = 0L
    private var quickSearchGeneration: Long = 0L
    private var lastExecutedNormalizedQuery: String = ""
    private var lastExecutedSearchIntent: Boolean = false
    private var unrankedMovies: List<Movie> = emptyList()
    private var unrankedSeries: List<Series> = emptyList()

    companion object {
        fun hasConfirmedDubbedSource(movie: Movie): Boolean = com.example.domain.search.DubbedSearchRankingEngine.hasConfirmedDubbedSource(movie)
        fun hasConfirmedDubbedSource(series: Series): Boolean = com.example.domain.search.DubbedSearchRankingEngine.hasConfirmedDubbedSource(series)
        fun hasUnverifiedCandidate(movie: Movie): Boolean = com.example.domain.search.DubbedSearchRankingEngine.hasUnverifiedCandidate(movie)
        fun hasUnverifiedCandidate(series: Series): Boolean = com.example.domain.search.DubbedSearchRankingEngine.hasUnverifiedCandidate(series)

        fun rankMoviesForDubbedIntent(movies: List<Movie>, cleanQuery: String = ""): List<Movie> =
            com.example.domain.search.DubbedSearchRankingEngine.rankMoviesForDubbedIntent(movies, cleanQuery)

        fun rankSeriesForDubbedIntent(seriesList: List<Series>, cleanQuery: String = ""): List<Series> =
            com.example.domain.search.DubbedSearchRankingEngine.rankSeriesForDubbedIntent(seriesList, cleanQuery)
    }

    private var initialDataInitialized = false

    init {
        viewModelScope.launch {
            queryFlow
                .debounce(500)
                .collect { q ->
                    val norm = SearchTriggerParser.normalizeWhitespace(q)
                    val trigger = SearchTriggerParser.parse(q)
                    val isDubbed = trigger.isArabicDubbedIntent
                    if (norm.isBlank()) {
                        lastExecutedNormalizedQuery = ""
                        lastExecutedSearchIntent = false
                        unrankedMovies = emptyList()
                        unrankedSeries = emptyList()
                        cancelActiveSearch()
                        _uiState.update { it.copy(movieResults = emptyList(), seriesResults = emptyList(), isSearching = false, isDubbedIntent = false) }
                    } else if (norm != lastExecutedNormalizedQuery || isDubbed != lastExecutedSearchIntent || (_uiState.value.movieResults.isEmpty() && _uiState.value.seriesResults.isEmpty() && !_uiState.value.isSearching)) {
                        performSearch(q)
                    }
                }
        }

        viewModelScope.launch {
            quickQueryFlow
                .debounce(400)
                .collect { q ->
                    val norm = SearchTriggerParser.normalizeWhitespace(q)
                    if (norm.isBlank()) {
                        cancelActiveQuickSearch()
                        _quickSearchState.update { QuickSearchUiState() }
                    } else {
                        performQuickSearch(q)
                    }
                }
        }
        
    }

    fun initializeIfNeeded() {
        if (initialDataInitialized) return
        initialDataInitialized = true
        loadTrending()
        loadGenres()
    }

    private fun loadGenres() {
        viewModelScope.launch {
            try {
                val mg = repository.getMovieGenres()
                val tg = repository.getTvGenres()
                val ag = repository.getAnimeGenres()
                _uiState.update { it.copy(movieGenres = mg, tvGenres = tg, animeGenres = ag) }
            } catch (_: Exception) {}
        }
    }
    
    private fun loadTrending() {
        viewModelScope.launch {
            repository.getTrendingMovies()
                .catch { }
                .collect { movies ->
                    _uiState.update { it.copy(trendingNow = movies.take(10)) }
                }
        }
    }

    fun setContentTypeFilter(type: String) {
        _uiState.update { current ->
            current.copy(
                selectedContentType = type,
                selectedGenreId = null,
                selectedGenreName = null
            )
        }
    }

    fun setGenreFilter(genreId: Int?, genreName: String?) {
        _uiState.update { current ->
            current.copy(
                selectedGenreId = genreId,
                selectedGenreName = genreName
            )
        }
    }

    fun clearFilters() {
        _uiState.update { current ->
            current.copy(
                selectedContentType = "ALL",
                selectedGenreId = null,
                selectedGenreName = null
            )
        }
    }

    fun refresh() {
        if (queryFlow.value.isNotBlank()) {
            performSearch(queryFlow.value)
        } else {
            loadTrending()
        }
    }

    /**
     * Cancels any active search coroutine immediately to eliminate background network drain.
     */
    fun cancelActiveSearch() {
        searchJob?.cancel()
        searchJob = null
        _uiState.update { it.copy(isSearching = false) }
    }

    fun cancelActiveQuickSearch() {
        quickSearchJob?.cancel()
        quickSearchJob = null
        _quickSearchState.update { it.copy(isSearching = false) }
    }

    fun resetQuickSearchState() {
        ++quickSearchGeneration
        cancelActiveQuickSearch()
        quickQueryFlow.value = ""
        _quickSearchState.update { QuickSearchUiState() }
    }

    fun onQuickQueryChange(query: String) {
        val norm = SearchTriggerParser.normalizeWhitespace(query)
        if (norm.isBlank()) {
            ++quickSearchGeneration
            cancelActiveQuickSearch()
            _quickSearchState.update { QuickSearchUiState() }
        } else {
            val trigger = SearchTriggerParser.parse(query)
            _quickSearchState.update {
                it.copy(
                    query = query,
                    isSearching = true,
                    isDubbedIntent = trigger.isArabicDubbedIntent
                )
            }
        }
        quickQueryFlow.value = query
    }

    fun resetTransientSearchState() {
        ++searchGeneration
        cancelActiveSearch()
        queryFlow.value = ""
        lastExecutedNormalizedQuery = ""
        lastExecutedSearchIntent = false
        unrankedMovies = emptyList()
        unrankedSeries = emptyList()
        _uiState.update {
            it.copy(
                query = "",
                movieResults = emptyList(),
                seriesResults = emptyList(),
                isSearching = false,
                selectedContentType = "ALL",
                selectedGenreId = null,
                selectedGenreName = null,
                isDubbedIntent = false
            )
        }
    }

    fun onQueryChange(query: String) {
        val norm = SearchTriggerParser.normalizeWhitespace(query)
        if (query.isBlank()) {
            lastExecutedNormalizedQuery = ""
            lastExecutedSearchIntent = false
            unrankedMovies = emptyList()
            unrankedSeries = emptyList()
            ++searchGeneration
            cancelActiveSearch()
            _uiState.update { it.copy(query = "", movieResults = emptyList(), seriesResults = emptyList(), isSearching = false, isDubbedIntent = false) }
        } else {
            val trigger = SearchTriggerParser.parse(query)
            val isDubbed = trigger.isArabicDubbedIntent
            val previousIntent = _uiState.value.isDubbedIntent
            _uiState.update { current ->
                val baseMovies = if (unrankedMovies.isNotEmpty()) unrankedMovies else current.movieResults
                val baseSeries = if (unrankedSeries.isNotEmpty()) unrankedSeries else current.seriesResults
                val movies = if (isDubbed) {
                    rankMoviesForDubbedIntent(baseMovies, trigger.cleanQuery)
                } else {
                    baseMovies
                }

                val series = if (isDubbed) {
                    rankSeriesForDubbedIntent(baseSeries, trigger.cleanQuery)
                } else {
                    baseSeries
                }

                current.copy(
                    query = query,
                    isSearching = true,
                    isDubbedIntent = isDubbed,
                    movieResults = movies,
                    seriesResults = series
                )
            }
        }
        queryFlow.value = query
    }

    fun submitSearch(query: String) {
        val trimmed = query.trim()
        val norm = SearchTriggerParser.normalizeWhitespace(trimmed)
        if (norm.isBlank()) return
        val trigger = SearchTriggerParser.parse(trimmed)
        _uiState.update { it.copy(query = trimmed, isSearching = true, isDubbedIntent = trigger.isArabicDubbedIntent) }
        queryFlow.value = trimmed
        performSearch(trimmed)
    }

    private fun performSearch(query: String) {
        val normalizedInput = SearchTriggerParser.normalizeWhitespace(query)
        if (normalizedInput.isBlank()) return
        val currentGen = ++searchGeneration
        val trigger = SearchTriggerParser.parse(query)
        val isDubbed = trigger.isArabicDubbedIntent
        lastExecutedNormalizedQuery = normalizedInput
        lastExecutedSearchIntent = isDubbed
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Determine TMDB query:
            // If dubbed intent with media title, query clean title.
            // If dubbed intent with bare trigger (like 'دبلجة' or 'مدبلج'), query "مدبلج" and fetch popular Arabic media.
            // Otherwise, query normal input.
            val tmdbQuery = if (isDubbed && trigger.cleanQuery.isNotBlank()) {
                trigger.cleanQuery
            } else if (isDubbed) {
                "مدبلج"
            } else {
                normalizedInput
            }

            // 1. Concurrently launch TMDB query, optional Arabic catalog query, and Managed Extensions query
            val tmdbDeferred = async(ioDispatcher) {
                try {
                    repository.searchMulti(tmdbQuery)
                } catch (_: Exception) {
                    Pair(emptyList(), emptyList())
                }
            }

            val arabicCatalogDeferred = if (isDubbed && trigger.cleanQuery.isBlank()) {
                async(ioDispatcher) {
                    try {
                        val m = repository.getArabicMovies().catch { }.firstOrNull() ?: emptyList()
                        val s = repository.getArabicSeries().catch { }.firstOrNull() ?: emptyList()
                        Pair(m, s)
                    } catch (_: Exception) {
                        Pair(emptyList<Movie>(), emptyList<Series>())
                    }
                }
            } else null

            val managedDeferred = async(ioDispatcher) {
                val orchestrator = managedOrchestrator
                if (orchestrator != null && orchestrator.hasActiveExtensions()) {
                    try {
                        val extensionQuery = if (isDubbed && trigger.cleanQuery.isBlank()) "مدبلج" else if (isDubbed) trigger.cleanQuery else query
                        val searchResult = orchestrator.searchMedia(extensionQuery)
                        searchResult.fold(
                            onSuccess = { it.items },
                            onFailure = { err ->
                                Phase05HLogger.log("SEARCH", "provider_error", "searchMedia failed for query '$extensionQuery': ${err.message}")
                                emptyList()
                            }
                        )
                    } catch (e: Exception) {
                        Phase05HLogger.log("SEARCH", "provider_error", "searchMedia exception for query '$query': ${e.message}")
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            // PROGRESSIVE FIRST PASS: Emit fast TMDB results immediately (~100-200ms) without erasing known candidates
            val (rawTmdbMovies, rawTmdbSeries) = tmdbDeferred.await()
            val (catalogMovies, catalogSeries) = arabicCatalogDeferred?.await() ?: Pair(emptyList(), emptyList())
            val tmdbMovies = (rawTmdbMovies + catalogMovies).distinctBy { it.id }
            val tmdbSeries = (rawTmdbSeries + catalogSeries).distinctBy { it.id }

            currentCoroutineContext().ensureActive()
            if (searchGeneration == currentGen && SearchTriggerParser.normalizeWhitespace(_uiState.value.query) == normalizedInput) {
                _uiState.update { current ->
                    // Preserve candidate enrichments from current state or unranked lists
                    val existingMoviesMap = (current.movieResults + unrankedMovies).associateBy { it.id }
                    val preservedMovies = tmdbMovies.map { m ->
                        val existing = existingMoviesMap[m.id] ?: (current.movieResults + unrankedMovies).firstOrNull { 
                            MediaTitleMatcher.matchesMovie(it.title, m, it.year.toString()) 
                        }
                        if (existing != null && (existing.sourceCandidates.isNotEmpty() || !existing.sourceUrl.isNullOrBlank())) {
                            m.copy(
                                sourceCandidates = existing.sourceCandidates,
                                sourceUrl = existing.sourceUrl,
                                providerId = existing.providerId,
                                mode = existing.mode ?: m.mode
                            )
                        } else m
                    }.distinctBy { it.id }

                    val existingSeriesMap = (current.seriesResults + unrankedSeries).associateBy { it.id }
                    val preservedSeries = tmdbSeries.map { s ->
                        val existing = existingSeriesMap[s.id] ?: (current.seriesResults + unrankedSeries).firstOrNull { 
                            MediaTitleMatcher.matchesSeries(it.title, s, it.year.toString()) 
                        }
                        if (existing != null && (existing.sourceCandidates.isNotEmpty() || !existing.sourceUrl.isNullOrBlank())) {
                            s.copy(
                                sourceCandidates = existing.sourceCandidates,
                                sourceUrl = existing.sourceUrl,
                                providerId = existing.providerId,
                                mode = existing.mode ?: s.mode
                            )
                        } else s
                    }.distinctBy { it.id }

                    unrankedMovies = preservedMovies
                    unrankedSeries = preservedSeries
                    val rankedMovies = if (isDubbed) rankMoviesForDubbedIntent(preservedMovies, trigger.cleanQuery) else preservedMovies
                    val rankedSeries = if (isDubbed) rankSeriesForDubbedIntent(preservedSeries, trigger.cleanQuery) else preservedSeries
                    current.copy(
                        movieResults = rankedMovies,
                        seriesResults = rankedSeries,
                        isSearching = true // Extensions still being fetched in background
                    )
                }
            }

            // SECOND PASS: Await extension search completion and enrich canonical items
            val managedResults = managedDeferred.await()
            currentCoroutineContext().ensureActive()
            val currentNormAfterManaged = SearchTriggerParser.normalizeWhitespace(_uiState.value.query)
            if (searchGeneration != currentGen || currentNormAfterManaged != normalizedInput) return@launch

            val (combinedMovies, combinedSeries) = enrichMediaWithProviders(
                tmdbMovies = tmdbMovies,
                tmdbSeries = tmdbSeries,
                managedResults = managedResults,
                existingMovies = _uiState.value.movieResults + unrankedMovies,
                existingSeries = _uiState.value.seriesResults + unrankedSeries,
                isDubbed = isDubbed
            )

            unrankedMovies = combinedMovies
            unrankedSeries = combinedSeries
            val finalMovies = if (isDubbed) rankMoviesForDubbedIntent(combinedMovies, trigger.cleanQuery) else combinedMovies
            val finalSeries = if (isDubbed) rankSeriesForDubbedIntent(combinedSeries, trigger.cleanQuery) else combinedSeries

            currentCoroutineContext().ensureActive()
            val finalNorm = SearchTriggerParser.normalizeWhitespace(_uiState.value.query)
            if (searchGeneration != currentGen || finalNorm != normalizedInput) return@launch
            _uiState.update { current ->
                if (SearchTriggerParser.normalizeWhitespace(current.query) == normalizedInput) {
                    current.copy(
                        movieResults = finalMovies,
                        seriesResults = finalSeries,
                        isSearching = false,
                        isDubbedIntent = isDubbed
                    )
                } else {
                    current
                }
            }
        }
    }

    private fun performQuickSearch(query: String) {
        val normalizedInput = SearchTriggerParser.normalizeWhitespace(query)
        if (normalizedInput.isBlank()) return
        val currentGen = ++quickSearchGeneration
        val trigger = SearchTriggerParser.parse(query)
        val isDubbed = trigger.isArabicDubbedIntent
        quickSearchJob?.cancel()
        quickSearchJob = viewModelScope.launch {
            val tmdbQuery = if (isDubbed && trigger.cleanQuery.isNotBlank()) {
                trigger.cleanQuery
            } else if (isDubbed) {
                "مدبلج"
            } else {
                normalizedInput
            }

            val tmdbDeferred = async(ioDispatcher) {
                try {
                    repository.searchMulti(tmdbQuery)
                } catch (_: Exception) {
                    Pair(emptyList(), emptyList())
                }
            }

            val managedDeferred = async(ioDispatcher) {
                val orchestrator = managedOrchestrator
                if (orchestrator != null && orchestrator.hasActiveExtensions()) {
                    try {
                        val extensionQuery = if (isDubbed && trigger.cleanQuery.isBlank()) "مدبلج" else if (isDubbed) trigger.cleanQuery else query
                        val searchResult = orchestrator.searchMedia(extensionQuery)
                        searchResult.fold(
                            onSuccess = { it.items },
                            onFailure = { err ->
                                Phase05HLogger.log("QUICK_SEARCH", "provider_error", "Quick searchMedia failed: ${err.message}")
                                emptyList()
                            }
                        )
                    } catch (e: Exception) {
                        Phase05HLogger.log("QUICK_SEARCH", "provider_error", "Quick searchMedia exception: ${e.message}")
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            val (rawTmdbMovies, rawTmdbSeries) = tmdbDeferred.await()
            val tmdbMovies = rawTmdbMovies.distinctBy { it.id }
            val tmdbSeries = rawTmdbSeries.distinctBy { it.id }

            currentCoroutineContext().ensureActive()
            if (quickSearchGeneration == currentGen && SearchTriggerParser.normalizeWhitespace(_quickSearchState.value.query) == normalizedInput) {
                _quickSearchState.update { current ->
                    current.copy(
                        query = query,
                        movieResults = tmdbMovies,
                        seriesResults = tmdbSeries,
                        isSearching = true,
                        isDubbedIntent = isDubbed
                    )
                }
            }

            val managedResults = managedDeferred.await()
            currentCoroutineContext().ensureActive()
            if (quickSearchGeneration != currentGen || SearchTriggerParser.normalizeWhitespace(_quickSearchState.value.query) != normalizedInput) return@launch

            val (combinedMovies, combinedSeries) = enrichMediaWithProviders(
                tmdbMovies = tmdbMovies,
                tmdbSeries = tmdbSeries,
                managedResults = managedResults,
                existingMovies = _quickSearchState.value.movieResults,
                existingSeries = _quickSearchState.value.seriesResults,
                isDubbed = isDubbed
            )

            val finalMovies = if (isDubbed) rankMoviesForDubbedIntent(combinedMovies, trigger.cleanQuery) else combinedMovies
            val finalSeries = if (isDubbed) rankSeriesForDubbedIntent(combinedSeries, trigger.cleanQuery) else combinedSeries

            _quickSearchState.update { current ->
                if (SearchTriggerParser.normalizeWhitespace(current.query) == normalizedInput) {
                    current.copy(
                        query = query,
                        movieResults = finalMovies,
                        seriesResults = finalSeries,
                        isSearching = false,
                        isDubbedIntent = isDubbed
                    )
                } else {
                    current
                }
            }
        }
    }

    /**
     * Authoritative Centralized Provider Enrichment & Canonical TMDB Resolution.
     * Consolidates media merging for both full search and quick search:
     * 1. Strictly gates 'dubbed' mode on verified provider candidates - never title strings.
     * 2. Resolves canonical TMDB identity for provider items not present in the current TMDB page.
     * 3. Prevents invalid synthetic IDs from causing crashes or hanging details screens.
     */
    private suspend fun enrichMediaWithProviders(
        tmdbMovies: List<Movie>,
        tmdbSeries: List<Series>,
        managedResults: List<SearchMediaItem>,
        existingMovies: List<Movie>,
        existingSeries: List<Series>,
        isDubbed: Boolean
    ): Pair<List<Movie>, List<Series>> {
        val managedMovies = managedResults.filter { it.contentType == ContentType.MOVIE }
        val managedSeries = managedResults.filter { it.contentType == ContentType.SERIES || it.contentType == ContentType.ANIME }

        val existingMoviesMap = existingMovies.associateBy { it.id }
        val existingSeriesMap = existingSeries.associateBy { it.id }

        // 1. Enrich TMDB movies that match managed items
        val enhancedTmdbMovies = tmdbMovies.map { movie ->
            val matchingManagedList = managedMovies.filter { MediaTitleMatcher.matchesMovie(it.title, movie, it.year) }
            val primaryManaged = matchingManagedList.firstOrNull()
            val existingMatchedMovie = existingMoviesMap[movie.id] ?: existingMovies.firstOrNull { 
                MediaTitleMatcher.matchesMovie(it.title, movie, it.year.toString()) 
            }
            val existingCandidates = existingMatchedMovie?.sourceCandidates ?: emptyList()
            val allCandidates = (matchingManagedList.flatMap { it.sourceCandidates } + movie.sourceCandidates + existingCandidates)
                .distinctBy { "${it.providerId}_${it.sourcePageUrl}" }

            val confirmedDubbedCandidate = allCandidates.firstOrNull {
                it.mode == MediaVariantMode.DUBBED && it.status == CoverageStatus.FOUND && it.sourcePageUrl.isNotBlank()
            } ?: primaryManaged?.sourceCandidates?.firstOrNull {
                it.mode == MediaVariantMode.DUBBED && it.status == CoverageStatus.FOUND && it.sourcePageUrl.isNotBlank()
            }

            // Strictly require a verified dubbed candidate - never title strings
            val isDubbedConfirmed = confirmedDubbedCandidate != null
            val assignedMode = if (isDubbedConfirmed) "dubbed" else if (isDubbed) "subtitled" else movie.mode ?: "subtitled"
            val resolvedSourceUrl = primaryManaged?.url ?: confirmedDubbedCandidate?.sourcePageUrl ?: existingMatchedMovie?.sourceUrl ?: movie.sourceUrl
            val resolvedProviderId = primaryManaged?.providerId ?: confirmedDubbedCandidate?.providerId ?: existingMatchedMovie?.providerId ?: movie.providerId

            movie.copy(
                sourceUrl = resolvedSourceUrl,
                providerId = resolvedProviderId,
                mode = assignedMode,
                sourceCandidates = allCandidates
            )
        }

        // 2. Unmatched managed movies -> Attempt canonical TMDB resolution before standalone creation
        val unmatchedManagedMovies = managedMovies.filterNot { item ->
            tmdbMovies.any { MediaTitleMatcher.matchesMovie(item.title, it, item.year) }
        }

        val app = try { AppContainer.application } catch (_: Exception) { null }
        val resolvedAdditionalMovies = mutableListOf<Movie>()

        for (item in unmatchedManagedMovies) {
            val confirmedDubbedCandidate = item.sourceCandidates.firstOrNull {
                it.mode == MediaVariantMode.DUBBED && it.status == CoverageStatus.FOUND && it.sourcePageUrl.isNotBlank()
            }
            val isDubbedConfirmed = confirmedDubbedCandidate != null
            val itemMode = if (isDubbedConfirmed) "dubbed" else "subtitled"
            val extractedYear = item.year?.toIntOrNull() ?: MediaTitleMatcher.extractYear(item.title) ?: 0

            // Try canonical TMDB resolution by cleaned title
            val cleanTitle = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(item.title))
            var resolvedTmdbMovie: Movie? = null
            if (cleanTitle.isNotBlank()) {
                try {
                    val (tmdbFound, _) = repository.searchMulti(cleanTitle)
                    val matchingMovies = tmdbFound.filter { MediaTitleMatcher.matchesMovie(item.title, it, item.year) }
                    resolvedTmdbMovie = matchingMovies.minByOrNull { m ->
                        val itemYr = item.year?.toIntOrNull() ?: MediaTitleMatcher.extractYear(item.title)
                        val yrDiff = if (itemYr != null && m.year > 0) kotlin.math.abs(itemYr - m.year) else 10
                        val titleDiff = if (MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(m.title)) == cleanTitle) 0 else 1
                        yrDiff * 2 + titleDiff
                    }
                } catch (_: Exception) {}
            }

            if (resolvedTmdbMovie != null) {
                val canonicalEnhanced = resolvedTmdbMovie.copy(
                    sourceUrl = item.url,
                    providerId = item.providerId,
                    mode = itemMode,
                    sourceCandidates = (resolvedTmdbMovie.sourceCandidates + item.sourceCandidates).distinctBy { "${it.providerId}_${it.sourcePageUrl}" }
                )
                if (app != null) {
                    try { com.example.utils.MediaDetailsCacheManager.saveMovieDetails(app, canonicalEnhanced) } catch (_: Exception) {}
                }
                resolvedAdditionalMovies.add(canonicalEnhanced)
            } else {
                Phase05HLogger.log("SEARCH", item.providerId ?: "unknown", "UNRESOLVED_TMDB_MAPPING: Unmatched provider movie '${item.title}' omitted from canonical TMDB catalog")
            }
        }

        // 3. Enrich TMDB series that match managed items
        val enhancedTmdbSeries = tmdbSeries.map { series ->
            val matchingManagedList = managedSeries.filter { MediaTitleMatcher.matchesSeries(it.title, series, it.year) }
            val primaryManaged = matchingManagedList.firstOrNull()
            val existingMatchedSeries = existingSeriesMap[series.id] ?: existingSeries.firstOrNull { 
                MediaTitleMatcher.matchesSeries(it.title, series, it.year.toString()) 
            }
            val existingCandidates = existingMatchedSeries?.sourceCandidates ?: emptyList()
            val allCandidates = (matchingManagedList.flatMap { it.sourceCandidates } + series.sourceCandidates + existingCandidates)
                .distinctBy { "${it.providerId}_${it.sourcePageUrl}" }

            val confirmedDubbedCandidate = allCandidates.firstOrNull {
                it.mode == MediaVariantMode.DUBBED && it.status == CoverageStatus.FOUND && it.sourcePageUrl.isNotBlank()
            } ?: primaryManaged?.sourceCandidates?.firstOrNull {
                it.mode == MediaVariantMode.DUBBED && it.status == CoverageStatus.FOUND && it.sourcePageUrl.isNotBlank()
            }

            val isDubbedConfirmed = confirmedDubbedCandidate != null
            val assignedMode = if (isDubbedConfirmed) "dubbed" else if (isDubbed) "subtitled" else series.mode ?: "subtitled"
            val resolvedSourceUrl = primaryManaged?.url ?: confirmedDubbedCandidate?.sourcePageUrl ?: existingMatchedSeries?.sourceUrl ?: series.sourceUrl
            val resolvedProviderId = primaryManaged?.providerId ?: confirmedDubbedCandidate?.providerId ?: existingMatchedSeries?.providerId ?: series.providerId

            series.copy(
                sourceUrl = resolvedSourceUrl,
                providerId = resolvedProviderId,
                mode = assignedMode,
                sourceCandidates = allCandidates
            )
        }

        // 4. Unmatched managed series -> Attempt canonical TMDB resolution
        val unmatchedManagedSeries = managedSeries.filterNot { item ->
            tmdbSeries.any { MediaTitleMatcher.matchesSeries(item.title, it, item.year) }
        }

        val resolvedAdditionalSeries = mutableListOf<Series>()
        for (item in unmatchedManagedSeries) {
            val confirmedDubbedCandidate = item.sourceCandidates.firstOrNull {
                it.mode == MediaVariantMode.DUBBED && it.status == CoverageStatus.FOUND && it.sourcePageUrl.isNotBlank()
            }
            val isDubbedConfirmed = confirmedDubbedCandidate != null
            val itemMode = if (isDubbedConfirmed) "dubbed" else "subtitled"
            val extractedYear = item.year?.toIntOrNull() ?: MediaTitleMatcher.extractYear(item.title) ?: 0

            val cleanTitle = MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(item.title))
            var resolvedTmdbSeries: Series? = null
            if (cleanTitle.isNotBlank()) {
                try {
                    val (_, tmdbFound) = repository.searchMulti(cleanTitle)
                    val matchingSeries = tmdbFound.filter { MediaTitleMatcher.matchesSeries(item.title, it, item.year) }
                    resolvedTmdbSeries = matchingSeries.minByOrNull { s ->
                        val itemYr = item.year?.toIntOrNull() ?: MediaTitleMatcher.extractYear(item.title)
                        val yrDiff = if (itemYr != null && s.year > 0) kotlin.math.abs(itemYr - s.year) else 10
                        val titleDiff = if (MediaTitleMatcher.cleanYearTokens(MediaTitleMatcher.normalize(s.title)) == cleanTitle) 0 else 1
                        yrDiff * 2 + titleDiff
                    }
                } catch (_: Exception) {}
            }

            if (resolvedTmdbSeries != null) {
                val canonicalEnhanced = resolvedTmdbSeries.copy(
                    sourceUrl = item.url,
                    providerId = item.providerId,
                    mode = itemMode,
                    sourceCandidates = (resolvedTmdbSeries.sourceCandidates + item.sourceCandidates).distinctBy { "${it.providerId}_${it.sourcePageUrl}" }
                )
                if (app != null) {
                    try { com.example.utils.MediaDetailsCacheManager.saveSeriesDetails(app, canonicalEnhanced) } catch (_: Exception) {}
                }
                resolvedAdditionalSeries.add(canonicalEnhanced)
            } else {
                Phase05HLogger.log("SEARCH", item.providerId ?: "unknown", "UNRESOLVED_TMDB_MAPPING: Unmatched provider series '${item.title}' omitted from canonical TMDB catalog")
            }
        }

        val combinedMovies = (enhancedTmdbMovies + resolvedAdditionalMovies).distinctBy { it.id }
        val combinedSeries = (enhancedTmdbSeries + resolvedAdditionalSeries).distinctBy { it.id }
        return Pair(combinedMovies, combinedSeries)
    }

    override fun onCleared() {
        super.onCleared()
        cancelActiveSearch()
        cancelActiveQuickSearch()
    }
}
