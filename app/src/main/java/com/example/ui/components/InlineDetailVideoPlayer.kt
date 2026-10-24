package com.example.ui.components

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.webkit.CookieManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.compose.material.icons.filled.CloudOff
import com.example.ui.screens.player.normalizeQualityKey
import com.example.ui.screens.player.normalizeQualityLabel
import com.example.ui.screens.player.normalizeCanonicalQualityName
import com.example.ui.screens.player.filterCanonicalQualities
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.R
import com.example.ui.screens.player.PlaybackSyncStore
import com.example.utils.MediaStorageUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

data class ActiveInlinePlayback(
    val mediaId: String,
    val title: String,
    val url: String,
    val serverName: String? = null,
    val website: String? = null,
    val posterUrl: String = "",
    val isMovie: Boolean = true,
    val episodeId: String? = null,
    val seasonNumber: Int = 1,
    val episodeNumber: Int = 1,
    val initialPosition: Long = 0L,
    val initialQuality: String? = null,
    val originalTitle: String? = null,
    val year: String? = null,
    val contentType: String? = null,
    val mode: String = "subtitled",
    val audioLanguage: String = "ar",
    val providerId: String? = null
)

fun isValidPlayableMediaUrl(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val trimmed = url.trim()
    if (trimmed.startsWith("file://") || trimmed.startsWith("content://") || trimmed.startsWith("local_offline_file://")) {
        return true
    }
    if (!trimmed.startsWith("http://", ignoreCase = true) && !trimmed.startsWith("https://", ignoreCase = true)) {
        return false
    }
    val lower = trimmed.lowercase()
    val pathWithoutQuery = lower.substringBefore("?").substringBefore("#")

    // Reject obvious HTML or script documents
    if (pathWithoutQuery.endsWith(".html") || pathWithoutQuery.endsWith(".htm") || 
        pathWithoutQuery.endsWith(".php") || pathWithoutQuery.endsWith(".asp") || 
        pathWithoutQuery.endsWith(".aspx") || pathWithoutQuery.endsWith(".js") || 
        pathWithoutQuery.endsWith(".css")) {
        return false
    }

    // Reject typical iframe embed paths unless they contain actual video stream extensions
    val isEmbedPattern = lower.contains("/e/") || lower.contains("/embed") || lower.contains("/v/") ||
            lower.contains("embed.") || lower.contains("player.") || lower.contains("/watch")
    val hasDirectMediaExtension = lower.contains(".m3u8") || lower.contains(".mp4") || 
            lower.contains(".mkv") || lower.contains(".webm") || lower.contains(".mpd") || 
            lower.contains("akamaized.net")

    if (isEmbedPattern && !hasDirectMediaExtension) {
        return false
    }

    if (hasDirectMediaExtension) {
        return true
    }

    return com.example.extension.managed.web.MediaStreamDetector.isMediaUrl(trimmed)
}

