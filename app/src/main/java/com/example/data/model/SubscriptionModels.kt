package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * PHASE SUBSCRIPTION-POINTS-03B: CANONICAL SUBSCRIPTION CONTRACT
 *
 * Invariant:
 * Subscription entitlement governs ONLY ad removal (REMOVE_ADS).
 * It does NOT grant, restrict, or modify video playback or download quality.
 */
enum class CanonicalSubscriptionTier {
    FREE,
    PRO_LITE,
    PRO
}

enum class CanonicalSubscriptionStatus {
    ACTIVE,
    EXPIRED,
    CANCELED,
    PENDING
}

enum class CanonicalSubscriptionSource {
    MONEY,
    POINTS,
    ADMIN_GRANT,
    LEGACY
}

/**
 * Domain representation of user subscription state.
 *
 * Absolute rule: No quality or download limit fields exist in this model.
 */
@IgnoreExtraProperties
data class UserSubscriptionState(
    val tier: CanonicalSubscriptionTier = CanonicalSubscriptionTier.FREE,
    val planId: String = "free",
    val durationDays: Int = 0,
    val status: CanonicalSubscriptionStatus = CanonicalSubscriptionStatus.ACTIVE,
    val source: CanonicalSubscriptionSource = CanonicalSubscriptionSource.LEGACY,
    val referenceId: String? = null,
    val startedAt: Long? = null,
    val expiresAt: Long? = null,
    val isActive: Boolean = false,
    val isAdFree: Boolean = false
)

object SubscriptionNormalizer {

    /**
     * Normalizes subscription tier according to canonical precedence:
     * 1. Canonical `subscriptionTier` field has highest priority.
     * 2. Legacy fields (`plan`, `proPlan`, `isPremium`, `isPro`) are consulted only when
     *    canonical `subscriptionTier` is absent or null.
     *
     * Example: subscriptionTier = "FREE", isPremium = true -> Result: FREE (stale legacy flag).
     */
    fun normalizeSubscriptionTier(
        rawTier: String?,
        rawIsPremium: Boolean? = null,
        rawPlan: String? = null,
        rawRole: String? = null
    ): CanonicalSubscriptionTier {
        if (!rawTier.isNullOrBlank()) {
            val upper = rawTier.trim().uppercase()
            return when (upper) {
                "FREE" -> CanonicalSubscriptionTier.FREE
                "PRO_LITE" -> CanonicalSubscriptionTier.PRO_LITE
                "PRO" -> CanonicalSubscriptionTier.PRO
                "PREMIUM" -> CanonicalSubscriptionTier.PRO
                "VIP" -> CanonicalSubscriptionTier.PRO
                else -> CanonicalSubscriptionTier.FREE
            }
        }

        // Secondary / fallback resolution from legacy fields
        val legacyPlan = rawPlan?.trim()?.uppercase()
        if (legacyPlan != null) {
            when (legacyPlan) {
                "FREE" -> return CanonicalSubscriptionTier.FREE
                "PRO_LITE" -> return CanonicalSubscriptionTier.PRO_LITE
                "PRO", "PREMIUM", "VIP" -> return CanonicalSubscriptionTier.PRO
            }
        }

        if (rawIsPremium == true) {
            return CanonicalSubscriptionTier.PRO
        }

        if (rawRole.equals("vip", ignoreCase = true)) {
            return CanonicalSubscriptionTier.PRO
        }

        return CanonicalSubscriptionTier.FREE
    }

    fun normalizeSubscriptionStatus(
        rawStatus: String?,
        expiresAt: Long?,
        tier: CanonicalSubscriptionTier,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): CanonicalSubscriptionStatus {
        if (tier == CanonicalSubscriptionTier.FREE) {
            return CanonicalSubscriptionStatus.ACTIVE
        }

        // Expiration is authoritative
        if (expiresAt != null && expiresAt <= currentTimeMillis) {
            return CanonicalSubscriptionStatus.EXPIRED
        }

        if (rawStatus.isNullOrBlank()) {
            return CanonicalSubscriptionStatus.ACTIVE
        }

        return when (rawStatus.trim().uppercase()) {
            "ACTIVE" -> CanonicalSubscriptionStatus.ACTIVE
            "EXPIRED" -> CanonicalSubscriptionStatus.EXPIRED
            "CANCELED", "CANCELLED" -> CanonicalSubscriptionStatus.CANCELED
            "PENDING" -> CanonicalSubscriptionStatus.PENDING
            else -> CanonicalSubscriptionStatus.ACTIVE
        }
    }

