package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.Exclude
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * PHASE SUBSCRIPTION-POINTS-03B: CANONICAL ECONOMY & FEATURE CONFIG MODELS
 *
 * Invariants:
 * 1. Users App is the Execution / Consumption plane only.
 * 2. It may READ features, economy configs, points balance, and transactions.
 * 3. It MUST NEVER write or mutate economic balances, ledgers, or configs.
 */
enum class FeatureState {
    ACTIVE,
    COMING_SOON,
    DISABLED
}

@IgnoreExtraProperties
data class FeatureItemConfig(
    val status: FeatureState = FeatureState.ACTIVE,
    val disabledMessage: String? = null,
    val comingSoonMessage: String? = null
) {
    val isEnabled: Boolean
        @Exclude get() = status == FeatureState.ACTIVE
}

@IgnoreExtraProperties
data class FeaturesConfig(
    val subscriptions: FeatureState = FeatureState.ACTIVE,
    val points: FeatureState = FeatureState.ACTIVE,
    val dailyLogin: FeatureState = FeatureState.ACTIVE,
    val rewardedAds: FeatureState = FeatureState.ACTIVE,
    val tasks: FeatureState = FeatureState.ACTIVE,
    val leaderboard: FeatureState = FeatureState.ACTIVE,
    val disabledMessage: String = DEFAULT_DISABLED_MESSAGE,
    val featureDetails: Map<String, FeatureItemConfig> = emptyMap()
) {
    val subscriptionsItem: FeatureItemConfig
        @Exclude get() = featureDetails["subscriptions"] ?: FeatureItemConfig(subscriptions)

    val pointsItem: FeatureItemConfig
        @Exclude get() = featureDetails["points"] ?: FeatureItemConfig(points)

    val dailyLoginItem: FeatureItemConfig
        @Exclude get() = featureDetails["dailyLogin"] ?: FeatureItemConfig(dailyLogin)

    val rewardedAdsItem: FeatureItemConfig
        @Exclude get() = featureDetails["rewardedAds"] ?: FeatureItemConfig(rewardedAds)

    val tasksItem: FeatureItemConfig
        @Exclude get() = featureDetails["tasks"] ?: FeatureItemConfig(tasks)

    val leaderboardItem: FeatureItemConfig
        @Exclude get() = featureDetails["leaderboard"] ?: FeatureItemConfig(leaderboard)

    val uploadItem: FeatureItemConfig
        @Exclude get() = featureDetails["upload"]
            ?: featureDetails["canUpload"]
            ?: FeatureItemConfig()

    val requestsItem: FeatureItemConfig
        @Exclude get() = featureDetails["requests"]
            ?: featureDetails["canRequest"]
            ?: featureDetails["request"]
            ?: FeatureItemConfig()

    /** Comments remain unavailable regardless of stale or remotely edited feature config. */
    val commentsItem: FeatureItemConfig
        @Exclude get() = FeatureItemConfig(
            status = FeatureState.DISABLED,
            disabledMessage = COMMENTS_DISABLED_MESSAGE,
            comingSoonMessage = COMMENTS_DISABLED_MESSAGE
        )

    val isUploadAllowed: Boolean
        @Exclude get() = uploadItem.isEnabled

    val isRequestAllowed: Boolean
        @Exclude get() = requestsItem.isEnabled

    val isCommentAllowed: Boolean
        @Exclude get() = false

    fun getFeatureItem(featureKey: String): FeatureItemConfig {
        if (featureKey.trim().lowercase() in setOf("comments", "comment", "cancomment")) {
            return commentsItem
        }
        return featureDetails[featureKey] ?: when (featureKey) {
            "subscriptions" -> subscriptionsItem
            "points" -> pointsItem
            "dailyLogin" -> dailyLoginItem
            "rewardedAds" -> rewardedAdsItem
            "tasks" -> tasksItem
            "leaderboard" -> leaderboardItem
            "upload", "canUpload" -> uploadItem
            "requests", "canRequest", "request" -> requestsItem
            "comments", "canComment", "comment" -> commentsItem
            else -> FeatureItemConfig()
        }
    }

    fun getDisabledMessage(featureKey: String? = null): String {
        if (featureKey != null) {
            val itemMsg = getFeatureItem(featureKey).disabledMessage
            if (!itemMsg.isNullOrBlank()) return itemMsg
        }
        return disabledMessage.ifBlank { DEFAULT_DISABLED_MESSAGE }
    }

    fun getComingSoonMessage(featureKey: String? = null): String {
        if (featureKey != null) {
            val itemMsg = getFeatureItem(featureKey).comingSoonMessage
            if (!itemMsg.isNullOrBlank()) return itemMsg
        }
        return COMING_SOON_MESSAGE
    }

    companion object {
        const val DEFAULT_DISABLED_MESSAGE = "هذه الميزة غير متوفرة حالياً"
        const val COMING_SOON_MESSAGE = "هذه الميزة ستضاف قريبًا"
        const val COMMENTS_DISABLED_MESSAGE = "ميزة التعليقات غير مفعلة في CineStream."

        fun parseFeatureState(raw: Any?, fallback: FeatureState = FeatureState.COMING_SOON): FeatureState {
            if (raw == null) return fallback
            if (raw is FeatureState) return raw
            if (raw is Map<*, *>) {
                val statusVal = raw["status"] ?: raw["state"]
                if (statusVal != null) {
                    return parseFeatureState(statusVal, fallback)
                }
                if (raw["disabledMessage"] != null) return FeatureState.DISABLED
                if (raw["comingSoonMessage"] != null) return FeatureState.COMING_SOON
                return fallback
            }
            if (raw is Boolean) {
                return if (raw) FeatureState.ACTIVE else FeatureState.DISABLED
            }
            if (raw !is String && raw !is CharSequence) {
                return fallback
            }
            val str = raw.toString().trim().uppercase()
            return when (str) {
                "ACTIVE", "TRUE" -> FeatureState.ACTIVE
                "DISABLED", "FALSE" -> FeatureState.DISABLED
                "COMING_SOON" -> FeatureState.COMING_SOON
                else -> fallback
            }
        }

        fun parseFeatureItem(
            raw: Any?,
            defaultStatus: FeatureState = FeatureState.ACTIVE,
            fallback: FeatureState = FeatureState.COMING_SOON
        ): FeatureItemConfig {
            if (raw == null) {
                return FeatureItemConfig(status = defaultStatus)
            }
            if (raw is FeatureItemConfig) {
                return raw
            }
            if (raw is Map<*, *>) {
                val statusVal = raw["status"] ?: raw["state"]
                val status = if (statusVal != null) {
                    parseFeatureState(statusVal, fallback)
                } else if (raw["disabledMessage"] != null) {
                    FeatureState.DISABLED
                } else if (raw["comingSoonMessage"] != null) {
                    FeatureState.COMING_SOON
                } else {
                    fallback
                }
                val disabledMessage = (raw["disabledMessage"] as? String)?.trim()?.ifBlank { null }
                val comingSoonMessage = (raw["comingSoonMessage"] as? String)?.trim()?.ifBlank { null }
                return FeatureItemConfig(
                    status = status,
                    disabledMessage = disabledMessage,
                    comingSoonMessage = comingSoonMessage
                )
            }
            // Legacy direct string, boolean, or fallback
            val status = parseFeatureState(raw, fallback)
            return FeatureItemConfig(status = status)
        }
    }
}

