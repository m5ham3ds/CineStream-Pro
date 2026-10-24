package com.example.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.data.model.CanonicalSubscriptionTier
import com.example.data.model.SubscriptionNormalizer
import com.example.data.model.UserRestrictions
import com.example.data.session.SessionManager
import com.google.firebase.Timestamp
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed class AccountDeletionResult {
    object Success : AccountDeletionResult()
    object ReauthRequired : AccountDeletionResult()
    data class PartialFailure(val message: String) : AccountDeletionResult()
    data class Failed(val message: String) : AccountDeletionResult()
}

/**
 * Explicit result for username checks. UNKNOWN must never be interpreted as AVAILABLE.
 * The current client query checks only public profile projections; usernames held by
 * private profiles still require the separately approved reservation-index migration.
 */
enum class UsernameAvailability {
    AVAILABLE,
    TAKEN,
    UNKNOWN
}

@IgnoreExtraProperties
data class User(
    val uid: String = "",
    val id: String = "",
    val email: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val displayName: String = "",
    val username: String = "",
    val photoUrl: String = "",
    val bio: String = "",
    val isProfilePublic: Boolean = true,
    
    // Subscriptions & Roles (Admin managed)
    val role: String = "user",
    val subscriptionTier: String = "FREE",
    val isPremium: Boolean = false, // Kept for backward compatibility
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
    
    // Status & Bans (Admin managed)
    val isActive: Boolean = true,
    val isBanned: Boolean = false,
    val banReason: String = "",
    val banExpiresAt: Long? = null,
    
    // Feature Permissions (Admin managed)
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

    // Points balance summary (Read-only from /users/{uid})
    val pointsBalance: Long = 0L,
    val totalPointsEarned: Long = 0L,
    val totalPointsSpent: Long = 0L,
    
    // Timestamps
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastLoginAt: Long = 0L,
    val lastActiveAt: Long = 0L,
    val appVersion: String = ""
) {
    // Backwards-compatible aliases
    val pointsEarnedTotal: Long get() = totalPointsEarned
    val pointsSpentTotal: Long get() = totalPointsSpent
    val plan: String get() = subscriptionTier
    val maxDevices: Int? get() = deviceLimit
    val lastLoginTimestamp: Long get() = lastLoginAt

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
     * Canonical entitlement rule: Subscription grants ad removal ONLY.
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
    // Comments are cancelled globally. Keep canComment only as a legacy schema field.
    val isCommentAllowed: Boolean get() = false
    val isUploadAllowed: Boolean get() = !isBanActive && canUpload
    val isRequestAllowed: Boolean get() = !isBanActive && canRequest

    val isAdmin: Boolean get() = role.equals("admin", true) || role.equals("superadmin", true)

    /**
     * Quality checks are decoupled from subscription tier.
     */
    fun isQualityAllowed(quality: String): Boolean {
        val maxQuality = allowedQuality?.let { UserRestrictions.extractResolutionNumber(it) } ?: return true
        val currentQ = UserRestrictions.extractResolutionNumber(quality) ?: return true
        return currentQ <= maxQuality
    }

    fun getMaxAllowedResolution(): Int? {
        return allowedQuality?.let { UserRestrictions.extractResolutionNumber(it) }
    }

    fun isDownloadLimitReached(currentCompletedDownloads: Int): Boolean {
        val limit = downloadLimit ?: return false
        return currentCompletedDownloads >= limit
    }

    companion object {
        fun fromDocument(doc: DocumentSnapshot, existingUser: User? = null): User {
            val uid = doc.getString("uid") ?: doc.id
            val role = doc.getString("role") ?: existingUser?.role ?: "user"

            val subState = SubscriptionNormalizer.fromDocument(doc)

            val rawSubTier = doc.getString("subscriptionTier")
            val legacyPlan = doc.getString("plan") ?: doc.getString("proPlan")
            val effectiveTierString = if (!rawSubTier.isNullOrBlank()) {
                rawSubTier.trim().uppercase()
            } else if (!legacyPlan.isNullOrBlank()) {
                legacyPlan.trim().uppercase()
            } else {
                subState.tier.name
            }

            val subExp = when (val s = doc.get("subscriptionExpiresAt") ?: doc.get("proExpiresAt")) {
                is Timestamp -> s.toDate().time
                is Number -> s.toLong()
                else -> existingUser?.subscriptionExpiresAt
            }

            val subStarted = when (val st = doc.get("subscriptionStartedAt")) {
                is Timestamp -> st.toDate().time
                is Number -> st.toLong()
                else -> existingUser?.subscriptionStartedAt
            }

            val explicitPrem = doc.getBoolean("isPremium") ?: (subState.tier != CanonicalSubscriptionTier.FREE)

            val created = when (val c = doc.get("createdAt")) {
                is Timestamp -> c.toDate().time
                is Number -> c.toLong()
                else -> existingUser?.createdAt ?: 0L
            }

            val updated = when (val u = doc.get("updatedAt")) {
                is Timestamp -> u.toDate().time
                is Number -> u.toLong()
                else -> existingUser?.updatedAt ?: 0L
            }

            val lastLogin = when (val l = doc.get("lastLoginAt") ?: doc.get("lastLoginTimestamp")) {
                is Timestamp -> l.toDate().time
                is Number -> l.toLong()
                else -> existingUser?.lastLoginAt ?: 0L
            }

            val banExp = when (val b = doc.get("banExpiresAt")) {
                is Timestamp -> b.toDate().time
                is Number -> b.toLong()
                else -> existingUser?.banExpiresAt
            }

            val deviceLimit = doc.getLong("deviceLimit")?.toInt()
                ?: doc.getLong("maxDevices")?.toInt()
                ?: existingUser?.deviceLimit

            val pointsBal = doc.getLong("pointsBalance") ?: existingUser?.pointsBalance ?: 0L
            val totalEarned = (if (doc.contains("totalPointsEarned")) doc.getLong("totalPointsEarned") else null)
                ?: doc.getLong("pointsEarnedTotal")
                ?: existingUser?.totalPointsEarned
                ?: 0L
            val totalSpent = (if (doc.contains("totalPointsSpent")) doc.getLong("totalPointsSpent") else null)
                ?: doc.getLong("pointsSpentTotal")
                ?: existingUser?.totalPointsSpent
                ?: 0L

            val fbAuthUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
            val authEmail = if (uid == fbAuthUser?.uid) (fbAuthUser.email ?: "") else ""
            val authDisplayName = if (uid == fbAuthUser?.uid) (fbAuthUser.displayName ?: "") else ""
            val authPhoto = if (uid == fbAuthUser?.uid) (fbAuthUser.photoUrl?.toString() ?: "") else ""

            val firstName = (if (doc.contains("firstName")) doc.getString("firstName") else null)
                ?: existingUser?.firstName
                ?: ""
            val lastName = (if (doc.contains("lastName")) doc.getString("lastName") else null)
                ?: existingUser?.lastName
                ?: ""
            val rawDisplayName = doc.getString("displayName")?.takeIf { it.isNotBlank() }
                ?: doc.getString("name")?.takeIf { it.isNotBlank() }
                ?: "${firstName} ${lastName}".trim().takeIf { it.isNotBlank() }
                ?: existingUser?.displayName?.takeIf { it.isNotBlank() }
                ?: authDisplayName.takeIf { it.isNotBlank() }
                ?: ""

            val rawUsername = (if (doc.contains("username")) doc.getString("username") else null)
                ?: existingUser?.username
                ?: ""

            val rawEmail = doc.getString("email")?.takeIf { it.isNotBlank() }
                ?: existingUser?.email?.takeIf { it.isNotBlank() }
                ?: authEmail

            val rawPhotoUrl = (if (doc.contains("photoUrl")) doc.getString("photoUrl") else if (doc.contains("avatarUrl")) doc.getString("avatarUrl") else null)?.takeIf { it.isNotBlank() }
                ?: existingUser?.photoUrl?.takeIf { it.isNotBlank() }
                ?: authPhoto

            val rawBio = (if (doc.contains("bio")) doc.getString("bio") else null)
                ?: existingUser?.bio
                ?: ""

            return User(
                uid = uid,
                id = uid,
                email = rawEmail,
                firstName = firstName,
                lastName = lastName,
                displayName = rawDisplayName,
                username = rawUsername,
                photoUrl = rawPhotoUrl,
                bio = rawBio,
                isProfilePublic = doc.getBoolean("isProfilePublic") ?: existingUser?.isProfilePublic ?: true,
                role = role,
                subscriptionTier = effectiveTierString,
                isPremium = explicitPrem,
                subscriptionStatus = subState.status.name,
                subscriptionExpiresAt = subExp,
                planId = subState.planId,
                durationDays = subState.durationDays,
                subscriptionSource = subState.source.name,
                subscriptionReferenceId = subState.referenceId,
                subscriptionStartedAt = subStarted,
                deviceLimit = deviceLimit,
                allowedQuality = doc.getString("allowedQuality") ?: existingUser?.allowedQuality,
                downloadLimit = doc.getLong("downloadLimit")?.toInt() ?: existingUser?.downloadLimit,
                isActive = doc.getBoolean("isActive") ?: existingUser?.isActive ?: true,
                isBanned = doc.getBoolean("isBanned") ?: existingUser?.isBanned ?: false,
                banReason = doc.getString("banReason") ?: existingUser?.banReason ?: "",
                banExpiresAt = banExp,
                canWatch = doc.getBoolean("canWatch") ?: existingUser?.canWatch ?: true,
                watchBan = doc.getBoolean("watchBan") ?: existingUser?.watchBan ?: false,
                canDownload = doc.getBoolean("canDownload") ?: existingUser?.canDownload ?: true,
                downloadBan = doc.getBoolean("downloadBan") ?: existingUser?.downloadBan ?: false,
                canChat = doc.getBoolean("canChat") ?: existingUser?.canChat ?: true,
                chatBan = doc.getBoolean("chatBan") ?: existingUser?.chatBan ?: false,
                canStory = doc.getBoolean("canStory") ?: existingUser?.canStory ?: true,
                storyBan = doc.getBoolean("storyBan") ?: existingUser?.storyBan ?: false,
                canP2P = doc.getBoolean("canP2P") ?: existingUser?.canP2P ?: true,
                p2pBan = doc.getBoolean("p2pBan") ?: existingUser?.p2pBan ?: false,
                canComment = UserRestrictions.parsePermission(doc.get("canComment"), existingUser?.canComment ?: true),
                canUpload = UserRestrictions.parsePermission(doc.get("canUpload"), existingUser?.canUpload ?: true),
                canRequest = UserRestrictions.parsePermission(doc.get("canRequest"), existingUser?.canRequest ?: true),
                offlineDaysOverride = doc.getLong("offlineDaysOverride")?.toInt() ?: existingUser?.offlineDaysOverride,
                forcedAdsOverride = doc.getLong("forcedAdsOverride")?.toInt() ?: existingUser?.forcedAdsOverride,
                pointsBalance = pointsBal,
                totalPointsEarned = totalEarned,
                totalPointsSpent = totalSpent,
                createdAt = created,
                updatedAt = updated,
                lastLoginAt = lastLogin,
                lastActiveAt = doc.getLong("lastActiveAt") ?: existingUser?.lastActiveAt ?: 0L,
                appVersion = doc.getString("appVersion") ?: existingUser?.appVersion ?: ""
            )
        }
    }
}

