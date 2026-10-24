package com.example.extension.managed.runtime

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.ExtensionLifecycleStatus
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.ManagedExtension
import com.example.extension.managed.model.ManagedExtensionValidator
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.SearchResult
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerDiscoveryResult
import com.example.extension.managed.registry.ManagedExtensionRegistry
import com.example.extension.managed.registry.ScraperRegistry
import com.example.extension.managed.runtime.context.ExtensionContext
import com.example.extension.managed.runtime.network.ConnectivityMonitor
import com.example.extension.managed.runtime.network.DefaultConnectivityMonitor
import com.example.extension.managed.web.WebExtractionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standard implementation of the Controlled Managed Extension Runtime.
 * Coordinates validation, compatibility gates, priority fallback, session management,
 * and normalized result output without UI or legacy dependencies.
 */
class DefaultControlledManagedExtensionRuntime(
    private val scraperRegistry: ScraperRegistry = ScraperRegistry.INSTANCE,
    private val fallbackManager: FallbackManager = FallbackManager(scraperRegistry),
    private val webEngineProvider: (() -> WebExtractionEngine)? = null,
    private val managedExtensionRegistry: ManagedExtensionRegistry = ManagedExtensionRegistry.INSTANCE,
    private val connectivityMonitor: ConnectivityMonitor = DefaultConnectivityMonitor(),
    override val currentAppVersionCode: Long = 1L,
    override val supportedRuntimeApiVersion: Int = 1
) : ControlledManagedExtensionRuntime {

    override suspend fun search(
        request: SearchRequest
    ): Result<SearchResult> = search(managedExtensionRegistry.getAllExtensions(), request)

    override suspend fun discoverServers(
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult> = discoverServers(managedExtensionRegistry.getAllExtensions(), request)

    override suspend fun extractStream(
        request: ExtractionRequest
    ): Result<ExtractionResult> = extractStream(managedExtensionRegistry.getAllExtensions(), request)

    override suspend fun search(
        extensions: List<ManagedExtension>,
        request: SearchRequest
    ): Result<SearchResult> = withContext(Dispatchers.IO) {
        if (!connectivityMonitor.isConnected()) {
            return@withContext Result.failure(
                ExtensionError.NoInternet().let { Exception(it.message, it) }
            )
        }

        val targetContentType = request.contentType ?: ContentType.MOVIE
        val candidates = fallbackManager.filterAndSortCandidates(
            candidates = extensions,
            contentType = targetContentType,
            capability = ScraperCapability.SEARCH,
            appVersionCode = currentAppVersionCode,
            supportedRuntimeApi = supportedRuntimeApiVersion
        )

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                Exception(ExtensionError.ExtractionFailed("No compatible candidate extensions available for search").message)
            )
        }

        var lastError: Throwable? = null

        for (candidate in candidates) {
            val scraper = scraperRegistry.getScraper(candidate.scraperKey) ?: continue
            val result = scraper.search(candidate, request)
            if (result.isSuccess) {
                return@withContext result
            }

            val error = result.exceptionOrNull()
            lastError = error
            val isRecoverable = when (error) {
                is ExtensionError -> fallbackManager.isRecoverable(error)
                else -> !((error?.message?.contains("NO_INTERNET", ignoreCase = true) == true) ||
                        (error?.message?.contains("No internet", ignoreCase = true) == true))
            }
            if (!isRecoverable) {
                return@withContext Result.failure(error ?: Exception("Unrecoverable error"))
            }
        }

        Result.failure(lastError ?: Exception(ExtensionError.ExtractionFailed("All candidate extensions failed for search").message))
    }

    override suspend fun getDetails(
        extension: ManagedExtension,
        url: String
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        val validation = validateAndCheckCompatibility(extension, ScraperCapability.DETAILS, null)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull()!!)
        }

        val scraper = scraperRegistry.getScraper(extension.scraperKey)
            ?: return@withContext Result.failure(Exception(ExtensionError.MissingBundledScraper(extension.scraperKey).message))

        scraper.getDetails(extension, url)
    }

    override suspend fun getEpisodes(
        extension: ManagedExtension,
        seriesUrl: String,
        season: Int
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        val validation = validateAndCheckCompatibility(extension, ScraperCapability.EPISODES, ContentType.SERIES)
        if (validation.isFailure) {
            return@withContext Result.failure(validation.exceptionOrNull()!!)
        }

        val scraper = scraperRegistry.getScraper(extension.scraperKey)
            ?: return@withContext Result.failure(Exception(ExtensionError.MissingBundledScraper(extension.scraperKey).message))

        scraper.getEpisodes(extension, seriesUrl, season)
    }

    override suspend fun discoverServers(
        extensions: List<ManagedExtension>,
        request: ServerDiscoveryRequest
    ): Result<ServerDiscoveryResult> = withContext(Dispatchers.IO) {
        if (!connectivityMonitor.isConnected()) {
            return@withContext Result.failure(
                ExtensionError.NoInternet().let { Exception(it.message, it) }
            )
        }

        val targetContentType = request.contentType ?: if (request.isMovie) ContentType.MOVIE else ContentType.SERIES
        val candidates = fallbackManager.filterAndSortCandidates(
            candidates = extensions,
            contentType = targetContentType,
            capability = ScraperCapability.SERVER_DISCOVERY,
            appVersionCode = currentAppVersionCode,
            supportedRuntimeApi = supportedRuntimeApiVersion
        )

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                Exception(ExtensionError.ExtractionFailed("No compatible candidate extensions available for server discovery").message)
            )
        }

        var lastError: Throwable? = null

        for (candidate in candidates) {
            val scraper = scraperRegistry.getScraper(candidate.scraperKey) ?: continue
            val session = ExtractionSession(
                extensionId = candidate.id,
                scraperKey = candidate.scraperKey,
                targetPageUrl = request.targetUrl
            )

            val webEngine = webEngineProvider?.invoke()
            val context = ExtensionContext.from(
                extension = candidate,
                session = session,
                connectivity = connectivityMonitor,
                webEngine = webEngine
            )
            val result = scraper.discoverServers(context, request)
            if (result.isSuccess) {
                return@withContext result
            }

            val error = result.exceptionOrNull()
            lastError = error
            val isRecoverable = when (error) {
                is ExtensionError -> fallbackManager.isRecoverable(error)
                else -> !((error?.message?.contains("NO_INTERNET", ignoreCase = true) == true) ||
                        (error?.message?.contains("No internet", ignoreCase = true) == true))
            }
            if (!isRecoverable) {
                return@withContext Result.failure(error ?: Exception("Unrecoverable error"))
            }
        }

        Result.failure(lastError ?: Exception(ExtensionError.ExtractionFailed("All candidate extensions failed for server discovery").message))
    }

    override suspend fun extractStream(
        extensions: List<ManagedExtension>,
        request: ExtractionRequest
    ): Result<ExtractionResult> = withContext(Dispatchers.IO) {
        if (!connectivityMonitor.isConnected()) {
            return@withContext Result.failure(
                ExtensionError.NoInternet().let { Exception(it.message, it) }
            )
        }

        val candidates = extensions.filter { ext ->
            ManagedExtensionValidator.validate(ext) is ManagedExtensionValidator.ValidationResult.Valid &&
                    ext.status == ExtensionLifecycleStatus.ACTIVE &&
                    ext.userEnabled &&
                    currentAppVersionCode >= ext.minAppVersionCode &&
                    ext.runtimeApiVersion <= supportedRuntimeApiVersion
        }.sortedByDescending { it.priority }

        if (candidates.isEmpty()) {
            return@withContext Result.failure(
                Exception(ExtensionError.ExtractionFailed("No candidate extensions available for stream extraction").message)
            )
        }

        var lastError: Throwable? = null

        for (candidate in candidates) {
            val scraper = scraperRegistry.getScraper(candidate.scraperKey) ?: continue
            val session = ExtractionSession(
                extensionId = candidate.id,
                scraperKey = candidate.scraperKey,
                targetPageUrl = request.serverItem.link
            )

            val webEngine = webEngineProvider?.invoke()
            val context = ExtensionContext.from(
                extension = candidate,
                session = session,
                connectivity = connectivityMonitor,
                webEngine = webEngine
            )
            val result = scraper.extractStream(context, request)
            if (result.isSuccess) {
                return@withContext result
            }

            val error = result.exceptionOrNull()
            lastError = error
            val isRecoverable = when (error) {
                is ExtensionError -> fallbackManager.isRecoverable(error)
                else -> !((error?.message?.contains("NO_INTERNET", ignoreCase = true) == true) ||
                        (error?.message?.contains("No internet", ignoreCase = true) == true))
            }
            if (!isRecoverable) {
                return@withContext Result.failure(error ?: Exception("Unrecoverable error"))
            }
        }

        Result.failure(lastError ?: Exception(ExtensionError.ExtractionFailed("All candidate extensions failed for stream extraction").message))
    }

    private fun validateAndCheckCompatibility(
        extension: ManagedExtension,
        requiredCapability: ScraperCapability,
        requiredContentType: ContentType?
    ): Result<Unit> {
        val validation = ManagedExtensionValidator.validate(extension)
        if (validation is ManagedExtensionValidator.ValidationResult.Invalid) {
            return Result.failure(Exception(validation.error.message))
        }

        if (extension.status == ExtensionLifecycleStatus.DISABLED) {
            return Result.failure(Exception(ExtensionError.SourceDisabled(extension.id).message))
        }

        if (extension.status == ExtensionLifecycleStatus.MAINTENANCE) {
            return Result.failure(Exception(ExtensionError.MaintenanceHold(extension.id).message))
        }

        if (!extension.userEnabled) {
            return Result.failure(Exception(ExtensionError.UserDisabled(extension.id).message))
        }

        if (currentAppVersionCode < extension.minAppVersionCode) {
            return Result.failure(
                Exception(ExtensionError.IncompatibleAppVersion(currentAppVersionCode, extension.minAppVersionCode).message)
            )
        }

        if (extension.runtimeApiVersion > supportedRuntimeApiVersion) {
            return Result.failure(
                Exception(ExtensionError.IncompatibleRuntime(supportedRuntimeApiVersion, extension.runtimeApiVersion).message)
            )
        }

        val scraper = scraperRegistry.getScraper(extension.scraperKey)
            ?: return Result.failure(Exception(ExtensionError.UnknownScraperKey(extension.scraperKey).message))

        if (!scraper.supportedCapabilities.contains(requiredCapability)) {
            return Result.failure(Exception(ExtensionError.CapabilityUnsupported(requiredCapability).message))
        }

        if (requiredContentType != null) {
            if (!extension.contentTypes.contains(requiredContentType)) {
                return Result.failure(Exception(ExtensionError.ContentTypeUnsupported(requiredContentType).message))
            }
            if (!scraper.supportedContentTypes.contains(requiredContentType)) {
                return Result.failure(Exception(ExtensionError.ContentTypeUnsupported(requiredContentType).message))
            }
        }

        return Result.success(Unit)
    }
}