@IgnoreExtraProperties
data class EconomyConfig(
    val redemptionCosts: Map<String, Long> = mapOf(
        "pro_lite_1d" to 50L,
        "pro_lite_7d" to 250L,
        "pro_lite_10d" to 350L,
        "pro_30d" to 1000L
    ),
    val dailyLoginRewards: List<Long> = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L),
    val rewardedAdPoints: Long = 15L,
    val rewardedAdDailyCap: Int = 5,
    val rewardedAdCooldownSeconds: Long = 300L
)

@IgnoreExtraProperties
data class PointTransaction(
    val id: String = "",
    val txId: String = "",
    val userId: String = "",
    val type: String = "", // DAILY_LOGIN, REWARDED_AD, TASK_REWARD, etc.
    val amount: Long = 0L,
    val balanceBefore: Long = 0L,
    val balanceAfter: Long = 0L,
    val referenceId: String? = null,
    val description: String = "",
    val createdAt: Long = 0L,
    val actorUid: String = ""
)

@IgnoreExtraProperties
data class RewardTask(
    val taskId: String = "",
    val title: String = "",
    val description: String = "",
    val rewardPoints: Long = 0L,
    val taskType: String = "",
    val actionUrl: String = "",
    val isActive: Boolean = true,
    val expiresAt: Long? = null
) {
    val isExpired: Boolean
        @Exclude get() = expiresAt != null && expiresAt < System.currentTimeMillis()
}

@IgnoreExtraProperties
data class LeaderboardEntry(
    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    val photoUrl: String = "",
    val email: String = "",
    val rank: Int = 0,
    val weeklyEarnedPoints: Long = 0L,
    val reward: String = "",
    val isActive: Boolean = true,
    val tier: String = "FREE",
    val isPremium: Boolean = false
)

