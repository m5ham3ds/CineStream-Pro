package com.example.extension.managed.web

import com.example.extension.managed.model.*
import java.net.URI
import java.util.regex.Pattern

/**
 * Universal media stream detection, normalization, and classification engine.
 * Handles detection of HLS (.m3u8), DASH (.mpd), and direct video files (.mp4, .mkv, .webm)
 * from network requests, DOM nodes, script variables, and unpacked player payloads.
 */
object MediaStreamDetector {

    const val STANDARD_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val MEDIA_EXTENSIONS = listOf(
        ".m3u8",
        ".mp4",
        ".mkv",
        ".webm",
        ".mpd",
        ".m4v",
        ".mov"
    )

    private val MEDIA_PATH_PATTERNS = listOf(
        "/hls/",
        "/hls2/",
        ".urlset/",
        "videodelivery.net",
        "/manifest/hls",
        "/manifest/dash"
    )

    private val AD_AND_TRACKER_HOSTS = listOf(
        "googleads",
        "doubleclick",
        "facebook",
        "google-analytics",
        "analytics",
        "histats",
        "clarity.ms",
        "popcash",
        "propeller",
        "adtrue",
        "llvpn.com",
        "scorecardresearch",
        "hotjar",
        "googletagmanager"
    )

    private val NON_MEDIA_FILE_EXTENSIONS = listOf(
        ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
        ".css", ".js", ".json", ".woff", ".woff2", ".ttf", ".eot"
    )

