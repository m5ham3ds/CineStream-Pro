package com.example.data.notification

import android.net.Uri
import android.util.Log
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

/**
 * Robust and testable parser for Firebase Cloud Messaging (FCM) messages.
 *
 * Adheres to the FCM Payload Contract and strictly validates all incoming fields
 * to prevent malformed data, command injection, arbitrary navigation, or crash vectors.
 */
object FcmPayloadParser {

    private const val TAG = "FcmPayloadParser"
    private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_.-]{1,128}$")

    /**
     * Parses an incoming [RemoteMessage] from Firebase into a structured [FcmNotificationPayload].
     */
    fun parse(remoteMessage: RemoteMessage): FcmNotificationPayload {
        val notification = remoteMessage.notification
        val data = remoteMessage.data

        val notificationTitle = notification?.title
        val notificationBody = notification?.body
        val notificationImageUrl = notification?.imageUrl?.toString()
        val messageId = remoteMessage.messageId
        val sentTime = remoteMessage.sentTime

        return parseRaw(
            data = data,
            notificationTitle = notificationTitle,
            notificationBody = notificationBody,
            notificationImageUrl = notificationImageUrl,
            messageId = messageId,
            sentTime = sentTime
        )
    }

    /**
     * Pure testable entry point: Parses raw data map and notification components.
     * Can be invoked directly by unit tests without mocking Firebase classes.
     */
    fun parseRaw(
        data: Map<String, String>?,
        notificationTitle: String? = null,
        notificationBody: String? = null,
        notificationImageUrl: String? = null,
        messageId: String? = null,
        sentTime: Long = 0L
    ): FcmNotificationPayload {
        val errors = mutableListOf<String>()

        val hasData = !data.isNullOrEmpty()
        val hasNotification = !notificationTitle.isNullOrBlank() || !notificationBody.isNullOrBlank()

        val payloadType = when {
            hasNotification && hasData -> FcmPayloadType.NOTIFICATION_AND_DATA
            hasData -> FcmPayloadType.DATA_ONLY
            hasNotification -> FcmPayloadType.NOTIFICATION_ONLY
            else -> FcmPayloadType.EMPTY
        }

        // 1. Notification ID
        val rawId = data?.get(FcmPayloadConstants.KEY_NOTIFICATION_ID)
            ?: data?.get(FcmPayloadConstants.KEY_ID_ALIAS)
            ?: messageId
            ?: ""

        val notificationId = sanitizeNotificationId(rawId)

        // 2. Title resolution (data takes precedence, then notification block, then fallback)
        val rawTitle = data?.get(FcmPayloadConstants.KEY_TITLE)?.trim()
            ?: notificationTitle?.trim()
            ?: ""

        // 3. Body / Message resolution
        val rawBody = data?.get(FcmPayloadConstants.KEY_BODY)?.trim()
            ?: data?.get(FcmPayloadConstants.KEY_MESSAGE_ALIAS)?.trim()
            ?: notificationBody?.trim()
            ?: ""

        // Content validation: At least one of title or body should be non-empty
        if (rawTitle.isEmpty() && rawBody.isEmpty()) {
            errors.add("Both title and body are empty")
        }

        // 4. Type resolution and sanitization
        val rawType = data?.get(FcmPayloadConstants.KEY_TYPE)?.trim()?.lowercase()
        val type = when (rawType) {
            FcmPayloadConstants.TYPE_ANNOUNCEMENT,
            FcmPayloadConstants.TYPE_UPDATE,
            FcmPayloadConstants.TYPE_MAINTENANCE,
            FcmPayloadConstants.TYPE_SYSTEM,
            FcmPayloadConstants.TYPE_MOVIE,
            FcmPayloadConstants.TYPE_SERIES,
            FcmPayloadConstants.TYPE_ANIME -> rawType
            FcmPayloadConstants.TYPE_INFO -> FcmPayloadConstants.TYPE_INFO
            null, "" -> FcmPayloadConstants.TYPE_INFO
            else -> {
                // Safely sanitize unexpected type string to alphanumeric
                val sanitized = rawType.replace(Regex("[^a-z0-9_]"), "")
                if (sanitized.isNotBlank()) sanitized else FcmPayloadConstants.TYPE_INFO
            }
        }

        // 5. Targeting
        val rawTarget = data?.get(FcmPayloadConstants.KEY_TARGET)?.trim()?.lowercase()
            ?: data?.get(FcmPayloadConstants.KEY_TARGET_TYPE_ALIAS)?.trim()?.lowercase()
            ?: FcmPayloadConstants.TARGET_ALL

        val target = when (rawTarget) {
            FcmPayloadConstants.TARGET_USER -> FcmPayloadConstants.TARGET_USER
            FcmPayloadConstants.TARGET_TOPIC -> FcmPayloadConstants.TARGET_TOPIC
            else -> FcmPayloadConstants.TARGET_ALL
        }

        val rawTargetUid = data?.get(FcmPayloadConstants.KEY_TARGET_UID)?.trim()
        val targetUid = if (!rawTargetUid.isNullOrEmpty() && SAFE_ID_REGEX.matches(rawTargetUid)) {
            rawTargetUid
        } else if (!rawTargetUid.isNullOrEmpty()) {
            errors.add("Invalid targetUid format: $rawTargetUid")
            null
        } else {
            null
        }

        // 6. Image URL validation
        val rawImageUrl = data?.get(FcmPayloadConstants.KEY_IMAGE_URL)?.trim()
            ?: data?.get(FcmPayloadConstants.KEY_IMAGE_URL_ALIAS)?.trim()
            ?: notificationImageUrl?.trim()

        val imageUrl = validateUrl(rawImageUrl)

        // 7. Media IDs (movieId, seriesId, episodeId)
        val rawMovieId = data?.get(FcmPayloadConstants.KEY_MOVIE_ID)?.trim()
            ?: data?.get(FcmPayloadConstants.KEY_MOVIE_ID_ALIAS)?.trim()
        val movieId = sanitizeMediaId(rawMovieId)

        val rawSeriesId = data?.get(FcmPayloadConstants.KEY_SERIES_ID)?.trim()
            ?: data?.get(FcmPayloadConstants.KEY_SERIES_ID_ALIAS)?.trim()
        val seriesId = sanitizeMediaId(rawSeriesId)

        val rawEpisodeId = data?.get(FcmPayloadConstants.KEY_EPISODE_ID)?.trim()
            ?: data?.get(FcmPayloadConstants.KEY_EPISODE_ID_ALIAS)?.trim()
        val episodeId = sanitizeMediaId(rawEpisodeId)

        // 8. Navigation Target Whitelist
        val rawNavigateTo = data?.get(FcmPayloadConstants.KEY_NAVIGATE_TO)?.trim()?.lowercase()
            ?: data?.get(FcmPayloadConstants.KEY_NAVIGATE_TO_ALIAS)?.trim()?.lowercase()

        val navigateTo = if (rawNavigateTo != null) {
            if (FcmPayloadConstants.ALLOWED_NAVIGATION_TARGETS.contains(rawNavigateTo)) {
                rawNavigateTo
            } else {
                errors.add("Disallowed navigateTo destination: $rawNavigateTo")
                null
            }
        } else {
            null
        }

        // 9. Timestamp
        val rawTime = data?.get(FcmPayloadConstants.KEY_CREATED_AT)
            ?: data?.get(FcmPayloadConstants.KEY_TIMESTAMP_ALIAS)
        val timestamp = parseTimestamp(rawTime, sentTime)

        // 10. Security Checks for sensitive / malicious payload keys
        data?.keys?.forEach { key ->
            if (FcmPayloadConstants.FORBIDDEN_SECURITY_KEYS.contains(key.lowercase())) {
                errors.add("Forbidden security key found in payload: $key")
            }
        }

        val isValid = errors.isEmpty()

        val rawEvent = data?.get(FcmPayloadConstants.KEY_EVENT)?.trim()?.lowercase()
            ?: data?.get(FcmPayloadConstants.KEY_EVENT_ALIAS)?.trim()?.lowercase()
            ?: data?.get(FcmPayloadConstants.KEY_EVENT_SNAKE_ALIAS)?.trim()?.lowercase()
        val event = rawEvent?.ifBlank { null }

        val rawIsAnime = data?.get(FcmPayloadConstants.KEY_IS_ANIME)?.trim()?.lowercase()
            ?: data?.get(FcmPayloadConstants.KEY_IS_ANIME_ALIAS)?.trim()?.lowercase()
        val isAnime = when (rawIsAnime) {
            "true", "1" -> true
            "false", "0" -> false
            else -> (type.equals(FcmPayloadConstants.TYPE_ANIME, ignoreCase = true))
        }

        return FcmNotificationPayload(
            notificationId = notificationId,
            title = rawTitle,
            body = rawBody,
            type = type,
            target = target,
            targetUid = targetUid,
            imageUrl = imageUrl,
            movieId = movieId,
            seriesId = seriesId,
            episodeId = episodeId,
            navigateTo = navigateTo,
            timestamp = timestamp,
            payloadType = payloadType,
            isValid = isValid,
            validationErrors = errors,
            event = event,
            isAnime = isAnime
        )
    }

    private fun sanitizeNotificationId(rawId: String): String {
        val trimmed = rawId.trim()
        return if (trimmed.isNotBlank() && SAFE_ID_REGEX.matches(trimmed)) {
            trimmed
        } else if (trimmed.isNotBlank()) {
            // Strip out unsafe characters, fall back to UUID if empty
            val sanitized = trimmed.replace(Regex("[^a-zA-Z0-9_.-]"), "")
            sanitized.ifBlank { UUID.randomUUID().toString() }
        } else {
            UUID.randomUUID().toString()
        }
    }

    private fun sanitizeMediaId(rawId: String?): String? {
        if (rawId.isNullOrBlank()) return null
        val trimmed = rawId.trim()
        return if (SAFE_ID_REGEX.matches(trimmed)) {
            trimmed
        } else {
            val sanitized = trimmed.replace(Regex("[^a-zA-Z0-9_.-]"), "")
            sanitized.ifBlank { null }
        }
    }

    private fun validateUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()
        return try {
            val uri = Uri.parse(trimmed)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "https" || scheme == "http") && !uri.host.isNullOrBlank()) {
                trimmed
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTimestamp(rawTime: String?, sentTime: Long): Long {
        if (rawTime != null) {
            rawTime.toLongOrNull()?.let { return it }
        }
        if (sentTime > 0L) {
            return sentTime
        }
        return System.currentTimeMillis()
    }
}
