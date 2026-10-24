package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.R
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView

private const val TAG = "YouTubePlayer"

/**
 * UI State representation for the YouTube Player.
 */
sealed class YouTubePlayerState {
    data object Idle : YouTubePlayerState()
    data object Loading : YouTubePlayerState()
    data object Ready : YouTubePlayerState()
    data class Playing(val videoId: String) : YouTubePlayerState()
    data object Paused : YouTubePlayerState()
    data object Ended : YouTubePlayerState()
    data class Error(val error: PlayerConstants.PlayerError, val message: String) : YouTubePlayerState()
}

/**
 * Opens a YouTube video via native Android Intent.
 * First tries the official YouTube app (vnd.youtube:videoId),
 * falling back to the web browser if the app is not installed.
 */
fun openYouTubeVideo(context: Context, videoId: String) {
    if (videoId.isBlank()) return
    val appIntent = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$videoId")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(appIntent)
    } catch (_: Exception) {
        try {
            context.startActivity(webIntent)
        } catch (_: Exception) {
            Toast.makeText(context, context.getString(R.string.trailer_error_unknown), Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * Modern Jetpack Compose wrapper around Pierfrancesco Soffritti's android-youtube-player.
 *
 * Implements:
 * - Proper lifecycle observation (pause on background, release on exit)
 * - Error detection (VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER, VIDEO_NOT_FOUND, etc.)
 * - Automatic and graceful fallback UI to open the video in the YouTube app or web
 * - Optional close action for seamless integration in details screens
 */
@Composable
fun InlineYouTubePlayer(
    videoId: String,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true,
    onClose: (() -> Unit)? = null,
    onFullscreenChange: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var playerState by remember { mutableStateOf<YouTubePlayerState>(YouTubePlayerState.Loading) }
    var youTubePlayerInstance by remember { mutableStateOf<YouTubePlayer?>(null) }
    var activeVideoId by remember { mutableStateOf(videoId) }
    var playerViewInstance by remember { mutableStateOf<YouTubePlayerView?>(null) }

    val currentAutoPlay by rememberUpdatedState(autoPlay)
    val currentOnFullscreenChange by rememberUpdatedState(onFullscreenChange)

    // Lifecycle binding: Register YouTubePlayerView with the current LifecycleOwner
    DisposableEffect(lifecycleOwner, playerViewInstance) {
        val view = playerViewInstance
        if (view != null) {
            lifecycleOwner.lifecycle.addObserver(view)
        }
        onDispose {
            if (view != null) {
                lifecycleOwner.lifecycle.removeObserver(view)
                try {
                    view.release()
                    Log.d(TAG, "YouTubePlayerView released cleanly")
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing YouTubePlayerView", e)
                }
            }
        }
    }

    // Handle videoId changes dynamically on the existing player instance
    LaunchedEffect(videoId) {
        if (videoId != activeVideoId) {
            Log.d(TAG, "Video ID changed from $activeVideoId to $videoId")
            activeVideoId = videoId
            playerState = YouTubePlayerState.Loading
            val player = youTubePlayerInstance
            if (player != null) {
                if (currentAutoPlay) {
                    player.loadVideo(videoId, 0f)
                } else {
                    player.cueVideo(videoId, 0f)
                }
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Embedded Player View
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                YouTubePlayerView(ctx).apply {
                    enableAutomaticInitialization = false
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    val options = IFramePlayerOptions.Builder()
                        .controls(1)
                        .fullscreen(1)
                        .rel(0)
                        .ivLoadPolicy(3)
                        .ccLoadPolicy(0)
                        .build()

                    initialize(
                        object : AbstractYouTubePlayerListener() {
                            override fun onReady(youTubePlayer: YouTubePlayer) {
                                Log.d(TAG, "YouTube player ready for videoId: $activeVideoId")
                                youTubePlayerInstance = youTubePlayer
                                playerState = YouTubePlayerState.Ready
                                if (currentAutoPlay) {
                                    youTubePlayer.loadVideo(activeVideoId, 0f)
                                } else {
                                    youTubePlayer.cueVideo(activeVideoId, 0f)
                                }
                            }

                            override fun onStateChange(
                                youTubePlayer: YouTubePlayer,
                                state: PlayerConstants.PlayerState
                            ) {
                                Log.d(TAG, "YouTube player state: $state for videoId: $activeVideoId")
                                playerState = when (state) {
                                    PlayerConstants.PlayerState.PLAYING -> YouTubePlayerState.Playing(activeVideoId)
                                    PlayerConstants.PlayerState.PAUSED -> YouTubePlayerState.Paused
                                    PlayerConstants.PlayerState.ENDED -> YouTubePlayerState.Ended
                                    PlayerConstants.PlayerState.BUFFERING -> YouTubePlayerState.Loading
                                    else -> playerState
                                }
                            }

                            override fun onError(
                                youTubePlayer: YouTubePlayer,
                                error: PlayerConstants.PlayerError
                            ) {
                                Log.w(TAG, "YouTube player error: $error for videoId: $activeVideoId")
                                val errorMsg = when (error) {
                                    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER ->
                                        context.getString(R.string.trailer_error_embedding_restricted)
                                    PlayerConstants.PlayerError.VIDEO_NOT_FOUND ->
                                        context.getString(R.string.trailer_error_not_found)
                                    PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST ->
                                        context.getString(R.string.trailer_error_invalid_id)
                                    PlayerConstants.PlayerError.HTML_5_PLAYER ->
                                        context.getString(R.string.trailer_error_html5)
                                    else ->
                                        context.getString(R.string.trailer_error_unknown)
                                }
                                playerState = YouTubePlayerState.Error(error, errorMsg)
                            }
                        },
                        true,
                        options
                    )

                    addFullscreenListener(object : FullscreenListener {
                        override fun onEnterFullscreen(fullscreenView: View, exitFullscreen: () -> Unit) {
                            currentOnFullscreenChange(true)
                        }

                        override fun onExitFullscreen() {
                            currentOnFullscreenChange(false)
                        }
                    })

                    playerViewInstance = this
                }
            },
            onRelease = { view ->
                try {
                    view.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Exception on view release", e)
                }
            }
        )

        // Loading indicator
        if (playerState is YouTubePlayerState.Loading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        // Top Control Overlay: External App Launcher + Close Action
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp)
                .align(Alignment.TopEnd),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = { openYouTubeVideo(context, activeVideoId) },
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                    .size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = stringResource(R.string.open_in_youtube),
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            if (onClose != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close_trailer),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Error Fallback UI Overlay
        if (playerState is YouTubePlayerState.Error) {
            val errorState = playerState as YouTubePlayerState.Error
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F0F14).copy(alpha = 0.96f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Color(0x22FF3B30), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.trailer_unavailable_in_app),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = errorState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    Button(
                        onClick = { openYouTubeVideo(context, activeVideoId) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF0000),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.height(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.watch_on_youtube),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
