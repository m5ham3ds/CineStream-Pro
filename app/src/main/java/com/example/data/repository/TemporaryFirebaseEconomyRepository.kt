package com.example.data.repository

import android.util.Log
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.DailyLoginClaimRequest
import com.example.data.model.DailyLoginClaimResponse
import com.example.data.model.FeatureState
import com.example.data.model.FeaturesConfig
import com.example.data.model.PointsEarningError
import com.example.data.model.PointsOperationResult
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.model.RewardedAdClaimResponse
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.SubscriptionRedemptionRequest
import com.example.data.model.SubscriptionRedemptionResponse
import com.example.data.model.TaskRewardClaimRequest
import com.example.data.model.TaskRewardClaimResponse
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * PHASE 04C: TEMPORARY / NON-TRUSTED FIREBASE ECONOMY REPOSITORY
 *
 * CRITICAL ARCHITECTURAL DISCLOSURE:
 * This repository implements the CineStream economy operations directly against
 * Firebase/Firestore for client execution in TEMPORARY ECONOMY MODE.
 * It does NOT provide server-authoritative or tamper-proof guarantees.
 * When a Trusted Backend (Cloudflare Worker / Cloud Functions) is provisioned in the future,
 * the execution authority is swapped cleanly without altering the UI or domain layers.
 */
object TemporaryFirebaseEconomyRepository {
    private const val TAG = "TempEconomyRepo"

