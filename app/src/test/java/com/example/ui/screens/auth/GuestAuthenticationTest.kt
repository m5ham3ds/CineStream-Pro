package com.example.ui.screens.auth

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.R
import com.example.data.diagnostic.AuthGatewayDiagnostic
import com.example.data.repository.AuthRepository
import com.example.data.repository.User
import com.example.utils.NetworkUtils
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuthException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GuestAuthenticationTest {

    private lateinit var application: Application
    private lateinit var viewModel: AuthViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(application).isEmpty()) {
            FirebaseApp.initializeApp(
                application,
                FirebaseOptions.Builder()
                    .setApplicationId("com.aistudio.cinestream.xyzabc")
                    .setProjectId("cinestream-sulo")
                    .setApiKey("AIzaSyC9CvaM9Mw3NiD-KsOiYvHZHj6XZJQJnPs")
                    .build()
            )
        }
        NetworkUtils.networkAvailableOverride = true
        AuthRepository.resetTestHooks()
        AuthRepository.currentUserFlow.value = null
        viewModel = AuthViewModel(application)
    }

    @After
    fun tearDown() {
        NetworkUtils.networkAvailableOverride = null
        AuthRepository.resetTestHooks()
        AuthRepository.currentUserFlow.value = null
    }

    /**
     * Test 1: Calling signInAnonymously when no user is signed in triggers the anonymous provider,
     * populates currentUserFlow with guest user details, and completes successfully.
     */
    @Test
    fun signInAnonymously_success_establishesGuestSession() = runTest {
        val guestUid = "test_guest_uid_777"
        val fakeGuestUser = User(
            uid = guestUid,
            id = guestUid,
            username = "user_test7",
            displayName = "Guest User",
            role = "user"
        )
        var providerInvoked = false

        AuthRepository.anonymousSignInUserProvider = {
            providerInvoked = true
            fakeGuestUser
        }

        var completionSuccess: Boolean? = null
        var completionError: String? = null

        viewModel.signInAnonymously { success, errorMsg ->
            completionSuccess = success
            completionError = errorMsg
        }

        assertTrue("Provider should have been invoked", providerInvoked)
        assertEquals(true, completionSuccess)
        assertNull(completionError)

        val currentUser = AuthRepository.currentUserFlow.value
        assertNotNull("Guest User should be populated in AuthRepository.currentUserFlow", currentUser)
        assertEquals(guestUid, currentUser?.uid)
        assertEquals("user_test7", currentUser?.username)
    }

    /**
     * Test 2: When internet is unavailable and no user is signed in,
     * signInAnonymously fails fast without calling Firebase provider and surfaces localized error.
     */
    @Test
    fun signInAnonymously_offline_failsFastAndDoesNotEstablishSession() = runTest {
        NetworkUtils.networkAvailableOverride = false
        var providerInvoked = false

        AuthRepository.anonymousSignInUserProvider = {
            providerInvoked = true
            User(uid = "should_not_be_created")
        }

        var completionSuccess: Boolean? = null
        var completionError: String? = null

        viewModel.signInAnonymously { success, errorMsg ->
            completionSuccess = success
            completionError = errorMsg
        }

        assertFalse("Provider must NOT be invoked when offline", providerInvoked)
        assertEquals(false, completionSuccess)
        assertEquals(application.getString(R.string.network_error_auth), completionError)
        assertNull(AuthRepository.currentUserFlow.value)
    }

    /**
     * Test 3: When anonymous sign-in provider throws an exception (e.g. Firebase config / provider error),
     * the error is mapped properly and no authenticated session is established.
     */
    @Test
    fun signInAnonymously_providerFailure_reportsErrorWithoutSession() = runTest {
        AuthRepository.anonymousSignInUserProvider = {
            throw FirebaseNetworkException("Connection failed")
        }

        var completionSuccess: Boolean? = null
        var completionError: String? = null

        viewModel.signInAnonymously { success, errorMsg ->
            completionSuccess = success
            completionError = errorMsg
        }

        assertEquals(false, completionSuccess)
        assertEquals(application.getString(R.string.network_error_auth), completionError)
        assertNull(AuthRepository.currentUserFlow.value)
    }

    /**
     * Test 4: When anonymous sign-in provider throws FirebaseAuthException (e.g. disabled provider),
     * the error message is sanitized and user remains unauthenticated.
     */
    @Test
    fun signInAnonymously_firebaseAuthException_mapsCorrectly() = runTest {
        AuthRepository.anonymousSignInUserProvider = {
            throw FirebaseAuthException("ERROR_OPERATION_NOT_ALLOWED", "Anonymous sign in disabled")
        }

        var completionSuccess: Boolean? = null
        var completionError: String? = null

        viewModel.signInAnonymously { success, errorMsg ->
            completionSuccess = success
            completionError = errorMsg
        }

        assertEquals(false, completionSuccess)
        assertNotNull(completionError)
        assertNull(AuthRepository.currentUserFlow.value)
    }

    /**
     * Test 5: Cloudflare Worker diagnostic evaluation with an anonymous user session.
     * When Worker returns ok=true, authenticated=true, matching projectId and UID,
     * it evaluates to AUTHENTICATION_VERIFIED and marks isAnonymous=true with UID match.
     */
    @Test
    fun workerDiagnostic_withGuestUser_verifiesSuccessfully() {
        val guestUid = "guest_diagnostic_uid_888"
        val payload = """
            {
                "ok": true,
                "authenticated": true,
                "projectId": "cinestream-sulo",
                "uid": "$guestUid",
                "expiresAt": 1729548000000
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = payload,
            currentUid = guestUid,
            isAnonymous = true
        )

        assertEquals(200, result.httpStatus)
        assertEquals("AUTHENTICATION_VERIFIED", result.resultCode)
        assertTrue(result.hasFirebaseUser)
        assertTrue(result.isAnonymous)
        assertEquals("MATCH", result.uidMatchResult)
        assertEquals("cinestream-sulo", result.returnedProjectId)
        assertNull(result.workerErrorCode)
    }

    /**
     * Test 6: Cloudflare Worker diagnostic evaluation when UID returned by Worker does not match guest UID.
     */
    @Test
    fun workerDiagnostic_withGuestUidMismatch_reportsUidMismatch() {
        val guestUid = "guest_diagnostic_uid_888"
        val differentUid = "another_account_uid_999"
        val payload = """
            {
                "ok": true,
                "authenticated": true,
                "projectId": "cinestream-sulo",
                "uid": "$differentUid"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = payload,
            currentUid = guestUid,
            isAnonymous = true
        )

        assertEquals(200, result.httpStatus)
        assertEquals("UID_MISMATCH", result.resultCode)
        assertTrue(result.hasFirebaseUser)
        assertTrue(result.isAnonymous)
        assertEquals("MISMATCH", result.uidMatchResult)
    }

    /**
     * Test 7: Cloudflare Worker diagnostic evaluation when no user is signed in.
     */
    @Test
    fun workerDiagnostic_withNoUser_verifiesNoAuthenticatedUserContract() {
        val payload = """
            {
                "ok": false,
                "authenticated": false,
                "code": "AUTH_REQUIRED",
                "message": "Authentication required."
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 401,
            responseBody = payload,
            currentUid = "",
            isAnonymous = false
        )

        assertEquals(401, result.httpStatus)
        assertEquals("AUTH_REQUIRED", result.resultCode)
        assertEquals("N/A", result.uidMatchResult)
    }
}
