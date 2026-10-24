package com.example.ui.screens.home
import com.example.ui.components.rememberCardMediaDetail
import com.example.ui.components.CardMediaDetail
import kotlin.math.absoluteValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.filled.Pause

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.foundation.background
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.HistoryItem
import com.example.data.repository.HistoryRepository
import com.example.ui.components.CustomTopBar

@Composable
fun WatchingScreen(
    onItemClick: (String, Boolean) -> Unit,
    onPlayItem: ((String, Boolean) -> Unit)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val historyRepository = remember { HistoryRepository(context) }
    val historyItems by historyRepository.getHistoryItems().collectAsState(initial = emptyList())
    LaunchedEffect(historyItems) {
        if (historyItems.isNotEmpty()) {
            com.example.data.repository.ContinueWatchingMetadataManager.preload(historyItems, this)
        }
    }
    val allStr = stringResource(R.string.all)
    val moviesStr = stringResource(R.string.movies)
    val seriesStr = stringResource(R.string.series)
    val animeStr = stringResource(R.string.anime)
    

    fun isAnimeTitle(title: String): Boolean {
        val t = title.lowercase()
        return t.contains("anime") || t.contains("أنمي") || t.contains("انمي") ||
               t.contains("attack on titan") || t.contains("one piece") || t.contains("naruto") ||
               t.contains("jujutsu") || t.contains("demon slayer") || t.contains("bleach")
    }

    val tabsList = listOf(allStr, moviesStr, seriesStr, animeStr)
    val pagerState = rememberPagerState(pageCount = { tabsList.size })
    val coroutineScope = rememberCoroutineScope()
    val selectedTab = tabsList[pagerState.currentPage]
    val getItemsForTab = { tab: String -> when (tab) {
        moviesStr -> historyItems.filter { it.isMovie && !isAnimeTitle(it.title) }
        seriesStr -> historyItems.filter { !it.isMovie && !isAnimeTitle(it.title) }
        animeStr -> historyItems.filter { isAnimeTitle(it.title) }
        else -> historyItems
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CustomTopBar(
            titleFirst = stringResource(R.string.watching_title_first),
            titleSecond = stringResource(R.string.watching_title_second),
            subtitle = stringResource(R.string.watching_subtitle),
            onBack = onBack,
            showFilter = false
        )

        // Pill Tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabsList.forEach { tab ->
                val isSelected = selectedTab == tab
                Box(
                    modifier = Modifier
                        .clickable { coroutineScope.launch { pagerState.animateScrollToPage(tabsList.indexOf(tab)) } }
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        fontSize = 14.sp
                    )
                }
            }
        }
        if (historyItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_watching_items), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
            }
        } else {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val currentTab = tabsList[page]
                val items = getItemsForTab(currentTab)
                if (items.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_watching_items), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 100.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(items.distinctBy { it.id }, key = { "watching_${it.id}" }) { item ->
                            DetailedContinueWatchingCard(
                                item = item,
                                onClick = { onItemClick(item.id, item.isMovie) },
                                onPlayClick = {
                                    if (onPlayItem != null) onPlayItem(item.id, item.isMovie) else onItemClick(item.id, item.isMovie)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailedContinueWatchingCard(
    item: com.example.data.model.HistoryItem,
    onPlayClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    com.example.ui.components.ContinueWatchingCardShared(
        item = item,
        modifier = Modifier.fillMaxWidth().height(140.dp),
        onPlayClick = onPlayClick,
        onClick = onClick
    )
}

