package com.example.data.repository

import android.util.Log
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.UserRestrictions
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * PHASE SUBSCRIPTION-POINTS-03B: USER SECURITY & ENTITLEMENT MANAGER
 *
 * Invariant:
 * Subscription entitlement controls ONLY ad removal (isAdFree).
 * Video and download quality are decoupled from subscription tier.
 */
object UserSecurityManager {
    private const val TAG = "UserSecurityManager"
    const val OWNER_EMAIL = "sulopros01@gmail.com"
    private var listenerRegistration: ListenerRegistration? = null
    private var adminListenerRegistration: ListenerRegistration? = null

    private val _restrictionsFlow = MutableStateFlow(UserRestrictions())
    val restrictionsFlow: StateFlow<UserRestrictions> = _restrictionsFlow.asStateFlow()

    private val _isAdminDocFlow = MutableStateFlow(false)
    val isAdminDocFlow: StateFlow<Boolean> = _isAdminDocFlow.asStateFlow()

    private val _isAdminAuthorityFlow = MutableStateFlow(false)
    val isAdminAuthorityFlow: StateFlow<Boolean> = _isAdminAuthorityFlow.asStateFlow()

    fun isCanonicalAdmin(email: String? = null): Boolean {
        val effectiveEmail = email
            ?: try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email } catch (_: Throwable) { null }
            ?: try { AuthRepository.currentUserFlow.value?.email } catch (_: Throwable) { null }
        val isOwner = effectiveEmail?.trim()?.equals(OWNER_EMAIL, ignoreCase = true) == true
        return isOwner || _isAdminDocFlow.value
    }

    var restrictions: UserRestrictions = UserRestrictions()
        private set

    fun listenToUserSecurity(uid: String, onSecurityChanged: ((UserRestrictions) -> Unit)? = null): ListenerRegistration? {
        if (uid.isBlank()) {
            reset()
            return null
        }

        listenerRegistration?.remove()
        adminListenerRegistration?.remove()

        // 1. Listen to /admins/{uid} to verify enabled == true per Canonical Admin Contract
        adminListenerRegistration = FirebaseFirestore.getInstance()
            .collection("admins")
            .document(uid)
            .addSnapshotListener { adminSnap, adminErr ->
                if (adminErr != null) {
                    Log.w(TAG, "Admin doc listen error: ", adminErr)
                    return@addSnapshotListener
                }
                val isDocAdmin = adminSnap != null && adminSnap.exists() && (adminSnap.getBoolean("enabled") == true)
                _isAdminDocFlow.value = isDocAdmin
                _isAdminAuthorityFlow.value = isCanonicalAdmin()
                Log.d(TAG, "Admin status for $uid: enabled=$isDocAdmin, canonicalAdmin=${_isAdminAuthorityFlow.value}")
            }

        // 2. Listen to /users/{uid}
        listenerRegistration = FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Listen failed for user security: ", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val rawRole = snapshot.getString("role") ?: "user"

                    // Canonical and legacy subscription parsing with strict precedence
                    val subState = SubscriptionNormalizer.fromDocument(snapshot)

                    val rawSubTier = snapshot.getString("subscriptionTier")
                    val legacyPlan = snapshot.getString("plan") ?: snapshot.getString("proPlan")
                    val effectiveTierString = if (!rawSubTier.isNullOrBlank()) {
                        rawSubTier.trim().uppercase()
                    } else if (!legacyPlan.isNullOrBlank()) {
                        legacyPlan.trim().uppercase()
                    } else {
                        subState.tier.name
                    }

                    val subExpiresAt = when (val s = snapshot.get("subscriptionExpiresAt") ?: snapshot.get("proExpiresAt")) {
                        is Timestamp -> s.toDate().time
                        is Number -> s.toLong()
                        else -> null
                    }

                    val subStartedAt = when (val st = snapshot.get("subscriptionStartedAt")) {
                        is Timestamp -> st.toDate().time
                        is Number -> st.toLong()
                        else -> null
                    }

                    val explicitPrem = snapshot.getBoolean("isPremium") ?: (subState.tier != CanonicalSubscriptionTier.FREE)

                    val isActive = snapshot.getBoolean("isActive") ?: true
                    val deviceLimit = snapshot.getLong("deviceLimit")?.toInt()
                        ?: snapshot.getLong("maxDevices")?.toInt()

                    val isBanned = snapshot.getBoolean("isBanned") ?: false
                    val banReason = snapshot.getString("banReason") ?: ""
                    val banExpiresAt = when (val b = snapshot.get("banExpiresAt")) {
                        is Timestamp -> b.toDate().time
                        is Number -> b.toLong()
                        else -> null
                    }

                    // Read-only points balance
                    val pointsBal = snapshot.getLong("pointsBalance") ?: 0L
                    val totalEarned = (if (snapshot.contains("totalPointsEarned")) snapshot.getLong("totalPointsEarned") else null)
                        ?: snapshot.getLong("pointsEarnedTotal")
                        ?: 0L
                    val totalSpent = (if (snapshot.contains("totalPointsSpent")) snapshot.getLong("totalPointsSpent") else null)
                        ?: snapshot.getLong("pointsSpentTotal")
                        ?: 0L

                    val updated = UserRestrictions(
                        isActive = isActive,
                        isPremium = explicitPrem,
                        role = rawRole,
                        subscriptionTier = effectiveTierString,
                        subscriptionStatus = subState.status.name,
                        subscriptionExpiresAt = subExpiresAt,
                        planId = subState.planId,
                        durationDays = subState.durationDays,
                        subscriptionSource = subState.source.name,
                        subscriptionReferenceId = subState.referenceId,
                        subscriptionStartedAt = subStartedAt,
                        deviceLimit = deviceLimit,
                        allowedQuality = snapshot.getString("allowedQuality"),
                        downloadLimit = snapshot.getLong("downloadLimit")?.toInt(),
                        isBanned = isBanned,
                        banReason = banReason,
                        banExpiresAt = banExpiresAt,
                        canWatch = snapshot.getBoolean("canWatch") ?: true,
                        watchBan = snapshot.getBoolean("watchBan") ?: false,
                        canDownload = snapshot.getBoolean("canDownload") ?: true,
                        downloadBan = snapshot.getBoolean("downloadBan") ?: false,
                        canChat = snapshot.getBoolean("canChat") ?: true,
                        chatBan = snapshot.getBoolean("chatBan") ?: false,
                        canStory = snapshot.getBoolean("canStory") ?: true,
                        storyBan = snapshot.getBoolean("storyBan") ?: false,
                        canP2P = snapshot.getBoolean("canP2P") ?: true,
                        p2pBan = snapshot.getBoolean("p2pBan") ?: false,
                        canComment = UserRestrictions.parsePermission(snapshot.get("canComment"), true),
                        canUpload = UserRestrictions.parsePermission(snapshot.get("canUpload"), true),
                        canRequest = UserRestrictions.parsePermission(snapshot.get("canRequest"), true),
                        offlineDaysOverride = snapshot.getLong("offlineDaysOverride")?.toInt(),
                        forcedAdsOverride = snapshot.getLong("forcedAdsOverride")?.toInt(),
                        pointsBalance = pointsBal,
                        totalPointsEarned = totalEarned,
                        totalPointsSpent = totalSpent
                    )
                    restrictions = updated
                    _restrictionsFlow.value = updated
                    
                    // Full real-time sync with AuthRepository currentUserFlow
                    val fullUser = User.fromDocument(
                        doc = snapshot,
                        existingUser = AuthRepository.currentUserFlow.value
                    )
                    AuthRepository.currentUserFlow.value = fullUser

                    onSecurityChanged?.invoke(updated)
                    Log.d(TAG, "Updated user security restrictions for $uid: tier=${updated.canonicalTier}, isAdFree=${updated.isAdFree}, role=${updated.role}")
                }
            }

        return listenerRegistration
    }

    fun isListening(): Boolean = listenerRegistration != null || adminListenerRegistration != null

    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
        adminListenerRegistration?.remove()
        adminListenerRegistration = null
    }

    fun reset() {
        stopListening()
        restrictions = UserRestrictions()
        _restrictionsFlow.value = restrictions
        _isAdminDocFlow.value = false
        _isAdminAuthorityFlow.value = false
    }

    // Fast check helpers matching CineStream Admin rules
    fun canWatch(): Boolean = restrictions.isWatchAllowed
    fun canDownload(): Boolean = restrictions.isDownloadAllowed
    fun canChat(): Boolean = restrictions.isChatAllowed
    fun canPostStory(): Boolean = restrictions.isStoryAllowed
    fun canPostStories(): Boolean = restrictions.isStoryAllowed
    fun canUseP2P(): Boolean = restrictions.isP2PAllowed
    /** Comments were cancelled product-wide; remote user/config flags cannot enable them. */
    fun canComment(): Boolean = false

    fun canUpload(): Boolean {
        if (!restrictions.isUploadAllowed) return false
        val global = EconomyConfigRepository.featuresConfig.value
        if (!global.isUploadAllowed) return false
        return true
    }

    fun canRequest(): Boolean {
        if (!restrictions.isRequestAllowed) return false
        val global = EconomyConfigRepository.featuresConfig.value
        if (!global.isRequestAllowed) return false
        return true
    }

    fun getUploadRestrictionMessage(): String {
        val global = EconomyConfigRepository.featuresConfig.value
        val item = global.uploadItem
        if (item.status == com.example.data.model.FeatureState.DISABLED) {
            return global.getDisabledMessage("upload")
        }
        if (item.status == com.example.data.model.FeatureState.COMING_SOON) {
            return global.getComingSoonMessage("upload")
        }
        if (restrictions.isBanActive) {
            return if (restrictions.banReason.isNotBlank()) restrictions.banReason else "تم حظر حسابك من تنفيذ هذه العملية"
        }
        return "تم تعطيل صلاحية رفع الوسائط والملفات لحسابك من قبل الإدارة"
    }

    fun getRequestRestrictionMessage(): String {
        val global = EconomyConfigRepository.featuresConfig.value
        val item = global.requestsItem
        if (item.status == com.example.data.model.FeatureState.DISABLED) {
            return global.getDisabledMessage("requests")
        }
        if (item.status == com.example.data.model.FeatureState.COMING_SOON) {
            return global.getComingSoonMessage("requests")
        }
        if (restrictions.isBanActive) {
            return if (restrictions.banReason.isNotBlank()) restrictions.banReason else "تم حظر حسابك من تنفيذ هذه العملية"
        }
        return "تم تعطيل صلاحية تقديم الطلبات لحسابك من قبل الإدارة"
    }

    fun getCommentRestrictionMessage(): String =
        com.example.MyApplication.appContext.getString(com.example.R.string.comments_feature_disabled)

    fun enforceCanUpload(): Result<Unit> {
        return if (canUpload()) Result.success(Unit) else Result.failure(SecurityException(getUploadRestrictionMessage()))
    }

    fun enforceCanRequest(): Result<Unit> {
        return if (canRequest()) Result.success(Unit) else Result.failure(SecurityException(getRequestRestrictionMessage()))
    }

    fun enforceCanComment(): Result<Unit> {
        return if (canComment()) Result.success(Unit) else Result.failure(SecurityException(getCommentRestrictionMessage()))
    }

    fun isBanned(): Boolean = restrictions.isBanActive

    /**
     * Technical account restriction check:
     * NEVER considers subscription tier.
     */
    fun isQualityAllowed(quality: String): Boolean = restrictions.isQualityAllowed(quality)
    fun getMaxAllowedResolution(): Int? = restrictions.getMaxAllowedResolution()
    fun isDownloadLimitReached(currentDownloads: Int): Boolean = restrictions.isDownloadLimitReached(currentDownloads)
    fun getBanReason(): String = restrictions.banReason
    fun isAdmin(): Boolean = isCanonicalAdmin()

    @androidx.annotation.VisibleForTesting
    fun setTestRestrictions(testRestrictions: UserRestrictions) {
        restrictions = testRestrictions
        _restrictionsFlow.value = testRestrictions
    }

    @androidx.annotation.VisibleForTesting
    fun setTestAdmin(enabled: Boolean) {
        _isAdminDocFlow.value = enabled
        _isAdminAuthorityFlow.value = enabled
    }

    /**
     * Canonical entitlement: Ad removal only.
     */
    fun isAdFree(): Boolean = restrictions.isAdFree
    fun isVip(): Boolean = restrictions.isAdFree
    fun getSubscriptionTier(): CanonicalSubscriptionTier = restrictions.canonicalTier
    fun getPointsBalance(): Long = restrictions.pointsBalance

    suspend fun refreshUserSecurity(uid: String): Boolean {
        if (uid.isBlank()) return false
        return try {
            val userSnap = FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .get(com.google.firebase.firestore.Source.SERVER)
                .await()
            val adminSnap = FirebaseFirestore.getInstance()
                .collection("admins")
                .document(uid)
                .get(com.google.firebase.firestore.Source.SERVER)
                .await()
            _isAdminDocFlow.value = adminSnap != null && adminSnap.exists() && (adminSnap.getBoolean("enabled") == true)
            _isAdminAuthorityFlow.value = isCanonicalAdmin()
            userSnap != null && userSnap.exists() && !(userSnap.getBoolean("isBanned") ?: false)
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing user security from server: ${e.message}")
            false
        }
    }

    fun getForcedAdsRequired(defaultGlobal: Int = 5): Int {
        if (isAdFree()) return 0
        return restrictions.forcedAdsOverride ?: defaultGlobal
    }

    fun getOfflineDaysLimit(defaultGlobal: Int = 2): Int {
        return restrictions.offlineDaysOverride ?: defaultGlobal
    }
}
