package com.example.data.model

import com.google.firebase.firestore.IgnoreExtraProperties
import java.util.UUID

/**
 * PHASE 03D: CANONICAL POINTS EARNING ENGINE MODELS
 *
 * Invariants:
 * 1. The Users App is NEVER the economic authority.
 * 2. Client code never directly creates transactions, mutates pointsBalance, or executes FieldValue.increment().
 * 3. All earning operations require trusted backend validation and atomic settlement.
 */

object CanonicalTransactionType {
    const val DAILY_LOGIN = "DAILY_LOGIN"
    const val REWARDED_AD = "REWARDED_AD"
    const val TASK_REWARD = "TASK_REWARD"
    /**
     * GAME_REWARD is a canonical transaction type reserved for trusted-authority settlement.
     * No game rewards may be minted client-side.
     */
    const val GAME_REWARD = "GAME_REWARD"
    const val LEADERBOARD_REWARD = "LEADERBOARD_REWARD"

    // Preserved canonical types (SUBSCRIPTION_REDEMPTION is OUT OF SCOPE for Phase 03D earning engine)
    const val SUBSCRIPTION_REDEMPTION = "SUBSCRIPTION_REDEMPTION"
    const val ADMIN_GRANT = "ADMIN_GRANT"
    const val ADMIN_ADJUSTMENT = "ADMIN_ADJUSTMENT"
    const val REVERSAL = "REVERSAL"
}

enum class PointsEarningError(val messageKey: String, val defaultMessage: String) {
    AUTH_REQUIRED("AUTH_REQUIRED", "يجب تسجيل الدخول لإجراء هذه العملية"),
    BACKEND_REQUIRED("BACKEND_REQUIRED", "يتطلب هذا الإجراء خدمة الخادم الموثوق (Backend Required)"),
    BACKEND_UNAVAILABLE("BACKEND_UNAVAILABLE", "خدمة الخادم غير متوفرة حالياً"),
    NETWORK_ERROR("NETWORK_ERROR", "حدث خطأ في الاتصال بالشبكة"),
    PERMISSION_DENIED("PERMISSION_DENIED", "ليس لديك صلاحية لتنفيذ هذا الإجراء"),
    INVALID_REQUEST("INVALID_REQUEST", "الطلب غير صالح"),
    SERVER_REJECTED("SERVER_REJECTED", "تم رفض الطلب من قبل الخادم"),
    DUPLICATE_REQUEST("DUPLICATE_REQUEST", "تم إرسال هذا الطلب مسبقاً"),
    ALREADY_CLAIMED("ALREADY_CLAIMED", "تم استلام هذه المكافأة بالفعل"),
    INSUFFICIENT_POINTS("INSUFFICIENT_POINTS", "رصيد النقاط غير كافٍ"),
    DAILY_CAP_REACHED("DAILY_CAP_REACHED", "تم الوصول إلى الحد الأقصى اليومي"),
    COOLDOWN_ACTIVE("COOLDOWN_ACTIVE", "يرجى الانتظار حتى انتهاء فترة التهدئة"),
    TASK_EXPIRED("TASK_EXPIRED", "هذه المهمة منتهية الصلاحية"),
    TASK_ALREADY_CLAIMED("TASK_ALREADY_CLAIMED", "تم استلام مكافأة هذه المهمة سابقاً"),
    FEATURE_DISABLED("FEATURE_DISABLED", "هذه الميزة غير متوفرة حالياً"),
    FEATURE_COMING_SOON("FEATURE_COMING_SOON", "هذه الميزة ستضاف قريبًا"),
    REWARD_UNAVAILABLE("REWARD_UNAVAILABLE", "المكافأة غير متاحة حالياً"),
    UNKNOWN_RESULT("UNKNOWN_RESULT", "حالة الطلب غير مؤكدة؛ جاري التحقق من الخادم"),
    INVALID_SKU("INVALID_SKU", "الخطة المحددة غير صالحة للاستبدال بالنقاط"),
    SKU_NOT_REDEEMABLE("SKU_NOT_REDEEMABLE", "هذه الخطة غير قابلة للاستبدال بالنقاط"),
    INVALID_ECONOMY_CONFIG("INVALID_ECONOMY_CONFIG", "خطأ في إعدادات الاقتصاد بالخادم"),
    SUBSCRIPTION_STATE_INVALID("SUBSCRIPTION_STATE_INVALID", "حالة الاشتراك الحالي لا تسمح بهذا الاستبدال")
}

