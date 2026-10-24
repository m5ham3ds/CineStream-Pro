package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.model.DownloadItem
import com.example.data.repository.DownloadRepository
import com.example.domain.models.Episode
import com.example.domain.models.Series
import com.example.extension.managed.model.ContentType
import com.example.extension.orchestrator.ManagedDiscoveryOutcome
import com.example.extension.orchestrator.ManagedMediaOrchestrator
import com.example.utils.AndroidDownloader
import com.example.utils.M3U8Parser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FinalDownloadData(
    val episodeId: String,
    val title: String,
    val posterUrl: String,
    val qualityName: String,
    val watchUrl: String,
    val headers: Map<String, String> = emptyMap()
)

@Composable
fun BatchDownloadProcessor(
    series: Series,
    seasonNumber: Int,
    episodes: List<Episode>,
    targetQuality: String,
    onComplete: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadRepository = remember { DownloadRepository(context) }
    val managedOrchestrator = remember { ManagedMediaOrchestrator.getInstance(context) }

    var currentIndex by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf("INIT") }
    var statusMessage by remember { mutableStateOf(context.getString(R.string.initializing)) }

    val finalDownloadList = remember { mutableStateListOf<FinalDownloadData>() }

    LaunchedEffect(Unit) {
        val isAnime = series.genres.any { it.contains("Animation", ignoreCase = true) || it.contains("Anime", ignoreCase = true) }
        val targetContentType = if (isAnime) ContentType.ANIME else ContentType.SERIES

        if (!managedOrchestrator.hasActiveExtensions(targetContentType)) {
            statusMessage = context.getString(R.string.no_extensions_available)
            delay(1500)
            onCancel()
            return@LaunchedEffect
        }

        val rawTitle = series.title
        val origTitle = series.originalTitle ?: series.title
        val year = series.firstAirDate?.take(4) ?: series.year.toString()

        for (i in episodes.indices) {
            currentIndex = i
            val ep = episodes[i]
            statusMessage = context.getString(R.string.preparing_episode_data, ep.episodeNumber.toString())

            val outcome = managedOrchestrator.discoverServers(
                title = rawTitle,
                year = year,
                isMovie = false,
                season = seasonNumber,
                episode = ep.episodeNumber,
                mediaId = series.id.toString(),
                altKeys = listOf("${series.id}_${seasonNumber}_${ep.episodeNumber}"),
                contentType = targetContentType,
                originalTitle = origTitle
            )

            if (outcome is ManagedDiscoveryOutcome.Success && outcome.servers.isNotEmpty()) {
                statusMessage = context.getString(R.string.searching_quality_servers)

                var matchedUrl: String? = null
                var matchedQualityName: String = "Default"
                var matchedHeaders: Map<String, String> = emptyMap()

                withContext(Dispatchers.IO) {
                    for (srv in outcome.servers) {
                        val link = srv.link
                        if (link.isNotBlank()) {
                            try {
                                val isDirect = link.contains(".m3u8") || link.contains("akamaized.net") || link.endsWith(".mp4") || link.endsWith(".mkv")
                                val realStream = if (isDirect) link else {
                                    try {
                                        com.example.extension.managed.web.StaticMediaExtractor.extract(link, referer = outcome.sourceUrl)
                                    } catch (_: Exception) { null }
                                }
                                if (!realStream.isNullOrBlank()) {
                                    val headers = mapOf("Referer" to link)
                                    if (realStream.contains(".m3u8") || realStream.contains("akamaized.net")) {
                                        val qList = M3U8Parser.getQualities(realStream, headers)
                                        val sortedQ = qList.sortedByDescending { it.name.replace("p", "").toIntOrNull() ?: 0 }
                                        if (targetQuality == "Auto" || targetQuality.isEmpty()) {
                                            if (sortedQ.isNotEmpty()) {
                                                matchedUrl = sortedQ.first().url
                                                matchedQualityName = sortedQ.first().name
                                                matchedHeaders = headers
                                                break
                                            }
                                        } else {
                                            val match = sortedQ.find { it.name.contains(targetQuality.replace("p", "")) }
                                            if (match != null) {
                                                matchedUrl = match.url
                                                matchedQualityName = match.name
                                                matchedHeaders = headers
                                                break
                                            } else if (sortedQ.isNotEmpty() && matchedUrl == null) {
                                                matchedUrl = sortedQ.first().url
                                                matchedQualityName = sortedQ.first().name
                                                matchedHeaders = headers
                                            }
                                        }
                                    } else if (realStream.endsWith(".mp4") || realStream.endsWith(".mkv")) {
                                        matchedUrl = realStream
                                        matchedQualityName = "720p"
                                        matchedHeaders = headers
                                        break
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }

                val finalUrl = matchedUrl ?: outcome.servers.firstOrNull()?.link
                if (!finalUrl.isNullOrBlank()) {
                    val fullTitle = "$rawTitle - S${seasonNumber}E${ep.episodeNumber}"
                    finalDownloadList.add(
                        FinalDownloadData(
                            episodeId = ep.id,
                            title = fullTitle,
                            posterUrl = ep.thumbnailUrl ?: "",
                            qualityName = matchedQualityName,
                            watchUrl = finalUrl,
                            headers = matchedHeaders
                        )
                    )
                }
            }
        }

        phase = "DONE"
        statusMessage = context.getString(R.string.starting_batch_downloads)
        for (data in finalDownloadList) {
            val epIdStr = "${series.id}_${data.episodeId}"
            val canonicalSource = com.example.extension.managed.adapter.UnifiedDownloadCoordinator.buildDownloadSource(
                streamUrl = data.watchUrl,
                mediaId = series.id.toString(),
                title = data.title,
                quality = data.qualityName,
                headers = data.headers,
                posterUrl = data.posterUrl,
                isMovie = false,
                episodeId = data.episodeId
            )
            com.example.extension.managed.adapter.UnifiedDownloadCoordinator.download(
                context = context,
                source = canonicalSource,
                scope = scope
            )
        }
        delay(1500)
        onComplete()
    }

    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.batch_download)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                CircularProgressIndicator()
                Text(statusMessage)
                if (phase != "DONE" && episodes.isNotEmpty()) {
                    Text("${currentIndex + 1} / ${episodes.size}")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}
