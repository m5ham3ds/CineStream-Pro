package com.example.extension.managed.web

import android.webkit.JavascriptInterface
import com.example.extension.managed.error.ExtensionError
import com.example.extension.managed.model.ChallengeStatus
import com.example.extension.managed.model.ServerItem
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Strict data-return bridge between isolated WebView Javascript and Android runtime.
 * Guarantees zero executable capability, strict origin verification, and schema validation.
 */
class SafeScraperBridge(
    private val expectedOrigin: String,
    private val listener: BridgeMessageListener,
    private val targetEmbedOrigin: String? = null
) {
    interface BridgeMessageListener {
        fun onServerList(servers: List<ServerItem>)
        fun onExtractionResult(streamUrl: String)
        fun onChallengeState(status: ChallengeStatus)
        fun onError(error: ExtensionError)
    }

    companion object {
        const val MAX_PAYLOAD_BYTES = 64 * 1024 // 64 KB limit to prevent DoS / memory exhaustion
        const val TYPE_SERVER_LIST = "SERVER_LIST"
        const val TYPE_EXTRACTION_RESULT = "EXTRACTION_RESULT"
        const val TYPE_CHALLENGE_STATE = "CHALLENGE_STATE"
        const val TYPE_ERROR = "ERROR"
    }

    @JavascriptInterface
    fun postMessage(rawJson: String?) {
        if (rawJson == null) {
            listener.onError(ExtensionError.ExtractionFailed("Null message received from bridge"))
            return
        }

        if (rawJson.length > MAX_PAYLOAD_BYTES) {
            listener.onError(
                ExtensionError.ExtractionFailed("Payload size exceeds maximum allowed limit (${rawJson.length} bytes)")
            )
            return
        }

        val json = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            listener.onError(ExtensionError.ExtractionFailed("Malformed JSON from bridge: ${e.message}", e))
            return
        }

        // Validate origin if provided in message
        if (json.has("origin")) {
            val reportedOrigin = json.getString("origin")
            if (!validateOrigin(reportedOrigin, expectedOrigin)) {
                listener.onError(
                    ExtensionError.ExtractionFailed("Untrusted message origin: '$reportedOrigin', expected: '$expectedOrigin'")
                )
                return
            }
        }

        val type = json.optString("type")
        when (type) {
            TYPE_SERVER_LIST -> parseServerList(json)
            TYPE_EXTRACTION_RESULT -> parseExtractionResult(json)
            TYPE_CHALLENGE_STATE -> parseChallengeState(json)
            TYPE_ERROR -> parseError(json)
            else -> {
                listener.onError(
                    ExtensionError.ExtractionFailed("Unsupported bridge message type: '$type'")
                )
            }
        }
    }

    private fun parseServerList(json: JSONObject) {
        val serversArray = json.optJSONArray("servers") ?: JSONArray()
        val result = mutableListOf<ServerItem>()
        for (i in 0 until serversArray.length()) {
            val item = serversArray.optJSONObject(i) ?: continue
            val name = item.optString("name").trim()
            val link = item.optString("link").trim()
            val id = item.optString("id").ifBlank { (i + 1).toString() }
            if (link.isNotBlank() && (link.startsWith("https://") || link.startsWith("http://"))) {
                result.add(
                    ServerItem(
                        id = id,
                        name = if (name.isNotBlank()) name else "سيرفر ${i + 1}",
                        link = link,
                        isDirectStream = link.contains(".m3u8") || link.contains(".mp4")
                    )
                )
            }
        }

        if (result.isNotEmpty()) {
            listener.onServerList(result)
        } else {
            listener.onError(ExtensionError.ExtractionFailed("SERVER_LIST message contained no valid servers"))
        }
    }

    private fun parseExtractionResult(json: JSONObject) {
        val streamUrl = json.optString("streamUrl").trim()
        val normalized = MediaStreamDetector.normalizeStreamUrl(streamUrl)
        if (MediaStreamDetector.validateMediaUrl(normalized)) {
            listener.onExtractionResult(normalized)
        } else {
            listener.onError(ExtensionError.ExtractionFailed("EXTRACTION_RESULT contained invalid or untrusted streamUrl: '$streamUrl'"))
        }
    }

    private fun parseChallengeState(json: JSONObject) {
        val statusStr = json.optString("status").uppercase()
        val status = when (statusStr) {
            "DETECTED" -> ChallengeStatus.DETECTED
            "SOLVED" -> ChallengeStatus.SOLVED
            "FAILED" -> ChallengeStatus.FAILED
            "TIMEOUT" -> ChallengeStatus.TIMEOUT
            else -> ChallengeStatus.NONE
        }
        listener.onChallengeState(status)
    }

    private fun parseError(json: JSONObject) {
        val detail = json.optString("detail").ifBlank { "Bridge script reported an error" }
        listener.onError(ExtensionError.ExtractionFailed(detail))
    }

    private fun validateOrigin(reported: String, expected: String): Boolean {
        return try {
            val reportedUri = URI(reported)
            val expectedUri = URI(expected)
            val repHost = reportedUri.host?.lowercase()?.removePrefix("www.") ?: return false
            val expHost = expectedUri.host?.lowercase()?.removePrefix("www.") ?: return false
            if (repHost == expHost || repHost.endsWith(".$expHost") || expHost.endsWith(".$repHost") ||
                TrustedEmbedHostPolicy.isSameBaseDomain(repHost, expHost) ||
                TrustedEmbedHostPolicy.isKnownEmbedHost(repHost)) {
                return true
            }
            if (!targetEmbedOrigin.isNullOrBlank()) {
                val embedUri = URI(targetEmbedOrigin)
                val embedHost = embedUri.host?.lowercase()?.removePrefix("www.") ?: ""
                if (embedHost.isNotBlank() && (repHost == embedHost || repHost.endsWith(".$embedHost") ||
                    TrustedEmbedHostPolicy.isSameBaseDomain(repHost, embedHost))) {
                    return true
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
