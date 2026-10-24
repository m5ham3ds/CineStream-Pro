package com.example.data.diagnostic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthGatewayDiagnosticTest {

    private val localUid = "test_user_uid_123"

    @Test
    fun evaluateResponse_whenValidMatchingResponse_returnsAuthenticationVerified() {
        val payload = """
            {
                "ok": true,
                "authenticated": true,
                "projectId": "cinestream-sulo",
                "uid": "$localUid",
                "expiresAt": 1729548000000
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(200, result.httpStatus)
        assertEquals("AUTHENTICATION_VERIFIED", result.resultCode)
        assertTrue(result.hasFirebaseUser)
        assertFalse(result.isAnonymous)
        assertEquals("MATCH", result.uidMatchResult)
        assertEquals("cinestream-sulo", result.returnedProjectId)
        assertNull(result.workerErrorCode)
        assertTrue(result.message.contains("successfully verified", ignoreCase = true))
    }

    @Test
    fun evaluateResponse_whenGuestUserMatchingResponse_returnsAuthenticationVerified() {
        val payload = """
            {
                "ok": true,
                "authenticated": true,
                "projectId": "cinestream-sulo",
                "uid": "$localUid"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = true
        )

        assertEquals("AUTHENTICATION_VERIFIED", result.resultCode)
        assertTrue(result.hasFirebaseUser)
        assertTrue(result.isAnonymous)
        assertEquals("MATCH", result.uidMatchResult)
    }

    @Test
    fun evaluateResponse_whenUidMismatch_returnsUidMismatch() {
        val payload = """
            {
                "ok": true,
                "authenticated": true,
                "projectId": "cinestream-sulo",
                "uid": "different_uid_456"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(200, result.httpStatus)
        assertEquals("UID_MISMATCH", result.resultCode)
        assertEquals("MISMATCH", result.uidMatchResult)
        assertEquals("cinestream-sulo", result.returnedProjectId)
    }

    @Test
    fun evaluateResponse_whenProjectIdMismatch_returnsProjectIdMismatch() {
        val payload = """
            {
                "ok": true,
                "authenticated": true,
                "projectId": "other-firebase-project",
                "uid": "$localUid"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(200, result.httpStatus)
        assertEquals("PROJECT_ID_MISMATCH", result.resultCode)
        assertEquals("other-firebase-project", result.returnedProjectId)
    }

    @Test
    fun evaluateResponse_whenOkOrAuthenticatedFalse_returnsInvalidResponse() {
        val payload = """
            {
                "ok": false,
                "authenticated": false,
                "projectId": "cinestream-sulo",
                "uid": "$localUid"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(200, result.httpStatus)
        assertEquals("INVALID_RESPONSE", result.resultCode)
    }

    @Test
    fun evaluateResponse_whenInvalidJson_returnsInvalidResponse() {
        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = "<html><body>502 Bad Gateway</body></html>",
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(200, result.httpStatus)
        assertEquals("INVALID_RESPONSE", result.resultCode)
    }

    @Test
    fun evaluateResponse_when401AuthRequired_returnsAuthRequired() {
        val payload = """
            {
                "error": "Authentication required",
                "code": "AUTH_REQUIRED"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 401,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(401, result.httpStatus)
        assertEquals("AUTH_REQUIRED", result.resultCode)
        assertEquals("AUTH_REQUIRED", result.workerErrorCode)
        assertEquals("N/A", result.uidMatchResult)
    }

    @Test
    fun evaluateResponse_when401InvalidIdToken_returnsInvalidIdToken() {
        val payload = """
            {
                "error": "Firebase ID token is invalid or expired",
                "code": "INVALID_ID_TOKEN"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 401,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(401, result.httpStatus)
        assertEquals("INVALID_ID_TOKEN", result.resultCode)
        assertEquals("INVALID_ID_TOKEN", result.workerErrorCode)
    }

    @Test
    fun evaluateResponse_when503PublicKeyRetrievalFailed_returnsPublicKeyRetrievalFailed() {
        val payload = """
            {
                "error": "Failed to fetch Firebase public certs",
                "code": "PUBLIC_KEY_RETRIEVAL_FAILED"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 503,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(503, result.httpStatus)
        assertEquals("PUBLIC_KEY_RETRIEVAL_FAILED", result.resultCode)
        assertEquals("PUBLIC_KEY_RETRIEVAL_FAILED", result.workerErrorCode)
    }

    @Test
    fun evaluateResponse_when503ServiceUnavailable_returnsServiceUnavailable() {
        val payload = """
            {
                "error": "Backend is temporarily offline",
                "code": "BACKEND_NOT_ENABLED"
            }
        """.trimIndent()

        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 503,
            responseBody = payload,
            currentUid = localUid,
            isAnonymous = false
        )

        assertEquals(503, result.httpStatus)
        assertEquals("BACKEND_NOT_ENABLED", result.resultCode)
    }

    @Test
    fun securityCheck_resultModelDoesNotStoreTokensOrAuthHeaders() {
        val result = AuthGatewayDiagnostic.evaluateResponse(
            statusCode = 200,
            responseBody = """{"ok":true,"authenticated":true,"projectId":"cinestream-sulo","uid":"$localUid"}""",
            currentUid = localUid,
            isAnonymous = false
        )

        // Reflection check that result class has no token or header fields
        val fieldNames = result.javaClass.declaredFields.map { it.name.lowercase() }
        assertFalse(fieldNames.any { it.contains("token") })
        assertFalse(fieldNames.any { it.contains("auth") && !it.contains("hasfirebaseuser") })
        assertFalse(fieldNames.any { it.contains("secret") })
        assertFalse(fieldNames.any { it.contains("password") })
        assertFalse(fieldNames.any { it.contains("header") })
    }
}
