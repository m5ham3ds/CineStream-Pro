package com.example.extension.managed.usecase

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.PlaybackSource
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.runtime.CredentialProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case extracting a normalized PlaybackSource from a selected ServerItem.
 *
 * Flow:
 * User Server Selection
 *   -> ControlledManagedExtensionRuntime.extractStream
 *   -> SafeScraperBridge (EXTRACTION_RESULT)
 *   -> Normalized ExtractionResult
 *   -> CredentialProjector (minimal stream headers, zero cookie leak)
 *   -> Normalized PlaybackSource
 *   -> Handoff to Media Player
 *
 * The media player never sees scraper internals, JavaScript tokens, or WebView references.
 */
class ExtractPlaybackSourceUseCase(
    private val runtime: ControlledManagedExtensionRuntime
) {

    suspend fun execute(
        serverItem: ServerItem,
        mediaTitle: String = ""
    ): Result<PlaybackSource> = withContext(Dispatchers.IO) {
        val request = ExtractionRequest(
            serverItem = serverItem,
            mediaTitle = mediaTitle
        )

        val result = runtime.extractStream(request)
        if (result.isSuccess) {
            val extraction = result.getOrThrow()
            val rawSource = extraction.playbackSource
            if (rawSource != null) {
                // Apply minimal necessary credential projection to headers
                val projectedHeaders = CredentialProjector.projectHeaders(
                    sessionHeaders = rawSource.headers,
                    sessionCookies = emptyMap()
                )
                val safeSource = rawSource.copy(headers = projectedHeaders)
                Result.success(safeSource)
            } else {
                Result.failure(Exception("No playback source extracted from server '${serverItem.name}'"))
            }
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Extraction failed for server '${serverItem.name}'"))
        }
    }
}
