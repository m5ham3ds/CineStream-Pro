package com.example.extension.managed.runtime.web

/**
 * Phase 05Q Specific Error Taxonomy.
 * Strictly separates distinct failure layers to avoid collapsing errors into generic fallbacks.
 */
sealed class ProtectedWebRuntimeError(
    val errorCode: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause) {

    class CloudflareChallengeRequired(
        val siteKey: String,
        val host: String
    ) : ProtectedWebRuntimeError(
        "CHALLENGE_REQUIRED",
        "CLOUDFLARE_CHALLENGE_REQUIRED: Site '$siteKey' at '$host' requires natural browser challenge resolution"
    )

    class CloudflareChallengeInteractive(
        val siteKey: String,
        val host: String,
        val url: String
    ) : ProtectedWebRuntimeError(
        "CHALLENGE_INTERACTIVE",
        "CLOUDFLARE_CHALLENGE_INTERACTIVE: Site '$siteKey' opened interactive challenge at '$url'"
    )

    class CloudflareChallengeCompleted(
        val siteKey: String,
        val host: String
    ) : ProtectedWebRuntimeError(
        "CHALLENGE_COMPLETED",
        "CLOUDFLARE_CHALLENGE_COMPLETED: Challenge successfully solved for '$siteKey'"
    )

    class CloudflareChallengeCancelled(
        val siteKey: String
    ) : ProtectedWebRuntimeError(
        "CHALLENGE_CANCELLED",
        "CLOUDFLARE_CHALLENGE_CANCELLED: Challenge cancelled by user for '$siteKey'"
    )

    class CloudflareChallengeUnverifiedExit(
        val siteKey: String
    ) : ProtectedWebRuntimeError(
        "UNVERIFIED_EXIT",
        "CLOUDFLARE_CHALLENGE_UNVERIFIED_EXIT: User exited challenge without completing verification for '$siteKey'"
    )

    class CloudflareChallengeTimeout(
        val siteKey: String,
        val timeoutMs: Long
    ) : ProtectedWebRuntimeError(
        "CHALLENGE_TIMEOUT",
        "CLOUDFLARE_CHALLENGE_TIMEOUT: Cloudflare challenge resolution timed out for '$siteKey' after ${timeoutMs}ms"
    )

    class CloudflareChallengeFailed(
        val siteKey: String,
        val reason: String
    ) : ProtectedWebRuntimeError(
        "CHALLENGE_FAILED",
        "CLOUDFLARE_CHALLENGE_FAILED: Interactive challenge failed for '$siteKey': $reason"
    )

    class CloudflareRecoveryLimitReached(
        val siteKey: String,
        val host: String
    ) : ProtectedWebRuntimeError(
        "RECOVERY_LIMIT_REACHED",
        "CLOUDFLARE_RECOVERY_LIMIT_REACHED: Maximum recovery attempts reached for site '$siteKey' at '$host'"
    )

    class CloudflareSessionReused(
        val siteKey: String,
        val host: String
    ) : ProtectedWebRuntimeError(
        "SESSION_REUSED",
        "CLOUDFLARE_SESSION_REUSED: Stored session successfully reused for '$siteKey'"
    )

    class CloudflareSessionInvalid(
        val siteKey: String,
        val reason: String = "Session expired or rejected by origin"
    ) : ProtectedWebRuntimeError(
        "SESSION_INVALID",
        "CLOUDFLARE_SESSION_INVALID: Session for '$siteKey' is invalid ($reason)"
    )

    class CloudflareSessionReplaced(
        val siteKey: String
    ) : ProtectedWebRuntimeError(
        "SESSION_REPLACED",
        "CLOUDFLARE_SESSION_REPLACED: Prior session for '$siteKey' replaced by fresh verified session"
    )

    class WebEngineLoadFailed(
        val url: String,
        val reason: String
    ) : ProtectedWebRuntimeError(
        "WEBENGINE_LOAD_FAILED",
        "WEBENGINE_LOAD_FAILED: Failed to load '$url' ($reason)"
    )

    class DnsFailure(
        val host: String
    ) : ProtectedWebRuntimeError(
        "DNS_FAILURE",
        "DNS_FAILURE: Unable to resolve host '$host'"
    )

    class Http403(
        val url: String,
        val details: String = "Forbidden"
    ) : ProtectedWebRuntimeError(
        "HTTP_403",
        "HTTP_403: Server returned 403 Forbidden for '$url' ($details)"
    )

    class Http404(
        val url: String
    ) : ProtectedWebRuntimeError(
        "HTTP_404",
        "HTTP_404: Resource not found at '$url'"
    )

    class ExtractionFailed(
        val url: String,
        val reason: String
    ) : ProtectedWebRuntimeError(
        "EXTRACTION_FAILED",
        "EXTRACTION_FAILED: Media extraction failed for '$url' ($reason)"
    )

    class PlayableUrlInvalid(
        val url: String
    ) : ProtectedWebRuntimeError(
        "PLAYABLE_URL_INVALID",
        "PLAYABLE_URL_INVALID: Extracted URL is not a valid playable media stream: '$url'"
    )

    class PlayerHandoffFailed(
        val reason: String
    ) : ProtectedWebRuntimeError(
        "PLAYER_HANDOFF_FAILED",
        "PLAYER_HANDOFF_FAILED: Media handoff rejected: $reason"
    )
}
