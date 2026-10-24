package com.example.extension.orchestrator

import android.content.Context
import android.util.Log
import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.DownloadTaskRequest
import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.repository.DefaultManagedExtensionRepository
import com.example.extension.managed.repository.ExtensionUserPreferences
import com.example.extension.managed.repository.FirebaseFirestoreManagedExtensionDataSource
import com.example.extension.managed.repository.ManagedExtensionCache
import com.example.extension.managed.repository.ManagedExtensionRepository
import com.example.extension.managed.repository.SafeLocalMetadataCache
import com.example.extension.managed.runtime.DefaultControlledManagedExtensionRuntime
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.managed.usecase.DiscoverManagedServersUseCase
import com.example.extension.managed.usecase.ExtractDownloadTaskUseCase
import com.example.extension.managed.usecase.ExtractPlaybackSourceUseCase
import com.example.extension.managed.usecase.GetManagedEpisodesUseCase
import com.example.extension.managed.usecase.GetManagedMediaDetailsUseCase
import com.example.extension.managed.usecase.ManagedExtensionResolver
import com.example.extension.managed.usecase.SearchManagedExtensionsUseCase
import com.example.extension.managed.model.ManagedExtensionValidator
import com.example.extension.managed.model.QualitySource
import com.example.extension.managed.searchorder.SearchOrderRepository
import com.example.extension.managed.searchorder.DefaultSearchOrderRepository
import com.example.extension.managed.searchorder.ExtensionEligibilityFilter
import com.example.extension.managed.playback.PlaybackOrchestrator
import com.example.extension.managed.playback.PlaybackSession
import com.example.extension.managed.playback.PlaybackOrchestratorOutcome
import com.example.extension.managed.playback.QualitySufficiencyPolicy
import com.example.ui.screens.player.ServerStateStore
import com.example.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central Orchestrator integrating the Managed Extension System into real Users App flows.
 *
 * Responsibilities:
 * 1. Primary path: Evaluates and routes requests through Managed Extensions first.
 * 2. Deterministic priority and lifecycle enforcement (ACTIVE, priority 100 > 50 > 10).
 * 3. Centralized fallback gating: Only recoverable errors fall back to candidate/legacy;
 *    security/compatibility errors halt immediately without untrusted fallback.
 * 4. Bridges normalized results into Player and Downloader via ServerStateStore and PlaybackSource.
 */
