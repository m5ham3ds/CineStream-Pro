@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.example.ui.screens.details

import androidx.activity.compose.BackHandler

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import com.example.ui.theme.SuccessGreen
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.res.stringResource
import com.example.R

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState

import androidx.compose.runtime.*
import com.example.ui.screens.extensions.NoExtensionsDialog
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.data.model.ContentType
import com.example.data.util.ContentTypeResolver
import com.example.data.model.DownloadItem
import com.example.data.model.LibraryItem
import com.example.data.repository.DownloadRepository
import com.example.data.repository.LibraryRepository
import com.example.domain.models.CastMember
import com.example.domain.models.Episode
import com.example.domain.models.Season
import com.example.domain.models.VideoTrailer
import com.example.data.util.TrailerSelectionHelper
import com.example.ui.components.ExpandableSynopsis
import com.example.ui.ViewModelFactory
import kotlinx.coroutines.launch
import java.net.URLEncoder

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MovieDetailsScreen(
    onPersonClick: (String) -> Unit = {},
    movieId: String, 
    autoPlay: Boolean = false,
    initialExperienceMode: String = "STANDARD",
    onBack: () -> Unit,
    onPlay: (String, String, String?, String?, String, String?, String?) -> Unit = { _, _, _, _, _, _, _ -> },
    onNavigateToExtensions: () -> Unit = {},
    viewModel: MovieDetailsViewModel = viewModel(factory = ViewModelFactory())
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val downloadRepository = remember { DownloadRepository(context) }
    val libraryRepository = remember { LibraryRepository(context) }
    val scope = rememberCoroutineScope()
    val libraryItems by libraryRepository.getLibraryItems().collectAsState(initial = emptyList())
    val downloadItems by downloadRepository.getDownloadItems().collectAsState(initial = emptyList())
    val currentMode = if (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || uiState.movie?.mode.equals("dubbed", ignoreCase = true)) "dubbed" else "subtitled"
    val isFavorite by libraryRepository.isItemInLibrary(movieId, ContentType.MOVIE, currentMode).collectAsState(initial = false)
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()
    val downloadItem = downloadItems.find { it.id == movieId || it.mediaId == movieId }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showNotReleasedDialog by remember { mutableStateOf(false) }
    var isCoverEnlarged by remember { mutableStateOf(false) }

    val networkObserver = remember { com.example.utils.NetworkConnectivityObserver(context) }
    val isOnline by networkObserver.observe().collectAsState(initial = com.example.utils.NetworkUtils.isInternetAvailable(context))

    LaunchedEffect(isOnline) {
        if (isOnline && uiState.movie == null) {
            viewModel.loadMovie(movieId, forceRefresh = true, experienceMode = initialExperienceMode)
        }
    }

    LaunchedEffect(movieId) {
        viewModel.loadMovie(movieId, experienceMode = initialExperienceMode)
        com.example.ui.screens.player.ServerStateStore.prepareForMedia(movieId, movieId)
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (uiState.movie == null) {
            DetailsSkeleton(onBack = onBack)
        } else {
            val movie = uiState.movie!!
            val ctx = LocalContext.current
            val historyRepository = remember { com.example.data.repository.HistoryRepository(ctx) }
            var showSmartDownloadDialog by remember { mutableStateOf(false) }
            var showNoExtensionsDialog by remember { mutableStateOf(false) }
            var selectedTrailerId by remember { mutableStateOf<String?>(null) }

            val ptrState = rememberPullToRefreshState()
            val scrollState = rememberScrollState()
            var activePlayback by remember { mutableStateOf<com.example.ui.components.ActiveInlinePlayback?>(null) }
            val challengeController = remember { com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance() }
            val challengeUiState by challengeController.stateFlow.collectAsState()
            val isChallengeActive = challengeUiState.isVisible || challengeUiState.phase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT

            val activity = ctx as? android.app.Activity
            val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                activity?.isInPictureInPictureMode == true && com.example.ui.screens.player.PlayerStateHolder.isInPipMode
            } else false

            DisposableEffect(Unit) {
                com.example.utils.AdManager.enterDetailsScreen()
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
                } else {
                    com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
                }
                onDispose {
                    com.example.utils.AdManager.exitDetailsScreen()
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                        com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
                    } else {
                        com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
                    }
                    val vMode = if (movie.mode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                    com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onLeaveWorkPage(
                        workId = movie.id,
                        contentType = com.example.extension.managed.model.ContentType.MOVIE,
                        variantMode = vMode,
                        audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                        season = 1,
                        episode = 1
                    )
                }
            }

            LaunchedEffect(movie.id) {
                com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onWorkChanged(movie.id, 1, 1)
                val rawTitle = movie.originalTitle ?: movie.title
                val mediaKey = "$rawTitle-true-1-1"
                com.example.ui.screens.player.ServerStateStore.prepareForMedia(mediaKey, movie.id.toString())
            }

            BackHandler(enabled = (activePlayback != null || selectedTrailerId != null) && !isInPip) {
                activePlayback = null
                selectedTrailerId = null
            }

            val isMovieAnime = (movie.genres.any { it.equals("Anime", ignoreCase = true) }) ||
                (movie.genres.any { it.contains("Animation", ignoreCase = true) } && (movie.language == "ja" || movie.country == "JP")) ||
                com.example.data.repository.AnimePlaybackStore.isAnime(ctx, movie.id, movie.title)

            val currentLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(ctx)
            val cachedPrimaryTrailer = remember(movie.trailers, currentLang) {
                com.example.data.util.TrailerSelectionHelper.selectPrimaryTrailer(
                    movie.trailers,
                    preferredLanguage = currentLang
                )
            }
            LaunchedEffect(movie.id, currentLang) {
                viewModel.loadPrimaryTrailer(movie.id, currentLang, movie.trailers)
            }
            val selectedPrimaryTrailer = uiState.primaryTrailer ?: cachedPrimaryTrailer
            val displayMovieGenres = remember(movie.genreIds, movie.genres, currentLang) {
                if (movie.genreIds.isNotEmpty()) {
                    com.example.data.util.TmdbGenreHelper.getMovieGenreNames(movie.genreIds, currentLang)
                } else {
                    movie.genres
                }
            }

            val isDubbedExperience = (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || movie.mode.equals("dubbed", ignoreCase = true)) && !isMovieAnime
            val effectiveMode = if (isDubbedExperience) "dubbed" else "subtitled"
            val movieOrchestrator = remember { com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx) }
            val movieDubbedOrder = remember { movieOrchestrator.registry.getSearchOrder().arabicDubbed }
            val movieDisplayInfo = remember(movie, isDubbedExperience, movieDubbedOrder) {
                com.example.domain.search.MediaDisplayTitleResolver.resolveForMovie(
                    movie = movie,
                    isDubbedIntent = isDubbedExperience,
                    dubbedOrder = movieDubbedOrder,
                    preferredProviderId = movie.providerId
                )
            }
            val displayMovieTitle = movieDisplayInfo.displayTitle
            val displayMoviePoster = movieDisplayInfo.displayPosterUrl?.takeIf { it.isNotBlank() } ?: movie.posterUrl.takeIf { it.isNotBlank() } ?: movie.backdropUrl
            val matchedDubbedCandidate = if (isDubbedExperience) {
                movie.sourceCandidates.firstOrNull {
                    it.providerId == movieDisplayInfo.providerId &&
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                } ?: movie.sourceCandidates.firstOrNull {
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                }
            } else null

            val isMovieDownloaded = (downloadItem?.isCompleted == true && downloadItem?.variantMode.equals(effectiveMode, ignoreCase = true)) ||
                    com.example.utils.MediaStorageUtils.hasDownloadedMedia(ctx, movie.id, mode = effectiveMode, language = "ar") ||
                    downloadItems.any { (it.id == movie.id || it.mediaId == movie.id) && it.isCompleted && it.variantMode.equals(effectiveMode, ignoreCase = true) }

            val resumeLastPlayback = {
                val vMode = if (effectiveMode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                val orchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx)
                com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.resumeOnPlay(
                    workId = movie.id,
                    variantMode = vMode,
                    audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                    season = 1,
                    episode = 1,
                    allEligibleProviders = orchestrator.registry.getAllExtensions()
                )
                if (!restrictions.isWatchAllowed) {
                    Toast.makeText(ctx, ctx.getString(R.string.watching_restricted), Toast.LENGTH_LONG).show()
                } else if (isMovieDownloaded) {
                    selectedTrailerId = null
                    val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition(movie.id)
                    val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(ctx, movie.id)
                    val startPos = if (syncPos > 0L) syncPos else (lastPlayback?.positionMillis ?: 0L)
                    val fileId = downloadItem?.id ?: movie.id
                    activePlayback = com.example.ui.components.ActiveInlinePlayback(
                        mediaId = movie.id,
                        title = movie.title,
                        url = "local_offline_file://$fileId",
                        posterUrl = movie.posterUrl,
                        isMovie = true,
                        initialPosition = startPos,
                        mode = effectiveMode,
                        providerId = movie.providerId
                    )
                    scope.launch { scrollState.animateScrollTo(0) }
                } else {
                    if (!com.example.utils.NetworkUtils.isInternetAvailable(ctx)) {
                        Toast.makeText(ctx, ctx.getString(R.string.no_internet_check_connection), Toast.LENGTH_LONG).show()
                    } else {
                        val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(ctx, movie.id)
                        val rawTitle = movie.originalTitle ?: movie.title
                        val mediaKey = com.example.ui.screens.player.buildMediaKey(
                            title = rawTitle,
                            isMovie = true,
                            season = 1,
                            episode = 1,
                            variantMode = effectiveMode,
                            audioLang = if (effectiveMode == "dubbed") "ar" else "orig"
                        )
                        val altTitleKey = com.example.ui.screens.player.buildMediaKey(
                            title = movie.title,
                            isMovie = true,
                            season = 1,
                            episode = 1,
                            variantMode = effectiveMode,
                            audioLang = if (effectiveMode == "dubbed") "ar" else "orig"
                        )
                        val cachedData = com.example.ui.screens.player.ServerStateStore.getCachedData(movie.id.toString(), mediaKey, altTitleKey)
                        val chosenQuality = lastPlayback?.quality ?: cachedData?.extractedQualities?.firstOrNull()?.name ?: "Auto"
                        val targetCanonical = com.example.ui.screens.player.normalizeCanonicalQualityName(chosenQuality)
                        val matchingQualityUrl = if (targetCanonical != null && targetCanonical != "Auto") {
                            cachedData?.extractedQualities?.find {
                                com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) == targetCanonical && it.url.isNotBlank() && it.url.startsWith("http")
                            }?.url
                        } else null

                        val candidateUrl = if (lastPlayback != null && lastPlayback.url.isNotBlank() && lastPlayback.url.startsWith("http")) {
                            lastPlayback.url
                        } else if (!matchingQualityUrl.isNullOrBlank()) {
                            matchingQualityUrl
                        } else if (cachedData != null && cachedData.extractedQualities.isNotEmpty()) {
                            cachedData.extractedQualities.firstOrNull { it.url.isNotBlank() && it.url.startsWith("http") }?.url
                        } else if (cachedData != null && !cachedData.directStreamUrl.isNullOrBlank()) {
                            cachedData.directStreamUrl
                        } else null

                        val warmResult = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx).evaluateWarmPlayback(
                            context = ctx,
                            mediaId = movie.id.toString(),
                            candidateUrl = candidateUrl?.takeIf { com.example.ui.components.isValidPlayableMediaUrl(it) },
                            savedQuality = chosenQuality
                        )
                        val cachedUrl = when (warmResult) {
                            is com.example.extension.orchestrator.ManagedMediaOrchestrator.WarmPlaybackResult.Valid -> warmResult.streamUrl
                            is com.example.extension.orchestrator.ManagedMediaOrchestrator.WarmPlaybackResult.StaleOrInvalid -> null
                        }

                        val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition(movie.id)
                        val startPos = if (syncPos > 0L) syncPos else (lastPlayback?.positionMillis ?: 0L)

                        if (cachedUrl != null) {
                            selectedTrailerId = null
                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                mediaId = movie.id,
                                title = movie.title,
                                originalTitle = movie.originalTitle ?: movie.title,
                                year = movie.year.toString(),
                                url = cachedUrl,
                                serverName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                website = lastPlayback?.website ?: cachedData?.website,
                                posterUrl = movie.posterUrl,
                                isMovie = true,
                                initialPosition = startPos,
                                initialQuality = chosenQuality,
                                mode = effectiveMode,
                                providerId = movie.providerId
                            )
                            scope.launch { scrollState.animateScrollTo(0) }
                        } else {
                            val isReleased = (movie.releaseDate ?: "") <= java.time.LocalDate.now().toString()
                            if (!isReleased) {
                                showNotReleasedDialog = true
                            } else {
                                selectedTrailerId = null
                                activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                    mediaId = movie.id,
                                    title = movie.title,
                                    originalTitle = movie.originalTitle ?: movie.title,
                                    year = movie.year.toString(),
                                    url = "auto_extract://",
                                    serverName = null,
                                    website = null,
                                    posterUrl = movie.posterUrl,
                                    isMovie = true,
                                    initialPosition = 0L,
                                    initialQuality = chosenQuality,
                                    mode = effectiveMode,
                                    providerId = movie.providerId
                                )
                                scope.launch { scrollState.animateScrollTo(0) }
                            }
                        }

                        // Background revalidation: strictly trigger ONLY when user clicks play, and ONLY if >= 6 hours have passed!
                        val now = System.currentTimeMillis()
                        val safeKey = cachedData?.scraperKey?.ifBlank { "managed" } ?: "managed"
                        val jobKey = "$safeKey:${movie.id}:"
                        val lastRun = com.example.extension.orchestrator.BackgroundMediaRevalidator.getLastRevalidationTime(jobKey).takeIf { it > 0L }
                            ?: (cachedData?.lastUpdatedTimestamp ?: 0L)
                        val SIX_HOURS_MS = 6 * 3600 * 1000L
                        if (lastRun == 0L || (now - lastRun >= SIX_HOURS_MS)) {
                            val knownUrl = cachedData?.playbackPageUrl ?: lastPlayback?.playbackPageUrl ?: cachedUrl ?: ""
                            val sKey = cachedData?.scraperKey ?: lastPlayback?.scraperKey ?: ""
                            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(ctx)
                            managedOrchestrator.revalidateMediaInBackground(
                                mediaId = movie.id,
                                mediaTitle = rawTitle,
                                isMovie = true,
                                knownPlaybackUrl = knownUrl,
                                scraperKey = sKey,
                                currentServerName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                currentQuality = chosenQuality,
                                altKeys = listOf(movie.id.toString(), mediaKey, altTitleKey)
                            )
                        }
                    }
                }
            }

            val startPlayFlow = {
                resumeLastPlayback()
            }

            var hasAutoPlayed by remember { mutableStateOf(false) }
            LaunchedEffect(movie, autoPlay) {
                if (autoPlay && !hasAutoPlayed) {
                    hasAutoPlayed = true
                    resumeLastPlayback()
                }
            }
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.loadMovie(movieId) },
                state = ptrState,
                modifier = Modifier.fillMaxSize().padding(bottom = if (isInPip) 0.dp else padding.calculateBottomPadding())
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (!isInPip) Modifier.verticalScroll(scrollState) else Modifier)
            ) {
                
                if (showDeleteConfirm && !isInPip) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text(stringResource(R.string.delete_download), color = MaterialTheme.colorScheme.onBackground) },
                        text = { Text(stringResource(R.string.delete_download_confirm), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    if (downloadItem != null) {
                                        downloadRepository.removeFromDownloads(downloadItem)
                                    } else {
                                        downloadRepository.removeFromDownloads(movie.id)
                                    }
                                    Toast.makeText(ctx, ctx.getString(R.string.download_deleted), Toast.LENGTH_SHORT).show()
                                }
                                showDeleteConfirm = false
                            }) { Text(stringResource(R.string.yes_delete), color = Color.Red) }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onBackground) }
                        },
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                }
                
                
    if (showNotReleasedDialog && !isInPip) {
        AlertDialog(
            onDismissRequest = { showNotReleasedDialog = false },
            title = { Text(stringResource(R.string.coming_soon), color = MaterialTheme.colorScheme.onBackground) },
            text = { Text(stringResource(R.string.not_released_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { showNotReleasedDialog = false }) { Text(stringResource(R.string.ok_button)) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Hero Image or Video Player
                if (activePlayback != null) {
                    Box(modifier = if (isInPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineDetailVideoPlayer(
                            playback = activePlayback!!,
                            onPlaybackUrlExtracted = { realUrl ->
                                activePlayback = activePlayback?.copy(url = realUrl)
                            },
                            onFullscreen = { currentPos ->
                                val p = activePlayback!!
                                val realUrl = if (!p.url.startsWith("auto_extract://") && com.example.ui.components.isValidPlayableMediaUrl(p.url)) {
                                    p.url
                                } else {
                                    val rawTitle = movie.originalTitle ?: movie.title
                                    val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(
                                        movie.id.toString(),
                                        "$rawTitle-true-1-1",
                                        "${movie.title}-true-1-1"
                                    )
                                    val candidate = cached?.directStreamUrl?.takeIf { it.isNotBlank() }
                                        ?: cached?.extractedQualities?.firstOrNull { it.url.isNotBlank() && it.name != "Auto" }?.url
                                    if (com.example.ui.components.isValidPlayableMediaUrl(candidate)) candidate!! else p.url
                                }
                                if (currentPos > 0L) {
                                    com.example.ui.screens.player.PlaybackSyncStore.setPosition(movie.id, currentPos)
                                }
                                activePlayback = null
                                onPlay(p.title, realUrl, p.serverName, p.website, p.posterUrl, p.mode, p.providerId)
                            },
                            onClose = {
                                activePlayback = null
                            },
                            onNavigateToExtensions = onNavigateToExtensions,
                            onChangeServer = {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    selectedTrailerId = null
                                    activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                        mediaId = movie.id,
                                        title = movie.title,
                                        url = "auto_extract://",
                                        serverName = null,
                                        website = null,
                                        posterUrl = movie.posterUrl,
                                        isMovie = true,
                                        initialPosition = 0L,
                                        mode = if (isMovieAnime) "subtitled" else effectiveMode,
                                        providerId = movie.providerId
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (!isInPip) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displayMovieTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${movie.year} • ${displayMovieGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", movie.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                    }
                } else if (selectedTrailerId != null) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineYouTubePlayer(
                            videoId = selectedTrailerId!!,
                            modifier = Modifier.fillMaxSize(),
                            onClose = { selectedTrailerId = null }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displayMovieTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${movie.year} • ${displayMovieGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", movie.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.8f)) {
                        AsyncImage(
                            model = displayMoviePoster,
                            contentDescription = displayMovieTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { isCoverEnlarged = true }
                        )
                        Box(modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha=0.6f), MaterialTheme.colorScheme.background),
                                startY = 0f
                            )
                        ))
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                        ) {
                            Text(text = displayMovieTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${movie.year} • ${displayMovieGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(String.format("%.1f", movie.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                                }
                                Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) } // Placeholder for age rating
                            }
                        }
                    }
                }
                
                if (!isInPip) {
                if (isDubbedExperience) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.variant_dubbed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (isDubbedExperience) {
                    val rawTitle = movie.originalTitle ?: movie.title
                    val dubbedMediaKey = com.example.ui.screens.player.buildMediaKey(
                        title = rawTitle,
                        isMovie = true,
                        season = 1,
                        episode = 1,
                        variantMode = "dubbed",
                        audioLang = "ar"
                    )
                    val dubbedCached = com.example.ui.screens.player.ServerStateStore.getCachedData(dubbedMediaKey, movie.id)
                    val isDubbedUnavailable = dubbedCached != null && dubbedCached.servers.isEmpty() && dubbedCached.directStreamUrl.isNullOrBlank()

                    if (isDubbedUnavailable) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.dubbed_unavailable_banner_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .combinedClickable(
                                onClick = { startPlayFlow() },
                                onLongClick = {
                                    if (isMovieDownloaded) {
                                        showDeleteConfirm = true
                                    }
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = MaterialTheme.colorScheme.onBackground)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isMovieDownloaded) stringResource(R.string.resume_offline) else stringResource(R.string.play_now), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(50.dp)
                            .background(
                                if (isMovieDownloaded) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant, 
                                CircleShape
                            )
                            .clip(CircleShape)
                            .combinedClickable(
                                onClick = { 
                                    if (!restrictions.isDownloadAllowed) {
                                        Toast.makeText(context, context.getString(R.string.downloading_restricted_by_admin), Toast.LENGTH_LONG).show()
                                    } else if (isMovieDownloaded) {
                                        showDeleteConfirm = true
                                    } else if (downloadItem != null) {
                                        val intent = android.content.Intent(context, com.example.utils.StreamDownloaderService::class.java).apply {
                                            action = if (downloadItem.isPaused) "RESUME" else "PAUSE"
                                            putExtra("id", movie.id.toString())
                                        }
                                        context.startService(intent)
                                        scope.launch { downloadRepository.updateDownload(downloadItem.copy(isPaused = !downloadItem.isPaused)) }
                                        Toast.makeText(context, if (downloadItem.isPaused) context.getString(R.string.download_resumed) else context.getString(R.string.download_paused), Toast.LENGTH_SHORT).show()
                                     } else {
                                        showSmartDownloadDialog = true
                                    }
                                },
                                onLongClick = {
                                    if (isMovieDownloaded || (downloadItem != null && !downloadItem.isCompleted)) {
                                        showDeleteConfirm = true
                                    }
                                }
                            )
                    ) {
                        if (isMovieDownloaded) {
                            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.completed), tint = Color(0xFF4CAF50))
                        } else if (downloadItem != null) {
                            AnimatedDownloadIcon(isPaused = downloadItem.isPaused)
                        } else {
                            Icon(Icons.Default.Download, contentDescription = stringResource(R.string.downloads), tint = MaterialTheme.colorScheme.onBackground)
                        }
                    }
                    IconButton(
                        onClick = { 
                            scope.launch {
                                val item = LibraryItem.create(
                                    contentType = ContentType.MOVIE,
                                    tmdbId = movie.id,
                                    title = displayMovieTitle,
                                    posterUrl = displayMoviePoster ?: movie.posterUrl,
                                    variantMode = effectiveMode,
                                    providerId = matchedDubbedCandidate?.providerId ?: movie.providerId ?: ""
                                )
                                if (isFavorite) libraryRepository.removeFromLibrary(item)
                                else libraryRepository.addToLibrary(item)
                            }
                        },
                        modifier = Modifier.size(50.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = stringResource(R.string.add_to_library_favorites), tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Trailers: only the primary trailer is composed initially. The full list
                // is requested from Worker only after the user explicitly expands this section.
                if (selectedPrimaryTrailer != null) {
                    Text(stringResource(R.string.trailers), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "m_primary_trailer_${selectedPrimaryTrailer.key}") {
                            TrailerCard(trailer = selectedPrimaryTrailer, isPrimary = true) {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    activePlayback = null
                                    selectedTrailerId = selectedPrimaryTrailer.key
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        TextButton(
                            enabled = !uiState.isLoadingOtherTrailers,
                            onClick = {
                                if (uiState.areOtherTrailersExpanded) viewModel.hideOtherTrailers()
                                else viewModel.showOtherTrailers(movie.id, currentLang)
                            }
                        ) {
                            if (uiState.isLoadingOtherTrailers) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(if (uiState.areOtherTrailersExpanded) R.string.hide_other_trailers else R.string.show_other_trailers))
                        }
                    }
                    AnimatedVisibility(
                        visible = uiState.areOtherTrailersExpanded && !uiState.isLoadingOtherTrailers,
                        enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(180)),
                        exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(120))
                    ) {
                        if (uiState.otherTrailers.isNotEmpty()) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(uiState.otherTrailers, key = { "m_other_trailer_${it.key}" }) { trailer ->
                                    TrailerCard(trailer = trailer, isPrimary = false) {
                                        if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                            Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                        } else {
                                            activePlayback = null
                                            selectedTrailerId = trailer.key
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.no_other_trailers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                
                // Overview
                if (movie.overview.isNotBlank()) {
                    Text(stringResource(R.string.overview), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpandableSynopsis(
                        overview = movie.overview,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
                
                // Cast
                if (movie.cast.isNotEmpty()) {
                    Text(stringResource(R.string.cast), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(movie.cast.distinctBy { it.id }, key = { "m_cast_${it.id}" }) { CastMemberCard(it) { onPersonClick(it.id) } }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
                } // end if (!isInPip)
            }

            }
            if (showNoExtensionsDialog && !isInPip) {
                NoExtensionsDialog(
                    onDismiss = { showNoExtensionsDialog = false },
                    onGoToExtensions = { 
                        onNavigateToExtensions()
                    },
                    onRetry = {
                        if (com.example.extension.orchestrator.ManagedMediaOrchestrator.hasActiveExtensions()) {
                            showNoExtensionsDialog = false
                        }
                    }
                )
            }
            if (showSmartDownloadDialog && !isInPip) {
                com.example.ui.screens.player.SmartDownloadQualityDialog(
                    title = movie.title,
                    originalTitle = movie.originalTitle ?: movie.title,
                    year = movie.year.toString(),
                    isMovie = true,
                    isAnime = isMovieAnime,
                    posterUrl = movie.posterUrl,
                    mediaId = movie.id,
                    onDismiss = { showSmartDownloadDialog = false },
                    onNavigateToExtensions = onNavigateToExtensions,
                    mode = if (isMovieAnime) "subtitled" else effectiveMode,
                    providerId = movie.providerId
                )
            }

            if (isCoverEnlarged) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { isCoverEnlarged = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.95f))
                            .clickable { isCoverEnlarged = false },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = displayMoviePoster,
                            contentDescription = displayMovieTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .clickable { isCoverEnlarged = false }
                        )
                        IconButton(
                            onClick = { isCoverEnlarged = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

    }
}
}

@Composable
fun SeriesDetailsScreen(
    onPersonClick: (String) -> Unit = {},
    seriesId: String,
    autoPlay: Boolean = false,
    initialExperienceMode: String = "STANDARD",
    onBack: () -> Unit,
    onPlay: (String, String, String?, String?, String, String?, String?, String?, String?) -> Unit = { _, _, _, _, _, _, _, _, _ -> },
    onNavigateToExtensions: () -> Unit = {}
) {
    val context = LocalContext.current
    val viewModel: SeriesDetailsViewModel = viewModel(factory = ViewModelFactory())
    val uiState by viewModel.uiState.collectAsState()
    val scope = rememberCoroutineScope()
    val libraryRepository = remember { LibraryRepository(context) }
    val resolvedContentType = uiState.series?.let { ContentTypeResolver.resolveSeries(it) }
    val currentMode = if (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || uiState.series?.mode.equals("dubbed", ignoreCase = true)) "dubbed" else "subtitled"
    val isFavorite by if (resolvedContentType != null) {
        libraryRepository.isItemInLibrary(seriesId, resolvedContentType, currentMode).collectAsState(initial = false)
    } else {
        libraryRepository.isSeriesInLibrary(seriesId, currentMode).collectAsState(initial = false)
    }
    val downloadRepository = remember { DownloadRepository(context) }
    val downloads by downloadRepository.getDownloadItems().collectAsState(initial = emptyList())
    val downloadedEpisodeIds = downloads.map { it.id }.toSet()
    val historyRepository = remember { com.example.data.repository.HistoryRepository(context) }
    val watchedRepo = remember { com.example.data.repository.WatchedEpisodeRepository(context) }
    val watchedEpisodes by watchedRepo.getAllWatched().collectAsState(initial = emptyList())
    val watchedEpisodeIds = watchedEpisodes.map { it.id }.toSet()
    val restrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()

    var downloadEpisodeTarget by remember { mutableStateOf<Episode?>(null) }
    var showBatchDownloadSheet by remember { mutableStateOf(false) }
    var showNotReleasedDialog by remember { mutableStateOf(false) }
    var selectedTrailerId by remember { mutableStateOf<String?>(null) }
    var isCoverEnlarged by remember { mutableStateOf(false) }
    var episodeToDelete by remember { mutableStateOf<com.example.data.model.DownloadItem?>(null) }
    var showEpisodeDeleteConfirm by remember { mutableStateOf(false) }
    var activePlayback by remember { mutableStateOf<com.example.ui.components.ActiveInlinePlayback?>(null) }
    val activity = context as? android.app.Activity
    val isInPip = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
        activity?.isInPictureInPictureMode == true && com.example.ui.screens.player.PlayerStateHolder.isInPipMode
    } else false

    DisposableEffect(Unit) {
        com.example.utils.AdManager.enterDetailsScreen()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
        } else {
            com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
        }
        onDispose {
            com.example.utils.AdManager.exitDetailsScreen()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = activity?.isInPictureInPictureMode == true
            } else {
                com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
            }
            val s = uiState.series
            if (s != null) {
                val vMode = if (s.mode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onLeaveWorkPage(
                    workId = s.id.toString(),
                    contentType = com.example.extension.managed.model.ContentType.SERIES,
                    variantMode = vMode,
                    audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                    season = uiState.selectedSeason?.seasonNumber ?: 1,
                    episode = 1
                )
            }
        }
    }
    BackHandler(enabled = (activePlayback != null || selectedTrailerId != null) && !isInPip) {
        activePlayback = null
        selectedTrailerId = null
    }

    val networkObserver = remember { com.example.utils.NetworkConnectivityObserver(context) }
    val isOnline by networkObserver.observe().collectAsState(initial = com.example.utils.NetworkUtils.isInternetAvailable(context))

    LaunchedEffect(isOnline, uiState.series, uiState.selectedSeason) {
        if (isOnline) {
            if (uiState.series == null) {
                viewModel.loadSeries(seriesId, forceRefresh = true, experienceMode = initialExperienceMode)
            } else if ((!uiState.isSeasonSynced || uiState.episodes.isEmpty()) && !uiState.isEpisodesLoading) {
                viewModel.triggerInitialEpisodesLoad()
            }
        }
    }

    LaunchedEffect(seriesId) {
        com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.onWorkChanged(seriesId, 1, 1)
        viewModel.loadSeries(seriesId, experienceMode = initialExperienceMode)
        com.example.ui.screens.player.ServerStateStore.prepareForMedia(seriesId, seriesId)
    }

    val pullRefreshState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = uiState.isLoading,
        onRefresh = { viewModel.loadSeries(seriesId, experienceMode = initialExperienceMode) },
        state = pullRefreshState
    ) {
        val series = uiState.series
        if (series != null) {
            LaunchedEffect(series) {
                if (ContentTypeResolver.resolveSeries(series) == ContentType.ANIME ||
                    (series.genres.any { it.contains("Animation", ignoreCase = true) } && (series.originalLanguage == "ja" || series.originCountry.contains("JP"))) ||
                    com.example.data.repository.AnimePlaybackStore.isAnime(context, series.id.toString(), series.title)) {
                    com.example.data.repository.AnimePlaybackStore.markAsAnime(context, series.id.toString())
                }
            }
        }
        if (series == null) {
            DetailsSkeleton(onBack = onBack)
            return@PullToRefreshBox
        }
        if (series != null) {
            val listState = androidx.compose.foundation.lazy.rememberLazyListState()
            val isSeriesAnime = (resolvedContentType == ContentType.ANIME) ||
                (series.genres.any { it.contains("Animation", ignoreCase = true) } && (series.originalLanguage == "ja" || series.originCountry.contains("JP"))) ||
                com.example.data.repository.AnimePlaybackStore.isAnime(context, series.id.toString(), series.title)
            val currentSeriesLang = com.example.data.util.TmdbGenreHelper.getCurrentLanguage(context)
            val cachedPrimaryTrailer = remember(series.trailers, currentSeriesLang) {
                com.example.data.util.TrailerSelectionHelper.selectPrimaryTrailer(
                    series.trailers,
                    preferredLanguage = currentSeriesLang
                )
            }
            LaunchedEffect(series.id, currentSeriesLang) {
                viewModel.loadPrimaryTrailer(series.id, currentSeriesLang, series.trailers)
            }
            val selectedPrimaryTrailer = uiState.primaryTrailer ?: cachedPrimaryTrailer
            val displaySeriesGenres = remember(series.genreIds, series.genres, currentSeriesLang) {
                if (series.genreIds.isNotEmpty()) {
                    com.example.data.util.TmdbGenreHelper.getTvGenreNames(series.genreIds, currentSeriesLang)
                } else {
                    series.genres
                }
            }
            val isDubbedExperience = (initialExperienceMode.equals("ARABIC_DUBBED", ignoreCase = true) || initialExperienceMode.equals("DUBBED", ignoreCase = true) || series.mode.equals("dubbed", ignoreCase = true)) && !isSeriesAnime
            val effectiveMode = if (isDubbedExperience) "dubbed" else "subtitled"
            val seriesOrchestrator = remember { com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context) }
            val seriesDubbedOrder = remember { seriesOrchestrator.registry.getSearchOrder().arabicDubbed }
            val seriesDisplayInfo = remember(series, isDubbedExperience, seriesDubbedOrder) {
                com.example.domain.search.MediaDisplayTitleResolver.resolveForSeries(
                    series = series,
                    isDubbedIntent = isDubbedExperience,
                    dubbedOrder = seriesDubbedOrder,
                    preferredProviderId = series.providerId,
                    contentType = resolvedContentType ?: ContentType.TV
                )
            }
            val displaySeriesTitle = seriesDisplayInfo.displayTitle
            val displaySeriesPoster = seriesDisplayInfo.displayPosterUrl?.takeIf { it.isNotBlank() } ?: series.posterUrl.takeIf { it.isNotBlank() } ?: series.backdropUrl
            val matchedDubbedCandidate = if (isDubbedExperience) {
                series.sourceCandidates.firstOrNull {
                    it.providerId == seriesDisplayInfo.providerId &&
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                } ?: series.sourceCandidates.firstOrNull {
                    it.mode == com.example.domain.models.MediaVariantMode.DUBBED &&
                    it.status == com.example.domain.models.CoverageStatus.FOUND &&
                    it.sourcePageUrl.isNotBlank()
                }
            } else null
            val challengeController = remember { com.example.extension.managed.runtime.web.InteractiveChallengeController.getInstance() }
            val challengeUiState by challengeController.stateFlow.collectAsState()
            val isChallengeActive = challengeUiState.isVisible || challengeUiState.phase == com.example.extension.managed.runtime.web.ChallengeStateMachine.Phase.CHALLENGE_TIMEOUT

            LaunchedEffect(isChallengeActive) {
                if (isChallengeActive && listState.firstVisibleItemIndex > 0) {
                    listState.animateScrollToItem(0)
                }
            }
            val checkIsEpisodeDownloaded: (String) -> Boolean = { epId ->
                val epIdStr1 = "${series.id}_$epId"
                val epIdStr2 = epId
                com.example.utils.MediaStorageUtils.hasDownloadedMedia(context, epIdStr1, mode = effectiveMode, language = "ar") ||
                com.example.utils.MediaStorageUtils.hasDownloadedMedia(context, epIdStr2, mode = effectiveMode, language = "ar") ||
                downloads.any { (it.id == epIdStr1 || it.id == epIdStr2) && it.isCompleted && it.variantMode.equals(effectiveMode, ignoreCase = true) }
            }

            LaunchedEffect(series.id) {
                val rawTitle = series.originalTitle ?: series.title
                val mediaKey = "$rawTitle-false-1-1"
                com.example.ui.screens.player.ServerStateStore.prepareForMedia(mediaKey, series.id.toString())
            }

            val isSeriesLevelDownloaded = com.example.utils.MediaStorageUtils.hasDownloadedMedia(context, series.id.toString(), mode = effectiveMode, language = "ar") ||
                    downloads.any { it.mediaId == series.id.toString() && it.isCompleted && it.variantMode.equals(effectiveMode, ignoreCase = true) }

            val firstDownloadedEpisode = uiState.episodes.firstOrNull { checkIsEpisodeDownloaded(it.id) }

        val isScrollNearBottom by remember {
            derivedStateOf {
                val layoutInfo = listState.layoutInfo
                val totalItems = layoutInfo.totalItemsCount
                val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                totalItems > 0 && lastVisibleItemIndex >= totalItems - 2
            }
        }
    
    LaunchedEffect(isScrollNearBottom) {
        if (isScrollNearBottom) {
            if (uiState.episodes.isEmpty() && !uiState.isEpisodesLoading) {
                viewModel.triggerInitialEpisodesLoad()
            } else if (uiState.episodes.size > uiState.visibleEpisodesCount && !uiState.isLoadingMore) {
                viewModel.loadMoreEpisodes(context)
            }
        }
    }
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                userScrollEnabled = !isInPip
            ) {
                item {
                val firstUnplayedEpisode = uiState.episodes.firstOrNull { !watchedEpisodeIds.contains(it.id) } ?: uiState.episodes.firstOrNull()

                val resumeLastPlayback = resume@{
                    val vMode = if (series.mode.equals("dubbed", ignoreCase = true)) com.example.domain.models.MediaVariantMode.DUBBED else com.example.domain.models.MediaVariantMode.SUBTITLED
                    val orchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                    com.example.extension.managed.runtime.WorkDiscoverySessionManager.INSTANCE.resumeOnPlay(
                        workId = series.id.toString(),
                        variantMode = vMode,
                        audioLanguage = com.example.domain.models.VariantLanguage.ARABIC,
                        season = uiState.selectedSeason?.seasonNumber ?: 1,
                        episode = 1,
                        allEligibleProviders = orchestrator.registry.getAllExtensions()
                    )
                    if (!restrictions.isWatchAllowed) {
                        Toast.makeText(context, context.getString(R.string.watching_restricted), Toast.LENGTH_LONG).show()
                        return@resume
                    }
                    val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(context, series.id.toString())
                    val targetEpisode = if (lastPlayback?.episodeId != null) {
                        uiState.episodes.find { it.id == lastPlayback.episodeId } 
                            ?: firstDownloadedEpisode 
                            ?: firstUnplayedEpisode
                    } else {
                        firstDownloadedEpisode ?: firstUnplayedEpisode
                    } ?: uiState.episodes.firstOrNull()

                    if (targetEpisode != null) {
                        val ep = targetEpisode
                        val isEpDownloaded = checkIsEpisodeDownloaded(ep.id) || isSeriesLevelDownloaded
                        if (isEpDownloaded) {
                            val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${ep.episodeNumber}"
                            val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition("${series.id}_${ep.id}")
                            val startPos = if (syncPos > 0L) syncPos else (if (lastPlayback?.episodeId == ep.id) lastPlayback.positionMillis else 0L)
                            val fileId = if (checkIsEpisodeDownloaded(ep.id)) "${series.id}_${ep.id}" else series.id.toString()
                            selectedTrailerId = null
                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                mediaId = series.id.toString(),
                                episodeId = ep.id,
                                title = fullTitle,
                                url = "local_offline_file://$fileId",
                                posterUrl = ep.thumbnailUrl,
                                isMovie = false,
                                contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                initialPosition = startPos,
                                mode = effectiveMode,
                                audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                providerId = series.providerId
                            )
                            scope.launch { listState.animateScrollToItem(0) }
                        } else {
                            if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_LONG).show()
                            } else {
                                val rawTitle = series.originalTitle ?: series.title
                                val epMediaKey = "$rawTitle-false-${uiState.selectedSeason?.seasonNumber ?: 1}-${ep.episodeNumber}"
                                val altEpKey = "${series.title}-false-${uiState.selectedSeason?.seasonNumber ?: 1}-${ep.episodeNumber}"
                                val cachedData = com.example.ui.screens.player.ServerStateStore.getCachedData("${series.id}_${ep.id}", epMediaKey, altEpKey, series.id.toString())
                                val chosenQuality = (if (lastPlayback?.episodeId == ep.id) lastPlayback.quality else null)
                                    ?: cachedData?.extractedQualities?.firstOrNull()?.name ?: "Auto"

                                val targetCanonical = com.example.ui.screens.player.normalizeCanonicalQualityName(chosenQuality)
                                val matchingQualityUrl = if (targetCanonical != null && targetCanonical != "Auto") {
                                    cachedData?.extractedQualities?.find {
                                        com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) == targetCanonical && it.url.isNotBlank() && it.url.startsWith("http")
                                    }?.url
                                } else null

                                val candidateUrl = if (lastPlayback != null && lastPlayback.url.isNotBlank() && lastPlayback.url.startsWith("http") && (lastPlayback.episodeId == null || lastPlayback.episodeId == ep.id)) {
                                    lastPlayback.url
                                } else if (!matchingQualityUrl.isNullOrBlank()) {
                                    matchingQualityUrl
                                } else if (cachedData != null && cachedData.extractedQualities.isNotEmpty()) {
                                    cachedData.extractedQualities.firstOrNull { it.url.isNotBlank() && it.url.startsWith("http") }?.url
                                } else if (cachedData != null && !cachedData.directStreamUrl.isNullOrBlank()) {
                                    cachedData.directStreamUrl
                                } else null

                                val cachedUrl = candidateUrl?.takeIf { com.example.ui.components.isValidPlayableMediaUrl(it) }

                                val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${ep.episodeNumber}"
                                val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition("${series.id}_${ep.id}")
                                val startPos = if (syncPos > 0L) syncPos else (if (lastPlayback?.episodeId == ep.id) lastPlayback.positionMillis else 0L)

                                if (cachedUrl != null) {
                                    selectedTrailerId = null
                                    activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                        mediaId = series.id.toString(),
                                        episodeId = ep.id,
                                        seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                        episodeNumber = ep.episodeNumber,
                                        originalTitle = series.originalTitle ?: series.title,
                                        title = fullTitle,
                                        url = cachedUrl,
                                        serverName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                        website = lastPlayback?.website ?: cachedData?.website,
                                        posterUrl = ep.thumbnailUrl,
                                        isMovie = false,
                                        contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                        initialPosition = startPos,
                                        initialQuality = chosenQuality,
                                        mode = effectiveMode,
                                        audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                        providerId = series.providerId
                                    )
                                    scope.launch { listState.animateScrollToItem(0) }
                                } else {
                                    val isReleased = (series.firstAirDate ?: "") <= java.time.LocalDate.now().toString()
                                    if (!isReleased) {
                                        showNotReleasedDialog = true
                                    } else {
                                        selectedTrailerId = null
                                        activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                            mediaId = series.id.toString(),
                                            episodeId = ep.id.toString(),
                                            seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                            episodeNumber = ep.episodeNumber,
                                            originalTitle = series.originalTitle ?: series.title,
                                            title = fullTitle,
                                            url = "auto_extract://",
                                            serverName = null,
                                            website = null,
                                            posterUrl = ep.thumbnailUrl.ifBlank { series.posterUrl },
                                            isMovie = false,
                                            contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                            initialPosition = 0L,
                                            initialQuality = chosenQuality,
                                            mode = effectiveMode,
                                            audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                            providerId = series.providerId
                                        )
                                        scope.launch { listState.animateScrollToItem(0) }
                                    }
                                }

                                // Background revalidation: strictly trigger ONLY when user clicks play, and ONLY if >= 6 hours have passed!
                                val now = System.currentTimeMillis()
                                val safeKey = cachedData?.scraperKey?.ifBlank { "managed" } ?: "managed"
                                val jobKey = "$safeKey:${series.id}:${ep.id}"
                                val lastRun = com.example.extension.orchestrator.BackgroundMediaRevalidator.getLastRevalidationTime(jobKey).takeIf { it > 0L }
                                    ?: (cachedData?.lastUpdatedTimestamp ?: 0L)
                                val SIX_HOURS_MS = 6 * 3600 * 1000L
                                if (lastRun == 0L || (now - lastRun >= SIX_HOURS_MS)) {
                                    val knownUrl = cachedData?.playbackPageUrl ?: lastPlayback?.playbackPageUrl ?: cachedUrl ?: ""
                                    val sKey = cachedData?.scraperKey ?: lastPlayback?.scraperKey ?: ""
                                    val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                                    managedOrchestrator.revalidateMediaInBackground(
                                        mediaId = series.id.toString(),
                                        episodeId = ep.id,
                                        mediaTitle = rawTitle,
                                        isMovie = false,
                                        season = uiState.selectedSeason?.seasonNumber ?: 1,
                                        episode = ep.episodeNumber,
                                        knownPlaybackUrl = knownUrl,
                                        scraperKey = sKey,
                                        currentServerName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                        currentQuality = chosenQuality,
                                        altKeys = listOf("${series.id}_${ep.id}", epMediaKey, altEpKey, series.id.toString())
                                    )
                                }
                            }
                        }
                    } else {
                        val dlForSeries = downloads.find { (it.mediaId == series.id.toString() || it.id.startsWith("${series.id}_")) && it.isCompleted }
                        if (dlForSeries != null) {
                            selectedTrailerId = null
                            val fallbackEpId = if (dlForSeries.id.contains("_")) dlForSeries.id.substringAfter("_") else null
                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                mediaId = series.id.toString(),
                                episodeId = fallbackEpId,
                                title = dlForSeries.title,
                                url = "local_offline_file://${dlForSeries.id}",
                                posterUrl = dlForSeries.posterUrl,
                                isMovie = false,
                                contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series)
                            )
                            scope.launch { listState.animateScrollToItem(0) }
                        } else {
                            if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(context, context.getString(R.string.no_episodes_available), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }

                val startPlayFlow = {
                    resumeLastPlayback()
                }

                var hasAutoPlayed by remember { mutableStateOf(false) }
                LaunchedEffect(series, uiState.episodes, autoPlay, downloads) {
                    if (autoPlay && !hasAutoPlayed && (uiState.episodes.isNotEmpty() || downloads.isNotEmpty())) {
                        hasAutoPlayed = true
                        resumeLastPlayback()
                    }
                }

                
    if (showNotReleasedDialog && !isInPip) {
        AlertDialog(
            onDismissRequest = { showNotReleasedDialog = false },
            title = { Text(stringResource(R.string.coming_soon), color = MaterialTheme.colorScheme.onBackground) },
            text = { Text(stringResource(R.string.not_released_yet), color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { showNotReleasedDialog = false }) { Text(stringResource(R.string.ok_button)) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    // Hero Image or Video Player
                if (activePlayback != null) {
                    Box(modifier = if (isInPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineDetailVideoPlayer(
                            playback = activePlayback!!,
                            onPlaybackUrlExtracted = { realUrl ->
                                activePlayback = activePlayback?.copy(url = realUrl)
                            },
                            onFullscreen = { currentPos ->
                                val p = activePlayback!!
                                val realUrl = if (!p.url.startsWith("auto_extract://") && com.example.ui.components.isValidPlayableMediaUrl(p.url)) {
                                    p.url
                                } else {
                                    val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(
                                        series.id.toString(),
                                        p.mediaId
                                    )
                                    val candidate = cached?.directStreamUrl?.takeIf { it.isNotBlank() }
                                        ?: cached?.extractedQualities?.firstOrNull { it.url.isNotBlank() && it.name != "Auto" }?.url
                                    if (com.example.ui.components.isValidPlayableMediaUrl(candidate)) candidate!! else p.url
                                }
                                val syncKey = "${p.mediaId}_${p.episodeId ?: ""}"
                                if (currentPos > 0L) {
                                    com.example.ui.screens.player.PlaybackSyncStore.setPosition(syncKey, currentPos)
                                }
                                activePlayback = null
                                onPlay(p.title, realUrl, p.serverName, p.website, p.posterUrl, p.contentType, p.episodeId, p.mode, p.providerId)
                            },
                            onClose = {
                                activePlayback = null
                            },
                            onNavigateToExtensions = onNavigateToExtensions,
                            onChangeServer = {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    val targetEp = uiState.episodes.find { it.id.toString() == activePlayback?.episodeId }
                                        ?: firstUnplayedEpisode ?: uiState.episodes.firstOrNull()
                                    if (targetEp != null) {
                                        selectedTrailerId = null
                                        val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${targetEp.episodeNumber}"
                                        activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                            mediaId = series.id.toString(),
                                            episodeId = targetEp.id.toString(),
                                            seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                            episodeNumber = targetEp.episodeNumber,
                                            title = fullTitle,
                                            url = "auto_extract://",
                                            serverName = null,
                                            website = null,
                                            posterUrl = targetEp.thumbnailUrl.ifBlank { series.posterUrl },
                                            isMovie = false,
                                            contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                            initialPosition = 0L
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    if (!isInPip) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displaySeriesTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${series.year} • ${displaySeriesGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", series.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                    }
                } else if (selectedTrailerId != null) {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f/9f)) {
                        com.example.ui.components.InlineYouTubePlayer(
                            videoId = selectedTrailerId!!,
                            modifier = Modifier.fillMaxSize(),
                            onClose = { selectedTrailerId = null }
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        Text(text = displaySeriesTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${series.year} • ${displaySeriesGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(String.format("%.1f", series.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                            }
                            Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.8f)) {
                        AsyncImage(
                            model = displaySeriesPoster,
                            contentDescription = displaySeriesTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable { isCoverEnlarged = true }
                        )
                        Box(modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background.copy(alpha=0.6f), MaterialTheme.colorScheme.background),
                                startY = 0f
                            )
                        ))
                        Column(
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                        ) {
                            Text(text = displaySeriesTitle, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("${series.year} • ${displaySeriesGenres.take(3).joinToString(" • ")}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(String.format("%.1f", series.rating), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                                }
                                Badge(containerColor = MaterialTheme.colorScheme.surfaceVariant) { Text("18+", color = MaterialTheme.colorScheme.onBackground) } // Placeholder for age rating
                            }
                        }
                    }
                }
                
                if (!isInPip) {
                if (isDubbedExperience) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.RecordVoiceOver,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.variant_dubbed),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                if (isDubbedExperience) {
                    val rawTitle = series.originalTitle ?: series.title
                    val dubbedMediaKey = com.example.ui.screens.player.buildMediaKey(
                        title = rawTitle,
                        isMovie = false,
                        season = uiState.selectedSeason?.seasonNumber ?: 1,
                        episode = 1,
                        variantMode = "dubbed",
                        audioLang = "ar"
                    )
                    val dubbedCached = com.example.ui.screens.player.ServerStateStore.getCachedData(dubbedMediaKey, series.id.toString())
                    val isDubbedUnavailable = dubbedCached != null && dubbedCached.servers.isEmpty() && dubbedCached.directStreamUrl.isNullOrBlank()

                    if (isDubbedUnavailable) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.dubbed_unavailable_banner_message),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val isAnyDownloaded = firstDownloadedEpisode != null || isSeriesLevelDownloaded
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .combinedClickable(
                                onClick = { startPlayFlow() },
                                onLongClick = {
                                    if (isAnyDownloaded) {
                                        val epDl = firstDownloadedEpisode?.let { ep ->
                                            downloads.find { it.id == "${series.id}_${ep.id}" || it.id == ep.id }
                                        } ?: downloads.find { it.mediaId == series.id.toString() }
                                        if (epDl != null) {
                                            episodeToDelete = epDl
                                            showEpisodeDeleteConfirm = true
                                        }
                                    }
                                }
                            )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = MaterialTheme.colorScheme.onBackground)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (isAnyDownloaded) stringResource(R.string.resume_offline) else stringResource(R.string.play), color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(
                        onClick = {
                            if (!restrictions.isDownloadAllowed) {
                                Toast.makeText(context, context.getString(R.string.downloading_restricted_by_admin), Toast.LENGTH_LONG).show()
                            } else if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                            } else {
                                showBatchDownloadSheet = true
                            }
                        },
                        modifier = Modifier.size(50.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = stringResource(R.string.downloads), tint = MaterialTheme.colorScheme.onBackground)
                    }
                    IconButton(
                        onClick = {
                            scope.launch {
                                val contentType = ContentTypeResolver.resolveSeries(series)
                                val item = LibraryItem.create(
                                    contentType = contentType,
                                    tmdbId = series.id,
                                    title = displaySeriesTitle,
                                    posterUrl = displaySeriesPoster ?: series.posterUrl,
                                    variantMode = effectiveMode,
                                    providerId = matchedDubbedCandidate?.providerId ?: series.providerId ?: ""
                                )
                                if (isFavorite) libraryRepository.removeFromLibrary(item)
                                else libraryRepository.addToLibrary(item)
                            }
                        },
                        modifier = Modifier.size(50.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    ) {
                        Icon(if (isFavorite) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, contentDescription = stringResource(R.string.add_to_library_favorites), tint = MaterialTheme.colorScheme.onBackground)
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Lazy trailer loading: fetch only the selected primary trailer on page entry.
                if (selectedPrimaryTrailer != null) {
                    Text(stringResource(R.string.trailers), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        item(key = "s_primary_trailer_${selectedPrimaryTrailer.key}") {
                            TrailerCard(trailer = selectedPrimaryTrailer, isPrimary = true) {
                                if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                    Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                } else {
                                    activePlayback = null
                                    selectedTrailerId = selectedPrimaryTrailer.key
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start
                    ) {
                        TextButton(
                            enabled = !uiState.isLoadingOtherTrailers,
                            onClick = {
                                if (uiState.areOtherTrailersExpanded) viewModel.hideOtherTrailers()
                                else viewModel.showOtherTrailers(series.id, currentSeriesLang)
                            }
                        ) {
                            if (uiState.isLoadingOtherTrailers) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(stringResource(if (uiState.areOtherTrailersExpanded) R.string.hide_other_trailers else R.string.show_other_trailers))
                        }
                    }
                    AnimatedVisibility(
                        visible = uiState.areOtherTrailersExpanded && !uiState.isLoadingOtherTrailers,
                        enter = fadeIn(animationSpec = tween(180)) + expandVertically(animationSpec = tween(180)),
                        exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(120))
                    ) {
                        if (uiState.otherTrailers.isNotEmpty()) {
                            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(uiState.otherTrailers, key = { "s_other_trailer_${it.key}" }) { trailer ->
                                    TrailerCard(trailer = trailer, isPrimary = false) {
                                        if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                            Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                        } else {
                                            activePlayback = null
                                            selectedTrailerId = trailer.key
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.no_other_trailers),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                
                // Overview
                if (series.overview.isNotBlank()) {
                    Text(stringResource(R.string.overview), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    ExpandableSynopsis(
                        overview = series.overview,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                }
                
                // Cast
                if (series.cast.isNotEmpty()) {
                    Text(stringResource(R.string.cast), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        items(series.cast.distinctBy { it.id }, key = { "s_cast_${it.id}" }) { CastMemberCard(it) { onPersonClick(it.id) } }
                    }
                    Spacer(modifier = Modifier.height(32.dp))
                }
                // Seasons & Episodes
                if (series.seasons.isNotEmpty()) {
                    Text(stringResource(R.string.seasons_and_episodes), color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Season Tabs
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(series.seasons.filter { it.seasonNumber > 0 }, key = { it.id }) { season ->
                            val isSelected = uiState.selectedSeason?.id == season.id || uiState.selectedSeason?.seasonNumber == season.seasonNumber
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectSeason(season) },
                                label = { Text(stringResource(R.string.season_number, season.seasonNumber)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onBackground
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))

                    // Episodes
                } // Close if block
                } // Close if (!isInPip) block
                } // Close item block
                
                if (!isInPip) {
                if (uiState.isEpisodesLoading) {
                    item { Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                } else {
                    items(uiState.episodes.take(uiState.visibleEpisodesCount), key = { it.id }) { episode ->
                        val isWatched = watchedEpisodeIds.contains(episode.id)
                        val downloadItem = downloads.find { it.id == "${series.id}_${episode.id}" || it.id == episode.id.toString() }
                        val isDownloaded = downloadItem?.isCompleted == true || checkIsEpisodeDownloaded(episode.id)
                        EpisodeCard(
                            episode = episode,
                            isWatched = isWatched,
                            downloadItem = downloadItem,
                            onClick = {
                                if (!restrictions.isWatchAllowed) {
                                    Toast.makeText(context, context.getString(R.string.watching_restricted), Toast.LENGTH_LONG).show()
                                } else if (isDownloaded) {
                                    val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber}E${episode.episodeNumber}"
                                    val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition("${series.id}_${episode.id}")
                                    val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(context, series.id.toString())
                                    val startPos = if (syncPos > 0L) syncPos else (if (lastPlayback?.episodeId == episode.id) lastPlayback.positionMillis else 0L)
                                    selectedTrailerId = null
                                    activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                        mediaId = series.id.toString(),
                                        episodeId = episode.id.toString(),
                                        title = fullTitle,
                                        url = "local_offline_file://${series.id}_${episode.id}",
                                        posterUrl = episode.thumbnailUrl,
                                        isMovie = false,
                                        contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                        initialPosition = startPos
                                    )
                                    scope.launch { listState.animateScrollToItem(0) }
                                } else {
                                    if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                                        Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                                    } else {
                                        val rawTitle = series.originalTitle ?: series.title
                                        val epMediaKey = "$rawTitle-false-${uiState.selectedSeason?.seasonNumber ?: 1}-${episode.episodeNumber}"
                                        val altEpKey = "${series.title}-false-${uiState.selectedSeason?.seasonNumber ?: 1}-${episode.episodeNumber}"
                                        val cachedData = com.example.ui.screens.player.ServerStateStore.getCachedData("${series.id}_${episode.id}", epMediaKey, altEpKey, series.id.toString())
                                        val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(context, series.id.toString())
                                        val chosenQuality = (if (lastPlayback?.episodeId == episode.id) lastPlayback.quality else null)
                                            ?: cachedData?.extractedQualities?.firstOrNull()?.name ?: "Auto"

                                        val targetCanonical = com.example.ui.screens.player.normalizeCanonicalQualityName(chosenQuality)
                                        val matchingQualityUrl = if (targetCanonical != null && targetCanonical != "Auto") {
                                            cachedData?.extractedQualities?.find {
                                                com.example.ui.screens.player.normalizeCanonicalQualityName(it.name) == targetCanonical && it.url.isNotBlank() && it.url.startsWith("http")
                                            }?.url
                                        } else null

                                        val candidateUrl = if (lastPlayback != null && lastPlayback.url.isNotBlank() && lastPlayback.url.startsWith("http") && lastPlayback.episodeId == episode.id) {
                                            lastPlayback.url
                                        } else if (!matchingQualityUrl.isNullOrBlank()) {
                                            matchingQualityUrl
                                        } else if (cachedData != null && cachedData.extractedQualities.isNotEmpty()) {
                                            cachedData.extractedQualities.firstOrNull { it.url.isNotBlank() && it.url.startsWith("http") }?.url
                                        } else if (cachedData != null && !cachedData.directStreamUrl.isNullOrBlank()) {
                                            cachedData.directStreamUrl
                                        } else null

                                        val cachedUrl = candidateUrl?.takeIf { com.example.ui.components.isValidPlayableMediaUrl(it) }

                                        val fullTitle = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${episode.episodeNumber}"
                                        val syncPos = com.example.ui.screens.player.PlaybackSyncStore.getPosition("${series.id}_${episode.id}")
                                        val startPos = if (syncPos > 0L) syncPos else (if (lastPlayback?.episodeId == episode.id) lastPlayback.positionMillis else 0L)

                                        if (cachedUrl != null) {
                                            selectedTrailerId = null
                                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                                mediaId = series.id.toString(),
                                                episodeId = episode.id.toString(),
                                                seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                                episodeNumber = episode.episodeNumber,
                                                originalTitle = series.originalTitle ?: series.title,
                                                title = fullTitle,
                                                url = cachedUrl,
                                                serverName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                                website = lastPlayback?.website ?: cachedData?.website,
                                                posterUrl = episode.thumbnailUrl,
                                                isMovie = false,
                                                contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                                initialPosition = startPos,
                                                initialQuality = chosenQuality,
                                                mode = effectiveMode,
                                                audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                                providerId = series.providerId
                                            )
                                            scope.launch { listState.animateScrollToItem(0) }
                                        } else {
                                            selectedTrailerId = null
                                            activePlayback = com.example.ui.components.ActiveInlinePlayback(
                                                mediaId = series.id.toString(),
                                                episodeId = episode.id.toString(),
                                                seasonNumber = uiState.selectedSeason?.seasonNumber ?: 1,
                                                episodeNumber = episode.episodeNumber,
                                                originalTitle = series.originalTitle ?: series.title,
                                                title = fullTitle,
                                                url = "auto_extract://",
                                                serverName = null,
                                                website = null,
                                                posterUrl = episode.thumbnailUrl.ifBlank { series.posterUrl },
                                                isMovie = false,
                                                contentType = resolvedContentType ?: ContentTypeResolver.resolveSeries(series),
                                                initialPosition = 0L,
                                                initialQuality = chosenQuality,
                                                mode = effectiveMode,
                                                audioLanguage = if (effectiveMode.equals("dubbed", ignoreCase = true)) "ar" else "orig",
                                                providerId = series.providerId
                                            )
                                            scope.launch { listState.animateScrollToItem(0) }
                                        }

                                        // Background revalidation: strictly trigger ONLY when user clicks play, and ONLY if >= 6 hours have passed!
                                        val now = System.currentTimeMillis()
                                        val safeKey = cachedData?.scraperKey?.ifBlank { "managed" } ?: "managed"
                                        val jobKey = "$safeKey:${series.id}:${episode.id}"
                                        val lastRun = com.example.extension.orchestrator.BackgroundMediaRevalidator.getLastRevalidationTime(jobKey).takeIf { it > 0L }
                                            ?: (cachedData?.lastUpdatedTimestamp ?: 0L)
                                        val SIX_HOURS_MS = 6 * 3600 * 1000L
                                        if (lastRun == 0L || (now - lastRun >= SIX_HOURS_MS)) {
                                            val knownUrl = cachedData?.playbackPageUrl ?: lastPlayback?.playbackPageUrl ?: cachedUrl ?: ""
                                            val sKey = cachedData?.scraperKey ?: lastPlayback?.scraperKey ?: ""
                                            val managedOrchestrator = com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(context)
                                            managedOrchestrator.revalidateMediaInBackground(
                                                mediaId = series.id.toString(),
                                                episodeId = episode.id.toString(),
                                                mediaTitle = rawTitle,
                                                isMovie = false,
                                                season = uiState.selectedSeason?.seasonNumber ?: 1,
                                                episode = episode.episodeNumber,
                                                knownPlaybackUrl = knownUrl,
                                                scraperKey = sKey,
                                                currentServerName = lastPlayback?.serverName ?: cachedData?.servers?.firstOrNull(),
                                                currentQuality = chosenQuality,
                                                altKeys = listOf("${series.id}_${episode.id}", epMediaKey, altEpKey, series.id.toString())
                                            )
                                        }
                                    }
                                }
                            },
                            onLongClick = {
                                if (isDownloaded) {
                                    episodeToDelete = downloadItem ?: com.example.data.model.DownloadItem(
                                        id = "${series.id}_${episode.id}",
                                        mediaId = series.id.toString(),
                                        title = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${episode.episodeNumber}",
                                        posterUrl = episode.thumbnailUrl,
                                        isMovie = false,
                                        quality = "",
                                        progress = 1f,
                                        isPaused = false,
                                        isCompleted = true,
                                        fileSizeBytes = 0L
                                    )
                                    showEpisodeDeleteConfirm = true
                                } else {
                                    scope.launch {
                                        if (isWatched) watchedRepo.markAsUnwatched(episode.id)
                                        else watchedRepo.markAsWatched(episode.id)
                                    }
                                }
                            },
                            onLongDownloadClick = {
                                if (isDownloaded || (downloadItem != null && !downloadItem.isCompleted)) {
                                    episodeToDelete = downloadItem ?: com.example.data.model.DownloadItem(
                                        id = "${series.id}_${episode.id}",
                                        mediaId = series.id.toString(),
                                        title = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${episode.episodeNumber}",
                                        posterUrl = episode.thumbnailUrl,
                                        isMovie = false,
                                        quality = "",
                                        progress = 1f,
                                        isPaused = false,
                                        isCompleted = true,
                                        fileSizeBytes = 0L
                                    )
                                    showEpisodeDeleteConfirm = true
                                }
                            },
                            onDownloadClick = {
                                if (isDownloaded) {
                                    episodeToDelete = downloadItem ?: com.example.data.model.DownloadItem(
                                        id = "${series.id}_${episode.id}",
                                        mediaId = series.id.toString(),
                                        title = "${series.title} - S${uiState.selectedSeason?.seasonNumber ?: 1}E${episode.episodeNumber}",
                                        posterUrl = episode.thumbnailUrl,
                                        isMovie = false,
                                        quality = "",
                                        progress = 1f,
                                        isPaused = false,
                                        isCompleted = true,
                                        fileSizeBytes = 0L
                                    )
                                    showEpisodeDeleteConfirm = true
                                } else if (downloadItem != null) {
                                    val intent = android.content.Intent(context, com.example.utils.StreamDownloaderService::class.java).apply {
                                        action = if (downloadItem.isPaused) "RESUME" else "PAUSE"
                                        putExtra("id", "${series.id}_${episode.id}")
                                    }
                                    context.startService(intent)
                                    scope.launch { downloadRepository.updateDownload(downloadItem.copy(isPaused = !downloadItem.isPaused)) }
                                    Toast.makeText(context, if (downloadItem.isPaused) context.getString(R.string.download_resumed) else context.getString(R.string.download_paused), Toast.LENGTH_SHORT).show()
                                } else if (!restrictions.isDownloadAllowed) {
                                    Toast.makeText(context, context.getString(R.string.downloading_restricted_by_admin), Toast.LENGTH_LONG).show()
                                } else {
                                    downloadEpisodeTarget = episode
                                }
                            }
                        )
                    }
                    
                    if (uiState.episodes.size > uiState.visibleEpisodesCount || uiState.isLoadingMore) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp), contentAlignment = Alignment.Center) {
                                if (uiState.isLoadingMore) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.loading_eps), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                    }
                                } else {
                                    Text(stringResource(R.string.swipe_to_load), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(32.dp)) }
                }
            }
            
            if (downloadEpisodeTarget != null && !isInPip) {
                val ep = downloadEpisodeTarget!!
                com.example.ui.screens.player.SmartDownloadQualityDialog(
                    title = series.title,
                    originalTitle = series.originalTitle ?: series.title,
                    year = series.year.toString(),
                    isMovie = false,
                    season = uiState.selectedSeason?.seasonNumber ?: 1,
                    episode = ep.episodeNumber,
                    isAnime = series.genres.any { it.contains("Animation", ignoreCase = true) || it.contains("Anime", ignoreCase = true) },
                    posterUrl = ep.thumbnailUrl.ifBlank { series.posterUrl },
                    mediaId = series.id.toString(),
                    episodeId = ep.id,
                    onDismiss = { downloadEpisodeTarget = null },
                    onNavigateToExtensions = onNavigateToExtensions,
                    mode = effectiveMode,
                    providerId = series.providerId
                )
            }

            if (showBatchDownloadSheet && !isInPip) {
                com.example.ui.components.BatchDownloadSheet(
                    series = series,
                    currentSeason = uiState.selectedSeason,
                    episodes = uiState.episodes,
                    onDismiss = { showBatchDownloadSheet = false }
                )
            }

            if (isCoverEnlarged) {
                androidx.compose.ui.window.Dialog(
                    onDismissRequest = { isCoverEnlarged = false },
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.95f))
                            .clickable { isCoverEnlarged = false },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = displaySeriesPoster,
                            contentDescription = displaySeriesTitle,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .clickable { isCoverEnlarged = false }
                        )
                        IconButton(
                            onClick = { isCoverEnlarged = false },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(16.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel),
                                tint = Color.White
                            )
                        }
                    }
                }
            }

            if (showEpisodeDeleteConfirm && !isInPip && episodeToDelete != null) {
                AlertDialog(
                    onDismissRequest = {
                        showEpisodeDeleteConfirm = false
                        episodeToDelete = null
                    },
                    title = { Text(stringResource(R.string.delete_download), color = MaterialTheme.colorScheme.onBackground) },
                    text = { Text(stringResource(R.string.delete_download_confirm), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    confirmButton = {
                        TextButton(onClick = {
                            episodeToDelete?.let { epDl ->
                                scope.launch {
                                    downloadRepository.removeFromDownloads(epDl)
                                    Toast.makeText(context, context.getString(R.string.download_deleted), Toast.LENGTH_SHORT).show()
                                }
                            }
                            showEpisodeDeleteConfirm = false
                            episodeToDelete = null
                        }) { Text(stringResource(R.string.yes_delete), color = Color.Red) }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            showEpisodeDeleteConfirm = false
                            episodeToDelete = null
                        }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onBackground) }
                    },
                    containerColor = MaterialTheme.colorScheme.surface
                )
            }
        }
    }
}

@Composable
fun TrailerCard(
    trailer: VideoTrailer,
    isPrimary: Boolean = false,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 560f),
        label = "trailer-card-press-scale"
    )
    Box(
        modifier = Modifier
            .width(200.dp)
            .aspectRatio(16f/9f)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = {
                    if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
                        Toast.makeText(context, context.getString(R.string.no_internet_check_connection), Toast.LENGTH_SHORT).show()
                    } else {
                        onClick()
                    }
                }
            )
    ) {
        AsyncImage(
            model = "https://img.youtube.com/vi/${trailer.key}/hqdefault.jpg",
            contentDescription = trailer.name.ifEmpty { stringResource(R.string.trailers) },
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha=0.3f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(48.dp))
        }
        if (isPrimary) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
            ) {
                Text(
                    text = stringResource(if (trailer.isOfficial) R.string.official_trailer else R.string.primary_trailer),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
        Text(
            text = trailer.name.ifEmpty { stringResource(R.string.trailers) },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
        )
    }
}

