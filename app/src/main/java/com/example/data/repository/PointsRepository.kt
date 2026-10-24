package com.example.data.repository

import android.util.Log
import com.example.data.model.LeaderboardEntry
import com.example.data.model.PointTransaction
import com.example.data.model.ProRequest
import com.example.data.model.RewardTask
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

/**
 * PHASE SUBSCRIPTION-POINTS-03B: POINTS & LEDGER REPOSITORY (READ-ONLY)
 *
 * Absolute rule:
 * Users App NEVER mutates pointsBalance, totalPointsEarned, totalPointsSpent,
 * or writes to point_transactions directly.
 * All mutations are server-authoritative.
 */
object PointsRepository {
    private const val TAG = "PointsRepository"
    private val db get() = FirebaseFirestore.getInstance()
    private val auth get() = FirebaseAuth.getInstance()

    /**
     * Observes the user's personal point transactions from:
     * /users/{uid}/point_transactions/{txId}
     *
     * Handles permission denied or missing subcollection safely by emitting empty list.
     */
    fun observePointTransactions(uid: String): Flow<List<PointTransaction>> = callbackFlow {
        if (uid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listener = db.collection("users").document(uid).collection("point_transactions")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Point transactions listen error (safe fallback): ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val items = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val amount = doc.getLong("amount") ?: 0L
                        val created = when (val c = doc.get("createdAt") ?: doc.get("serverTimestamp")) {
                            is Timestamp -> c.toDate().time
                            is Number -> c.toLong()
                            else -> 0L
                        }
                        PointTransaction(
                            id = doc.id,
                            txId = doc.getString("txId") ?: doc.id,
                            userId = doc.getString("userId") ?: uid,
                            type = doc.getString("type") ?: "UNKNOWN",
                            amount = amount,
                            balanceBefore = doc.getLong("balanceBefore") ?: 0L,
                            balanceAfter = doc.getLong("balanceAfter") ?: 0L,
                            referenceId = doc.getString("referenceId"),
                            description = doc.getString("description") ?: "",
                            createdAt = created
                        )
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                trySend(items)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Observes active reward tasks from /reward_tasks
     */
    fun observeRewardTasks(): Flow<List<RewardTask>> = callbackFlow {
        val listener = db.collection("reward_tasks")
            .whereEqualTo("isActive", true)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Reward tasks listen error: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                val tasks = snapshot?.documents?.mapNotNull { doc ->
                    try {
                        val data = doc.data ?: return@mapNotNull null
                        parseRewardTask(doc.id, data)
                    } catch (e: Exception) {
                        null
                    }
                } ?: emptyList()

                trySend(tasks)
            }

        awaitClose { listener.remove() }
    }

    /**
     * Observes weekly leaderboard from /leaderboard/weekly_current
     */
    fun observeWeeklyLeaderboard(): Flow<List<LeaderboardEntry>> = callbackFlow {
        val listener = db.collection("leaderboard").document("weekly_current")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Leaderboard listen error: ${error.message}")
                    trySend(emptyList())
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val rawList = (snapshot.get("rankings") ?: snapshot.get("entries") ?: snapshot.get("leaderboard")) as? List<*>
                    val entries = rawList?.mapNotNull { item ->
                        val map = item as? Map<*, *> ?: return@mapNotNull null
                        parseLeaderboardEntry(map)
                    } ?: emptyList()
                    trySend(entries)
                } else {
                    trySend(emptyList())
                }
            }

