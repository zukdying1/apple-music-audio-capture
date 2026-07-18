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
import java.util.concurrent.atomic.AtomicReference

/**
 * Foreground service + notification helpers.
 *
 * CRITICAL: The hook runs in the Apple Music process, so [context] passed to
 * [showDownloadCompleteNotification] / [showDownloadFailedNotification] is
 * Apple Music's Context.  Module R.string resources are NOT available there.
 * We store the module's own Context (from [initModuleContext]) for resource
 * lookups, and fall back to hardcoded English strings when unavailable.
 */
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

        /** Module's own Context, stored once for resource lookups. */
        private val moduleContextRef = AtomicReference<Context?>(null)

        fun initModuleContext(ctx: Context) {
            moduleContextRef.compareAndSet(null, ctx.applicationContext)
        }

        fun getModuleContext(): Context? = moduleContextRef.get()

        fun updateTrackInfo(adamId: String?, title: String, artist: String) {
            currentAdamId = adamId
            currentTitle = title
            currentArtist = artist
        }

        fun getCurrentAdamId(): String? = currentAdamId
        fun getCurrentTitle(): String = currentTitle
        fun getCurrentArtist(): String = currentArtist
    }

    /**
     * Returns the module's own Context if available, or this Service instance
     * (which may be the host process Context).  Used for resource lookups.
     */
    private fun resContext(): Context {
        val mc = getModuleContext()
        if (mc != null) return mc
        // Service itself is a Context — may be host process but worth trying.
        return this
    }

    override fun onCreate() {
        super.onCreate()
        // Store this Service's context as module context fallback.
        initModuleContext(this)
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
        val label = safeString(resContext(), R.string.notification_channel_downloads, "Downloads")
        val channel = NotificationChannel(
            CHANNEL_ID,
            label,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AppleDecryptor download notifications"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val ctx = resContext()
        val title = if (getCurrentTitle().isNotBlank()) {
            getCurrentTitle()
        } else {
            safeString(ctx, R.string.no_active_track, "No active track")
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
                safeString(ctx, R.string.notification_download_this, "Download This"),
                downloadPendingIntent
            )
            .build()
    }
}

// ── Top-level notification helpers ──────────────────────────────────────────

fun showDownloadCompleteNotification(context: Context, title: String, filePath: String) {
    val ctx = DownloadNotificationService.getModuleContext() ?: context
    ensureChannel(ctx)

    val intent = Intent(ctx, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
    val pendingIntent = PendingIntent.getActivity(
        ctx,
        0,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    val notification = NotificationCompat.Builder(ctx, DownloadNotificationService.CHANNEL_ID)
        .setContentTitle(safeString(ctx, R.string.download_complete, "Download Complete"))
        .setContentText(safeString(ctx, R.string.file_saved_to, "Saved to $filePath"))
        .setSmallIcon(android.R.drawable.ic_menu_save)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .build()

    val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(DownloadNotificationService.NOTIFICATION_ID + 1, notification)
}

fun showDownloadFailedNotification(context: Context, title: String, error: String) {
    val ctx = DownloadNotificationService.getModuleContext() ?: context
    ensureChannel(ctx)

    val notification = NotificationCompat.Builder(ctx, DownloadNotificationService.CHANNEL_ID)
        .setContentTitle(safeString(ctx, R.string.download_failed, "Download Failed"))
        .setContentText("$title: $error")
        .setSmallIcon(android.R.drawable.ic_dialog_alert)
        .setAutoCancel(true)
        .build()

    val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.notify(DownloadNotificationService.NOTIFICATION_ID + 2, notification)
}

// ── Internal helpers ────────────────────────────────────────────────────────

private fun ensureChannel(context: Context) {
    val label = safeString(context, R.string.notification_channel_downloads, "Downloads")
    val channel = NotificationChannel(
        DownloadNotificationService.CHANNEL_ID,
        label,
        NotificationManager.IMPORTANCE_LOW
    )
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    manager.createNotificationChannel(channel)
}

/**
 * Safe resource lookup: try [Context.getString] with the module's resource ID,
 * fall back to [fallback] if the resource is not found (e.g. when [context] is
 * the host process and doesn't have the module's resources).
 */
private fun safeString(context: Context?, resId: Int, fallback: String): String {
    if (context == null) return fallback
    return try {
        context.getString(resId)
    } catch (e: android.content.res.Resources.NotFoundException) {
        fallback
    }
}