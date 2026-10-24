package com.example.extension.managed.usecase

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.ScraperCapability
import com.example.extension.managed.model.SearchRequest
import com.example.extension.managed.model.ServerDiscoveryRequest
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.runtime.FallbackManager
import com.example.extension.orchestrator.ManagedDiscoveryOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case executing server discovery through the Managed Extension pipeline.
 *
 * Flow:
 * Episode / Movie Details
 *   -> Network Check
 *   -> ManagedExtensionResolver (resolves candidates by priority: 100 > 50 > 10)
 *   -> ControlledManagedExtensionRuntime
 *   -> Bundled Scraper execution
 *   -> SafeScraperBridge (SERVER_LIST message)
 *   -> Normalized ServerItem list
 *   -> ServerStateStore population for Player & Downloader
 *   -> ManagedDiscoveryOutcome
 *
 * Excludes DISABLED, MAINTENANCE, and DEPRECATED extensions.
 * Evaluates candidate failures with FallbackManager.
 */
class DiscoverManagedServersUseCase(
    private val resolver: ManagedExtensionResolver,
    private val runtime: ControlledManagedExtensionRuntime,
    private val fallbackManager: FallbackManager,
    private val isOnlineChecker: () -> Boolean = { true }
) {

    suspend fun execute(
        title: String,
        year: String = "",
        isMovie: Boolean = true,
        season: Int = 1,
        episode: Int = 1,
        mediaId: String = "",
        altKeys: List<String> = emptyList(),
        contentType: ContentType? = null,
        variantMode: String = "subtitled",
        audioLanguage: String = "ar"
    ): ManagedDiscoveryOutcome = withContext(Dispatchers.IO) {
        if (!isOnlineChecker()) {
            return@withContext ManagedDiscoveryOutcome.RecoverableFailure(
                error = ExtensionError.NoInternet()
            )
        }

        val targetContentType = contentType ?: if (isMovie) ContentType.MOVIE else ContentType.SERIES
        val isDubbed = variantMode.equals("dubbed", ignoreCase = true)

        // 1. Resolve candidates sorted by priority descending (HIGHER NUMBER = HIGHER PRIORITY)
        val candidates = if (isDubbed) {
            resolver.resolveEligibleDubbedExtensions(
                language = com.example.domain.models.VariantLanguage.fromString(audioLanguage),
                capability = ScraperCapability.SERVER_DISCOVERY,
                contentType = targetContentType
            )
        } else {
            resolver.resolveEligibleExtensions(
                capability = ScraperCapability.SERVER_DISCOVERY,
                contentType = targetContentType,
                allowDeprecated = false
            )
        }

        if (candidates.isEmpty()) {
            return@withContext ManagedDiscoveryOutcome.RecoverableFailure(
                error = ExtensionError.MediaNotFound(
                    if (isDubbed) "النسخة المدبلجة غير متوفرة لهذا العمل" else "No eligible managed extensions available for $targetContentType"
                )
            )
        }

        val mediaKey = "$title-$isMovie-$season-$episode"
        val cleanTitle = title.replace(Regex("[^\\p{L}\\p{N}\\s]"), " ").replace(Regex("\\s+"), " ").trim()
        val cleanTitleWithYear = if (year.isNotBlank() && year != "0") "$cleanTitle $year" else cleanTitle

        var lastRecoverableError: ExtensionError? = null

        // 2. Iterate through prioritized managed candidates
        for (candidate in candidates) {
            try {
                // A. Search for title on candidate extension
                val searchResult = runtime.search(
                    listOf(candidate),
                    SearchRequest(query = cleanTitle, contentType = targetContentType)
                )

                val matchedItem = searchResult.getOrNull()?.items?.firstOrNull()
                    ?: runtime.search(
                        listOf(candidate),
                        SearchRequest(query = cleanTitleWithYear, contentType = targetContentType)
                    ).getOrNull()?.items?.firstOrNull()

                if (matchedItem == null) {
                    lastRecoverableError = ExtensionError.MediaNotFound("Media '$cleanTitle' not found on ${candidate.name}")
                    continue
                }

                // B. For series, resolve target episode page URL
                val targetUrl = if (isMovie) {
                    matchedItem.url
                } else {
                    val epResult = runtime.getEpisodes(candidate, matchedItem.url, season)
                    val epList = epResult.getOrNull() ?: emptyList()
                    epList.firstOrNull { it.episodeNumber == episode }?.url ?: matchedItem.url
                }

                // C. Discover servers via Controlled Runtime
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
                        val firstServer = serverItems.first()
                        val directPlayback = if (firstServer.isDirectStream) {
                            PlaybackSource(
                                streamUrl = firstServer.link,
                                mimeType = if (firstServer.link.contains(".m3u8")) "application/x-mpegURL" else "video/mp4"
                            )
                        } else null

                        return@withContext ManagedDiscoveryOutcome.Success(
                            servers = serverItems,
                            website = candidate.name,
                            sourceUrl = targetUrl,
                            directStream = directPlayback
                        )
                    }
                } else {
                    val exception = discoveryResult.exceptionOrNull()
                    val error = (exception as? Exception)?.let { ExtensionError.ExtractionFailed(it.message ?: "Server discovery failed", it) }
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

        // All candidates failed with recoverable error
        val finalError = lastRecoverableError ?: ExtensionError.MediaNotFound("No servers found across eligible managed candidates")
        ManagedDiscoveryOutcome.RecoverableFailure(error = finalError)
    }
}
