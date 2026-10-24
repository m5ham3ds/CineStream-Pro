package com.example.data.notification

import com.example.data.model.NotificationPreferences

/**
 * Granular classification of notifications to enforce user-configured preferences.
 */
enum class NotificationCategory {
    GENERAL,
    ANNOUNCEMENTS,
    APP_UPDATES,
    MAINTENANCE,
    NEW_MOVIES,
    NEW_TV_SERIES,
    NEW_ANIME,
    NEW_TV_EPISODES,
    NEW_ANIME_EPISODES,
    NEW_TV_SEASONS,
    NEW_ANIME_SEASONS
}

/**
 * Deterministic resolver for classifying notifications into user preference categories
 * and evaluating whether the notification is permitted to be displayed.
 *
 * Strict Rule: Anime is NEVER inferred simply because `isMovie == false`. Anime classification
 * requires an explicit indicator (e.g. `type == "anime"` or explicit `isAnime == true`).
 */
object NotificationCategoryResolver {

    fun resolveCategory(
        type: String?,
        event: String? = null,
        isAnime: Boolean = false
    ): NotificationCategory {
        val normalizedType = type?.trim()?.lowercase() ?: ""
        val normalizedEvent = event?.trim()?.lowercase() ?: ""

        val isEpisodeEvent = normalizedEvent.contains("episode")
        val isSeasonEvent = normalizedEvent.contains("season")

        return when {
            normalizedType == "announcement" || normalizedType == "announcements" -> NotificationCategory.ANNOUNCEMENTS
            normalizedType == "update" || normalizedType == "app_update" -> NotificationCategory.APP_UPDATES
            normalizedType == "maintenance" || normalizedType == "system" -> NotificationCategory.MAINTENANCE
            normalizedType == "movie" -> NotificationCategory.NEW_MOVIES
            normalizedType == "anime" -> {
                when {
                    isEpisodeEvent -> NotificationCategory.NEW_ANIME_EPISODES
                    isSeasonEvent -> NotificationCategory.NEW_ANIME_SEASONS
                    else -> NotificationCategory.NEW_ANIME
                }
            }
            normalizedType == "series" || normalizedType == "tv" || normalizedType == "tv_series" -> {
                when {
                    isEpisodeEvent -> if (isAnime) NotificationCategory.NEW_ANIME_EPISODES else NotificationCategory.NEW_TV_EPISODES
                    isSeasonEvent -> if (isAnime) NotificationCategory.NEW_ANIME_SEASONS else NotificationCategory.NEW_TV_SEASONS
                    else -> if (isAnime) NotificationCategory.NEW_ANIME else NotificationCategory.NEW_TV_SERIES
                }
            }
            normalizedType == "episode" || normalizedType == "new_episode" -> {
                if (isAnime) NotificationCategory.NEW_ANIME_EPISODES else NotificationCategory.NEW_TV_EPISODES
            }
            normalizedType == "season" || normalizedType == "new_season" -> {
                if (isAnime) NotificationCategory.NEW_ANIME_SEASONS else NotificationCategory.NEW_TV_SEASONS
            }
            else -> NotificationCategory.GENERAL
        }
    }

    /**
     * Checks if a notification category is allowed under the provided user preferences.
     * If the master switch [NotificationPreferences.notificationsEnabled] is false, ALL notifications are disallowed.
     */
    fun isNotificationAllowed(
        category: NotificationCategory,
        preferences: NotificationPreferences
    ): Boolean {
        if (!preferences.notificationsEnabled) {
            return false
        }
        return when (category) {
            NotificationCategory.GENERAL -> true
            NotificationCategory.ANNOUNCEMENTS -> preferences.announcementsEnabled
            NotificationCategory.APP_UPDATES -> preferences.appUpdatesEnabled
            NotificationCategory.MAINTENANCE -> preferences.maintenanceEnabled
            NotificationCategory.NEW_MOVIES -> preferences.newMoviesEnabled
            NotificationCategory.NEW_TV_SERIES -> preferences.newTvSeriesEnabled
            NotificationCategory.NEW_ANIME -> preferences.newAnimeEnabled
            NotificationCategory.NEW_TV_EPISODES -> preferences.newTvEpisodesEnabled
            NotificationCategory.NEW_ANIME_EPISODES -> preferences.newAnimeEpisodesEnabled
            NotificationCategory.NEW_TV_SEASONS -> preferences.newTvSeasonsEnabled
            NotificationCategory.NEW_ANIME_SEASONS -> preferences.newAnimeSeasonsEnabled
        }
    }
}
