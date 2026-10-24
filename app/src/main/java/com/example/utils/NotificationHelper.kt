package com.example.utils
import com.example.R

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.Manifest

import com.example.data.notification.NotificationChannels

object NotificationHelper {
    val CHANNEL_ID = NotificationChannels.CHANNEL_DOWNLOADS
    val CHANNEL_ANNOUNCEMENTS = NotificationChannels.CHANNEL_ANNOUNCEMENTS
    
    fun createChannel(context: Context) {
        NotificationChannels.createAllChannels(context)
    }

    /**
     * Builds a NotificationCompat.Builder for an announcement / push notification.
     *
     * Adheres to Phase 2B constraints:
     * - Uses [NotificationChannels.CHANNEL_ANNOUNCEMENTS]
     * - Monochrome icon [R.drawable.ic_notification]
     * - Content title & message resolution with safe fallback
     * - [NotificationCompat.BigTextStyle] for multiline text support
     * - Priority HIGH (matching the channel importance)
     * - Safe immutable PendingIntent opening MainActivity only
     * - No network image loading or BigPictureStyle
     */
    fun buildGeneralNotification(
        context: Context,
        id: String,
        title: String,
        message: String,
        navigateTo: String? = null,
        movieId: String? = null,
        seriesId: String? = null,
        episodeId: String? = null,
        type: String? = null
    ): NotificationCompat.Builder? {
        if (title.isBlank() && message.isBlank()) {
            return null
        }

        createChannel(context)

        val displayTitle = when {
            title.isNotBlank() -> title.trim()
            message.isNotBlank() -> context.getString(R.string.app_name)
            else -> return null
        }

        val displayMessage = when {
            message.isNotBlank() -> message.trim()
            else -> displayTitle
        }

        // Safe immutable PendingIntent strictly launching MainActivity only
        val intent = Intent(context, com.example.MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (id.isNotBlank()) {
                putExtra(com.example.navigation.NotificationNavigationContract.EXTRA_NOTIFICATION_ID, id)
            }
            if (!navigateTo.isNullOrBlank()) {
                putExtra(com.example.navigation.NotificationNavigationContract.EXTRA_NAVIGATE_TO, navigateTo.trim())
            }
            if (!movieId.isNullOrBlank()) {
                putExtra(com.example.navigation.NotificationNavigationContract.EXTRA_MOVIE_ID, movieId.trim())
            }
            if (!seriesId.isNullOrBlank()) {
                putExtra(com.example.navigation.NotificationNavigationContract.EXTRA_SERIES_ID, seriesId.trim())
            }
            if (!episodeId.isNullOrBlank()) {
                putExtra(com.example.navigation.NotificationNavigationContract.EXTRA_EPISODE_ID, episodeId.trim())
            }
            if (!type.isNullOrBlank()) {
                putExtra(com.example.navigation.NotificationNavigationContract.EXTRA_NOTIFICATION_TYPE, type.trim())
            }
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            pendingIntentFlags
        )

        return NotificationCompat.Builder(context, CHANNEL_ANNOUNCEMENTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(displayTitle)
            .setContentText(displayMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(displayMessage))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
    }

    fun showGeneralNotification(
        context: Context,
        id: String,
        title: String,
        message: String,
        navigateTo: String? = null,
        movieId: String? = null,
        seriesId: String? = null,
        episodeId: String? = null,
        type: String? = null
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val builder = buildGeneralNotification(
            context = context,
            id = id,
            title = title,
            message = message,
            navigateTo = navigateTo,
            movieId = movieId,
            seriesId = seriesId,
            episodeId = episodeId,
            type = type
        ) ?: return

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        notificationManager.notify(id.hashCode(), builder.build())
    }

    fun showDownloadStarted(context: Context, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(context.getString(R.string.new_download))
            .setContentText(context.getString(R.string.downloading, title))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(title.hashCode(), builder.build())
    }
    
    fun showDownloadCompleted(context: Context, title: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.download_completed_title))
            .setContentText(context.getString(R.string.download_complete, title))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setTimeoutAfter(3000)

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(title.hashCode(), builder.build())
    }
}