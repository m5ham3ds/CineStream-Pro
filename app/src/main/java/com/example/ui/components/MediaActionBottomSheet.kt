package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.ServerItem
import com.example.extension.orchestrator.ManagedDiscoveryOutcome
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.example.ui.screens.player.normalizeQualityLabel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaActionBottomSheet(
    isMovie: Boolean,
    onDismissRequest: () -> Unit,
    onDownloadStart: (String) -> Unit,
    onAddToLibrary: () -> Unit,
    mediaId: String = "",
    title: String = "",
    year: String = "",
    posterUrl: String? = null,
    contentType: String = if (isMovie) "movie" else "series",
    episodeCount: Int = 10,
    orchestrator: ManagedMediaOrchestrator? = null
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val mediaOrchestrator = orchestrator ?: remember(context) { ManagedMediaOrchestrator.getInstance(context) }

    var step by remember { mutableStateOf(if (isMovie) 2 else 0) } // 0 = episode select, 1 = episode quality/server, 2 = movie quality/server
    var selectedSeason by remember { mutableIntStateOf(1) }
    var selectedEpisode by remember { mutableIntStateOf(1) }

    var isLoading by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf<List<ServerItem>>(emptyList()) }
    var directStreamSource by remember { mutableStateOf<PlaybackSource?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var retryTrigger by remember { mutableIntStateOf(0) }

    // Live media discovery via ManagedMediaOrchestrator production pipeline
    LaunchedEffect(step, selectedSeason, selectedEpisode, retryTrigger, mediaId, title) {
        if (step == 1 || step == 2) {
            val queryTitle = title.ifBlank { mediaId }.trim()
            if (queryTitle.isNotBlank()) {
                isLoading = true
                errorMessage = null
                discoveredServers = emptyList()
                directStreamSource = null

                val resolvedContentType = when (contentType.lowercase()) {
                    "anime" -> ContentType.ANIME
                    "movie" -> ContentType.MOVIE
                    else -> if (isMovie) ContentType.MOVIE else ContentType.SERIES
                }

                val outcome = mediaOrchestrator.discoverServers(
                    title = queryTitle,
                    year = year,
                    isMovie = isMovie,
                    season = selectedSeason,
                    episode = selectedEpisode,
                    mediaId = mediaId,
                    contentType = resolvedContentType
                )

                when (outcome) {
                    is ManagedDiscoveryOutcome.Success -> {
                        discoveredServers = outcome.servers
                        directStreamSource = outcome.directStream
                        isLoading = false
                    }
                    is ManagedDiscoveryOutcome.RecoverableFailure -> {
                        discoveredServers = emptyList()
                        directStreamSource = null
                        errorMessage = outcome.error.message ?: context.getString(R.string.no_servers_found)
                        isLoading = false
                    }
                    is ManagedDiscoveryOutcome.SecurityFailure -> {
                        discoveredServers = emptyList()
                        directStreamSource = null
                        errorMessage = outcome.error.message ?: context.getString(R.string.no_servers_found)
                        isLoading = false
                    }
                }
            } else {
                isLoading = false
                discoveredServers = emptyList()
                directStreamSource = null
            }
        }
    }

    val allServers = remember(discoveredServers, directStreamSource) {
        if (discoveredServers.isNotEmpty()) {
            discoveredServers
        } else {
            val direct = directStreamSource
            if (direct != null) {
                listOf(
                    ServerItem(
                        id = "direct_stream",
                        name = "Direct Stream",
                        link = direct.streamUrl,
                        isDirectStream = true
                    )
                )
            } else {
                emptyList()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 16.dp)
        ) {
            when (step) {
                0 -> {
                    Text(
                        stringResource(R.string.select_episode),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val maxEp = if (episodeCount > 0) episodeCount else 12
                    (1..maxEp).forEach { ep ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    selectedEpisode = ep
                                    step = 1
                                }
                                .padding(vertical = 14.dp, horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(text = stringResource(R.string.episode_number, ep), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
                        }
                    }
                }
                1, 2 -> {
                    if (step == 1) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { step = 0 }
                                .padding(vertical = 6.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.episode_number, selectedEpisode),
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Text(
                        stringResource(R.string.select_source),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(36.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    strokeWidth = 3.dp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = stringResource(R.string.searching_quality_servers),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else if (allServers.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = errorMessage ?: stringResource(R.string.no_servers_found),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedButton(
                                onClick = { retryTrigger++ },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                        }
                    } else {
                        allServers.forEach { server ->
                            val qualityLabel = normalizeQualityLabel(server.name) ?: if (server.isDirectStream) "Direct Stream" else "Server"
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        scope.launch { sheetState.hide() }.invokeOnCompletion {
                                            if (!sheetState.isVisible) {
                                                onDownloadStart("${qualityLabel} - ${server.name}")
                                            }
                                        }
                                    }
                                    .padding(vertical = 14.dp, horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(16.dp))
                                Column {
                                    Text(
                                        text = server.name,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = qualityLabel,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = stringResource(R.string.cd_download),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            if (step == 0 || step == 2) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            scope.launch { sheetState.hide() }.invokeOnCompletion {
                                if (!sheetState.isVisible) {
                                    onAddToLibrary()
                                }
                            }
                        }
                        .padding(vertical = 14.dp, horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(text = stringResource(R.string.add_to_library_favorites), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