@Composable
fun CastMemberCard(cast: CastMember, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.68f, stiffness = 560f),
        label = "cast-card-press-scale"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(80.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onClick = onClick
            )
    ) {
        val placeholderColor = MaterialTheme.colorScheme.surfaceVariant
        AsyncImage(
            model = cast.profileUrl,
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
            error = androidx.compose.ui.graphics.painter.ColorPainter(placeholderColor),
            contentDescription = cast.name.ifEmpty { "Cast member" },
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(CircleShape).background(placeholderColor)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = cast.name.ifEmpty { "—" },
            color = MaterialTheme.colorScheme.onBackground,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (cast.character.isNotBlank()) {
            Text(
                text = cast.character,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun EpisodeCard(
    episode: Episode,
    isWatched: Boolean,
    downloadItem: com.example.data.model.DownloadItem? = null,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onLongDownloadClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.width(120.dp).aspectRatio(16f/9f).clip(RoundedCornerShape(8.dp))
        ) {
            AsyncImage(
                model = episode.thumbnailUrl,
                contentDescription = episode.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)
            )
            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.play_now), tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.align(Alignment.Center))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${episode.episodeNumber}. ${episode.title}",
                color = if (isWatched) MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = stringResource(R.string.rating_r), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(String.format("%.1f", episode.rating), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.width(8.dp))
                Text("${episode.duration}m", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(episode.overview, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val isEpisodeDownloaded = downloadItem?.isCompleted == true
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    onClick = onDownloadClick,
                    onLongClick = onLongDownloadClick
                ),
            contentAlignment = Alignment.Center
        ) {
            if (isEpisodeDownloaded) {
                Icon(Icons.Default.Check, contentDescription = stringResource(R.string.completed), tint = Color(0xFF4CAF50))
            } else if (downloadItem != null) {
                AnimatedDownloadIcon(isPaused = downloadItem.isPaused)
            } else {
                Icon(Icons.Default.Download, contentDescription = stringResource(R.string.downloads), tint = MaterialTheme.colorScheme.onBackground)
            }
        }
    }
    }
@Composable
fun AnimatedDownloadIcon(isPaused: Boolean) {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition()
    val offset by infiniteTransition.animateFloat(
        initialValue = -5f,
        targetValue = 5f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = androidx.compose.animation.core.RepeatMode.Restart
        )
    )
    
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.size(24.dp).clipToBounds(), 
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        val tint = if (isPaused) androidx.compose.ui.graphics.Color.Gray.copy(alpha = 0.5f) else androidx.compose.material3.MaterialTheme.colorScheme.primary
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Default.ArrowDownward, 
            contentDescription = stringResource(R.string.downloading), 
            tint = tint, 
            modifier = androidx.compose.ui.Modifier.graphicsLayer {
                translationY = if (isPaused) 0f else offset * density
            }
        )
    


}




}

