package com.example.data.diagnostic

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Safe diagnostic result representation.
 *
 * CRITICAL SECURITY INVARIANT:
 * This model intentionally NEVER contains the Firebase ID token, refresh token,
 * authorization header, or any client secrets/credentials. Tokens must never be
 * displayed in UI, copied to clipboard, logged, or persisted.
 */
data class AuthDiagnosticResult(
    val httpStatus: Int?,
    val resultCode: String,
    val hasFirebaseUser: Boolean,
    val isAnonymous: Boolean,
    val uidMatchResult: String, // "MATCH", "MISMATCH", or "N/A"
    val returnedProjectId: String?,
    val workerErrorCode: String?,
    val message: String
)

object AuthGatewayDiagnostic {

    const val EXPECTED_PROJECT_ID = "cinestream-sulo"
    const val WORKER_SERVICE_URL = "https://cinestream-api-gateway.blackmyemail1.workers.dev"
    const val DIAGNOSTIC_ENDPOINT_PATH = "/api/v1/auth/verify"
    const val FULL_DIAGNOSTIC_URL = "$WORKER_SERVICE_URL$DIAGNOSTIC_ENDPOINT_PATH"

    // Dedicated diagnostic client: strictly NO logging interceptors to ensure authorization headers cannot be logged.
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val isExecuting = AtomicBoolean(false)

    /**
     * Executes the controlled authentication diagnostic between the current Firebase user
     * and the Cloudflare Worker verification endpoint.
     * Prevents duplicate concurrent in-flight requests.
     */
    suspend fun runDiagnostic(
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        client: OkHttpClient = httpClient
    ): AuthDiagnosticResult = withContext(Dispatchers.IO) {
        if (!isExecuting.compareAndSet(false, true)) {
            return@withContext AuthDiagnosticResult(
                httpStatus = null,
                resultCode = "REQUEST_IN_PROGRESS",
                hasFirebaseUser = auth.currentUser != null,
                isAnonymous = auth.currentUser?.isAnonymous ?: false,
                uidMatchResult = "N/A",
                returnedProjectId = null,
                workerErrorCode = null,
                message = "A diagnostic request is already running. Please wait for completion."
            )
        }

        try {
            executeDiagnosticInternal(auth, client)
        } finally {
            isExecuting.set(false)
        }
    }

