package com.example.data.model

import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * PHASE SUBSCRIPTION-POINTS-03B: CANONICAL RESTRICTIONS & ENTITLEMENT MODEL
 *
 * Separation of Concerns:
 * 1. Subscription Entitlement: ONLY controls ad removal (isAdFree).
 * 2. Technical Account Restrictions: canWatch, canDownload, allowedQuality, downloadLimit, deviceLimit.
 *    These remain completely independent and are NEVER bypassed or imposed by subscription tier.
 */
@IgnoreExtraProperties
data class UserRestrictions(
    val isActive: Boolean = true,
    val isPremium: Boolean = false, // Retained for legacy backward compatibility
    val role: String = "user",
    val subscriptionTier: String = "FREE",
    val subscriptionStatus: String = "none",
    val subscriptionExpiresAt: Long? = null,
    val planId: String = "free",
    val durationDays: Int = 0,
    val subscriptionSource: String = "LEGACY",
    val subscriptionReferenceId: String? = null,
    val subscriptionStartedAt: Long? = null,

    // Independent Technical Account Restrictions
    val deviceLimit: Int? = null,
    val allowedQuality: String? = null,
    val downloadLimit: Int? = null,
    
    // Account ban status
    val isBanned: Boolean = false,
    val banReason: String = "",
    val banExpiresAt: Long? = null,
    
    // Feature Permissions (Independent of subscription)
    val canWatch: Boolean = true,
    val watchBan: Boolean = false,
    val canDownload: Boolean = true,
    val downloadBan: Boolean = false,
    val canChat: Boolean = true,
    val chatBan: Boolean = false,
    val canStory: Boolean = true,
    val storyBan: Boolean = false,
    val canP2P: Boolean = true,
    val p2pBan: Boolean = false,
    val canComment: Boolean = true,
    val canUpload: Boolean = true,
    val canRequest: Boolean = true,
    
    val offlineDaysOverride: Int? = null,
    val forcedAdsOverride: Int? = null,

    // Points wallet summary (Read-only from /users/{uid})
    val pointsBalance: Long = 0L,
    val totalPointsEarned: Long = 0L,
    val totalPointsSpent: Long = 0L
) {
    // Backwards compatibility aliases
    val pointsEarnedTotal: Long get() = totalPointsEarned
    val pointsSpentTotal: Long get() = totalPointsSpent
    val plan: String get() = subscriptionTier
    val maxDevices: Int? get() = deviceLimit

    val canonicalTier: CanonicalSubscriptionTier
        get() = SubscriptionNormalizer.normalizeSubscriptionTier(
            rawTier = subscriptionTier,
            rawIsPremium = isPremium,
            rawPlan = plan,
            rawRole = role
        )

    val isSubscriptionExpired: Boolean
        get() = subscriptionExpiresAt != null && subscriptionExpiresAt <= System.currentTimeMillis()

    /**
     * Canonical entitlement rule: Active subscription removes ads ONLY.
     */
    val isAdFree: Boolean
        get() = (canonicalTier != CanonicalSubscriptionTier.FREE) &&
                !isSubscriptionExpired &&
                !subscriptionStatus.equals("EXPIRED", ignoreCase = true) &&
                !subscriptionStatus.equals("CANCELED", ignoreCase = true)

    val isSubscriptionActive: Boolean
        get() = isAdFree

    val isBanActive: Boolean
        get() = (!isActive || isBanned) && (banExpiresAt == null || banExpiresAt > System.currentTimeMillis())

    val isWatchAllowed: Boolean get() = !isBanActive && canWatch && !watchBan
    val isDownloadAllowed: Boolean get() = !isBanActive && canDownload && !downloadBan
    val isChatAllowed: Boolean get() = !isBanActive && canChat && !chatBan
    val isStoryAllowed: Boolean get() = !isBanActive && canStory && !storyBan
    val isP2PAllowed: Boolean get() = !isBanActive && canP2P && !p2pBan
    // Comments are cancelled globally. This cannot be re-enabled by stale remote flags.
    val isCommentAllowed: Boolean get() = false
    val isUploadAllowed: Boolean get() = !isBanActive && canUpload
    val isRequestAllowed: Boolean get() = !isBanActive && canRequest

    val isAdmin: Boolean get() = role.equals("admin", true) || role.equals("superadmin", true)

    /**
     * Video and download quality is governed strictly by independent technical account limits,
     * NEVER by subscription status or tier.
     */
    fun isQualityAllowed(quality: String): Boolean {
        val maxQuality = allowedQuality?.let { extractResolutionNumber(it) } ?: return true
        val currentQ = extractResolutionNumber(quality) ?: return true
        return currentQ <= maxQuality
    }

    fun getMaxAllowedResolution(): Int? {
        return allowedQuality?.let { extractResolutionNumber(it) }
    }

    fun isDownloadLimitReached(currentCompletedDownloads: Int): Boolean {
        val limit = downloadLimit ?: return false
        return currentCompletedDownloads >= limit
    }

    companion object {
        fun extractResolutionNumber(qualityStr: String): Int? {
            val trimmed = qualityStr.trim()
            val lower = trimmed.lowercase()
            if (lower.contains("4k") || lower.contains("2160")) return 2160
            if (lower.contains("1440") || lower.contains("2k")) return 1440
            return trimmed.filter { it.isDigit() }.toIntOrNull()
        }

        /**
         * PHASE 22.8-C: Defensive permission parsing.
         * Enforces admin-controlled feature permissions:
         * - Respects exact Booleans
         * - Interprets standard active/enabled strings and blocks disabled/blocked/coming_soon
         * - Supports nested maps (Phase 22.8-A format)
         * - Fails closed on invalid/unrecognized values (safe behavior)
         * - Preserves defaultValue when field is absent/null
         */
        fun parsePermission(raw: Any?, defaultValue: Boolean = true): Boolean {
            if (raw == null) return defaultValue
            return when (raw) {
                is Boolean -> raw
                is Number -> raw.toInt() == 1
                is String -> {
                    val s = raw.trim()
                    when {
                        s.equals("true", ignoreCase = true) ||
                        s.equals("active", ignoreCase = true) ||
                        s.equals("enabled", ignoreCase = true) ||
                        s.equals("allow", ignoreCase = true) ||
                        s.equals("allowed", ignoreCase = true) ||
                        s.equals("1") -> true

                        s.equals("false", ignoreCase = true) ||
                        s.equals("disabled", ignoreCase = true) ||
                        s.equals("blocked", ignoreCase = true) ||
                        s.equals("restricted", ignoreCase = true) ||
                        s.equals("banned", ignoreCase = true) ||
                        s.equals("deny", ignoreCase = true) ||
                        s.equals("denied", ignoreCase = true) ||
                        s.equals("coming_soon", ignoreCase = true) ||
                        s.equals("0") -> false

                        // Malformed / invalid string: Fail-Closed safely
                        else -> false
                    }
                }
                is Map<*, *> -> {
                    val status = (raw["status"] ?: raw["state"])?.toString()?.trim()
                    val enabled = raw["enabled"] ?: raw["allowed"]
                    when {
                        status != null -> {
                            status.equals("ACTIVE", ignoreCase = true) ||
                            status.equals("ENABLED", ignoreCase = true) ||
                            status.equals("TRUE", ignoreCase = true) ||
                            status == "1"
                        }
                        enabled != null -> parsePermission(enabled, defaultValue = false)
                        raw["disabledMessage"] != null -> false
                        else -> false
                    }
                }
                else -> false
            }
        }
    }
}
