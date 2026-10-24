package com.example.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI

object M3U8Parser {
    
    data class QualityInfo(
        val name: String,
        val url: String,
        val width: Int? = null,
        val height: Int? = null,
        val bitrate: Long? = null,
        val headers: Map<String, String> = emptyMap(),
        val serverId: String? = null,
        val serverName: String? = null
    )

    fun heightToCanonicalQuality(h: Int): String? {
        return when {
            h >= 900 -> "1080p"
            h in 650..899 -> "720p"
            h in 420..649 -> "480p"
            h in 300..419 -> "360p"
            h in 200..299 -> "240p"
            h in 100..199 -> "144p"
            else -> null
        }
    }

    fun resolveUri(baseMasterUrl: String, targetUri: String): String {
        val trimmed = targetUri.trim().removeSurrounding("\"").removeSurrounding("'")
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            return trimmed
        }
        return try {
            val baseUri = URI(baseMasterUrl)
            baseUri.resolve(trimmed).toString()
        } catch (_: Exception) {
            if (trimmed.startsWith("/")) {
                val protoHost = baseMasterUrl.substringBefore("://") + "://" + baseMasterUrl.substringAfter("://").substringBefore("/")
                "$protoHost$trimmed"
            } else {
                val dir = baseMasterUrl.substringBeforeLast("/")
                "$dir/$trimmed"
            }
        }
    }

    /**
     * Parses HLS playlist content.
     * Enforces evidence-based resolution parsing without fabricating qualities from bandwidth or arbitrary labels.
     * Correctly identifies single media playlists vs master playlists with variants.
     */
    fun parsePlaylistContent(
        content: String,
        masterUrl: String,
        headers: Map<String, String> = emptyMap()
    ): List<QualityInfo> {
        val qualities = mutableListOf<QualityInfo>()

        // 1. Single Media Playlist check: contains #EXTINF (segments) and NO #EXT-X-STREAM-INF
        if (content.contains("#EXTINF:") && !content.contains("#EXT-X-STREAM-INF:")) {
            return listOf(QualityInfo(name = "Auto", url = masterUrl, headers = headers))
        }

        val lines = content.split("\n").map { it.trim() }

        var currentWidth: Int? = null
        var currentHeight: Int? = null
        var currentBitrate: Long? = null
        var currentInlineUri: String? = null

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                currentWidth = null
                currentHeight = null
                currentBitrate = null
                currentInlineUri = null

                // Extract RESOLUTION (e.g. RESOLUTION=1920x1080)
                val resMatch = Regex("""RESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE).find(line)
                if (resMatch != null) {
                    val w = resMatch.groupValues[1].toIntOrNull() ?: 0
                    val h = resMatch.groupValues[2].toIntOrNull() ?: 0
                    if (w > 0 && h > 0) {
                        currentWidth = w
                        currentHeight = h
                    }
                }

                // Extract BANDWIDTH (e.g. BANDWIDTH=5000000)
                val bwMatch = Regex("""BANDWIDTH=(\d+)""", RegexOption.IGNORE_CASE).find(line)
                if (bwMatch != null) {
                    currentBitrate = bwMatch.groupValues[1].toLongOrNull()
                }

                // Extract inline URI if specified in attributes (e.g. URI="...")
                val uriMatch = Regex("""URI="([^"]+)"""", RegexOption.IGNORE_CASE).find(line)
                if (uriMatch != null) {
                    currentInlineUri = uriMatch.groupValues[1]
                }

                // If inline URI exists and we have valid resolution evidence
                if (!currentInlineUri.isNullOrBlank()) {
                    val qName = currentHeight?.let { heightToCanonicalQuality(it) }
                    if (qName != null) {
                        val fullUrl = resolveUri(masterUrl, currentInlineUri!!)
                        qualities.add(
                            QualityInfo(
                                name = qName,
                                url = fullUrl,
                                width = currentWidth,
                                height = currentHeight,
                                bitrate = currentBitrate,
                                headers = headers
                            )
                        )
                    }
                    currentWidth = null
                    currentHeight = null
                    currentBitrate = null
                    currentInlineUri = null
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                // This is the target variant URL on the line following #EXT-X-STREAM-INF
                if (currentHeight != null && currentHeight!! > 0) {
                    val qName = heightToCanonicalQuality(currentHeight!!)
                    if (qName != null) {
                        val fullUrl = resolveUri(masterUrl, line)
                        qualities.add(
                            QualityInfo(
                                name = qName,
                                url = fullUrl,
                                width = currentWidth,
                                height = currentHeight,
                                bitrate = currentBitrate,
                                headers = headers
                            )
                        )
                    }
                }
                currentWidth = null
                currentHeight = null
                currentBitrate = null
                currentInlineUri = null
            }
        }

        // Deduplicate variants by name
        val distinctQualities = qualities.distinctBy { it.name }
            .sortedByDescending { it.height ?: (it.name.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0) }

        return distinctQualities
    }

    suspend fun fetchAndParseQualities(
        masterUrl: String,
        headers: Map<String, String> = emptyMap()
    ): List<QualityInfo> = withContext(Dispatchers.IO) {
        val qualities = mutableListOf<QualityInfo>()
        try {
            val url = URL(masterUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"

            // Set provided headers (preserving Referer, User-Agent, etc.)
            for ((key, value) in headers) {
                if (key.isNotBlank() && value.isNotBlank()) {
                    conn.setRequestProperty(key, value)
                }
            }
            if (!headers.keys.any { it.equals("User-Agent", ignoreCase = true) }) {
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val content = conn.inputStream.bufferedReader().use { it.readText() }
                val parsed = parsePlaylistContent(content, masterUrl, headers)
                if (parsed.isNotEmpty()) {
                    qualities.addAll(parsed)
                } else if (content.contains("#EXTM3U")) {
                    qualities.add(QualityInfo("Auto", masterUrl, headers = headers))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        qualities
    }

    suspend fun getQualities(
        masterUrl: String,
        headers: Map<String, String> = emptyMap()
    ): List<QualityInfo> = withContext(Dispatchers.IO) {
        val qualities = fetchAndParseQualities(masterUrl, headers).toMutableList()

        // If no qualities parsed (e.g. media playlist or single stream), return Auto
        if (qualities.isEmpty()) {
            qualities.add(QualityInfo("Auto", masterUrl, headers = headers))
        }

        qualities
    }
}