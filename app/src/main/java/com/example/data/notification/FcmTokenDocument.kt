package com.example.data.notification

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.IgnoreExtraProperties

/**
 * Represents an FCM device registration document stored under:
 * /users/{uid}/fcmTokens/{installationId}
 *
 * Supports multi-device scenarios per user (phone, tablet, etc.)
 * without leaking sensitive data or creating duplicate documents.
 */
@IgnoreExtraProperties
data class FcmTokenDocument(
    val token: String = "",
    val installationId: String = "",
    val platform: String = "android",
    val deviceModel: String = "",
    val osVersion: String = "",
    val appVersion: String = "",
    val isActive: Boolean = true,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val lastSeenAt: Long = 0L
) {
    fun toFirestoreMap(includeCreatedAt: Boolean = true): Map<String, Any> {
        val map = mutableMapOf<String, Any>(
            "token" to token,
            "installationId" to installationId,
            "platform" to platform,
            "deviceModel" to deviceModel,
            "osVersion" to osVersion,
            "appVersion" to appVersion,
            "isActive" to isActive,
            "updatedAt" to FieldValue.serverTimestamp(),
            "lastSeenAt" to FieldValue.serverTimestamp()
        )
        // On create, Firestore Rules require createdAt == request.time.
        // On update, omit createdAt entirely so merge preserves the immutable value.
        if (includeCreatedAt) {
            map["createdAt"] = FieldValue.serverTimestamp()
        }
        return map
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): FcmTokenDocument {
            return FcmTokenDocument(
                token = map["token"] as? String ?: "",
                installationId = map["installationId"] as? String ?: "",
                platform = map["platform"] as? String ?: "android",
                deviceModel = map["deviceModel"] as? String ?: "",
                osVersion = map["osVersion"] as? String ?: "",
                appVersion = map["appVersion"] as? String ?: "",
                isActive = map["isActive"] as? Boolean ?: true,
                createdAt = when (val value = map["createdAt"]) {
                    is com.google.firebase.Timestamp -> value.toDate().time
                    is Number -> value.toLong()
                    else -> 0L
                },
                updatedAt = when (val value = map["updatedAt"]) {
                    is com.google.firebase.Timestamp -> value.toDate().time
                    is Number -> value.toLong()
                    else -> 0L
                },
                lastSeenAt = when (val value = map["lastSeenAt"]) {
                    is com.google.firebase.Timestamp -> value.toDate().time
                    is Number -> value.toLong()
                    else -> 0L
                }
            )
        }
    }
}
