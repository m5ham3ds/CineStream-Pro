package com.example.extension.managed.adapter

import com.example.extension.managed.model.DownloadSource
import com.example.extension.managed.model.DownloadTaskRequest
import com.example.extension.managed.model.QualityCandidate
import java.util.UUID

/**
 * Normalized downloader input model.
 * The download engine consumes only this model without knowing anything about scrapers or WebViews.
 */
data class DownloaderInput(
    val taskId: String,
    val mediaTitle: String,
    val downloadUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val quality: String? = null,
    val qualityKey: String? = null,
    val mediaId: String? = null,
    val episodeId: String? = null,
    val scraperKey: String? = null,
    val sourceUrl: String? = null,
    val fallbackCandidates: List<QualityCandidate> = emptyList(),
    val posterUrl: String? = null,
    val isMovie: Boolean = true,
    val variantMode: String = "subtitled",
    val audioLanguage: String = "ar",
    val seasonNumber: Int = 0,
    val episodeNumber: Int = 0,
    val providerId: String = ""
)

/**
 * Adapter converting normalized DownloadTaskRequest into DownloaderInput.
 * Enforces zero session cookie leakage to download services or local storage.
 */
object DownloaderHandoffAdapter {

    fun toDownloaderInput(taskRequest: DownloadTaskRequest): DownloaderInput {
        // Strip any accidental cookie headers as defense-in-depth
        val sanitizedHeaders = taskRequest.headers.filterNot { (key, _) ->
            key.equals("cookie", ignoreCase = true) || key.equals("set-cookie", ignoreCase = true)
        }

        return DownloaderInput(
            taskId = taskRequest.id,
            mediaTitle = taskRequest.title,
            downloadUrl = taskRequest.downloadUrl,
            headers = sanitizedHeaders,
            mimeType = taskRequest.mimeType
        )
    }

    fun toDownloaderInput(downloadSource: DownloadSource): DownloaderInput {
        val taskId = downloadSource.filename 
            ?: downloadSource.mediaId 
            ?: UUID.randomUUID().toString()
        val mediaTitle = downloadSource.title ?: "Video"
        return toDownloaderInput(downloadSource, taskId, mediaTitle)
    }

    fun toDownloaderInput(
        downloadSource: DownloadSource,
        taskId: String,
        mediaTitle: String
    ): DownloaderInput {
        val sanitizedHeaders = downloadSource.headers.filterNot { (key, _) ->
            key.equals("cookie", ignoreCase = true) || key.equals("set-cookie", ignoreCase = true)
        }.toMutableMap()

        // Ensure Referer is explicitly preserved from sourceUrl or metadata if not already in headers
        if (!sanitizedHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) {
            val ref = downloadSource.sourceUrl
                ?: downloadSource.metadata["sourceUrl"]
                ?: downloadSource.metadata["referer"]
            if (!ref.isNullOrBlank()) {
                sanitizedHeaders["Referer"] = ref
            }
        }

        // Ensure User-Agent is present
        if (!sanitizedHeaders.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
            val ua = downloadSource.metadata["userAgent"]
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            sanitizedHeaders["User-Agent"] = ua
        }

        val resolvedSourceUrl = downloadSource.sourceUrl 
            ?: sanitizedHeaders.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value

        val mode = downloadSource.variantMode.ifBlank { downloadSource.metadata["mode"] ?: "subtitled" }
        val lang = downloadSource.audioLanguage.ifBlank { downloadSource.metadata["language"] ?: "ar" }
        val season = downloadSource.metadata["season"]?.toIntOrNull() ?: 0
        val episode = downloadSource.metadata["episode"]?.toIntOrNull() ?: 0
        val provider = downloadSource.metadata["providerId"] ?: downloadSource.scraperKey ?: ""

        return DownloaderInput(
            taskId = taskId,
            mediaTitle = mediaTitle,
            downloadUrl = downloadSource.url,
            headers = sanitizedHeaders,
            mimeType = downloadSource.mimeType,
            quality = downloadSource.quality,
            qualityKey = downloadSource.qualityKey,
            mediaId = downloadSource.mediaId,
            episodeId = downloadSource.episodeId,
            scraperKey = downloadSource.scraperKey,
            sourceUrl = resolvedSourceUrl,
            fallbackCandidates = downloadSource.fallbackCandidates,
            posterUrl = downloadSource.metadata["posterUrl"],
            isMovie = downloadSource.metadata["isMovie"]?.toBooleanStrictOrNull() ?: (downloadSource.episodeId == null),
            variantMode = mode,
            audioLanguage = lang,
            seasonNumber = season,
            episodeNumber = episode,
            providerId = provider
        )
    }
}
