package com.example.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.data.model.AppConfig
import com.example.utils.NetworkUtils
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Publication status defined canonically by CineStream Admin Dashboard
 * under /app_updates/{updateId}.
 */
enum class AppUpdateStatus {
    PUBLISHED,
    DRAFT,
    ARCHIVED,
    UNKNOWN
}

data class AppUpdateInfo(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val releaseNotes: String = "",
    val downloadUrl: String = "",
    val isMandatory: Boolean = false,
    val publishedAt: Long = System.currentTimeMillis(),
    val currentVersionName: String = "",
    val expectedSha256: String? = null,
    val signature: String? = null,
    val publicKey: String? = null,
    val minVersionCode: Long = 0L,
    val status: String = "PUBLISHED"
)

sealed class UpdateCheckResult {
    data class UpdateAvailable(val info: AppUpdateInfo) : UpdateCheckResult()
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateCheckResult()
    object NoInternet : UpdateCheckResult()
    data class Error(val message: String) : UpdateCheckResult()
}

object AppUpdateManager {
    const val APP_UPDATES_COLLECTION = "app_updates"
    private const val TAG = "AppUpdateManager"

    fun getCurrentVersionInfo(context: Context): Pair<Long, String> {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            Pair(code, pInfo.versionName ?: "1.0.0")
        } catch (e: Exception) {
            Pair(1L, "1.0.0")
        }
    }

    /**
     * Resolves the canonical publication status of an update document.
     */
    fun extractPublicationStatus(data: Map<String, Any?>): AppUpdateStatus {
        val rawStatus = (data["status"] as? String)?.trim()
        if (rawStatus != null) {
            return when (rawStatus.uppercase()) {
                "PUBLISHED" -> AppUpdateStatus.PUBLISHED
                "DRAFT" -> AppUpdateStatus.DRAFT
                "ARCHIVED" -> AppUpdateStatus.ARCHIVED
                else -> AppUpdateStatus.UNKNOWN
            }
        }
        return AppUpdateStatus.UNKNOWN
    }

    /**
     * Evaluates whether an update record is published.
     * Invariants:
     * 1. Canonical "status" is the primary source of truth.
     * 2. "DRAFT" and "ARCHIVED" are strictly NEVER published, regardless of legacy flags.
     * 3. Unknown or invalid statuses are NEVER considered published by default (fail closed).
     * 4. Legacy fields ("isActive", "published", "active") are honored ONLY when standard "status" is absent.
     */
    fun isUpdatePublished(data: Map<String, Any?>): Boolean {
        val canonicalStatus = extractPublicationStatus(data)

        // Strict: DRAFT and ARCHIVED can NEVER be active or published
        if (canonicalStatus == AppUpdateStatus.DRAFT || canonicalStatus == AppUpdateStatus.ARCHIVED) {
            return false
        }
        if (canonicalStatus == AppUpdateStatus.PUBLISHED) {
            return true
        }

        // If standard "status" was provided but unknown, fail closed (do NOT consider published by default)
        val rawStatus = (data["status"] as? String)?.trim()
        if (!rawStatus.isNullOrBlank()) {
            return false
        }

        // Status is absent/null: check legacy boolean/string fields safely
        val rawLegacy = data["isActive"] ?: data["published"] ?: data["active"]
        return when (rawLegacy) {
            is Boolean -> rawLegacy
            is String -> {
                val s = rawLegacy.trim().lowercase()
                s == "true" || s == "1" || s == "published" || s == "active"
            }
            is Number -> rawLegacy.toInt() == 1
            else -> false // Unknown status is NOT published by default (fail closed)
        }
    }

    /**
     * Parses an update document map from /app_updates/{updateId}.
     * Returns null if the update is unpublished (DRAFT/ARCHIVED), missing an APK URL,
     * or contains an invalid versionCode.
     */
    fun parseUpdateData(
        data: Map<String, Any?>,
        currentCode: Long,
        currentVersionName: String = "1.0.0"
    ): AppUpdateInfo? {
        if (!isUpdatePublished(data)) {
            return null
        }

        val targetCode = when (val c = data["versionCode"] ?: data["latestVersionCode"] ?: data["version_code"]) {
            is Number -> c.toLong()
            is String -> c.toLongOrNull() ?: 0L
            else -> 0L
        }
        if (targetCode <= 0L) return null

        val url = when (val u = data["apkUrl"] ?: data["downloadUrl"] ?: data["updateUrl"] ?: data["apk_url"]) {
            is String -> u.trim()
            else -> ""
        }
        if (url.isBlank()) return null

        val targetName = when (val n = data["versionName"] ?: data["version"] ?: data["latestVersionName"] ?: data["version_name"]) {
            is String -> n.trim().ifBlank { "v$targetCode" }
            else -> "v$targetCode"
        }

        val notes = when (val r = data["releaseNotes"] ?: data["notes"] ?: data["description"] ?: data["release_notes"]) {
            is String -> r.trim()
            else -> ""
        }

        val minCode = when (val m = data["minVersionCode"] ?: data["minimumVersionCode"] ?: data["min_version_code"]) {
            is Number -> m.toLong()
            is String -> m.toLongOrNull() ?: 0L
            else -> 0L
        }

        val isMandatoryFlag = when (val mand = data["mandatoryUpdate"] ?: data["isMandatory"] ?: data["mandatory"] ?: data["forceUpdate"]) {
            is Boolean -> mand
            is String -> mand.trim().equals("true", ignoreCase = true) || mand.trim() == "1"
            is Number -> mand.toInt() == 1
            else -> false
        }

        val createdAt = when (val ca = data["createdAt"] ?: data["publishedAt"] ?: data["updatedAt"]) {
            is com.google.firebase.Timestamp -> ca.toDate().time
            is Number -> ca.toLong()
            else -> System.currentTimeMillis()
        }

        val sha256 = when (val s = data["apkSha256"] ?: data["sha256"] ?: data["apk_sha256"] ?: data["checksum"] ?: data["hash"]) {
            is String -> s.trim().ifBlank { null }
            else -> null
        }

        val signature = when (val sig = data["signature"] ?: data["sig"]) {
            is String -> sig.trim().ifBlank { null }
            else -> null
        }

        val publicKey = when (val pk = data["publicKey"] ?: data["public_key"]) {
            is String -> pk.trim().ifBlank { null }
            else -> null
        }

        val isBelowMin = currentCode < minCode
        val isUpdateAvailable = targetCode > currentCode && url.isNotBlank()
        val isMandatory = isBelowMin || (isUpdateAvailable && isMandatoryFlag)

        val statusString = (data["status"] as? String)?.trim()?.uppercase() ?: "PUBLISHED"

        return AppUpdateInfo(
            versionCode = targetCode,
            versionName = targetName,
            releaseNotes = notes,
            downloadUrl = url,
            isMandatory = isMandatory,
            publishedAt = createdAt,
            currentVersionName = currentVersionName,
            expectedSha256 = sha256,
            signature = signature,
            publicKey = publicKey,
            minVersionCode = minCode,
            status = statusString
        )
    }

    /**
     * Parses a Firestore DocumentSnapshot from /app_updates/{updateId}.
     */
    fun parseUpdateDoc(
        doc: DocumentSnapshot,
        currentCode: Long,
        currentVersionName: String = "1.0.0"
    ): AppUpdateInfo? {
        val data = doc.data ?: return null
        return parseUpdateData(data, currentCode, currentVersionName)
    }

    /**
     * Evaluates a list of update documents and an optional fallback /config/app.
     * Invariants:
     * 1. Only PUBLISHED updates are considered for update availability.
     * 2. If /app_updates contains unpublished records (e.g. DRAFT or ARCHIVED) newer than currentCode,
     *    fallback to /config/app is strictly suppressed to avoid bypassing the unpublished newer release.
     */
    fun selectUpdateFromData(
        appUpdateDocs: List<Map<String, Any?>>,
        currentCode: Long,
        currentVersionName: String = "1.0.0",
        fallbackConfig: AppConfig? = null
    ): UpdateCheckResult {
        if (appUpdateDocs.isNotEmpty()) {
            var latestPublishedInfo: AppUpdateInfo? = null
            var maxPublishedCode = currentCode
            var latestPublishedVersionLabel = currentVersionName
            var hasValidPublishedDoc = false
            var maxUnpublishedNewerCode = 0L

            for (data in appUpdateDocs) {
                val docCode = when (val c = data["versionCode"] ?: data["latestVersionCode"] ?: data["version_code"]) {
                    is Number -> c.toLong()
                    is String -> c.toLongOrNull() ?: 0L
                    else -> 0L
                }

                if (isUpdatePublished(data)) {
                    val parsed = parseUpdateData(data, currentCode, currentVersionName) ?: continue
                    hasValidPublishedDoc = true
                    if (parsed.versionCode > maxPublishedCode) {
                        maxPublishedCode = parsed.versionCode
                        latestPublishedVersionLabel = parsed.versionName
                        if (parsed.versionCode > currentCode || parsed.isMandatory) {
                            latestPublishedInfo = parsed
                        }
                    }
                } else {
                    // Track unpublished records (e.g. DRAFT or ARCHIVED) that are newer than currentCode
                    if (docCode > currentCode && docCode > maxUnpublishedNewerCode) {
                        maxUnpublishedNewerCode = docCode
                    }
                }
            }

            if (latestPublishedInfo != null) {
                return UpdateCheckResult.UpdateAvailable(latestPublishedInfo)
            } else if (hasValidPublishedDoc) {
                return UpdateCheckResult.UpToDate(
                    currentVersion = currentVersionName,
                    latestVersion = latestPublishedVersionLabel
                )
            } else if (maxUnpublishedNewerCode > 0L) {
                // All newer records in /app_updates are unpublished (DRAFT or ARCHIVED).
                // Invariant: Do NOT fall back to /config/app to bypass an unpublished newer update!
                return UpdateCheckResult.UpToDate(
                    currentVersion = currentVersionName,
                    latestVersion = currentVersionName
                )
            }
        }

        // Fallback to /config/app if provided and /app_updates had no valid/unpublished blocking records
        if (fallbackConfig != null) {
            val isBelowMin = currentCode < fallbackConfig.minVersionCode
            val isUpdateAvailable = fallbackConfig.latestVersionCode > currentCode && fallbackConfig.apkUrl.isNotBlank()
            val isMandatory = isBelowMin || (isUpdateAvailable && fallbackConfig.forceUpdate)

            return if (isUpdateAvailable || isBelowMin) {
                val info = AppUpdateInfo(
                    versionCode = fallbackConfig.latestVersionCode,
                    versionName = if (fallbackConfig.latestVersionName.isNotBlank()) fallbackConfig.latestVersionName else "v${fallbackConfig.latestVersionCode}",
                    releaseNotes = fallbackConfig.releaseNotes,
                    downloadUrl = fallbackConfig.apkUrl,
                    isMandatory = isMandatory,
                    publishedAt = fallbackConfig.updatedAt,
                    currentVersionName = currentVersionName,
                    expectedSha256 = fallbackConfig.apkSha256.ifBlank { null }
                )
                UpdateCheckResult.UpdateAvailable(info)
            } else {
                UpdateCheckResult.UpToDate(
                    currentVersion = currentVersionName,
                    latestVersion = if (fallbackConfig.latestVersionName.isNotBlank()) fallbackConfig.latestVersionName else currentVersionName
                )
            }
        }

        return UpdateCheckResult.UpToDate(
            currentVersion = currentVersionName,
            latestVersion = currentVersionName
        )
    }

    /**
     * Checks for updates and returns a detailed UpdateCheckResult.
     * Priority 1: Canonical /app_updates/{updateId} collection.
     * Priority 2: Fallback to /config/app AppConfig document (only if /app_updates has no blocking records).
     */
    suspend fun checkForUpdateResult(context: Context): UpdateCheckResult = withContext(Dispatchers.IO) {
        if (!NetworkUtils.isInternetAvailable(context)) {
            return@withContext UpdateCheckResult.NoInternet
        }

        try {
            val (currentCode, currentVersionName) = getCurrentVersionInfo(context)
            val firestore = FirebaseFirestore.getInstance()

            // 1. Try canonical /app_updates collection
            try {
                val updatesSnapshot = withTimeoutOrNull(7000L) {
                    try {
                        firestore.collection(APP_UPDATES_COLLECTION)
                            .get(Source.SERVER).await()
                    } catch (e: Exception) {
                        try {
                            firestore.collection(APP_UPDATES_COLLECTION).get().await()
                        } catch (e2: Exception) {
                            null
                        }
                    }
                }

                if (updatesSnapshot != null && !updatesSnapshot.isEmpty) {
                    val dataList = updatesSnapshot.documents.mapNotNull { it.data }
                    val decision = selectUpdateFromData(
                        appUpdateDocs = dataList,
                        currentCode = currentCode,
                        currentVersionName = currentVersionName,
                        fallbackConfig = null
                    )

                    // If /app_updates resolved to UpdateAvailable or UpToDate, return immediately
                    // Note: if all newer updates in /app_updates were DRAFT/ARCHIVED, selectUpdateFromData
                    // returns UpToDate, which prevents leaking the draft via /config/app.
                    if (decision is UpdateCheckResult.UpdateAvailable || decision is UpdateCheckResult.UpToDate) {
                        return@withContext decision
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Notice: /app_updates check skipped: ${e.message}")
            }

            // 2. Fallback to /config/app (only when /app_updates is empty or unreachable)
            val fetchedDoc = withTimeoutOrNull(10000L) {
                val docRef = firestore.collection(AppConfig.CONFIG_COLLECTION)
                    .document(AppConfig.CONFIG_DOCUMENT)

                try {
                    docRef.get(Source.SERVER).await()
                } catch (e: Exception) {
                    try {
                        docRef.get().await()
                    } catch (e2: Exception) {
                        null
                    }
                }
            }

            if (fetchedDoc == null || !fetchedDoc.exists()) {
                val isAr = java.util.Locale.getDefault().language == "ar"
                return@withContext UpdateCheckResult.Error(
                    if (isAr) "تعذر الاتصال بخادم التحديثات، يرجى المحاولة لاحقاً"
                    else "Unable to reach update server, please try again later"
                )
            }

            val config = AppConfig.fromDocument(fetchedDoc)
            return@withContext selectUpdateFromData(
                appUpdateDocs = emptyList(),
                currentCode = currentCode,
                currentVersionName = currentVersionName,
                fallbackConfig = config
            )
        } catch (e: Exception) {
            e.printStackTrace()
            UpdateCheckResult.Error(e.localizedMessage ?: "Failed to connect to update server")
        }
    }

    /**
     * Checks for updates matching the Firebase Contract.
     * Backwards-compatible with callers expecting AppUpdateInfo?
     */
    suspend fun checkForUpdate(context: Context): AppUpdateInfo? {
        return when (val result = checkForUpdateResult(context)) {
            is UpdateCheckResult.UpdateAvailable -> result.info
            else -> null
        }
    }

    /**
     * F-007: Cryptographically verifies a downloaded update APK before allowing installation.
     * Fails closed: Rejects unauthenticated SHA-256 hashes, missing signatures, or untrusted keys.
     * Metadata-provided public keys are explicitly ignored and never establish trust.
     */
    suspend fun verifyDownloadedUpdate(
        apkFile: java.io.File,
        updateInfo: AppUpdateInfo,
        verifier: UpdatePackageVerifier = CryptographicUpdateVerifier
    ): UpdatePackageVerifier.VerificationResult {
        return verifier.verifyPackage(
            apkFile = apkFile,
            expectedSha256 = updateInfo.expectedSha256,
            signature = updateInfo.signature,
            publicKey = null // Metadata publicKey is NEVER trusted as an anchor
        )
    }
}
