package com.example.extension.managed.runtime.web

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/**
 * Phase 05Q Cloudflare Challenge Detector.
 *
 * Responsibilities:
 * - Accurately identifies whether an incoming page represents a Cloudflare verification challenge.
 * - Strictly differentiates CLOUDFLARE_CHALLENGE from standard HTTP 403, HTTP 404, DNS errors,
 *   site connection outages, parser defects, or player embed failures.
 * - Categorizes challenge type (Turnstile, Managed, Interactive, JavaScript challenge).
 */
object CloudflareChallengeDetector {

    enum class ChallengeType {
        NONE,
        TURNSTILE,
        MANAGED_CHALLENGE,
        INTERACTIVE,
        JAVASCRIPT_CHALLENGE,
        GENERIC_CHALLENGE
    }

    enum class FailureCategory {
        NONE,
        CLOUDFLARE_CHALLENGE,
        HTTP_403,
        HTTP_404,
        DNS_FAILURE,
        SITE_UNAVAILABLE,
        PARSER_FAILURE,
        EMBED_FAILURE
    }

    data class DetectionResult(
        val isChallenge: Boolean,
        val challengeType: ChallengeType = ChallengeType.NONE,
        val failureCategory: FailureCategory = FailureCategory.NONE,
        val requiresInteraction: Boolean = false,
        val details: String = ""
    )

    private val CF_TITLE_SIGNATURES = listOf(
        "just a moment...",
        "attention required! | cloudflare",
        "security check",
        "ddos-guard",
        "please verify you are a human"
    )

    private val CF_DOM_SELECTORS = listOf(
        "#challenge-form",
        "#challenge-running",
        "#challenge-stage",
        ".cf-browser-verification",
        "#cf-wrapper",
        "#turnstile-wrapper",
        "iframe[src*='challenges.cloudflare.com']",
        "div[class*='cf-turnstile']",
        "#challenge-error-title",
        ".cf-turnstile-wrapper",
        "#cf-stage"
    )

