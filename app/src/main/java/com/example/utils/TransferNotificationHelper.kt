package com.example.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R

object TransferNotificationHelper {
    private const val CHANNEL_ID = "p2p_transfer_channel"
    private const val NOTIFICATION_ID_PROGRESS = 1001
    private const val NOTIFICATION_ID_STATUS = 1002

    private fun createShareScreenPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("navigate_to", "share")
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 2005, intent, flags)
    }

    fun initChannel(context: Context) {
        com.example.data.notification.NotificationChannels.createAllChannels(context)
    }

    fun showConnectionNotification(context: Context, deviceName: String) {
        initChannel(context)
        try {
            val pendingIntent = createShareScreenPendingIntent(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.connected_to, deviceName))
                .setContentText(context.getString(R.string.connected_ready_share))
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID_STATUS, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showTransferProgress(context: Context, title: String, progressPercent: Int, isSender: Boolean, speedText: String? = null) {
        initChannel(context)
        try {
            val actionText = if (isSender) context.getString(R.string.sending) else context.getString(R.string.receiving)
            val text = if (!speedText.isNullOrBlank()) "$progressPercent% • $speedText" else context.getString(R.string.transfer_progress_percent, progressPercent)
            val pendingIntent = createShareScreenPendingIntent(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("$actionText: $title")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setProgress(100, progressPercent, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showTransferComplete(context: Context, title: String, isSender: Boolean) {
        initChannel(context)
        try {
            val actionText = if (isSender) context.getString(R.string.sent) else context.getString(R.string.received)
            val pendingIntent = createShareScreenPendingIntent(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.transfer_complete_title))
                .setContentText(context.getString(R.string.transfer_success_details, actionText, title))
                .setContentIntent(pendingIntent)
                .setProgress(0, 0, false)
                .setOngoing(false)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID_PROGRESS, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelProgressNotification(context: Context) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID_PROGRESS)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
