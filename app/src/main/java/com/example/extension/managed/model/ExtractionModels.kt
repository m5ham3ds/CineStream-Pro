package com.example.extension.managed.model

import java.util.UUID

enum class StreamProtocol {
    HLS,
    DASH,
    DIRECT_FILE,
    EMBED,
    UNKNOWN
}

/**
 * Normalized representation of a discovered media stream variant (resolution / bitrate).
 * Never assumes artificial defaults; missing resolution/height remains null or "Auto".
 */
data class MediaVariant(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val width: Int? = null,
    val height: Int? = null,
    val bitrate: Long? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val protocol: StreamProtocol = StreamProtocol.UNKNOWN,
    val headers: Map<String, String> = emptyMap(),
    val isDefault: Boolean = false
) {
    val displayQuality: String
        get() = when {
            !label.isNullOrBlank() && isAutoQualityToken(label) -> "Auto"
            !label.isNullOrBlank() -> normalizeCanonicalQualityName(label) ?: "Auto"
            height != null && height > 0 -> normalizeCanonicalQualityName("${height}p") ?: "${height}p"
            else -> "Auto"
        }

    fun toQualitySource(): QualitySource = QualitySource(
        label = displayQuality,
        url = url,
        resolution = if (width != null && height != null) "${width}x$height" else null,
        bitrate = bitrate
    )
}

data class QualitySource(
    val label: String,
    val url: String,
    val resolution: String? = null,
    val bitrate: Long? = null
)

/**
 * Normalized playback source for consumption by media players.
 * Decoupled from scraper, WebView, and remote configuration details.
 */
data class PlaybackSource(
    val streamUrl: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val drmScheme: String? = null,
    val protocol: StreamProtocol = StreamProtocol.UNKNOWN,
    val variants: List<MediaVariant> = emptyList(),
    val qualities: List<QualitySource> = if (variants.isNotEmpty()) variants.map { it.toQualitySource() } else emptyList(),
    val durationMs: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Normalized download source describing a media asset available for download.
 * Completely distinct from playback session cookies or tokens.
 */
data class DownloadSource(
    val url: String,
    val filename: String? = null,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val protocol: StreamProtocol = StreamProtocol.DIRECT_FILE,
    val estimatedBytes: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
    val quality: String? = null,
    val qualityKey: String? = null,
    val title: String? = null,
    val mediaId: String? = null,
    val episodeId: String? = null,
    val scraperKey: String? = null,
    val sourceUrl: String? = null,
    val fallbackCandidates: List<QualityCandidate> = emptyList(),
    val variantMode: String = "subtitled",
    val audioLanguage: String = "ar"
) {
    val streamUrl: String get() = url
}

data class DownloadRequest(
    val mediaId: String,
    val title: String,
    val serverItem: ServerItem? = null,
    val quality: String? = null,
    val headers: Map<String, String> = emptyMap()
)

/**
 * Normalized download task request for consumption by download services.
 * Contains only minimal projected credentials necessary for the transfer.
 */
data class DownloadTaskRequest(
    val id: String,
    val title: String,
    val downloadUrl: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    val downloadSource: DownloadSource? = null
)

data class ExtractionRequest(
    val serverItem: ServerItem,
    val mediaTitle: String = "",
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 15_000L
)

data class ExtractionResult(
    val playbackSource: PlaybackSource?,
    val downloadTask: DownloadTaskRequest? = null,
    val downloadSource: DownloadSource? = null,
    val variants: List<MediaVariant> = playbackSource?.variants ?: emptyList(),
    val metadata: Map<String, String> = emptyMap()
)
