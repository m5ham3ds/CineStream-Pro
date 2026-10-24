package com.example.ui.screens.player
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack

import androidx.compose.ui.res.stringResource
import com.example.R

import androidx.compose.foundation.verticalScroll

import android.app.Activity
import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector

import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.media3.ui.PlayerView

@OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
@Suppress("OPT_IN_USAGE")
fun PlayerScreen(
    mediaId: String,
    episodeId: String = "",
    isMovie: Boolean,
    title: String,
    posterUrl: String = "",
    url: String? = null,
    targetServer: String? = null,
    website: String? = null,
    contentType: String? = null,
    startPosition: Long = 0L,
    mode: String = "subtitled",
    audioLanguage: String = "ar",
    providerId: String? = null,
    seasonNumber: Int = 1,
    episodeNumber: Int = 1,
    onBack: () -> Unit,
    onNavigateToExtensions: () -> Unit = {},
    viewModel: PlayerViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    
    LaunchedEffect(mediaId, episodeId, contentType, mode) {
        if (contentType?.contains("anime", ignoreCase = true) == true ||
            website?.lowercase()?.contains("anime") == true ||
            website?.lowercase()?.contains("witanime") == true ||
            com.example.data.repository.AnimePlaybackStore.isAnime(context, mediaId, title)) {
            com.example.data.repository.AnimePlaybackStore.markAsAnime(context, mediaId)
            if (episodeId.isNotBlank()) {
                com.example.data.repository.AnimePlaybackStore.markAsAnime(context, "${mediaId}_${episodeId}")
            }
        }
        viewModel.initialize(mediaId, isMovie, title, url, targetServer, website, episodeId, contentType, mode, audioLanguage, providerId)
    }

    val userPrefs = remember { com.example.data.repository.UserPreferencesRepository(context) }
    val seekSeconds by userPrefs.playbackSeekDuration.collectAsState(initial = 10)
    val controlsTimeoutSeconds by userPrefs.playbackControlsTimeout.collectAsState(initial = 10)
    val downloadRepository = remember { com.example.data.repository.DownloadRepository(context) }
    val scope = rememberCoroutineScope()
    val fileId = if (isMovie) mediaId else "${mediaId}_${episodeId}"
    val downloadItem by downloadRepository.getDownloadItemById(fileId).collectAsState(initial = null)
    var showDownloadSheet by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var isBuffering by remember { mutableStateOf(true) }
    var isPlaybackError by remember { mutableStateOf(false) }
    var hasTransientNetworkError by remember { mutableStateOf(false) }
    var currentTime by remember { mutableStateOf(0L) }
    var totalDuration by remember { mutableStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableStateOf(0L) }
    var hasSoughtStartPosition by remember { mutableStateOf(false) }
    var hasActuallyStartedPlayback by remember { mutableStateOf(false) }
    var actualPlayDurationMs by remember { mutableLongStateOf(0L) }
    // Initialize brightness with current system brightness
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
    var brightness by remember { mutableStateOf(initialBrightness) }

    // Initialize volume with current device media stream volume
    val audioManager = remember { context.getSystemService(android.content.Context.AUDIO_SERVICE) as? AudioManager }
    val initialVolume = remember {
        audioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0) curVol.toFloat() / maxVol.toFloat() else 0.5f
        } ?: 0.5f
    }
    var volume by remember { mutableStateOf(initialVolume) }

    // Physical effect of brightness
    LaunchedEffect(brightness) {
        val activity = context as? Activity
        val window = activity?.window
        val lp = window?.attributes
        if (lp != null) {
            lp.screenBrightness = brightness.coerceIn(0.01f, 1f)
            window.attributes = lp
        }
    }

    // Physical effect of volume
    LaunchedEffect(volume) {
        audioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (maxVol > 0) {
                val target = (volume * maxVol).roundToInt().coerceIn(0, maxVol)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            }
        }
    }

    // Gestures HUD & PiP states
    var hudBrightness by remember { mutableStateOf<Float?>(null) }
    var hudVolume by remember { mutableStateOf<Float?>(null) }
    var seekFeedback by remember { mutableStateOf<Pair<Boolean, Int>?>(null) }

    LaunchedEffect(hudBrightness, hudVolume) {
        if (hudBrightness != null || hudVolume != null) {
            delay(1200)
            hudBrightness = null
            hudVolume = null
        }
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(700)
            seekFeedback = null
        }
    }

    // Auto-hide controls after configured timeout of inactivity when playing
    LaunchedEffect(showControls, isPlaying, controlsTimeoutSeconds) {
        if (showControls && isPlaying) {
            delay(controlsTimeoutSeconds * 1000L)
            showControls = false
        }
    }

    var isLocked by remember { mutableStateOf(false) }
    var currentSpeed by remember { mutableStateOf(1f) }
    
    var showQualitySheet by remember { mutableStateOf(false) }
    var showEpisodesSheet by remember { mutableStateOf(false) }
    var showDeleteDownloadDialog by remember { mutableStateOf(false) }
    
    val isCloudflareChallenge = uiState.interactiveChallengeState.isVisible ||
        uiState.interactiveChallengeState.phase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT

    // BackHandler: Handle system back gesture / back button during playback
    androidx.activity.compose.BackHandler {
        val controller = com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance()
        val curPhase = controller.stateFlow.value.phase
        if (curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.WAITING_FOR_USER ||
            curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.VERIFYING ||
            curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.WEBVIEW_VISIBLE ||
            curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.INTERACTIVE_RECOVERY_REQUIRED) {
            controller.onUnverifiedExit("Back gesture pressed during challenge")
        }
        viewModel.stopPlayback()
        onBack()
    }

    // Force landscape mode and keep screen on while inside player
    DisposableEffect(Unit) {
        val activity = context as? Activity
        val window = activity?.window
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        var insetsController: WindowInsetsControllerCompat? = null
        if (window != null) {
            insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        
        onDispose {
            viewModel.stopPlayback()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            insetsController?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val lp = window?.attributes
            if (lp != null) {
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    // ContentObserver to sync physical volume buttons with the volume slider
    DisposableEffect(context) {
        val contentObserver = object : ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                audioManager?.let { am ->
                    val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (maxVol > 0) {
                        volume = (curVol.toFloat() / maxVol.toFloat()).coerceIn(0f, 1f)
                    }
                }
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.CONTENT_URI,
                true,
                contentObserver
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(contentObserver)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    val trackSelector = remember { DefaultTrackSelector(context) }

        var lastWorkingVideoUrl by remember { mutableStateOf<String?>(null) }
        var lastWorkingQuality by remember { mutableStateOf<String?>(null) }
        var lastWorkingPosition by remember { mutableLongStateOf(0L) }

        val exoPlayer = remember {
            // Build ExoPlayer with cookies from WebView
            val cookie = android.webkit.CookieManager.getInstance().getCookie(uiState.currentVideoUrl ?: "") ?: ""
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36")
            if (cookie.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
            }
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            
            val loadControl = androidx.media3.exoplayer.DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    15_000,
                    50_000,
                    2_000,
                    3_000
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .setSeekParameters(androidx.media3.exoplayer.SeekParameters.CLOSEST_SYNC)
                .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlayingChanged: Boolean) {
                        isPlaying = isPlayingChanged
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_READY) {
                            totalDuration = duration.coerceAtLeast(0L)
                            isBuffering = false
                            isPlaybackError = false
                            hasTransientNetworkError = false
                            lastWorkingVideoUrl = uiState.currentVideoUrl
                            lastWorkingQuality = uiState.currentQuality
                            lastWorkingPosition = currentPosition
                        } else if (state == Player.STATE_BUFFERING) {
                            isBuffering = true
                        } else {
                            isBuffering = false
                        }
                    }
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        val detected = mutableListOf<String>()
                        val qInfoList = mutableListOf<com.example.utils.M3U8Parser.QualityInfo>()
                        for (group in tracks.groups) {
                            if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                                for (i in 0 until group.length) {
                                    val format = group.getTrackFormat(i)
                                    val h = format.height
                                    if (h > 0) {
                                        val canonical = normalizeCanonicalQualityName("${h}p")
                                        if (canonical != null && canonical != "Auto" && !detected.contains(canonical)) {
                                            detected.add(canonical)
                                            qInfoList.add(com.example.utils.M3U8Parser.QualityInfo(name = canonical, url = uiState.currentVideoUrl ?: ""))
                                        }
                                    }
                                }
                            }
                        }
                        if (detected.isNotEmpty()) {
                            viewModel.mergeDetectedQualities(detected)
                            ServerStateStore.mergeDiscoveredQualities(
                                mediaKey = ServerStateStore.currentMediaKey,
                                mediaId = uiState.mediaId,
                                newQualities = qInfoList
                            )
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val isTransient = PlaybackErrorClassifier.isTransientNetworkError(error)
                        if (isTransient) {
                            android.util.Log.i("PlayerScreen", "TRANSIENT_NETWORK_ERROR: ${error.message}, preserving position and buffering")
                            isBuffering = true
                            hasTransientNetworkError = true
                            return
                        }
                        isBuffering = false
                        val prevUrl = lastWorkingVideoUrl
                        val prevQuality = lastWorkingQuality
                        if (prevUrl != null && prevUrl != uiState.currentVideoUrl && prevQuality != null) {
                            android.util.Log.w("PlayerScreen", "QUALITY_SWITCH_FAILED error=${error.message}, falling back to $prevQuality")
                            val fallbackPos = if (lastWorkingPosition > 0L) lastWorkingPosition else currentTime
                            viewModel.selectQuality(prevQuality, fallbackPos)
                        } else {
                            android.util.Log.w("PlayerScreen", "STREAM_PLAYBACK_ERROR error=${error.message}, falling back to canonical orchestrator")
                            viewModel.onPlaybackStreamFailed()
                        }
                    }
                })
            }
        }

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
                PlayerStateHolder.isInPipMode = true
                exoPlayer.playWhenReady = true
                exoPlayer.play()
            } else {
                PlayerStateHolder.isInPipMode = false
            }
        }
    }

    LaunchedEffect(PlayerStateHolder.isInPipMode) {
        if (PlayerStateHolder.isInPipMode) {
            showControls = false
            exoPlayer.playWhenReady = true
            exoPlayer.play()
        }
    }

    DisposableEffect(Unit) {
        PlayerStateHolder.isPlayerActive = true
        PlayerStateHolder.onEnterPipRequested = {
            if (exoPlayer.isPlaying) {
                enterPip()
            }
        }
        onDispose {
            PlayerStateHolder.isPlayerActive = false
            PlayerStateHolder.onEnterPipRequested = null
            val act = context as? Activity
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                PlayerStateHolder.isInPipMode = act?.isInPictureInPictureMode == true
            } else {
                PlayerStateHolder.isInPipMode = false
            }
        }
    }

    val networkObserver = remember { com.example.utils.NetworkConnectivityObserver(context) }
    val isOnline by networkObserver.observe().collectAsState(initial = com.example.utils.NetworkUtils.isInternetAvailable(context))

    LaunchedEffect(isOnline) {
        if (isOnline) {
            isPlaybackError = false
            if (hasTransientNetworkError) {
                hasTransientNetworkError = false
                isBuffering = true
                exoPlayer.prepare()
                exoPlayer.play()
            } else if (uiState.currentVideoUrl == null && !uiState.isLoading && !uiState.isOffline) {
                viewModel.retryPlayback()
            } else if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playerError != null) {
                exoPlayer.prepare()
                exoPlayer.play()
            }
        } else if (!uiState.isOffline) {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            }
        }
    }
    
    LaunchedEffect(uiState.currentQuality) {
        val canonicalChoice = normalizeCanonicalQualityName(uiState.currentQuality) ?: "Auto"
        val parametersBuilder = trackSelector.buildUponParameters()
        if (canonicalChoice == "Auto") {
            parametersBuilder.clearVideoSizeConstraints()
        } else {
            val maxH = when (canonicalChoice) {
                "1080p" -> 1080
                "720p" -> 720
                "480p" -> 480
                "360p" -> 360
                "240p" -> 240
                "144p" -> 144
                else -> Int.MAX_VALUE
            }
            val minH = when (canonicalChoice) {
                "1080p" -> 850
                "720p" -> 550
                "480p" -> 400
                "360p" -> 300
                "240p" -> 180
                "144p" -> 0
                else -> 0
            }
            parametersBuilder.setMaxVideoSize(Int.MAX_VALUE, maxH)
            parametersBuilder.setMinVideoSize(0, minH)
        }
        trackSelector.setParameters(parametersBuilder)
    }

    LaunchedEffect(uiState.currentVideoUrl) {
        uiState.currentVideoUrl?.let { url ->
            // We need to recreate the media source if the url changes to ensure new cookies are fetched
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36")
            if (cookie.isNotEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
            }
            val dataSourceFactory = androidx.media3.datasource.DefaultDataSource.Factory(context, httpDataSourceFactory)
            val mediaSource = DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(MediaItem.fromUri(url))
            
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()

            val pending = uiState.pendingSeekPosition ?: 0L
            val targetPos = when {
                pending > 0L -> pending
                startPosition > 0L && !hasSoughtStartPosition -> startPosition
                currentTime > 0L -> currentTime
                else -> {
                    val p = PlaybackSyncStore.getPosition(fileId)
                    if (p > 0L) p else if (fileId.contains("_")) PlaybackSyncStore.getPosition(mediaId) else 0L
                }
            }
            if (targetPos > 0L) {
                exoPlayer.seekTo(targetPos)
                currentTime = targetPos
                hasSoughtStartPosition = true
            }

            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(volume) {
        audioManager?.let { am ->
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (volume * maxVol).roundToInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
        }
        exoPlayer.volume = volume.coerceIn(0f, 1f)
    }
    
    LaunchedEffect(isPlaying) {
        var tick = 0
        while (isPlaying) {
            if (!isScrubbing) {
                currentTime = exoPlayer.currentPosition
                val dur = exoPlayer.duration
                if (dur > 0L && exoPlayer.playbackState == Player.STATE_READY) {
                    totalDuration = dur
                    actualPlayDurationMs += 1000L
                    if (currentTime >= 2000L && actualPlayDurationMs >= 2000L) {
                        hasActuallyStartedPlayback = true
                        viewModel.updatePlaybackPosition(currentTime, totalDuration)
                        PlaybackSyncStore.setPosition(fileId, currentTime)
                        tick++
                        if (tick == 1 || tick % 5 == 0) {
                            PlaybackSyncStore.setPositionAndPersist(
                                context = context,
                                key = fileId,
                                position = currentTime,
                                duration = totalDuration,
                                title = uiState.title,
                                posterUrl = posterUrl,
                                isMovie = uiState.isMovie
                            )
                        }
                    }
                }
            }
            delay(1000)
        }
    }

    LaunchedEffect(showControls, isPlaying, isScrubbing, controlsTimeoutSeconds) {
        if (showControls && !isScrubbing) {
            delay(controlsTimeoutSeconds * 1000L)
            if (!isLocked) showControls = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val currentPos = exoPlayer.currentPosition
            val dur = if (totalDuration > 0L) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
            if (hasActuallyStartedPlayback && currentPos >= 2000L && dur > 0L) {
                PlaybackSyncStore.setPositionAndPersist(
                    context = context,
                    key = fileId,
                    position = currentPos,
                    duration = dur,
                    title = uiState.title,
                    posterUrl = posterUrl,
                    isMovie = uiState.isMovie
                )
            }
            exoPlayer.release()

            // Restore system screen brightness when player is exited
            val activity = context as? Activity
            activity?.window?.let { window ->
                val lp = window.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
    }

    // Lifecycle observer: Stop playback when exiting/leaving app (except when entering/in PiP)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val activity = context as? Activity
            val inPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                activity?.isInPictureInPictureMode == true || PlayerStateHolder.isInPipMode
            } else PlayerStateHolder.isInPipMode

            if (event == Lifecycle.Event.ON_PAUSE) {
                if (!inPip) {
                    val currentPos = exoPlayer.currentPosition
                    val dur = if (totalDuration > 0L) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
                    if (hasActuallyStartedPlayback && currentPos >= 2000L && dur > 0L) {
                        PlaybackSyncStore.setPositionAndPersist(
                            context = context,
                            key = fileId,
                            position = currentPos,
                            duration = dur,
                            title = uiState.title,
                            posterUrl = posterUrl,
                            isMovie = uiState.isMovie
                        )
                    }
                    exoPlayer.pause()
                    exoPlayer.playWhenReady = false
                    isPlaying = false

                    // Reset screen brightness when app is minimized or backgrounded
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
                    val currentPos = exoPlayer.currentPosition
                    val dur = if (totalDuration > 0L) totalDuration else exoPlayer.duration.coerceAtLeast(0L)
                    if (hasActuallyStartedPlayback && currentPos >= 2000L && dur > 0L) {
                        PlaybackSyncStore.setPositionAndPersist(
                            context = context,
                            key = fileId,
                            position = currentPos,
                            duration = dur,
                            title = uiState.title,
                            posterUrl = posterUrl,
                            isMovie = uiState.isMovie
                        )
                    }
                    exoPlayer.pause()
                    exoPlayer.playWhenReady = false
                    isPlaying = false

                    // Ensure brightness is reset
                    activity?.window?.let { window ->
                        val lp = window.attributes
                        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                        window.attributes = lp
                    }
                }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                // Reapply player brightness when returning to foreground
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
        }
    }

    var boxWidthPx by remember { mutableFloatStateOf(0f) }
    var boxHeightPx by remember { mutableFloatStateOf(0f) }
    var activeDragSide by remember { mutableStateOf<String?>(null) }

    // Horizontal Seek & Gesture states
    var dragGestureDirection by remember { mutableStateOf<String?>(null) } // "horizontal" or "vertical"
    var dragStartSeekPos by remember { mutableLongStateOf(0L) }
    var targetDragSeekPos by remember { mutableLongStateOf(0L) }
    var accumulatedDragX by remember { mutableFloatStateOf(0f) }
    var accumulatedDragY by remember { mutableFloatStateOf(0f) }
    var isDragSeeking by remember { mutableStateOf(false) }

    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()

    LaunchedEffect(restrictions.isWatchAllowed) {
        if (!restrictions.isWatchAllowed) {
            exoPlayer.pause()
            exoPlayer.playWhenReady = false
        }
    }

    if (!restrictions.isWatchAllowed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(56.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.watching_restricted),
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.contact_support_for_details),
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onBack,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(R.string.back))
                }
            }
        }
        return
    }

    val isVideoReadyToSeek = !uiState.isLoading && !isBuffering && totalDuration > 0L && exoPlayer.playbackState == Player.STATE_READY && uiState.currentVideoUrl != null
    val isLoadingCenterState = uiState.isLoading || isBuffering || exoPlayer.playbackState == Player.STATE_BUFFERING || uiState.currentVideoUrl == null

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged {
                boxWidthPx = it.width.toFloat()
                boxHeightPx = it.height.toFloat()
            }
            .pointerInput(isLocked, isCloudflareChallenge) {
                if (!isCloudflareChallenge) {
                    if (isLocked) {
                        detectTapGestures(
                            onTap = { showControls = !showControls }
                        )
                    } else {
                        detectTapGestures(
                            onTap = {
                                showControls = !showControls
                            },
                            onDoubleTap = { offset ->
                                if (isVideoReadyToSeek && boxWidthPx > 0) {
                                    val seekMs = seekSeconds * 1000L
                                    if (offset.x < boxWidthPx * 0.4f) {
                                        val newPos = (exoPlayer.currentPosition - seekMs).coerceAtLeast(0L)
                                        isBuffering = true
                                        exoPlayer.seekTo(newPos)
                                        currentTime = newPos
                                        seekFeedback = Pair(false, seekSeconds)
                                    } else if (offset.x > boxWidthPx * 0.6f) {
                                        val newPos = (exoPlayer.currentPosition + seekMs).coerceAtMost(totalDuration)
                                        isBuffering = true
                                        exoPlayer.seekTo(newPos)
                                        currentTime = newPos
                                        seekFeedback = Pair(true, seekSeconds)
                                    } else {
                                        if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                                    }
                                }
                            }
                        )
                    }
                }
            }
            .pointerInput(isLocked, isCloudflareChallenge) {
                if (!isLocked && !isCloudflareChallenge) {
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
                                    targetDragSeekPos = (dragStartSeekPos + seekDeltaMs).coerceIn(0L, totalDuration.coerceAtLeast(0L))
                                } else {
                                    isDragSeeking = false
                                }
                            } else if (dragGestureDirection == "vertical") {
                                isDragSeeking = false
                                val h = if (boxHeightPx > 0) boxHeightPx else 1000f
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
        if (uiState.currentVideoUrl != null) {
            val videoUrl = uiState.currentVideoUrl!!
            val isDirectVideo = videoUrl.contains(".mp4") || videoUrl.contains(".m3u8") || videoUrl.contains(".mkv") || videoUrl.startsWith("file://")
            
            if (isDirectVideo && uiState.currentVideoUrl?.contains("embed") != true && uiState.currentVideoUrl?.contains("iframe") != true) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = false
                            keepScreenOn = true
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            keepScreenOn = true
                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                mediaPlaybackRequiresUserGesture = false
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                val originalUserAgent = WebSettings.getDefaultUserAgent(ctx)
                                userAgentString = originalUserAgent.replace("; wv", "").replace("Version/4.0 ", "")
                            }
                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl(videoUrl)
                        }
                    },
                    update = { webView ->
                        val lastUrl = webView.getTag(com.example.R.id.tag_url) as? String
                        if (lastUrl != videoUrl) {
                            webView.setTag(com.example.R.id.tag_url, videoUrl)
                            webView.loadUrl(videoUrl)
                        }
                    },
                    onRelease = { webView ->
                        webView.destroy()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Video loading and playback handled via ExoPlayer and ManagedMediaOrchestrator
        
        val isWaitingForNetwork = hasTransientNetworkError || (!isOnline && uiState.currentVideoUrl != null)
        val showBufferingOverlay = (isBuffering || isWaitingForNetwork || (uiState.isLoading && isOnline)) &&
            !isCloudflareChallenge && !uiState.isUserCancelledChallenge
        if (showBufferingOverlay) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                        strokeWidth = 4.dp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    val statusText = when {
                        isWaitingForNetwork -> stringResource(R.string.buffering)
                        uiState.isLoading -> stringResource(R.string.connecting_to) + (if (uiState.currentServer.isNotEmpty()) " / ${uiState.currentServer}" else "")
                        else -> stringResource(R.string.buffering)
                    }
                    Text(statusText, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        val shouldShowPlayerError = (uiState.currentVideoUrl == null && !uiState.isLoading && !uiState.isOffline && !isCloudflareChallenge && !uiState.isUserCancelledChallenge) ||
            (isPlaybackError && !hasTransientNetworkError && !isCloudflareChallenge && !uiState.isUserCancelledChallenge)
        if (shouldShowPlayerError) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = if (!isOnline && !uiState.isOffline) stringResource(R.string.no_internet_check_connection) else stringResource(R.string.content_not_available_currently),
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (!isOnline && !uiState.isOffline) stringResource(R.string.connection_lost_desc) else stringResource(R.string.content_not_available_desc),
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            isPlaybackError = false
                            if (com.example.utils.NetworkUtils.isInternetAvailable(context) || uiState.isOffline) {
                                if (uiState.currentVideoUrl == null) {
                                    viewModel.retryPlayback()
                                } else {
                                    exoPlayer.prepare()
                                    exoPlayer.play()
                                }
                            } else {
                                android.widget.Toast.makeText(context, context.getString(R.string.no_internet_check_connection), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("player_retry_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.retry), color = Color.White)
                    }
                }
            }
        }

        // Phase 05Q.1: User Cancelled Challenge UI (Without showing "Content Unavailable")
        if (uiState.isUserCancelledChallenge && uiState.currentVideoUrl == null && !uiState.isLoading && !isCloudflareChallenge) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "تم إلغاء عملية التحقق",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "يمكنك إعادة المحاولة في أي وقت لإكمال التحقق والتشغيل",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.retryPlayback() },
                        modifier = Modifier.testTag("cancelled_challenge_retry_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("إعادة المحاولة", color = Color.White)
                    }
                }
            }
        }

        // Phase 05Q.3-A: Player-contained Interactive Challenge Overlay
        if (isCloudflareChallenge) {
            com.example.ui.components.InteractiveChallengeOverlay(
                state = uiState.interactiveChallengeState,
                onCancel = { viewModel.cancelInteractiveChallenge() },
                onRetry = { viewModel.retryInteractiveChallenge() },
                modifier = Modifier.matchParentSize()
            )
        }

        // Seek indicator feedback
        if (seekFeedback != null && !PlayerStateHolder.isInPipMode) {
            val isForward = seekFeedback!!.first
            val seconds = seekFeedback!!.second
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.35f)
                    .align(if (isForward) Alignment.CenterEnd else Alignment.CenterStart)
                    .background(
                        Color.Black.copy(alpha = 0.45f),
                        shape = if (isForward) RoundedCornerShape(topStart = 80.dp, bottomStart = 80.dp)
                                else RoundedCornerShape(topEnd = 80.dp, bottomEnd = 80.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(52.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isForward) "+${seconds}s" else "-${seconds}s",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }

        // Floating HUD for Seek Gesture
        if (isDragSeeking && !PlayerStateHolder.isInPipMode) {
            val diffSeconds = (targetDragSeekPos - dragStartSeekPos) / 1000
            val isForward = diffSeconds >= 0
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.85f))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${if (isForward) "+" else ""}${diffSeconds}s",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "${formatTime(targetDragSeekPos)} / ${formatTime(totalDuration)}",
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Vertical Floating HUD for Brightness
        if (hudBrightness != null && !PlayerStateHolder.isInPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 32.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.BrightnessMedium,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(110.dp)
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${(hudBrightness!! * 100).roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Vertical Floating HUD for Volume
        if (hudVolume != null && !PlayerStateHolder.isInPipMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 32.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(horizontal = 14.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        if (hudVolume!! <= 0.01f) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(110.dp)
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
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "${(hudVolume!! * 100).roundToInt()}%",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = showControls && !PlayerStateHolder.isInPipMode && !isCloudflareChallenge,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f))
            ) {
                if (isLocked) {
                    // Only show unlock button if locked
                    IconButton(
                        onClick = { isLocked = false; showControls = true },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(32.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = stringResource(R.string.unlock), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(32.dp))
                    }
                } else {
                    // Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, start = 24.dp, end = 24.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left section
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack, 
                                contentDescription = stringResource(R.string.back), 
                                tint = MaterialTheme.colorScheme.onBackground, 
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable {
                                        val controller = com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance()
                                        val curPhase = controller.stateFlow.value.phase
                                        if (curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.WAITING_FOR_USER ||
                                            curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.VERIFYING ||
                                            curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.WEBVIEW_VISIBLE ||
                                            curPhase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.INTERACTIVE_RECOVERY_REQUIRED) {
                                            controller.onUnverifiedExit("Back icon pressed during challenge")
                                        }
                                        viewModel.stopPlayback()
                                        onBack()
                                    }
                            )
                        }
                        
                        // Center section
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1.8f)) {
                            Text(if(uiState.isMovie) stringResource(R.string.movie_singular) else stringResource(R.string.series_singular), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(uiState.title, color = Color.LightGray, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                        
                        // Right section
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Default.PictureInPictureAlt, 
                                contentDescription = stringResource(R.string.pip_mode), 
                                tint = MaterialTheme.colorScheme.onBackground, 
                                modifier = Modifier
                                    .size(26.dp)
                                    .clickable { enterPip() }
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Icon(
                                Icons.Default.MoreVert, 
                                contentDescription = stringResource(R.string.menu), 
                                tint = MaterialTheme.colorScheme.onBackground, 
                                modifier = Modifier.size(28.dp).clickable { /* Menu */ }
                            )
                        }
                    }

                    // Left Vertical Slider (Brightness)
                    Box(modifier = Modifier.align(Alignment.CenterStart).padding(start = 24.dp)) {
                        VerticalSlider(
                            value = brightness, 
                            onValueChange = { brightness = it },
                            icon = Icons.Default.BrightnessMedium
                        )
                    }

                    // Right Vertical Slider (Volume)
                    Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 24.dp)) {
                        VerticalSlider(
                            value = volume, 
                            onValueChange = { volume = it },
                            icon = if (volume <= 0.01f) Icons.Default.VolumeOff else Icons.Default.VolumeUp
                        )
                    }

                    // Center Playback Controls - only show when NOT loading
                    if (uiState.currentVideoUrl != null && !isLoadingCenterState) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(32.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(56.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), CircleShape)
                                    .clickable { 
                                        val newPos = (exoPlayer.currentPosition - seekSeconds * 1000L).coerceAtLeast(0L)
                                        isBuffering = true
                                        exoPlayer.seekTo(newPos)
                                        currentTime = newPos
                                        seekFeedback = Pair(false, seekSeconds)
                                    }
                            ) {
                                Icon(Icons.Default.FastRewind, contentDescription = stringResource(R.string.rewind), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
                            }
                            
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(72.dp)
                                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                                    .clickable { if (isPlaying) exoPlayer.pause() else exoPlayer.play() }
                            ) {
                                if (isBuffering) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                                } else {
                                    Icon(
                                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = stringResource(R.string.play_pause),
                                        tint = MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(36.dp)
                                    )
                                }
                            }
                            
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(56.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), CircleShape)
                                    .clickable { 
                                        val newPos = (exoPlayer.currentPosition + seekSeconds * 1000L).coerceAtMost(totalDuration)
                                        isBuffering = true
                                        exoPlayer.seekTo(newPos)
                                        currentTime = newPos
                                        seekFeedback = Pair(true, seekSeconds)
                                    }
                            ) {
                                Icon(Icons.Default.FastForward, contentDescription = stringResource(R.string.forward), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(28.dp))
                            }
                        }
                    }

                    // Bottom Controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 24.dp)
                    ) {
                        // Progress Bar Row
                        val hasHours = totalDuration >= 3600_000L
                        val displayTime = if (isScrubbing) scrubPosition else currentTime
                        val displayPercent = if (totalDuration > 0L) (displayTime.toFloat() / totalDuration.toFloat()).coerceIn(0f, 1f) else 0f

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(formatTime(displayTime, hasHours), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(16.dp))
                            SimpleSlider(
                                value = displayPercent,
                                onValueChange = { percent ->
                                    if (isVideoReadyToSeek) {
                                        isScrubbing = true
                                        scrubPosition = (percent * totalDuration).toLong().coerceIn(0L, totalDuration)
                                    }
                                },
                                onValueChangeFinished = {
                                    if (isVideoReadyToSeek) {
                                        isScrubbing = false
                                        isBuffering = true
                                        exoPlayer.seekTo(scrubPosition)
                                        currentTime = scrubPosition
                                        PlaybackSyncStore.setPosition(fileId, scrubPosition)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Text(formatTime(totalDuration, hasHours), color = MaterialTheme.colorScheme.onBackground, fontSize = 14.sp)
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Action Toolbar Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            BottomAction(icon = Icons.Default.Speed, text = stringResource(R.string.player_speed, if (currentSpeed == 1f) "1" else currentSpeed.toString())) { 
                                val nextSpeed = when(currentSpeed) {
                                    0.5f -> 1f
                                    1f -> 1.5f
                                    1.5f -> 2f
                                    else -> 0.5f
                                }
                                currentSpeed = nextSpeed
                                exoPlayer.setPlaybackSpeed(nextSpeed)
                            }
                            ActionDivider()
                            BottomAction(icon = Icons.Default.Lock, text = stringResource(R.string.lock)) { isLocked = true }
                            ActionDivider()
                            if (!uiState.isMovie) { 
                                val isEpisodesEnabled = !uiState.isOffline
                                BottomAction(
                                    icon = Icons.Default.VideoLibrary, 
                                    text = stringResource(R.string.episodes),
                                    enabled = isEpisodesEnabled,
                                    onClick = { if (isEpisodesEnabled) showEpisodesSheet = true }
                                ) 
                            }
                            val showQuality = uiState.isOffline || uiState.availableQualities.isNotEmpty()
                            if (showQuality) {
                                ActionDivider()
                                val isQualityEnabled = !uiState.isOffline && uiState.availableQualities.isNotEmpty()
                                val displayQuality = if (uiState.isOffline) {
                                    com.example.data.model.DownloadItem.cleanQualityName(downloadItem?.quality)
                                } else {
                                    com.example.data.model.DownloadItem.cleanQualityName(uiState.currentQuality)
                                }
                                QualityAction(
                                    currentQuality = displayQuality,
                                    enabled = isQualityEnabled,
                                    onClick = { if (isQualityEnabled) showQualitySheet = true }
                                )
                            }
                            ActionDivider()
                            val isDownloaded = uiState.isOffline || downloadItem?.isCompleted == true
                            BottomAction(
                                icon = if (isDownloaded) Icons.Default.Check else Icons.Default.Download, 
                                text = if (isDownloaded) stringResource(R.string.downloaded) else stringResource(R.string.downloads),
                                enabled = true,
                                iconTint = if (isDownloaded) Color(0xFF4CAF50) else null,
                                onClick = { 
                                    if (isDownloaded) {
                                        showDeleteDownloadDialog = true
                                    } else {
                                        showDownloadSheet = true 
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Bottom Sheets & Dialogs
    if (!PlayerStateHolder.isInPipMode) {
        if (showQualitySheet) {
            ModalBottomSheet(
                onDismissRequest = { showQualitySheet = false },
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.select_quality),
                            color = MaterialTheme.colorScheme.onBackground,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                    val standardList = listOf("4K", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p")
                    val explicitQualities = uiState.availableQualities
                        .mapNotNull { normalizeCanonicalQualityName(it) }
                        .filter { it in standardList }
                        .distinct()
                        .sortedWith(Comparator { a, b -> standardList.indexOf(a).compareTo(standardList.indexOf(b)) })
                    val displayQualities = listOf("Auto") + explicitQualities

                    items(displayQualities, key = { it }) { canonicalQ ->
                        val isSelected = canonicalQ == (normalizeCanonicalQualityName(uiState.currentQuality) ?: "Auto")
                        val isQAllowed = canonicalQ == "Auto" || restrictions.isQualityAllowed(canonicalQ)
                        val badgeText = when {
                            !isQAllowed -> "LOCKED"
                            canonicalQ == "Auto" -> "AUTO"
                            canonicalQ == "4K" -> "4K"
                            canonicalQ == "1440p" -> "2K"
                            canonicalQ == "1080p" -> "FHD"
                            canonicalQ == "720p" -> "HD"
                            canonicalQ == "480p" -> "SD"
                            canonicalQ == "360p" -> "360p"
                            canonicalQ == "240p" -> "240p"
                            canonicalQ == "144p" -> "144p"
                            else -> "SD"
                        }
                        val badgeColor = when {
                            !isQAllowed -> MaterialTheme.colorScheme.error
                            canonicalQ == "Auto" -> MaterialTheme.colorScheme.secondary
                            canonicalQ == "4K" || canonicalQ == "1440p" || canonicalQ == "1080p" || canonicalQ == "720p" -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.outline
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                                .clickable {
                                    if (!isQAllowed) {
                                        val maxRes = restrictions.getMaxAllowedResolution() ?: 720
                                        android.widget.Toast.makeText(context, "Quality $canonicalQ exceeds account limit (${maxRes}p).", android.widget.Toast.LENGTH_LONG).show()
                                        return@clickable
                                    }
                                    viewModel.selectQuality(canonicalQ, currentTime)
                                    showQualitySheet = false
                                }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .background(badgeColor.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = badgeText,
                                        color = badgeColor,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (canonicalQ == "Auto") stringResource(R.string.quality_auto_title) else canonicalQ,
                                    color = if (!isQAllowed) Color.Gray else if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                                if (!isQAllowed) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Max ${restrictions.allowedQuality ?: ""}",
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                    item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
        }
    
    if (showEpisodesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showEpisodesSheet = false },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text(modifier = Modifier.padding(horizontal = 16.dp),
                        text = stringResource(R.string.episodes), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                items(uiState.episodes.take(uiState.visibleEpisodesCount), key = { it.id }) { ep ->
                    TextButton(
                        onClick = { 
                            viewModel.selectEpisode(ep)
                            showEpisodesSheet = false 
                        },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 16.dp)
                    ) {
                        Text(
                            stringResource(R.string.episode_number, ep.episodeNumber.toString()) + if (ep.title.isNotBlank()) ": ${ep.title}" else "", 
                            color = if (ep.id == uiState.currentEpisodeId) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                        )
                    }
                }
                
                if (uiState.episodes.size > uiState.visibleEpisodesCount) {
                    item {
                        TextButton(
                            onClick = { viewModel.loadMoreEpisodes() },
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                        ) {
                            Text(stringResource(R.string.load_more_eps), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    if (showDownloadSheet) {
        val cleanMediaTitle = (uiState.originalTitle.takeIf { it.isNotBlank() } ?: uiState.title)
            .replace(Regex("""\s*-\s*S\d+E\d+.*$""", RegexOption.IGNORE_CASE), "").trim()
        SmartDownloadQualityDialog(
            title = cleanMediaTitle.ifBlank { uiState.title },
            year = uiState.releaseYear,
            isMovie = uiState.isMovie,
            season = if (uiState.isMovie) 1 else uiState.currentSeasonNumber,
            episode = if (uiState.isMovie) 1 else uiState.currentEpisodeNumber,
            isAnime = uiState.isAnime,
            posterUrl = posterUrl,
            mediaId = uiState.mediaId,
            initialQualities = emptyList(),
            currentStreamUrl = uiState.currentVideoUrl,
            onDismiss = { showDownloadSheet = false },
            onNavigateToExtensions = onNavigateToExtensions,
            mode = uiState.mode,
            providerId = uiState.providerId
        )
    }

    if (showDeleteDownloadDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDownloadDialog = false },
            title = { Text(stringResource(R.string.delete_download), color = MaterialTheme.colorScheme.onBackground) },
            text = { Text(stringResource(R.string.delete_download_confirm), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDownloadDialog = false
                    scope.launch {
                        downloadRepository.removeFromDownloads(fileId)
                        android.widget.Toast.makeText(context, context.getString(R.string.download_deleted), android.widget.Toast.LENGTH_SHORT).show()
                        if (uiState.isOffline) {
                            onBack()
                        }
                    }
                }) {
                    Text(stringResource(R.string.yes_delete), color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDownloadDialog = false }) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onBackground)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    }
}

@Composable
fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    icon: ImageVector? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
        }
        val onBgColor = MaterialTheme.colorScheme.onBackground
        Canvas(
            modifier = Modifier
                .width(32.dp)
                .height(140.dp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newValue = 1f - (offset.y / size.height).coerceIn(0f, 1f)
                        onValueChange(newValue)
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        val newValue = 1f - (change.position.y / size.height).coerceIn(0f, 1f)
                        onValueChange(newValue)
                    }
                }
        ) {
            val width = size.width
            val height = size.height
            val centerX = width / 2f
            val trackWidth = 4.dp.toPx()
            val thumbRadius = 8.dp.toPx()
            
            val thumbY = height - (height * value)
            
            // Inactive Track (Full height)
            drawRoundRect(
                color = onBgColor.copy(alpha = 0.3f),
                topLeft = Offset(centerX - trackWidth / 2f, 0f),
                size = Size(trackWidth, height),
                cornerRadius = CornerRadius(trackWidth / 2f)
            )
            
            // Active Track (From bottom to thumb)
            drawRoundRect(
                color = onBgColor,
                topLeft = Offset(centerX - trackWidth / 2f, thumbY),
                size = Size(trackWidth, height - thumbY),
                cornerRadius = CornerRadius(trackWidth / 2f)
            )
            
            // Thumb
            drawCircle(
                color = onBgColor,
                radius = thumbRadius,
                center = Offset(centerX, thumbY)
            )
        }
    }
}

@Composable
fun BottomAction(
    icon: ImageVector, 
    text: String, 
    enabled: Boolean = true, 
    iconTint: Color? = null,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp)
            .alpha(if (enabled) 1f else 0.38f)
    ) {
        Icon(
            icon, 
            contentDescription = null, 
            tint = iconTint ?: (if (enabled) MaterialTheme.colorScheme.onBackground else Color.Gray), 
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, color = if (enabled) Color.LightGray else Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Normal)
    }
}

@Composable
fun QualityAction(currentQuality: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(8.dp)
            .alpha(if (enabled) 1f else 0.38f)
    ) {
        Icon(Icons.Default.Settings, contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onBackground else Color.Gray, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(stringResource(R.string.quality), color = if (enabled) Color.LightGray else Color.Gray, fontSize = 14.sp, fontWeight = FontWeight.Normal)
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .border(1.dp, if (enabled) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            val qText = if (currentQuality == "Auto") stringResource(R.string.auto_quality) else currentQuality
            Text(qText, color = if (enabled) MaterialTheme.colorScheme.primary else Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun ActionDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

fun formatTime(timeMs: Long, forceHours: Boolean = false): String {
    if (timeMs <= 0) return if (forceHours) "0:00:00" else "00:00"
    val totalSeconds = timeMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0 || forceHours) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@Composable
fun SimpleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    thumbColor: Color = MaterialTheme.colorScheme.primary,
    thumbRadius: Float = 12f,
    trackHeight: Float = 4f // Made track slightly thinner for elegance
) {
    var isDragging by remember { mutableStateOf(false) }
    var dragPercent by remember { mutableStateOf(value) }
    val displayPercent = if (isDragging) dragPercent else value

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp) // Touch target height
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val p = (offset.x / size.width).coerceIn(0f, 1f)
                    onValueChange(p)
                    onValueChangeFinished?.invoke()
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragPercent = (offset.x / size.width).coerceIn(0f, 1f)
                        onValueChange(dragPercent)
                    },
                    onDragEnd = {
                        isDragging = false
                        onValueChangeFinished?.invoke()
                    },
                    onDragCancel = {
                        isDragging = false
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        dragPercent = (change.position.x / size.width).coerceIn(0f, 1f)
                        onValueChange(dragPercent)
                    }
                )
            }
    ) {
        val width = size.width
        val height = size.height
        val centerY = height / 2f
        
        // Inactive Track
        drawRoundRect(
            color = inactiveColor,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )
        
        // Active Track
        drawRoundRect(
            color = activeColor,
            topLeft = Offset(0f, centerY - trackHeight / 2f),
            size = Size(width * displayPercent, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f)
        )
        
        // Thumb
        drawCircle(
            color = thumbColor,
            radius = if (isDragging) thumbRadius * 1.3f else thumbRadius,
            center = Offset(width * displayPercent, centerY)
        )
    }
}