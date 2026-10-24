package com.example.data.repository

import com.example.domain.models.Movie
import com.example.domain.models.PaginatedResult
import com.example.domain.models.Series
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch

data class TabPaginationState(
    val items: List<Pair<Any, Boolean>> = emptyList(),
    val currentPage: Int = 0,
    val totalPages: Int = 1,
    val isLoadingFirstPage: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadMoreError: String? = null,
    val hasNextPage: Boolean = true
)

class TmdbListPaginator(
    private val fetchMoviesPage: suspend (page: Int) -> PaginatedResult<Movie>,
    private val fetchSeriesPage: suspend (page: Int) -> PaginatedResult<Series>,
    private val fetchAnimePage: suspend (page: Int) -> PaginatedResult<Series>,
    private val coroutineScope: CoroutineScope
) {
    // Phase 05AE-9: Granular per-tab state flows providing isolated invalidation
    private val _tab0State = MutableStateFlow(TabPaginationState())
    private val _tab1State = MutableStateFlow(TabPaginationState())
    private val _tab2State = MutableStateFlow(TabPaginationState())
    private val _tab3State = MutableStateFlow(TabPaginationState())

    val tab0State: StateFlow<TabPaginationState> = _tab0State.asStateFlow()
    val tab1State: StateFlow<TabPaginationState> = _tab1State.asStateFlow()
    val tab2State: StateFlow<TabPaginationState> = _tab2State.asStateFlow()
    val tab3State: StateFlow<TabPaginationState> = _tab3State.asStateFlow()

    private val _tabStates = MutableStateFlow<Map<Int, TabPaginationState>>(
        mapOf(
            0 to TabPaginationState(),
            1 to TabPaginationState(),
            2 to TabPaginationState(),
            3 to TabPaginationState()
        )
    )
    val tabStates: StateFlow<Map<Int, TabPaginationState>> = _tabStates.asStateFlow()

    fun getTabStateFlow(tabIndex: Int): StateFlow<TabPaginationState> {
        return when (tabIndex) {
            0 -> tab0State
            1 -> tab1State
            2 -> tab2State
            3 -> tab3State
            else -> tab0State
        }
    }

    private val tabJobs = mutableMapOf<Int, Job>()

    fun getState(tabIndex: Int): TabPaginationState {
        return when (tabIndex) {
            0 -> _tab0State.value
            1 -> _tab1State.value
            2 -> _tab2State.value
            3 -> _tab3State.value
            else -> _tabStates.value[tabIndex] ?: TabPaginationState()
        }
    }

    private fun updateState(tabIndex: Int, transform: (TabPaginationState) -> TabPaginationState) {
        val newState = when (tabIndex) {
            0 -> _tab0State.updateAndGet(transform)
            1 -> _tab1State.updateAndGet(transform)
            2 -> _tab2State.updateAndGet(transform)
            3 -> _tab3State.updateAndGet(transform)
            else -> transform(_tabStates.value[tabIndex] ?: TabPaginationState())
        }
        _tabStates.update { current ->
            current + (tabIndex to newState)
        }
    }

    private fun mediaKey(media: Any, isMovie: Boolean): String {
        val id = if (isMovie) (media as Movie).id else (media as Series).id
        return "${if (isMovie) "m" else "s"}_$id"
    }

    fun ensureTabLoaded(tabIndex: Int) {
        val state = getState(tabIndex)
        if (state.currentPage == 0 && !state.isLoadingFirstPage) {
            loadFirstPage(tabIndex)
        }
    }

    fun loadFirstPage(tabIndex: Int) {
        tabJobs[tabIndex]?.cancel()
        val job = coroutineScope.launch {
            updateState(tabIndex) {
                // Keep old items visible while refreshing, but reset the page cursor so
                // a failed refresh retries page one rather than skipping to a later page.
                it.copy(
                    isLoadingFirstPage = true,
                    isLoadingMore = false,
                    currentPage = 0,
                    hasNextPage = true,
                    loadMoreError = null
                )
            }
            try {
                when (tabIndex) {
                    1 -> { // Movies
                        val res = fetchMoviesPage(1)
                        val mapped = res.items.map { it to true }
                        updateState(tabIndex) {
                            it.copy(
                                items = mapped,
                                currentPage = res.page,
                                totalPages = res.totalPages,
                                isLoadingFirstPage = false,
                                hasNextPage = res.page < res.totalPages
                            )
                        }
                    }
                    2 -> { // Series
                        val res = fetchSeriesPage(1)
                        val mapped = res.items.map { it to false }
                        updateState(tabIndex) {
                            it.copy(
                                items = mapped,
                                currentPage = res.page,
                                totalPages = res.totalPages,
                                isLoadingFirstPage = false,
                                hasNextPage = res.page < res.totalPages
                            )
                        }
                    }
                    3 -> { // Anime
                        val res = fetchAnimePage(1)
                        val mapped = res.items.map { it to false }
                        updateState(tabIndex) {
                            it.copy(
                                items = mapped,
                                currentPage = res.page,
                                totalPages = res.totalPages,
                                isLoadingFirstPage = false,
                                hasNextPage = res.page < res.totalPages
                            )
                        }
                    }
                    else -> { // All (tab 0)
                        val mDeferred = async { fetchMoviesPage(1) }
                        val sDeferred = async { fetchSeriesPage(1) }
                        val aDeferred = async { fetchAnimePage(1) }

                        val mRes = mDeferred.await()
                        val sRes = sDeferred.await()
                        val aRes = aDeferred.await()

                        val combined = (mRes.items.map { it to true } +
                                        sRes.items.map { it to false } +
                                        aRes.items.map { it to false })
                            .distinctBy { mediaKey(it.first, it.second) }

                        val maxPages = maxOf(mRes.totalPages, sRes.totalPages, aRes.totalPages).coerceAtLeast(1)

                        updateState(tabIndex) {
                            it.copy(
                                items = combined,
                                currentPage = 1,
                                totalPages = maxPages,
                                isLoadingFirstPage = false,
                                hasNextPage = 1 < maxPages
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                updateState(tabIndex) {
                    it.copy(
                        isLoadingFirstPage = false,
                        loadMoreError = e.message ?: "Failed to load initial page"
                    )
                }
            }
        }
        tabJobs[tabIndex] = job
    }

    fun loadMore(tabIndex: Int) {
        val state = getState(tabIndex)
        if (state.isLoadingMore || state.isLoadingFirstPage || !state.hasNextPage) {
            return
        }
        // A scroll observer can fire before the initial-page effect updates state.
        // Never treat an uninitialized cursor (page 0) as a load-more request for page 1.
        if (state.currentPage <= 0) {
            loadFirstPage(tabIndex)
            return
        }
        val nextPage = state.currentPage + 1
        if (nextPage > state.totalPages) {
            updateState(tabIndex) { it.copy(hasNextPage = false) }
            return
        }

        tabJobs[tabIndex]?.cancel()
        val job = coroutineScope.launch {
            updateState(tabIndex) { it.copy(isLoadingMore = true, loadMoreError = null) }
            try {
                when (tabIndex) {
                    1 -> { // Movies
                        val res = fetchMoviesPage(nextPage)
                        val newMapped = res.items.map { it to true }
                        if (newMapped.isEmpty()) {
                            updateState(tabIndex) {
                                it.copy(
                                    currentPage = maxOf(res.page, nextPage),
                                    totalPages = res.totalPages.coerceAtLeast(1),
                                    isLoadingMore = false,
                                    hasNextPage = maxOf(res.page, nextPage) < res.totalPages
                                )
                            }
                        } else {
                            val combined = (state.items + newMapped).distinctBy { mediaKey(it.first, it.second) }
                            updateState(tabIndex) {
                                it.copy(
                                    items = combined,
                                    currentPage = res.page,
                                    totalPages = res.totalPages,
                                    isLoadingMore = false,
                                    hasNextPage = res.page < res.totalPages
                                )
                            }
                        }
                    }
                    2 -> { // Series
                        val res = fetchSeriesPage(nextPage)
                        val newMapped = res.items.map { it to false }
                        if (newMapped.isEmpty()) {
                            updateState(tabIndex) {
                                it.copy(
                                    currentPage = maxOf(res.page, nextPage),
                                    totalPages = res.totalPages.coerceAtLeast(1),
                                    isLoadingMore = false,
                                    hasNextPage = maxOf(res.page, nextPage) < res.totalPages
                                )
                            }
                        } else {
                            val combined = (state.items + newMapped).distinctBy { mediaKey(it.first, it.second) }
                            updateState(tabIndex) {
                                it.copy(
                                    items = combined,
                                    currentPage = res.page,
                                    totalPages = res.totalPages,
                                    isLoadingMore = false,
                                    hasNextPage = res.page < res.totalPages
                                )
                            }
                        }
                    }
                    3 -> { // Anime
                        val res = fetchAnimePage(nextPage)
                        val newMapped = res.items.map { it to false }
                        if (newMapped.isEmpty()) {
                            updateState(tabIndex) {
                                it.copy(
                                    currentPage = maxOf(res.page, nextPage),
                                    totalPages = res.totalPages.coerceAtLeast(1),
                                    isLoadingMore = false,
                                    hasNextPage = maxOf(res.page, nextPage) < res.totalPages
                                )
                            }
                        } else {
                            val combined = (state.items + newMapped).distinctBy { mediaKey(it.first, it.second) }
                            updateState(tabIndex) {
                                it.copy(
                                    items = combined,
                                    currentPage = res.page,
                                    totalPages = res.totalPages,
                                    isLoadingMore = false,
                                    hasNextPage = res.page < res.totalPages
                                )
                            }
                        }
                    }
                    else -> { // All (tab 0)
                        // Do not convert upstream failures into an apparently successful
                        // empty page. Let the outer catch retain existing items and expose
                        // a retryable error instead.
                        val mDeferred = async { fetchMoviesPage(nextPage) }
                        val sDeferred = async { fetchSeriesPage(nextPage) }
                        val aDeferred = async { fetchAnimePage(nextPage) }

                        val mRes = mDeferred.await()
                        val sRes = sDeferred.await()
                        val aRes = aDeferred.await()

                        val newItems = (mRes.items.map { it to true } +
                                        sRes.items.map { it to false } +
                                        aRes.items.map { it to false })

                        if (newItems.isEmpty()) {
                            updateState(tabIndex) {
                                val advancedPage = maxOf(nextPage, mRes.page, sRes.page, aRes.page)
                                val availablePages = maxOf(mRes.totalPages, sRes.totalPages, aRes.totalPages).coerceAtLeast(1)
                                it.copy(
                                    currentPage = advancedPage,
                                    totalPages = availablePages,
                                    isLoadingMore = false,
                                    hasNextPage = advancedPage < availablePages
                                )
                            }
                        } else {
                            val combined = (state.items + newItems).distinctBy { mediaKey(it.first, it.second) }
                            val newMaxPages = maxOf(state.totalPages, mRes.totalPages, sRes.totalPages, aRes.totalPages)
                            updateState(tabIndex) {
                                it.copy(
                                    items = combined,
                                    currentPage = nextPage,
                                    totalPages = newMaxPages,
                                    isLoadingMore = false,
                                    hasNextPage = nextPage < newMaxPages
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Crucial requirement: DO NOT lose existing items on load-more error
                updateState(tabIndex) {
                    it.copy(
                        isLoadingMore = false,
                        loadMoreError = e.message ?: "Failed to load more"
                    )
                }
            }
        }
        tabJobs[tabIndex] = job
    }


    /** Retry only tabs which previously recorded a load error after connectivity returns. */
    fun retryFailedTabs() {
        for (tabIndex in 0..3) {
            val state = getState(tabIndex)
            if (state.loadMoreError != null && !state.isLoadingFirstPage && !state.isLoadingMore) {
                retry(tabIndex)
            }
        }
    }

    fun retry(tabIndex: Int) {
        val state = getState(tabIndex)
        if (state.currentPage == 0) {
            loadFirstPage(tabIndex)
        } else {
            loadMore(tabIndex)
        }
    }

    fun refresh(tabIndex: Int) {
        loadFirstPage(tabIndex)
    }

    fun resetTab(tabIndex: Int) {
        tabJobs[tabIndex]?.cancel()
        updateState(tabIndex) { TabPaginationState() }
    }

    fun onTabSwitched(newTabIndex: Int, cancelInFlight: Boolean = true) {
        if (cancelInFlight) {
            tabJobs.filterKeys { it != newTabIndex }.values.forEach { it.cancel() }
        }
        ensureTabLoaded(newTabIndex)
    }

    fun cancelInFlight() {
        tabJobs.values.forEach { it.cancel() }
        tabJobs.clear()
    }
}
