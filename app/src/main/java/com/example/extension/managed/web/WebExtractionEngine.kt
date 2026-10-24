package com.example.extension.managed.web

import com.example.extension.managed.model.ExtractionResult
import com.example.extension.managed.model.ServerItem

interface WebExtractionEngine {
    suspend fun extractServers(
        targetUrl: String,
        script: String,
        timeoutMs: Long,
        expectedOrigin: String
    ): Result<List<ServerItem>>

    suspend fun extractStreamUrl(
        targetUrl: String,
        targetServerId: String?,
        script: String,
        timeoutMs: Long,
        expectedOrigin: String
    ): Result<String>

    suspend fun extractMedia(
        targetUrl: String,
        targetServerId: String? = null,
        script: String = "",
        timeoutMs: Long = 15_000L,
        expectedOrigin: String = ""
    ): Result<ExtractionResult> {
        val streamResult = extractStreamUrl(targetUrl, targetServerId, script, timeoutMs, expectedOrigin)
        return if (streamResult.isSuccess) {
            val streamUrl = streamResult.getOrThrow()
            val headers = if (expectedOrigin.isNotBlank()) mapOf("Referer" to expectedOrigin) else emptyMap()
            Result.success(MediaStreamDetector.createExtractionResult(streamUrl, headers))
        } else {
            Result.failure(streamResult.exceptionOrNull() ?: Exception("Extraction failed"))
        }
    }

    fun cancel()
}
