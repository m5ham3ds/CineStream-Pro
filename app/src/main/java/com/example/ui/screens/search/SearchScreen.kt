package com.example.ui.screens.search

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.example.data.model.LibraryItem
import com.example.data.repository.LibraryRepository
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Category
import androidx.compose.ui.draw.shadow
import com.example.ui.components.DropdownOption
import com.example.ui.components.CineStreamDropdownSelector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh

import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material.icons.outlined.LocalMovies
import androidx.compose.material.icons.outlined.TheaterComedy
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.ViewModelFactory
import com.example.ui.components.MediaCard
import com.example.ui.components.SectionTitleShared
import com.example.data.model.ContentType
import com.example.domain.search.MediaDisplayTitleResolver

data class UnifiedMediaResult(
    val id: String,
    val title: String,
    val canonicalTitle: String,
    val posterUrl: String,
    val isMovie: Boolean,
    val isDubbed: Boolean,
    val isUnverified: Boolean,
    val variantMode: String,
    val providerId: String?,
    val contentType: String,
    val canonicalPosterUrl: String? = null,
    val providerPosterUrl: String? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onMediaClick: (String, Boolean) -> Unit = { _, _ -> },
    onMediaClickWithMode: ((String, Boolean, String) -> Unit)? = null,
    onNavigateToTrending: () -> Unit = {},
    viewModel: SearchViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by rememberSaveable { mutableStateOf(uiState.query) }
    val scrollState = rememberScrollState()
    val ptrState = rememberPullToRefreshState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryRepository = remember { LibraryRepository(context) }
    var selectedItemForFavorite by remember { mutableStateOf<UnifiedMediaResult?>(null) }
    var showFilterPanel by remember { mutableStateOf(false) }

    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    LaunchedEffect(uiState.query) {
        if (searchQuery != uiState.query) {
            searchQuery = uiState.query
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.initializeIfNeeded()
    }

    val searchResults = remember(uiState.filteredMovieResults, uiState.filteredSeriesResults, uiState.isDubbedIntent) {
        val movieItems = uiState.filteredMovieResults.map { m ->
            val displayInfo = MediaDisplayTitleResolver.resolveForMovie(m, isDubbedIntent = uiState.isDubbedIntent)
            val isDubbed = displayInfo.isConfirmedDubbed
            val isUnverified = if (!isDubbed && uiState.isDubbedIntent) SearchViewModel.hasUnverifiedCandidate(m) else false
            val displayPoster = displayInfo.displayPosterUrl ?: m.posterUrl
            UnifiedMediaResult(
                id = m.id,
                title = displayInfo.displayTitle,
                canonicalTitle = m.title,
                posterUrl = displayPoster,
                isMovie = true,
                isDubbed = isDubbed,
                isUnverified = isUnverified,
                variantMode = if (isDubbed) "dubbed" else "subtitled",
                providerId = displayInfo.providerId ?: m.providerId,
                contentType = ContentType.MOVIE,
                canonicalPosterUrl = m.posterUrl,
                providerPosterUrl = displayInfo.providerPosterUrl
            )
        }
        val seriesItems = uiState.filteredSeriesResults.map { s ->
            val displayInfo = MediaDisplayTitleResolver.resolveForSeries(s, isDubbedIntent = uiState.isDubbedIntent)
            val isDubbed = displayInfo.isConfirmedDubbed
            val isUnverified = if (!isDubbed && uiState.isDubbedIntent) SearchViewModel.hasUnverifiedCandidate(s) else false
            val displayPoster = displayInfo.displayPosterUrl ?: s.posterUrl
            UnifiedMediaResult(
                id = s.id,
                title = displayInfo.displayTitle,
                canonicalTitle = s.title,
                posterUrl = displayPoster,
                isMovie = false,
                isDubbed = isDubbed,
                isUnverified = isUnverified,
                variantMode = if (isDubbed) "dubbed" else "subtitled",
                providerId = displayInfo.providerId ?: s.providerId,
                contentType = ContentType.TV,
                canonicalPosterUrl = s.posterUrl,
                providerPosterUrl = displayInfo.providerPosterUrl
            )
        }
        val combined = movieItems + seriesItems
        if (uiState.isDubbedIntent) {
            val trigger = com.example.domain.search.SearchTriggerParser.parse(uiState.query)
            com.example.domain.search.DubbedSearchRankingEngine.rankUnifiedMedia(
                items = combined,
                cleanQuery = trigger.cleanQuery,
                isDubbedIntent = true,
                titleExtractor = { it.title },
                canonicalTitleExtractor = { it.canonicalTitle },
                isDubbedExtractor = { it.isDubbed }
            )
        } else {
            combined
        }
    }

    PullToRefreshBox(
        isRefreshing = uiState.isSearching,
        onRefresh = { viewModel.refresh() },
        state = ptrState,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = 100.dp)
        ) {
        // Search Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .clip(RoundedCornerShape(percent = 50))
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Search, 
                contentDescription = stringResource(R.string.search), 
                tint = MaterialTheme.colorScheme.primary, 
                modifier = Modifier.size(24.dp).clickable {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    viewModel.submitSearch(searchQuery)
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it; viewModel.onQueryChange(it) },
                placeholder = { Text(stringResource(R.string.search_hint), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp) },
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedTextColor = MaterialTheme.colorScheme.onBackground,
                    unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    viewModel.submitSearch(searchQuery)
                })
            )
            if (searchQuery.isNotEmpty()) {
                IconButton(
                    onClick = {
                        keyboardController?.hide()
                        focusManager.clearFocus()
                        searchQuery = ""
                        viewModel.onQueryChange("")
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Box(modifier = Modifier.width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.surfaceVariant))
            Spacer(modifier = Modifier.width(12.dp))
            Icon(
                Icons.Default.FilterAlt,
                contentDescription = stringResource(R.string.filter),
                tint = if (showFilterPanel || uiState.selectedContentType != "ALL" || uiState.selectedGenreId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp).clickable {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    showFilterPanel = !showFilterPanel
                }
            )
        }

        // Professional Dropdown Filter Panel (Anchored to Filter button)
        AnimatedVisibility(
            visible = showFilterPanel,
            enter = fadeIn(animationSpec = tween(180)) + expandVertically(
                animationSpec = tween(220, easing = FastOutSlowInEasing)
            ),
            exit = fadeOut(animationSpec = tween(140)) + shrinkVertically(
                animationSpec = tween(180, easing = FastOutSlowInEasing)
            )
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .shadow(10.dp, RoundedCornerShape(18.dp)),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.FilterAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.filter_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (uiState.selectedContentType != "ALL" || uiState.selectedGenreId != null) {
                                TextButton(onClick = { viewModel.clearFilters() }) {
                                    Text(
                                        text = stringResource(R.string.filter_clear),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            IconButton(
                                onClick = { showFilterPanel = false },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.close),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                    )

                    // Content Type Selection
                    Text(
                        text = stringResource(R.string.filter_content_type),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val types = listOf(
                            "ALL" to stringResource(R.string.filter_all),
                            "MOVIE" to stringResource(R.string.filter_movies),
                            "TV" to stringResource(R.string.filter_series),
                            "ANIME" to stringResource(R.string.filter_anime)
                        )
                        types.forEach { (typeKey, typeLabel) ->
                            val isSelected = uiState.selectedContentType == typeKey
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    )
                                    .clickable { viewModel.setContentTypeFilter(typeKey) }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = typeLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Genres Vertical Dropdown Selector
                    val allGenresStr = stringResource(R.string.genre_all)
                    var isGenreDropdownExpanded by remember { mutableStateOf(false) }
                    val genreOptions = remember(uiState.currentAvailableGenres, allGenresStr) {
                        listOf(
                            DropdownOption<Int?>(
                                value = null,
                                title = allGenresStr
                            )
                        ) + uiState.currentAvailableGenres.map {
                            DropdownOption<Int?>(
                                value = it.id,
                                title = it.name
                            )
                        }
                    }

                    val selectedGenreOption = remember(genreOptions, uiState.selectedGenreId) {
                        genreOptions.find { it.value == uiState.selectedGenreId } ?: genreOptions[0]
                    }

                    CineStreamDropdownSelector(
                        label = stringResource(R.string.filter_genres),
                        selectedOption = selectedGenreOption,
                        options = genreOptions,
                        expanded = isGenreDropdownExpanded,
                        onExpandedChange = { isGenreDropdownExpanded = it },
                        onOptionSelected = { option ->
                            viewModel.setGenreFilter(option.value, if (option.value != null) option.title else null)
                        },
                        leadingIcon = Icons.Default.Category,
                        testTag = "search_genre_dropdown"
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { showFilterPanel = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(
                            text = stringResource(R.string.filter_apply),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }

        // Active Filter Chips Indicator
        if (uiState.selectedContentType != "ALL" || uiState.selectedGenreId != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.selectedContentType != "ALL") {
                    val label = when (uiState.selectedContentType) {
                        "MOVIE" -> stringResource(R.string.filter_movies)
                        "TV" -> stringResource(R.string.filter_series)
                        "ANIME" -> stringResource(R.string.filter_anime)
                        else -> ""
                    }
                    InputChip(
                        selected = true,
                        onClick = { viewModel.setContentTypeFilter("ALL") },
                        label = { Text(label) },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
                if (uiState.selectedGenreName != null) {
                    InputChip(
                        selected = true,
                        onClick = { viewModel.setGenreFilter(null, null) },
                        label = { Text(uiState.selectedGenreName ?: "") },
                        trailingIcon = {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
                TextButton(onClick = { viewModel.clearFilters() }) {
                    Text(stringResource(R.string.filter_clear), fontSize = 12.sp)
                }
            }
        }

        if (searchQuery.isEmpty()) {
            if (uiState.trendingNow.isEmpty()) {
                if (uiState.isSearching) {
                    com.example.ui.components.SearchScreenSkeleton()
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 48.dp, start = 24.dp, end = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.check_net_retry),
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.refresh() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text(stringResource(R.string.retry), color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            } else {
            // Popular Searches
            SectionTitleSharedWithAction(stringResource(R.string.popular_searches), stringResource(R.string.clear_all))
            
            @Composable
            fun SearchChip(text: String) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(percent = 50))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable { 
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            searchQuery = text
                            viewModel.submitSearch(text) 
                        },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.TrendingUp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }

            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SearchChip("Game of Thrones")
                SearchChip("Stranger Things")
                SearchChip("The Last of Us")
                SearchChip("Interstellar")
                SearchChip("Money Heist")
                SearchChip("Breaking Bad")
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Browse by Category
            Text(text = stringResource(R.string.browse_by_category),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            
            val cats = listOf(
                Triple("Movies", Icons.Outlined.LocalMovies, "MOVIE"),
                Triple("Series", Icons.Outlined.LiveTv, "TV"),
                Triple(stringResource(R.string.anime), Icons.Outlined.TheaterComedy, "ANIME"),
                Triple("Documentaries", Icons.Outlined.Description, "DOC"),
                Triple("Kids", Icons.Outlined.ChildCare, "KIDS")
            )
            
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(cats, key = { it.first }) { cat ->
                    Column(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .clickable {
                                when (cat.third) {
                                    "MOVIE" -> viewModel.setContentTypeFilter("MOVIE")
                                    "TV" -> viewModel.setContentTypeFilter("TV")
                                    "ANIME" -> viewModel.setContentTypeFilter("ANIME")
                                    "DOC" -> {
                                        viewModel.setContentTypeFilter("MOVIE")
                                        viewModel.setGenreFilter(99, "وثائقي")
                                    }
                                    "KIDS" -> {
                                        viewModel.setContentTypeFilter("TV")
                                        viewModel.setGenreFilter(10762, "أطفال")
                                    }
                                }
                                showFilterPanel = true
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(cat.second, contentDescription = cat.first, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(cat.first, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))

            // Trending Now
            SectionTitleShared(stringResource(R.string.trending_now), onSeeAllClick = onNavigateToTrending)
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (uiState.trendingNow.isEmpty()) {
                    items(4) { index ->
                        MediaCard(
                            title = stringResource(R.string.loading),
                            posterUrl = "",
                            rank = index + 1,
                            rating = 0.0,
                            year = "",
                            isMovie = true,
                            onClick = { }
                        )
                    }
                } else {
                    itemsIndexed(uiState.trendingNow, key = { _, movie -> movie.id }) { index, movie ->
                        MediaCard(
                            title = movie.title,
                            posterUrl = movie.posterUrl,
                            rank = index + 1,
                            rating = movie.rating,
                            year = movie.year.toString(),
                            isMovie = true,
                            onClick = { onMediaClick(movie.id, true) }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Recent Searches
            var recentsList by remember {
                mutableStateOf(
                    listOf(
                        Triple("Interstellar", "Movie • Sci-Fi", "https://image.tmdb.org/t/p/w500/gEU2QniE6E77NI6lCU6MxlNBvIx.jpg"),
                        Triple("Money Heist", "Series • Crime", "https://image.tmdb.org/t/p/w500/reEMJA1uzscCbkpeRJeTT2bjqUp.jpg"),
                        Triple("Breaking Bad", "Series • Drama", "https://image.tmdb.org/t/p/w500/ggFHVNu6YYI5L9pCfOacjizRGt.jpg"),
                        Triple("The Dark Knight", "Movie • Action", "https://image.tmdb.org/t/p/w500/qJ2tW6WMUDux911r6m7haRef0WH.jpg")
                    )
                )
            }

            if (recentsList.isNotEmpty()) {
                SectionTitleSharedWithAction(
                    stringResource(R.string.recent_searches),
                    stringResource(R.string.clear_all),
                    onActionClick = { recentsList = emptyList() }
                )
                
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    recentsList.forEach { recent ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .clickable {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    searchQuery = recent.first
                                    viewModel.submitSearch(recent.first)
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = recent.third,
                                contentDescription = recent.first,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(50.dp, 50.dp).clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(recent.first, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(recent.second, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            }
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.remove),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .size(20.dp)
                                    .clickable {
                                        recentsList = recentsList.filterNot { it.first == recent.first }
                                    }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
            }
            
            // Can't find what you're looking for?
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(80.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                         Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.cant_find), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.try_different_keyword), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Box(
                            modifier = Modifier
                                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(percent = 50))
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                                .clickable {
                                    showFilterPanel = true
                                }
                        ) {
                            Text(stringResource(R.string.explore_all_content), color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            }
        } else {
            // Search Results
            if (uiState.isSearching && searchResults.isEmpty()) {
                com.example.ui.components.SearchScreenSkeleton()
            } else if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp, start = 24.dp, end = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.no_results),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                if (uiState.isSearching) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = stringResource(R.string.results_for, searchQuery),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    searchResults.forEach { media ->
                        val isItemDubbed = media.isDubbed
                        val isItemUnverified = media.isUnverified

                        @OptIn(ExperimentalFoundationApi::class)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .combinedClickable(
                                    onClick = {
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        if (media.isMovie) {
                                            val m = uiState.movieResults.firstOrNull { it.id == media.id }
                                                ?: uiState.filteredMovieResults.firstOrNull { it.id == media.id }
                                            if (m != null) {
                                                val movieTargetMode = if (isItemDubbed) "ARABIC_DUBBED" else "STANDARD"
                                                com.example.ui.screens.details.MovieDetailsViewModel.saveState(m.id, com.example.ui.screens.details.MovieDetailsUiState(movie = m, isLoading = false), experienceMode = movieTargetMode)
                                                val encId = try { java.net.URLEncoder.encode(m.id, "UTF-8") } catch (_: Exception) { m.id }
                                                com.example.ui.screens.details.MovieDetailsViewModel.saveState(encId, com.example.ui.screens.details.MovieDetailsUiState(movie = m, isLoading = false), experienceMode = movieTargetMode)
                                                val vMode = if (isItemDubbed) "dubbed" else "subtitled"
                                                com.example.utils.MediaDetailsCacheManager.saveMovieDetails(context, m, variantMode = vMode)
                                            }
                                        } else {
                                            val s = uiState.seriesResults.firstOrNull { it.id == media.id }
                                                ?: uiState.filteredSeriesResults.firstOrNull { it.id == media.id }
                                            if (s != null) {
                                                val seriesTargetMode = if (isItemDubbed) "ARABIC_DUBBED" else "STANDARD"
                                                com.example.ui.screens.details.SeriesDetailsViewModel.saveState(s.id, com.example.ui.screens.details.SeriesDetailsUiState(series = s, isLoading = false), experienceMode = seriesTargetMode)
                                                val encId = try { java.net.URLEncoder.encode(s.id, "UTF-8") } catch (_: Exception) { s.id }
                                                com.example.ui.screens.details.SeriesDetailsViewModel.saveState(encId, com.example.ui.screens.details.SeriesDetailsUiState(series = s, isLoading = false), experienceMode = seriesTargetMode)
                                                val vMode = if (isItemDubbed) "dubbed" else "subtitled"
                                                com.example.utils.MediaDetailsCacheManager.saveSeriesDetails(context, s, variantMode = vMode)
                                            }
                                        }
                                        val targetMode = if (isItemDubbed) "ARABIC_DUBBED" else "STANDARD"
                                        if (onMediaClickWithMode != null) {
                                            onMediaClickWithMode(media.id, media.isMovie, targetMode)
                                        } else {
                                            onMediaClick(media.id, media.isMovie)
                                        }
                                    },
                                    onLongClick = { selectedItemForFavorite = media }
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = media.posterUrl,
                                contentDescription = media.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .width(64.dp)
                                    .height(96.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(media.title, color = MaterialTheme.colorScheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (media.isMovie) stringResource(R.string.movie_singular) else stringResource(R.string.series_singular), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                    if (isItemDubbed) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Text(
                                                text = stringResource(R.string.variant_dubbed),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    } else if (isItemUnverified) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = stringResource(R.string.dubbed_unverified),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    if (selectedItemForFavorite != null) {
        val targetMedia = selectedItemForFavorite!!
        val isTargetInLib by libraryRepository.isItemInLibrary(
            targetMedia.id,
            targetMedia.contentType,
            targetMedia.variantMode
        ).collectAsState(initial = false)

        val dialogActionText = stringResource(
            if (isTargetInLib) R.string.remove_from_favorites else R.string.add_to_library_favorites
        )

        AlertDialog(
            onDismissRequest = { selectedItemForFavorite = null },
            title = {
                Text(
                    text = dialogActionText,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = targetMedia.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val libItem = LibraryItem.create(
                            contentType = targetMedia.contentType,
                            tmdbId = targetMedia.id,
                            title = targetMedia.title,
                            posterUrl = targetMedia.posterUrl,
                            variantMode = targetMedia.variantMode,
                            providerId = targetMedia.providerId ?: ""
                        )
                        scope.launch {
                            if (isTargetInLib) {
                                libraryRepository.removeFromLibrary(libItem)
                                Toast.makeText(context, context.getString(R.string.removed_from_favorites), Toast.LENGTH_SHORT).show()
                            } else {
                                libraryRepository.addToLibrary(libItem)
                                Toast.makeText(context, context.getString(R.string.added_to_library), Toast.LENGTH_SHORT).show()
                            }
                        }
                        selectedItemForFavorite = null
                    }
                ) {
                    Text(
                        text = dialogActionText,
                        color = if (isTargetInLib) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedItemForFavorite = null }) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}

@Composable
fun SectionTitleSharedWithAction(
    title: String,
    actionText: String,
    onActionClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = actionText,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onActionClick() }
        )
    }
}