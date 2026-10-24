package com.example.ui.screens.auth

import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * Result abstraction for parsing Credential Manager results during Google Sign-In.
 */
sealed class GoogleCredentialResult {
    data class Success(val credential: GoogleIdTokenCredential) : GoogleCredentialResult()
    data class UnexpectedType(val actualType: String) : GoogleCredentialResult()
    data class ParsingError(val message: String) : GoogleCredentialResult()
    object NotCustomCredential : GoogleCredentialResult()
}

/**
 * Encapsulates validation of Web Client ID and parsing of CustomCredential for Google Sign-In.
 */
object GoogleCredentialParser {

    /**
     * Validates that the provided Web Client ID is non-blank, non-mock, and follows Google OAuth format.
     */
    fun validateWebClientId(webClientId: String?): Boolean {
        if (webClientId.isNullOrBlank()) return false
        val trimmed = webClientId.trim()
        if (trimmed == "web_client_mock_id") return false
        if (trimmed.contains("mock", ignoreCase = true)) return false
        if (trimmed.contains("dummy", ignoreCase = true)) return false
        return trimmed.endsWith(".apps.googleusercontent.com") || trimmed.contains(".apps.googleusercontent.com")
    }

    /**
     * Checks if the credential type matches either standard Google ID token or Sign In With Google (SIWG) type.
     */
    fun isSupportedGoogleCredentialType(type: String): Boolean {
        return type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL ||
               type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
    }

    /**
     * Parses a raw Credential from CredentialManager.getCredential() into a typed GoogleIdTokenCredential.
     * Enforces:
     * 1. Credential must be an instance of CustomCredential.
     * 2. CustomCredential.type must match GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL or TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL.
     * 3. GoogleIdTokenCredential.createFrom(credential.data) parses the Bundle payload.
     */
    fun parseCredential(credential: Credential): GoogleCredentialResult {
        if (credential !is CustomCredential) {
            return GoogleCredentialResult.NotCustomCredential
        }
        if (!isSupportedGoogleCredentialType(credential.type)) {
            return GoogleCredentialResult.UnexpectedType(credential.type)
        }
        return try {
            val googleIdToken = GoogleIdTokenCredential.createFrom(credential.data)
            GoogleCredentialResult.Success(googleIdToken)
        } catch (e: Exception) {
            GoogleCredentialResult.ParsingError(e.message ?: "Failed to parse Google ID token credential data")
        }
    }
}