@Composable
fun InlineDetailVideoPlayer(
    playback: ActiveInlinePlayback,
    onFullscreen: (currentPosition: Long) -> Unit,
    onClose: () -> Unit,
    onChangeServer: (() -> Unit)? = null,
    onNavigateToExtensions: () -> Unit = {},
    onPlaybackUrlExtracted: ((realUrl: String) -> Unit)? = null,
    onToggleVariant: ((newMode: String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val syncKey = if (playback.isMovie) playback.mediaId else "${playback.mediaId}_${playback.episodeId ?: ""}"

    val userPrefs = remember { com.example.data.repository.UserPreferencesRepository(context) }
    val seekSeconds by userPrefs.playbackSeekDuration.collectAsState(initial = 10)
    val controlsTimeoutSeconds by userPrefs.playbackControlsTimeout.collectAsState(initial = 10)

    BackHandler(enabled = true) {
        onClose()
    }

    var showInlineDownloadDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    val initialQ = playback.initialQuality?.takeIf { it.isNotBlank() }
        ?: com.example.utils.LastPlaybackStore.getLastPlayback(context, playback.mediaId, playback.episodeId)?.quality?.takeIf { it.isNotBlank() }
        ?: "Auto"
    var currentQuality by remember { mutableStateOf(initialQ) }
    var targetSwitchSeekPos by remember { mutableLongStateOf(0L) }
    var resizeMode by remember { mutableIntStateOf(androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var showControls by remember { mutableStateOf(false) }

    val fileId = if (playback.isMovie) playback.mediaId else "${playback.mediaId}_${playback.episodeId ?: "1"}"
    val downloadRepo = remember { com.example.data.repository.DownloadRepository(context) }
    val downloadItem by downloadRepo.getDownloadItemById(fileId).collectAsState(initial = null)

    val isDownloaded = playback.url.startsWith("local_offline_file://") || downloadItem?.isCompleted == true

    val currentMediaKey = remember(playback.originalTitle, playback.title, playback.isMovie, playback.seasonNumber, playback.episodeNumber, playback.episodeId) {
        val epNum = if (playback.isMovie) 1 else playback.episodeNumber
        val sNum = if (playback.isMovie) 1 else playback.seasonNumber
        val rawTitle = playback.originalTitle?.takeIf { it.isNotBlank() } ?: playback.title
        val effTitle = rawTitle.replace(Regex("""\s*-\s*S\d+E\d+.*$""", RegexOption.IGNORE_CASE), "").trim().ifBlank { rawTitle }
        "$effTitle-${playback.isMovie}-$sNum-$epNum"
    }

    val cachedInitialData = remember(currentMediaKey, fileId, playback.mediaId, playback.episodeId) {
        val epKeys = if (!playback.isMovie && !playback.episodeId.isNullOrBlank()) {
            listOf("${playback.mediaId}_${playback.episodeId}", "${playback.mediaId}_${playback.seasonNumber}_${playback.episodeNumber}")
        } else emptyList()
        val altTitleKey = "${playback.title}-${playback.isMovie}-${if (playback.isMovie) 1 else playback.seasonNumber}-${if (playback.isMovie) 1 else playback.episodeNumber}"
        com.example.ui.screens.player.ServerStateStore.getCachedData(currentMediaKey, altTitleKey, fileId, playback.mediaId, *epKeys.toTypedArray())
    }

    var allDeduplicatedQualities by remember(currentMediaKey, playback.mediaId) {
        mutableStateOf<List<com.example.utils.M3U8Parser.QualityInfo>>(cachedInitialData?.extractedQualities ?: emptyList())
    }

    val liveExtractedQualities by com.example.ui.screens.player.ServerStateStore.extractedQualitiesFlow.collectAsState()

    LaunchedEffect(liveExtractedQualities, currentMediaKey, playback.mediaId) {
        val isStoreMatching = com.example.ui.screens.player.ServerStateStore.currentMediaKey == currentMediaKey ||
            (playback.mediaId.isNotBlank() && com.example.ui.screens.player.ServerStateStore.currentMediaId == playback.mediaId)
        if (isStoreMatching && liveExtractedQualities.isNotEmpty()) {
            allDeduplicatedQualities = liveExtractedQualities
        }
    }

    LaunchedEffect(playback.url, playback.mediaId) {
        val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(currentMediaKey, fileId, playback.mediaId)
        if (cached != null && cached.extractedQualities.isNotEmpty()) {
            allDeduplicatedQualities = cached.extractedQualities
            return@LaunchedEffect
        }
        val isStoreMatching = com.example.ui.screens.player.ServerStateStore.currentMediaKey == currentMediaKey ||
            (playback.mediaId.isNotBlank() && com.example.ui.screens.player.ServerStateStore.currentMediaId == playback.mediaId)
        val servers = cached?.servers ?: (if (isStoreMatching) com.example.ui.screens.player.ServerStateStore.extractedServers else emptyList())
        val links = cached?.serverLinks ?: (if (isStoreMatching) com.example.ui.screens.player.ServerStateStore.extractedServerLinks else emptyMap())
        val downloads = cached?.downloadLinks ?: (if (isStoreMatching) com.example.ui.screens.player.ServerStateStore.extractedDownloadLinks else emptyMap())

        if (servers.isEmpty() && links.isEmpty()) {
            return@LaunchedEffect
        }

        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val resolved = com.example.ui.screens.player.ServerStateStore.resolveAndCacheAllQualities(
                mediaKey = currentMediaKey,
                serversNames = servers,
                serversMap = links,
                downloadsMap = downloads,
                currentStreamUrl = playback.url,
                altKeys = listOf(fileId, playback.mediaId)
            )
            com.example.ui.screens.player.ServerStateStore.saveForMedia(
                mediaKey = currentMediaKey,
                servers = servers,
                links = links,
                ids = cached?.serverIds ?: emptyMap(),
                downloads = downloads,
                extractedQ = resolved,
                website = playback.website ?: cached?.website ?: "",
                playbackPageUrl = playback.website ?: cached?.playbackPageUrl,
                scraperKey = playback.serverName ?: cached?.scraperKey,
                altKeys = listOf(fileId, playback.mediaId),
                directStreamUrl = playback.url,
                mediaId = playback.mediaId
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (resolved.isNotEmpty()) {
                    allDeduplicatedQualities = resolved
                }
            }
        }
    }

    LaunchedEffect(downloadItem) {
        val clean = com.example.data.model.DownloadItem.cleanQualityName(downloadItem?.quality)
        if (clean.isNotBlank()) {
            currentQuality = clean
        }
    }

    // Hardware Brightness
    val initialBrightness = remember {
        try {
            val systemBrightnessInt = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
            (systemBrightnessInt / 255f).coerceIn(0.01f, 1f)
        } catch (e: Exception) {
            0.5f
        }
    }
    var brightness by remember { mutableFloatStateOf(initialBrightness) }

    // Hardware Volume
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    val initialVolume = remember {
        audioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0) curVol.toFloat() / maxVol.toFloat() else 0.5f
        } ?: 0.5f
    }
    var volume by remember { mutableFloatStateOf(initialVolume) }

    LaunchedEffect(brightness) {
        val activity = context as? Activity
        val window = activity?.window
        val lp = window?.attributes
        if (lp != null) {
            lp.screenBrightness = brightness.coerceIn(0.01f, 1f)
            window.attributes = lp
        }
    }

    LaunchedEffect(volume) {
        audioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0) {
                val target = (volume * maxVol).roundToInt().coerceIn(0, maxVol)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        }
    }

    var hudBrightness by remember { mutableStateOf<Float?>(null) }
    var hudVolume by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(hudBrightness, hudVolume) {
        if (hudBrightness != null || hudVolume != null) {
            delay(1200)
            hudBrightness = null
            hudVolume = null
        }
    }

    // Drag Gesture state (Seek horizontal, Volume & Brightness vertical)
    var boxWidthPx by remember { mutableFloatStateOf(0f) }
    var boxHeightPx by remember { mutableFloatStateOf(0f) }
    var activeDragSide by remember { mutableStateOf<String?>(null) }
    var dragGestureDirection by remember { mutableStateOf<String?>(null) }
    var dragStartSeekPos by remember { mutableLongStateOf(0L) }
    var targetDragSeekPos by remember { mutableLongStateOf(0L) }
    var accumulatedDragX by remember { mutableFloatStateOf(0f) }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }
    var isDragSeeking by remember { mutableStateOf(false) }

    val enterPip = {
        val activity = context as? Activity
        if (activity != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val aspectRatio = android.util.Rational(16, 9)
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build()
            val entered = activity.enterPictureInPictureMode(params)
            if (entered) {
                showControls = false
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = true
            } else {
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
            }
        }
    }

    // State for direct playable URL
    var playableUrl by remember(playback.url) {
        val raw = playback.url
        val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
        val candidate = if (raw.startsWith("auto_extract://")) null else raw
        val warmResult = managedOrchestrator.evaluateWarmPlayback(
            context = context,
            mediaId = playback.mediaId,
            episodeId = playback.episodeId,
            candidateUrl = candidate,
            savedQuality = playback.initialQuality,
            mode = playback.mode,
            language = playback.audioLanguage
        )
        val initialUrl = when (warmResult) {
            is com.example.extension.orchestrator.ManagedMediaOrchestrator.WarmPlaybackResult.Valid -> warmResult.streamUrl
            is com.example.extension.orchestrator.ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid -> null
        }
        mutableStateOf(initialUrl)
    }

    LaunchedEffect(playableUrl) {
        val url = playableUrl
        if (!url.isNullOrBlank() && !url.startsWith("auto_extract://") && isValidPlayableMediaUrl(url)) {
            onPlaybackUrlExtracted?.invoke(url)
            try {
                val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                managedOrchestrator.cachePlaybackResolution(
                    mediaId = playback.mediaId,
                    contentType = if (playback.isMovie) com.example.extension.managed.model.ContentType.MOVIE else com.example.extension.managed.model.ContentType.SERIES,
                    season = playback.seasonNumber,
                    episode = playback.episodeNumber,
                    streamUrl = url,
                    mode = playback.mode,
                    language = playback.audioLanguage
                )
            } catch (_: Throwable) { }
        }
    }

    var isExtracting by remember(playback.url) {
        mutableStateOf(playableUrl == null && !playback.url.startsWith("local_offline_file://") && !playback.url.startsWith("file://") && !playback.url.startsWith("content://"))
    }
    var extractionFailed by remember { mutableStateOf(false) }

    // Player state
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(playback.initialPosition) }
    var totalDuration by remember { mutableStateOf(0L) }
    var hasActuallyStartedPlayback by remember { mutableStateOf(false) }
    var actualPlayDurationMs by remember { mutableLongStateOf(0L) }

    // Scrubbing state
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }

    // Double-tap visual indicators
    var showDoubleTapForward by remember { mutableStateOf(false) }
    var showDoubleTapBackward by remember { mutableStateOf(false) }

    // Setup ExoPlayer
    var detectedQualities by remember { mutableStateOf<List<com.example.utils.M3U8Parser.QualityInfo>>(emptyList()) }
    var hasAutoSelectedSpeedQuality by remember { mutableStateOf(false) }

    var lastWorkingPlayableUrl by remember { mutableStateOf<String?>(null) }
    var lastWorkingQuality by remember { mutableStateOf<String?>(null) }
    var lastWorkingPosition by remember { mutableLongStateOf(0L) }
    var hasPlaybackError by remember { mutableStateOf(false) }
    var hasTransientNetworkError by remember { mutableStateOf(false) }

    val exoPlayer = remember(context) {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,
                50_000,
                2_000,
                3_000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val cookie = CookieManager.getInstance().getCookie(playableUrl ?: "") ?: ""
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36")
        if (cookie.isNotEmpty()) {
            httpDataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
        }
        val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setSeekParameters(SeekParameters.CLOSEST_SYNC)
            .build().apply {
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_READY -> {
                                totalDuration = duration.coerceAtLeast(0L)
                                isBuffering = false
                                hasPlaybackError = false
                                hasTransientNetworkError = false
                                lastWorkingPlayableUrl = playableUrl
                                lastWorkingQuality = currentQuality
                                lastWorkingPosition = currentPosition
                                if (duration > 0L) {
                                    playableUrl?.let { url ->
                                        if (url.startsWith("http") || url.startsWith("local_offline_file")) {
                                            com.example.utils.LastPlaybackStore.savePlayback(
                                                context = context,
                                                mediaId = playback.mediaId,
                                                url = url,
                                                quality = currentQuality,
                                                serverName = playback.serverName ?: cachedInitialData?.servers?.firstOrNull(),
                                                website = playback.website ?: cachedInitialData?.website,
                                                episodeId = playback.episodeId,
                                                positionMillis = currentPosition,
                                                durationMillis = duration,
                                                playbackPageUrl = playback.website ?: cachedInitialData?.playbackPageUrl,
                                                scraperKey = playback.serverName ?: cachedInitialData?.scraperKey,
                                                mode = playback.mode
                                            )
                                        }
                                    }
                                }
                            }
                            Player.STATE_BUFFERING -> {
                                isBuffering = true
                            }
                            Player.STATE_ENDED -> {
                                isBuffering = false
                                isPlaying = false
                                if (!playback.isMovie && !playback.episodeId.isNullOrBlank()) {
                                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                        try {
                                            com.example.data.repository.WatchedEpisodeRepository(context).markAsWatched(playback.episodeId)
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            else -> {
                                isBuffering = false
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val isTransient = com.example.ui.screens.player.PlaybackErrorClassifier.isTransientNetworkError(error)
                        if (isTransient) {
                            android.util.Log.i("InlinePlayer", "TRANSIENT_NETWORK_ERROR: ${error.message}, preserving URL and buffering")
                            isBuffering = true
                            hasTransientNetworkError = true
                            return
                        }
                        isBuffering = false
                        val prevUrl = lastWorkingPlayableUrl
                        val prevQuality = lastWorkingQuality
                        if (prevUrl != null && prevUrl != playableUrl && prevQuality != null) {
                            android.util.Log.w("InlinePlayer", "QUALITY_SWITCH_FAILED error=${error.message}, falling back to $prevQuality")
                            val fallbackPos = if (lastWorkingPosition > 0L) lastWorkingPosition else currentTime
                            currentQuality = prevQuality
                            targetSwitchSeekPos = fallbackPos
                            playableUrl = prevUrl
                        } else {
                            android.util.Log.w("InlinePlayer", "STREAM_ERROR: ${error.message}, invalidating stale URL and falling back to orchestrator")
                            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                            managedOrchestrator.invalidateStreamUrl(context, playback.mediaId, playback.episodeId, currentMediaKey)
                            playableUrl = null
                            isExtracting = true
                            hasPlaybackError = false
                        }
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.height > 0) {
                            val canonical = normalizeCanonicalQualityName("${videoSize.height}p")
                            if (canonical != null && canonical != "Auto") {
                                if (detectedQualities.none { it.name == canonical }) {
                                    detectedQualities = (detectedQualities + com.example.utils.M3U8Parser.QualityInfo(name = canonical, url = playableUrl ?: ""))
                                        .sortedByDescending { it.name.removeSuffix("p").toIntOrNull() ?: 0 }
                                }
                            }
                        }
                    }

                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val qList = mutableListOf<com.example.utils.M3U8Parser.QualityInfo>()
                        for (group in tracks.groups) {
                            if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                                for (i in 0 until group.length) {
                                    val format = group.getTrackFormat(i)
                                    val h = format.height
                                    if (h > 0) {
                                        val canonical = normalizeCanonicalQualityName("${h}p")
                                        if (canonical != null && canonical != "Auto") {
                                            if (qList.none { it.name == canonical }) {
                                                qList.add(com.example.utils.M3U8Parser.QualityInfo(name = canonical, url = playableUrl ?: ""))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if (qList.isNotEmpty()) {
                            detectedQualities = qList.sortedByDescending { it.name.removeSuffix("p").toIntOrNull() ?: 0 }
                        }
                    }
                })
            }
    }

    DisposableEffect(exoPlayer) {
        com.example.ui.screens.player.PlayerStateHolder.isPlayerActive = true
        com.example.ui.screens.player.PlayerStateHolder.onEnterPipRequested = {
            if (exoPlayer.isPlaying) {
                enterPip()
            }
        }
        onDispose {
            com.example.ui.screens.player.PlayerStateHolder.isPlayerActive = false
            com.example.ui.screens.player.PlayerStateHolder.onEnterPipRequested = null
            val act = context as? Activity
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = act?.isInPictureInPictureMode == true
            } else {
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
            }

            // Phase 05Q.3-D: If an interactive challenge was running and unverified, trigger unverified exit
            val controller = com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance()
            val curPhase = controller.stateFlow.value.phase
            if (curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.WAITING_FOR_USER ||
                curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.VERIFYING ||
                curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.WEBVIEW_VISIBLE ||
                curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.INTERACTIVE_RECOVERY_REQUIRED) {
                controller.onUnverifiedExit("Inline player disposed")
            }
        }
    }

    // Prepare media when URL is resolved
    LaunchedEffect(playableUrl) {
        playableUrl?.let { url ->
            isBuffering = true
            if (url.contains(".m3u8") || url.contains("akamaized.net")) {
                coroutineScope.launch {
                    val parsed = com.example.utils.M3U8Parser.getQualities(url)
                    if (parsed.isNotEmpty()) {
                        detectedQualities = parsed
                    }
                }
            }
            val cookie = CookieManager.getInstance().getCookie(url) ?: ""
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36")
            if (cookie.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
            }
            val dataSourceFactory = DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(url))

            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            val savedPos = when {
                targetSwitchSeekPos > 0L -> {
                    val p = targetSwitchSeekPos
                    targetSwitchSeekPos = 0L
                    p
                }
                currentTime > 0L -> currentTime
                playback.initialPosition > 0L -> playback.initialPosition
                else -> {
                    val p = PlaybackSyncStore.getPosition(syncKey)
                    if (p > 0L) p else 0L
                }
            }
            if (savedPos > 0L) {
                exoPlayer.seekTo(savedPos)
                currentTime = savedPos
            }
            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(playback.mediaId, playback.episodeId, playback.url) {
        val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(currentMediaKey, fileId, playback.mediaId)
        val now = System.currentTimeMillis()
        val safeKey = cached?.scraperKey?.ifBlank { "managed" } ?: "managed"
        val jobKey = "$safeKey:${playback.mediaId}:${playback.episodeId ?: ""}"
        val lastRun = com.example.extension.orchestrator.BackgroundMediaRevalidator.getLastRevalidationTime(jobKey).takeIf { it > 0L }
            ?: (cached?.lastUpdatedTimestamp ?: 0L)
        val SIX_HOURS_MS = 6 * 3600 * 1000L
        // 6-hour interval rule: do not re-scan on every play click if revalidated recently
        if (lastRun > 0L && (now - lastRun < SIX_HOURS_MS)) {
            return@LaunchedEffect
        }
        val knownUrl = cached?.playbackPageUrl ?: playback.url
        val scraperKey = cached?.scraperKey ?: ""
        val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
        managedOrchestrator.revalidateMediaInBackground(
            mediaId = playback.mediaId,
            episodeId = playback.episodeId,
            mediaTitle = playback.title,
            isMovie = playback.isMovie,
            season = if (playback.isMovie) 1 else playback.seasonNumber,
            episode = if (playback.isMovie) 1 else playback.episodeNumber,
            knownPlaybackUrl = knownUrl,
            scraperKey = scraperKey,
            currentServerName = playback.serverName,
            currentQuality = currentQuality,
            altKeys = listOf(fileId, playback.mediaId)
        )
    }

    var retryExtractionTrigger by remember { mutableIntStateOf(0) }
    val networkObserver = remember { com.example.utils.NetworkConnectivityObserver(context) }
    val isOnline by networkObserver.observe().collectAsState(initial = com.example.utils.NetworkUtils.isInternetAvailable(context))

    LaunchedEffect(isOnline) {
        if (isOnline) {
            hasPlaybackError = false
            if (hasTransientNetworkError) {
                hasTransientNetworkError = false
                isBuffering = true
                exoPlayer.prepare()
                exoPlayer.play()
            } else if (extractionFailed || playableUrl == null) {
                extractionFailed = false
                isExtracting = true
                retryExtractionTrigger++
            } else if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playerError != null || !isPlaying) {
                exoPlayer.prepare()
                exoPlayer.play()
            }
        } else if (!isDownloaded) {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            }
        }
    }

    val serverSpecificQualities = remember(playback.serverName, detectedQualities, allDeduplicatedQualities) {
        val rawList = mutableListOf<com.example.utils.M3U8Parser.QualityInfo>()
        rawList.addAll(allDeduplicatedQualities)
        rawList.addAll(detectedQualities)
        val isStoreMatching = com.example.ui.screens.player.ServerStateStore.currentMediaKey == currentMediaKey ||
            (playback.mediaId.isNotBlank() && com.example.ui.screens.player.ServerStateStore.currentMediaId == playback.mediaId)
        if (isStoreMatching) {
            rawList.addAll(com.example.ui.screens.player.ServerStateStore.extractedQualities)
        }
        filterCanonicalQualities(rawList)
    }

    // Auto quality selection based on user's internet speed ONLY if quality is Auto
    LaunchedEffect(serverSpecificQualities, detectedQualities) {
        val valid = (serverSpecificQualities + detectedQualities)
            .filter { it.name != "Auto" && it.url.isNotBlank() }
            .distinctBy { it.name }
        if (valid.isNotEmpty() && currentQuality == "Auto" && !hasAutoSelectedSpeedQuality && !isDownloaded) {
            hasAutoSelectedSpeedQuality = true
            val speed = com.example.utils.NetworkUtils.getEstimatedBandwidthKbps(context)
            val best = com.example.utils.NetworkUtils.selectBestQuality(valid, speed)
            if (best != null && best.name != currentQuality) {
                if (best.url.isNotBlank() && best.url != playableUrl && best.url.startsWith("http")) {
                    val savedPos = if (currentTime > 0L) currentTime else exoPlayer.currentPosition
                    targetSwitchSeekPos = savedPos
                    playableUrl = best.url
                }
            }
        } else if (valid.isNotEmpty() && currentQuality != "Auto" && !isDownloaded) {
            // Restore saved quality source if not already playing it
            val matched = valid.find { it.name == currentQuality }
            if (matched != null && matched.url.isNotBlank() && matched.url != playableUrl && matched.url.startsWith("http")) {
                val savedPos = if (currentTime > 0L) currentTime else exoPlayer.currentPosition
                targetSwitchSeekPos = savedPos
                playableUrl = matched.url
            }
        }
    }

    // Auto-update position while playing - ONLY persist when duration > 0 and position >= 10000ms
    LaunchedEffect(isPlaying) {
        var tick = 0
        while (isPlaying) {
            if (!isScrubbing) {
                currentTime = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                if (dur > 0L && exoPlayer.playbackState == Player.STATE_READY) {
                    totalDuration = dur
                    actualPlayDurationMs += 500L
                    if (currentTime >= 2000L && actualPlayDurationMs >= 2000L) {
                        hasActuallyStartedPlayback = true
                        PlaybackSyncStore.setPosition(syncKey, currentTime)
                        tick++
                        if (tick == 1 || tick % 6 == 0) {
                            if (playback.contentType?.contains("anime", ignoreCase = true) == true ||
                                playback.website?.lowercase()?.contains("anime") == true ||
                                playback.website?.lowercase()?.contains("witanime") == true ||
                                com.example.data.repository.AnimePlaybackStore.isAnime(context, playback.mediaId, playback.title)) {
                                com.example.data.repository.AnimePlaybackStore.markAsAnime(context, playback.mediaId)
                                if (!playback.episodeId.isNullOrBlank()) {
                                    com.example.data.repository.AnimePlaybackStore.markAsAnime(context, "${playback.mediaId}_${playback.episodeId}")
                                }
                            }
                            PlaybackSyncStore.setPositionAndPersist(
                                context = context,
                                key = syncKey,
                                position = currentTime,
                                duration = totalDuration,
                                title = playback.title,
                                posterUrl = playback.posterUrl,
                                isMovie = playback.isMovie
                            )
                        }
                    }
                }
            }
            delay(500)
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls, isPlaying, isScrubbing, controlsTimeoutSeconds) {
        if (showControls && !isScrubbing) {
            delay(controlsTimeoutSeconds * 1000L)
            showControls = false
        }
    }

    // Double tap fadeout helpers
    LaunchedEffect(showDoubleTapForward) {
        if (showDoubleTapForward) {
            delay(650)
            showDoubleTapForward = false
        }
    }
    LaunchedEffect(showDoubleTapBackward) {
        if (showDoubleTapBackward) {
            delay(650)
            showDoubleTapBackward = false
        }
    }

    // Apply Quality changes to ExoPlayer
    LaunchedEffect(currentQuality) {
        val maxVideoWidth = when (currentQuality) {
            "1080p" -> 1920
            "720p" -> 1280
            "480p" -> 854
            "360p" -> 640
            else -> Int.MAX_VALUE
        }
        val maxVideoHeight = when (currentQuality) {
            "1080p" -> 1080
            "720p" -> 720
            "480p" -> 480
            "360p" -> 360
            else -> Int.MAX_VALUE
        }
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(maxVideoWidth, maxVideoHeight)
            .build()
    }

    // Keep playing in PiP mode
    LaunchedEffect(com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
        if (com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
            showControls = false
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    // Pause on lifecycle background (unless in PiP mode)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val activity = context as? Activity
            val inPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                activity?.isInPictureInPictureMode == true || com.example.ui.screens.player.PlayerStateHolder.isInPipMode
            } else com.example.ui.screens.player.PlayerStateHolder.isInPipMode

            if (event == Lifecycle.Event.ON_PAUSE) {
                if (!inPip) {
                    exoPlayer.pause()
                    val pos = exoPlayer.currentPosition
                    val dur = if (totalDuration > 0L) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
                    if (pos > 0L || dur > 0L) {
                        playableUrl?.let { url ->
                            if (url.startsWith("http") || url.startsWith("local_offline_file")) {
                                com.example.utils.LastPlaybackStore.savePlayback(
                                    context = context,
                                    mediaId = playback.mediaId,
                                    url = url,
                                    quality = currentQuality,
                                    serverName = playback.serverName ?: cachedInitialData?.servers?.firstOrNull(),
                                    website = playback.website ?: cachedInitialData?.website,
                                    episodeId = playback.episodeId,
                                    positionMillis = pos,
                                    durationMillis = dur,
                                    playbackPageUrl = playback.website ?: cachedInitialData?.playbackPageUrl,
                                    scraperKey = playback.serverName ?: cachedInitialData?.scraperKey,
                                    mode = playback.mode
                                )
                            }
                        }
                    }
                    if (hasActuallyStartedPlayback && pos >= 2000L && dur > 0L) {
                        PlaybackSyncStore.setPositionAndPersist(
                            context = context,
                            key = syncKey,
                            position = pos,
                            duration = dur,
                            title = playback.title,
                            posterUrl = playback.posterUrl,
                            isMovie = playback.isMovie
                        )
                    }

                    // Reset screen brightness when player is paused/backgrounded
                    activity?.window?.let { window ->
                        val lp = window.attributes
                        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                    }
                } else {
                    exoPlayer.playWhenReady = true
                    exoPlayer.play()
                }
            } else if (event == Lifecycle.Event.ON_STOP) {
                if (!inPip) {
                    exoPlayer.pause()
                    val pos = exoPlayer.currentPosition
                    val dur = if (totalDuration > 0L) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
                    if (pos > 0L || dur > 0L) {
                        playableUrl?.let { url ->
                            if (url.startsWith("http") || url.startsWith("local_offline_file")) {
                                com.example.utils.LastPlaybackStore.savePlayback(
                                    context = context,
                                    mediaId = playback.mediaId,
                                    url = url,
                                    quality = currentQuality,
                                    serverName = playback.serverName ?: cachedInitialData?.servers?.firstOrNull(),
                                    website = playback.website ?: cachedInitialData?.website,
                                    episodeId = playback.episodeId,
                                    positionMillis = pos,
                                    durationMillis = dur,
                                    playbackPageUrl = playback.website ?: cachedInitialData?.playbackPageUrl,
                                    scraperKey = playback.serverName ?: cachedInitialData?.scraperKey,
                                    mode = playback.mode
                                )
                            }
                        }
                    }
                    if (hasActuallyStartedPlayback && pos >= 2000L && dur > 0L) {
                        PlaybackSyncStore.setPositionAndPersist(
                            context = context,
                            key = syncKey,
                            position = pos,
                            duration = dur,
                            title = playback.title,
                            posterUrl = playback.posterUrl,
                            isMovie = playback.isMovie
                        )
                    }

                    activity?.window?.let { window ->
                        val lp = window.attributes
                        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                    }
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                activity?.window?.let { window ->
                    val lp = window.attributes
                    lp.screenBrightness = brightness.coerceIn(0.01f, 1f)
                    window.attributes = lp
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val pos = exoPlayer.currentPosition
            val dur = if (totalDuration > 0L) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
            if (pos > 0L || dur > 0L) {
                playableUrl?.let { url ->
                    if (url.startsWith("http") || url.startsWith("local_offline_file")) {
                        com.example.utils.LastPlaybackStore.savePlayback(
                            context = context,
                            mediaId = playback.mediaId,
                            url = url,
                            quality = currentQuality,
                            serverName = playback.serverName ?: cachedInitialData?.servers?.firstOrNull(),
                            website = playback.website ?: cachedInitialData?.website,
                            episodeId = playback.episodeId,
                            positionMillis = pos,
                            durationMillis = dur,
                            playbackPageUrl = playback.website ?: cachedInitialData?.playbackPageUrl,
                            scraperKey = playback.serverName ?: cachedInitialData?.scraperKey,
                            mode = playback.mode
                        )
                    }
                }
            }
            if (hasActuallyStartedPlayback && pos >= 2000L && dur > 0L) {
                PlaybackSyncStore.setPositionAndPersist(
                    context = context,
                    key = syncKey,
                    position = pos,
                    duration = dur,
                    title = playback.title,
                    posterUrl = playback.posterUrl,
                    isMovie = playback.isMovie
                )
            }
            exoPlayer.release()

            // Restore system screen brightness when player is closed
            val activity = context as? Activity
            activity?.window?.let { window ->
                val lp = window.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    // Managed runtime stream extraction if URL is an embed/webpage
    val managedOrchestrator = remember { com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context) }
    val challengeController = remember { com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance() }
    val challengeUiState by challengeController.stateFlow.collectAsState()
    val isChallengeActive = challengeUiState.isVisible || challengeUiState.phase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT

    LaunchedEffect(playback.url, isExtracting, retryExtractionTrigger) {
        if (playableUrl == null && (isExtracting || playback.url.isBlank() || playback.url.startsWith("auto_extract://")) && !playback.url.startsWith("local_offline_file://") && !playback.url.startsWith("file://") && !playback.url.startsWith("content://")) {
            // Phase 05Q.5-C: Establish fresh PlaybackAttempt context for this playback lifecycle
            val currentPlaybackAttempt = com.example.extension.managed.playback.PlaybackAttempt.createNew()
            isExtracting = true
            extractionFailed = false
            try {
                val mediaId = playback.mediaId
            val currentUser = try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser } catch (_: Throwable) { null }
            com.example.extension.managed.trace.Phase05GLogger.log(
                "AUTH",
                mediaId,
                "authenticated=${currentUser != null}, uid=${currentUser?.uid ?: "none"}, email=${currentUser?.email ?: "none"}"
            )

            val registry = com.example.extension.managed.registry.ManagedExtensionRegistry.INSTANCE
            val allExts = registry.getAllExtensions()
            val activeExts = registry.getActiveExtensions()
            val eligibleMovie = activeExts.filter { it.contentTypes.contains(com.example.extension.managed.model.ContentType.MOVIE) }

            com.example.extension.managed.trace.Phase05GLogger.log(
                "FIREBASE",
                mediaId,
                "orchestrator initialized, activeExtensionsCount=${activeExts.size}"
            )
            com.example.extension.managed.trace.Phase05GLogger.log(
                "REMOTE_COUNT",
                mediaId,
                "allExtensionsCount=${allExts.size}"
            )
            com.example.extension.managed.trace.Phase05GLogger.log(
                "VALIDATED_COUNT",
                mediaId,
                "activeValidatedCount=${activeExts.size}"
            )
            com.example.extension.managed.trace.Phase05GLogger.log(
                "CACHE",
                mediaId,
                "checking cached state for mediaKey=$currentMediaKey"
            )
            com.example.extension.managed.trace.Phase05GLogger.log(
                "REGISTRY",
                mediaId,
                "total=${allExts.size}, ids=${allExts.map { it.id }}"
            )
            com.example.extension.managed.trace.Phase05GLogger.log(
                "ACTIVE",
                mediaId,
                "activeCount=${activeExts.size}, ids=${activeExts.map { it.id }}"
            )
            com.example.extension.managed.trace.Phase05GLogger.log(
                "ELIGIBLE_MOVIE",
                mediaId,
                "eligibleMovieCount=${eligibleMovie.size}, ids=${eligibleMovie.map { it.id }}"
            )
            com.example.extension.managed.trace.Phase05GLogger.log(
                "SERVERSTATE",
                mediaId,
                "dispatching inspectAndCacheMedia for mediaKey=$currentMediaKey"
            )

            // 1. If playback.url is a server link, extract from that server
            if (playback.url.isNotBlank() && !playback.url.startsWith("auto_extract://")) {
                val serverItem = com.example.extension.managed.model.ServerItem(
                    id = playback.serverName ?: "default",
                    name = playback.serverName ?: "Server",
                    link = playback.url
                )
                val extractResult = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                    managedOrchestrator.extractPlaybackSource(serverItem, playback.title)
                }
                if (extractResult != null && extractResult.isSuccess) {
                    val stream = extractResult.getOrThrow().streamUrl
                    if (!stream.isNullOrBlank() && isValidPlayableMediaUrl(stream)) {
                        playableUrl = stream
                        isExtracting = false
                        extractionFailed = false
                        com.example.extension.managed.trace.Phase05GLogger.log(
                            "PLAYER",
                            mediaId,
                            "Handoff success (server link): playableUrl=$stream"
                        )
                        onPlaybackUrlExtracted?.invoke(stream)
                        return@LaunchedEffect
                    }
                }
            }

            // 2. Discover and orchestrate via unified PlaybackOrchestrator pipeline (with Interactive Cloudflare Recovery)
            val cType = playback.contentType?.let { com.example.extension.managed.model.ContentType.from(it, playback.isMovie) }
                ?: if (playback.isMovie) com.example.extension.managed.model.ContentType.MOVIE else com.example.extension.managed.model.ContentType.SERIES

            if (!managedOrchestrator.hasActiveExtensions(cType)) {
                managedOrchestrator.awaitRuntimeReady(timeoutMillis = 2500L, contentType = cType)
            }

            var resolvedStream: String? = null

            // Primary unified path: PlaybackOrchestrator with full interactive challenge recovery
            val session = managedOrchestrator.startPlaybackSession(
                mediaId = playback.mediaId,
                title = playback.title,
                originalTitle = playback.originalTitle,
                year = playback.year ?: "",
                isMovie = playback.isMovie,
                season = if (playback.isMovie) 1 else playback.seasonNumber,
                episode = if (playback.isMovie) 1 else playback.episodeNumber,
                contentType = cType,
                variantMode = playback.mode,
                audioLanguage = playback.audioLanguage,
                attempt = currentPlaybackAttempt
            )

            try {
                val outcome = kotlinx.coroutines.withTimeoutOrNull(120_000L) {
                    managedOrchestrator.orchestratePlayback(
                        session = session,
                        onFirstPlayableSource = { src ->
                            val s = src.streamUrl
                            if (!s.isNullOrBlank() && isValidPlayableMediaUrl(s)) {
                                resolvedStream = s
                                playableUrl = s
                                isExtracting = false
                                extractionFailed = false
                                com.example.extension.managed.trace.Phase05GLogger.log(
                                    "PLAYER",
                                    mediaId,
                                    "Handoff success (orchestrator): playableUrl=$s"
                                )
                                onPlaybackUrlExtracted?.invoke(s)
                            }
                        },
                        onQualitiesDiscovered = { qs ->
                            if (qs.isNotEmpty()) {
                                val qItems = qs.map { q ->
                                    com.example.utils.M3U8Parser.QualityInfo(name = q.label, url = q.url)
                                }
                                val canonical = com.example.ui.screens.player.filterCanonicalQualities(qItems)
                                if (canonical.isNotEmpty()) {
                                    com.example.ui.screens.player.ServerStateStore.extractedQualities = canonical
                                }
                            }
                        },
                        onError = { err ->
                            com.example.extension.managed.trace.Phase05GLogger.log("PLAYER", mediaId, "Orchestrator notice: $err")
                        }
                    )
                }

                if (resolvedStream == null && outcome is com.example.extension.managed.playback.PlaybackOrchestratorOutcome.Started) {
                    val s = outcome.firstSource.streamUrl
                    if (!s.isNullOrBlank() && isValidPlayableMediaUrl(s)) {
                        resolvedStream = s
                        playableUrl = s
                        isExtracting = false
                        extractionFailed = false
                        onPlaybackUrlExtracted?.invoke(s)
                    }
                }
            } catch (orchEx: Exception) {
                com.example.extension.managed.trace.Phase05GLogger.log("PLAYER", mediaId, "Orchestrator exception: ${orchEx.message}")
            }

            if (playableUrl == null && resolvedStream == null) {
                isExtracting = false
                extractionFailed = true
                isBuffering = false
                com.example.extension.managed.trace.Phase05GLogger.log(
                    "PLAYER",
                    mediaId,
                    "Handoff failed: no playable stream resolved, extractionFailed=true"
                )
            }
        } catch (e: Exception) {
            isExtracting = false
            extractionFailed = true
            isBuffering = false
            com.example.extension.managed.trace.Phase05GLogger.log(
                "PLAYER",
                playback.mediaId,
                "Handoff failed with exception: ${e.message}, extractionFailed=true"
            )
        }
        }
    }

    val displayTime = if (isScrubbing) scrubPosition else currentTime
    val hasHours = totalDuration >= 3600_000L
    val progressFraction = if (totalDuration > 0L) (displayTime.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f

    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()

    LaunchedEffect(restrictions.isWatchAllowed) {
        if (!restrictions.isWatchAllowed) {
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
        }
    }

    if (!restrictions.isWatchAllowed) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp)
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = Color.Red, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(com.example.R.string.watching_restricted),
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        return
    }

    val isVideoReadyToSeek = !isExtracting && !isBuffering && playableUrl != null && totalDuration > 0L && exoPlayer.playbackState == Player.STATE_READY
    val isLoadingCenterState = (isBuffering || isExtracting || playableUrl == null || exoPlayer.playbackState == Player.STATE_BUFFERING || exoPlayer.playbackState == Player.STATE_IDLE) && !extractionFailed && !hasPlaybackError

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black)
            .onSizeChanged {
                boxWidthPx = it.width.toFloat()
                boxHeightPx = it.height.toFloat()
            }
            .pointerInput(isChallengeActive) {
                if (!isChallengeActive) {
                    detectTapGestures(
                        onTap = {
                            showControls = !showControls
                        },
                        onDoubleTap = { offset ->
                            if (isVideoReadyToSeek) {
                                val isLeft = offset.x < size.width / 2f
                                val seekMs = seekSeconds * 1000L
                                if (isLeft) {
                                    // Double tap backward (-seekSeconds)
                                    val newPos = (exoPlayer.currentPosition - seekMs).coerceAtLeast(0L)
                                    isBuffering = true
                                    exoPlayer.seekTo(newPos)
                                    currentTime = newPos
                                    showDoubleTapBackward = true
                                    showDoubleTapForward = false
                                } else {
                                    // Double tap forward (+seekSeconds)
                                    val dur = if (exoPlayer.duration > 0) exoPlayer.duration else totalDuration
                                    val newPos = (exoPlayer.currentPosition + seekMs).coerceAtMost(dur)
                                    isBuffering = true
                                    exoPlayer.seekTo(newPos)
                                    currentTime = newPos
                                    showDoubleTapForward = true
                                    showDoubleTapBackward = false
                                }
                            }
                        }
                    )
                }
            }
            .pointerInput(isChallengeActive) {
                if (!isChallengeActive) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            accumulatedDragX = 0f
                            accumulatedDragY = 0f
                            dragGestureDirection = null
                            dragStartSeekPos = exoPlayer.currentPosition
                            targetDragSeekPos = dragStartSeekPos
                            isDragSeeking = false
                            if (boxWidthPx > 0) {
                                activeDragSide = if (offset.x < boxWidthPx / 2f) "left" else "right"
                            }
                        },
                        onDragEnd = {
                            if (dragGestureDirection == "horizontal" && isDragSeeking && isVideoReadyToSeek) {
                                isBuffering = true
                                exoPlayer.seekTo(targetDragSeekPos)
                                currentTime = targetDragSeekPos
                            }
                            dragGestureDirection = null
                            isDragSeeking = false
                            activeDragSide = null
                        },
                        onDragCancel = {
                            dragGestureDirection = null
                            isDragSeeking = false
                            activeDragSide = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            accumulatedDragX += dragAmount.x
                            accumulatedDragY += dragAmount.y

                            if (dragGestureDirection == null) {
                                val absX = kotlin.math.abs(accumulatedDragX)
                                val absY = kotlin.math.abs(accumulatedDragY)
                                if (absX > 15f || absY > 15f) {
                                    dragGestureDirection = if (absX > absY) "horizontal" else "vertical"
                                }
                            }

                            if (dragGestureDirection == "horizontal") {
                                if (isVideoReadyToSeek) {
                                    isDragSeeking = true
                                    hudBrightness = null
                                    hudVolume = null
                                    val seekDeltaMs = (accumulatedDragX * 120f).toLong()
                                    val dur = if (totalDuration > 0) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
                                    targetDragSeekPos = (dragStartSeekPos + seekDeltaMs).coerceIn(0L, dur)
                                } else {
                                    isDragSeeking = false
                                }
                            } else if (dragGestureDirection == "vertical") {
                                isDragSeeking = false
                                val h = if (boxHeightPx > 0) boxHeightPx else 600f
                                val delta = -dragAmount.y / (h * 0.75f)
                                if (activeDragSide == "left") {
                                    brightness = (brightness + delta).coerceIn(0.01f, 1f)
                                    hudBrightness = brightness
                                    hudVolume = null
                                } else if (activeDragSide == "right") {
                                    volume = (volume + delta).coerceIn(0f, 1f)
                                    hudVolume = volume
                                    hudBrightness = null
                                }
                            }
                        }
                    )
                }
            }
    ) {
        // Player Surface View
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    player = exoPlayer
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                playerView.resizeMode = resizeMode
            }
        )

        // Double-Tap Animated Feedback: Backward (-10s)
        AnimatedVisibility(
            visible = showDoubleTapBackward && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 28.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
            ) {
                Icon(
                    imageVector = Icons.Filled.FastRewind,
                    contentDescription = "-${seekSeconds}s",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Text("-${seekSeconds}s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Double-Tap Animated Feedback: Forward (+10s)
        AnimatedVisibility(
            visible = showDoubleTapForward && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(300)),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 28.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.65f))
            ) {
                Icon(
                    imageVector = Icons.Filled.FastForward,
                    contentDescription = "+${seekSeconds}s",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
                Text("+${seekSeconds}s", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // Floating HUD for Seek Gesture
        if (isDragSeeking && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
            val diffSeconds = (targetDragSeekPos - dragStartSeekPos) / 1000
            val isForward = diffSeconds >= 0
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isForward) Icons.Filled.FastForward else Icons.Filled.FastRewind,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${if (isForward) "+" else ""}${diffSeconds}s",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    val dur = if (totalDuration > 0) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
                    Text(
                        text = "${formatTime(targetDragSeekPos, hasHours)} / ${formatTime(dur, hasHours)}",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                }
            }
        }

        // Vertical Floating HUD for Brightness
        if (hudBrightness != null && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Filled.BrightnessMedium,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(95.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(hudBrightness!!.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${(hudBrightness!! * 100).roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Vertical Floating HUD for Volume
        if (hudVolume != null && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (hudVolume!! <= 0.01f) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .width(7.dp)
                            .height(95.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(hudVolume!!.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(4.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "${(hudVolume!! * 100).roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Buffering / Loading Indicator (Clean, transparent, no unwanted background)
        val isWaitingForNetwork = hasTransientNetworkError || (!isOnline && playableUrl != null)
        if ((isBuffering || isExtracting || isWaitingForNetwork) && !hasPlaybackError && !extractionFailed && !isChallengeActive) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isWaitingForNetwork) stringResource(R.string.buffering) else stringResource(R.string.loading),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Extraction Failed / Permanent Error (in-player error overlay with Retry button)
        val shouldShowInPlayerError = !isChallengeActive && !hasTransientNetworkError &&
            ((extractionFailed && playableUrl == null) || (hasPlaybackError && playableUrl == null) || (playableUrl == null && !isDownloaded && !isOnline))
        if (shouldShowInPlayerError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.90f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (!isOnline && !isDownloaded) stringResource(R.string.no_internet_check_connection) else stringResource(R.string.content_not_available_currently),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (!isOnline && !isDownloaded) stringResource(R.string.connection_lost_desc) else stringResource(R.string.content_not_available_desc),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            hasPlaybackError = false
                            if (com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                if (playableUrl != null) {
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                } else {
                                    extractionFailed = false
                                    isExtracting = true
                                    retryExtractionTrigger++
                                }
                            } else {
                                android.widget.Toast.makeText(context, context.getString(R.string.no_internet_check_connection), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("inline_player_retry_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.retry), color = Color.White)
                    }
                }
            }
        }

        // Phase 05Q.3-A: Player-contained Interactive Challenge Overlay for Inline Player
        if (isChallengeActive) {
            com.example.ui.components.InteractiveChallengeOverlay(
                state = challengeUiState,
                onCancel = { challengeController.cancelChallenge() },
                onRetry = {
                    retryExtractionTrigger++
                },
                modifier = Modifier.matchParentSize()
            )
        }

        // Controls Overlay (Tap to show/hide)
        AnimatedVisibility(
            visible = showControls && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode && !isChallengeActive,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.65f),
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            ) {
                // Top Header (Title + Quick Download + Quality + MMM Options)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopStart)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = playback.title,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val confirmedQualities = (serverSpecificQualities + allDeduplicatedQualities + detectedQualities)
                            .filter { !com.example.ui.screens.player.isAutoQuality(it.name) && it.url.isNotBlank() }
                            .mapNotNull { q ->
                                val canonical = normalizeCanonicalQualityName(q.name)
                                if (canonical != null && canonical != "Auto") q.copy(name = canonical) else null
                            }
                            .distinctBy { it.name }
                        val isQuickDownloadEnabled = isDownloaded || (playableUrl != null && !isExtracting && confirmedQualities.isNotEmpty())

                        // Quick Download / Delete Button (borderless clean icon button)
                        IconButton(
                            onClick = {
                                if (!restrictions.isDownloadAllowed) {
                                    android.widget.Toast.makeText(context, context.getString(R.string.downloading_restricted_by_admin), android.widget.Toast.LENGTH_SHORT).show()
                                } else if (isDownloaded) {
                                    showDeleteConfirm = true
                                } else {
                                    showInlineDownloadDialog = true
                                }
                            },
                            modifier = Modifier
                                .size(34.dp)
                                .alpha(1f)
                        ) {
                            Icon(
                                imageVector = if (isDownloaded) Icons.Default.Check else Icons.Default.Download,
                                contentDescription = if (isDownloaded) stringResource(R.string.downloaded) else stringResource(R.string.quick_download),
                                tint = if (isDownloaded) Color(0xFF4CAF50) else Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Change Quality Button
                        Box(
                            modifier = Modifier
                                .height(34.dp)
                                .clip(RoundedCornerShape(17.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .border(
                                    1.dp, 
                                    if (isDownloaded) Color.Gray.copy(alpha = 0.4f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.8f), 
                                    RoundedCornerShape(17.dp)
                                )
                                .then(if (!isDownloaded) Modifier.clickable { showQualityDialog = true } else Modifier)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            val displayQ = normalizeCanonicalQualityName(currentQuality) ?: "Auto"
                            Text(
                                text = if (displayQ == "Auto") stringResource(R.string.auto_quality) else displayQ,
                                color = if (isDownloaded) Color.Gray else MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Picture-in-Picture (PiP) Button
                        IconButton(
                            onClick = { enterPip() },
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.PictureInPictureAlt,
                                contentDescription = stringResource(R.string.pip_mode),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Center Play / Pause Button - only show when NOT loading/buffering/extracting
                if (!isLoadingCenterState) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .pointerInput(Unit) {
                                detectTapGestures {
                                    if (isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        exoPlayer.play()
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) stringResource(R.string.cd_pause) else stringResource(R.string.cd_play),
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }

                // Bottom Controls Toolbar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    // Time and Fullscreen button row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatTime(displayTime, hasHours)} / ${formatTime(totalDuration, hasHours)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isAnime = playback.contentType?.contains("anime", true) == true ||
                                playback.website?.lowercase()?.contains("anime") == true ||
                                com.example.data.repository.AnimePlaybackStore.isAnime(context, playback.mediaId, playback.title)
                            if (!isAnime) {
                                val isDubbed = playback.mode.equals("dubbed", ignoreCase = true)
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Translate,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isDubbed) stringResource(R.string.variant_dubbed) else stringResource(R.string.variant_subtitled),
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                            }

                            IconButton(
                                onClick = {
                                    val currentPos = exoPlayer.currentPosition
                                    if (currentPos > 0) PlaybackSyncStore.setPosition(syncKey, currentPos)
                                    exoPlayer.pause()
                                    val url = playableUrl
                                    if (!url.isNullOrBlank() && !url.startsWith("auto_extract://") && isValidPlayableMediaUrl(url)) {
                                        onPlaybackUrlExtracted?.invoke(url)
                                    }
                                    onFullscreen(currentPos)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Fullscreen,
                                    contentDescription = stringResource(R.string.cd_fullscreen),
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }

                    // Interactive scrubber right along the bottom edge
                    InlineBottomScrubber(
                        progressFraction = progressFraction,
                        onScrubStart = { frac ->
                            if (isVideoReadyToSeek) {
                                isScrubbing = true
                                scrubPosition = (frac * totalDuration).toLong().coerceIn(0L, totalDuration)
                            }
                        },
                        onScrubMove = { frac ->
                            if (isVideoReadyToSeek) {
                                scrubPosition = (frac * totalDuration).toLong().coerceIn(0L, totalDuration)
                            }
                        },
                        onScrubEnd = {
                            if (isVideoReadyToSeek) {
                                isScrubbing = false
                                isBuffering = true
                                exoPlayer.seekTo(scrubPosition)
                                currentTime = scrubPosition
                                if (scrubPosition > 0) PlaybackSyncStore.setPosition(syncKey, scrubPosition)
                            }
                        },
                        isInteractive = isVideoReadyToSeek,
                        modifier = Modifier.fillMaxWidth().height(16.dp)
                    )
                }
            }
        }

        // When controls are hidden: Sleek YouTube-like flush bottom progress line
        if (!showControls) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .align(Alignment.BottomStart)
                    .background(Color.White.copy(alpha = 0.25f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progressFraction)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        // Download Quality Selection Dialog (Uses SmartDownloadQualityDialog for consistent canonical quality verification)
        if (showInlineDownloadDialog && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
            val cleanMediaTitle = (playback.originalTitle?.takeIf { it.isNotBlank() } ?: playback.title)
                .replace(Regex("""\s*-\s*S\d+E\d+.*$""", RegexOption.IGNORE_CASE), "").trim()
            com.example.ui.screens.player.SmartDownloadQualityDialog(
                title = cleanMediaTitle.ifBlank { playback.title },
                year = playback.year.orEmpty(),
                isMovie = playback.isMovie,
                season = if (playback.isMovie) 1 else playback.seasonNumber,
                episode = if (playback.isMovie) 1 else playback.episodeNumber,
                isAnime = false,
                posterUrl = playback.posterUrl,
                mediaId = playback.mediaId,
                initialQualities = emptyList(),
                currentStreamUrl = playableUrl,
                onDismiss = { showInlineDownloadDialog = false },
                onNavigateToExtensions = onNavigateToExtensions,
                mode = playback.mode,
                audioLanguage = playback.audioLanguage,
                providerId = playback.providerId
            )
        }

        // Delete Download Confirmation Dialog
        if (showDeleteConfirm && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                icon = {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text(
                        text = stringResource(R.string.delete_download),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = stringResource(R.string.delete_download_confirm),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showDeleteConfirm = false
                            coroutineScope.launch {
                                val targetId = if (playback.isMovie) playback.mediaId else "${playback.mediaId}_${playback.episodeId ?: "1"}"
                                downloadRepo.removeFromDownloads(targetId)
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.download_deleted),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                                onClose()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text(stringResource(R.string.delete), color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }

        // Quality Selection Dialog
        if (showQualityDialog && !com.example.ui.screens.player.PlayerStateHolder.isInPipMode) {
            val qualityOptions = remember(serverSpecificQualities, detectedQualities) {
                val standardList = listOf("4K", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p")
                val availableCandidates = mutableListOf<com.example.utils.M3U8Parser.QualityInfo>()
                availableCandidates.addAll(detectedQualities)
                for (s in serverSpecificQualities) {
                    if (s.url.isNotBlank() && (s.url.contains(".m3u8") || s.url.contains(".mp4") || s.url == playableUrl)) {
                        availableCandidates.add(s)
                    }
                }
                val canonical = filterCanonicalQualities(availableCandidates)
                val explicit = canonical
                    .mapNotNull { normalizeCanonicalQualityName(it.name) }
                    .filter { it in standardList }
                    .distinct()
                    .sortedWith(Comparator { a, b -> standardList.indexOf(a).compareTo(standardList.indexOf(b)) })
                listOf("Auto") + explicit
            }

            AlertDialog(
                onDismissRequest = { showQualityDialog = false },
                title = {
                    Text(
                        text = stringResource(R.string.change_quality),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        qualityOptions.forEach { q ->
                            val canonicalQ = normalizeCanonicalQualityName(q) ?: "Auto"
                            val isSelected = canonicalQ == (normalizeCanonicalQualityName(currentQuality) ?: "Auto")
                            val isQAllowed = canonicalQ == "Auto" || restrictions.isQualityAllowed(canonicalQ)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        if (!isQAllowed) {
                                            val maxRes = restrictions.getMaxAllowedResolution() ?: 720
                                            android.widget.Toast.makeText(context, "Quality $canonicalQ exceeds account limit (${maxRes}p).", android.widget.Toast.LENGTH_LONG).show()
                                            return@clickable
                                        }
                                        val currentPos = if (currentTime > 0L) currentTime else exoPlayer.currentPosition
                                        val canonicalChoice = normalizeCanonicalQualityName(q) ?: "Auto"
                                        currentQuality = canonicalChoice
                                        showQualityDialog = false
                                        com.example.utils.LastPlaybackStore.updateQuality(context, playback.mediaId, playback.episodeId, canonicalChoice)
                                        val matchedQ = serverSpecificQualities.find { 
                                            normalizeCanonicalQualityName(it.name) == canonicalChoice &&
                                            it.url.isNotBlank() &&
                                            (it.url.contains(".m3u8") || it.url.contains(".mp4"))
                                        }
                                        if (canonicalChoice != "Auto" && matchedQ != null && matchedQ.url.isNotBlank() && matchedQ.url != playableUrl && matchedQ.url.startsWith("http")) {
                                            targetSwitchSeekPos = currentPos
                                            isBuffering = true
                                            playableUrl = matchedQ.url
                                        } else {
                                            val maxVideoHeight = when (canonicalChoice) {
                                                "4K" -> 2160
                                                "1440p" -> 1440
                                                "1080p" -> 1080
                                                "720p" -> 720
                                                "480p" -> 480
                                                "360p" -> 360
                                                "240p" -> 240
                                                "144p" -> 144
                                                "Auto" -> restrictions.getMaxAllowedResolution() ?: Int.MAX_VALUE
                                                else -> canonicalChoice.removeSuffix("p").toIntOrNull() ?: Int.MAX_VALUE
                                            }
                                            val minVideoHeight = when (canonicalChoice) {
                                                "4K" -> 1800
                                                "1440p" -> 1200
                                                "1080p" -> 850
                                                "720p" -> 550
                                                "480p" -> 400
                                                "360p" -> 300
                                                "240p" -> 180
                                                "144p" -> 0
                                                else -> 0
                                            }
                                            val builder = exoPlayer.trackSelectionParameters.buildUpon()
                                            if (canonicalChoice == "Auto") {
                                                if (restrictions.getMaxAllowedResolution() != null) {
                                                    builder.setMaxVideoSize(Int.MAX_VALUE, restrictions.getMaxAllowedResolution()!!)
                                                } else {
                                                    builder.clearVideoSizeConstraints()
                                                }
                                            } else {
                                                builder.setMaxVideoSize(Int.MAX_VALUE, maxVideoHeight)
                                                builder.setMinVideoSize(0, minVideoHeight)
                                            }
                                            exoPlayer.trackSelectionParameters = builder.build()
                                        }
                                        android.widget.Toast.makeText(
                                            context,
                                            if (canonicalChoice == "Auto") context.getString(R.string.auto_quality) else canonicalChoice,
                                            android.widget.Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = if (canonicalQ == "Auto") stringResource(R.string.auto_quality) else canonicalQ,
                                        color = if (!isQAllowed) Color.Gray else if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    if (!isQAllowed) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        ) {
                                            Text(
                                                text = "Max ${restrictions.allowedQuality ?: ""}",
                                                fontSize = 10.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showQualityDialog = false }) {
                        Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

@Composable
private fun InlineBottomScrubber(
    progressFraction: Float,
    onScrubStart: (Float) -> Unit,
    onScrubMove: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    isInteractive: Boolean,
    modifier: Modifier = Modifier
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val trackBgColor = Color.White.copy(alpha = 0.3f)

    androidx.compose.foundation.Canvas(
        modifier = modifier
            .pointerInput(isInteractive) {
                if (!isInteractive) return@pointerInput
                detectTapGestures { offset ->
                    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                    onScrubStart(fraction)
                    onScrubEnd()
                }
            }
            .pointerInput(isInteractive) {
                if (!isInteractive) return@pointerInput
                detectDragGestures(
                    onDragStart = { offset ->
                        val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                        onScrubStart(fraction)
                    },
                    onDragEnd = {
                        onScrubEnd()
                    },
                    onDragCancel = {
                        onScrubEnd()
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrubMove(fraction)
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        val trackHeight = 4.dp.toPx()
        val thumbRadius = 6.dp.toPx()

        // Background Track
        drawRoundRect(
            color = trackBgColor,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )

        // Active Track
        drawRoundRect(
            color = primaryColor,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(width * progressFraction, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )

        // Scrubber Thumb
        drawCircle(
            color = primaryColor,
            radius = thumbRadius,
            center = Offset(width * progressFraction, centerY)
        )
    }
}

private fun formatTime(millis: Long, forceHours: Boolean = false): String {
    val totalSeconds = (millis / 1000).coerceAtLeast(0)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0 || forceHours) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
