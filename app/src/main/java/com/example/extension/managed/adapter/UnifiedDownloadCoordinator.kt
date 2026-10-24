package com.example.extension.managed.adapter

import android.content.Context
import com.example.R
import com.example.data.model.DownloadItem
import com.example.data.repository.DownloadRepository
import com.example.data.repository.UserSecurityManager
import com.example.extension.managed.model.AggregatedQuality
import com.example.extension.managed.model.DownloadSource
import com.example.extension.managed.model.QualityCandidate
import com.example.extension.managed.model.StreamProtocol
import com.example.extension.managed.model.normalizeQualityKey
import com.example.utils.AndroidDownloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * UnifiedDownloadCoordinator
 *
 * Canonical coordinator for all CineStream download operations across:
 * - InlineDetailVideoPlayer
 * - PlayerScreen
 * - DetailsScreens (Movie and Series/Anime Episode)
 * - BatchDownloadProcessor
 *
 * Enforces:
 * 1. Single entry point: download(context, source, ...)
 * 2. Canonical DownloadSource input model
 * 3. Handoff via DownloaderHandoffAdapter (cookie sanitization, input projection)
 * 4. DB registration via DownloadRepository
 * 5. Dispatch to StreamDownloaderService via AndroidDownloader
 * 6. Preservation of exact Referer/User-Agent headers to prevent CDN 403 / 0 bytes
 */
object UnifiedDownloadCoordinator {

    private val pendingDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var cachedCompletedCount: Int = 0
    @Volatile
    private var isObserverStarted: Boolean = false
    private val coordinatorScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun ensureObserver(context: Context) {
        if (isObserverStarted) return
        synchronized(this) {
            if (isObserverStarted) return
            isObserverStarted = true
            val appContext = context.applicationContext
            coordinatorScope.launch {
                try {
                    DownloadRepository(appContext).getDownloadItems().collect { items ->
                        cachedCompletedCount = items.count { it.isCompleted }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    fun isValidDownloadUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val uri = try { android.net.Uri.parse(url) } catch (_: Exception) { return false }
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        val host = uri.host?.lowercase()?.trim() ?: return false
        if (host.isEmpty()) return false
        if (com.example.extension.managed.web.TrustedEmbedHostPolicy.isForbiddenHost(host)) return false

        // Media stream or file format check
        return url.contains(".m3u8") ||
                url.contains(".mp4") ||
                url.contains(".mkv") ||
                url.contains(".webm") ||
                url.contains(".mpd") ||
                url.contains(".m4v") ||
                url.contains(".ts") ||
                url.contains("akamaized.net") ||
                url.contains("play.php") ||
                com.example.extension.managed.web.MediaStreamDetector.isMediaUrl(url)
    }

    fun clearPendingDownloads() {
        pendingDownloads.clear()
    }

    /**
     * Canonical entry point for initiating any download in CineStream.
     */
    fun download(
        context: Context,
        source: DownloadSource,
        scope: CoroutineScope? = null,
        onStarted: (() -> Unit)? = null
    ): Long {
        if (!UserSecurityManager.canDownload()) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.download_disabled_for_account),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return 0L
        }

        val qCandidate = source.quality ?: "Auto"
        if (qCandidate != "Auto" && !UserSecurityManager.restrictions.isQualityAllowed(qCandidate)) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.quality_restricted),
                android.widget.Toast.LENGTH_LONG
            ).show()
            return 0L
        }

