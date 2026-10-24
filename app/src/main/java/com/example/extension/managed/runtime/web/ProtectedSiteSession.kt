package com.example.extension.managed.runtime.web

/**
 * Phase 05Q: Per-site Cloudflare & Browser Session Model.
 *
 * Enforces:
 * - Domain isolation: Each site/domain maintains strictly isolated session credentials.
 * - Zero bypass: Sessions are established exclusively through natural browser execution.
 * - Secure lifecycle: NONE -> CHALLENGE_REQUIRED -> ACTIVE -> INVALID / EXPIRED.
 */
enum class ProtectedSessionState {
    NONE,
    ACTIVE,
    CHALLENGE_REQUIRED,
    INVALID,
    EXPIRED
}

data class ProtectedSiteSession(
    val siteKey: String,
    val canonicalHost: String,
    val cookies: Map<String, String> = emptyMap(),
    val createdAt: Long = System.currentTimeMillis(),
    val lastValidatedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0L,
    val state: ProtectedSessionState = ProtectedSessionState.NONE,
    val userAgent: String = "",
    val requiresInteraction: Boolean = false
) {
    val hasCfClearance: Boolean
        get() = cookies.containsKey("cf_clearance") && !cookies["cf_clearance"].isNullOrBlank()

    fun isValid(): Boolean {
        if (state != ProtectedSessionState.ACTIVE) return false
        if (cookies.isEmpty()) return false
        if (expiresAt > 0 && System.currentTimeMillis() > expiresAt) return false
        return true
    }

    fun hasValidCookies(): Boolean = cookies.isNotEmpty()

    fun toCookieHeader(): String {
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    companion object {
        fun createEmpty(siteKey: String, canonicalHost: String): ProtectedSiteSession {
            return ProtectedSiteSession(
                siteKey = siteKey,
                canonicalHost = canonicalHost,
                cookies = emptyMap(),
                state = ProtectedSessionState.NONE
            )
        }
    }
}