object AuthRepository {

    val currentUserFlow = MutableStateFlow<User?>(null)
    private val _authProfileStateFlow = MutableStateFlow<AuthProfileState>(AuthProfileState.Loading)
    val authProfileStateFlow: StateFlow<AuthProfileState> = _authProfileStateFlow.asStateFlow()
    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    init {
        try {
            auth.addAuthStateListener { firebaseAuth ->
                val user = firebaseAuth.currentUser
                if (user != null) {
                    UserSecurityManager.listenToUserSecurity(user.uid)
                    EconomyConfigRepository.startListening()
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            getCurrentUser()
                        } catch (_: Exception) {}
                    }
                } else {
                    _authProfileStateFlow.value = AuthProfileState.Unauthenticated
                    currentUserFlow.value = null
                    UserSecurityManager.reset()
                    EconomyConfigRepository.stopListening()
                }
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Firebase listener initialization skipped: ${e.message}")
        }
    }

    suspend fun uploadProfilePicture(uid: String, uri: Uri): String {
        if (!UserSecurityManager.canUpload()) {
            throw SecurityException(UserSecurityManager.getUploadRestrictionMessage())
        }
        val cloudName = com.example.BuildConfig.CLOUDINARY_CLOUD_NAME
        val uploadPreset = com.example.BuildConfig.CLOUDINARY_UPLOAD_PRESET
        
        fun isMissingCloudinaryValue(value: String): Boolean =
            value.isBlank() || value.contains("YOUR_", ignoreCase = true) ||
                value.contains("mock", ignoreCase = true) || value.contains("dummy", ignoreCase = true) ||
                value.contains("example", ignoreCase = true) || value == "UNCONFIGURED"

        if (isMissingCloudinaryValue(cloudName) || isMissingCloudinaryValue(uploadPreset)) {
            throw Exception("Cloudinary configuration is missing. Please set CLOUDINARY_CLOUD_NAME and CLOUDINARY_UPLOAD_PRESET.")
        }

        try {
            com.cloudinary.android.MediaManager.get()
        } catch (e: Exception) {
            throw Exception("Cloudinary MediaManager not initialized.")
        }
        
        return suspendCancellableCoroutine<String> { continuation ->
            com.cloudinary.android.MediaManager.get().upload(uri)
                .unsigned(uploadPreset)
                .callback(object : com.cloudinary.android.callback.UploadCallback {
                    override fun onSuccess(requestId: String?, resultData: Map<*, *>?) {
                        val secureUrl = resultData?.get("secure_url") as? String
                        if (secureUrl != null) {
                            continuation.resume(secureUrl)
                        } else {
                            continuation.resumeWithException(Exception("Secure URL not found in Cloudinary response"))
                        }
                    }
                    
                    override fun onStart(requestId: String?) {}
                    override fun onProgress(requestId: String?, bytes: Long, totalBytes: Long) {}
                    override fun onError(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                        continuation.resumeWithException(Exception(error?.description ?: "Cloudinary upload error"))
                    }
                    override fun onReschedule(requestId: String?, error: com.cloudinary.android.callback.ErrorInfo?) {
                        continuation.resumeWithException(Exception("Upload rescheduled"))
                    }
                }).dispatch()
        }
    }

    suspend fun getCurrentUser(): User? {
        val firebaseUser = auth.currentUser
        if (firebaseUser == null) {
            _authProfileStateFlow.value = AuthProfileState.Unauthenticated
            currentUserFlow.value = null
            UserSecurityManager.reset()
            return null
        }
        return try {
            val snapshot = kotlinx.coroutines.withTimeout(15000) { db.collection("users").document(firebaseUser.uid).get().await() }
            if (snapshot.exists()) {
                val user = User.fromDocument(snapshot)
                currentUserFlow.value = user
                _authProfileStateFlow.value = AuthProfileState.AuthenticatedWithProfile(user)
                UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                // Keep activity timestamp updated
                try {
                    val appVersion = AppStartupManager.getCurrentVersionName(com.example.MyApplication.appContext)
                    db.collection("users").document(firebaseUser.uid).update(
                        mapOf(
                            "lastActiveAt" to System.currentTimeMillis(),
                            "appVersion" to appVersion
                        )
                    )
                } catch (_: Exception) {}
                user
            } else {
                // User document in /users/{uid} does not exist yet.
                // Do NOT mark user as unauthenticated! Emit AuthenticatedProfileMissing.
                Log.w("AuthRepository", "User document missing for uid=${firebaseUser.uid}. Emitting AuthenticatedProfileMissing.")
                _authProfileStateFlow.value = AuthProfileState.AuthenticatedProfileMissing(firebaseUser)
                val defaultUser = createDefaultUser(firebaseUser)
                try {
                    saveUser(defaultUser)
                } catch (e: Exception) {
                    Log.w("AuthRepository", "Failed to auto-save default user document: ${e.message}")
                }
                currentUserFlow.value = defaultUser
                UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                defaultUser
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Error fetching user document: ${e.message}")
            _authProfileStateFlow.value = AuthProfileState.ProfileLoadFailed(firebaseUser, e)
            // Resilient fallback: Never reset currentUserFlow to null when Firebase Auth is still signed in!
            val current = currentUserFlow.value
            if (current != null && current.uid == firebaseUser.uid) {
                current
            } else {
                val fallbackUser = createDefaultUser(firebaseUser)
                currentUserFlow.value = fallbackUser
                UserSecurityManager.listenToUserSecurity(firebaseUser.uid)
                fallbackUser
            }
        }
    }

    /**
     * Synthesizes a safe, non-destructive User instance from a FirebaseUser object.
     */
    fun createDefaultUser(firebaseUser: FirebaseUser): User {
        val email = firebaseUser.email ?: ""
        val displayName = firebaseUser.displayName ?: ""
        val username = if (email.isNotBlank()) {
            email.substringBefore("@")
        } else if (displayName.isNotBlank()) {
            displayName.lowercase().replace(" ", "_")
        } else {
            "user_" + firebaseUser.uid.take(5)
        }
        val firstName = displayName.substringBefore(" ").ifBlank { username }
        val lastName = displayName.substringAfter(" ", "")
        val now = System.currentTimeMillis()
        return User(
            uid = firebaseUser.uid,
            id = firebaseUser.uid,
            email = email,
            firstName = firstName,
            lastName = lastName,
            displayName = displayName.ifBlank { username },
            username = username,
            photoUrl = firebaseUser.photoUrl?.toString() ?: "",
            createdAt = now,
            lastLoginAt = now,
            lastActiveAt = now
        )
    }

    /**
     * Links a Google Auth credential directly to the active authenticated account.
     * Prevents account separation and keeps the user's UID and data intact.
     */
    suspend fun linkGoogleAccount(credential: AuthCredential): Result<User> {
        val firebaseUser = auth.currentUser ?: return Result.failure(IllegalStateException("No authenticated user"))
        return try {
            val authResult = firebaseUser.linkWithCredential(credential).await()
            val user = getCurrentUser() ?: createDefaultUser(authResult.user ?: firebaseUser)
            currentUserFlow.value = user
            Result.success(user)
        } catch (e: Exception) {
            Log.e("AuthRepository", "linkGoogleAccount failed", e)
            Result.failure(e)
        }
    }

    fun isGoogleLinked(): Boolean {
        val user = auth.currentUser ?: return false
        return user.providerData.any { it.providerId == "google.com" }
    }

    fun isPasswordLinked(): Boolean {
        val user = auth.currentUser ?: return false
        return user.providerData.any { it.providerId == "password" }
    }

    suspend fun linkCurrentUserWithGoogle(idToken: String): Result<User> {
        val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
        return linkGoogleAccount(credential)
    }

    /**
     * Signs in anonymously via Firebase Authentication or reuses an existing session.
     * Guaranteed to establish a valid Firebase user session for guest mode.
     */
    suspend fun signInAnonymously(): Result<User> {
        val existingUser = auth.currentUser
        if (existingUser != null) {
            Log.d("AuthRepository", "Reusing existing authenticated Firebase user: uid=${existingUser.uid}, isAnonymous=${existingUser.isAnonymous}")
            val user = getCurrentUser() ?: createDefaultUser(existingUser)
            currentUserFlow.value = user
            return Result.success(user)
        }

        anonymousSignInUserProvider?.let { provider ->
            return try {
                val appUser = provider.invoke()
                currentUserFlow.value = appUser
                Result.success(appUser)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

        return try {
            val authResult = kotlinx.coroutines.withTimeout(15000) {
                auth.signInAnonymously().await()
            }
            val newUser = authResult.user ?: auth.currentUser
            if (newUser != null) {
                Log.d("AuthRepository", "Firebase anonymous sign-in successful: uid=${newUser.uid}")
                val user = getCurrentUser() ?: createDefaultUser(newUser)
                currentUserFlow.value = user
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("Firebase anonymous sign-in succeeded but returned null user"))
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Firebase anonymous sign-in failed", e)
            Result.failure(e)
        }
    }

    /**
     * Saves or updates user document in /users/{uid} using SetOptions.merge().
     * Strictly protects Admin-managed and economic fields from being overwritten.
     */
    suspend fun saveUser(user: User) {
        val uid = if (user.uid.isNotBlank()) user.uid else (auth.currentUser?.uid ?: return)
        val appVersion = AppStartupManager.getCurrentVersionName(com.example.MyApplication.appContext)
        val resolvedName = "${user.firstName} ${user.lastName}".trim().ifBlank { user.username }

        val docRef = db.collection("users").document(uid)
        val existingDoc = try { docRef.get().await() } catch (_: Exception) { null }

        val userMap = mutableMapOf<String, Any>(
            "uid" to uid,
            "id" to uid,
            "email" to user.email,
            "firstName" to user.firstName,
            "lastName" to user.lastName,
            "displayName" to (if (user.displayName.isNotBlank()) user.displayName else resolvedName),
            "username" to user.username,
            "photoUrl" to user.photoUrl,
            "isProfilePublic" to user.isProfilePublic,
            "lastLoginAt" to FieldValue.serverTimestamp(),
            "lastLoginTimestamp" to System.currentTimeMillis(),
            "lastActiveAt" to System.currentTimeMillis(),
            "updatedAt" to FieldValue.serverTimestamp(),
            "appVersion" to appVersion
        )

        // Only for brand new user documents, initialize default non-privileged state
        if (existingDoc == null || !existingDoc.exists()) {
            userMap["createdAt"] = FieldValue.serverTimestamp()
            userMap["isActive"] = true
            userMap["role"] = "user"
            userMap["subscriptionTier"] = "FREE"
            userMap["planId"] = "free"
            userMap["plan"] = "free"
            userMap["durationDays"] = 0
            userMap["isPremium"] = false
            userMap["subscriptionStatus"] = "none"
            userMap["subscriptionSource"] = "LEGACY"
            userMap["pointsBalance"] = 0L
            userMap["totalPointsEarned"] = 0L
            userMap["totalPointsSpent"] = 0L
            userMap["pointsEarnedTotal"] = 0L
            userMap["pointsSpentTotal"] = 0L
            userMap["isBanned"] = false
            userMap["banReason"] = ""
            userMap["canWatch"] = true
            userMap["canDownload"] = true
            userMap["canChat"] = true
            userMap["canStory"] = true
            userMap["canP2P"] = true
            userMap["canComment"] = false
            userMap["canUpload"] = true
            userMap["canRequest"] = true
        }

        docRef.set(userMap, SetOptions.merge()).await()

        // Sync limited public profile projection to /public_profiles/{uid} adhering strictly to firestore.rules allowlist
        try {
            val publicProfileRef = db.collection("public_profiles").document(uid)
            val publicMap = mapOf<String, Any>(
                "uid" to uid,
                "username" to user.username,
                "firstName" to user.firstName,
                "lastName" to user.lastName,
                "photoUrl" to user.photoUrl,
                "isOnline" to true,
                "isProfilePublic" to user.isProfilePublic,
                "bio" to user.bio,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            publicProfileRef.set(publicMap).await()
        } catch (e: Exception) {
            Log.w("AuthRepository", "Failed updating public_profile projection: ${e.message}")
        }

        val updatedUser = if (existingDoc != null && existingDoc.exists()) {
            User.fromDocument(existingDoc).copy(
                uid = uid,
                email = user.email,
                firstName = user.firstName,
                lastName = user.lastName,
                displayName = userMap["displayName"] as String,
                username = user.username,
                photoUrl = user.photoUrl,
                isProfilePublic = user.isProfilePublic,
                updatedAt = System.currentTimeMillis(),
                lastActiveAt = System.currentTimeMillis()
            )
        } else {
            user.copy(uid = uid, role = "user", subscriptionTier = "FREE", planId = "free", isPremium = false, isBanned = false, isActive = true)
        }

        currentUserFlow.value = updatedUser
        UserSecurityManager.listenToUserSecurity(uid)
    }

    /**
     * Checks the public username namespace without asking Firestore to return private
     * profile documents. Firestore rules are not filters, so every query result must
     * satisfy isProfilePublic == true. Any request failure is returned as UNKNOWN.
     *
     * IMPORTANT: this is not yet a globally unique namespace: a private profile is
     * intentionally invisible here. Global uniqueness requires the separately reviewed
     * /usernames reservation index or a trusted registration service and migration.
     */
    suspend fun checkUsernameAvailability(
        username: String,
        currentUid: String
    ): UsernameAvailability {
        val normalized = username.trim().lowercase().replace(Regex("\\s+"), "")
        if (normalized.isBlank()) return UsernameAvailability.UNKNOWN

        return try {
            val snapshot = db.collection("public_profiles")
                .whereEqualTo("isProfilePublic", true)
                .whereEqualTo("username", normalized)
                .limit(2)
                .get()
                .await()

            if (snapshot.documents.any { it.id != currentUid }) {
                UsernameAvailability.TAKEN
            } else {
                UsernameAvailability.AVAILABLE
            }
        } catch (e: Exception) {
            Log.w("AuthRepository", "Username availability could not be verified: ${e.message}")
            UsernameAvailability.UNKNOWN
        }
    }

    /** Compatibility wrapper for existing call sites; UNKNOWN fails closed. */
    suspend fun isUsernameTaken(username: String, currentUid: String): Boolean =
        checkUsernameAvailability(username, currentUid) != UsernameAvailability.AVAILABLE

    suspend fun generateUniqueUsername(baseName: String): String {
        var base = baseName.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
        if (base.isEmpty()) base = "user"

        // Do not spin forever if Firestore is unavailable or every candidate is occupied.
        for (count in 0..99) {
            val candidate = if (count == 0) base else "${base}${count}"
            when (checkUsernameAvailability(candidate, "")) {
                UsernameAvailability.AVAILABLE -> return candidate
                UsernameAvailability.TAKEN -> continue
                UsernameAvailability.UNKNOWN -> throw IllegalStateException(
                    "Username availability could not be verified. Please retry when connected."
                )
            }
        }
        throw IllegalStateException("Unable to find an available username after 100 attempts.")
    }

    @androidx.annotation.VisibleForTesting
    var activeUserUidForTesting: String? = null

    @androidx.annotation.VisibleForTesting
    var firebaseUserDeleter: (suspend () -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    var userDocDeleter: (suspend (uid: String) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    var anonymousSignInUserProvider: (suspend () -> User)? = null

    @androidx.annotation.VisibleForTesting
    fun resetTestHooks() {
        activeUserUidForTesting = null
        firebaseUserDeleter = null
        userDocDeleter = null
        anonymousSignInUserProvider = null
    }

    suspend fun deleteAccount(context: Context): AccountDeletionResult {
        val firebaseUser = auth.currentUser
        val uid = firebaseUser?.uid ?: activeUserUidForTesting
        if (uid.isNullOrBlank()) {
            return AccountDeletionResult.Failed("No active user session found.")
        }

        if (!com.example.utils.NetworkUtils.isInternetAvailable(context)) {
            return AccountDeletionResult.Failed(context.getString(com.example.R.string.cannot_logout_offline))
        }

        return try {
            val userDoc = db.collection("users").document(uid)

            // 1. Delete user-owned Firestore subcollections while authenticated
            try {
                val fcmSnap = userDoc.collection("fcmTokens").get().await()
                for (doc in fcmSnap.documents) {
                    doc.reference.delete().await()
                }
            } catch (e: Exception) {
                Log.w("AuthRepository", "Failed deleting fcmTokens: ${e.message}")
            }

            try {
                val libSnap = userDoc.collection("library").get().await()
                for (doc in libSnap.documents) {
                    doc.reference.delete().await()
                }
            } catch (e: Exception) {
                Log.w("AuthRepository", "Failed deleting library: ${e.message}")
            }

            try {
                val histSnap = userDoc.collection("history").get().await()
                for (doc in histSnap.documents) {
                    doc.reference.delete().await()
                }
            } catch (e: Exception) {
                Log.w("AuthRepository", "Failed deleting history: ${e.message}")
            }

            try {
                val weSnap = userDoc.collection("watched_episodes").get().await()
                for (doc in weSnap.documents) {
                    doc.reference.delete().await()
                }
            } catch (e: Exception) {
                Log.w("AuthRepository", "Failed deleting watched_episodes: ${e.message}")
            }

            try {
                userDoc.collection("settings").document("notifications").delete().await()
            } catch (_: Exception) {}

            try {
                val storiesSnap = db.collection("stories").whereEqualTo("userId", uid).get().await()
                for (doc in storiesSnap.documents) {
                    doc.reference.delete().await()
                }
            } catch (_: Exception) {}

            // Delete public profile projection while authenticated
            try {
                db.collection("public_profiles").document(uid).delete().await()
            } catch (e: Exception) {
                Log.w("AuthRepository", "Failed deleting public_profiles projection: ${e.message}")
            }

            var docDeletionFailed = false
            if (userDocDeleter != null) {
                try {
                    userDocDeleter?.invoke(uid)
                } catch (e: Exception) {
                    Log.w("AuthRepository", "userDocDeleter hook failed, falling back to anonymization: ${e.message}")
                    docDeletionFailed = true
                }
            } else {
                try {
                    userDoc.delete().await()
                } catch (e: Exception) {
                    Log.w("AuthRepository", "Failed deleting userDoc, falling back to anonymization: ${e.message}")
                    docDeletionFailed = true
                    try {
                        userDoc.update(
                            mapOf(
                                "displayName" to "Deleted User",
                                "firstName" to "",
                                "lastName" to "",
                                "bio" to "",
                                "photoUrl" to "",
                                "isProfilePublic" to false,
                                "deletedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                    } catch (_: Exception) {}
                }
            }

            // 2. Permanently delete Firebase Authentication user
            if (firebaseUserDeleter != null) {
                try {
                    firebaseUserDeleter?.invoke()
                } catch (e: FirebaseAuthRecentLoginRequiredException) {
                    Log.w("AuthRepository", "Reauthentication required for user deletion")
                    return AccountDeletionResult.ReauthRequired
                } catch (e: Exception) {
                    Log.e("AuthRepository", "FirebaseAuth user deletion failed: ${e.message}")
                    return AccountDeletionResult.Failed(e.localizedMessage ?: "Failed to delete authentication account.")
                }
            } else if (firebaseUser != null) {
                try {
                    firebaseUser.delete().await()
                } catch (e: FirebaseAuthRecentLoginRequiredException) {
                    Log.w("AuthRepository", "Reauthentication required for user deletion")
                    return AccountDeletionResult.ReauthRequired
                } catch (e: Exception) {
                    Log.e("AuthRepository", "FirebaseAuth user deletion failed: ${e.message}")
                    return AccountDeletionResult.Failed(e.localizedMessage ?: "Failed to delete authentication account.")
                }
            }

            // 3. Clear all local user-scoped state and terminate session
            SessionManager.onUserLoggedOut(context)

            if (docDeletionFailed) {
                AccountDeletionResult.PartialFailure("User document could not be completely removed; anonymization fallback was applied.")
            } else {
                AccountDeletionResult.Success
            }
        } catch (e: Exception) {
            Log.e("AuthRepository", "Account deletion encountered critical error", e)
            AccountDeletionResult.Failed(e.localizedMessage ?: "Critical account deletion error.")
        }
    }

    suspend fun signOut(context: Context) {
        SocialRepository.clearCache()
        SessionManager.onUserLoggedOut(context)
    }

    fun signOut() {
        SessionManager.invalidateSession()
        try {
            auth.signOut()
        } catch (_: Exception) {}
        _authProfileStateFlow.value = AuthProfileState.Unauthenticated
        currentUserFlow.value = null
        UserSecurityManager.reset()
        EconomyConfigRepository.stopListening()
        SocialRepository.clearCache()
    }
}