sealed class PointsOperationResult<out T> {
    data class Success<out T>(val data: T, val isAuthoritative: Boolean = true) : PointsOperationResult<T>()
    data class PendingUnknown(val requestId: String, val message: String = "حالة الطلب غير مؤكدة، جاري المزامنة") : PointsOperationResult<Nothing>()
    data class Error(val error: PointsEarningError, val message: String) : PointsOperationResult<Nothing>()
}

@IgnoreExtraProperties
data class PointWallet(
    val pointsBalance: Long = 0L,
    val totalPointsEarned: Long = 0L,
    val totalPointsSpent: Long = 0L,
    val isAuthoritative: Boolean = true
) {
    // Backwards compatibility aliases
    val pointsEarnedTotal: Long get() = totalPointsEarned
    val pointsSpentTotal: Long get() = totalPointsSpent

    companion object {
        const val MAX_POINT_BALANCE = 1_000_000L
    }
}

data class DailyLoginClaimRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val userId: String,
    val clientTimestamp: Long = System.currentTimeMillis()
)

data class DailyLoginClaimResponse(
    val requestId: String,
    val pointsAwarded: Long,
    val newStreak: Int,
    val nextReward: Long,
    val transactionId: String = "",
    val newBalance: Long = 0L,
    val serverTimestamp: Long = 0L
)

@IgnoreExtraProperties
data class DailyLoginState(
    val currentStreak: Int = 0,
    val todayClaimed: Boolean = false,
    val nextReward: Long = 5L,
    val rewardLadder: List<Long> = listOf(10L, 15L, 20L, 25L, 30L, 40L, 50L),
    val lastClaimDateUtc: String? = null
)

data class RewardedAdClaimRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val userId: String,
    val adUnitId: String = "startapp_rewarded",
    val clientTimestamp: Long = System.currentTimeMillis(),
    val verificationToken: String? = null
)

data class RewardedAdClaimResponse(
    val requestId: String,
    val pointsAwarded: Long,
    val dailyCount: Int,
    val cooldownSeconds: Long,
    val transactionId: String = "",
    val newBalance: Long = 0L,
    val serverTimestamp: Long = 0L
)

@IgnoreExtraProperties
data class RewardedAdState(
    val dailyWatchedCount: Int = 0,
    val dailyCap: Int = 5,
    val rewardPerAd: Long = 15L,
    val cooldownSecondsRemaining: Long = 0L,
    val isEligible: Boolean = true
)

data class TaskRewardClaimRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val userId: String,
    val taskId: String,
    val clientTimestamp: Long = System.currentTimeMillis()
)

data class TaskRewardClaimResponse(
    val requestId: String,
    val taskId: String,
    val pointsAwarded: Long,
    val transactionId: String = "",
    val newBalance: Long = 0L,
    val serverTimestamp: Long = 0L
)

@IgnoreExtraProperties
data class TaskClaimDocument(
    val claimId: String = "",
    val taskId: String = "",
    val userId: String = "",
    val pointsAwarded: Long = 0L,
    val status: String = "COMPLETED",
    val claimedAt: Long = 0L
)

data class SubscriptionRedemptionRequest(
    val requestId: String = UUID.randomUUID().toString(),
    val sku: String
)

data class SubscriptionRedemptionResponse(
    val requestId: String,
    val transactionId: String,
    val transactionType: String = "SUBSCRIPTION_REDEMPTION",
    val sku: String,
    val tier: String,
    val durationDays: Int,
    val pointsCost: Long,
    val balanceBefore: Long,
    val balanceAfter: Long,
    val subscriptionStatus: String,
    val subscriptionStartedAt: Long,
    val subscriptionExpiresAt: Long,
    val subscriptionSource: String = "POINTS",
    val serverTimestamp: Long = 0L
)

