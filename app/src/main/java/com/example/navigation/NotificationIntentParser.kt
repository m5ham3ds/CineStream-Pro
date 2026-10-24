package com.example.navigation

import android.content.Intent
import android.os.Bundle

/**
 * Pure, unit-testable validator and parser for notification-based navigation.
 * Validates untrusted incoming Intent extras against strict security whitelists
 * and transforms them into known, canonical internal NavHost destinations.
 */
object NotificationIntentParser {

    private val SAFE_ID_REGEX = Regex("^[a-zA-Z0-9_.-]{1,64}$")
    private val DISALLOWED_CHARS = charArrayOf('/', '\\', '?', '&', '#', ':', ' ', '\t', '\n', '\r')

    /**
     * Validates whether a given content identifier is safe and adheres to strict formatting bounds.
     * Rejects path traversal, query injections, URLs, whitespace, control characters, and lengths > 64.
     */
    fun isValidId(id: String?): Boolean {
        if (id.isNullOrBlank()) return false
        val trimmed = id.trim()
        if (trimmed.length > 64) return false
        if (trimmed == "." || trimmed == ".." || trimmed.contains("..")) return false
        if (trimmed.any { it in DISALLOWED_CHARS }) return false
        return SAFE_ID_REGEX.matches(trimmed)
    }

    /**
     * Parses an Android Intent and resolves it to a safe internal route string.
     * Returns null if the Intent is null, unparseable, or fails security validation.
     */
    fun parse(intent: Intent?): String? {
        if (intent == null) return null
        val extras = intent.extras ?: return null
        return parse(extras)
    }

    /**
     * Parses an Android Bundle and resolves it to a safe internal route string.
     */
    fun parse(bundle: Bundle?): String? {
        if (bundle == null) return null

        val navigateTo = bundle.getString(NotificationNavigationContract.EXTRA_NAVIGATE_TO)
            ?: bundle.getString(NotificationNavigationContract.EXTRA_NAVIGATE_TO_CAMEL)
        val movieId = bundle.getString(NotificationNavigationContract.EXTRA_MOVIE_ID)
        val seriesId = bundle.getString(NotificationNavigationContract.EXTRA_SERIES_ID)
        val episodeId = bundle.getString(NotificationNavigationContract.EXTRA_EPISODE_ID)
        val type = bundle.getString(NotificationNavigationContract.EXTRA_NOTIFICATION_TYPE)

        return parseRaw(
            navigateTo = navigateTo,
            movieId = movieId,
            seriesId = seriesId,
            episodeId = episodeId,
            type = type
        )
    }

    /**
     * Pure validator and resolver given individual fields.
     */
    fun parseRaw(
        navigateTo: String?,
        movieId: String? = null,
        seriesId: String? = null,
        episodeId: String? = null,
        type: String? = null
    ): String? {
        if (navigateTo.isNullOrBlank()) return null

        val cleanTarget = navigateTo.trim()

        // Immediate rejection for suspicious patterns in navigateTo
        if (cleanTarget.any { it in DISALLOWED_CHARS } || cleanTarget.contains("..")) {
            return null
        }
        val lowerTarget = cleanTarget.lowercase()

        // Guard: target must be explicitly whitelisted
        if (!NotificationNavigationContract.ALLOWED_TARGETS.contains(lowerTarget)) {
            return null
        }

        // Static routes mapping (e.g. home, movies, series, support -> help_support)
        val staticRoute = NotificationNavigationContract.STATIC_ROUTE_MAPPING[lowerTarget]
        if (staticRoute != null) {
            return staticRoute
        }

        // Handle detail target
        if (lowerTarget == NotificationNavigationContract.TARGET_DETAIL) {
            val normalizedType = type?.trim()?.lowercase()

            // Missing or empty type is explicitly rejected for detail
            if (normalizedType.isNullOrBlank()) {
                return null
            }

            val hasValidMovieId = isValidId(movieId)
            val hasValidSeriesId = isValidId(seriesId)
            val hasMovieInput = !movieId.isNullOrBlank()
            val hasSeriesInput = !seriesId.isNullOrBlank()

            // Reject conflicting IDs (both provided)
            if (hasMovieInput && hasSeriesInput) {
                return null
            }

            return when (normalizedType) {
                NotificationNavigationContract.TYPE_MOVIE -> {
                    // Type mismatch or conflict: cannot provide seriesId when type is movie
                    if (hasSeriesInput) return null
                    // Missing or invalid movieId is rejected
                    if (!hasValidMovieId) return null
                    Screen.MovieDetails.createRoute(movieId!!.trim(), autoPlay = false)
                }
                NotificationNavigationContract.TYPE_SERIES -> {
                    // Type mismatch or conflict: cannot provide movieId when type is series
                    if (hasMovieInput) return null
                    // Missing or invalid seriesId is rejected
                    if (!hasValidSeriesId) return null
                    Screen.SeriesDetails.createRoute(seriesId!!.trim(), autoPlay = false)
                }
                else -> {
                    // Unsupported or mismatched detail type (e.g., info, update, announcement, unknown)
                    null
                }
            }
        }

        return null
    }

    /**
     * Extracts a safe notificationId from an Intent.
     */
    fun extractNotificationId(intent: Intent?): String? {
        if (intent == null) return null
        val id = intent.getStringExtra(NotificationNavigationContract.EXTRA_NOTIFICATION_ID)
            ?: intent.getStringExtra("id")
        return if (isValidId(id)) id!!.trim() else null
    }

    /**
     * Checks if the intent has already been consumed by MainActivity navigation handling.
     */
    fun isIntentConsumed(intent: Intent?): Boolean {
        if (intent == null) return true
        return intent.getBooleanExtra(NotificationNavigationContract.EXTRA_INTENT_CONSUMED, false)
    }

    /**
     * Marks the intent as consumed to prevent duplicate execution during rotation or recreation.
     */
    fun markIntentConsumed(intent: Intent?) {
        if (intent == null) return
        intent.putExtra(NotificationNavigationContract.EXTRA_INTENT_CONSUMED, true)
        // Clean out sensitive navigation extras from the intent copy
        intent.removeExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO)
        intent.removeExtra(NotificationNavigationContract.EXTRA_NAVIGATE_TO_CAMEL)
        intent.removeExtra(NotificationNavigationContract.EXTRA_MOVIE_ID)
        intent.removeExtra(NotificationNavigationContract.EXTRA_SERIES_ID)
        intent.removeExtra(NotificationNavigationContract.EXTRA_EPISODE_ID)
    }
}