class ManagedMediaOrchestrator(
    private val repository: ManagedExtensionRepository,
    val registry: ManagedExtensionRegistry = ManagedExtensionRegistry.INSTANCE,
    private val runtime: ControlledManagedExtensionRuntime = DefaultControlledManagedExtensionRuntime(
        managedExtensionRegistry = registry
    ),
    private val userPreferences: ExtensionUserPreferences,
    private val fallbackManager: FallbackManager = FallbackManager(ScraperRegistry.INSTANCE),
    private val isOnlineChecker: () -> Boolean = { true },
    val searchOrderRepository: SearchOrderRepository = DefaultSearchOrderRepository(),
    private val cache: ManagedExtensionCache = SafeLocalMetadataCache(),
    val protectedWebRuntime: com.example.extension.managed.runtime.web.ProtectedWebRuntime = com.example.extension.managed.runtime.web.ProtectedWebRuntime.getInstance()
) {

    private val orchestratorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    val managedExtensionsFlow: StateFlow<List<ManagedExtension>> = registry.extensionsFlow

    private val _globalConfigState = kotlinx.coroutines.flow.MutableStateFlow(
        if (cache.getLastKnownGood()?.isNotEmpty() == true) com.example.extension.managed.model.GlobalExtensionConfigState.READY
        else com.example.extension.managed.model.GlobalExtensionConfigState.REMOTE_SYNC_PENDING
    )
    val globalConfigState: StateFlow<com.example.extension.managed.model.GlobalExtensionConfigState> = _globalConfigState

    init {
        orchestratorScope.launch {
            registry.snapshotFlow.collect { snapshot ->
                _globalConfigState.value = snapshot.state
            }
        }
    }

    val resolver: ManagedExtensionResolver = ManagedExtensionResolver(
        registry = registry,
        scraperRegistry = ScraperRegistry.INSTANCE,
        currentAppVersionCode = runtime.currentAppVersionCode,
        supportedRuntimeApiVersion = runtime.supportedRuntimeApiVersion
    )

    val searchUseCase: SearchManagedExtensionsUseCase = SearchManagedExtensionsUseCase(
        resolver = resolver,
        runtime = runtime,
        fallbackManager = fallbackManager,
        isOnlineChecker = isOnlineChecker
    )

    val detailsUseCase: GetManagedMediaDetailsUseCase = GetManagedMediaDetailsUseCase(
        resolver = resolver,
        runtime = runtime,
        isOnlineChecker = isOnlineChecker
    )

    val episodesUseCase: GetManagedEpisodesUseCase = GetManagedEpisodesUseCase(
        resolver = resolver,
        runtime = runtime,
        isOnlineChecker = isOnlineChecker
    )

    val serverDiscoveryUseCase: DiscoverManagedServersUseCase = DiscoverManagedServersUseCase(
        resolver = resolver,
        runtime = runtime,
        fallbackManager = fallbackManager,
        isOnlineChecker = isOnlineChecker
    )

    val extractPlaybackSourceUseCase: ExtractPlaybackSourceUseCase = ExtractPlaybackSourceUseCase(
        runtime = runtime
    )

    val extractDownloadTaskUseCase: ExtractDownloadTaskUseCase = ExtractDownloadTaskUseCase(
        runtime = runtime
    )

    val revalidator: BackgroundMediaRevalidator = BackgroundMediaRevalidator(
        registry = registry,
        runtime = runtime,
        isOnlineChecker = isOnlineChecker,
        coroutineScope = orchestratorScope
    )

    val eligibilityFilter: ExtensionEligibilityFilter = ExtensionEligibilityFilter(
        scraperRegistry = ScraperRegistry.INSTANCE,
        validator = ManagedExtensionValidator
    )
    val playbackOrchestrator: PlaybackOrchestrator = PlaybackOrchestrator(
        searchOrderRepository = searchOrderRepository,
        eligibilityFilter = eligibilityFilter,
        extensionRegistry = registry,
        runtime = runtime,
        sufficiencyPolicy = QualitySufficiencyPolicy(),
        isOnlineChecker = isOnlineChecker,
        protectedWebRuntime = protectedWebRuntime
    )
    val resolutionCache: com.example.extension.managed.playback.PlaybackResolutionCache = com.example.extension.managed.playback.PlaybackResolutionCache()

    fun cachePlaybackResolution(
        mediaId: String,
        contentType: ContentType,
        season: Int = 1,
        episode: Int = 1,
        providerId: String = "managed",
        selectedQuality: String = "Auto",
        streamUrl: String,
        availableServers: List<String> = emptyList(),
        serverLinks: Map<String, String> = emptyMap(),
        scraperKey: String? = null,
        mode: String = "subtitled",
        language: String = "ar"
    ): com.example.extension.managed.playback.PlaybackResolution? {
        if (!com.example.extension.managed.playback.PlaybackResolutionCache.isValidStreamUrl(streamUrl)) return null
        val res = com.example.extension.managed.playback.PlaybackResolution(
            mediaIdentity = "$mediaId:${if (contentType.isMovie) "movie" else "series"}:$season:$episode",
            contentType = contentType,
            mediaId = mediaId,
            season = season,
            episode = episode,
            providerId = providerId,
            selectedQuality = selectedQuality,
            streamUrl = streamUrl,
            resolvedAt = System.currentTimeMillis(),
            lastValidatedAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + (2 * 60 * 60 * 1000L),
            availableServers = availableServers,
            serverLinks = serverLinks,
            scraperKey = scraperKey,
            mode = mode,
            language = language
        )
        return resolutionCache.put(res)
    }

    fun startPlaybackSession(
        mediaId: String,
        title: String,
        originalTitle: String? = null,
        year: String = "",
        isMovie: Boolean = true,
        season: Int = 1,
        episode: Int = 1,
        contentType: ContentType = if (isMovie) ContentType.MOVIE else ContentType.SERIES,
        variantMode: String = "subtitled",
        audioLanguage: String = "ar",
        attempt: com.example.extension.managed.playback.PlaybackAttempt? = null
    ): PlaybackSession {
        return PlaybackSession(
            mediaId = mediaId,
            title = title,
            originalTitle = originalTitle,
            year = year,
            isMovie = isMovie,
            season = season,
            episode = episode,
            contentType = contentType,
            variantMode = variantMode,
            audioLanguage = audioLanguage,
            attempt = attempt ?: com.example.extension.managed.playback.PlaybackAttempt.getOrCreate()
        )
    }

    sealed interface WarmPlaybackResult {
        data class Valid(
            val streamUrl: String,
            val quality: String,
            val isLocal: Boolean
        ) : WarmPlaybackResult

        object StaleOrInvalid : WarmPlaybackResult
    }

    fun evaluateWarmPlayback(
        context: Context,
        mediaId: String,
        episodeId: String? = null,
        candidateUrl: String? = null,
        savedQuality: String? = null,
        maxAgeMs: Long = 2 * 60 * 60 * 1000L,
        mode: String? = null,
        language: String? = null,
        season: Int = 1
    ): WarmPlaybackResult {
        val isExplicitDubbed = mode?.equals("dubbed", ignoreCase = true) == true || mode?.equals("arabic_dubbed", ignoreCase = true) == true

        // 0. Check PlaybackResolutionCache (authoritative in-memory resolution cache)
        val cachedRes = resolutionCache.findValidResolution(
            mediaId = mediaId,
            contentType = if (episodeId.isNullOrBlank()) ContentType.MOVIE else ContentType.SERIES,
            season = season,
            episode = episodeId?.toIntOrNull() ?: 1,
            requestedQuality = savedQuality,
            mode = mode,
            language = language
        )
        if (cachedRes != null && com.example.extension.managed.playback.PlaybackResolutionCache.isValidStreamUrl(cachedRes.streamUrl)) {
            val isLocal = cachedRes.streamUrl.startsWith("file://") || cachedRes.streamUrl.startsWith("local_offline_file://") || cachedRes.streamUrl.startsWith("content://")
            return WarmPlaybackResult.Valid(
                streamUrl = cachedRes.streamUrl,
                quality = cachedRes.selectedQuality,
                isLocal = isLocal
            )
        }

        // 1. Direct local file check
        if (!candidateUrl.isNullOrBlank()) {
            if (candidateUrl.startsWith("local_offline_file://")) {
                val fileId = candidateUrl.removePrefix("local_offline_file://")
                val f = com.example.utils.MediaStorageUtils.findMediaFile(context, fileId, mode = mode, language = language)
                    ?: (if (!isExplicitDubbed) com.example.utils.MediaStorageUtils.findMediaFile(context, fileId) else null)
                    ?: com.example.utils.MediaStorageUtils.findMediaFile(context, mediaId, mode = mode, language = language)
                    ?: (if (!isExplicitDubbed) com.example.utils.MediaStorageUtils.findMediaFile(context, mediaId) else null)
                    ?: if (!episodeId.isNullOrBlank()) {
                        com.example.utils.MediaStorageUtils.findMediaFile(context, episodeId, mode = mode, language = language)
                            ?: (if (!isExplicitDubbed) com.example.utils.MediaStorageUtils.findMediaFile(context, episodeId) else null)
                    } else null
                if (f != null && f.exists()) {
                    return WarmPlaybackResult.Valid(
                        streamUrl = android.net.Uri.fromFile(f).toString(),
                        quality = savedQuality ?: "Auto",
                        isLocal = true
                    )
                }
            } else if (candidateUrl.startsWith("file://") || candidateUrl.startsWith("content://")) {
                return WarmPlaybackResult.Valid(
                    streamUrl = candidateUrl,
                    quality = savedQuality ?: "Auto",
                    isLocal = true
                )
            }
        }

        // 2. Check local downloaded storage
        val downloadId = if (episodeId.isNullOrBlank()) mediaId else "${mediaId}_$episodeId"
        val localDownloadedFile = com.example.utils.MediaStorageUtils.findMediaFile(context, downloadId, mode = mode, language = language)
            ?: (if (!isExplicitDubbed) com.example.utils.MediaStorageUtils.findMediaFile(context, downloadId) else null)
        if (localDownloadedFile != null && localDownloadedFile.exists()) {
            return WarmPlaybackResult.Valid(
                streamUrl = android.net.Uri.fromFile(localDownloadedFile).toString(),
                quality = savedQuality ?: "Auto",
                isLocal = true
            )
        }

        // 3. Evaluate candidate URL if it's an explicit HTTP playable stream URL
        if (!candidateUrl.isNullOrBlank() && 
            !candidateUrl.startsWith("auto_extract://") && 
            com.example.ui.components.isValidPlayableMediaUrl(candidateUrl)) {
            val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(context, mediaId, episodeId)
            val isFresh = if (lastPlayback != null && lastPlayback.url == candidateUrl) {
                com.example.utils.LastPlaybackStore.isUrlFresh(candidateUrl, lastPlayback.timestamp, maxAgeMs)
            } else {
                true
            }
            if (isFresh) {
                return WarmPlaybackResult.Valid(
                    streamUrl = candidateUrl,
                    quality = savedQuality ?: lastPlayback?.quality ?: "Auto",
                    isLocal = false
                )
            }
        }

        // 4. Check LastPlaybackStore
        val lastPlayback = com.example.utils.LastPlaybackStore.getLastPlayback(context, mediaId, episodeId)
        if (lastPlayback != null && !lastPlayback.url.isNullOrBlank()) {
            val url = lastPlayback.url
            if (url.startsWith("local_offline_file://") || url.startsWith("file://") || url.startsWith("content://")) {
                val f = com.example.utils.MediaStorageUtils.findMediaFile(context, downloadId)
                    ?: com.example.utils.MediaStorageUtils.findMediaFile(context, mediaId)
                if (f != null && f.exists()) {
                    return WarmPlaybackResult.Valid(
                        streamUrl = android.net.Uri.fromFile(f).toString(),
                        quality = lastPlayback.quality,
                        isLocal = true
                    )
                }
            } else if (com.example.utils.LastPlaybackStore.isUrlFresh(url, lastPlayback.timestamp, maxAgeMs) &&
                com.example.ui.components.isValidPlayableMediaUrl(url)) {
                return WarmPlaybackResult.Valid(
                    streamUrl = url,
                    quality = lastPlayback.quality,
                    isLocal = false
                )
            }
        }

        return WarmPlaybackResult.StaleOrInvalid
    }

    fun invalidateStreamUrl(
        context: Context,
        mediaId: String,
        episodeId: String? = null,
        mediaKey: String? = null
    ) {
        resolutionCache.invalidateMedia(mediaId)
        if (!episodeId.isNullOrBlank()) {
            val epNum = episodeId.toIntOrNull() ?: 1
            resolutionCache.invalidateEpisode(mediaId, 1, epNum)
        }
        com.example.utils.LastPlaybackStore.invalidateStreamUrl(context, mediaId, episodeId)
        if (!mediaKey.isNullOrBlank()) {
            com.example.ui.screens.player.ServerStateStore.invalidateStreamUrl(mediaKey)
        }
        com.example.ui.screens.player.ServerStateStore.invalidateStreamUrl(mediaId)
    }

    suspend fun orchestratePlayback(
        session: PlaybackSession,
        onFirstPlayableSource: (PlaybackSource) -> Unit,
        onQualitiesDiscovered: (List<QualitySource>) -> Unit = {},
        onError: (String) -> Unit = {}
    ): PlaybackOrchestratorOutcome {
        // Phase 05M Rule 02 & 18: ZERO Firebase requests during Playback.
        // Consult local registry snapshot directly.
        val snapshot = registry.getCurrentSnapshot()
        val isReady = snapshot.isReady || snapshot.getActiveExtensions().isNotEmpty() || registry.getActiveExtensions().isNotEmpty()

        com.example.extension.managed.trace.Phase05HLogger.log(
            "PLAYBACK",
            session.mediaId,
            "PLAYBACK_REGISTRY_READ: activeCount=${registry.getActiveExtensions().size}, rev=${snapshot.revision}"
        )

        val order = registry.getOrderForContentType(session.contentType)
        com.example.extension.managed.trace.Phase05HLogger.log(
            "PLAYBACK",
            session.mediaId,
            "PLAYBACK_SEARCH_ORDER_READ: type=${session.contentType}, order=$order"
        )

        if (!isReady && _globalConfigState.value == com.example.extension.managed.model.GlobalExtensionConfigState.REMOTE_SYNC_PENDING) {
            // Restore from persistent LKG cache in memory if needed without Firebase
            val lkg = cache.getLastKnownGood()
            if (!lkg.isNullOrEmpty()) {
                registry.setExtensions(lkg)
                _globalConfigState.value = com.example.extension.managed.model.GlobalExtensionConfigState.READY
            }
        }

        val currentState = _globalConfigState.value
        com.example.extension.managed.trace.Phase05HLogger.log(
            "PLAY_GATE",
            session.mediaId,
            "Evaluating Playback Gate: state=$currentState"
        )

        if (currentState != com.example.extension.managed.model.GlobalExtensionConfigState.READY && !isReady) {
            val userMsg = "إعدادات مصادر التشغيل غير متوفرة"
            onError(userMsg)
            return PlaybackOrchestratorOutcome.Failure(
                message = "Playback blocked by global extension config state: $currentState",
                cause = ExtensionError.GlobalConfigUnavailable("Global extension configuration is $currentState")
            )
        }

        return playbackOrchestrator.orchestratePlayback(
            session = session,
            onFirstPlayableSource = onFirstPlayableSource,
            onQualitiesDiscovered = onQualitiesDiscovered,
            onError = onError
        )
    }

    fun revalidateMediaInBackground(
        mediaId: String,
        episodeId: String? = null,
        mediaTitle: String,
        isMovie: Boolean,
        season: Int = 1,
        episode: Int = 1,
        knownPlaybackUrl: String,
        scraperKey: String = "",
        currentServerName: String? = null,
        currentQuality: String = "Auto",
        altKeys: List<String> = emptyList(),
        force: Boolean = false
    ): Job {
        return revalidator.revalidateMedia(
            mediaId = mediaId,
            episodeId = episodeId,
            mediaTitle = mediaTitle,
            isMovie = isMovie,
            season = season,
            episode = episode,
            knownPlaybackUrl = knownPlaybackUrl,
            scraperKey = scraperKey,
            currentServerName = currentServerName,
            currentQuality = currentQuality,
            altKeys = altKeys,
            force = force
        )
    }

    fun cancelAllBackgroundRevalidations(exceptKey: String? = null) {
        revalidator.cancelAllExcept(exceptKey)
    }

    companion object {
        val DEFAULT_EGYDEAD_MANAGED_EXTENSION = ManagedExtension(
            id = "egydead",
            name = "EgyDead (Managed)",
            description = "المشاهدة والتحميل عبر إيجي ديد المدار رسمياً",
            baseUrl = "https://tv10.egydead.live",
            iconUrl = "",
            scraperKey = "egydead",
            definitionVersion = 1,
            minAppVersionCode = 1,
            runtimeApiVersion = 1,
            priority = 100,
            language = "ar",
            contentTypes = setOf(ContentType.MOVIE, ContentType.SERIES, ContentType.ANIME),
            audioCapabilities = setOf(com.example.domain.models.AudioCapability.ARABIC_DUB, com.example.domain.models.AudioCapability.ORIGINAL_AUDIO),
            status = ExtensionLifecycleStatus.ACTIVE,
            updatedAt = 1710000000000L,
            userEnabled = true
        )

        val DEFAULT_QFILM_MANAGED_EXTENSION = ManagedExtension(
            id = "qfilm",
            name = "كيو فيلم",
            description = "المشاهدة والتحميل عبر كيو فيلم المدار رسمياً",
            baseUrl = "https://a.qfilm.tv",
            iconUrl = "",
            scraperKey = "qfilm",
            definitionVersion = 1,
            minAppVersionCode = 1,
            runtimeApiVersion = 1,
            priority = 110,
            language = "ar",
            contentTypes = setOf(ContentType.MOVIE, ContentType.ANIME),
            audioCapabilities = setOf(com.example.domain.models.AudioCapability.ARABIC_DUB, com.example.domain.models.AudioCapability.ORIGINAL_AUDIO),
            status = ExtensionLifecycleStatus.ACTIVE,
            updatedAt = 1710000000000L,
            userEnabled = true
        )

        val DEFAULT_WITANIME_MANAGED_EXTENSION = ManagedExtension(
            id = "witanime",
            name = "WitAnime",
            description = "المشاهدة عبر ويت أنمي المدار رسمياً",
            baseUrl = "https://witanime.site",
            iconUrl = "",
            scraperKey = "witanime",
            definitionVersion = 1,
            minAppVersionCode = 1,
            runtimeApiVersion = 1,
            priority = 105,
            language = "ar",
            contentTypes = setOf(ContentType.ANIME, ContentType.SERIES, ContentType.MOVIE),
            status = ExtensionLifecycleStatus.ACTIVE,
            updatedAt = 1710000000000L,
            userEnabled = true
        )

        val DEFAULT_ANIME4UP_MANAGED_EXTENSION = ManagedExtension(
            id = "anime4up",
            name = "Anime4Up",
            description = "المشاهدة عبر أنمي فور أب المدار رسمياً",
            baseUrl = "https://w1.anime4up.rest",
            iconUrl = "",
            scraperKey = "anime4up",
            definitionVersion = 1,
            minAppVersionCode = 1,
            runtimeApiVersion = 1,
            priority = 108,
            language = "ar",
            contentTypes = setOf(ContentType.ANIME, ContentType.MOVIE, ContentType.SERIES),
            status = ExtensionLifecycleStatus.ACTIVE,
            updatedAt = 1710000000000L,
            userEnabled = true
        )

        val DEFAULT_ANIMEBLKOM_MANAGED_EXTENSION = ManagedExtension(
            id = "animeblkom",
            name = "AnimeBlkom",
            description = "المشاهدة عبر أنمي بالكوم المدار رسمياً",
            baseUrl = "https://animeblkom.net",
            iconUrl = "",
            scraperKey = "animeblkom",
            definitionVersion = 1,
            minAppVersionCode = 1,
            runtimeApiVersion = 1,
            priority = 106,
            language = "ar",
            contentTypes = setOf(ContentType.ANIME, ContentType.MOVIE, ContentType.SERIES),
            status = ExtensionLifecycleStatus.ACTIVE,
            updatedAt = 1710000000000L,
            userEnabled = true
        )

        val BUNDLED_DEFAULT_EXTENSIONS = listOf(
            DEFAULT_QFILM_MANAGED_EXTENSION,
            DEFAULT_EGYDEAD_MANAGED_EXTENSION,
            DEFAULT_WITANIME_MANAGED_EXTENSION,
            DEFAULT_ANIME4UP_MANAGED_EXTENSION,
            DEFAULT_ANIMEBLKOM_MANAGED_EXTENSION
        )

        @Volatile
        private var instance: ManagedMediaOrchestrator? = null
        private val isInitialized = AtomicBoolean(false)

        fun getInstance(context: Context): ManagedMediaOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: buildDefault(context.applicationContext).also {
                    instance = it
                    if (isInitialized.compareAndSet(false, true)) {
                        try {
                            com.example.extension.managed.repository.ManagedExtensionRealtimeSyncManager.getInstance().startListening()
                        } catch (e: Exception) {
                            com.example.extension.managed.trace.Phase05HLogger.log("SYNC", "orchestrator", "Realtime sync start notice: ${e.message}")
                        }
                        it.refreshRemote(force = false)
                    }
                }
            }
        }

        private fun buildDefault(appContext: Context): ManagedMediaOrchestrator {
            val userPrefs = SharedPreferencesExtensionUserPreferences(appContext)
            val cache = SafeLocalMetadataCache()
            val remoteSource = FirebaseFirestoreManagedExtensionDataSource()
            val repo = DefaultManagedExtensionRepository(
                remoteDataSource = remoteSource,
                cache = cache,
                userPreferences = userPrefs,
                bundledDefaults = BUNDLED_DEFAULT_EXTENSIONS
            )
            val registry = ManagedExtensionRegistry.INSTANCE

            // Pre-seed from last-known-good cache if present to respect past Admin disable states
            val cachedCatalog = cache.getLastKnownGood()
            val existing = registry.getAllExtensions()
            if (cachedCatalog != null && cachedCatalog.isNotEmpty()) {
                registry.setExtensions(cachedCatalog)
                com.example.extension.managed.trace.Phase05HLogger.log(
                    "CACHE",
                    "orchestrator",
                    "Pre-loaded ${cachedCatalog.size} extensions from last-known-good cache (active=${registry.getActiveExtensions().size})"
                )
            } else if (existing.isEmpty()) {
                // Phase 05V: Pre-seed bundled defaults as LKG baseline so cold start never fails at 0ms.
                // Remote sync will validate and update asynchronously without blocking first-tap playback.
                registry.setExtensions(BUNDLED_DEFAULT_EXTENSIONS)
                com.example.extension.managed.trace.Phase05HLogger.log(
                    "STATE",
                    "orchestrator",
                    "Clean start: pre-seeded ${BUNDLED_DEFAULT_EXTENSIONS.size} bundled extensions as local LKG baseline"
                )
            }

            val runtime = DefaultControlledManagedExtensionRuntime(
                managedExtensionRegistry = registry,
                webEngineProvider = {
                    com.example.extension.managed.web.ControlledWebViewEngine(appContext)
                },
                connectivityMonitor = com.example.extension.managed.runtime.network.DefaultConnectivityMonitor {
                    NetworkUtils.isInternetAvailable(appContext)
                }
            )

            return ManagedMediaOrchestrator(
                repository = repo,
                registry = registry,
                runtime = runtime,
                userPreferences = userPrefs,
                isOnlineChecker = { NetworkUtils.isInternetAvailable(appContext) },
                cache = cache,
                protectedWebRuntime = com.example.extension.managed.runtime.web.ProtectedWebRuntime.getInstance(appContext)
            )
        }

        /**
         * For unit and integration tests.
         */
        fun setTestInstance(orchestrator: ManagedMediaOrchestrator) {
            instance = orchestrator
            isInitialized.set(true)
        }

        fun hasActiveExtensions(): Boolean {
            val fromInstance = instance?.registry?.getActiveExtensions()
            if (fromInstance != null) {
                return fromInstance.isNotEmpty()
            }
            val fromRegistry = ManagedExtensionRegistry.INSTANCE.getActiveExtensions()
            return fromRegistry.isNotEmpty()
        }

        fun hasActiveExtensions(contentType: ContentType?): Boolean {
            val fromInstance = instance?.hasActiveExtensions(contentType)
            if (fromInstance != null) {
                return fromInstance
            }
            val exts = ManagedExtensionRegistry.INSTANCE.getActiveExtensions()
            return if (contentType != null) exts.any { it.contentTypes.contains(contentType) } else exts.isNotEmpty()
        }

        fun getActiveExtensionNames(): List<String> {
            return (instance?.registry?.getActiveExtensions()
                ?: ManagedExtensionRegistry.INSTANCE.getActiveExtensions()).map { it.name }
        }
    }

    /**
     * Refreshes managed extension definitions from Firestore asynchronously.
     * Complies with Firebase Spark Plan constraint: One-time fetch + Cache.
     */
    fun refreshRemote(force: Boolean = false) {
        orchestratorScope.launch {
            forceRefresh()
        }
    }

    /**
     * Suspending force refresh for UI use with progress indicator.
     */
    suspend fun forceRefresh() {
        try {
            val result = repository.getExtensions(forceRefresh = true)
            if (result.isSuccess) {
                val freshList = result.getOrNull() ?: emptyList()
                if (freshList.isNotEmpty()) {
                    // Strict remote validation gate before updating registry per FIX 3
                    val validatedList = freshList.filter { ext ->
                        com.example.extension.managed.model.ManagedExtensionValidator.validateRemoteEntry(
                            extension = ext,
                            currentAppVersionCode = runtime.currentAppVersionCode
                        ) is com.example.extension.managed.model.ManagedExtensionValidator.ValidationResult.Valid
                    }
                    if (validatedList.isNotEmpty()) {
                        registry.setExtensions(validatedList)
                        _globalConfigState.value = com.example.extension.managed.model.GlobalExtensionConfigState.READY
                        com.example.extension.managed.trace.Phase05GLogger.log(
                            "REGISTRY",
                            "orchestrator",
                            "Updated registry with ${validatedList.size} validated extensions"
                        )
                        com.example.extension.managed.trace.Phase05HLogger.log(
                            "SYNC",
                            "orchestrator",
                            "Global sync success: count=${validatedList.size}, active=${registry.getActiveExtensions().size}"
                        )
                        for (ext in validatedList) {
                            com.example.extension.managed.trace.Phase05HLogger.log("STATUS", ext.id, "status=${ext.status}")
                        }
                    } else {
                        _globalConfigState.value = com.example.extension.managed.model.GlobalExtensionConfigState.READY
                        com.example.extension.managed.trace.Phase05GLogger.log(
                            "REGISTRY",
                            "orchestrator",
                            "Remote extensions rejected by validation gate; keeping existing healthy registry"
                        )
                    }
                } else {
                    // Genuine empty list from remote
                    registry.setExtensions(emptyList())
                    _globalConfigState.value = com.example.extension.managed.model.GlobalExtensionConfigState.READY
                    com.example.extension.managed.trace.Phase05GLogger.log(
                        "REGISTRY",
                        "orchestrator",
                        "Remote catalog returned 0 extensions (genuine empty)"
                    )
                    com.example.extension.managed.trace.Phase05HLogger.log(
                        "SYNC",
                        "orchestrator",
                        "Remote catalog returned 0 extensions: all extensions deactivated globally"
                    )
                }
            } else {
                if (registry.getAllExtensions().isNotEmpty()) {
                    _globalConfigState.value = com.example.extension.managed.model.GlobalExtensionConfigState.READY
                    com.example.extension.managed.trace.Phase05HLogger.log(
                        "STATE",
                        "orchestrator",
                        "Remote sync failed, retained last-known-good cache: READY"
                    )
                } else {
                    _globalConfigState.value = com.example.extension.managed.model.GlobalExtensionConfigState.GLOBAL_CONFIG_UNAVAILABLE
                    com.example.extension.managed.trace.Phase05HLogger.log(
                        "STATE",
                        "orchestrator",
                        "Remote sync failed and no cache available: GLOBAL_CONFIG_UNAVAILABLE"
                    )
                }
                com.example.extension.managed.trace.Phase05GLogger.log(
                    "FIREBASE",
                    "orchestrator",
                    "forceRefresh repository error: ${result.exceptionOrNull()?.message}; keeping existing registry"
                )
            }
            searchOrderRepository.getSearchOrder(forceRefresh = true)
        } catch (e: Exception) {
            com.example.extension.managed.trace.Phase05GLogger.log(
                "FIREBASE",
                "orchestrator",
                "forceRefresh exception: ${e.message}; keeping existing registry"
            )
        }
    }

    /**
     * Read-only client enforcement: Users App MUST NEVER write bundled defaults into Firestore
     * or override Admin configurations.
     */
    suspend fun syncDefaultExtensionsToFirestore(): Result<Int> = withContext(Dispatchers.IO) {
        Log.i("ManagedMediaOrchestrator", "syncDefaultExtensionsToFirestore is disabled: client is read-only")
        Result.success(0)
    }

    suspend fun awaitRuntimeReady(timeoutMillis: Long = 2500L, contentType: ContentType? = null): Boolean {
        if (hasActiveExtensions(contentType)) return true
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            registry.snapshotFlow.first { snapshot ->
                snapshot.hasActiveExtensions(contentType) || snapshot.state == com.example.extension.managed.model.GlobalExtensionConfigState.READY
            }
            true
        } ?: run {
            if (!hasActiveExtensions(contentType) && registry.getAllExtensions().isEmpty()) {
                registry.setExtensions(BUNDLED_DEFAULT_EXTENSIONS)
            }
            hasActiveExtensions(contentType)
        }
    }

    fun hasActiveExtensions(contentType: ContentType? = null): Boolean {
        val active = registry.getActiveExtensions()
        if (contentType == null) return active.isNotEmpty()
        return active.any { it.contentTypes.contains(contentType) }
    }

    fun getActiveExtensions(): List<ManagedExtension> {
        return registry.getActiveExtensions()
    }

    fun updateUserPreference(extensionId: String, enabled: Boolean) {
        userPreferences.setExtensionEnabled(extensionId, enabled)
        registry.updateExtensionUserPreference(extensionId, enabled)
    }

    suspend fun refreshExtensions() {
        forceRefresh()
    }

    fun setExtensionEnabled(extensionId: String, enabled: Boolean) {
        updateUserPreference(extensionId, enabled)
    }

    /**
     * Executes search query across eligible managed extensions.
     */
    suspend fun searchMedia(
        query: String,
        contentType: ContentType? = null,
        page: Int = 1
    ): Result<SearchResult> {
        return searchUseCase.execute(query, contentType, page)
    }

    /**
     * Retrieves normalized media details via managed extension.
     */
    suspend fun getMediaDetails(
        contentUrl: String,
        extensionId: String? = null
    ): Result<MediaDetailsResult> {
        return detailsUseCase.execute(contentUrl, extensionId)
    }

    /**
     * Retrieves normalized series episodes via managed extension.
     */
    suspend fun getEpisodes(
        seriesUrl: String,
        season: Int = 1,
        extensionId: String? = null
    ): Result<List<EpisodeItem>> {
        return episodesUseCase.execute(seriesUrl, season, extensionId)
    }

    /**
     * Main user flow: Server Discovery with canonical Search Order and prioritized fallback across managed candidates.
     */
    suspend fun discoverServers(
        title: String,
        year: String = "",
        isMovie: Boolean = true,
        season: Int = 1,
        episode: Int = 1,
        mediaId: String = "",
        altKeys: List<String> = emptyList(),
        contentType: ContentType? = null,
        originalTitle: String? = null,
        playbackAttempt: com.example.extension.managed.playback.PlaybackAttempt? = null,
        variantMode: String = "subtitled",
        audioLanguage: String = "ar"
    ): ManagedDiscoveryOutcome = withContext(Dispatchers.IO) {
        if (!isOnlineChecker()) {
            return@withContext ManagedDiscoveryOutcome.RecoverableFailure(
                error = ExtensionError.NoInternet()
            )
        }

        val effectiveAttempt = playbackAttempt ?: com.example.extension.managed.playback.PlaybackAttempt.current()
        val targetContentType = contentType ?: if (isMovie) ContentType.MOVIE else ContentType.SERIES
        val isDubbed = variantMode.equals("dubbed", ignoreCase = true)

        var allExtensions = registry.getAllExtensions()
        if (allExtensions.isEmpty()) {
            awaitRuntimeReady(timeoutMillis = 2500L, contentType = targetContentType)
            allExtensions = registry.getAllExtensions()
        }

        // 1. Fetch canonical Search Order for requested content type or dubbed group
        val searchOrder = registry.getSearchOrder()
        val searchOrderIds = if (isDubbed) {
            searchOrder.getDubbedOrder(com.example.domain.models.VariantLanguage.ARABIC)
        } else {
            searchOrderRepository.getOrderForContentType(targetContentType)
        }

        // 2. Filter candidates through canonical eligibility pipeline & hard content-type isolation
        val candidates = if (isDubbed) {
            val resolver = com.example.extension.managed.usecase.ManagedExtensionResolver(
                registry = registry,
                scraperRegistry = com.example.extension.managed.registry.ScraperRegistry.INSTANCE,
                currentAppVersionCode = runtime.currentAppVersionCode,
                supportedRuntimeApiVersion = runtime.supportedRuntimeApiVersion
            )
            // In dubbed mode: query ONLY extensions with verified Arabic Dubbed capability. Never fall back to subtitle scrapers!
            resolver.resolveEligibleDubbedExtensions(
                language = com.example.domain.models.VariantLanguage.ARABIC,
                contentType = targetContentType,
                capability = ScraperCapability.SERVER_DISCOVERY
            )
        } else {
            val eligibleSubtitledExtensions = allExtensions.filter {
                // In subtitled mode: exclude extensions that are dubbed-only
                !it.audioCapabilities.contains(com.example.domain.models.AudioCapability.ARABIC_DUB) ||
                it.audioCapabilities.contains(com.example.domain.models.AudioCapability.ORIGINAL_AUDIO)
            }
            val initialCandidates = if (searchOrderIds.isNotEmpty()) {
                eligibilityFilter.filterEligibleExtensions(
                    orderedExtensionIds = searchOrderIds,
                    availableExtensions = eligibleSubtitledExtensions,
                    targetContentType = targetContentType,
                    capability = ScraperCapability.SERVER_DISCOVERY,
                    currentAppVersionCode = runtime.currentAppVersionCode,
                    supportedRuntimeApiVersion = runtime.supportedRuntimeApiVersion
                )
            } else emptyList()

            if (initialCandidates.isNotEmpty()) {
                initialCandidates
            } else {
                fallbackManager.filterAndSortCandidates(
                    candidates = eligibleSubtitledExtensions,
                    contentType = targetContentType,
                    capability = ScraperCapability.SERVER_DISCOVERY,
                    appVersionCode = runtime.currentAppVersionCode,
                    supportedRuntimeApi = runtime.supportedRuntimeApiVersion
                )
            }
        }

        // Phase 05Q.5-C: Exclude any candidate that was cancelled in the current PlaybackAttempt
        val activeCandidates = if (effectiveAttempt != null && effectiveAttempt.hasCancelledCandidates) {
            candidates.filter { candidate ->
                val isCancelled = effectiveAttempt.isCandidateCancelled(candidate.id) ||
                                  effectiveAttempt.isCandidateCancelled(candidate.scraperKey)
                if (isCancelled) {
                    com.example.extension.managed.trace.Phase05GLogger.log(
                        "DISCOVERY",
                        mediaId,
                        "CANDIDATE_SKIPPED_CANCELLED_IN_ATTEMPT: extensionId=${candidate.id}"
                    )
                    false
                } else {
                    true
                }
            }
        } else {
            candidates
        }

        if (activeCandidates.isEmpty()) {
            return@withContext ManagedDiscoveryOutcome.RecoverableFailure(
                error = ExtensionError.MediaNotFound("No uncancelled candidate managed extension available for $targetContentType")
            )
        }

        val mediaKey = com.example.ui.screens.player.buildMediaKey(
            title = title,
            isMovie = isMovie,
            season = season,
            episode = episode,
            variantMode = variantMode,
            audioLang = audioLanguage
        )
        val cleanTitle = title.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val cleanTitleWithYear = if (year.isNotBlank() && year != "0") "$cleanTitle $year" else cleanTitle

        val cleanOriginalTitle = originalTitle?.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")?.replace(Regex("\\s+"), " ")?.trim()
        val cleanOriginalTitleWithYear = if (!cleanOriginalTitle.isNullOrBlank() && year.isNotBlank() && year != "0") "$cleanOriginalTitle $year" else cleanOriginalTitle

        val primaryTitle = cleanTitle.split(":", "-", " Season", " الموسم", " Part", " الجزء").firstOrNull()?.trim()
        val primaryOriginalTitle = cleanOriginalTitle?.split(":", "-", " Season", " الموسم", " Part", " الجزء")?.firstOrNull()?.trim()

        var lastRecoverableError: ExtensionError? = null

        // 2. Iterate through prioritized managed candidates
        for (candidate in activeCandidates) {
            if (effectiveAttempt?.isCandidateCancelled(candidate.id) == true || effectiveAttempt?.isCandidateCancelled(candidate.scraperKey) == true) {
                continue
            }
            try {
                // A. Search for title on candidate extension (try dubbed suffix first if in dubbed mode)
                var matchedItem = if (isDubbed && !cleanTitle.contains("مدبلج")) {
                    runtime.search(
                        listOf(candidate),
                        SearchRequest(query = "$cleanTitle مدبلج", contentType = targetContentType)
                    ).getOrNull()?.items?.firstOrNull()
                } else null
                    ?: runtime.search(
                        listOf(candidate),
                        SearchRequest(query = cleanTitle, contentType = targetContentType)
                    ).getOrNull()?.items?.firstOrNull()
                    ?: runtime.search(
                        listOf(candidate),
                        SearchRequest(query = cleanTitleWithYear, contentType = targetContentType)
                    ).getOrNull()?.items?.firstOrNull()
                    ?: if (!primaryTitle.isNullOrBlank() && primaryTitle != cleanTitle && primaryTitle.length >= 3) {
                        runtime.search(
                            listOf(candidate),
                            SearchRequest(query = primaryTitle, contentType = targetContentType)
                        ).getOrNull()?.items?.firstOrNull()
                    } else null
                    ?: if (!cleanOriginalTitle.isNullOrBlank() && cleanOriginalTitle != cleanTitle) {
                        runtime.search(
                            listOf(candidate),
                            SearchRequest(query = cleanOriginalTitle, contentType = targetContentType)
                        ).getOrNull()?.items?.firstOrNull()
                            ?: if (!cleanOriginalTitleWithYear.isNullOrBlank()) {
                                runtime.search(
                                    listOf(candidate),
                                    SearchRequest(query = cleanOriginalTitleWithYear, contentType = targetContentType)
                                ).getOrNull()?.items?.firstOrNull()
                            } else null
                            ?: if (!primaryOriginalTitle.isNullOrBlank() && primaryOriginalTitle != cleanOriginalTitle && primaryOriginalTitle.length >= 3) {
                                runtime.search(
                                    listOf(candidate),
                                    SearchRequest(query = primaryOriginalTitle, contentType = targetContentType)
                                ).getOrNull()?.items?.firstOrNull()
                            } else null
                    } else null

                if (matchedItem == null) {
                    if (candidate.scraperKey == "qfilm" || candidate.id == "qfilm") {
                        try { Log.w("QFILM_DIAG", "[QFILM_DIAG] 08_SEARCH_NO_MATCH candidate=${candidate.name}, cleanTitle='$cleanTitle'") } catch (_: Throwable) {}
                    }
                    lastRecoverableError = ExtensionError.MediaNotFound("Media $cleanTitle not found on ${candidate.name}")
                    continue
                }

                if (candidate.scraperKey == "qfilm" || candidate.id == "qfilm") {
                    try { Log.i("QFILM_DIAG", "[QFILM_DIAG] 08_SEARCH_RESULT_SELECTED candidate=${candidate.name}, title='${matchedItem.title}', url='${matchedItem.url}'") } catch (_: Throwable) {}
                }

                // B. For series, resolve target episode page URL
                val targetUrl = if (isMovie) {
                    matchedItem.url
                } else {
                    val epResult = runtime.getEpisodes(candidate, matchedItem.url, season)
                    val epList = epResult.getOrNull() ?: emptyList()
                    epList.firstOrNull { it.episodeNumber == episode }?.url ?: matchedItem.url
                }

                // C. Discover servers
                val discoveryRequest = ServerDiscoveryRequest(
                    targetUrl = targetUrl,
                    mediaTitle = title,
                    isMovie = isMovie,
                    season = season,
                    episode = episode
                )
                val discoveryResult = runtime.discoverServers(listOf(candidate), discoveryRequest)

                if (discoveryResult.isSuccess) {
                    val result = discoveryResult.getOrThrow()
                    val serverItems = result.servers

                    if (serverItems.isNotEmpty()) {
                        // Bridge normalized results into ServerStateStore for Player/Downloader
                        val serverNames = mutableListOf<String>()
                        val serverLinks = mutableMapOf<String, String>()
                        val serverIds = mutableMapOf<String, String>()
                        val downloadLinks = mutableMapOf<String, String>()

                        for (item in serverItems) {
                            if (item.name.contains("(تحميل)") || item.link.endsWith(".mp4") || item.link.endsWith(".mkv")) {
                                downloadLinks[item.name] = item.link
                            } else {
                                serverNames.add(item.name)
                                serverLinks[item.name] = item.link
                                serverIds[item.name] = item.id
                            }
                        }

                        // Also extract direct stream if available on first server
                        val firstServer = serverItems.first()
                        val directPlayback = if (firstServer.isDirectStream) {
                            PlaybackSource(
                                streamUrl = firstServer.link,
                                mimeType = if (firstServer.link.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"
                            )
                        } else null

                        ServerStateStore.saveForMedia(
                            mediaKey = mediaKey,
                            servers = serverNames,
                            links = serverLinks,
                            ids = serverIds,
                            downloads = downloadLinks,
                            website = candidate.name,
                            playbackPageUrl = targetUrl,
                            scraperKey = candidate.scraperKey,
                            altKeys = listOf(mediaId) + altKeys
                        )

                        return@withContext ManagedDiscoveryOutcome.Success(
                            servers = serverItems,
                            website = candidate.name,
                            sourceUrl = targetUrl,
                            directStream = directPlayback
                        )
                    }
                } else {
                    val exception = discoveryResult.exceptionOrNull()
                    val error = (exception as? Exception)?.let { ExtensionError.ExtractionFailed(it.message ?: "Unknown", it) }
                        ?: ExtensionError.MediaNotFound("No servers found")

                    if (!fallbackManager.isRecoverable(error)) {
                        return@withContext ManagedDiscoveryOutcome.SecurityFailure(error)
                    }
                    lastRecoverableError = error
                }
            } catch (e: Exception) {
                val error = ExtensionError.ExtractionFailed("Discovery exception: ${e.message}", e)
                if (!fallbackManager.isRecoverable(error)) {
                    return@withContext ManagedDiscoveryOutcome.SecurityFailure(error)
                }
                lastRecoverableError = error
            }
        }

        // 3. All managed candidates failed with recoverable errors
        val finalError = lastRecoverableError ?: ExtensionError.MediaNotFound("No servers found across managed candidates")
        return@withContext ManagedDiscoveryOutcome.RecoverableFailure(
            error = finalError
        )
    }

    /**
     * Extracts normalized playback source for consumption by media players.
     */
    suspend fun extractPlaybackSource(
        serverItem: ServerItem,
        mediaTitle: String = ""
    ): Result<PlaybackSource> = withContext(Dispatchers.IO) {
        val request = ExtractionRequest(serverItem = serverItem, mediaTitle = mediaTitle)
        val result = runtime.extractStream(request)
        if (result.isSuccess) {
            val extraction = result.getOrThrow()
            if (extraction.playbackSource != null) {
                Result.success(extraction.playbackSource)
            } else {
                Result.failure(Exception("No playback source extracted"))
            }
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Extraction failed"))
        }
    }

    /**
     * Extracts normalized download task for consumption by download managers.
     * Guarantees minimal header projection and zero session cookie leakage.
     */
    suspend fun extractDownloadTask(
        serverItem: ServerItem,
        mediaTitle: String = ""
    ): Result<DownloadTaskRequest> = withContext(Dispatchers.IO) {
        val request = ExtractionRequest(serverItem = serverItem, mediaTitle = mediaTitle)
        val result = runtime.extractStream(request)
        if (result.isSuccess) {
            val extraction = result.getOrThrow()
            val task = extraction.downloadTask ?: extraction.playbackSource?.let { src ->
                DownloadTaskRequest(
                    id = serverItem.id,
                    title = mediaTitle,
                    downloadUrl = src.streamUrl,
                    headers = src.headers,
                    mimeType = src.mimeType
                )
            }
            if (task != null) {
                Result.success(task)
            } else {
                Result.failure(Exception("No download task available"))
            }
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Download extraction failed"))
        }
    }
}
