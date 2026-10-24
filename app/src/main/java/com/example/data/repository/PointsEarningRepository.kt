package com.example.data.repository

import android.util.Log
import com.example.data.model.DailyLoginClaimRequest
import com.example.data.model.DailyLoginClaimResponse
import com.example.data.model.DailyLoginState
import com.example.data.model.PointWallet
import com.example.data.model.PointsEarningError
import com.example.data.model.PointsOperationResult
import com.example.data.model.RewardedAdClaimRequest
import com.example.data.model.RewardedAdClaimResponse
import com.example.data.model.RewardedAdState
import com.example.data.model.TaskRewardClaimRequest
import com.example.data.model.TaskRewardClaimResponse
import com.example.data.model.SubscriptionRedemptionRequest
import com.example.data.model.SubscriptionRedemptionResponse
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * PHASE 03D: POINTS EARNING ENGINE REPOSITORY
 *
 * Absolute Security Invariants:
 * 1. The Users App is NEVER the economic authority.
 * 2. Zero direct client writes to pointsBalance, totalPointsEarned, totalPointsSpent.
 * 3. Zero direct client writes to /users/{uid}/point_transactions/{txId}.
 * 4. Zero local minting or FieldValue.increment().
 * 5. Where a trusted backend does not exist, return explicit BACKEND_REQUIRED state.
 */
object PointsEarningRepository {
    private const val TAG = "PointsEarningRepository"
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    /**
     * Configurable trusted backend base URL (e.g. Cloudflare Worker endpoint).
     * If null/blank and no backendHandler is provided, calls fall back to
     * TemporaryFirebaseEconomyRepository when temporary economy mode is active.
     */
    var trustedBackendUrl: String? = null

    /**
     * Marker for temporary Firebase economy mode (Phase 04C).
     * Set to false to prevent client-authoritative writes against firestore.rules.
     * All earning and redemption operations require trusted backend authority.
     */
    const val TEMPORARY_ECONOMY_MODE = false
    var isTemporaryEconomyModeEnabled: Boolean = false

    /**
     * Pluggable backend request handler for testing or direct dispatch.
     * Takes endpoint, auth token, payload JSON -> returns HTTP status and response JSON.
     */
    var backendHandler: (suspend (endpoint: String, token: String, payloadJson: String) -> Pair<Int, String>)? = null