    /**
     * Marker constant indicating the temporary economy execution mode.
     */
    const val TEMPORARY_ECONOMY_MODE = true
    const val MODE_IDENTIFIER = "TEMPORARY_NON_TRUSTED_FIREBASE"

    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    private fun getTodayUtcString(timeMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(timeMillis))
    }

    private fun getYesterdayUtcString(timeMillis: Long = System.currentTimeMillis()): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return sdf.format(Date(timeMillis - 86400000L))
    }

    /**
     * Executes Daily Login claim directly against Firestore.
     * Enforces single daily claim per UTC day and maintains streak.
     */
    suspend fun claimDailyLogin(request: DailyLoginClaimRequest): PointsOperationResult<DailyLoginClaimResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        val uid = currentUser.uid
        if (uid != request.userId) {
            return PointsOperationResult.Error(
                PointsEarningError.INVALID_REQUEST,
                PointsEarningError.INVALID_REQUEST.defaultMessage
            )
        }

        // Feature flag check
        val featState = EconomyConfigRepository.featuresConfig.value.dailyLogin
        if (featState == FeatureState.DISABLED) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_DISABLED,
                EconomyConfigRepository.featuresConfig.value.getDisabledMessage("dailyLogin")
            )
        } else if (featState == FeatureState.COMING_SOON) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_COMING_SOON,
                EconomyConfigRepository.featuresConfig.value.getComingSoonMessage("dailyLogin")
            )
        }

        val todayUtc = getTodayUtcString()
        val yesterdayUtc = getYesterdayUtcString()
        val userRef = db.collection("users").document(uid)

        return try {
            val result = db.runTransaction { transaction ->
                val snapshot = transaction.get(userRef)
                if (!snapshot.exists()) {
                    throw IllegalStateException("USER_NOT_FOUND")
                }

                val lastClaimDate = snapshot.getString("lastDailyLoginDate")
                if (lastClaimDate == todayUtc) {
                    throw IllegalStateException("ALREADY_CLAIMED")
                }

                val lastClaimTimestamp = snapshot.getLong("lastDailyLoginTimestamp") ?: 0L
                val now = System.currentTimeMillis()
                if (lastClaimTimestamp > 0 && now < lastClaimTimestamp) {
                    throw IllegalStateException("CLOCK_ROLLBACK_DETECTED")
                }

                val currentStreakFromDoc = snapshot.getLong("dailyStreak")?.toInt() ?: 0
                val newStreak = if (lastClaimDate == yesterdayUtc) {
                    currentStreakFromDoc + 1
                } else {
                    1
                }

                val economy = EconomyConfigRepository.economyConfig.value
                val ladder = economy.dailyLoginRewards
                val rewardIndex = (newStreak - 1) % ladder.size
                val reward = if (ladder.isNotEmpty()) ladder[rewardIndex] else 10L

                val balanceBefore = snapshot.getLong("pointsBalance") ?: 0L
                val totalEarnedBefore = snapshot.getLong("totalPointsEarned") ?: 0L
                val balanceAfter = balanceBefore + reward
                val totalEarnedAfter = totalEarnedBefore + reward

                // 1. Update user document
                val userUpdates = mapOf<String, Any>(
                    "pointsBalance" to balanceAfter,
                    "totalPointsEarned" to totalEarnedAfter,
                    "dailyStreak" to newStreak,
                    "lastDailyLoginDate" to todayUtc,
                    "lastDailyLoginTimestamp" to System.currentTimeMillis()
                )
                transaction.update(userRef, userUpdates)

                // 2. Insert ledger record in subcollection /users/{uid}/point_transactions
                val txId = if (request.requestId.isNotBlank()) {
                    request.requestId
                } else {
                    "tx_daily_${todayUtc}_${System.currentTimeMillis()}"
                }
                val txRef = userRef.collection("point_transactions").document(txId)
                val txData = mapOf<String, Any>(
                    "id" to txId,
                    "txId" to txId,
                    "userId" to uid,
                    "type" to "DAILY_LOGIN",
                    "amount" to reward,
                    "balanceBefore" to balanceBefore,
                    "balanceAfter" to balanceAfter,
                    "referenceId" to todayUtc,
                    "description" to "Daily login reward (Day $newStreak)",
                    "createdAt" to System.currentTimeMillis()
                )
                transaction.set(txRef, txData)

                val nextRewardIndex = newStreak % ladder.size
                val nextReward = if (ladder.isNotEmpty()) ladder[nextRewardIndex] else 10L

                DailyLoginClaimResponse(
                    requestId = request.requestId,
                    pointsAwarded = reward,
                    newStreak = newStreak,
                    nextReward = nextReward,
                    transactionId = txId,
                    newBalance = balanceAfter,
                    serverTimestamp = System.currentTimeMillis()
                )
            }.await()

            PointsOperationResult.Success(result, isAuthoritative = false)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.w(TAG, "Temporary daily login claim error: $msg", e)
            when {
                msg.contains("CLOCK_ROLLBACK_DETECTED") -> PointsOperationResult.Error(
                    PointsEarningError.INVALID_REQUEST,
                    "تم اكتشاف تلاعب في وقت الجهاز، يرجى ضبط الوقت الصحيح"
                )
                msg.contains("ALREADY_CLAIMED") -> PointsOperationResult.Error(
                    PointsEarningError.ALREADY_CLAIMED,
                    "تم استلام المكافأة اليومية بالفعل لهذا اليوم"
                )
                msg.contains("USER_NOT_FOUND") -> PointsOperationResult.Error(
                    PointsEarningError.INVALID_REQUEST,
                    "لم يتم العثور على حساب المستخدم"
                )
                else -> PointsOperationResult.Error(
                    PointsEarningError.SERVER_REJECTED,
                    e.localizedMessage ?: "فشلت عملية استلام المكافأة اليومية"
                )
            }
        }
    }

    /**
     * Executes Task Reward claim directly against Firestore.
     * Verifies task validity, active state, expiry, and prevents duplicate claim.
     */
    suspend fun claimTaskReward(request: TaskRewardClaimRequest): PointsOperationResult<TaskRewardClaimResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        val uid = currentUser.uid
        if (uid != request.userId) {
            return PointsOperationResult.Error(
                PointsEarningError.INVALID_REQUEST,
                PointsEarningError.INVALID_REQUEST.defaultMessage
            )
        }

        // Feature flag check
        val featState = EconomyConfigRepository.featuresConfig.value.tasks
        if (featState == FeatureState.DISABLED) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_DISABLED,
                EconomyConfigRepository.featuresConfig.value.getDisabledMessage("tasks")
            )
        } else if (featState == FeatureState.COMING_SOON) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_COMING_SOON,
                EconomyConfigRepository.featuresConfig.value.getComingSoonMessage("tasks")
            )
        }

        val taskRef = db.collection("reward_tasks").document(request.taskId)
        val userRef = db.collection("users").document(uid)
        val claimRef = userRef.collection("task_claims").document(request.taskId)

        return try {
            val result = db.runTransaction { transaction ->
                val taskDoc = transaction.get(taskRef)
                if (!taskDoc.exists()) {
                    throw IllegalStateException("TASK_NOT_FOUND")
                }

                val isActive = taskDoc.getBoolean("isActive") ?: false
                if (!isActive) {
                    throw IllegalStateException("TASK_INACTIVE")
                }

                val expiresAt = when (val exp = taskDoc.get("expiresAt")) {
                    is Timestamp -> exp.toDate().time
                    is Number -> exp.toLong()
                    else -> null
                }
                if (expiresAt != null && expiresAt < System.currentTimeMillis()) {
                    throw IllegalStateException("TASK_EXPIRED")
                }

                val reward = taskDoc.getLong("rewardPoints") ?: 0L
                if (reward <= 0L) {
                    throw IllegalStateException("INVALID_TASK_REWARD")
                }

                val claimDoc = transaction.get(claimRef)
                if (claimDoc.exists()) {
                    throw IllegalStateException("TASK_ALREADY_CLAIMED")
                }

                val userDoc = transaction.get(userRef)
                if (!userDoc.exists()) {
                    throw IllegalStateException("USER_NOT_FOUND")
                }

                val balanceBefore = userDoc.getLong("pointsBalance") ?: 0L
                val totalEarnedBefore = userDoc.getLong("totalPointsEarned") ?: 0L
                val balanceAfter = balanceBefore + reward
                val totalEarnedAfter = totalEarnedBefore + reward

                // 1. Update user document
                transaction.update(
                    userRef,
                    mapOf(
                        "pointsBalance" to balanceAfter,
                        "totalPointsEarned" to totalEarnedAfter
                    )
                )

                // 2. Record task claim
                transaction.set(
                    claimRef,
                    mapOf(
                        "taskId" to request.taskId,
                        "userId" to uid,
                        "rewardPoints" to reward,
                        "claimedAt" to System.currentTimeMillis()
                    )
                )

                // 3. Create ledger record
                val txId = if (request.requestId.isNotBlank()) {
                    request.requestId
                } else {
                    "tx_task_${request.taskId}_${System.currentTimeMillis()}"
                }
                val txRef = userRef.collection("point_transactions").document(txId)
                val taskTitle = taskDoc.getString("title") ?: "Reward Task"
                transaction.set(
                    txRef,
                    mapOf(
                        "id" to txId,
                        "txId" to txId,
                        "userId" to uid,
                        "type" to "TASK_REWARD",
                        "amount" to reward,
                        "balanceBefore" to balanceBefore,
                        "balanceAfter" to balanceAfter,
                        "referenceId" to request.taskId,
                        "description" to "Task reward: $taskTitle",
                        "createdAt" to System.currentTimeMillis()
                    )
                )

                TaskRewardClaimResponse(
                    requestId = request.requestId,
                    taskId = request.taskId,
                    pointsAwarded = reward,
                    transactionId = txId,
                    newBalance = balanceAfter,
                    serverTimestamp = System.currentTimeMillis()
                )
            }.await()

            PointsOperationResult.Success(result, isAuthoritative = false)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.w(TAG, "Temporary task reward claim error: $msg", e)
            when {
                msg.contains("TASK_ALREADY_CLAIMED") -> PointsOperationResult.Error(
                    PointsEarningError.TASK_ALREADY_CLAIMED,
                    "تم استلام مكافأة هذه المهمة سابقاً"
                )
                msg.contains("TASK_NOT_FOUND") || msg.contains("TASK_EXPIRED") || msg.contains("TASK_INACTIVE") -> PointsOperationResult.Error(
                    PointsEarningError.TASK_EXPIRED,
                    "انتهت صلاحية المهمة أو أصبحت غير متوفرة"
                )
                msg.contains("INVALID_TASK_REWARD") -> PointsOperationResult.Error(
                    PointsEarningError.INVALID_REQUEST,
                    "بيانات مكافأة المهمة غير صالحة"
                )
                else -> PointsOperationResult.Error(
                    PointsEarningError.SERVER_REJECTED,
                    e.localizedMessage ?: "فشلت عملية استلام مكافأة المهمة"
                )
            }
        }
    }

    /**
     * Executes Rewarded Ad claim directly against Firestore.
     * Enforces daily watch cap and cooldown.
     */
    suspend fun claimRewardedAd(request: RewardedAdClaimRequest): PointsOperationResult<RewardedAdClaimResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        val uid = currentUser.uid
        if (uid != request.userId) {
            return PointsOperationResult.Error(
                PointsEarningError.INVALID_REQUEST,
                PointsEarningError.INVALID_REQUEST.defaultMessage
            )
        }

        // Feature flag check
        val featState = EconomyConfigRepository.featuresConfig.value.rewardedAds
        if (featState == FeatureState.DISABLED) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_DISABLED,
                EconomyConfigRepository.featuresConfig.value.getDisabledMessage("rewardedAds")
            )
        } else if (featState == FeatureState.COMING_SOON) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_COMING_SOON,
                EconomyConfigRepository.featuresConfig.value.getComingSoonMessage("rewardedAds")
            )
        }

        val economy = EconomyConfigRepository.economyConfig.value
        val reward = economy.rewardedAdPoints
        val dailyCap = economy.rewardedAdDailyCap
        val cooldownSeconds = economy.rewardedAdCooldownSeconds
        val cooldownMillis = cooldownSeconds * 1000L

        val userRef = db.collection("users").document(uid)
        val todayUtc = getTodayUtcString()

        return try {
            val result = db.runTransaction { transaction ->
                val userDoc = transaction.get(userRef)
                if (!userDoc.exists()) {
                    throw IllegalStateException("USER_NOT_FOUND")
                }

                val lastWatchDate = userDoc.getString("lastRewardedAdWatchedDate") ?: ""
                val rawWatchedCount = userDoc.getLong("rewardedAdsWatchedToday")?.toInt() ?: 0
                // Reset daily counter if day changed
                val watchedCount = if (lastWatchDate == todayUtc) rawWatchedCount else 0

                if (watchedCount >= dailyCap) {
                    throw IllegalStateException("DAILY_CAP_REACHED")
                }

                val lastWatchedAt = userDoc.getLong("lastRewardedAdWatchedAt") ?: 0L
                val now = System.currentTimeMillis()
                if (lastWatchedAt > 0 && now < lastWatchedAt) {
                    throw IllegalStateException("CLOCK_ROLLBACK_DETECTED")
                }
                val elapsed = now - lastWatchedAt
                if (lastWatchedAt > 0 && elapsed < cooldownMillis) {
                    throw IllegalStateException("COOLDOWN_ACTIVE")
                }

                val balanceBefore = userDoc.getLong("pointsBalance") ?: 0L
                val totalEarnedBefore = userDoc.getLong("totalPointsEarned") ?: 0L
                val balanceAfter = balanceBefore + reward
                val totalEarnedAfter = totalEarnedBefore + reward
                val newWatchedCount = watchedCount + 1

                transaction.update(
                    userRef,
                    mapOf(
                        "pointsBalance" to balanceAfter,
                        "totalPointsEarned" to totalEarnedAfter,
                        "rewardedAdsWatchedToday" to newWatchedCount,
                        "lastRewardedAdWatchedAt" to now,
                        "lastRewardedAdWatchedDate" to todayUtc
                    )
                )

                val txId = if (request.requestId.isNotBlank()) {
                    request.requestId
                } else {
                    "tx_ad_${System.currentTimeMillis()}"
                }
                val txRef = userRef.collection("point_transactions").document(txId)
                val existingTx = transaction.get(txRef)
                if (existingTx.exists()) {
                    throw IllegalStateException("DUPLICATE_REQUEST")
                }
                transaction.set(
                    txRef,
                    mapOf(
                        "id" to txId,
                        "txId" to txId,
                        "userId" to uid,
                        "type" to "REWARDED_AD",
                        "amount" to reward,
                        "balanceBefore" to balanceBefore,
                        "balanceAfter" to balanceAfter,
                        "referenceId" to request.adUnitId,
                        "description" to "Rewarded ad view ($newWatchedCount/$dailyCap)",
                        "createdAt" to now
                    )
                )

                RewardedAdClaimResponse(
                    requestId = request.requestId,
                    pointsAwarded = reward,
                    dailyCount = newWatchedCount,
                    cooldownSeconds = cooldownSeconds,
                    transactionId = txId,
                    newBalance = balanceAfter,
                    serverTimestamp = now
                )
            }.await()

            PointsOperationResult.Success(result, isAuthoritative = false)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.w(TAG, "Temporary rewarded ad claim error: $msg", e)
            when {
                msg.contains("DUPLICATE_REQUEST") -> PointsOperationResult.Error(
                    PointsEarningError.DUPLICATE_REQUEST,
                    "تمت معالجة مكافأة هذا الإعلان مسبقاً"
                )
                msg.contains("CLOCK_ROLLBACK_DETECTED") -> PointsOperationResult.Error(
                    PointsEarningError.INVALID_REQUEST,
                    "تم اكتشاف تلاعب في وقت الجهاز، يرجى ضبط الوقت الصحيح"
                )
                msg.contains("DAILY_CAP_REACHED") -> PointsOperationResult.Error(
                    PointsEarningError.DAILY_CAP_REACHED,
                    "تم الوصول إلى الحد الأقصى لمشاهدة الإعلانات اليوم ($dailyCap إعلانات)"
                )
                msg.contains("COOLDOWN_ACTIVE") -> PointsOperationResult.Error(
                    PointsEarningError.COOLDOWN_ACTIVE,
                    "يرجى الانتظار قبل مشاهدة إعلان آخر"
                )
                else -> PointsOperationResult.Error(
                    PointsEarningError.SERVER_REJECTED,
                    e.localizedMessage ?: "فشلت عملية استلام مكافأة الإعلان"
                )
            }
        }
    }

    /**
     * Executes Subscription Points Redemption directly against Firestore.
     * Enforces canonical pricing (50, 250, 350, 1000), checks balance, stacks same tier,
     * immediately upgrades PRO_LITE -> PRO, forbids PRO -> PRO_LITE downgrade,
     * and keeps subscription strictly decoupled from video streaming and download qualities.
     */
    suspend fun redeemSubscription(request: SubscriptionRedemptionRequest): PointsOperationResult<SubscriptionRedemptionResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        val uid = currentUser.uid

        // Feature flag check
        val featState = EconomyConfigRepository.featuresConfig.value.subscriptions
        if (featState == FeatureState.DISABLED) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_DISABLED,
                EconomyConfigRepository.featuresConfig.value.getDisabledMessage("subscriptions")
            )
        } else if (featState == FeatureState.COMING_SOON) {
            return PointsOperationResult.Error(
                PointsEarningError.FEATURE_COMING_SOON,
                EconomyConfigRepository.featuresConfig.value.getComingSoonMessage("subscriptions")
            )
        }

        // Canonical SKU validation
        val (targetTier, durationDays) = when (request.sku) {
            "pro_lite_1d" -> Pair(CanonicalSubscriptionTier.PRO_LITE, 1)
            "pro_lite_7d" -> Pair(CanonicalSubscriptionTier.PRO_LITE, 7)
            "pro_lite_10d" -> Pair(CanonicalSubscriptionTier.PRO_LITE, 10)
            "pro_30d" -> Pair(CanonicalSubscriptionTier.PRO, 30)
            else -> return PointsOperationResult.Error(
                PointsEarningError.INVALID_SKU,
                "باقة الاشتراك المحددة غير صالحة"
            )
        }

        // Price resolution: Priority 1 = remote economy config, Priority 2 = canonical fallback
        val costs = EconomyConfigRepository.economyConfig.value.redemptionCosts
        val cost = when (request.sku) {
            "pro_lite_1d" -> costs["pro_lite_1d"] ?: 50L
            "pro_lite_7d" -> costs["pro_lite_7d"] ?: 250L
            "pro_lite_10d" -> costs["pro_lite_10d"] ?: 350L
            "pro_30d" -> costs["pro_30d"] ?: 1000L
            else -> 0L
        }

        if (cost <= 0L) {
            return PointsOperationResult.Error(
                PointsEarningError.INVALID_ECONOMY_CONFIG,
                "إعدادات تسعير الباقة غير متوفرة"
            )
        }

        val userRef = db.collection("users").document(uid)

        return try {
            val result = db.runTransaction { transaction ->
                val userDoc = transaction.get(userRef)
                if (!userDoc.exists()) {
                    throw IllegalStateException("USER_NOT_FOUND")
                }

                val balanceBefore = userDoc.getLong("pointsBalance") ?: 0L
                if (balanceBefore < cost) {
                    throw IllegalStateException("INSUFFICIENT_POINTS")
                }

                val currentTierStr = userDoc.getString("subscriptionTier") ?: userDoc.getString("plan") ?: "FREE"
                val currentTier = SubscriptionNormalizer.normalizeSubscriptionTier(currentTierStr)
                val currentExpiresAt = when (val e = userDoc.get("subscriptionExpiresAt") ?: userDoc.get("proExpiresAt")) {
                    is Timestamp -> e.toDate().time
                    is Number -> e.toLong()
                    else -> 0L
                }

                val now = System.currentTimeMillis()
                val isCurrentlyActive = currentExpiresAt > now && (
                    userDoc.getString("subscriptionStatus") == "ACTIVE" ||
                    userDoc.getBoolean("isPremium") == true
                )

                // Stacking & Upgrade Semantics:
                // Active PRO users cannot downgrade to PRO_LITE
                if (currentTier == CanonicalSubscriptionTier.PRO && isCurrentlyActive && targetTier == CanonicalSubscriptionTier.PRO_LITE) {
                    throw IllegalStateException("SUBSCRIPTION_STATE_INVALID")
                }

                val (newStartedAt, newExpiresAt) = if (currentTier == targetTier && isCurrentlyActive) {
                    // Same tier extension: stack duration onto existing expiration
                    val started = when (val s = userDoc.get("subscriptionStartedAt")) {
                        is Timestamp -> s.toDate().time
                        is Number -> s.toLong()
                        else -> now
                    }
                    Pair(started, currentExpiresAt + (durationDays * 86400000L))
                } else {
                    // New subscription, re-activation after expiry, or upgrade from PRO_LITE to PRO
                    Pair(now, now + (durationDays * 86400000L))
                }

                val balanceAfter = balanceBefore - cost
                val totalSpentBefore = userDoc.getLong("totalPointsSpent") ?: 0L
                val totalSpentAfter = totalSpentBefore + cost

                // 1. Update user document atomically
                val userUpdates = mapOf<String, Any>(
                    "pointsBalance" to balanceAfter,
                    "totalPointsSpent" to totalSpentAfter,
                    // Canonical subscription fields
                    "subscriptionTier" to targetTier.name,
                    "planId" to request.sku,
                    "durationDays" to durationDays,
                    "subscriptionStatus" to "ACTIVE",
                    "subscriptionSource" to "POINTS",
                    "subscriptionReferenceId" to request.requestId,
                    "subscriptionStartedAt" to newStartedAt,
                    "subscriptionExpiresAt" to newExpiresAt,
                    // Legacy compatibility mirrors
                    "isPremium" to true,
                    "isPro" to true,
                    "plan" to targetTier.name.lowercase(),
                    "proPlan" to request.sku,
                    "proExpiresAt" to newExpiresAt
                )
                transaction.update(userRef, userUpdates)

                // 2. Insert ledger record in subcollection /users/{uid}/point_transactions
                val txId = if (request.requestId.isNotBlank()) {
                    request.requestId
                } else {
                    "tx_sub_${System.currentTimeMillis()}"
                }
                val txRef = userRef.collection("point_transactions").document(txId)
                val txData = mapOf<String, Any>(
                    "id" to txId,
                    "txId" to txId,
                    "userId" to uid,
                    "type" to "SUBSCRIPTION_REDEMPTION",
                    "amount" to -cost,
                    "balanceBefore" to balanceBefore,
                    "balanceAfter" to balanceAfter,
                    "referenceId" to request.sku,
                    "description" to "Redeemed ${targetTier.name} ($durationDays days)",
                    "createdAt" to now
                )
                transaction.set(txRef, txData)

                SubscriptionRedemptionResponse(
                    requestId = request.requestId,
                    transactionId = txId,
                    transactionType = "SUBSCRIPTION_REDEMPTION",
                    sku = request.sku,
                    tier = targetTier.name,
                    durationDays = durationDays,
                    pointsCost = cost,
                    balanceBefore = balanceBefore,
                    balanceAfter = balanceAfter,
                    subscriptionStatus = "ACTIVE",
                    subscriptionStartedAt = newStartedAt,
                    subscriptionExpiresAt = newExpiresAt,
                    subscriptionSource = "POINTS",
                    serverTimestamp = now
                )
            }.await()

            // Trigger real-time user security refresh
            UserSecurityManager.listenToUserSecurity(uid)

            PointsOperationResult.Success(result, isAuthoritative = false)
        } catch (e: Exception) {
            val msg = e.message ?: ""
            Log.w(TAG, "Temporary subscription redemption error: $msg", e)
            when {
                msg.contains("INSUFFICIENT_POINTS") -> PointsOperationResult.Error(
                    PointsEarningError.INSUFFICIENT_POINTS,
                    "رصيد النقاط غير كافٍ للاشتراك في هذه الباقة"
                )
                msg.contains("SUBSCRIPTION_STATE_INVALID") -> PointsOperationResult.Error(
                    PointsEarningError.SUBSCRIPTION_STATE_INVALID,
                    "لا يمكن تخفيض اشتراك PRO النشط إلى PRO LITE"
                )
                msg.contains("USER_NOT_FOUND") -> PointsOperationResult.Error(
                    PointsEarningError.INVALID_REQUEST,
                    "لم يتم العثور على حساب المستخدم"
                )
                else -> PointsOperationResult.Error(
                    PointsEarningError.SERVER_REJECTED,
                    e.localizedMessage ?: "فشلت عملية استبدال النقاط بالاشتراك"
                )
            }
        }
    }
}
