package com.example.data.notification

/**
 * Validates whether an incoming FCM message target matches the current app user state.
 *
 * Rules:
 * - `target = all`: Allowed for all users (guests, authenticated users, or unauthenticated).
 * - `target = user`: Allowed ONLY if the user is authenticated (not anonymous/guest)
 *   and currentUid matches targetUid.
 * - `target = topic`: Disallowed in Phase 2B (topic subscriptions not yet enabled).
 * - Unknown or invalid target: Disallowed.
 */
object FcmTargetValidator {

    fun isTargetValid(
        target: String,
        targetUid: String?,
        currentUid: String?,
        isAnonymous: Boolean = false
    ): Boolean {
        return when (target.trim().lowercase()) {
            FcmPayloadConstants.TARGET_ALL -> true
            FcmPayloadConstants.TARGET_USER -> {
                if (isAnonymous || currentUid.isNullOrBlank() || targetUid.isNullOrBlank()) {
                    false
                } else {
                    currentUid.trim() == targetUid.trim()
                }
            }
            FcmPayloadConstants.TARGET_TOPIC -> false
            else -> false
        }
    }

    fun isTargetValid(
        payload: FcmNotificationPayload,
        currentUid: String?,
        isAnonymous: Boolean = false
    ): Boolean {
        return isTargetValid(
            target = payload.target,
            targetUid = payload.targetUid,
            currentUid = currentUid,
            isAnonymous = isAnonymous
        )
    }
}
