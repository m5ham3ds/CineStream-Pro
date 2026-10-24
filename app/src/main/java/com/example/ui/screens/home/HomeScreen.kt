package com.example.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.*
import androidx.compose.foundation.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.draw.*
import androidx.compose.foundation.shape.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.res.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.unit.*
import com.example.ui.components.*
import coil.compose.AsyncImage
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.example.data.model.*
import com.example.data.util.ContentTypeResolver
import com.example.data.repository.*
import com.example.domain.models.*
import com.example.ui.ViewModelFactory
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.lazy.*
import androidx.lifecycle.viewmodel.compose.viewModel
import android.widget.Toast
import androidx.compose.material3.pulltorefresh.*
import com.example.ui.screens.home.HomeViewModel


private data class HomeMixedMediaItem(
    val id: String,
    val title: String,
    val posterUrl: String,
    val year: Int,
    val rating: Double,
    val isMovie: Boolean,
    val contentType: String
)

enum class HomeNavCategory(val titleRes: Int, val id: String) {
    HOME(R.string.home, "home"),
    MOVIES(R.string.category_movies, "movies"),
    ANIME(R.string.category_anime, "anime"),
    SERIES(R.string.category_series, "series")
}







@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onPlayMovie: ((String) -> Unit)? = null,
    onPlaySeries: ((String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    onNavigateToWatching: () -> Unit = {},
    onNavigateToPopular: () -> Unit = {},
    onNavigateToNewReleases: () -> Unit = {},
    onNavigateToUpcoming: () -> Unit = {},
    onNavigateToAnime: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val homeMoviesPageState by viewModel.popularPaginator.tab1State.collectAsState()
    val homeSeriesPageState by viewModel.popularPaginator.tab2State.collectAsState()
    val homeAnimePageState by viewModel.popularPaginator.tab3State.collectAsState()
    val homeMovieGenrePageState by viewModel.movieGenrePaginator.tab1State.collectAsState()
    val homeSeriesGenrePageState by viewModel.seriesGenrePaginator.tab2State.collectAsState()
    val homeAnimeGenrePageState by viewModel.animeGenrePaginator.tab3State.collectAsState()
    val homeMoviesPageItems = remember(homeMoviesPageState.items) {
        homeMoviesPageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    val homeSeriesPageItems = remember(homeSeriesPageState.items) {
        homeSeriesPageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val homeAnimePageItems = remember(homeAnimePageState.items) {
        homeAnimePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val homeMovieGenreItems = remember(homeMovieGenrePageState.items) {
        homeMovieGenrePageState.items.mapNotNull { it.first as? Movie }.distinctBy { it.id }
    }
    val homeSeriesGenreItems = remember(homeSeriesGenrePageState.items) {
        homeSeriesGenrePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    val homeAnimeGenreItems = remember(homeAnimeGenrePageState.items) {
        homeAnimeGenrePageState.items.mapNotNull { it.first as? Series }.distinctBy { it.id }
    }
    LaunchedEffect(viewModel) {
        viewModel.ensureLoaded()
    }
    val context = LocalContext.current
    val historyRepository = remember { HistoryRepository(context) }
    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    LaunchedEffect(historyItems) {
        if (historyItems.isNotEmpty()) {
            com.example.data.repository.ContinueWatchingMetadataManager.preload(historyItems, this)
        }
    }
    val libraryRepository = remember { LibraryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }
    val scope = rememberCoroutineScope()

    val hasRemoteContent = uiState.trendingMovies.isNotEmpty() ||
            uiState.allMovies.isNotEmpty() ||
            uiState.popularMovies.isNotEmpty() ||
            uiState.trendingSeries.isNotEmpty() ||
            uiState.popularSeries.isNotEmpty() ||
            uiState.allSeries.isNotEmpty() ||
            uiState.actionMovies.isNotEmpty() ||
            uiState.upcomingMovies.isNotEmpty() ||
            uiState.newReleasesMovies.isNotEmpty() ||
            uiState.trendingAnime.isNotEmpty() ||
            uiState.animeSeries.isNotEmpty()

    var showBottomSheet by remember { mutableStateOf(false) }
    var bottomSheetIsMovie by remember { mutableStateOf(true) }
    var bottomSheetContentType by remember { mutableStateOf(ContentType.MOVIE) }
    var selectedMediaId by remember { mutableStateOf("") }
    var selectedMediaTitle by remember { mutableStateOf("") }
    var selectedMediaPoster by remember { mutableStateOf("") }

    val onMediaLongClick: (id: String, title: String, posterUrl: String, isMovie: Boolean, contentType: String) -> Unit =
        remember {
            { id, title, posterUrl, isMovie, contentType ->
                bottomSheetIsMovie = isMovie
                bottomSheetContentType = contentType
                selectedMediaId = id
                selectedMediaTitle = title
                selectedMediaPoster = posterUrl
                showBottomSheet = true
            }
        }

    var selectedCategory by rememberSaveable { mutableStateOf(HomeNavCategory.HOME) }
    var selectedMovieGenre by remember { mutableStateOf<Genre?>(null) }
    var selectedSeriesGenre by remember { mutableStateOf<Genre?>(null) }
    var selectedAnimeGenre by remember { mutableStateOf<Genre?>(null) }
    var isGenreDropdownExpanded by remember { mutableStateOf(false) }

    val homeCategories = remember {
        listOf(
            HomeNavCategory.HOME,
            HomeNavCategory.MOVIES,
            HomeNavCategory.ANIME,
            HomeNavCategory.SERIES
        )
    }

    val movieGenres = remember { com.example.data.util.TmdbGenreHelper.getMovieGenres() }
    val tvGenres = remember { com.example.data.util.TmdbGenreHelper.getTvGenres() }
    val animeGenres = remember { com.example.data.util.TmdbGenreHelper.getAnimeGenres() }
    val allGenreStr = stringResource(R.string.genre_all)

    val movieGenreOptions = remember(movieGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + movieGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }
    val tvGenreOptions = remember(tvGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + tvGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }
    val animeGenreOptions = remember(animeGenres, allGenreStr) {
        listOf(DropdownOption<Genre?>(null, allGenreStr)) + animeGenres.map {
            DropdownOption<Genre?>(it, it.name)
        }
    }

    // Each category uses its own paginated query. Genre selections use server-side
    // discovery filters and remain isolated from other media types and catalogues.
    val displayCategoryMovies = remember(homeMoviesPageItems, homeMovieGenreItems, selectedMovieGenre) {
        if (selectedMovieGenre == null) homeMoviesPageItems else homeMovieGenreItems
    }
    val displayCategorySeries = remember(homeSeriesPageItems, homeSeriesGenreItems, selectedSeriesGenre) {
        if (selectedSeriesGenre == null) homeSeriesPageItems else homeSeriesGenreItems
    }
    val displayCategoryAnime = remember(homeAnimePageItems, homeAnimeGenreItems, selectedAnimeGenre) {
        if (selectedAnimeGenre == null) homeAnimePageItems else homeAnimeGenreItems
    }

    val ptrState = rememberPullToRefreshState()
    
    val trendingMix = remember(uiState.trendingMovies, uiState.trendingSeries) {
        uiState.trendingMovies.take(5).map { movie ->
            HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE)
        } + uiState.trendingSeries.take(5).map { series ->
            HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series))
        }
    }
    val newReleasesMix = remember(uiState.newReleasesMovies, uiState.newReleasesSeries) {
        uiState.newReleasesMovies.take(10).map { movie ->
            HomeMixedMediaItem(movie.id, movie.title, movie.posterUrl, movie.year, movie.rating, true, ContentType.MOVIE)
        } + uiState.newReleasesSeries.take(10).map { series ->
            HomeMixedMediaItem(series.id, series.title, series.posterUrl, series.year, series.rating, false, ContentTypeResolver.resolveSeries(series))
        }
    }
    val upcomingMix = remember(uiState.upcomingMovies) {
        uiState.upcomingMovies.take(10).distinctBy { it.id }
    }
    val filteredTrendingMovies = remember(uiState.trendingMovies) {
        uiState.trendingMovies.distinctBy { it.id }
    }
    val filteredTrendingSeries = remember(uiState.trendingSeries) {
        uiState.trendingSeries.distinctBy { it.id }
    }
    // Keep Trending Anime bound to its own feed. The general anime catalogue is
    // rendered in the Anime category; using it here made sections show different datasets.
    val filteredAnimeSeries = remember(uiState.trendingAnime) {
        uiState.trendingAnime.distinctBy { it.id }
    }

    val homeListState = rememberLazyListState()
    val activeHomeTab = when (selectedCategory) {
        HomeNavCategory.MOVIES -> 1
        HomeNavCategory.SERIES -> 2
        HomeNavCategory.ANIME -> 3
        HomeNavCategory.HOME -> 0
    }
    val activeHomePaginator = when (selectedCategory) {
        HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) viewModel.movieGenrePaginator else viewModel.popularPaginator
        HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) viewModel.seriesGenrePaginator else viewModel.popularPaginator
        HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) viewModel.animeGenrePaginator else viewModel.popularPaginator
        HomeNavCategory.HOME -> viewModel.popularPaginator
    }
    val activeHomePageState = when (selectedCategory) {
        HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) homeMovieGenrePageState else homeMoviesPageState
        HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) homeSeriesGenrePageState else homeSeriesPageState
        HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) homeAnimeGenrePageState else homeAnimePageState
        HomeNavCategory.HOME -> homeMoviesPageState
    }

    LaunchedEffect(
        selectedCategory, selectedMovieGenre?.id, selectedSeriesGenre?.id, selectedAnimeGenre?.id
    ) {
        homeListState.scrollToItem(0)
    }
    LaunchedEffect(selectedCategory, selectedMovieGenre?.id, selectedSeriesGenre?.id, selectedAnimeGenre?.id) {
        when (selectedCategory) {
            HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) viewModel.movieGenrePaginator.ensureTabLoaded(1) else viewModel.popularPaginator.ensureTabLoaded(1)
            HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) viewModel.seriesGenrePaginator.ensureTabLoaded(2) else viewModel.popularPaginator.ensureTabLoaded(2)
            HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) viewModel.animeGenrePaginator.ensureTabLoaded(3) else viewModel.popularPaginator.ensureTabLoaded(3)
            HomeNavCategory.HOME -> Unit
        }
    }
    LaunchedEffect(
        selectedCategory, selectedMovieGenre?.id, selectedSeriesGenre?.id, selectedAnimeGenre?.id,
        activeHomePageState.currentPage, activeHomePageState.items.size,
        activeHomePageState.isLoadingFirstPage, activeHomePageState.isLoadingMore,
        activeHomePageState.hasNextPage
    ) {
        if (selectedCategory != HomeNavCategory.HOME && activeHomePageState.hasNextPage) {
            snapshotFlow {
                val layout = homeListState.layoutInfo
                val total = layout.totalItemsCount
                val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                total > 0 && lastVisible >= total - 5
            }.collect { atEnd ->
                if (atEnd) activeHomePaginator.loadMore(activeHomeTab)
            }
        }
    }

    BackHandler(enabled = selectedCategory != HomeNavCategory.HOME) {
        selectedCategory = HomeNavCategory.HOME
        isGenreDropdownExpanded = false
        selectedMovieGenre = null
        selectedSeriesGenre = null
        selectedAnimeGenre = null
        viewModel.selectHomeMovieGenre(null)
        viewModel.selectHomeSeriesGenre(null)
        viewModel.selectHomeAnimeGenre(null)
    }

    PullToRefreshBox(
        isRefreshing = uiState.isLoading || (selectedCategory != HomeNavCategory.HOME && activeHomePageState.isLoadingFirstPage),
        onRefresh = {
            when (selectedCategory) {
                HomeNavCategory.HOME -> viewModel.loadData(forceRefresh = true)
                HomeNavCategory.MOVIES -> if (selectedMovieGenre != null) viewModel.movieGenrePaginator.refresh(1) else viewModel.popularPaginator.refresh(1)
                HomeNavCategory.SERIES -> if (selectedSeriesGenre != null) viewModel.seriesGenrePaginator.refresh(2) else viewModel.popularPaginator.refresh(2)
                HomeNavCategory.ANIME -> if (selectedAnimeGenre != null) viewModel.animeGenrePaginator.refresh(3) else viewModel.popularPaginator.refresh(3)
            }
        },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        if (!hasRemoteContent) {
            val isOffline = !com.example.utils.NetworkUtils.isInternetAvailable(context)
            if (uiState.isLoading || isOffline) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    MediaScreenSkeleton()
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        val isAuthError = uiState.error?.contains("401") == true ||
                                uiState.error?.contains("Auth") == true ||
                                uiState.error?.contains("API key") == true
                        val errorText = when {
                            isAuthError -> "TMDB API key configuration required in Secrets panel."
                            !uiState.error.isNullOrBlank() -> uiState.error!!
                            else -> stringResource(R.string.check_net_retry)
                        }
                        Text(
                            text = errorText,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { viewModel.loadData(forceRefresh = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                state = homeListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {
                // Hero Section
                item(key = "home_hero_section") {
                    val heroMovies = remember(uiState.trendingMovies) { uiState.trendingMovies }
                    if (heroMovies.isNotEmpty()) {
                        HeroCarousel(items = heroMovies.take(5).map { HeroItem(it.id, it.title, it.backdropUrl, true, contentType = ContentType.MOVIE) }, onClick = onMovieClick)
                    }
                }

                item(key = "home_category_spacer_top") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Category Navigation Row (ALWAYS visible at all times)
                item(key = "home_category_navigation_row") {
                    CategoryNavigationRow(
                        categories = homeCategories,
                        selectedCategory = selectedCategory,
                        onCategorySelected = { category ->
                            val destination = if (category == selectedCategory && category != HomeNavCategory.HOME) {
                                HomeNavCategory.HOME
                            } else category
                            selectedCategory = destination
                            isGenreDropdownExpanded = false
                            if (destination == HomeNavCategory.HOME) {
                                selectedMovieGenre = null
                                selectedSeriesGenre = null
                                selectedAnimeGenre = null
                                viewModel.selectHomeMovieGenre(null)
                                viewModel.selectHomeSeriesGenre(null)
                                viewModel.selectHomeAnimeGenre(null)
                            }
                        },
                        categoryTitle = { stringResource(it.titleRes) },
                        categoryTag = { it.id }
                    )
                }

                item(key = "home_category_spacer_bottom") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when (selectedCategory) {
                    HomeNavCategory.HOME -> {
                        // 1. Continue Watching
                        if (historyItems.isNotEmpty()) {
                            item(key = "section_continue_watching") {
                                SectionTitle(stringResource(R.string.continue_watching), onSeeAllClick = onNavigateToWatching)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    items(historyItems, key = { it.id }) { item ->
                                        ContinueWatchingCardShared(
                                            item = item,
                                            onClick = {
                                                if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id)
                                            },
                                            onPlayClick = {
                                                if (item.isMovie) {
                                                    if (onPlayMovie != null) onPlayMovie(item.id) else onMovieClick(item.id)
                                                } else {
                                                    if (onPlaySeries != null) onPlaySeries(item.id) else onSeriesClick(item.id)
                                                }
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // 2. Trending Now
                        if (uiState.trendingMovies.isNotEmpty() || uiState.trendingSeries.isNotEmpty()) {
                            item(key = "section_trending_now") {
                                SectionTitle(stringResource(R.string.trending_now), onSeeAllClick = onNavigateToTrending)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(trendingMix, key = { _, item -> "trending_${if (item.isMovie) "m" else "s"}_${item.id}" }) { index, item ->
                                        val onClick = remember(item.id, item.isMovie) {
                                            { if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id) }
                                        }
                                        val onLongClick = remember(item.id, item.isMovie, item.contentType, item.title, item.posterUrl) {
                                            { onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) }
                                        }
                                        MediaCard(
                                            title = item.title,
                                            posterUrl = item.posterUrl,
                                            rank = index + 1,
                                            rating = item.rating,
                                            year = item.year.toString(),
                                            isMovie = item.isMovie,
                                            contentType = item.contentType,
                                            mediaId = item.id,
                                            onClick = onClick,
                                            onLongClick = onLongClick
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // 3. New Releases
                        if (uiState.newReleasesMovies.isNotEmpty() || uiState.newReleasesSeries.isNotEmpty()) {
                            item(key = "section_new_releases") {
                                SectionTitle(stringResource(R.string.new_releases), onSeeAllClick = onNavigateToNewReleases)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(newReleasesMix, key = { _, item -> "new_releases_${if (item.isMovie) "m" else "s"}_${item.id}" }) { index, item ->
                                        val onClick = remember(item.id, item.isMovie) {
                                            { if (item.isMovie) onMovieClick(item.id) else onSeriesClick(item.id) }
                                        }
                                        val onLongClick = remember(item.id, item.isMovie, item.contentType, item.title, item.posterUrl) {
                                            { onMediaLongClick(item.id, item.title, item.posterUrl, item.isMovie, item.contentType) }
                                        }
                                        MediaCard(
                                            title = item.title,
                                            posterUrl = item.posterUrl,
                                            rank = index + 1,
                                            rating = item.rating,
                                            year = item.year.toString(),
                                            isMovie = item.isMovie,
                                            contentType = item.contentType,
                                            mediaId = item.id,
                                            onClick = onClick,
                                            onLongClick = onLongClick
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // 4. Trending Movies
                        if (filteredTrendingMovies.isNotEmpty()) {
                            item(key = "section_trending_movies") {
                                SectionTitle(stringResource(R.string.trending_movies), onSeeAllClick = onNavigateToTrending)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(filteredTrendingMovies, key = { _, movie -> "home_m_trend_${movie.id}" }) { index, movie ->
                                        val onClick = remember(movie.id) {
                                            { onMovieClick(movie.id) }
                                        }
                                        val onLongClick = remember(movie.id, movie.title, movie.posterUrl) {
                                            { onMediaLongClick(movie.id, movie.title, movie.posterUrl, true, ContentType.MOVIE) }
                                        }
                                        MediaCard(
                                            title = movie.title,
                                            posterUrl = movie.posterUrl,
                                            rank = index + 1,
                                            rating = movie.rating,
                                            year = movie.year.toString(),
                                            isMovie = true,
                                            contentType = ContentType.MOVIE,
                                            mediaId = movie.id,
                                            onClick = onClick,
                                            onLongClick = onLongClick
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // 5. Trending Series
                        if (filteredTrendingSeries.isNotEmpty()) {
                            item(key = "section_trending_series") {
                                SectionTitle(stringResource(R.string.trending_series), onSeeAllClick = onNavigateToTrending)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(filteredTrendingSeries, key = { _, series -> "home_s_trend_${series.id}" }) { index, series ->
                                        val resolvedType = ContentTypeResolver.resolveSeries(series)
                                        val onClick = remember(series.id) {
                                            { onSeriesClick(series.id) }
                                        }
                                        val onLongClick = remember(series.id, resolvedType, series.title, series.posterUrl) {
                                            { onMediaLongClick(series.id, series.title, series.posterUrl, false, resolvedType) }
                                        }
                                        MediaCard(
                                            title = series.title,
                                            posterUrl = series.posterUrl,
                                            rank = index + 1,
                                            rating = series.rating,
                                            year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                                androidx.compose.ui.platform.LocalContext.current,
                                                series.seasons.size,
                                                if (series.year > 0) series.year.toString() else ""
                                            ),
                                            isMovie = false,
                                            contentType = resolvedType,
                                            mediaId = series.id,
                                            onClick = onClick,
                                            onLongClick = onLongClick
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // 6. Trending Anime
                        if (filteredAnimeSeries.isNotEmpty()) {
                            item(key = "section_trending_anime") {
                                SectionTitle(stringResource(R.string.trending_anime), onSeeAllClick = onNavigateToTrending)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(filteredAnimeSeries, key = { _, series -> "home_a_trend_${series.id}" }) { index, series ->
                                        val resolvedType = ContentTypeResolver.resolveSeries(series)
                                        val onClick = remember(series.id) {
                                            { onSeriesClick(series.id) }
                                        }
                                        val onLongClick = remember(series.id, resolvedType, series.title, series.posterUrl) {
                                            { onMediaLongClick(series.id, series.title, series.posterUrl, false, resolvedType) }
                                        }
                                        MediaCard(
                                            title = series.title,
                                            posterUrl = series.posterUrl,
                                            rank = index + 1,
                                            rating = series.rating,
                                            year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                                androidx.compose.ui.platform.LocalContext.current,
                                                series.seasons.size,
                                                if (series.year > 0) series.year.toString() else ""
                                            ),
                                            isMovie = false,
                                            contentType = resolvedType,
                                            mediaId = series.id,
                                            onClick = onClick,
                                            onLongClick = onLongClick
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }

                        // 7. Coming Soon
                        if (upcomingMix.isNotEmpty()) {
                            item(key = "section_coming_soon") {
                                SectionTitle(stringResource(R.string.coming_soon), onSeeAllClick = onNavigateToUpcoming)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    itemsIndexed(upcomingMix, key = { _, item -> "home_upcoming_${item.id}" }) { index, item ->
                                        val onClick = remember(item.id) {
                                            { onMovieClick(item.id) }
                                        }
                                        val onLongClick = remember(item.id, item.title, item.posterUrl) {
                                            { onMediaLongClick(item.id, item.title, item.posterUrl, true, ContentType.MOVIE) }
                                        }
                                        MediaCard(
                                            title = item.title,
                                            posterUrl = item.posterUrl,
                                            rank = index + 1,
                                            rating = null,
                                            year = item.releaseDate?.take(4)?.takeIf { it.toIntOrNull() != null }
                                                ?: item.year.takeIf { it > 0 }?.toString(),
                                            isMovie = true,
                                            contentType = ContentType.MOVIE,
                                            mediaId = item.id,
                                            onClick = onClick,
                                            onLongClick = onLongClick
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(24.dp))
                            }
                        }
                    }
                    HomeNavCategory.MOVIES -> {
                        item(key = "home_movies_genre_dropdown") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = selectedMovieGenre,
                                        title = stringResource(R.string.genre_filter_prefix, selectedMovieGenre?.name ?: stringResource(R.string.genre_all)),
                                        icon = Icons.Default.FilterAlt
                                    ),
                                    options = movieGenreOptions,
                                    expanded = isGenreDropdownExpanded,
                                    onExpandedChange = { isGenreDropdownExpanded = it },
                                    onOptionSelected = { option ->
                                        selectedMovieGenre = option.value
                                        viewModel.selectHomeMovieGenre(option.value)
                                    },
                                    leadingIcon = Icons.Default.FilterAlt,
                                    testTag = "home_genre_dropdown"
                                )

                                AnimatedVisibility(
                                    visible = selectedMovieGenre != null,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        ActiveFilterChip(
                                            filterName = selectedMovieGenre?.name ?: "",
                                            onClear = {
                                                selectedMovieGenre = null
                                                viewModel.selectHomeMovieGenre(null)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "home_movies_spacer") { Spacer(modifier = Modifier.height(16.dp)) }

                        if (displayCategoryMovies.isEmpty()) {
                            item(key = "home_movies_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = stringResource(R.string.no_genre_results),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = { selectedMovieGenre = null },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(stringResource(R.string.filter_clear))
                                        }
                                    }
                                }
                            }
                        } else {
                            verticalGridItems(
                                items = displayCategoryMovies,
                                columns = 3,
                                horizontalPadding = 16.dp,
                                key = { it.id }
                            ) { movie ->
                                val onClick = remember(movie.id) { { onMovieClick(movie.id) } }
                                val onLongClick = remember(movie.id, movie.title, movie.posterUrl) {
                                    { onMediaLongClick(movie.id, movie.title, movie.posterUrl, true, ContentType.MOVIE) }
                                }
                                MediaCard(
                                    title = movie.title,
                                    posterUrl = movie.posterUrl,
                                    rating = movie.rating,
                                    year = movie.year.toString(),
                                    isMovie = true,
                                    contentType = ContentType.MOVIE,
                                    mediaId = movie.id,
                                    onClick = onClick,
                                    onLongClick = onLongClick
                                )
                            }
                        }
                    }
                    HomeNavCategory.SERIES -> {
                        item(key = "home_series_genre_dropdown") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = selectedSeriesGenre,
                                        title = stringResource(R.string.genre_filter_prefix, selectedSeriesGenre?.name ?: stringResource(R.string.genre_all)),
                                        icon = Icons.Default.FilterAlt
                                    ),
                                    options = tvGenreOptions,
                                    expanded = isGenreDropdownExpanded,
                                    onExpandedChange = { isGenreDropdownExpanded = it },
                                    onOptionSelected = { option ->
                                        selectedSeriesGenre = option.value
                                        viewModel.selectHomeSeriesGenre(option.value)
                                    },
                                    leadingIcon = Icons.Default.FilterAlt,
                                    testTag = "home_genre_dropdown"
                                )

                                AnimatedVisibility(
                                    visible = selectedSeriesGenre != null,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        ActiveFilterChip(
                                            filterName = selectedSeriesGenre?.name ?: "",
                                            onClear = {
                                                selectedSeriesGenre = null
                                                viewModel.selectHomeSeriesGenre(null)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "home_series_spacer") { Spacer(modifier = Modifier.height(16.dp)) }

                        if (displayCategorySeries.isEmpty()) {
                            item(key = "home_series_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = stringResource(R.string.no_genre_results),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = { selectedSeriesGenre = null },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(stringResource(R.string.filter_clear))
                                        }
                                    }
                                }
                            }
                        } else {
                            verticalGridItems(
                                items = displayCategorySeries,
                                columns = 3,
                                horizontalPadding = 16.dp,
                                key = { it.id }
                            ) { series ->
                                val resolvedType = ContentTypeResolver.resolveSeries(series)
                                val onClick = remember(series.id) { { onSeriesClick(series.id) } }
                                val onLongClick = remember(series.id, resolvedType, series.title, series.posterUrl) {
                                    { onMediaLongClick(series.id, series.title, series.posterUrl, false, resolvedType) }
                                }
                                MediaCard(
                                    title = series.title,
                                    posterUrl = series.posterUrl,
                                    rating = series.rating,
                                    year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                        androidx.compose.ui.platform.LocalContext.current,
                                        series.seasons.size,
                                        if (series.year > 0) series.year.toString() else ""
                                    ),
                                    isMovie = false,
                                    contentType = resolvedType,
                                    mediaId = series.id,
                                    onClick = onClick,
                                    onLongClick = onLongClick
                                )
                            }
                        }
                    }
                    HomeNavCategory.ANIME -> {
                        item(key = "home_anime_genre_dropdown") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                CineStreamDropdownSelector(
                                    label = stringResource(R.string.filter_category),
                                    selectedOption = DropdownOption(
                                        value = selectedAnimeGenre,
                                        title = stringResource(R.string.genre_filter_prefix, selectedAnimeGenre?.name ?: stringResource(R.string.genre_all)),
                                        icon = Icons.Default.FilterAlt
                                    ),
                                    options = animeGenreOptions,
                                    expanded = isGenreDropdownExpanded,
                                    onExpandedChange = { isGenreDropdownExpanded = it },
                                    onOptionSelected = { option ->
                                        selectedAnimeGenre = option.value
                                        viewModel.selectHomeAnimeGenre(option.value)
                                    },
                                    leadingIcon = Icons.Default.FilterAlt,
                                    testTag = "home_genre_dropdown"
                                )

                                AnimatedVisibility(
                                    visible = selectedAnimeGenre != null,
                                    enter = expandVertically() + fadeIn(),
                                    exit = shrinkVertically() + fadeOut()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        ActiveFilterChip(
                                            filterName = selectedAnimeGenre?.name ?: "",
                                            onClear = {
                                                selectedAnimeGenre = null
                                                viewModel.selectHomeAnimeGenre(null)
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "home_anime_spacer") { Spacer(modifier = Modifier.height(16.dp)) }

                        if (displayCategoryAnime.isEmpty()) {
                            item(key = "home_anime_empty") {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 40.dp, horizontal = 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = stringResource(R.string.no_genre_results),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(12.dp))
                                        OutlinedButton(
                                            onClick = { selectedAnimeGenre = null },
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(stringResource(R.string.filter_clear))
                                        }
                                    }
                                }
                            }
                        } else {
                            verticalGridItems(
                                items = displayCategoryAnime,
                                columns = 3,
                                horizontalPadding = 16.dp,
                                key = { it.id }
                            ) { anime ->
                                val onClick = remember(anime.id) { { onSeriesClick(anime.id) } }
                                val onLongClick = remember(anime.id, anime.title, anime.posterUrl) {
                                    { onMediaLongClick(anime.id, anime.title, anime.posterUrl, false, ContentType.ANIME) }
                                }
                                MediaCard(
                                    title = anime.title,
                                    posterUrl = anime.posterUrl,
                                    rating = anime.rating,
                                    year = com.example.utils.SeasonFormatter.formatSeasonOrFallback(
                                        androidx.compose.ui.platform.LocalContext.current,
                                        anime.seasons.size,
                                        if (anime.year > 0) anime.year.toString() else ""
                                    ),
                                    isMovie = false,
                                    contentType = ContentType.ANIME,
                                    mediaId = anime.id,
                                    onClick = onClick,
                                    onLongClick = onLongClick
                                )
                            }
                        }
                    }
                }
        }
    }
}

    if (showBottomSheet) {
        MediaActionBottomSheet(
            isMovie = bottomSheetIsMovie,
            mediaId = selectedMediaId,
            title = selectedMediaTitle,
            posterUrl = selectedMediaPoster,
            contentType = bottomSheetContentType,
            onDismissRequest = { showBottomSheet = false },
            onDownloadStart = { quality ->
                scope.launch {
                    downloadRepository.addToDownloads(DownloadItem(
                        id = selectedMediaId,
                        mediaId = selectedMediaId,
                        title = selectedMediaTitle,
                        posterUrl = selectedMediaPoster,
                        isMovie = bottomSheetIsMovie,
                        quality = quality
                    ))
                    Toast.makeText(context, context.getString(R.string.download_started), Toast.LENGTH_SHORT).show()
                }
            },
            onAddToLibrary = {
                scope.launch {
                    val libItem = LibraryItem.create(
                        contentType = bottomSheetContentType,
                        tmdbId = selectedMediaId,
                        title = selectedMediaTitle,
                        posterUrl = selectedMediaPoster
                    )
                    libraryRepository.addToLibrary(libItem)
                    Toast.makeText(context, context.getString(R.string.added_to_library), Toast.LENGTH_SHORT).show()
                }
            }
        )
    }
}



@Composable
fun SectionTitle(title: String, onSeeAllClick: (() -> Unit)? = null) {
    val parts = title.split(" ", limit = 2)
    val firstWord = parts.getOrNull(0) ?: ""
    val rest = parts.getOrNull(1) ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row {
                Text(
                    text = firstWord,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (rest.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = rest,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .width(28.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        
        if (onSeeAllClick != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onSeeAllClick() }
            ) {
                Text(
                    text = stringResource(R.string.see_all),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.see_all),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    

}
}