    private val MEDIA_URL_REGEX = Regex(
        """https?://[^"'<>\s\\]+?\.(?:m3u8|mp4|mkv|webm|mpd)(?:\?[^"'<>\s\\]*)?""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val PLAYER_CONFIG_KEY_REGEX = Regex(
        """(?:file|source|src|video_url|stream_url|hlsUrl)\s*:\s*["'](https?:[^"']+)["']""",
        setOf(RegexOption.IGNORE_CASE)
    )

    /**
     * Determines whether the given URL is a candidate media stream.
     */
    fun isMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = url.trim().lowercase()

        // Reject non-media static assets
        val pathWithoutQuery = clean.substringBefore("?")
        if (NON_MEDIA_FILE_EXTENSIONS.any { pathWithoutQuery.endsWith(it) }) {
            return false
        }

        // Must start with http://, https://, or //
        if (!clean.startsWith("http://") && !clean.startsWith("https://") && !clean.startsWith("//")) {
            return false
        }

        // Check extensions
        if (MEDIA_EXTENSIONS.any { pathWithoutQuery.contains(it) }) {
            return true
        }

        // Check path tokens
        if (MEDIA_PATH_PATTERNS.any { clean.contains(it) }) {
            return true
        }

        return false
    }

    /**
     * Checks if the URL is associated with known ad networks, trackers, or analytics.
     */
    fun isAdOrAnalytics(url: String): Boolean {
        val lower = url.lowercase()
        return AD_AND_TRACKER_HOSTS.any { lower.contains(it) }
    }

    /**
     * Extracts and normalizes all media candidate URLs from arbitrary text (HTML, JavaScript, JSON).
     */
    fun extractMediaCandidates(text: String): List<String> {
        val candidates = mutableListOf<String>()

        // 1. Check player configuration keys (file: "...", source: "...")
        val configMatches = PLAYER_CONFIG_KEY_REGEX.findAll(text)
        for (m in configMatches) {
            val candidate = normalizeStreamUrl(m.groupValues[1])
            if (isMediaUrl(candidate) && !isAdOrAnalytics(candidate)) {
                candidates.add(candidate)
            }
        }

        // 2. Direct regex search for media URLs
        val urlMatches = MEDIA_URL_REGEX.findAll(text)
        for (m in urlMatches) {
            val candidate = normalizeStreamUrl(m.value)
            if (isMediaUrl(candidate) && !isAdOrAnalytics(candidate)) {
                candidates.add(candidate)
            }
        }

        return candidates.distinct()
    }

    /**
     * Normalizes a discovered raw stream URL (Stage F).
     * Decodes escaped slashes, trims quotes/spaces, ensures valid scheme.
     */
    fun normalizeStreamUrl(rawUrl: String): String {
        var clean = rawUrl.trim()

        // Remove wrapping quotes or brackets
        clean = clean.trim('"', '\'', '`', '<', '>', '{', '}', '[', ']', ';', ',')

        // Decode escaped forward slashes
        clean = clean.replace("\\/", "/")

        // Decode basic URL escaped characters if present
        clean = clean.replace("\\u0026", "&")
            .replace("&amp;", "&")
            .replace("\\u002F", "/")

        // Fix protocol-relative URLs
        if (clean.startsWith("//")) {
            clean = "https:$clean"
        }

        return clean.trim()
    }

    /**
     * Determines the protocol for a given stream URL (Stage G).
     */
    fun determineProtocol(url: String): StreamProtocol {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") || lower.contains("/hls") || lower.contains("akamaized.net") -> StreamProtocol.HLS
            lower.contains(".mpd") || lower.contains("/dash") -> StreamProtocol.DASH
            lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".webm") || lower.contains(".m4v") -> StreamProtocol.DIRECT_FILE
            else -> StreamProtocol.HLS
        }
    }

    /**
     * Determines standard MIME type for the stream URL.
     */
    fun determineMimeType(url: String, protocol: StreamProtocol): String {
        val lower = url.lowercase()
        return when (protocol) {
            StreamProtocol.HLS -> "application/x-mpegURL"
            StreamProtocol.DASH -> "application/dash+xml"
            StreamProtocol.DIRECT_FILE -> {
                when {
                    lower.contains(".mkv") -> "video/x-matroska"
                    lower.contains(".webm") -> "video/webm"
                    else -> "video/mp4"
                }
            }
            else -> "application/x-mpegURL"
        }
    }

    /**
     * Constructs a normalized PlaybackSource with variants.
     */
    suspend fun createPlaybackSource(
        streamUrl: String,
        headers: Map<String, String> = emptyMap()
    ): PlaybackSource {
        val normalizedUrl = normalizeStreamUrl(streamUrl)
        val protocol = determineProtocol(normalizedUrl)
        val mimeType = determineMimeType(normalizedUrl, protocol)

        val variants = if (protocol == StreamProtocol.HLS) {
            try {
                val parsed = com.example.utils.M3U8Parser.getQualities(normalizedUrl, headers)
                if (parsed.isNotEmpty()) {
                    MediaVariantParser.fromQualityInfoList(parsed, StreamProtocol.HLS, headers)
                } else {
                    listOf(MediaVariant(url = normalizedUrl, protocol = StreamProtocol.HLS, headers = headers))
                }
            } catch (_: Exception) {
                listOf(MediaVariant(url = normalizedUrl, protocol = StreamProtocol.HLS, headers = headers))
            }
        } else {
            emptyList()
        }

        return PlaybackSource(
            streamUrl = normalizedUrl,
            headers = headers,
            mimeType = mimeType,
            protocol = protocol,
            variants = variants
        )
    }

    /**
     * Strictly validates a discovered candidate media URL (Stage G Validation).
     * Enforces HTTPS, non-empty, valid URL syntax, supported media type, non-ad, and non-forbidden host.
     */
    fun validateMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val clean = normalizeStreamUrl(url)
        if (!clean.startsWith("https://", ignoreCase = true)) {
            return false
        }
        val uri = try {
            URI(clean)
        } catch (_: Exception) {
            return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host.isBlank() || TrustedEmbedHostPolicy.isForbiddenHost(host)) {
            return false
        }
        if (isAdOrAnalytics(clean)) {
            return false
        }
        return isMediaUrl(clean)
    }

    /**
     * Constructs a complete, decoupled ExtractionResult.
     */
    suspend fun createExtractionResult(
        url: String,
        headers: Map<String, String> = emptyMap()
    ): ExtractionResult {
        val playback = createPlaybackSource(url, headers)
        val download = DownloadSource(
            url = playback.streamUrl,
            mimeType = playback.mimeType,
            protocol = playback.protocol,
            headers = headers
        )
        return ExtractionResult(
            playbackSource = playback,
            downloadSource = download,
            variants = playback.variants
        )
    }
}
