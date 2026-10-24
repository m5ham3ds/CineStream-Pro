package com.example.data.model

import com.google.firebase.firestore.FieldValue

/**
 * Immutable domain model representing granular user notification preferences.
 *
 * All settings are independent and default to true.
 * Disabling [notificationsEnabled] acts as a Master switch that suppresses notification
 * tray delivery while keeping the individual category preferences intact.
 */
data class NotificationPreferences(
    val notificationsEnabled: Boolean = true,
    val announcementsEnabled: Boolean = true,
    val appUpdatesEnabled: Boolean = true,
    val maintenanceEnabled: Boolean = true,
    val newMoviesEnabled: Boolean = true,
    val newTvSeriesEnabled: Boolean = true,
    val newAnimeEnabled: Boolean = true,
    val newTvEpisodesEnabled: Boolean = true,
    val newAnimeEpisodesEnabled: Boolean = true,
    val newTvSeasonsEnabled: Boolean = true,
    val newAnimeSeasonsEnabled: Boolean = true
) {

    /**
     * Converts this model to a Firestore-compatible map matching the specification in Phase 4B.
     */
    fun toFirestoreMap(): Map<String, Any> {
        return mapOf(
            "notificationsEnabled" to notificationsEnabled,
            "announcementsEnabled" to announcementsEnabled,
            "appUpdatesEnabled" to appUpdatesEnabled,
            "maintenanceEnabled" to maintenanceEnabled,
            "newMoviesEnabled" to newMoviesEnabled,
            "newTvSeriesEnabled" to newTvSeriesEnabled,
            "newAnimeEnabled" to newAnimeEnabled,
            "newTvEpisodesEnabled" to newTvEpisodesEnabled,
            "newAnimeEpisodesEnabled" to newAnimeEpisodesEnabled,
            "newTvSeasonsEnabled" to newTvSeasonsEnabled,
            "newAnimeSeasonsEnabled" to newAnimeSeasonsEnabled,
            "enabled" to notificationsEnabled,
            "announcements" to announcementsEnabled,
            "appUpdates" to appUpdatesEnabled,
            "maintenance" to maintenanceEnabled,
            "newMovies" to newMoviesEnabled,
            "newTvSeries" to newTvSeriesEnabled,
            "newAnime" to newAnimeEnabled,
            "newTvEpisodes" to newTvEpisodesEnabled,
            "newAnimeEpisodes" to newAnimeEpisodesEnabled,
            "newTvSeasons" to newTvSeasonsEnabled,
            "newAnimeSeasons" to newAnimeSeasonsEnabled,
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    companion object {
        val DEFAULT = NotificationPreferences()

        /**
         * Safely parses Firestore document data with fallback to defaults.
         */
        fun fromFirestoreMap(data: Map<String, Any?>?): NotificationPreferences {
            if (data == null) return DEFAULT
            return NotificationPreferences(
                notificationsEnabled = (data["notificationsEnabled"] as? Boolean)
                    ?: (data["enabled"] as? Boolean) ?: true,
                announcementsEnabled = (data["announcementsEnabled"] as? Boolean)
                    ?: (data["announcements"] as? Boolean) ?: true,
                appUpdatesEnabled = (data["appUpdatesEnabled"] as? Boolean)
                    ?: (data["appUpdates"] as? Boolean) ?: true,
                maintenanceEnabled = (data["maintenanceEnabled"] as? Boolean)
                    ?: (data["maintenance"] as? Boolean) ?: true,
                newMoviesEnabled = (data["newMoviesEnabled"] as? Boolean)
                    ?: (data["newMovies"] as? Boolean) ?: true,
                newTvSeriesEnabled = (data["newTvSeriesEnabled"] as? Boolean)
                    ?: (data["newTvSeries"] as? Boolean) ?: true,
                newAnimeEnabled = (data["newAnimeEnabled"] as? Boolean)
                    ?: (data["newAnime"] as? Boolean) ?: true,
                newTvEpisodesEnabled = (data["newTvEpisodesEnabled"] as? Boolean)
                    ?: (data["newTvEpisodes"] as? Boolean) ?: true,
                newAnimeEpisodesEnabled = (data["newAnimeEpisodesEnabled"] as? Boolean)
                    ?: (data["newAnimeEpisodes"] as? Boolean) ?: true,
                newTvSeasonsEnabled = (data["newTvSeasonsEnabled"] as? Boolean)
                    ?: (data["newTvSeasons"] as? Boolean) ?: true,
                newAnimeSeasonsEnabled = (data["newAnimeSeasonsEnabled"] as? Boolean)
                    ?: (data["newAnimeSeasons"] as? Boolean) ?: true
            )
        }
    }
}
