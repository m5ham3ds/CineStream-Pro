package com.example.ui.screens.auth

import android.os.Bundle
import androidx.credentials.Credential
import androidx.credentials.CustomCredential
import androidx.credentials.PasswordCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleCredentialParserTest {

    @Test
    fun validateWebClientId_withValidOAuthClientId_returnsTrue() {
        val validClientId = "979447256418-rjb9a0991gvve0328n113dme67i4gpv2.apps.googleusercontent.com"
        assertTrue(GoogleCredentialParser.validateWebClientId(validClientId))
    }

    @Test
    fun validateWebClientId_withMockId_returnsFalse() {
        assertFalse(GoogleCredentialParser.validateWebClientId("web_client_mock_id"))
        assertFalse(GoogleCredentialParser.validateWebClientId("mock_client_12345"))
        assertFalse(GoogleCredentialParser.validateWebClientId("dummy_client_id"))
        assertFalse(GoogleCredentialParser.validateWebClientId("example_oauth_id"))
    }

    @Test
    fun validateWebClientId_withEmptyOrBlankOrNull_returnsFalse() {
        assertFalse(GoogleCredentialParser.validateWebClientId(null))
        assertFalse(GoogleCredentialParser.validateWebClientId(""))
        assertFalse(GoogleCredentialParser.validateWebClientId("   "))
    }

    @Test
    fun parseCredential_withNonCustomCredential_returnsNotCustomCredential() {
        val nonCustom = PasswordCredential("test_user", "password123")
        val result = GoogleCredentialParser.parseCredential(nonCustom)
        assertEquals(GoogleCredentialResult.NotCustomCredential, result)
    }

    @Test
    fun parseCredential_withUnexpectedType_returnsUnexpectedType() {
        val unexpected = CustomCredential("com.example.unknown.custom_credential", Bundle())
        val result = GoogleCredentialParser.parseCredential(unexpected)
        assertTrue(result is GoogleCredentialResult.UnexpectedType)
        assertEquals("com.example.unknown.custom_credential", (result as GoogleCredentialResult.UnexpectedType).actualType)
    }

    @Test
    fun parseCredential_withEmptyBundle_returnsParsingError() {
        val emptyGoogleCustom = CustomCredential(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
            Bundle()
        )
        val result = GoogleCredentialParser.parseCredential(emptyGoogleCustom)
        assertTrue("Expected ParsingError but got $result", result is GoogleCredentialResult.ParsingError)
    }

    @Test
    fun isSupportedGoogleCredentialType_validatesBothStandardAndSiwgTypes() {
        assertTrue(
            GoogleCredentialParser.isSupportedGoogleCredentialType(
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            )
        )
        assertTrue(
            GoogleCredentialParser.isSupportedGoogleCredentialType(
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL
            )
        )
        assertFalse(GoogleCredentialParser.isSupportedGoogleCredentialType("com.example.other.type"))
        assertFalse(GoogleCredentialParser.isSupportedGoogleCredentialType(""))
    }

    @Test
    fun parseCredential_withValidGoogleIdTokenBundle_returnsSuccess() {
        val expectedToken = "header.payload.signature"
        val expectedEmail = "sulo@test.com"
        val expectedName = "Sulo Tester"

        val original = GoogleIdTokenCredential.Builder()
            .setIdToken(expectedToken)
            .setId(expectedEmail)
            .setDisplayName(expectedName)
            .build()

        val providerCustomCredential = CustomCredential(original.type, original.data)
        val result = GoogleCredentialParser.parseCredential(providerCustomCredential)

        assertTrue("Expected Success but got $result", result is GoogleCredentialResult.Success)
        val success = result as GoogleCredentialResult.Success
        assertEquals(expectedToken, success.credential.idToken)
        assertEquals(expectedEmail, success.credential.id)
        assertEquals(expectedName, success.credential.displayName)
    }

    @Test
    fun parseCredential_withValidSignInWithGoogleOptionBundle_returnsSuccess() {
        val expectedToken = "siwg.header.payload.signature"
        val expectedEmail = "user.siwg@test.com"
        val expectedDisplayName = "SIWG User"

        val original = GoogleIdTokenCredential.Builder()
            .setIdToken(expectedToken)
            .setId(expectedEmail)
            .setDisplayName(expectedDisplayName)
            .build()

        // Create CustomCredential with TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL as returned by GetSignInWithGoogleOption
        val siwgCustomCredential = CustomCredential(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
            original.data
        )

        val result = GoogleCredentialParser.parseCredential(siwgCustomCredential)

        assertTrue("Expected Success for SIWG credential but got $result", result is GoogleCredentialResult.Success)
        val success = result as GoogleCredentialResult.Success
        assertEquals(expectedToken, success.credential.idToken)
        assertEquals(expectedEmail, success.credential.id)
        assertEquals(expectedDisplayName, success.credential.displayName)
    }

    @Test
    fun parseCredential_withSiwgEmptyBundle_returnsParsingError() {
        val siwgEmptyCustom = CustomCredential(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
            Bundle()
        )
        val result = GoogleCredentialParser.parseCredential(siwgEmptyCustom)
        assertTrue("Expected ParsingError for empty SIWG bundle but got $result", result is GoogleCredentialResult.ParsingError)
    }

    @Test
    fun parseCredential_withSiwgMinimalFields_returnsSuccess() {
        val expectedToken = "minimal.jwt.siwg.token"
        val expectedId = "minimal_siwg_user@gmail.com"

        val original = GoogleIdTokenCredential.Builder()
            .setIdToken(expectedToken)
            .setId(expectedId)
            .build()

        val siwgCustomCredential = CustomCredential(
            GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_SIWG_CREDENTIAL,
            original.data
        )

        val result = GoogleCredentialParser.parseCredential(siwgCustomCredential)
        assertTrue("Expected Success for minimal SIWG fields but got $result", result is GoogleCredentialResult.Success)
        val success = result as GoogleCredentialResult.Success
        assertEquals(expectedToken, success.credential.idToken)
        assertEquals(expectedId, success.credential.id)
    }
}
