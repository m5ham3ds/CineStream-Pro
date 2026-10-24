package com.example.extension.managed.usecase

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.MediaDetailsResult
import com.example.extension.managed.model.ScraperCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case retrieving normalized media details through the Managed Extension pipeline.
 *
 * Flow:
 * Content URL
 *   -> Network Check
 *   -> ManagedExtensionResolver
 *   -> ControlledManagedExtensionRuntime
 *   -> Bundled Scraper
 *   -> Normalized MediaDetailsResult (id, title, description, posterUrl, bannerUrl, episodes, seasons)
 *
 * Strict isolation: The caller receives only normalized domain models.
 * Zero exposure of Firebase snapshots, raw HTML, or scraper internals.
 */
class GetManagedMediaDetailsUseCase(
    private val resolver: ManagedExtensionResolver,
    private val runtime: ControlledManagedExtensionRuntime,
    private val isOnlineChecker: () -> Boolean = { true }
) {

    suspend fun execute(
        contentUrl: String,
        extensionId: String? = null
    ): Result<MediaDetailsResult> = withContext(Dispatchers.IO) {
        if (contentUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Content URL must not be blank"))
        }

        if (!isOnlineChecker()) {
            return@withContext Result.failure(ExtensionError.NoInternet())
        }

        val targetExtension = if (extensionId != null) {
            resolver.resolveExtensionById(extensionId, capability = ScraperCapability.DETAILS)
        } else {
            resolver.resolveEligibleExtensions(capability = ScraperCapability.DETAILS).firstOrNull()
        }

        if (targetExtension == null) {
            return@withContext Result.failure(
                ExtensionError.ExtractionFailed("No eligible managed extension available to fetch details")
            )
        }

        runtime.getDetails(targetExtension, contentUrl)
    }
}
