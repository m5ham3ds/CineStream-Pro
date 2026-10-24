package com.example.ui.screens.downloads

import android.os.Environment
import androidx.compose.ui.res.stringResource
import com.example.R
import android.os.StatFs
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.DownloadItem
import com.example.data.repository.DownloadRepository
import kotlinx.coroutines.launch

@Composable
fun DownloadsScreen(
    onNavigateToHome: () -> Unit,
    onItemClick: (String, Boolean, String) -> Unit = { _, _, _ -> }
) {
    val context = LocalContext.current
    val downloadRepository = remember { DownloadRepository(context) }
    val downloadsNullable by downloadRepository.getDownloadItems().collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    if (downloadsNullable == null) {
        com.example.ui.components.DownloadsScreenSkeleton()
        return
    }
    val downloads = downloadsNullable ?: emptyList()

    var selectedTab by remember { mutableStateOf("All") }
    var itemToDelete by remember { mutableStateOf<DownloadItem?>(null) }
    var movieToExport by remember { mutableStateOf<DownloadItem?>(null) }
    var seriesToExport by remember { mutableStateOf<Pair<DownloadItem, List<DownloadItem>>?>(null) }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgressTitle by remember { mutableStateOf("") }
    var exportProgressSubtitle by remember { mutableStateOf("") }
    var exportProgressFraction by remember { mutableFloatStateOf(0f) }

    fun handleTriggerExport(item: DownloadItem) {
        val file = com.example.utils.MediaStorageUtils.findMediaFile(context, item.id)
        if (!item.isCompleted || file == null || !file.exists() || file.length() == 0L) {
            android.widget.Toast.makeText(context, context.getString(R.string.export_not_completed), android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        if (item.isMovie) {
            movieToExport = item
        } else {
            val seriesInfo = com.example.utils.DeviceStorageExporter.extractSeriesInfo(item)
            val allMatchingEpisodes = downloads.filter { dl ->
                !dl.isMovie && (dl.mediaId == item.mediaId || com.example.utils.DeviceStorageExporter.extractSeriesInfo(dl).seriesName.equals(seriesInfo.seriesName, ignoreCase = true))
            }
            val existingEpisodes = allMatchingEpisodes.filter { ep ->
                ep.isCompleted && com.example.utils.MediaStorageUtils.findMediaFile(context, ep.id)?.let { it.exists() && it.length() > 0L } == true
            }.distinctBy { it.id }.sortedBy { com.example.utils.DeviceStorageExporter.extractEpisodeNumber(it) }

            if (existingEpisodes.isEmpty()) {
                android.widget.Toast.makeText(context, context.getString(R.string.no_existing_episodes_for_export), android.widget.Toast.LENGTH_SHORT).show()
            } else {
                seriesToExport = Pair(item, existingEpisodes)
            }
        }
    }


    
    val filteredDownloads = when (selectedTab) {
        "Movies" -> downloads.filter { it.isMovie }
        "Series" -> downloads.filter { !it.isMovie }
        stringResource(R.string.anime) -> downloads.filter { !it.isMovie } // Adjust if Anime has specific logic
        else -> downloads
    }

    val totalDownloaded = downloads.filter { it.isCompleted }.size
    val totalInProgress = downloads.filter { !it.isCompleted }.size

    // Calculate Storage
    val internalStatFs = remember { StatFs(Environment.getDataDirectory().path) }
    val totalBytes = internalStatFs.totalBytes
    val availableBytes = internalStatFs.availableBytes
    val usedBytes = totalBytes - availableBytes

    val usedPercentage = if (totalBytes > 0) (usedBytes.toFloat() / totalBytes.toFloat()) else 0f
    val usedPercentageInt = (usedPercentage * 100).toInt()

    val totalStr = Formatter.formatFileSize(context, totalBytes)
    val usedStr = Formatter.formatFileSize(context, usedBytes)
    val availableStr = Formatter.formatFileSize(context, availableBytes)


    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text(stringResource(R.string.delete_download_title, item.title)) },
            text = { Text(stringResource(R.string.delete_download_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    val intent = android.content.Intent(context, com.example.utils.StreamDownloaderService::class.java).apply {
                        action = "CANCEL"
                        putExtra("id", item.id)
                    }
                    context.startService(intent)
                    scope.launch {
                        downloadRepository.removeFromDownloads(item)
                    }
                    itemToDelete = null
                }) {
                    Text(stringResource(R.string.delete_confirm), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Movie Export Confirmation Dialog
    movieToExport?.let { movieItem ->
        val cleanName = com.example.utils.DeviceStorageExporter.getDisplayMovieName(movieItem)
        val targetPath = "/storage/emulated/0/Movies/CineStream"
        val sizeOnDisk = com.example.utils.MediaStorageUtils.getActualFileSize(context, movieItem.id)
        val sizeStr = if (sizeOnDisk > 0L) com.example.utils.MediaStorageUtils.formatFileSize(sizeOnDisk) else ""

        AlertDialog(
            onDismissRequest = { movieToExport = null },
            icon = {
                Icon(
                    Icons.Outlined.PhoneAndroid,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            },
            title = {
                Text(
                    stringResource(R.string.export_movie_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.export_movie_confirm_msg, cleanName, targetPath),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        lineHeight = 20.sp
                    )
                    if (sizeStr.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.file_size), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(sizeStr, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = movieItem
                        movieToExport = null
                        scope.launch {
                            isExporting = true
                            exportProgressFraction = 0f
                            exportProgressTitle = context.getString(R.string.exporting_in_progress)
                            exportProgressSubtitle = cleanName
                            val result = com.example.utils.DeviceStorageExporter.exportMovie(context, target) { frac ->
                                exportProgressFraction = frac
                            }
                            isExporting = false
                            if (result.success) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.export_success_msg, result.filePath),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    result.errorMessage ?: context.getString(R.string.download_error_occurred),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.export_to_phone), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { movieToExport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Series / Anime Episodes Selection Dialog
    seriesToExport?.let { (clickedItem, availableEpisodes) ->
        val seriesInfo = remember(clickedItem) { com.example.utils.DeviceStorageExporter.extractSeriesInfo(clickedItem) }
        val targetPath = "/storage/emulated/0/Movies/CineStream/${seriesInfo.seriesName}"

        var selectedIds by remember(availableEpisodes) {
            mutableStateOf(availableEpisodes.map { it.id }.toSet())
        }

        AlertDialog(
            onDismissRequest = { seriesToExport = null },
            modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp),
            title = {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            seriesInfo.seriesName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { seriesToExport = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.export_series_subtitle),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        targetPath,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${selectedIds.size} / ${availableEpisodes.size}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(
                            onClick = {
                                selectedIds = if (selectedIds.size == availableEpisodes.size) {
                                    emptySet()
                                } else {
                                    availableEpisodes.map { it.id }.toSet()
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(
                                if (selectedIds.size == availableEpisodes.size) stringResource(R.string.deselect_all_episodes) else stringResource(R.string.select_all_episodes),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant)

                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(availableEpisodes, key = { it.id }) { ep ->
                            val isChecked = selectedIds.contains(ep.id)
                            val epDisplayName = com.example.utils.DeviceStorageExporter.getDisplayEpisodeName(ep, seriesInfo.seriesName)
                            val sizeOnDisk = com.example.utils.MediaStorageUtils.getActualFileSize(context, ep.id)
                            val sizeStr = if (sizeOnDisk > 0L) com.example.utils.MediaStorageUtils.formatFileSize(sizeOnDisk) else ""

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isChecked) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                border = if (isChecked) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedIds = if (isChecked) selectedIds - ep.id else selectedIds + ep.id
                                    }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedIds = if (checked) selectedIds + ep.id else selectedIds - ep.id
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            epDisplayName,
                                            fontSize = 13.sp,
                                            fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Normal,
                                            color = MaterialTheme.colorScheme.onBackground,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (sizeStr.isNotBlank()) {
                                            Text(
                                                sizeStr,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val episodesToExport = availableEpisodes.filter { selectedIds.contains(it.id) }
                        val targetSeries = seriesInfo.seriesName
                        seriesToExport = null
                        if (episodesToExport.isEmpty()) return@Button

                        scope.launch {
                            isExporting = true
                            exportProgressFraction = 0f
                            exportProgressTitle = context.getString(R.string.exporting_in_progress)
                            val totalCount = episodesToExport.size
                            var successCount = 0

                            for ((idx, ep) in episodesToExport.withIndex()) {
                                val epName = com.example.utils.DeviceStorageExporter.getDisplayEpisodeName(ep, targetSeries)
                                exportProgressSubtitle = context.getString(R.string.exporting_ep_progress, idx + 1, totalCount, epName)
                                val res = com.example.utils.DeviceStorageExporter.exportEpisode(context, targetSeries, ep) { fileFrac ->
                                    exportProgressFraction = (idx + fileFrac) / totalCount
                                }
                                if (res.success) {
                                    successCount++
                                }
                            }
                            isExporting = false
                            if (successCount > 0) {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.export_success_msg, "/storage/emulated/0/Movies/CineStream/$targetSeries"),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.failed_to_export_episodes),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    enabled = selectedIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.export_selected_btn, selectedIds.size),
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { seriesToExport = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    // Export In-Progress Non-Dismissible Dialog
    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        exportProgressTitle.ifBlank { stringResource(R.string.exporting_in_progress) },
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        exportProgressSubtitle,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { exportProgressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${(exportProgressFraction * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            },
            confirmButton = {},
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 100.dp)
    ) {
        item {
            // Stats Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DownloadStat(icon = Icons.Outlined.Folder, value = downloads.size.toString(), label = stringResource(R.string.downloaded), isPrimary = true)
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                DownloadStat(icon = Icons.Outlined.Timer, value = totalInProgress.toString(), label = stringResource(R.string.in_progress))
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                DownloadStat(icon = Icons.Outlined.CheckCircle, value = totalDownloaded.toString(), label = stringResource(R.string.completed))
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // Tabs
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(stringResource(R.string.all), stringResource(R.string.movies), stringResource(R.string.series), stringResource(R.string.anime)).forEach { tab ->
                    val isSelected = selectedTab == tab
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { selectedTab = tab }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(tab, color = if (isSelected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Export to phone storage hint
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.PhoneAndroid,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.long_press_export_hint),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }

        if (filteredDownloads.isEmpty()) {
            item {
                // Empty State
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(64.dp))
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(stringResource(R.string.no_downloads_yet), color = MaterialTheme.colorScheme.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.downloads_will_appear_here), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                }
                Spacer(modifier = Modifier.height(24.dp))
            }
        } else {
            items(filteredDownloads, key = { it.id }) { item ->
                DownloadItemRow(
                    item = item,
                    onClick = { onItemClick(item.mediaId, item.isMovie, item.variantMode) },
                    onLongClick = { handleTriggerExport(item) },
                    onExport = { handleTriggerExport(item) },
                    onPauseResume = {
                        val intent = android.content.Intent(context, com.example.utils.StreamDownloaderService::class.java).apply {
                            action = if (item.isPaused) "RESUME" else "PAUSE"
                            putExtra("id", item.id)
                        }
                        context.startService(intent)
                        scope.launch {
                            downloadRepository.updateDownload(item.copy(isPaused = !item.isPaused))
                        }
                    },
                    onDelete = {
                        itemToDelete = item
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        item {
            // Storage Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.storage), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(stringResource(R.string.used_space, usedStr, totalStr), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                    }
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    // Progress Bar
                    Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(percent = 50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        Box(modifier = Modifier.fillMaxWidth(usedPercentage).height(6.dp).clip(RoundedCornerShape(percent = 50)).background(MaterialTheme.colorScheme.primary))
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.used_percentage, usedPercentageInt), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text(stringResource(R.string.free_space, availableStr), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Download More Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                        AsyncImage(
                            model = "https://images.unsplash.com/photo-1485846234645-a62644f84728?q=80&w=200&auto=format&fit=crop",
                            contentDescription = null,
                            modifier = Modifier.size(80.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            alpha = 0.5f
                        )
                        Box(modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                            Icon(Icons.Outlined.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.download_more), color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.download_more_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onNavigateToHome,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            shape = RoundedCornerShape(percent = 50),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text(stringResource(R.string.browse_content), color = MaterialTheme.colorScheme.onBackground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadItemRow(
    item: DownloadItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    onExport: () -> Unit = {},
    onPauseResume: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val context = LocalContext.current
        val posterModel = remember(item.mediaId, item.posterUrl) {
            com.example.utils.DownloadedPostersManager.getPosterModel(context, item.mediaId, item.posterUrl)
        }
        AsyncImage(
            model = posterModel,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(60.dp)
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(if (item.isMovie) stringResource(R.string.movie_singular) else stringResource(R.string.series_singular), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                Text("${(item.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = if (item.isCompleted) Color.Green else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (item.isCompleted) "Completed" else if (item.isPaused) "Paused" else "Downloading...",
                    color = if (item.isCompleted) Color.Green else if (item.isPaused) Color.Yellow else MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp
                )
                val context = LocalContext.current
                val sizeOnDisk = if (item.fileSizeBytes > 0L) item.fileSizeBytes else com.example.utils.MediaStorageUtils.getActualFileSize(context, item.id)
                if (sizeOnDisk > 0L) {
                    Text(
                        com.example.utils.MediaStorageUtils.formatFileSize(sizeOnDisk),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(6.dp))
        if (item.isCompleted) {
            IconButton(
                onClick = onExport,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    Icons.Outlined.PhoneAndroid,
                    contentDescription = stringResource(R.string.export_to_phone),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        } else {
            IconButton(onClick = onPauseResume) {
                Icon(if (item.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = stringResource(R.string.pause_resume), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DownloadStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String, isPrimary: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 4.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent)
                .border(1.dp, if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(value, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