    /**
     * Observes the user's authoritative point wallet from /users/{uid}.
     * Read-only flow.
     */
    fun observeWallet(uid: String): Flow<PointWallet> = callbackFlow {
        if (uid.isBlank()) {
            trySend(PointWallet())
            close()
            return@callbackFlow
        }

        val listener = db.collection("users").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Failed to observe wallet for $uid: ${error.message}")
                    trySend(PointWallet())
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val bal = snapshot.getLong("pointsBalance") ?: 0L
                    val earned = (if (snapshot.contains("totalPointsEarned")) snapshot.getLong("totalPointsEarned") else null)
                        ?: snapshot.getLong("pointsEarnedTotal")
                        ?: 0L
                    val spent = (if (snapshot.contains("totalPointsSpent")) snapshot.getLong("totalPointsSpent") else null)
                        ?: snapshot.getLong("pointsSpentTotal")
                        ?: 0L
                    trySend(
                        PointWallet(
                            pointsBalance = bal,
                            totalPointsEarned = earned,
                            totalPointsSpent = spent,
                            isAuthoritative = true
                        )
                    )
                } else {
                    trySend(PointWallet())
                }
            }

        awaitClose { listener.remove() }
    }

    /**
     * Observes daily login status based on /users/{uid} and remote EconomyConfig ladder.
     */
    fun observeDailyLoginState(uid: String): Flow<DailyLoginState> {
        val userDocFlow: Flow<Pair<Int, String?>> = callbackFlow {
            if (uid.isBlank()) {
                trySend(Pair(0, null))
                close()
                return@callbackFlow
            }

            val listener = db.collection("users").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        trySend(Pair(0, null))
                        return@addSnapshotListener
                    }
                    val streak = snapshot.getLong("dailyStreak")?.toInt() ?: 0
                    val lastDate = snapshot.getString("lastDailyLoginDate")
                    trySend(Pair(streak, lastDate))
                }
            awaitClose { listener.remove() }
        }

        return combine(userDocFlow, EconomyConfigRepository.economyConfig) { (streak, lastDate), economy ->
            val todayUtc = getTodayUtcString()
            val isClaimedToday = lastDate == todayUtc
            val ladder = economy.dailyLoginRewards
            val nextRewardIndex = if (isClaimedToday) {
                streak % ladder.size
            } else {
                streak % ladder.size
            }
            val nextReward = if (ladder.isNotEmpty()) ladder[nextRewardIndex] else 5L

            DailyLoginState(
                currentStreak = streak,
                todayClaimed = isClaimedToday,
                nextReward = nextReward,
                rewardLadder = ladder,
                lastClaimDateUtc = lastDate
            )
        }
    }

    /**
     * Observes rewarded ad eligibility, cap, and cooldown for the user.
     */
    fun observeRewardedAdState(uid: String): Flow<RewardedAdState> {
        val userAdFlow: Flow<Pair<Int, Long>> = callbackFlow {
            if (uid.isBlank()) {
                trySend(Pair(0, 0L))
                close()
                return@callbackFlow
            }

            val listener = db.collection("users").document(uid)
                .addSnapshotListener { snapshot, error ->
                    if (error != null || snapshot == null || !snapshot.exists()) {
                        trySend(Pair(0, 0L))
                        return@addSnapshotListener
                    }
                    val count = snapshot.getLong("rewardedAdsWatchedToday")?.toInt() ?: 0
                    val lastWatch = snapshot.getLong("lastRewardedAdWatchedAt") ?: 0L
                    trySend(Pair(count, lastWatch))
                }
            awaitClose { listener.remove() }
        }

        return combine(userAdFlow, EconomyConfigRepository.economyConfig) { (watchedCount, lastWatch), economy ->
            val now = System.currentTimeMillis()
            val cooldownMillis = economy.rewardedAdCooldownSeconds * 1000L
            val elapsed = now - lastWatch
            val remainingCooldownSeconds = if (lastWatch > 0 && elapsed < cooldownMillis) {
                (cooldownMillis - elapsed) / 1000L
            } else {
                0L
            }
            val isEligible = watchedCount < economy.rewardedAdDailyCap && remainingCooldownSeconds <= 0L

            RewardedAdState(
                dailyWatchedCount = watchedCount,
                dailyCap = economy.rewardedAdDailyCap,
                rewardPerAd = economy.rewardedAdPoints,
                cooldownSecondsRemaining = remainingCooldownSeconds,
                isEligible = isEligible
            )
        }
    }

    /**
     * Observes user's completed task claims from /users/{uid}/task_claims.
     * Read-only. Returns map of taskId -> isClaimed.
     */
    fun observeUserTaskClaims(uid: String): Flow<Map<String, Boolean>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyMap())
            close()
            return@callbackFlow
        }

        val listener = db.collection("users").document(uid).collection("task_claims")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Task claims listen error: ${error.message}")
                    trySend(emptyMap())
                    return@addSnapshotListener
                }
                val claims = mutableMapOf<String, Boolean>()
                snapshot?.documents?.forEach { doc ->
                    val taskId = doc.getString("taskId") ?: doc.id
                    claims[taskId] = true
                }
                trySend(claims)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Daily Login Claim request.
     * Invariant: Client NEVER mints points or mutates balance directly.
     * Dispatches to trusted backend if configured, otherwise returns controlled BACKEND_REQUIRED.
     */
    suspend fun claimDailyLogin(request: DailyLoginClaimRequest): PointsOperationResult<DailyLoginClaimResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        if (currentUser.uid != request.userId) {
            return PointsOperationResult.Error(
                PointsEarningError.INVALID_REQUEST,
                PointsEarningError.INVALID_REQUEST.defaultMessage
            )
        }

        // 1. TRUSTED BACKEND AVAILABILITY CHECK:
        val payloadJson = """{"requestId":"${request.requestId}"}"""
        val backendResult = callBackendEndpoint("/api/earn/daily_login", payloadJson)
        if (backendResult != null) {
            val (statusCode, responseBody) = backendResult
            if (statusCode == -1) {
                return PointsOperationResult.PendingUnknown(request.requestId)
            }
            if (statusCode == 200) {
                val parsed = parseDailyLoginResponse(responseBody, request.requestId)
                return PointsOperationResult.Success(parsed, isAuthoritative = true)
            }
            val errorObj = parseErrorResponse(responseBody)
            return PointsOperationResult.Error(errorObj.first, errorObj.second)
        }

        // 2. TEMPORARY FIREBASE ECONOMY MODE FALLBACK:
        if (isTemporaryEconomyModeEnabled) {
            return TemporaryFirebaseEconomyRepository.claimDailyLogin(request)
        }

        return PointsOperationResult.Error(
            PointsEarningError.BACKEND_REQUIRED,
            PointsEarningError.BACKEND_REQUIRED.defaultMessage
        )
    }

    /**
     * Rewarded Ad Claim request.
     * Invariant: Ad completion callback is not economic authority.
     * Dispatches verification token to backend; fails if backend is not available or token is unverified.
     */
    suspend fun claimRewardedAd(request: RewardedAdClaimRequest): PointsOperationResult<RewardedAdClaimResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        if (currentUser.uid != request.userId) {
            return PointsOperationResult.Error(
                PointsEarningError.INVALID_REQUEST,
                PointsEarningError.INVALID_REQUEST.defaultMessage
            )
        }

        // 1. TRUSTED BACKEND AVAILABILITY CHECK:
        val tokenJson = if (request.verificationToken != null) """"verificationToken":"${request.verificationToken}"""" else """"verificationToken":null"""
        val payloadJson = """{"requestId":"${request.requestId}","adUnitId":"${request.adUnitId}",$tokenJson}"""
        val backendResult = callBackendEndpoint("/api/earn/rewarded_ad", payloadJson)
        if (backendResult != null) {
            val (statusCode, responseBody) = backendResult
            if (statusCode == -1) {
                return PointsOperationResult.PendingUnknown(request.requestId)
            }
            if (statusCode == 200) {
                val parsed = parseRewardedAdResponse(responseBody, request.requestId)
                return PointsOperationResult.Success(parsed, isAuthoritative = true)
            }
            val errorObj = parseErrorResponse(responseBody)
            return PointsOperationResult.Error(errorObj.first, errorObj.second)
        }

        // 2. TEMPORARY FIREBASE ECONOMY MODE FALLBACK:
        if (isTemporaryEconomyModeEnabled) {
            return TemporaryFirebaseEconomyRepository.claimRewardedAd(request)
        }

        return PointsOperationResult.Error(
            PointsEarningError.BACKEND_REQUIRED,
            PointsEarningError.BACKEND_REQUIRED.defaultMessage
        )
    }

    /**
     * Task Reward Claim request.
     * Invariant: Client never validates task completion or updates balance.
     */
    suspend fun claimTaskReward(request: TaskRewardClaimRequest): PointsOperationResult<TaskRewardClaimResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        if (currentUser.uid != request.userId) {
            return PointsOperationResult.Error(
                PointsEarningError.INVALID_REQUEST,
                PointsEarningError.INVALID_REQUEST.defaultMessage
            )
        }

        // 1. TRUSTED BACKEND AVAILABILITY CHECK:
        val payloadJson = """{"requestId":"${request.requestId}","taskId":"${request.taskId}"}"""
        val backendResult = callBackendEndpoint("/api/earn/task_reward", payloadJson)
        if (backendResult != null) {
            val (statusCode, responseBody) = backendResult
            if (statusCode == -1) {
                return PointsOperationResult.PendingUnknown(request.requestId)
            }
            if (statusCode == 200) {
                val parsed = parseTaskRewardResponse(responseBody, request.requestId, request.taskId)
                return PointsOperationResult.Success(parsed, isAuthoritative = true)
            }
            val errorObj = parseErrorResponse(responseBody)
            return PointsOperationResult.Error(errorObj.first, errorObj.second)
        }

        // 2. TEMPORARY FIREBASE ECONOMY MODE FALLBACK:
        if (isTemporaryEconomyModeEnabled) {
            return TemporaryFirebaseEconomyRepository.claimTaskReward(request)
        }

        return PointsOperationResult.Error(
            PointsEarningError.BACKEND_REQUIRED,
            PointsEarningError.BACKEND_REQUIRED.defaultMessage
        )
    }

    /**
     * Subscription Points Redemption (Phase 03F).
     * Invariant: Self-service instant activation via trusted backend.
     * Zero client-side economic authority in trusted mode; temporary fallback in Phase 04C.
     */
    suspend fun redeemSubscription(request: SubscriptionRedemptionRequest): PointsOperationResult<SubscriptionRedemptionResponse> {
        val currentUser = try { auth.currentUser } catch (e: Throwable) { null }
        if (currentUser == null && backendHandler == null && !isTemporaryEconomyModeEnabled) return PointsOperationResult.Error(
            PointsEarningError.AUTH_REQUIRED,
            PointsEarningError.AUTH_REQUIRED.defaultMessage
        )

        // 1. TRUSTED BACKEND AVAILABILITY CHECK:
        val payloadJson = """{"requestId":"${request.requestId}","sku":"${request.sku}"}"""
        val backendResult = callBackendEndpoint("/api/subscription/redeem", payloadJson)
        if (backendResult != null) {
            val (statusCode, responseBody) = backendResult
            if (statusCode == -1) {
                return PointsOperationResult.PendingUnknown(request.requestId)
            }
            if (statusCode == 200) {
                val parsed = parseSubscriptionRedemptionResponse(responseBody, request.requestId, request.sku)
                // Trigger authoritative refresh for user profile, security restrictions, and wallet
                if (currentUser != null) {
                    refreshAuthoritativeState(currentUser.uid)
                    UserSecurityManager.refreshUserSecurity(currentUser.uid)
                }
                return PointsOperationResult.Success(parsed, isAuthoritative = true)
            }
            val errorObj = parseErrorResponse(responseBody)
            return PointsOperationResult.Error(errorObj.first, errorObj.second)
        }

        // 2. TEMPORARY FIREBASE ECONOMY MODE FALLBACK:
        if (isTemporaryEconomyModeEnabled) {
            val tempResult = TemporaryFirebaseEconomyRepository.redeemSubscription(request)
            if (tempResult is PointsOperationResult.Success && currentUser != null) {
                refreshAuthoritativeState(currentUser.uid)
                UserSecurityManager.refreshUserSecurity(currentUser.uid)
            }
            return tempResult
        }

        return PointsOperationResult.Error(
            PointsEarningError.BACKEND_REQUIRED,
            PointsEarningError.BACKEND_REQUIRED.defaultMessage
        )
    }

    private suspend fun callBackendEndpoint(
        endpoint: String,
        payloadJson: String
    ): Pair<Int, String>? {
        val customHandler = backendHandler
        if (customHandler != null) {
            val token = try { auth.currentUser?.getIdToken(false)?.await()?.token ?: "test_token" } catch (e: Exception) { "test_token" }
            return customHandler(endpoint, token, payloadJson)
        }

        val baseUrl = trustedBackendUrl
        if (baseUrl.isNullOrBlank()) {
            return null
        }

        return try {
            val token = try { auth.currentUser?.getIdToken(false)?.await()?.token ?: "" } catch (e: Exception) { "" }
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build()

            val url = baseUrl.trimEnd('/') + endpoint
            val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
            val body = payloadJson.toRequestBody(mediaType)
            val reqBuilder = okhttp3.Request.Builder()
                .url(url)
                .post(body)

            if (token.isNotBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $token")
            }

            client.newCall(reqBuilder.build()).execute().use { resp ->
                val code = resp.code
                val respBody = resp.body?.string() ?: ""
                Pair(code, respBody)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Backend call exception on $endpoint: ${e.message}")
            Pair(-1, e.message ?: "Network error")
        }
    }

    private fun parseErrorResponse(json: String): Pair<PointsEarningError, String> {
        val errorCode = try {
            val match = Regex(""""errorCode"\s*:\s*"([^"]+)"""").find(json)
            match?.groupValues?.get(1) ?: "SERVER_REJECTED"
        } catch (e: Exception) { "SERVER_REJECTED" }

        val message = try {
            val match = Regex(""""message"\s*:\s*"([^"]+)"""").find(json)
            match?.groupValues?.get(1) ?: "Server error"
        } catch (e: Exception) { "Server error" }

        val error = when (errorCode) {
            "UNAUTHENTICATED" -> PointsEarningError.AUTH_REQUIRED
            "DAILY_LOGIN_ALREADY_CLAIMED", "ALREADY_CLAIMED" -> PointsEarningError.ALREADY_CLAIMED
            "DAILY_CAP_REACHED" -> PointsEarningError.DAILY_CAP_REACHED
            "COOLDOWN_ACTIVE" -> PointsEarningError.COOLDOWN_ACTIVE
            "BACKEND_VERIFICATION_REQUIRED", "REWARD_VERIFICATION_FAILED" -> PointsEarningError.BACKEND_REQUIRED
            "TASK_NOT_FOUND", "TASK_EXPIRED", "TASK_NOT_ELIGIBLE" -> PointsEarningError.TASK_EXPIRED
            "TASK_ALREADY_CLAIMED" -> PointsEarningError.TASK_ALREADY_CLAIMED
            "BALANCE_LIMIT_EXCEEDED", "BALANCE_LIMIT_ERROR" -> PointsEarningError.INVALID_REQUEST
            "FEATURE_DISABLED" -> PointsEarningError.FEATURE_DISABLED
            "FEATURE_COMING_SOON" -> PointsEarningError.FEATURE_COMING_SOON
            "DUPLICATE_REQUEST" -> PointsEarningError.DUPLICATE_REQUEST
            "INVALID_SKU" -> PointsEarningError.INVALID_SKU
            "SKU_NOT_REDEEMABLE" -> PointsEarningError.SKU_NOT_REDEEMABLE
            "INVALID_ECONOMY_CONFIG" -> PointsEarningError.INVALID_ECONOMY_CONFIG
            "INSUFFICIENT_POINTS" -> PointsEarningError.INSUFFICIENT_POINTS
            "SUBSCRIPTION_STATE_INVALID" -> PointsEarningError.SUBSCRIPTION_STATE_INVALID
            else -> PointsEarningError.SERVER_REJECTED
        }

        return Pair(error, message)
    }

    private fun parseSubscriptionRedemptionResponse(json: String, defaultRequestId: String, defaultSku: String): SubscriptionRedemptionResponse {
        val txId = Regex(""""transactionId"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
        val reqId = Regex(""""requestId"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: defaultRequestId
        val sku = Regex(""""sku"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: defaultSku
        val tier = Regex(""""tier"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "PRO_LITE"
        val duration = Regex(""""durationDays"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val cost = Regex(""""pointsCost"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val balanceBefore = Regex(""""balanceBefore"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val balanceAfter = Regex(""""balanceAfter"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val status = Regex(""""subscriptionStatus"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "ACTIVE"
        val startedAt = Regex(""""subscriptionStartedAt"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
        val expiresAt = Regex(""""subscriptionExpiresAt"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()
        val source = Regex(""""subscriptionSource"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: "POINTS"
        val serverTime = Regex(""""serverTimestamp"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: System.currentTimeMillis()

        return SubscriptionRedemptionResponse(
            requestId = reqId,
            transactionId = txId,
            transactionType = "SUBSCRIPTION_REDEMPTION",
            sku = sku,
            tier = tier,
            durationDays = duration,
            pointsCost = cost,
            balanceBefore = balanceBefore,
            balanceAfter = balanceAfter,
            subscriptionStatus = status,
            subscriptionStartedAt = startedAt,
            subscriptionExpiresAt = expiresAt,
            subscriptionSource = source,
            serverTimestamp = serverTime
        )
    }

    private fun parseDailyLoginResponse(json: String, defaultRequestId: String): DailyLoginClaimResponse {
        val amount = Regex(""""amount"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 5L
        val streak = Regex(""""streak"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val newBal = Regex(""""newBalance"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val txId = Regex(""""transactionId"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
        return DailyLoginClaimResponse(
            requestId = defaultRequestId,
            pointsAwarded = amount,
            newStreak = streak,
            nextReward = 5L,
            transactionId = txId,
            newBalance = newBal
        )
    }

    private fun parseRewardedAdResponse(json: String, defaultRequestId: String): RewardedAdClaimResponse {
        val amount = Regex(""""amount"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 15L
        val dailyCount = Regex(""""dailyCount"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val cooldown = Regex(""""cooldownSeconds"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 300L
        val newBal = Regex(""""newBalance"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val txId = Regex(""""transactionId"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
        return RewardedAdClaimResponse(
            requestId = defaultRequestId,
            pointsAwarded = amount,
            dailyCount = dailyCount,
            cooldownSeconds = cooldown,
            transactionId = txId,
            newBalance = newBal
        )
    }

    private fun parseTaskRewardResponse(json: String, defaultRequestId: String, taskId: String): TaskRewardClaimResponse {
        val amount = Regex(""""amount"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val newBal = Regex(""""newBalance"\s*:\s*([0-9]+)""").find(json)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val txId = Regex(""""transactionId"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: ""
        return TaskRewardClaimResponse(
            requestId = defaultRequestId,
            taskId = taskId,
            pointsAwarded = amount,
            transactionId = txId,
            newBalance = newBal
        )
    }

    /**
     * Refreshes the user document from Firestore to guarantee UI displays authoritative state.
     */
    suspend fun refreshAuthoritativeState(uid: String) {
        if (uid.isBlank()) return
        try {
            val doc = db.collection("users").document(uid).get().await()
            if (doc != null && doc.exists()) {
                val fullUser = com.example.data.repository.User.fromDocument(
                    doc = doc,
                    existingUser = AuthRepository.currentUserFlow.value
                )
                AuthRepository.currentUserFlow.value = fullUser
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing authoritative user state: ${e.message}")
        }
    }

    fun getTodayUtcString(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
