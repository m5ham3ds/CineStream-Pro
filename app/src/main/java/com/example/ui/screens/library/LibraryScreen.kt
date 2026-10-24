package com.example.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.example.R
import com.example.ui.components.MediaCard
import com.example.data.repository.HistoryRepository
import com.example.data.repository.LibraryRepository
import com.example.data.repository.DownloadRepository

@Composable
fun LibraryScreen(
    onItemClick: (String, Boolean) -> Unit,
    onItemClickWithMode: ((String, Boolean, String) -> Unit)? = null
) {
    var isLoading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(350)
        isLoading = false
    }
    if (isLoading) {
        com.example.ui.components.LibraryScreenSkeleton()
        return
    }

    val context = LocalContext.current
    val libraryRepository = remember { LibraryRepository(context) }
    val downloadRepository = remember { DownloadRepository(context) }
    val historyRepository = remember { HistoryRepository(context) }
    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    
    val libraryItems by libraryRepository.getLibraryItems().collectAsState(initial = emptyList())
    val downloadedItems by downloadRepository.getDownloadItems().collectAsState(initial = emptyList())
    
    val watchlistStr = stringResource(R.string.watchlist)
    val downloadsStr = stringResource(R.string.downloads)
    var selectedTab by remember { mutableStateOf(watchlistStr) }
    
    data class TabItem(val name: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
    val tabs = listOf(
        TabItem(stringResource(R.string.watchlist), Icons.Default.Favorite),
        TabItem(stringResource(R.string.downloads), Icons.Default.Download),
        TabItem(stringResource(R.string.history), Icons.Default.History)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        Spacer(modifier = Modifier.height(4.dp))

        // Tabs
        LazyRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tabs, key = { it.name }) { tab ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (selectedTab == tab.name) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .border(
                            1.dp,
                            if (selectedTab == tab.name) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(percent = 50)
                        )
                        .clickable { selectedTab = tab.name }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = tab.icon, 
                            contentDescription = tab.name, 
                            tint = if (selectedTab == tab.name) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = tab.name,
                            color = if (selectedTab == tab.name) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            fontWeight = if (selectedTab == tab.name) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Items grid
        val displayItems = if (selectedTab == watchlistStr) {
            libraryItems
        } else if (selectedTab == downloadsStr) {
            downloadedItems.map { 
                com.example.data.model.LibraryItem.fromLegacy(
                    id = it.id, 
                    title = it.title, 
                    posterUrl = it.posterUrl, 
                    isMovie = it.isMovie
                ) 
            }
        } else {
            emptyList()
        }

        if (displayItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (selectedTab == watchlistStr) Icons.Default.Favorite else Icons.Default.Download,
                        contentDescription = stringResource(R.string.empty),
                        tint = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (selectedTab == watchlistStr) stringResource(R.string.empty_watchlist) else if (selectedTab == downloadsStr) stringResource(R.string.empty_downloads) else stringResource(R.string.empty_history),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(displayItems.distinctBy { it.libraryId }, key = { "lib_${it.libraryId}" }) { item ->
                    MediaCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        rank = null,
                        // Library metadata does not include authoritative TMDB rating/date.
                        // Do not invent a score or release year for saved items.
                        rating = null,
                        year = null,
                        mediaId = item.tmdbId,
                        isMovie = item.isMovie,
                        contentType = item.contentType,
                        isBookmarked = true,
                        onClick = {
                            val expMode = if (item.variantMode.equals("dubbed", ignoreCase = true)) "ARABIC_DUBBED" else "STANDARD"
                            if (onItemClickWithMode != null) {
                                onItemClickWithMode(item.tmdbId, item.isMovie, expMode)
                            } else {
                                onItemClick(item.tmdbId, item.isMovie)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}