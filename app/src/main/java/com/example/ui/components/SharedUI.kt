package com.example.ui.components
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.runtime.produceState
import com.example.BuildConfig
import com.example.data.remote.RetrofitClient
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.DeleteOutline
import com.example.data.model.*
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




@Composable
fun SectionTitleShared(title: String, onSeeAllClick: (() -> Unit)? = null) {
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
                    contentDescription = stringResource(R.string.cd_see_all),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}


@Composable
fun ContinueWatchingCardShared(
    item: com.example.data.model.HistoryItem,
    modifier: Modifier = Modifier.width(320.dp).height(140.dp),
    onPlayClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val libraryRepository = remember { LibraryRepository(context) }
    val historyRepository = remember { HistoryRepository(context) }
    val isInLibrary by libraryRepository.isItemInLibrary(item.id, if (item.isMovie) ContentType.MOVIE else ContentType.TV).collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val mediaDetail = rememberCardMediaDetail(item) ?: CardMediaDetail(
        title = item.title, year = "", rating = "", overview = "", backdropUrl = item.posterUrl, isMovie = item.isMovie
    )
    val typeText = if (mediaDetail.isMovie) stringResource(R.string.movies) else stringResource(R.string.series)
    
    val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition(item.id)
    val effectivePos = if (syncPos > 0L) syncPos else item.positionMillis
    val effectiveDur = if (item.durationMillis > 0L) {
        item.durationMillis
    } else if (mediaDetail.runtimeMinutes > 0) {
        mediaDetail.runtimeMinutes * 60 * 1000L
    } else {
        0L
    }
    
    val progressFraction = if (effectiveDur > 0L && effectivePos > 0L) {
        (effectivePos.toFloat() / effectiveDur.toFloat()).coerceIn(0.01f, 1f)
    } else {
        0f
    }

    val timeText = if (effectivePos > 0L && effectiveDur > 0L) {
        "${com.example.ui.screens.player.PlaybackSyncStore.formatTime(effectivePos)} / ${com.example.ui.screens.player.PlaybackSyncStore.formatTime(effectiveDur)}"
    } else if (effectivePos > 0L) {
        com.example.ui.screens.player.PlaybackSyncStore.formatTime(effectivePos)
    } else {
        stringResource(R.string.resume)
    }
    
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha=0.5f), RoundedCornerShape(16.dp))
            .clickable { onClick() }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // Left Image Section
            Box(
                modifier = Modifier
                    .weight(0.45f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
            ) {
                AsyncImage(
                    model = mediaDetail.backdropUrl,
                    contentDescription = mediaDetail.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                
                // Top Left Badge "Movie" / "Series"
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 8.dp, top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = typeText, 
                        color = Color.White, 
                        fontSize = 11.sp, 
                        fontWeight = FontWeight.Bold
                    )
                }
                
                // Bottom Time Pill
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 8.dp, end = 8.dp, bottom = 14.dp)
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = timeText,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
                
                // Progress Bar at the very bottom
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.Gray.copy(alpha = 0.4f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progressFraction)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
            
            // Right Content Section
            Box(
                modifier = Modifier
                    .weight(0.55f)
                    .fillMaxHeight()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = mediaDetail.title,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Box {
                            IconButton(
                                onClick = { showMenu = true },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.options),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (isInLibrary) stringResource(R.string.remove_from_favorites) else stringResource(R.string.add_to_library_favorites)
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        scope.launch {
                                            val libItem = LibraryItem.create(
                                                contentType = if (item.isMovie) ContentType.MOVIE else ContentType.TV,
                                                tmdbId = item.id,
                                                title = item.title.ifBlank { mediaDetail.title },
                                                posterUrl = item.posterUrl.ifBlank { mediaDetail.backdropUrl }
                                            )
                                            if (isInLibrary) {
                                                libraryRepository.removeFromLibrary(libItem)
                                                Toast.makeText(context, context.getString(R.string.removed_from_favorites), Toast.LENGTH_SHORT).show()
                                            } else {
                                                libraryRepository.addToLibrary(libItem)
                                                Toast.makeText(context, context.getString(R.string.added_to_favorites), Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = if (isInLibrary) Icons.Default.BookmarkRemove else Icons.Default.BookmarkAdd,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.remove_from_continue_watching),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    },
                                    onClick = {
                                        showMenu = false
                                        showDeleteConfirmDialog = true
                                    },
                                    leadingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                )
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(2.dp))
                    
                    Text(
                        text = if (mediaDetail.year.isNotBlank()) "${mediaDetail.year} | $typeText" else typeText,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                    
                    Spacer(modifier = Modifier.height(6.dp))
                    
                    Text(
                        text = mediaDetail.overview,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 15.sp
                    )
                }
                
                // Big FAB-like play button at bottom right
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 12.dp)
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                        .clickable {
                            if (onPlayClick != null) onPlayClick() else onClick()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.play),
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.remove_from_continue_watching),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.remove_from_continue_watching_confirm),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmDialog = false
                        scope.launch {
                            historyRepository.deleteHistoryItem(item.id)
                        }
                        com.example.ui.screens.player.PlaybackSyncStore.clearPosition(item.id)
                        Toast.makeText(context, context.getString(R.string.removed_from_continue_watching), Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.yes_delete),
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text(
                        text = stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}



data class CardMediaDetail(
    val title: String,
    val year: String,
    val rating: String,
    val overview: String,
    val backdropUrl: String,
    val isMovie: Boolean,
    val runtimeMinutes: Int = 0
)

@Composable
fun rememberCardMediaDetail(item: com.example.data.model.HistoryItem): CardMediaDetail? {
    val metadataMap by com.example.data.repository.ContinueWatchingMetadataManager.metadataFlow.collectAsState()
    val languageKey = com.example.data.util.TmdbGenreHelper.getCurrentLanguage()
    val metadataKey = remember(item.id, item.isMovie, languageKey) {
        com.example.data.repository.ContinueWatchingMetadataManager.cacheKeyFor(item)
    }
    val detail = metadataMap[metadataKey] ?: com.example.data.repository.ContinueWatchingMetadataManager.getCachedOrFallback(item)

    LaunchedEffect(item.id, item.isMovie, languageKey) {
        if (!com.example.data.repository.ContinueWatchingMetadataManager.isCached(item)) {
            com.example.data.repository.ContinueWatchingMetadataManager.getOrFetchMetadata(item)
        }
    }

    return detail
}

