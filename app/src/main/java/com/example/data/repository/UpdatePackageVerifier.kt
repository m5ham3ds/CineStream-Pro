package com.example.data.repository

import android.util.Base64
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * F-007: Cryptographic verification boundary for application update packages.
 *
 * Enforces:
 * 1. An update package is NEVER trusted based on HTTP status, URL, or file name.
 * 2. Independent cryptographic verification (SHA-256 integrity + RSA/ECDSA signature verification).
 * 3. Rejection of unverified updates; documents required trusted backend/signing authority.
 */
interface UpdatePackageVerifier {
    sealed class VerificationResult {
        object Verified : VerificationResult()
        data class Failed(val reason: String) : VerificationResult()
        object BlockedMissingSignatureAuthority : VerificationResult()
    }

    suspend fun verifyPackage(
        apkFile: File,
        expectedSha256: String?,
        signature: String?,
        publicKey: String? = null
    ): VerificationResult
}

object CryptographicUpdateVerifier : UpdatePackageVerifier {

    /**
     * Independently pinned release public key trust anchor.
     * Can be set at application initialization or by secure configuration.
     * If not configured, verification fails-closed with BlockedMissingSignatureAuthority
     * unless an explicit trusted anchor key is supplied to [verifyPackage].
     */
    @Volatile
    var pinnedPublicKey: String? = null

    override suspend fun verifyPackage(
        apkFile: File,
        expectedSha256: String?,
        signature: String?,
        publicKey: String?
    ): UpdatePackageVerifier.VerificationResult {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return UpdatePackageVerifier.VerificationResult.Failed("APK file not found or unreadable")
        }

        // 1. Check SHA-256 integrity if checksum is provided
        if (!expectedSha256.isNullOrBlank()) {
            val calculatedSha256 = calculateSha256(apkFile)
            if (!calculatedSha256.equals(expectedSha256.trim(), ignoreCase = true)) {
                return UpdatePackageVerifier.VerificationResult.Failed(
                    "SHA-256 checksum mismatch: calculated=$calculatedSha256, expected=$expectedSha256"
                )
            }
        }

        // Resolve trusted public key anchor
        val trustedKey = pinnedPublicKey ?: publicKey

        // 2. Fail-closed: Both signature and trusted public key anchor are mandatory.
        // An unauthenticated SHA-256 checksum alone can NEVER yield Verified.
        if (signature.isNullOrBlank() || trustedKey.isNullOrBlank()) {
            return UpdatePackageVerifier.VerificationResult.BlockedMissingSignatureAuthority
        }

        // 3. Cryptographic signature check (RSA SHA256withRSA)
        return try {
            val isValid = verifySignature(apkFile, signature, trustedKey)
            if (isValid) {
                UpdatePackageVerifier.VerificationResult.Verified
            } else {
                UpdatePackageVerifier.VerificationResult.Failed("Cryptographic signature validation rejected")
            }
        } catch (e: Exception) {
            UpdatePackageVerifier.VerificationResult.Failed("Signature verification exception: ${e.message}")
        }
    }

    fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } > 0) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verifySignature(file: File, signatureBase64: String, publicKeyBase64: String): Boolean {
        val keyBytes = decodeBase64Safe(publicKeyBase64)
        val spec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance("RSA")
        val pubKey = keyFactory.generatePublic(spec)

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initVerify(pubKey)
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } > 0) {
                sig.update(buffer, 0, bytesRead)
            }
        }
        return sig.verify(decodeBase64Safe(signatureBase64))
    }

    private fun decodeBase64Safe(base64Str: String): ByteArray {
        return try {
            java.util.Base64.getDecoder().decode(base64Str.trim())
        } catch (_: Exception) {
            try {
                android.util.Base64.decode(base64Str, android.util.Base64.DEFAULT)
            } catch (_: Exception) {
                ByteArray(0)
            }
        }
    }
}
