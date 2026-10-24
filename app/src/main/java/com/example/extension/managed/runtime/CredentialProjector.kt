package com.example.extension.managed.runtime

/**
 * Ensures minimal credential projection.
 * Filters session headers and cookies so only strictly required credentials
 * are exposed to PlaybackSource and DownloadTaskRequest.
 */
object CredentialProjector {

    private val ALLOWED_STANDARD_STREAM_HEADERS = setOf(
        "referer",
        "user-agent",
        "origin",
        "accept-language",
        "sec-fetch-dest",
        "sec-fetch-mode",
        "sec-fetch-site"
    )

    fun projectHeaders(
        sessionHeaders: Map<String, String>,
        sessionCookies: Map<String, String>,
        requiredHeaderKeys: Set<String> = emptySet(),
        requiredCookieKeys: Set<String> = emptySet()
    ): Map<String, String> {
        val projected = mutableMapOf<String, String>()

        // 1. Only project explicitly required headers or standard media player headers if present in requiredHeaderKeys
        val normalizedRequiredHeaders = requiredHeaderKeys.map { it.lowercase() }.toSet()
        for ((key, value) in sessionHeaders) {
            val lowerKey = key.lowercase()
            if (lowerKey in normalizedRequiredHeaders || (normalizedRequiredHeaders.isEmpty() && lowerKey in ALLOWED_STANDARD_STREAM_HEADERS)) {
                projected[key] = value
            }
        }

        // 2. Only project explicitly required cookies as a Cookie header
        val normalizedRequiredCookies = requiredCookieKeys.map { it.lowercase() }.toSet()
        if (normalizedRequiredCookies.isNotEmpty()) {
            val matchedCookies = sessionCookies.filter { (k, _) ->
                k.lowercase() in normalizedRequiredCookies
            }
            if (matchedCookies.isNotEmpty()) {
                val cookieString = matchedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                projected["Cookie"] = cookieString
            }
        }

        return projected
    }
}
