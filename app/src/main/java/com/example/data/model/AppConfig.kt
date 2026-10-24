package com.example.data.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.IgnoreExtraProperties

@IgnoreExtraProperties
data class AppConfig(
    val maintenanceEnabled: Boolean = false,
    val maintenanceTitle: String = "Under Scheduled Maintenance",
    val maintenanceMessage: String = "CineStream is temporarily offline for scheduled maintenance.",
    val maintenanceAllowedRoles: List<String> = listOf("admin", "superadmin"),
    val minimumVersionCode: Long = 1L,
    val latestVersionCode: Long = 1L,
    val latestVersionName: String = "1.0.0",
    val apkUrl: String = "",
    val apkSha256: String = "",
    val mandatoryUpdate: Boolean = false,
    val releaseNotes: String = "",
    val defaultOfflineDays: Long = 2L,
    val defaultForcedAds: Long = 5L,
    val providersJson: String = "{}",
    val updatedAt: Long = 0L
) {
    // Backwards-compatible aliases for legacy callers
    val minVersionCode: Long get() = minimumVersionCode
    val forceUpdate: Boolean get() = mandatoryUpdate
    val offlineWatchDaysLimit: Long get() = defaultOfflineDays
    val forcedAdsCount: Long get() = defaultForcedAds

    companion object {
        const val CONFIG_COLLECTION = "config"
        const val CONFIG_DOCUMENT = "app"

        fun fromDocument(doc: DocumentSnapshot?): AppConfig {
            if (doc == null || !doc.exists()) return AppConfig()

            val rawMaintenance = doc.get("maintenanceEnabled")
                ?: doc.get("maintenance")
                ?: doc.get("isMaintenance")
                ?: doc.get("maintenance_enabled")
                ?: doc.get("maintenanceMode")

            val isMaintenance = when (rawMaintenance) {
                is Boolean -> rawMaintenance
                is String -> rawMaintenance.trim().equals("true", ignoreCase = true) || rawMaintenance.trim() == "1"
                is Number -> rawMaintenance.toInt() == 1
                else -> false
            }

            val statusStr = doc.getString("status")?.trim()
            val statusMaintenance = statusStr.equals("maintenance", ignoreCase = true)
            val finalIsMaintenance = isMaintenance || statusMaintenance

            val title = doc.getString("maintenanceTitle")
                ?: doc.getString("maintenance_title")
                ?: doc.getString("title")
                ?: ""

            val message = doc.getString("maintenanceMessage")
                ?: doc.getString("maintenance_message")
                ?: doc.getString("message")
                ?: ""

            @Suppress("UNCHECKED_CAST")
            val allowedRoles = (doc.get("maintenanceAllowedRoles") as? List<String>)
                ?: listOf("admin", "superadmin")

            // Canonical: minimumVersionCode
            val minCode = doc.getLong("minimumVersionCode")
                ?: doc.getLong("minVersionCode")
                ?: 1L

            val latestCode = doc.getLong("latestVersionCode")
                ?: 1L

            val latestName = doc.getString("latestVersionName")
                ?: "1.0.0"

            val apk = doc.getString("apkUrl")
                ?: ""

            val sha = doc.getString("apkSha256")
                ?: doc.getString("sha256")
                ?: ""

            // Canonical: mandatoryUpdate
            val force = doc.getBoolean("mandatoryUpdate")
                ?: doc.getBoolean("forceUpdate")
                ?: false

            val notes = doc.getString("releaseNotes")
                ?: ""

            // Canonical: defaultOfflineDays
            val offlineDays = doc.getLong("defaultOfflineDays")
                ?: doc.getLong("offlineWatchDaysLimit")
                ?: 2L

            // Canonical: defaultForcedAds
            val forcedAds = doc.getLong("defaultForcedAds")
                ?: doc.getLong("forcedAdsCount")
                ?: 5L

            val providers = doc.getString("providersJson") ?: "{}"

            val updatedTime = when (val u = doc.get("updatedAt")) {
                is Timestamp -> u.toDate().time
                is Number -> u.toLong()
                else -> 0L
            }

            return AppConfig(
                maintenanceEnabled = finalIsMaintenance,
                maintenanceTitle = title,
                maintenanceMessage = message,
                maintenanceAllowedRoles = allowedRoles,
                minimumVersionCode = minCode,
                latestVersionCode = latestCode,
                latestVersionName = latestName,
                apkUrl = apk,
                apkSha256 = sha,
                mandatoryUpdate = force,
                releaseNotes = notes,
                defaultOfflineDays = offlineDays,
                defaultForcedAds = forcedAds,
                providersJson = providers,
                updatedAt = updatedTime
            )
        }
    }
}
