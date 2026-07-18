package com.neurax08.xposed.appledecryptor.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives queue mutations from the Apple Music (hook) process.
 * Explicit package-targeted broadcasts work without sdcard and without
 * ContentProvider package-visibility issues on Android 11+.
 */
class QueueSyncReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "AppleDecryptor"
        const val ACTION_UPSERT = "com.neurax08.xposed.appledecryptor.action.QUEUE_UPSERT"
        const val ACTION_DELETE = "com.neurax08.xposed.appledecryptor.action.QUEUE_DELETE"
        const val ACTION_PING = "com.neurax08.xposed.appledecryptor.action.QUEUE_PING"

        const val EXTRA_ADAM_ID = "adamId"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_FILE_PATH = "filePath"
        const val EXTRA_CREATED_AT = "createdAt"
        const val EXTRA_ERROR = "errorMessage"
        const val EXTRA_HLS_URL = "hlsUrl"
        const val EXTRA_TOTAL = "totalSegments"
        const val EXTRA_DONE = "completedSegments"

        const val MODULE_PACKAGE = "com.neurax08.xposed.appledecryptor"
        const val RECEIVER_CLASS = "com.neurax08.xposed.appledecryptor.download.QueueSyncReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return
        // Ensure local store points at module private storage.
        SharedQueueStore.init(context.applicationContext)
        when (intent.action) {
            ACTION_UPSERT -> {
                val adamId = intent.getStringExtra(EXTRA_ADAM_ID)?.trim().orEmpty()
                if (adamId.isEmpty()) return
                val entry = SharedQueueStore.QueueEntry(
                    adamId = adamId,
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    artist = intent.getStringExtra(EXTRA_ARTIST).orEmpty(),
                    status = intent.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank { "QUEUED" },
                    progress = intent.getIntExtra(EXTRA_PROGRESS, 0),
                    filePath = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty(),
                    createdAt = intent.getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis()),
                    errorMessage = intent.getStringExtra(EXTRA_ERROR).orEmpty(),
                    hlsUrl = intent.getStringExtra(EXTRA_HLS_URL).orEmpty(),
                    totalSegments = intent.getIntExtra(EXTRA_TOTAL, 0),
                    completedSegments = intent.getIntExtra(EXTRA_DONE, 0),
                )
                SharedQueueStore.applyLocalUpsert(entry)
                Log.i(TAG, "QueueSyncReceiver upsert adamId=$adamId status=${entry.status}")
            }
            ACTION_DELETE -> {
                val adamId = intent.getStringExtra(EXTRA_ADAM_ID)?.trim().orEmpty()
                if (adamId.isEmpty()) return
                SharedQueueStore.applyLocalDelete(adamId)
                Log.i(TAG, "QueueSyncReceiver delete adamId=$adamId")
            }
            ACTION_PING -> {
                Log.i(TAG, "QueueSyncReceiver ping received")
            }
            else -> Unit
        }
    }
}
