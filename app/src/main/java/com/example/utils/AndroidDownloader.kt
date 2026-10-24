package com.example.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import com.example.R
import com.example.extension.managed.adapter.DownloaderInput
import com.example.extension.managed.adapter.UnifiedDownloadCoordinator
import com.example.extension.managed.model.DownloadSource

object AndroidDownloader {

    /**
     * Backward-compatible helper method. Delegates to UnifiedDownloadCoordinator.
     */
    fun downloadVideo(context: Context, url: String, id: String, title: String): Long {
        val cached = com.example.ui.screens.player.ServerStateStore.getCachedData(id, id, id)
        val knownHeaders = cached?.extractedQualities?.find { it.url == url }?.headers
            ?: (cached?.playbackPageUrl?.let { mapOf("Referer" to it) } ?: emptyMap())
        val source = DownloadSource(
            url = url,
            filename = id,
            title = title,
            mediaId = id.substringBefore("_"),
            episodeId = if (id.contains("_")) id.substringAfter("_") else null,
            headers = knownHeaders,
            sourceUrl = cached?.playbackPageUrl
        )
        return UnifiedDownloadCoordinator.download(context, source)
    }

    /**
     * Dispatches sanitized DownloaderInput to StreamDownloaderService.
     */
    fun enqueue(context: Context, input: DownloaderInput): Long {
        if (!com.example.data.repository.UserSecurityManager.canDownload()) {
            Toast.makeText(context, context.getString(R.string.download_disabled_for_account), Toast.LENGTH_LONG).show()
            return 0L
        }
        return try {
            val intent = Intent(context, StreamDownloaderService::class.java).apply {
                putExtra("url", input.downloadUrl)
                putExtra("title", input.mediaTitle)
                putExtra("id", input.taskId)
                putExtra("variant_mode", input.variantMode)
                putExtra("audio_language", input.audioLanguage)
                putExtra("is_movie", input.isMovie)
                putExtra("season_number", input.seasonNumber)
                putExtra("episode_number", input.episodeNumber)
                if (input.mediaId != null) {
                    putExtra("media_id", input.mediaId)
                }
                if (input.quality != null) {
                    putExtra("quality", input.quality)
                }
                if (input.sourceUrl != null) {
                    putExtra("source_url", input.sourceUrl)
                }
                if (input.headers.isNotEmpty()) {
                    putExtra("headers", HashMap(input.headers))
                }
                if (input.fallbackCandidates.isNotEmpty()) {
                    val fallbackUrls = ArrayList(input.fallbackCandidates.map { it.streamUrl })
                    putStringArrayListExtra("fallback_urls", fallbackUrls)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Toast.makeText(context, context.getString(R.string.download_started_check_notifications), Toast.LENGTH_SHORT).show()
            System.currentTimeMillis()
        } catch (e: Exception) {
            e.printStackTrace()
            0L
        }
    }
}
