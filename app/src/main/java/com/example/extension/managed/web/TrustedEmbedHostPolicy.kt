package com.example.extension.managed.web

import android.net.Uri

/**
 * Security policy for controlled WebView navigation during media stream extraction.
 * Restricts navigation exclusively to HTTPS schemes and trusted embed providers,
 * preventing arbitrary redirects, ads, popups, and private IP/intranet attacks.
 */
object TrustedEmbedHostPolicy {

    // Known trusted media embed providers encountered by managed extensions
    private val KNOWN_EMBED_DOMAINS = setOf(
        "challenges.cloudflare.com",
        "cloudflare.com",
        "cloudflareinsights.com",
        "hgcloud.to",
        "streamhg.to",
        "streamhg.com",
        "egydead.live",
        "egydead.to",
        "tv10.egydead.live",
        "witanime.site",
        "witanime.com",
        "anime4up.rest",
        "anime4up.com",
        "animeblkom.net",
        "animeblkom.com",
        "vidbom.com",
        "vidshar.org",
        "uqload.com",
        "uqload.to",
        "doodstream.com",
        "dood.so",
        "dood.ws",
        "dood.to",
        "streamtape.com",
        "mp4upload.com",
        "upstream.to",
        "mixdrop.co",
        "mixdrop.to",
        "filelions.com",
        "filelions.to",
        "streamwish.to",
        "streamwish.com",
        "embedwish.com",
        "vidspeed.cc",
        "akamaized.net",
        "4shared.com",
        "vidmoly.me",
        "vidmoly.to",
        "videa.hu",
        "ok.ru",
        "mega.nz",
        "fastream.to",
        "uqload.io",
        "uqload.ws",
        "uqload.cx",
        "dood.li",
        "dood.re",
        "dood.pm",
        "dood.wf",
        "voe.sx",
        "dropload.io",
        "supervideo.tv",
        "vidguard.to"
    )

    // Forbidden host patterns (localhost, private network, link-local, loopback, test networks)
    private val FORBIDDEN_HOST_PATTERNS = listOf(
        Regex("^localhost$", RegexOption.IGNORE_CASE),
        Regex("^127\\.\\d+\\.\\d+\\.\\d+$"),
        Regex("^0\\.0\\.0\\.0$"),
        Regex("^10\\.\\d+\\.\\d+\\.\\d+$"),
        Regex("^192\\.168\\.\\d+\\.\\d+$"),
        Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\.\\d+\\.\\d+$"),
        Regex("^169\\.254\\.\\d+\\.\\d+$"),
        Regex("^192\\.0\\.2\\.\\d+$"),
        Regex("^198\\.51\\.100\\.\\d+$"),
        Regex("^203\\.0\\.113\\.\\d+$"),
        Regex(".*\\.local$", RegexOption.IGNORE_CASE),
        Regex(".*\\.internal$", RegexOption.IGNORE_CASE)
    )

    /**
     * Checks if a target URI is allowed for navigation in the controlled WebView.
     *
     * @param targetUri The URI to evaluate.
     * @param expectedOriginHost Optional origin host associated with the active extension.
     * @param initialTargetHost Optional host of the initially loaded target URL.
     * @return True if navigation is permitted, false if it should be blocked.
     */
    fun isNavigationAllowed(
        targetUri: Uri,
        expectedOriginHost: String? = null,
        initialTargetHost: String? = null
    ): Boolean {
        // 1. Strictly enforce HTTPS scheme
        val scheme = targetUri.scheme?.lowercase()
        if (scheme != "https") {
            return false
        }

        // 2. Reject missing or blank hosts
        val host = targetUri.host?.lowercase()?.trim() ?: return false
        if (host.isEmpty()) return false

        // 3. Block private IPs, loopbacks, and local patterns
        if (isForbiddenHost(host)) {
            return false
        }

        // 4. Media stream URLs are always allowed for inspection
        if (MediaStreamDetector.isMediaUrl(targetUri.toString())) {
            return true
        }

        // 5. Clean host string
        val normalizedHost = host.removePrefix("www.")

        // 6. Allow if matching expectedOriginHost
        if (!expectedOriginHost.isNullOrBlank()) {
            val normExpected = expectedOriginHost.lowercase().trim().removePrefix("www.")
            if (normalizedHost == normExpected || normalizedHost.endsWith(".$normExpected") ||
                isSameBaseDomain(normalizedHost, normExpected)) {
                return true
            }
        }

        // 7. Allow if matching initial target embed host (including subdomains and base domain)
        if (!initialTargetHost.isNullOrBlank()) {
            val normInitial = initialTargetHost.lowercase().trim().removePrefix("www.")
            if (normalizedHost == normInitial || normalizedHost.endsWith(".$normInitial") ||
                normInitial.endsWith(".$normalizedHost") || isSameBaseDomain(normalizedHost, normInitial)) {
                return true
            }
        }

        // 8. Allow Cloudflare verification domains (Turnstile / Managed challenge frames)
        if (normalizedHost == "challenges.cloudflare.com" || normalizedHost.endsWith(".cloudflare.com") ||
            normalizedHost == "cloudflareinsights.com") {
            return true
        }

        // 9. Allow if host matches known trusted embed providers
        return isKnownEmbedHost(normalizedHost)
    }

    fun isForbiddenHost(host: String): Boolean {
        return FORBIDDEN_HOST_PATTERNS.any { it.matches(host) }
    }

    fun isKnownEmbedHost(host: String): Boolean {
        val norm = host.lowercase().trim().removePrefix("www.")
        return KNOWN_EMBED_DOMAINS.any { domain ->
            norm == domain || norm.endsWith(".$domain")
        }
    }

    fun isSameBaseDomain(hostA: String, hostB: String): Boolean {
        val baseA = getBaseDomain(hostA)
        val baseB = getBaseDomain(hostB)
        return baseA.isNotBlank() && baseA == baseB
    }

    private fun getBaseDomain(host: String): String {
        val clean = host.removePrefix("www.")
        val parts = clean.split('.')
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}.${parts[parts.size - 1]}"
        } else {
            clean
        }
    }
}
