package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import com.example.data.model.ContentType
import com.example.data.model.LibraryItem
import com.example.data.repository.LibraryRepository
import com.example.domain.models.Movie
import com.example.domain.models.Series
import com.example.domain.search.MediaDisplayTitleResolver
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ui.ViewModelFactory
import com.example.ui.screens.search.SearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class QuickSearchFavoriteItem(
    val tmdbId: String,
    val title: String,
    val posterUrl: String,
    val isMovie: Boolean,
    val contentType: String,
    val variantMode: String,
    val providerId: String
)

@Composable
fun ExpandableSearchBar(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onMovieClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onMovieClickWithMode: ((String, String) -> Unit)? = null,
    onSeriesClickWithMode: ((String, String) -> Unit)? = null,
    onSearchSubmit: ((String) -> Unit)? = null,
    viewModel: SearchViewModel = viewModel(factory = ViewModelFactory())
) {
    val quickState by viewModel.quickSearchState.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libraryRepository = remember { LibraryRepository(context) }
    var selectedItemForFavorite by remember { mutableStateOf<QuickSearchFavoriteItem?>(null) }

    LaunchedEffect(isExpanded) {
        if (isExpanded) {
            viewModel.resetQuickSearchState()
            focusRequester.requestFocus()
        } else {
            keyboardController?.hide()
            focusManager.clearFocus()
            viewModel.resetQuickSearchState()
        }
    }
    
    // We want the search bar to overlay the UI when it shows results.
    // So it should be in a Box.
    
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
        if (!isExpanded) {
            Icon(
                Icons.Default.Search, 
                contentDescription = stringResource(R.string.cd_search), 
                tint = MaterialTheme.colorScheme.onBackground, 
                modifier = Modifier
                    .size(24.dp)
                    .clickable { onExpandedChange(true) }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                // Search Input Field
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Search, 
                        contentDescription = stringResource(R.string.cd_search), 
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, 
                        modifier = Modifier
                            .size(20.dp)
                            .clickable {
                                val trimmed = quickState.query.trim()
                                if (trimmed.isNotBlank()) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    onExpandedChange(false)
                                    viewModel.submitSearch(trimmed)
                                    onSearchSubmit?.invoke(trimmed)
                                }
                            }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicTextField(
                        value = quickState.query,
                        onValueChange = { viewModel.onQuickQueryChange(it) },
                        modifier = Modifier.weight(1f).focusRequester(focusRequester),
                        textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            val trimmed = quickState.query.trim()
                            if (trimmed.isNotBlank()) {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onExpandedChange(false)
                                viewModel.submitSearch(trimmed)
                                onSearchSubmit?.invoke(trimmed)
                            }
                        }),
                        decorationBox = { innerTextField ->
                            if (quickState.query.isEmpty()) {
                                Text(stringResource(R.string.search_dots), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                            }
                            innerTextField()
                        }
                    )
                    IconButton(
                        onClick = { 
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            if (quickState.query.isNotEmpty()) {
                                viewModel.onQuickQueryChange("")
                            } else {
                                viewModel.cancelActiveQuickSearch()
                                onExpandedChange(false) 
                            }
                        }, 
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Dropdown Results
                if (quickState.query.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                    
                    if (quickState.isSearching && quickState.movieResults.isEmpty() && quickState.seriesResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.primary)
                        }
                    } else if (quickState.movieResults.isEmpty() && quickState.seriesResults.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_results), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else {
                        if (quickState.isSearching) {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp) // Limit height so it doesn't take up the whole screen
                        ) {
                            val allResults = if (quickState.isDubbedIntent) {
                                val trigger = com.example.domain.search.SearchTriggerParser.parse(quickState.query)
                                com.example.domain.search.DubbedSearchRankingEngine.rankDropdownResults(
                                    movies = quickState.movieResults,
                                    series = quickState.seriesResults,
                                    cleanQuery = trigger.cleanQuery,
                                    isDubbedIntent = true
                                )
                            } else {
                                quickState.movieResults.map { it to true } + quickState.seriesResults.map { it to false }
                            }
                            
                            items(
                                allResults,
                                key = { (item, isMovie) ->
                                    val id = if (isMovie) (item as Movie).id else (item as Series).id
                                    "${if (isMovie) "m" else "s"}_$id"
                                }
                            ) { (item, isMovie) ->
                                val id = if (isMovie) (item as Movie).id else (item as Series).id
                                val tmdbPoster = if (isMovie) (item as Movie).posterUrl else (item as Series).posterUrl
                                val year = if (isMovie) (item as Movie).year.toString() else (item as Series).year.toString()
                                val displayInfo = if (isMovie) {
                                    MediaDisplayTitleResolver.resolveForMovie(item as Movie, isDubbedIntent = quickState.isDubbedIntent)
                                } else {
                                    MediaDisplayTitleResolver.resolveForSeries(item as Series, isDubbedIntent = quickState.isDubbedIntent)
                                }
                                val title = displayInfo.displayTitle
                                val poster = displayInfo.displayPosterUrl ?: tmdbPoster
                                val isItemDubbed = displayInfo.isConfirmedDubbed
                                val contentType = if (isMovie) ContentType.MOVIE else ContentType.TV
                                
                                @OptIn(ExperimentalFoundationApi::class)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .combinedClickable(
                                            onClick = {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                val expMode = if (isItemDubbed) "ARABIC_DUBBED" else "STANDARD"
                                                if (isMovie) {
                                                    val m = item as Movie
                                                    com.example.ui.screens.details.MovieDetailsViewModel.saveState(m.id, com.example.ui.screens.details.MovieDetailsUiState(movie = m, isLoading = false), experienceMode = expMode)
                                                    val encId = try { java.net.URLEncoder.encode(m.id, "UTF-8") } catch (_: Exception) { m.id }
                                                    com.example.ui.screens.details.MovieDetailsViewModel.saveState(encId, com.example.ui.screens.details.MovieDetailsUiState(movie = m, isLoading = false), experienceMode = expMode)
                                                    val vMode = if (isItemDubbed) "dubbed" else "subtitled"
                                                    com.example.utils.MediaDetailsCacheManager.saveMovieDetails(context, m, variantMode = vMode)
                                                    if (onMovieClickWithMode != null) onMovieClickWithMode(id, expMode) else onMovieClick(id)
                                                } else {
                                                    val s = item as Series
                                                    com.example.ui.screens.details.SeriesDetailsViewModel.saveState(s.id, com.example.ui.screens.details.SeriesDetailsUiState(series = s, isLoading = false), experienceMode = expMode)
                                                    val encId = try { java.net.URLEncoder.encode(s.id, "UTF-8") } catch (_: Exception) { s.id }
                                                    com.example.ui.screens.details.SeriesDetailsViewModel.saveState(encId, com.example.ui.screens.details.SeriesDetailsUiState(series = s, isLoading = false), experienceMode = expMode)
                                                    val vMode = if (isItemDubbed) "dubbed" else "subtitled"
                                                    com.example.utils.MediaDetailsCacheManager.saveSeriesDetails(context, s, variantMode = vMode)
                                                    if (onSeriesClickWithMode != null) onSeriesClickWithMode(id, expMode) else onSeriesClick(id)
                                                }
                                                onExpandedChange(false)
                                            },
                                            onLongClick = {
                                                selectedItemForFavorite = QuickSearchFavoriteItem(
                                                    tmdbId = id,
                                                    title = title,
                                                    posterUrl = poster,
                                                    isMovie = isMovie,
                                                    contentType = contentType,
                                                    variantMode = if (isItemDubbed) "dubbed" else "subtitled",
                                                    providerId = displayInfo.providerId ?: ""
                                                )
                                            }
                                        )
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    AsyncImage(
                                        model = poster,
                                        contentDescription = title,
                                        modifier = Modifier
                                            .width(54.dp)
                                            .height(80.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                "$year • ${if (isMovie) stringResource(R.string.movie_singular) else stringResource(R.string.series_singular)}",
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                fontSize = 12.sp
                                            )
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
                                            }
                                        }
                                    }
                                }
                            }

                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val trimmed = quickState.query.trim()
                                            if (trimmed.isNotBlank()) {
                                                keyboardController?.hide()
                                                focusManager.clearFocus()
                                                onExpandedChange(false)
                                                viewModel.submitSearch(trimmed)
                                                onSearchSubmit?.invoke(trimmed)
                                            }
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.view_all_results_for, quickState.query.trim()),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedItemForFavorite != null) {
        val fav = selectedItemForFavorite!!
        val isFavInLib by libraryRepository.isItemInLibrary(
            fav.tmdbId,
            fav.contentType,
            fav.variantMode
        ).collectAsState(initial = false)

        val dialogActionText = stringResource(
            if (isFavInLib) R.string.remove_from_favorites else R.string.add_to_library_favorites
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
                    text = fav.title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val libItem = LibraryItem.create(
                            contentType = fav.contentType,
                            tmdbId = fav.tmdbId,
                            title = fav.title,
                            posterUrl = fav.posterUrl,
                            variantMode = fav.variantMode,
                            providerId = fav.providerId
                        )
                        scope.launch {
                            if (isFavInLib) {
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
                        color = if (isFavInLib) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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