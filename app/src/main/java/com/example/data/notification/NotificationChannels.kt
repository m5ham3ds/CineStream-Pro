package com.example.data.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.example.R

/**
 * Centralized registry and factory for all Android Notification Channels used by the application.
 *
 * All channel IDs and importance levels are strictly preserved to maintain backward compatibility
 * and prevent notification regressions across Download, Stream Download, P2P Transfers, and Cloud Announcements.
 */
object NotificationChannels {

    /**
     * Channel for general announcements, cloud updates, releases, and push notifications.
     * Importance: HIGH (Makes sound and appears as heads-up notification when appropriate).
     */
    const val CHANNEL_ANNOUNCEMENTS = "announcements_channel"

    /**
     * Channel for media downloads started via DownloadManager.
     * Importance: DEFAULT.
     */
    const val CHANNEL_DOWNLOADS = "download_channel"

    /**
     * Channel for background streaming downloads managed by StreamDownloaderService.
     * Importance: LOW.
     */
    const val CHANNEL_STREAM_DOWNLOADS = "stream_download_channel"

    /**
     * Channel for offline peer-to-peer file transfers managed by TransferNotificationHelper / P2PManager.
     * Importance: LOW.
     */
    const val CHANNEL_P2P_TRANSFERS = "p2p_transfer_channel"

    /**
     * Idempotently creates all application notification channels in the system NotificationManager.
     * Safe to call during Application startup or before showing any notification.
     */
    fun createAllChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return

        // 1. Announcements & Push Notifications Channel
        val announcementChannel = NotificationChannel(
            CHANNEL_ANNOUNCEMENTS,
            context.getString(R.string.notification_channel_announcements),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_announcements_desc)
        }

        // 2. Standard Downloads Channel
        val downloadChannel = NotificationChannel(
            CHANNEL_DOWNLOADS,
            context.getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.notification_channel_downloads_desc)
        }

        // 3. Background Stream Downloads Channel
        val streamDownloadChannel = NotificationChannel(
            CHANNEL_STREAM_DOWNLOADS,
            context.getString(R.string.notification_channel_stream_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_stream_downloads_desc)
        }

        // 4. P2P File Transfers Channel
        val p2pTransferChannel = NotificationChannel(
            CHANNEL_P2P_TRANSFERS,
            context.getString(R.string.notification_channel_transfers),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_transfers_desc)
        }

        notificationManager.createNotificationChannels(
            listOf(
                announcementChannel,
                downloadChannel,
                streamDownloadChannel,
                p2pTransferChannel
            )
        )
    }
}