    private suspend fun executeDiagnosticInternal(
        auth: FirebaseAuth,
        client: OkHttpClient
    ): AuthDiagnosticResult {
        // 1. Inspect current Firebase Authentication user
        val currentUser = auth.currentUser
        if (currentUser == null) {
            return AuthDiagnosticResult(
                httpStatus = null,
                resultCode = "NO_AUTHENTICATED_USER",
                hasFirebaseUser = false,
                isAnonymous = false,
                uidMatchResult = "N/A",
                returnedProjectId = null,
                workerErrorCode = null,
                message = "No authenticated Firebase user found in session. Please sign in first."
            )
        }

        val localUid = currentUser.uid
        val isAnonymous = currentUser.isAnonymous

        // 2. Retrieve Firebase ID token (forceRefresh = false)
        val idToken = try {
            val tokenResult = currentUser.getIdToken(false).await()
            val token = tokenResult.token
            if (token.isNullOrBlank()) {
                return AuthDiagnosticResult(
                    httpStatus = null,
                    resultCode = "TOKEN_RETRIEVAL_FAILED",
                    hasFirebaseUser = true,
                    isAnonymous = isAnonymous,
                    uidMatchResult = "N/A",
                    returnedProjectId = null,
                    workerErrorCode = null,
                    message = "Firebase ID token returned empty or null."
                )
            }
            token
        } catch (e: Exception) {
            return AuthDiagnosticResult(
                httpStatus = null,
                resultCode = "TOKEN_RETRIEVAL_FAILED",
                hasFirebaseUser = true,
                isAnonymous = isAnonymous,
                uidMatchResult = "N/A",
                returnedProjectId = null,
                workerErrorCode = null,
                message = "Failed to retrieve Firebase ID token: ${e.localizedMessage ?: e.javaClass.simpleName}"
            )
        }

        // 3. Send GET request to Worker endpoint with Authorization and Accept headers
        val (statusCode, responseBody) = try {
            val request = Request.Builder()
                .url(FULL_DIAGNOSTIC_URL)
                .addHeader("Authorization", "Bearer $idToken")
                .addHeader("Accept", "application/json")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                response.code to (response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            return AuthDiagnosticResult(
                httpStatus = null,
                resultCode = "NETWORK_FAILURE",
                hasFirebaseUser = true,
                isAnonymous = isAnonymous,
                uidMatchResult = "N/A",
                returnedProjectId = null,
                workerErrorCode = null,
                message = "Network or DNS failure connecting to Worker endpoint: ${e.localizedMessage ?: "Connection error"}"
            )
        } catch (e: Exception) {
            return AuthDiagnosticResult(
                httpStatus = null,
                resultCode = "NETWORK_FAILURE",
                hasFirebaseUser = true,
                isAnonymous = isAnonymous,
                uidMatchResult = "N/A",
                returnedProjectId = null,
                workerErrorCode = null,
                message = "Diagnostic request failed: ${e.localizedMessage ?: "Unknown error"}"
            )
        }

        // 4. Parse & evaluate the response
        return evaluateResponse(
            statusCode = statusCode,
            responseBody = responseBody,
            currentUid = localUid,
            isAnonymous = isAnonymous
        )
    }

    /**
     * Pure evaluator for Worker HTTP status and JSON payload.
     * Accessible for deterministic unit testing.
     */
    fun evaluateResponse(
        statusCode: Int,
        responseBody: String,
        currentUid: String,
        isAnonymous: Boolean
    ): AuthDiagnosticResult {
        val json = try {
            if (responseBody.isNotBlank()) JSONObject(responseBody) else null
        } catch (_: Exception) {
            null
        }

        val workerErrorCode = json?.optString("code")?.takeIf { it.isNotBlank() }
            ?: json?.optString("errorCode")?.takeIf { it.isNotBlank() }
        val workerMessage = json?.optString("message")?.takeIf { it.isNotBlank() }
            ?: json?.optString("error")?.takeIf { it.isNotBlank() }
        val returnedProjectId = if (json?.has("projectId") == true) json.optString("projectId") else null

        return when (statusCode) {
            200 -> {
                if (json == null) {
                    AuthDiagnosticResult(
                        httpStatus = statusCode,
                        resultCode = "INVALID_RESPONSE",
                        hasFirebaseUser = true,
                        isAnonymous = isAnonymous,
                        uidMatchResult = "N/A",
                        returnedProjectId = null,
                        workerErrorCode = null,
                        message = "HTTP 200 received but response body could not be parsed as JSON."
                    )
                } else {
                    val ok = json.optBoolean("ok", false)
                    val authenticated = json.optBoolean("authenticated", false)
                    val returnedUid = if (json.has("uid")) json.optString("uid") else null
                    val isUidMatch = returnedUid != null && returnedUid == currentUid
                    val uidMatchStr = if (returnedUid == null) "N/A" else if (isUidMatch) "MATCH" else "MISMATCH"

                    if (!ok || !authenticated) {
                        AuthDiagnosticResult(
                            httpStatus = statusCode,
                            resultCode = "INVALID_RESPONSE",
                            hasFirebaseUser = true,
                            isAnonymous = isAnonymous,
                            uidMatchResult = uidMatchStr,
                            returnedProjectId = returnedProjectId,
                            workerErrorCode = workerErrorCode,
                            message = "Worker returned ok=$ok, authenticated=$authenticated (expected true for both)."
                        )
                    } else if (returnedProjectId != EXPECTED_PROJECT_ID) {
                        AuthDiagnosticResult(
                            httpStatus = statusCode,
                            resultCode = "PROJECT_ID_MISMATCH",
                            hasFirebaseUser = true,
                            isAnonymous = isAnonymous,
                            uidMatchResult = uidMatchStr,
                            returnedProjectId = returnedProjectId,
                            workerErrorCode = workerErrorCode,
                            message = "Worker returned projectId='$returnedProjectId', expected '$EXPECTED_PROJECT_ID'."
                        )
                    } else if (!isUidMatch) {
                        AuthDiagnosticResult(
                            httpStatus = statusCode,
                            resultCode = "UID_MISMATCH",
                            hasFirebaseUser = true,
                            isAnonymous = isAnonymous,
                            uidMatchResult = "MISMATCH",
                            returnedProjectId = returnedProjectId,
                            workerErrorCode = workerErrorCode,
                            message = "Worker verified UID does not match local Firebase user UID."
                        )
                    } else {
                        // Success: all 5 conditions strictly verified
                        AuthDiagnosticResult(
                            httpStatus = statusCode,
                            resultCode = "AUTHENTICATION_VERIFIED",
                            hasFirebaseUser = true,
                            isAnonymous = isAnonymous,
                            uidMatchResult = "MATCH",
                            returnedProjectId = returnedProjectId,
                            workerErrorCode = null,
                            message = "Authentication successfully verified by Cloudflare Worker."
                        )
                    }
                }
            }
            401 -> {
                val resultCode = when {
                    workerErrorCode.equals("AUTH_REQUIRED", ignoreCase = true) -> "AUTH_REQUIRED"
                    workerErrorCode.equals("INVALID_ID_TOKEN", ignoreCase = true) -> "INVALID_ID_TOKEN"
                    workerErrorCode != null && workerErrorCode.contains("TOKEN", ignoreCase = true) -> "INVALID_ID_TOKEN"
                    workerErrorCode != null -> workerErrorCode
                    workerMessage?.contains("token", ignoreCase = true) == true -> "INVALID_ID_TOKEN"
                    else -> "AUTH_REQUIRED"
                }
                AuthDiagnosticResult(
                    httpStatus = statusCode,
                    resultCode = resultCode,
                    hasFirebaseUser = true,
                    isAnonymous = isAnonymous,
                    uidMatchResult = "N/A",
                    returnedProjectId = returnedProjectId,
                    workerErrorCode = workerErrorCode,
                    message = workerMessage ?: "Authentication required or invalid token (HTTP 401: $resultCode)."
                )
            }
            503 -> {
                val resultCode = when {
                    workerErrorCode.equals("PUBLIC_KEY_RETRIEVAL_FAILED", ignoreCase = true) -> "PUBLIC_KEY_RETRIEVAL_FAILED"
                    workerMessage?.contains("public key", ignoreCase = true) == true -> "PUBLIC_KEY_RETRIEVAL_FAILED"
                    workerErrorCode != null -> workerErrorCode
                    else -> "SERVICE_UNAVAILABLE"
                }
                AuthDiagnosticResult(
                    httpStatus = statusCode,
                    resultCode = resultCode,
                    hasFirebaseUser = true,
                    isAnonymous = isAnonymous,
                    uidMatchResult = "N/A",
                    returnedProjectId = returnedProjectId,
                    workerErrorCode = workerErrorCode,
                    message = workerMessage ?: "Worker service unavailable or public key retrieval failed (HTTP 503: $resultCode)."
                )
            }
            else -> {
                AuthDiagnosticResult(
                    httpStatus = statusCode,
                    resultCode = workerErrorCode ?: "INVALID_RESPONSE",
                    hasFirebaseUser = true,
                    isAnonymous = isAnonymous,
                    uidMatchResult = "N/A",
                    returnedProjectId = returnedProjectId,
                    workerErrorCode = workerErrorCode,
                    message = workerMessage ?: "Unexpected HTTP status $statusCode from Worker."
                )
            }
        }
    }
}
