package com.example.extension.managed.runtime.challenge

import com.example.extension.managed.model.ChallengeStatus

/**
 * Result of a challenge resolution attempt (e.g. Cloudflare Turnstile/Interactive challenge).
 */
data class ChallengeResult(
    val status: ChallengeStatus,
    val resolvedCookies: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val errorMessage: String? = null
)

/**
 * Standard contract for interactive or managed challenge resolution.
 * Strictly forbids automated CAPTCHA bypass or SSL tampering.
 */
interface ChallengeHandler {
    suspend fun handleChallenge(
        targetUrl: String,
        status: ChallengeStatus,
        timeoutMs: Long = 15_000L
    ): Result<ChallengeResult>
}
