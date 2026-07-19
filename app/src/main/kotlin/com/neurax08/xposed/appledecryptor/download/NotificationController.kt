package com.neurax08.xposed.appledecryptor.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.neurax08.xposed.appledecryptor.MainActivity

/**
 * Notifications for the download pipeline.
 *
 * IMPORTANT: This code may run inside the Apple Music process (hooked host).
 * NEVER call context.getString(R.string.xxx) — module resource IDs do not exist
 * in the host app's Resources and will crash with Resources$NotFoundException.
 * Always use hardcoded string literals.
 */
class DownloadNotificationService : Service() {
    companion object {
        private const val TAG = "AppleDecryptor"
        const val CHANNEL_ID = "appledecryptor_downloads"
        const val NOTIFICATION_ID = 1001
        const val ACTION_DOWNLOAD_TRACK = "com.neurax08.xposed.appledecryptor.DOWNLOAD_TRACK"
        const val EXTRA_ADAM_ID = "adam_id"
        const val EXTRA_TRACK_TITLE = "track_title"

        // Hardcoded — safe in both module and host process.
        const val CHANNEL_NAME = "AppleDecryptor Downloads"
        const val TEXT_NO_ACTIVE_TRACK = "No active track"
        const val TEXT_DOWNLOAD_THIS = "Download this track"
        const val TEXT_DOWNLOAD_COMPLETE = "Download complete"
        const val TEXT_DOWNLOAD_FAILED = "Download failed"

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
        runCatching { createNotificationChannel() }
            .onFailure { Log.w(TAG, "createNotificationChannel failed: ${it.message}") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            if (intent?.action == ACTION_DOWNLOAD_TRACK) {
                val adamId = intent.getStringExtra(EXTRA_ADAM_ID) ?: getCurrentAdamId()
                val title = intent.getStringExtra(EXTRA_TRACK_TITLE) ?: getCurrentTitle()
                if (!adamId.isNullOrBlank()) {
                    DownloadManager.enqueue(adamId = adamId, title = title)
                }
            }

            val notification = buildNotification()
            startForeground(NOTIFICATION_ID, notification)
            START_STICKY
        } catch (e: Exception) {
            Log.e(TAG, "DownloadNotificationService onStartCommand failed: ${e.message}", e)
            // Still try to stay alive with a minimal system-icon notification.
            runCatching {
                val fallback = NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle("AppleDecryptor")
                    .setContentText("Download service")
                    .setSmallIcon(android.R.drawable.ic_media_play)
                    .setOngoing(true)
                    .build()
                startForeground(NOTIFICATION_ID, fallback)
            }
            START_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "AppleDecryptor download notifications"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        ensureChannelSafe(this)

        val title = if (getCurrentTitle().isNotBlank()) {
            getCurrentTitle()
        } else {
            TEXT_NO_ACTIVE_TRACK
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Opening MainActivity from host process may fail; wrap safely.
        val openAppPendingIntent = runCatching {
            val openAppIntent = Intent().apply {
                setClassName(
                    "com.neurax08.xposed.appledecryptor",
                    MainActivity::class.java.name,
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                this,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_save,
                TEXT_DOWNLOAD_THIS,
                downloadPendingIntent,
            )
        if (openAppPendingIntent != null) {
            builder.setContentIntent(openAppPendingIntent)
        }
        return builder.build()
    }
}

fun showDownloadCompleteNotification(context: Context, title: String, filePath: String) {
    runCatching {
        ensureChannelSafe(context)

        val pendingIntent = runCatching {
            val intent = Intent().apply {
                setClassName(
                    "com.neurax08.xposed.appledecryptor",
                    MainActivity::class.java.name,
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()

        val builder = NotificationCompat.Builder(context, DownloadNotificationService.CHANNEL_ID)
            .setContentTitle(DownloadNotificationService.TEXT_DOWNLOAD_COMPLETE)
            .setContentText("Saved: $filePath")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setAutoCancel(true)
        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(DownloadNotificationService.NOTIFICATION_ID + 1, builder.build())
    }.onFailure { e ->
        Log.w("AppleDecryptor", "showDownloadCompleteNotification failed: ${e.message}")
    }
}

fun showDownloadFailedNotification(context: Context, title: String, error: String) {
    runCatching {
        ensureChannelSafe(context)

        val notification = NotificationCompat.Builder(context, DownloadNotificationService.CHANNEL_ID)
            .setContentTitle(DownloadNotificationService.TEXT_DOWNLOAD_FAILED)
            .setContentText("$title: $error")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setAutoCancel(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(DownloadNotificationService.NOTIFICATION_ID + 2, notification)
    }.onFailure { e ->
        Log.w("AppleDecryptor", "showDownloadFailedNotification failed: ${e.message}")
    }
}

/** Create channel with hardcoded name — never touch module R.string. */
private fun ensureChannelSafe(context: Context) {
    val channel = NotificationChannel(
        DownloadNotificationService.CHANNEL_ID,
        DownloadNotificationService.CHANNEL_NAME,
        NotificationManager.IMPORTANCE_LOW,
    )
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
}