@IgnoreExtraProperties
data class ProRequest(
    val requestId: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val userName: String = "",
    val planId: String = "",
    val requestedPlan: String = "pro",
    val requestedDuration: String = "",
    val durationDays: Int = 0,
    val paymentMethod: String = "",
    val paymentProofUrl: String = "",
    val paymentReference: String = "",
    val userNote: String = "",
    val notes: String = "",
    val status: String = "PENDING", // PENDING, APPROVED, REJECTED, CANCELLED
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val cancelledAt: Long? = null,
    val reviewedAt: Long? = null,
    val reviewedBy: String? = null,
    val reviewedByEmail: String? = null,
    val rejectionReason: String? = null,
    val adminNote: String? = null
) {
    fun toFirestoreMap(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val finalCreated = if (createdAt > 0L) createdAt else now
        val finalUpdated = if (updatedAt > 0L) updatedAt else now
        val map = mutableMapOf<String, Any?>(
            "requestId" to requestId,
            "userId" to userId,
            "userEmail" to userEmail,
            "userName" to userName,
            "planId" to planId,
            "requestedPlan" to requestedPlan,
            "requestedDuration" to requestedDuration,
            "durationDays" to durationDays.toLong(),
            "status" to status,
            "paymentReference" to paymentReference,
            "notes" to notes,
            "userNote" to (userNote.ifBlank { notes }),
            "createdAt" to finalCreated,
            "updatedAt" to finalUpdated
        )
        if (paymentMethod.isNotBlank()) {
            map["paymentMethod"] = paymentMethod
        }
        if (paymentProofUrl.isNotBlank()) {
            map["paymentProofUrl"] = paymentProofUrl
        }
        if (cancelledAt != null) {
            map["cancelledAt"] = cancelledAt
        }
        return map
    }

    companion object {
        fun fromDocument(doc: DocumentSnapshot): ProRequest {
            val requestId = doc.getString("requestId") ?: doc.id
            val userId = doc.getString("userId") ?: ""
            val userEmail = doc.getString("userEmail") ?: ""
            val userName = doc.getString("userName") ?: ""
            val planId = doc.getString("planId") ?: doc.getString("requestedPlan") ?: ""
            val requestedPlan = doc.getString("requestedPlan") ?: doc.getString("planId") ?: "pro"
            val durationDays = doc.getLong("durationDays")?.toInt() ?: 0
            val requestedDuration = doc.getString("requestedDuration") ?: if (durationDays > 0) "$durationDays days" else ""
            val paymentMethod = doc.getString("paymentMethod") ?: ""
            val paymentProofUrl = doc.getString("paymentProofUrl") ?: ""
            val paymentReference = doc.getString("paymentReference") ?: ""
            val userNote = doc.getString("userNote") ?: doc.getString("notes") ?: ""
            val notes = doc.getString("notes") ?: doc.getString("userNote") ?: ""
            val rawStatus = doc.getString("status")?.trim()?.uppercase() ?: "PENDING"
            val status = when (rawStatus) {
                "PENDING" -> "PENDING"
                "APPROVED" -> "APPROVED"
                "REJECTED" -> "REJECTED"
                "CANCELLED", "CANCELED" -> "CANCELLED"
                else -> rawStatus
            }
            val created = when (val c = doc.get("createdAt")) {
                is Timestamp -> c.toDate().time
                is Number -> c.toLong()
                else -> 0L
            }
            val updated = when (val u = doc.get("updatedAt")) {
                is Timestamp -> u.toDate().time
                is Number -> u.toLong()
                else -> 0L
            }
            val cancelled = when (val ca = doc.get("cancelledAt")) {
                is Timestamp -> ca.toDate().time
                is Number -> ca.toLong()
                else -> null
            }
            val reviewed = when (val r = doc.get("reviewedAt")) {
                is Timestamp -> r.toDate().time
                is Number -> r.toLong()
                else -> null
            }
            val reviewedBy = doc.getString("reviewedBy")
            val reviewedByEmail = doc.getString("reviewedByEmail")
            val rejectionReason = doc.getString("rejectionReason") ?: doc.getString("adminNote")
            val adminNote = doc.getString("adminNote") ?: doc.getString("rejectionReason")

            return ProRequest(
                requestId = requestId,
                userId = userId,
                userEmail = userEmail,
                userName = userName,
                planId = planId,
                requestedPlan = requestedPlan,
                requestedDuration = requestedDuration,
                durationDays = durationDays,
                paymentMethod = paymentMethod,
                paymentProofUrl = paymentProofUrl,
                paymentReference = paymentReference,
                userNote = userNote,
                notes = notes,
                status = status,
                createdAt = created,
                updatedAt = updated,
                cancelledAt = cancelled,
                reviewedAt = reviewed,
                reviewedBy = reviewedBy,
                reviewedByEmail = reviewedByEmail,
                rejectionReason = rejectionReason,
                adminNote = adminNote
            )
        }
    }
}
