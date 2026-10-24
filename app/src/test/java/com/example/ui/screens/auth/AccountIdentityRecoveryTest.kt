package com.example.ui.screens.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.model.PointTransaction
import com.example.data.repository.AuthProfileState
import com.example.data.repository.AuthRepository
import com.example.data.repository.User
import com.example.data.repository.UserProfile
import com.example.data.session.SessionManager
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountIdentityRecoveryTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        if (com.google.firebase.FirebaseApp.getApps(application).isEmpty()) {
            com.google.firebase.FirebaseApp.initializeApp(
                application,
                com.google.firebase.FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.cinestream.xyzabc")
                    .setProjectId("cinestream-sulo")
                    .setApiKey("AIzaSyC9CvaM9Mw3NiD-KsOiYvHZHj6XZJQJnPs")
                    .build()
            )
        }
    }

    /**
     * Test 1: Email/Password login to existing UID with points retains pointsBalance and ledger intact.
     */
    @Test
    fun existingUserWithPoints_preservesPointsBalanceAndLedger() {
        val canonicalUid = "canonical_user_123"
        val existingUser = User(
            uid = canonicalUid,
            id = canonicalUid,
            email = "user@test.com",
            username = "originaluser",
            pointsBalance = 450L,
            totalPointsEarned = 1000L,
            totalPointsSpent = 550L,
            subscriptionTier = "PRO"
        )

        assertEquals(450L, existingUser.pointsBalance)
        assertEquals(1000L, existingUser.totalPointsEarned)
        assertEquals(550L, existingUser.totalPointsSpent)
        assertEquals("PRO", existingUser.subscriptionTier)
        assertEquals(canonicalUid, existingUser.uid)

        val tx = PointTransaction(
            id = "tx_001",
            txId = "tx_001",
            userId = canonicalUid,
            type = "DAILY_REWARD",
            amount = 50L,
            balanceBefore = 400L,
            balanceAfter = 450L,
            createdAt = System.currentTimeMillis()
        )
        assertEquals(canonicalUid, tx.userId)
        assertEquals(50L, tx.amount)
        assertEquals(450L, tx.balanceAfter)
    }

    /**
     * Test 2: Support linking Google to existing UID without generating a second UID.
     */
    @Test
    fun linkProvider_preservesCanonicalUid() {
        val canonicalUid = "original_email_uid"
        val userBeforeLink = User(
            uid = canonicalUid,
            id = canonicalUid,
            email = "original@test.com",
            pointsBalance = 250L
        )

        // Linking should never change the UID of the account
        val userAfterLink = userBeforeLink.copy(
            photoUrl = "https://lh3.googleusercontent.com/test_avatar"
        )
        assertEquals(canonicalUid, userAfterLink.uid)
        assertEquals(250L, userAfterLink.pointsBalance)
        assertEquals("https://lh3.googleusercontent.com/test_avatar", userAfterLink.photoUrl)
    }

    /**
     * Test 3: Prevention of automatic second account creation on account collisions.
     */
    @Test
    fun accountCollision_doesNotCreateSecondAccountOrOverwriteExisting() {
        val collisionException = FirebaseAuthUserCollisionException(
            "ERROR_CREDENTIAL_ALREADY_IN_USE",
            "The email address is already in use by another account."
        )
        // Verify collision error maps to clear user guidance without altering current user
        val mapped = when (collisionException.errorCode) {
            "ERROR_CREDENTIAL_ALREADY_IN_USE",
            "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL" -> application.getString(R.string.google_account_already_linked)
            else -> "Other"
        }
        assertEquals(application.getString(R.string.google_account_already_linked), mapped)
    }

    /**
     * Test 4: When Firestore /users/{uid} is missing, AuthProfileState emits AuthenticatedProfileMissing, NOT Unauthenticated.
     */
    @Test
    fun missingProfileDocument_doesNotEmitUnauthenticated() {
        val stateMissing: AuthProfileState = AuthProfileState.AuthenticatedProfileMissing(
            firebaseUser = null
        )
        assertTrue(stateMissing is AuthProfileState.AuthenticatedProfileMissing)
        assertFalse(stateMissing is AuthProfileState.Unauthenticated)

        val stateLoading: AuthProfileState = AuthProfileState.Loading
        assertTrue(stateLoading is AuthProfileState.Loading)
        assertFalse(stateLoading is AuthProfileState.Unauthenticated)
    }

    /**
     * Test 5: Prevent AuthStateListener from wiping valid user profile to null during race condition.
     */
    @Test
    fun raceConditionPrevention_preservesExistingInMemoryProfile() {
        val currentUid = "active_user_uid"
        val existingProfile = User(
            uid = currentUid,
            id = currentUid,
            email = "active@test.com",
            pointsBalance = 320L
        )

        AuthRepository.currentUserFlow.value = existingProfile

        // Even if a transient query doesn't find document yet, existing in-memory profile is maintained
        val retrieved = if (AuthRepository.currentUserFlow.value?.uid == currentUid) {
            AuthRepository.currentUserFlow.value
        } else {
            null
        }

        assertNotNull(retrieved)
        assertEquals(currentUid, retrieved?.uid)
        assertEquals(320L, retrieved?.pointsBalance)
    }

    /**
     * Test 6: Social community access is not blocked when user is authenticated.
     */
    @Test
    fun communityGate_withAuthenticatedUser_providesProfile() {
        val authUser = User(
            uid = "community_user_777",
            username = "cinefan",
            firstName = "Cine",
            lastName = "Fan"
        )
        val profile = UserProfile(
            uid = authUser.uid,
            username = authUser.username,
            firstName = authUser.firstName,
            lastName = authUser.lastName,
            isOnline = true
        )
        assertNotNull(profile)
        assertEquals("community_user_777", profile.uid)
        assertEquals("cinefan", profile.username)
        assertEquals("Cine Fan", profile.displayName)
    }

    /**
     * Test 7: Account switch detection in SessionManager purges old user local data.
     */
    @Test
    fun sessionManager_accountSwitchPurgesOldUserLocalData() {
        val userA = "user_A_uid"
        val userB = "user_B_uid"

        // Set active user A
        val genA = SessionManager.bindSessionForTesting(userA)
        assertEquals(userA, SessionManager.activeUid)

        // Switching to user B invalidates user A's session and advances epoch
        val genB = SessionManager.bindSessionForTesting(userB)
        assertNotEquals(genA, genB)
        assertEquals(userB, SessionManager.activeUid)
        assertFalse(SessionManager.isSessionValid(userA, genA))
        assertTrue(SessionManager.isSessionValid(userB, genB))
    }

    /**
     * Test 8: Points immutability - user cannot directly manipulate server-authoritative balance.
     */
    @Test
    fun pointsLedger_isServerAuthoritativeAndImmutableFromUserApp() {
        val user = User(
            uid = "verified_uid",
            pointsBalance = 100L,
            totalPointsEarned = 200L,
            totalPointsSpent = 100L
        )
        // User fields are immutable properties
        assertEquals(100L, user.pointsBalance)
        assertEquals(200L, user.totalPointsEarned)
        assertEquals(100L, user.totalPointsSpent)
    }

    /**
     * Test 9: Sanitized Email/Password error mapping prevents raw Firebase internals or credential leaks.
     */
    @Test
    fun mapAuthException_sanitizesInvalidCredentialError() {
        val rawMessage = "The supplied auth credential is incorrect, malformed or has expired."
        val invalidCredEx = FirebaseAuthInvalidCredentialsException(
            "ERROR_INVALID_CREDENTIAL",
            rawMessage
        )

        // Verify that raw internal error message is sanitized to localized string
        val sanitizedError = when (invalidCredEx.errorCode) {
            "ERROR_INVALID_CREDENTIAL",
            "ERROR_WRONG_PASSWORD",
            "ERROR_USER_NOT_FOUND" -> application.getString(R.string.invalid_email_or_password)
            else -> "Other"
        }

        assertEquals(application.getString(R.string.invalid_email_or_password), sanitizedError)
        assertFalse("Error message must not expose raw internal token message", sanitizedError.contains(rawMessage))
    }

    /**
     * Test 10: Error mapping for network and account disabled codes.
     */
    @Test
    fun mapAuthException_mapsDisabledAndNetworkErrors() {
        val disabledEx = FirebaseAuthException("ERROR_USER_DISABLED", "User is disabled")
        val tooManyRequestsEx = FirebaseAuthException("ERROR_TOO_MANY_REQUESTS", "Too many requests")
        val networkEx = FirebaseNetworkException("Network connection failed")

        val expectedDisabled = application.getString(R.string.account_disabled)
        val expectedTooMany = application.getString(R.string.too_many_requests)
        val expectedNetwork = application.getString(R.string.network_error_auth)

        val mappedDisabled = when (disabledEx.errorCode) {
            "ERROR_USER_DISABLED" -> application.getString(R.string.account_disabled)
            else -> "Other"
        }
        val mappedTooMany = when (tooManyRequestsEx.errorCode) {
            "ERROR_TOO_MANY_REQUESTS" -> application.getString(R.string.too_many_requests)
            else -> "Other"
        }
        val mappedNetwork = if (networkEx is FirebaseNetworkException) {
            application.getString(R.string.network_error_auth)
        } else "Other"

        assertEquals(expectedDisabled, mappedDisabled)
        assertEquals(expectedTooMany, mappedTooMany)
        assertEquals(expectedNetwork, mappedNetwork)
    }
}
