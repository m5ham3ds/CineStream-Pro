package com.example.navigation

/**
 * Single source of truth for notification-based navigation contracts, Intent extras,
 * whitelist of destinations, and canonical route mappings.
 */
object NotificationNavigationContract {

    // --- Intent Extra Keys ---
    const val EXTRA_NOTIFICATION_ID = "notificationId"
    const val EXTRA_NAVIGATE_TO = "navigate_to"
    const val EXTRA_NAVIGATE_TO_CAMEL = "navigateTo" // alias support
    const val EXTRA_MOVIE_ID = "movieId"
    const val EXTRA_SERIES_ID = "seriesId"
    const val EXTRA_EPISODE_ID = "episodeId"
    const val EXTRA_NOTIFICATION_TYPE = "type"

    // Consumed flag to guard against activity rotation / recreation duplicate navigations
    const val EXTRA_INTENT_CONSUMED = "com.example.extra.NAV_INTENT_CONSUMED"

    // --- Whitelisted Navigation Targets ---
    const val TARGET_HOME = "home"
    const val TARGET_MOVIES = "movies"
    const val TARGET_SERIES = "series"
    const val TARGET_SEARCH = "search"
    const val TARGET_LIBRARY = "library"
    const val TARGET_DOWNLOADS = "downloads"
    const val TARGET_NOTIFICATIONS = "notifications"
    const val TARGET_PROFILE = "profile"
    const val TARGET_SETTINGS = "settings"
    const val TARGET_SUPPORT = "support"
    const val TARGET_ANIME = "anime"
    const val TARGET_SHARE = "share"
    const val TARGET_DETAIL = "detail"

    // --- Content Types ---
    const val TYPE_MOVIE = "movie"
    const val TYPE_SERIES = "series"
    const val TYPE_ANNOUNCEMENT = "announcement"
    const val TYPE_UPDATE = "update"
    const val TYPE_INFO = "info"

    /**
     * Complete set of allowed navigation target keys.
     * Any target outside this set must be rejected.
     */
    val ALLOWED_TARGETS: Set<String> = setOf(
        TARGET_HOME,
        TARGET_MOVIES,
        TARGET_SERIES,
        TARGET_SEARCH,
        TARGET_LIBRARY,
        TARGET_DOWNLOADS,
        TARGET_NOTIFICATIONS,
        TARGET_PROFILE,
        TARGET_SETTINGS,
        TARGET_SUPPORT,
        TARGET_ANIME,
        TARGET_SHARE,
        TARGET_DETAIL
    )

    /**
     * Mapping from canonical navigation targets to actual NavHost/Screen routes.
     * Note: "support" maps centrally to Screen.HelpSupport.route ("help_support").
     */
    val STATIC_ROUTE_MAPPING: Map<String, String> = mapOf(
        TARGET_HOME to Screen.Home.route,
        TARGET_MOVIES to Screen.Movies.route,
        TARGET_SERIES to Screen.Series.route,
        TARGET_SEARCH to Screen.Search.route,
        TARGET_LIBRARY to Screen.Library.route,
        TARGET_DOWNLOADS to Screen.Downloads.route,
        TARGET_NOTIFICATIONS to Screen.Notifications.route,
        TARGET_PROFILE to Screen.Profile.route,
        TARGET_SETTINGS to Screen.Settings.route,
        TARGET_SUPPORT to Screen.HelpSupport.route, // Canonical translation: support -> help_support
        TARGET_ANIME to Screen.Anime.route,
        TARGET_SHARE to Screen.Share.route
    )
}
