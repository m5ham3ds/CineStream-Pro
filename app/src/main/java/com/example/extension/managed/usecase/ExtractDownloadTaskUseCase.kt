package com.example.extension.managed.usecase

import com.example.extension.managed.contract.ControlledManagedExtensionRuntime
import com.example.extension.managed.model.DownloadTaskRequest
import com.example.extension.managed.model.ExtractionRequest
import com.example.extension.managed.model.ServerItem
import com.example.extension.managed.runtime.CredentialProjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Use case extracting a normalized DownloadTaskRequest from a ServerItem.
 *
 * Flow:
 * ServerItem
 *   -> ControlledManagedExtensionRuntime.extractStream
 *   -> Normalized ExtractionResult
 *   -> CredentialProjector (minimal necessary stream headers, strictly ZERO session cookies)
 *   -> Normalized DownloadTaskRequest
 *   -> Downloader handoff
 *
 * Guarantees zero credential and cookie leakage to disk storage or download services.
 */
class ExtractDownloadTaskUseCase(
    private val runtime: ControlledManagedExtensionRuntime
) {

    suspend fun execute(
        serverItem: ServerItem,
        mediaTitle: String = ""
    ): Result<DownloadTaskRequest> = withContext(Dispatchers.IO) {
        val request = ExtractionRequest(
            serverItem = serverItem,
            mediaTitle = mediaTitle
        )

        val result = runtime.extractStream(request)
        if (result.isSuccess) {
            val extraction = result.getOrThrow()
            val rawTask = extraction.downloadTask ?: extraction.playbackSource?.let { src ->
                DownloadTaskRequest(
                    id = serverItem.id,
                    title = mediaTitle,
                    downloadUrl = src.streamUrl,
                    headers = src.headers,
                    mimeType = src.mimeType
                )
            }

            if (rawTask != null) {
                // Ensure minimal credential projection without session cookies
                val safeHeaders = CredentialProjector.projectHeaders(
                    sessionHeaders = rawTask.headers,
                    sessionCookies = emptyMap() // Strictly zero cookies projected
                )
                val sanitizedTask = rawTask.copy(headers = safeHeaders)
                Result.success(sanitizedTask)
            } else {
                Result.failure(Exception("No download task available for server '${serverItem.name}'"))
            }
        } else {
            Result.failure(result.exceptionOrNull() ?: Exception("Download extraction failed for server '${serverItem.name}'"))
        }
    }
}
