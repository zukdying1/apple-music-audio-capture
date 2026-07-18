package com.neurax08.xposed.appledecryptor.download

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-process queue facade.
 *
 * Primary backend: [QueueContentProvider] (module private storage, always works).
 * Hook process writes via ContentResolver; UI reads the same provider.
 */
object SharedQueueStore {
    private const val TAG = "AppleDecryptor"

    data class QueueEntry(
        val adamId: String,
        val title: String = "",
        val artist: String = "",
        val status: String = "QUEUED",
        val progress: Int = 0,
        val filePath: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val errorMessage: String = "",
        val hlsUrl: String = "",
        val totalSegments: Int = 0,
        val completedSegments: Int = 0,
    )

    private val appContextRef = AtomicReference<Context?>(null)

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var backendLabel: String = "uninitialized"

    fun init(context: Context) {
        appContextRef.set(context.applicationContext)
        backendLabel = "content://${QueueContentProvider.AUTHORITY}"
        lastError = ""
        Log.i(TAG, "SharedQueueStore init package=${context.packageName} backend=$backendLabel")
    }

    fun getActivePath(): String = backendLabel

    private fun ctx(): Context? = appContextRef.get()

    suspend fun load(): List<QueueEntry> = withContext(Dispatchers.IO) { loadSync() }

    fun loadSync(): List<QueueEntry> {
        val context = ctx()
        if (context == null) {
            lastError = "SharedQueueStore not initialized (no Context)"
            Log.w(TAG, lastError)
            return emptyList()
        }
        return try {
            val list = ArrayList<QueueEntry>()
            context.contentResolver.query(
                QueueContentProvider.CONTENT_URI,
                QueueContentProvider.COLUMNS,
                null,
                null,
                "createdAt DESC",
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    list.add(cursorToEntry(cursor))
                }
            }
            lastError = ""
            backendLabel = "content://${QueueContentProvider.AUTHORITY} (${list.size} items)"
            Log.i(TAG, "queue load size=${list.size}")
            list
        } catch (e: Exception) {
            lastError = "load failed: ${e.message}"
            Log.e(TAG, lastError, e)
            emptyList()
        }
    }

    suspend fun upsert(entry: QueueEntry) = withContext(Dispatchers.IO) { upsertSync(entry) }

    fun upsertSync(entry: QueueEntry) {
        if (entry.adamId.isBlank()) return
        val context = ctx()
        if (context == null) {
            lastError = "upsert skipped: SharedQueueStore not initialized"
            Log.w(TAG, lastError)
            return
        }
        try {
            val values = QueueContentProvider.entryToValues(entry)
            // insert() on provider performs upsert
            val uri = context.contentResolver.insert(QueueContentProvider.CONTENT_URI, values)
            lastError = ""
            backendLabel = "content://${QueueContentProvider.AUTHORITY}"
            Log.i(TAG, "queue upsert adamId=${entry.adamId} status=${entry.status} uri=$uri")
        } catch (e: Exception) {
            lastError = "upsert failed: ${e.message}"
            Log.e(TAG, lastError, e)
        }
    }

    suspend fun delete(adamId: String) = withContext(Dispatchers.IO) { deleteSync(adamId) }

    fun deleteSync(adamId: String) {
        val context = ctx() ?: return
        try {
            val uri = Uri.withAppendedPath(QueueContentProvider.CONTENT_URI, adamId)
            context.contentResolver.delete(uri, null, null)
            lastError = ""
        } catch (e: Exception) {
            lastError = "delete failed: ${e.message}"
            Log.e(TAG, lastError, e)
        }
    }

    suspend fun get(adamId: String): QueueEntry? = withContext(Dispatchers.IO) {
        val context = ctx() ?: return@withContext null
        try {
            val uri = Uri.withAppendedPath(QueueContentProvider.CONTENT_URI, adamId)
            context.contentResolver.query(
                uri,
                QueueContentProvider.COLUMNS,
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursorToEntry(cursor) else null
            }
        } catch (e: Exception) {
            lastError = "get failed: ${e.message}"
            Log.e(TAG, lastError, e)
            null
        }
    }

    fun snapshot(): List<QueueEntry> = loadSync()

    private fun cursorToEntry(cursor: Cursor): QueueEntry {
        fun str(col: String): String {
            val i = cursor.getColumnIndex(col)
            return if (i >= 0) cursor.getString(i) ?: "" else ""
        }
        fun int(col: String): Int {
            val i = cursor.getColumnIndex(col)
            return if (i >= 0) cursor.getInt(i) else 0
        }
        fun long(col: String): Long {
            val i = cursor.getColumnIndex(col)
            return if (i >= 0) cursor.getLong(i) else 0L
        }
        return QueueEntry(
            adamId = str("adamId"),
            title = str("title"),
            artist = str("artist"),
            status = str("status"),
            progress = int("progress"),
            filePath = str("filePath"),
            createdAt = long("createdAt"),
            errorMessage = str("errorMessage"),
            hlsUrl = str("hlsUrl"),
            totalSegments = int("totalSegments"),
            completedSegments = int("completedSegments"),
        )
    }
}
