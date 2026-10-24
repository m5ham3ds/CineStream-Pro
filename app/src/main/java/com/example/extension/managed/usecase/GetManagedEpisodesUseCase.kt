package com.example.extension.managed.usecase

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ContentType
import com.example.extension.managed.model.EpisodeItem
import com.example.extension.managed.model.ScraperCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case retrieving normalized series episodes through the Managed Extension pipeline.
 *
 * Flow:
 * Series Details URL + Season Number
 *   -> Network Check
 *   -> ManagedExtensionResolver (resolves series-capable active extension)
 *   -> ControlledManagedExtensionRuntime
 *   -> Bundled Scraper
 *   -> Normalized List<EpisodeItem>
 *
 * Context Preservation:
 * Preserves episode context (id, title, episodeNumber, seasonNumber, url)
 * needed for downstream server discovery without exposing raw WebView state.
 */
class GetManagedEpisodesUseCase(
    private val resolver: ManagedExtensionResolver,
    private val runtime: ControlledManagedExtensionRuntime,
    private val isOnlineChecker: () -> Boolean = { true }
) {

    suspend fun execute(
        seriesUrl: String,
        season: Int = 1,
        extensionId: String? = null
    ): Result<List<EpisodeItem>> = withContext(Dispatchers.IO) {
        if (seriesUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Series URL must not be blank"))
        }

        if (!isOnlineChecker()) {
            return@withContext Result.failure(ExtensionError.NoInternet())
        }

        val targetExtension = if (extensionId != null) {
            resolver.resolveExtensionById(
                extensionId = extensionId,
                capability = ScraperCapability.EPISODES,
                contentType = ContentType.SERIES
            )
        } else {
            resolver.resolveEligibleExtensions(
                capability = ScraperCapability.EPISODES,
                contentType = ContentType.SERIES
            ).firstOrNull()
        }

        if (targetExtension == null) {
            return@withContext Result.failure(
                ExtensionError.ExtractionFailed("No eligible series-capable managed extension available")
            )
        }

        runtime.getEpisodes(targetExtension, seriesUrl, season)
    }
}
