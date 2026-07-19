package com.neurax08.xposed.appledecryptor.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.neurax08.xposed.appledecryptor.MainActivity
import com.neurax08.xposed.appledecryptor.R

class DownloadNotificationService : Service() {
    companion object {
        const val CHANNEL_ID = "appledecryptor_downloads"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DOWNLOAD_TRACK = "com.neurax08.xposed.appledecryptor.DOWNLOAD_TRACK"
        const val EXTRA_ADAM_ID = "adam_id"
        const val EXTRA_TRACK_TITLE = "track_title"

        @Volatile
        private var currentAdamId: String? = null
        @Volatile
        private var currentTitle: String = ""
        @Volatile
        private var currentArtist: String = ""

        fun updateTrackInfo(adamId: String?, title: String, artist: String) {
            currentAdamId = adamId
            currentTitle = title
            currentArtist = artist
        }

        fun getCurrentAdamId(): String? = currentAdamId
        fun getCurrentTitle(): String = currentTitle
        fun getCurrentArtist(): String = currentArtist
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DOWNLOAD_TRACK) {
            val adamId = intent.getStringExtra(EXTRA_ADAM_ID) ?: getCurrentAdamId()
            val title = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: getCurrentTitle()
            if (!adamId.isNullOrBlank()) {
                DownloadManager.enqueue(adamId = adamId, title = title)
            }
        }

        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_downloads),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AppleDecryptor download notifications"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val title = if (getCurrentTitle().isNotBlank()) {
            getCurrentTitle()
        } else {
            getString(R.string.no_active_track)
        }
        val artist = getCurrentArtist()
        val text = if (artist.isNotBlank()) "$artist - $title" else title

        val downloadIntent = Intent(this, DownloadNotificationService::class.java).apply {
            action = ACTION_DOWNLOAD_TRACK
            putExtra(EXTRA_ADAM_ID, getCurrentAdamId())
            putExtra(EXTRA_TRACK_TITLE, getCurrentTitle())
        }
        val downloadPendingIntent = PendingIntent.getService(
            this,
            0,
            downloadIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_save,
                getString(R.string.notification_download_this),
                downloadPendingIntent
            )
            .build()
    }
}

fun showDownloadCompleteNotification(context: Context, title: String, filePath: String) {
    ensureChannel(context)

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        context,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(context, DownloadNotificationService.CHANNEL_ID)
        .setContentTitle(context.getString(R.string.download_complete))
        .setContentText(context.getString(R.string.file_saved_to, filePath))
        .setSmallIcon(android.R.drawable.ic_menu_save)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(DownloadNotificationService.NOTIFICATION_ID + 1, notification)
}

fun showDownloadFailedNotification(context: Context, title: String, error: String) {
    ensureChannel(context)

    val notification = NotificationCompat.Builder(context, DownloadNotificationService.CHANNEL_ID)
        .setContentTitle(context.getString(R.string.download_failed))
        .setContentText("$title: $error")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setAutoCancel(true)
        .build()

    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(DownloadNotificationService.NOTIFICATION_ID + 2, notification)
}

private fun ensureChannel(context: Context) {
    val channel = NotificationChannel(
        DownloadNotificationService.CHANNEL_ID,
        context.getString(R.string.notification_channel_downloads),
        NotificationManager.IMPORTANCE_LOW
    )
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
}