        val limit = UserSecurityManager.restrictions.downloadLimit
        if (limit != null) {
            ensureObserver(context)
            if (cachedCompletedCount >= limit) {
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.download_limit_reached, limit),
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return 0L
            }
        }

        // Validate download URL (HTTPS/HTTP, valid host, media format)
        if (!isValidDownloadUrl(source.url)) {
            android.util.Log.e("UnifiedDownloadCoordinator", "Rejected invalid or unsafe download URL: ${source.url}")
            return 0L
        }

        // Handoff conversion through DownloaderHandoffAdapter (defense-in-depth header sanitization)
        val downloaderInput = DownloaderHandoffAdapter.toDownloaderInput(source)

        val fileId = downloaderInput.taskId
        // Multiple click protection: prevent duplicate download requests within debounce window
        if (!pendingDownloads.add(fileId)) {
            android.util.Log.w("UnifiedDownloadCoordinator", "Duplicate download click ignored for fileId=$fileId")
            return 0L
        }

        val mediaId = downloaderInput.mediaId ?: fileId
        val qLabel = downloaderInput.quality ?: source.quality ?: "HD"
        val downloadUrl = downloaderInput.downloadUrl
        val poster = downloaderInput.posterUrl ?: source.metadata["posterUrl"] ?: ""
        val isMovie = downloaderInput.isMovie

        // Persist DB state
        val downloadRepo = DownloadRepository(context.applicationContext)
        val downloadItem = DownloadItem(
            id = fileId,
            mediaId = mediaId,
            title = downloaderInput.mediaTitle,
            posterUrl = poster,
            isMovie = isMovie,
            quality = "$qLabel||$downloadUrl",
            progress = 0.05f,
            isCompleted = false,
            variantMode = downloaderInput.variantMode,
            audioLanguage = downloaderInput.audioLanguage,
            seasonNumber = downloaderInput.seasonNumber,
            episodeNumber = downloaderInput.episodeNumber,
            providerId = downloaderInput.providerId
        )

        val runner = scope ?: CoroutineScope(Dispatchers.IO)
        runner.launch(Dispatchers.IO) {
            try {
                downloadRepo.addToDownloads(downloadItem)
                AndroidDownloader.enqueue(context, downloaderInput)
                onStarted?.invoke()
            } finally {
                kotlinx.coroutines.delay(2500L)
                pendingDownloads.remove(fileId)
            }
        }

        return System.currentTimeMillis()
    }

    /**
     * Factory to build a canonical DownloadSource from a QualityCandidate.
     */
    fun fromCandidate(
        candidate: QualityCandidate,
        mediaId: String,
        title: String,
        posterUrl: String? = null,
        isMovie: Boolean = true,
        episodeId: String? = null,
        scraperKey: String? = null,
        fallbackCandidates: List<QualityCandidate> = emptyList(),
        variantMode: String? = null,
        audioLanguage: String? = null,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ): DownloadSource {
        val meta = mutableMapOf<String, String>()
        if (posterUrl != null) meta["posterUrl"] = posterUrl
        meta["isMovie"] = isMovie.toString()
        if (candidate.sourceUrl != null) meta["sourceUrl"] = candidate.sourceUrl
        if (candidate.serverName != null) meta["serverName"] = candidate.serverName
        if (variantMode != null) meta["mode"] = variantMode
        if (audioLanguage != null) meta["language"] = audioLanguage
        meta["season"] = seasonNumber.toString()
        meta["episode"] = episodeNumber.toString()

        val isHls = candidate.streamUrl.contains(".m3u8") || candidate.streamUrl.contains("akamaized.net")
        val protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
        val mimeType = if (isHls) "application/x-mpegURL" else "video/mp4"

        val fileId = if (variantMode != null && audioLanguage != null) {
            com.example.utils.MediaStorageUtils.buildDownloadId(mediaId, isMovie, seasonNumber, episodeNumber, variantMode, audioLanguage)
        } else {
            if (isMovie) mediaId else "${mediaId}_${episodeId ?: "1_1"}"
        }

        val resolvedHeaders = candidate.headers.toMutableMap()
        if (!resolvedHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) {
            if (!candidate.sourceUrl.isNullOrBlank()) {
                resolvedHeaders["Referer"] = candidate.sourceUrl
            }
        }

        return DownloadSource(
            url = candidate.streamUrl,
            filename = fileId,
            mimeType = mimeType,
            headers = resolvedHeaders,
            protocol = protocol,
            metadata = meta,
            quality = candidate.label,
            qualityKey = candidate.qualityKey,
            title = title,
            mediaId = mediaId,
            episodeId = episodeId,
            scraperKey = scraperKey,
            sourceUrl = candidate.sourceUrl,
            fallbackCandidates = fallbackCandidates,
            variantMode = variantMode ?: "subtitled",
            audioLanguage = audioLanguage ?: "ar"
        )
    }

    /**
     * Factory to build a canonical DownloadSource from an AggregatedQuality.
     */
    fun fromAggregatedQuality(
        aggregated: AggregatedQuality,
        mediaId: String,
        title: String,
        posterUrl: String? = null,
        isMovie: Boolean = true,
        episodeId: String? = null,
        scraperKey: String? = null,
        variantMode: String? = null,
        audioLanguage: String? = null,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1
    ): DownloadSource {
        return fromCandidate(
            candidate = aggregated.primaryCandidate,
            mediaId = mediaId,
            title = title,
            posterUrl = posterUrl,
            isMovie = isMovie,
            episodeId = episodeId,
            scraperKey = scraperKey,
            fallbackCandidates = aggregated.fallbackCandidates,
            variantMode = variantMode,
            audioLanguage = audioLanguage,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }

    /**
     * Factory to build a canonical DownloadSource from general stream details.
     */
    fun buildDownloadSource(
        streamUrl: String,
        mediaId: String,
        title: String,
        quality: String? = null,
        qualityKey: String? = null,
        headers: Map<String, String> = emptyMap(),
        posterUrl: String? = null,
        isMovie: Boolean = true,
        episodeId: String? = null,
        scraperKey: String? = null,
        sourceUrl: String? = null,
        fallbackCandidates: List<QualityCandidate> = emptyList(),
        metadata: Map<String, String> = emptyMap(),
        variantMode: String? = null,
        audioLanguage: String? = null,
        seasonNumber: Int = 1,
        episodeNumber: Int = 1,
        providerId: String? = null
    ): DownloadSource {
        val meta = metadata.toMutableMap()
        if (posterUrl != null) meta["posterUrl"] = posterUrl
        meta["isMovie"] = isMovie.toString()
        if (sourceUrl != null) meta["sourceUrl"] = sourceUrl
        val mode = variantMode ?: meta["mode"]
        val lang = audioLanguage ?: meta["language"]
        if (mode != null) meta["mode"] = mode
        if (lang != null) meta["language"] = lang
        meta["season"] = seasonNumber.toString()
        meta["episode"] = episodeNumber.toString()
        if (providerId != null) meta["providerId"] = providerId

        val isHls = streamUrl.contains(".m3u8") || streamUrl.contains("akamaized.net")
        val protocol = if (isHls) StreamProtocol.HLS else StreamProtocol.DIRECT_FILE
        val mimeType = if (isHls) "application/x-mpegURL" else "video/mp4"

        val fileId = if (mode != null && lang != null) {
            com.example.utils.MediaStorageUtils.buildDownloadId(mediaId, isMovie, seasonNumber, episodeNumber, mode, lang)
        } else {
            if (isMovie) mediaId else "${mediaId}_${episodeId ?: "1_1"}"
        }

        val resolvedHeaders = headers.toMutableMap()
        if (!resolvedHeaders.keys.any { it.equals("Referer", ignoreCase = true) }) {
            if (!sourceUrl.isNullOrBlank()) {
                resolvedHeaders["Referer"] = sourceUrl
            }
        }

        return DownloadSource(
            url = streamUrl,
            filename = fileId,
            mimeType = mimeType,
            headers = resolvedHeaders,
            protocol = protocol,
            metadata = meta,
            quality = quality,
            qualityKey = qualityKey ?: quality?.let { normalizeQualityKey(it) },
            title = title,
            mediaId = mediaId,
            episodeId = episodeId,
            scraperKey = scraperKey,
            sourceUrl = sourceUrl,
            fallbackCandidates = fallbackCandidates,
            variantMode = mode ?: "subtitled",
            audioLanguage = lang ?: "ar"
        )
    }
}
