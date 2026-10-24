package com.example.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.filled.Extension
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Splash : Screen("splash", "Splash", Icons.Default.Home)
    object Onboarding : Screen("onboarding", "Onboarding", Icons.Default.Home)
    object Auth : Screen("auth", "Auth", Icons.Default.Home)
    object Home : Screen("home", "Home", Icons.Default.Home)
    object Movies : Screen("movies", "Movies", Icons.Default.Movie)
    object Series : Screen("series", "Series", Icons.Default.Tv)
    object Extensions : Screen("extensions", "Extensions", Icons.Default.Extension)
    object Search : Screen("search", "Search", Icons.Default.Search)
    object Anime : Screen("anime", "Anime", Icons.Default.Face)
    object Library : Screen("library", "Library", Icons.AutoMirrored.Filled.LibraryBooks)

    object Profile : Screen("profile", "Profile", Icons.Default.Person)
    object EditProfile : Screen("edit_profile", "Edit Profile", Icons.Default.Person)
    object AccountSettings : Screen("account_settings", "Account Settings", Icons.Default.Settings)
    object PublicProfile : Screen("public_profile/{userId}", "Public Profile", Icons.Default.Person) {
        fun createRoute(userId: String) = "public_profile/$userId"
    }
    object Downloads : Screen("downloads", "Downloads", Icons.Default.Download)
    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    object HelpSupport : Screen("help_support", "Help & Support", Icons.Default.Info)
    object Security : Screen("security", "Security", Icons.Default.Person)
    object Subscription : Screen("subscription", "Subscription", Icons.Default.Person)
    object About : Screen("about", "About Us", Icons.Default.Info)
    object Notifications : Screen("notifications", "Notifications", Icons.Default.Notifications)
    object NotificationPreferences : Screen("notification_preferences", "Notification Preferences", Icons.Default.Notifications)
    object Trending : Screen("trending", "Trending Now", Icons.Default.Movie)
    object Watching : Screen("watching", "Continue Watching", Icons.Default.Tv)
    object Popular : Screen("popular", "Popular", Icons.Default.Movie)
    object NewReleases : Screen("new_releases", "New Releases", Icons.Default.Movie)
    object Upcoming : Screen("upcoming", "Coming Soon", Icons.Default.Movie)

    object MovieDetails : Screen("movie_details/{movieId}?autoPlay={autoPlay}&experienceMode={experienceMode}", "Movie Details", Icons.Default.Movie) {
        fun createRoute(movieId: String, autoPlay: Boolean = false, experienceMode: String = "STANDARD"): String {
            val encodedId = try { java.net.URLEncoder.encode(movieId, "UTF-8") } catch (_: Exception) { movieId }
            return "movie_details/$encodedId?autoPlay=$autoPlay&experienceMode=$experienceMode"
        }
    }
    
    object Social : Screen("social", "Community", Icons.Default.Person)
    object Share : Screen("share", "Offline Share", Icons.Outlined.Share)
    object RecentTransfers : Screen("recent_transfers", "Recent Transfers", Icons.Outlined.Share)
    object SeriesDetails : Screen("series_details/{seriesId}?autoPlay={autoPlay}&experienceMode={experienceMode}", "Series Details", Icons.Default.Tv) {
        fun createRoute(seriesId: String, autoPlay: Boolean = false, experienceMode: String = "STANDARD"): String {
            val encodedId = try { java.net.URLEncoder.encode(seriesId, "UTF-8") } catch (_: Exception) { seriesId }
            return "series_details/$encodedId?autoPlay=$autoPlay&experienceMode=$experienceMode"
        }
    }
    object BlockedUsers : Screen("blocked_users", "Blocked Users", Icons.Default.Person)
}