    fun normalizeSubscriptionSource(rawSource: String?): CanonicalSubscriptionSource {
        if (rawSource.isNullOrBlank()) return CanonicalSubscriptionSource.LEGACY
        return when (rawSource.trim().uppercase()) {
            "MONEY" -> CanonicalSubscriptionSource.MONEY
            "POINTS" -> CanonicalSubscriptionSource.POINTS
            "ADMIN_GRANT" -> CanonicalSubscriptionSource.ADMIN_GRANT
            "LEGACY" -> CanonicalSubscriptionSource.LEGACY
            else -> CanonicalSubscriptionSource.LEGACY
        }
    }

    fun parseUserSubscriptionState(
        tierStr: String?,
        planIdStr: String?,
        durationDaysVal: Int?,
        statusStr: String?,
        sourceStr: String?,
        refIdStr: String?,
        startedAtVal: Long?,
        expiresAtVal: Long?,
        legacyIsPremium: Boolean? = null,
        legacyPlan: String? = null,
        legacyRole: String? = null,
        currentTimeMillis: Long = System.currentTimeMillis()
    ): UserSubscriptionState {
        val tier = normalizeSubscriptionTier(
            rawTier = tierStr,
            rawIsPremium = legacyIsPremium,
            rawPlan = legacyPlan,
            rawRole = legacyRole
        )

        val status = normalizeSubscriptionStatus(
            rawStatus = statusStr,
            expiresAt = expiresAtVal,
            tier = tier,
            currentTimeMillis = currentTimeMillis
        )

        val source = normalizeSubscriptionSource(sourceStr)

        val isExpired = expiresAtVal != null && expiresAtVal <= currentTimeMillis
        val isActive = (tier != CanonicalSubscriptionTier.FREE) && !isExpired && (status == CanonicalSubscriptionStatus.ACTIVE)
        val isAdFree = isActive

        val planId = planIdStr?.ifBlank { null } ?: when (tier) {
            CanonicalSubscriptionTier.FREE -> "free"
            CanonicalSubscriptionTier.PRO_LITE -> when (durationDaysVal) {
                1 -> "pro_lite_1d"
                7 -> "pro_lite_7d"
                10 -> "pro_lite_10d"
                else -> "pro_lite_1d"
            }
            CanonicalSubscriptionTier.PRO -> "pro_30d"
        }

        val durationDays = durationDaysVal ?: when (planId) {
            "pro_lite_1d" -> 1
            "pro_lite_7d" -> 7
            "pro_lite_10d" -> 10
            "pro_30d" -> 30
            else -> 0
        }

        return UserSubscriptionState(
            tier = tier,
            planId = planId,
            durationDays = durationDays,
            status = status,
            source = source,
            referenceId = refIdStr,
            startedAt = startedAtVal,
            expiresAt = expiresAtVal,
            isActive = isActive,
            isAdFree = isAdFree
        )
    }

    fun fromDocument(doc: DocumentSnapshot, currentTimeMillis: Long = System.currentTimeMillis()): UserSubscriptionState {
        val tierStr = doc.getString("subscriptionTier")
        val planIdStr = doc.getString("planId")
        val durationDaysVal = doc.getLong("durationDays")?.toInt()
        val statusStr = doc.getString("subscriptionStatus")
        val sourceStr = doc.getString("subscriptionSource")
        val refIdStr = doc.getString("subscriptionReferenceId")

        val startedAtVal = when (val s = doc.get("subscriptionStartedAt")) {
            is Timestamp -> s.toDate().time
            is Number -> s.toLong()
            else -> null
        }

        val expiresAtVal = when (val e = doc.get("subscriptionExpiresAt") ?: doc.get("proExpiresAt")) {
            is Timestamp -> e.toDate().time
            is Number -> e.toLong()
            else -> null
        }

        val legacyIsPremium = doc.getBoolean("isPremium")
        val legacyPlan = doc.getString("plan") ?: doc.getString("proPlan")
        val legacyRole = doc.getString("role")

        return parseUserSubscriptionState(
            tierStr = tierStr,
            planIdStr = planIdStr,
            durationDaysVal = durationDaysVal,
            statusStr = statusStr,
            sourceStr = sourceStr,
            refIdStr = refIdStr,
            startedAtVal = startedAtVal,
            expiresAtVal = expiresAtVal,
            legacyIsPremium = legacyIsPremium,
            legacyPlan = legacyPlan,
            legacyRole = legacyRole,
            currentTimeMillis = currentTimeMillis
        )
    }
}