    /**
     * Inspects HTML content, HTTP response code, headers, title, and current URL.
     */
    fun detect(
        html: String? = null,
        statusCode: Int = 200,
        headers: Map<String, String> = emptyMap(),
        pageTitle: String? = null,
        currentUrl: String? = null,
        exceptionMessage: String? = null
    ): DetectionResult {
        // 1. Inspect network / exception message signals first
        val exc = exceptionMessage?.lowercase() ?: ""
        if (exc.contains("unknownhostexception") || exc.contains("err_name_not_resolved") || exc.contains("dns")) {
            return DetectionResult(
                isChallenge = false,
                failureCategory = FailureCategory.DNS_FAILURE,
                details = "DNS resolution failure: $exceptionMessage"
            )
        }

        if (exc.contains("connectexception") || exc.contains("connection refused") ||
            exc.contains("err_connection_refused") || exc.contains("err_connection_timed_out") ||
            statusCode == 502 || statusCode == 504) {
            return DetectionResult(
                isChallenge = false,
                failureCategory = FailureCategory.SITE_UNAVAILABLE,
                details = "Site unavailable or server gateway error (HTTP $statusCode): $exceptionMessage"
            )
        }

        // 2. HTTP 404 Not Found
        if (statusCode == 404 || exc.contains("404")) {
            return DetectionResult(
                isChallenge = false,
                failureCategory = FailureCategory.HTTP_404,
                details = "HTTP 404 Not Found"
            )
        }

        // 3. Inspect Cloudflare headers and exception signatures
        val serverHeader = headers.entries.firstOrNull { it.key.equals("server", ignoreCase = true) }?.value?.lowercase() ?: ""
        val cfMitigated = headers.entries.firstOrNull { it.key.equals("cf-mitigated", ignoreCase = true) }?.value?.lowercase() ?: ""
        val cfRay = headers.entries.firstOrNull { it.key.equals("cf-ray", ignoreCase = true) }?.value ?: ""
        val isCfServer = serverHeader.contains("cloudflare") || cfRay.isNotBlank() || cfMitigated.contains("challenge") || exc.contains("cloudflare")

        // 4. URL signals
        val url = currentUrl?.lowercase() ?: ""
        val isChallengeUrl = url.contains("/cdn-cgi/challenge-platform/") || url.contains("challenges.cloudflare.com")

        // 5. Title & Exception inspection
        val title = (pageTitle ?: "").lowercase().trim()
        val hasChallengeTitle = CF_TITLE_SIGNATURES.any { title.contains(it) }
        val hasChallengeException = exc.contains("cloudflare") ||
                exc.contains("turnstile") ||
                CF_TITLE_SIGNATURES.any { exc.contains(it) } ||
                (exc.contains("challenge") && (exc.contains("403") || exc.contains("cf") || exc.contains("required") || exc.contains("forbidden")))

        // 6. DOM inspection
        val doc = if (!html.isNullOrBlank()) {
            try { Jsoup.parse(html) } catch (_: Exception) { null }
        } else null

        val docTitle = doc?.title()?.lowercase()?.trim() ?: ""
        val hasDocTitle = CF_TITLE_SIGNATURES.any { docTitle.contains(it) }

        val hasChallengeSelector = doc?.let { d ->
            CF_DOM_SELECTORS.any { selector -> d.select(selector).isNotEmpty() }
        } ?: false

        val hasTurnstile = (doc?.select("iframe[src*='challenges.cloudflare.com'], div[class*='cf-turnstile'], #turnstile-wrapper")?.isNotEmpty() == true) ||
                (html?.contains("challenges.cloudflare.com") == true) ||
                (html?.contains("turnstile") == true && isCfServer) ||
                exc.contains("turnstile")

        val hasInteractiveForm = doc?.select("input[type='checkbox'], #challenge-stage input")?.isNotEmpty() == true ||
                hasChallengeException

        // Decision: Is this a genuine Cloudflare challenge?
        val isChallenge = (hasChallengeTitle || hasDocTitle || hasChallengeSelector || isChallengeUrl || hasTurnstile || hasChallengeException) &&
                (isCfServer || statusCode in listOf(403, 503, 429) || hasChallengeSelector || hasTurnstile || hasChallengeException)

        if (isChallenge) {
            val type = when {
                hasTurnstile && hasInteractiveForm -> ChallengeType.INTERACTIVE
                hasTurnstile -> ChallengeType.TURNSTILE
                html?.contains("cf-browser-verification") == true -> ChallengeType.JAVASCRIPT_CHALLENGE
                isCfServer && (statusCode == 403 || statusCode == 503) -> ChallengeType.MANAGED_CHALLENGE
                else -> ChallengeType.GENERIC_CHALLENGE
            }

            return DetectionResult(
                isChallenge = true,
                challengeType = type,
                failureCategory = FailureCategory.CLOUDFLARE_CHALLENGE,
                requiresInteraction = hasInteractiveForm || type == ChallengeType.INTERACTIVE,
                details = "Cloudflare Challenge detected ($type), title='$title', status=$statusCode, turnstile=$hasTurnstile"
            )
        }

        // 7. Non-Cloudflare HTTP 403 Forbidden
        if (statusCode == 403) {
            return DetectionResult(
                isChallenge = false,
                failureCategory = FailureCategory.HTTP_403,
                details = "Standard HTTP 403 Forbidden (Non-Cloudflare origin restriction)"
            )
        }

        // 8. Parser / Embed failure checks
        if (exc.contains("parser") || exc.contains("selector") || exc.contains("element not found")) {
            return DetectionResult(
                isChallenge = false,
                failureCategory = FailureCategory.PARSER_FAILURE,
                details = "Parser failure: $exceptionMessage"
            )
        }

        if (exc.contains("embed") || exc.contains("player error") || exc.contains("iframe")) {
            return DetectionResult(
                isChallenge = false,
                failureCategory = FailureCategory.EMBED_FAILURE,
                details = "Embed failure: $exceptionMessage"
            )
        }

        // 9. No challenge detected
        return DetectionResult(
            isChallenge = false,
            failureCategory = FailureCategory.NONE,
            details = "Clean page load (no challenge)"
        )
    }

    /**
     * Inspects a Jsoup Document directly.
     */
    fun detectFromDocument(doc: Document, statusCode: Int = 200): DetectionResult {
        return detect(
            html = doc.html(),
            statusCode = statusCode,
            pageTitle = doc.title()
        )
    }
}
