package com.neurax08.xposed.appledecryptor.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log

/**
 * Runs inside the **Apple Music (hook) process** only.
 *
 * UI process cannot decrypt. When user taps "Download now" / Retry, the module UI
 * sends [ACTION_FORCE_START] targeted at package com.apple.android.music.
 * This receiver (registered dynamically by the Xposed module) starts the real job.
 *
 * IMPORTANT: Do not chain provideHlsUrl + enqueue + forceStart as separate async
 * launches — they race. Always pass hlsUrl into a single forceStart(override).
 */
object HostDownloadCommandReceiver {
    private const val TAG = "AppleDecryptor"

    const val ACTION_FORCE_START = "com.neurax08.xposed.appledecryptor.action.FORCE_START"
    const val EXTRA_ADAM_ID = "adamId"
    const val EXTRA_HLS_URL = "hlsUrl"
    const val EXTRA_TITLE = "title"
    const val EXTRA_FORCE = "force"

    /** Package of the host process that has FairPlay native decrypt. */
    const val HOST_PACKAGE = "com.apple.android.music"

    @Volatile
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent == null) return
            if (intent.action != ACTION_FORCE_START) return
            val adamId = intent.getStringExtra(EXTRA_ADAM_ID)?.trim().orEmpty()
            if (adamId.isEmpty() || adamId == "0") {
                Log.w(TAG, "FORCE_START ignored: bad adamId")
                return
            }
            val hlsUrl = intent.getStringExtra(EXTRA_HLS_URL).orEmpty()
            val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
            Log.i(
                TAG,
                "FORCE_START received adamId=$adamId hlsLen=${hlsUrl.length} pkg=${context.packageName}",
            )
            try {
                DownloadManager.init(context.applicationContext, asExecutor = true)
                // Single entry: write URL + start. No multi-launch race.
                DownloadManager.forceStart(
                    adamId = adamId,
                    hlsUrlOverride = hlsUrl,
                    titleOverride = title,
                )
            } catch (e: Exception) {
                Log.e(TAG, "FORCE_START failed adamId=$adamId: ${e.message}", e)
            }
        }
    }

    /**
     * Call from Xposed host (Apple Music) when download stack is bound.
     * Safe to call multiple times.
     */
    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        try {
            val filter = IntentFilter(ACTION_FORCE_START)
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            registered = true
            Log.i(TAG, "HostDownloadCommandReceiver registered pkg=${app.packageName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register FORCE_START receiver: ${e.message}", e)
        }
    }

    /** Send from module UI process → Apple Music process. */
    fun sendForceStart(
        context: Context,
        adamId: String,
        hlsUrl: String = "",
        title: String = "",
    ): Boolean {
        if (adamId.isBlank()) return false
        return try {
            val intent = Intent(ACTION_FORCE_START).apply {
                setPackage(HOST_PACKAGE)
                putExtra(EXTRA_ADAM_ID, adamId)
                putExtra(EXTRA_HLS_URL, hlsUrl)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_FORCE, true)
            }
            context.sendBroadcast(intent)
            Log.i(
                TAG,
                "FORCE_START broadcast sent adamId=$adamId hlsBlank=${hlsUrl.isBlank()} hlsLen=${hlsUrl.length}",
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "FORCE_START broadcast failed: ${e.message}", e)
            false
        }
    }
}