        awaitClose { listener.remove() }
    }

    fun parseRewardTask(taskId: String, data: Map<String, Any?>): RewardTask {
        val exp = when (val e = data["expiresAt"]) {
            is Timestamp -> e.toDate().time
            is Number -> e.toLong()
            is String -> e.trim().toLongOrNull()
            else -> null
        }
        val rawPoints = data["rewardPoints"] ?: data["points"] ?: data["reward"]
        val points = when (rawPoints) {
            is Number -> rawPoints.toLong()
            is String -> rawPoints.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        val rawActive = data["isActive"] ?: data["active"]
        val isActive = when (rawActive) {
            is Boolean -> rawActive
            is String -> rawActive.trim().toBooleanStrictOrNull() ?: true
            is Number -> rawActive.toInt() == 1
            else -> true
        }
        return RewardTask(
            taskId = taskId,
            title = data["title"]?.toString() ?: "",
            description = data["description"]?.toString() ?: "",
            rewardPoints = points,
            taskType = data["taskType"]?.toString() ?: "GENERAL",
            actionUrl = data["actionUrl"]?.toString() ?: "",
            isActive = isActive,
            expiresAt = exp
        )
    }

    fun parseLeaderboardEntry(map: Map<*, *>): LeaderboardEntry? {
        val userId = map["userId"]?.toString() ?: return null
        val rawPoints = map["weeklyEarnedPoints"] ?: map["points"] ?: map["score"]
        val points = when (rawPoints) {
            is Number -> rawPoints.toLong()
            is String -> rawPoints.trim().toLongOrNull() ?: 0L
            else -> 0L
        }
        val rawRank = map["rank"]
        val rank = when (rawRank) {
            is Number -> rawRank.toInt()
            is String -> rawRank.trim().toIntOrNull() ?: 0
            else -> 0
        }
        val rawActive = map["isActive"]
        val isActive = when (rawActive) {
            is Boolean -> rawActive
            is String -> rawActive.trim().toBooleanStrictOrNull() ?: true
            is Number -> rawActive.toInt() == 1
            else -> true
        }
        val tier = map["tier"]?.toString() ?: "FREE"
        val rawPremium = map["isPremium"]
        val isPremium = when (rawPremium) {
            is Boolean -> rawPremium
            is String -> rawPremium.trim().toBooleanStrictOrNull() ?: (tier.equals("PRO", ignoreCase = true))
            is Number -> rawPremium.toInt() == 1
            else -> tier.equals("PRO", ignoreCase = true)
        }
        return LeaderboardEntry(
            userId = userId,
            username = map["username"]?.toString() ?: "",
            displayName = map["displayName"]?.toString() ?: "",
            photoUrl = map["photoUrl"]?.toString() ?: "",
            rank = rank,
            weeklyEarnedPoints = points,
            reward = map["reward"]?.toString() ?: "",
            email = map["email"]?.toString() ?: "",
            isActive = isActive,
            tier = tier,
            isPremium = isPremium
        )
    }

    /**
     * Submits a pending Pro subscription request to /pro_requests/{id}.
     * The client NEVER approves itself or activates subscriptions directly.
     * Activation occurs only when Admin updates /users/{uid}.
     */
    suspend fun submitProRequest(
        planId: String,
        durationDays: Int,
        paymentReference: String,
        notes: String = "",
        paymentMethod: String? = null,
        paymentProofUrl: String? = null
    ): Result<String> {
        if (!UserSecurityManager.canRequest()) {
            val errorMsg = UserSecurityManager.getRequestRestrictionMessage()
            try { Log.w(TAG, "submitProRequest blocked by permissions: $errorMsg") } catch (_: Throwable) {}
            return Result.failure(SecurityException(errorMsg))
        }
        val currentUser = auth.currentUser ?: return Result.failure(IllegalStateException("User not signed in"))
        return try {
            val reqId = "req_${System.currentTimeMillis()}_${currentUser.uid.take(6)}"
            val now = System.currentTimeMillis()
            val userEmail = currentUser.email ?: ""
            val userName = currentUser.displayName ?: if (userEmail.isNotBlank()) userEmail.substringBefore("@") else "User"
            val requestedPlanName = when {
                planId.contains("lite", ignoreCase = true) -> "pro_lite"
                else -> "pro"
            }
            val requestedDurationDesc = when (durationDays) {
                1 -> "1 day"
                7 -> "7 days"
                10 -> "10 days"
                30 -> "1 month"
                else -> "$durationDays days"
            }
            val payload = mutableMapOf<String, Any?>(
                "requestId" to reqId,
                "userId" to currentUser.uid,
                "userEmail" to userEmail,
                "userName" to userName,
                "planId" to planId,
                "requestedPlan" to requestedPlanName,
                "requestedDuration" to requestedDurationDesc,
                "durationDays" to durationDays.toLong(),
                "status" to "PENDING",
                "paymentReference" to paymentReference,
                "notes" to notes,
                "userNote" to notes,
                "createdAt" to now,
                "updatedAt" to now
            )
            if (!paymentMethod.isNullOrBlank()) {
                payload["paymentMethod"] = paymentMethod
            }
            if (!paymentProofUrl.isNullOrBlank()) {
                payload["paymentProofUrl"] = paymentProofUrl
            }
            db.collection("pro_requests").document(reqId).set(payload).await()
            Result.success(reqId)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to submit pro request", e)
            Result.failure(e)
        }
    }

    suspend fun getUserProRequests(): List<ProRequest> {
        val currentUser = auth.currentUser ?: return emptyList()
        return try {
            val snapshot = db.collection("pro_requests")
                .whereEqualTo("userId", currentUser.uid)
                .get()
                .await()
            snapshot.documents.map { ProRequest.fromDocument(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Cancels an existing PENDING Pro subscription request owned by the current user.
     * In accordance with the shared security contract:
     * - Only transitions status from PENDING to CANCELLED.
     * - Only the request owner may cancel, using the canonical userId field enforced by Firestore rules.
     * - Requests already approved, rejected, or cancelled cannot be modified.
     */
    suspend fun cancelProRequest(requestId: String): Result<Unit> {
        val currentUser = auth.currentUser ?: return Result.failure(IllegalStateException("User not signed in"))
        return try {
            val reqRef = db.collection("pro_requests").document(requestId)
            val snapshot = reqRef.get().await()
            if (!snapshot.exists()) {
                return Result.failure(IllegalArgumentException("Request not found"))
            }
            val requestOwnerId = snapshot.getString("userId")
            if (requestOwnerId.isNullOrBlank()) {
                return Result.failure(IllegalStateException("Request is missing the canonical userId field"))
            }
            if (requestOwnerId != currentUser.uid) {
                return Result.failure(SecurityException("Cannot cancel request of another user"))
            }
            val currentStatus = snapshot.getString("status")?.uppercase()
            if (currentStatus != "PENDING") {
                return Result.failure(IllegalStateException("Cannot cancel request in status: $currentStatus"))
            }
            val now = System.currentTimeMillis()
            reqRef.update(
                mapOf(
                    "status" to "CANCELLED",
                    "cancelledAt" to now,
                    "updatedAt" to now
                )
            ).await()
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel pro request $requestId", e)
            Result.failure(e)
        }
    }
}
