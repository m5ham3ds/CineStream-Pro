package com.example.data.notification

import com.example.data.model.NotificationItem
import java.util.UUID

/**
 * Standard keys used in FCM payloads.
 * Includes official keys and backward-compatible aliases matching the Firestore /notifications schema.
 */
object FcmPayloadConstants {
    const val KEY_NOTIFICATION_ID = "notificationId"
    const val KEY_ID_ALIAS = "id"

    const val KEY_TITLE = "title"
    const val KEY_BODY = "body"
    const val KEY_MESSAGE_ALIAS = "message"

    const val KEY_TYPE = "type"
    const val KEY_TARGET = "target"
    const val KEY_TARGET_TYPE_ALIAS = "targetType"
    const val KEY_TARGET_UID = "targetUid"

    const val KEY_IMAGE_URL = "imageUrl"
    const val KEY_IMAGE_URL_ALIAS = "image_url"

    const val KEY_MOVIE_ID = "movieId"
    const val KEY_MOVIE_ID_ALIAS = "movie_id"

    const val KEY_SERIES_ID = "seriesId"
    const val KEY_SERIES_ID_ALIAS = "series_id"

    const val KEY_EPISODE_ID = "episodeId"
    const val KEY_EPISODE_ID_ALIAS = "episode_id"

    const val KEY_EVENT = "event"
    const val KEY_EVENT_ALIAS = "eventType"
    const val KEY_EVENT_SNAKE_ALIAS = "event_type"

    const val KEY_IS_ANIME = "isAnime"
    const val KEY_IS_ANIME_ALIAS = "is_anime"

    const val KEY_NAVIGATE_TO = "navigateTo"
    const val KEY_NAVIGATE_TO_ALIAS = "navigate_to"

    const val KEY_CREATED_AT = "createdAt"
    const val KEY_TIMESTAMP_ALIAS = "timestamp"

    // Recognized notification types
    const val TYPE_INFO = "info"
    const val TYPE_ANNOUNCEMENT = "announcement"
    const val TYPE_UPDATE = "update"
    const val TYPE_MAINTENANCE = "maintenance"
    const val TYPE_SYSTEM = "system"
    const val TYPE_MOVIE = "movie"
    const val TYPE_SERIES = "series"
    const val TYPE_ANIME = "anime"

    // Recognized targeting types
    const val TARGET_ALL = "all"
    const val TARGET_USER = "user"
    const val TARGET_TOPIC = "topic"

    // Whitelist of valid in-app navigation targets (guards against arbitrary navigation/intent execution)
    val ALLOWED_NAVIGATION_TARGETS = setOf(
        "home",
        "movies",
        "series",
        "anime",
        "library",
        "notifications",
        "downloads",
        "share",
        "settings",
        "movie_details",
        "series_details",
        "player"
    )

    // Security sensitive keys that must never be evaluated or trusted from untrusted FCM payloads
    val FORBIDDEN_SECURITY_KEYS = setOf(
        "role",
        "isadmin",
        "admin",
        "auth",
        "authtoken",
        "password",
        "credential",
        "token",
        "secret"
    )
}

/**
 * Classification of an incoming FCM RemoteMessage based on whether it carries
 * a Notification block, Data payload, both, or is empty.
 */
enum class FcmPayloadType {
    NOTIFICATION_ONLY,
    DATA_ONLY,
    NOTIFICATION_AND_DATA,
    EMPTY
}

/**
 * Clean, immutable model representing a validated and parsed FCM message payload.
 *
 * Designed to cleanly translate remote push messages into the application's local
 * notification domain without exposing raw maps or unvalidated strings.
 */
data class FcmNotificationPayload(
    val notificationId: String,
    val title: String,
    val body: String,
    val type: String = FcmPayloadConstants.TYPE_INFO,
    val target: String = FcmPayloadConstants.TARGET_ALL,
    val targetUid: String? = null,
    val imageUrl: String? = null,
    val movieId: String? = null,
    val seriesId: String? = null,
    val episodeId: String? = null,
    val navigateTo: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val payloadType: FcmPayloadType = FcmPayloadType.DATA_ONLY,
    val isValid: Boolean = true,
    val validationErrors: List<String> = emptyList(),
    val event: String? = null,
    val isAnime: Boolean = false
) {
    /**
     * Converts this validated FCM payload into the local Room [NotificationItem]
     * for persistence in the Notification Center database.
     */
    fun toNotificationItem(): NotificationItem {
        return NotificationItem(
            id = notificationId.ifBlank { UUID.randomUUID().toString() },
            title = title,
            message = body,
            timestamp = timestamp,
            isRead = false,
            imageUrl = imageUrl,
            type = type
        )
    }
}
