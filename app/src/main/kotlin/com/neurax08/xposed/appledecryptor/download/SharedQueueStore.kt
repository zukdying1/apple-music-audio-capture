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
 * Primary backend: [QueueContentProvider] (module private storage).
 * Hook process writes via ContentResolver; UI reads the same provider.
 *
 * Critical for Xposed: [init] may not run at package-ready time because
 * Application Context is often null then. Every public API calls [ensureInit]
 * and resolves Context via ActivityThread / optional seed Context.
 */
object SharedQueueStore {
    private const val TAG = "AppleDecryptor"
    private const val MODULE_PACKAGE = "com.neurax08.xposed.appledecryptor"

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
        val app = runCatching { context.applicationContext }.getOrNull() ?: context
        appContextRef.set(app)
        backendLabel = "content://${QueueContentProvider.AUTHORITY}"
        lastError = ""
        Log.i(TAG, "SharedQueueStore init package=${app.packageName} backend=$backendLabel")
    }

    /**
     * Lazily resolve Application Context if [init] never ran or ran too early.
     * Safe to call from hook threads in the host process.
     */
    fun ensureInit(seed: Context? = null): Boolean {
        if (appContextRef.get() != null) return true

        val fromSeed = seed?.let { runCatching { it.applicationContext }.getOrNull() ?: it }
        if (fromSeed != null) {
            init(fromSeed)
            return true
        }

        val fromAt = resolveActivityThreadApplication()
        if (fromAt != null) {
            init(fromAt)
            return true
        }

        // Last resort: module package context (for ContentResolver only).
        val moduleCtx = resolveModuleContext(fromAt)
        if (moduleCtx != null) {
            init(moduleCtx)
            return true
        }

        lastError = "upsert skipped: SharedQueueStore not initialized (no Application Context yet)"
        backendLabel = "uninitialized"
        Log.w(TAG, lastError)
        return false
    }

    fun getActivePath(): String = backendLabel

    fun isInitialized(): Boolean = appContextRef.get() != null

    private fun ctx(): Context? = appContextRef.get()

    private fun resolveActivityThreadApplication(): Context? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApp = activityThreadClass.getMethod("currentApplication").invoke(null)
            currentApp as? Context
        }.onFailure { e ->
            Log.w(TAG, "ActivityThread.currentApplication failed: ${e.message}")
        }.getOrNull()
    }

    private fun resolveModuleContext(base: Context?): Context? {
        val host = base ?: resolveActivityThreadApplication() ?: return null
        return runCatching {
            host.createPackageContext(
                MODULE_PACKAGE,
                Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY,
            )
        }.onFailure { e ->
            Log.w(TAG, "createPackageContext($MODULE_PACKAGE) failed: ${e.message}")
        }.getOrNull()
    }

    suspend fun load(): List<QueueEntry> = withContext(Dispatchers.IO) { loadSync() }

    fun loadSync(): List<QueueEntry> {
        if (!ensureInit()) {
            return emptyList()
        }
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
            } ?: run {
                // null cursor often means provider missing / permission
                lastError = "query returned null cursor (provider missing or blocked?)"
                Log.w(TAG, lastError)
            }
            if (lastError.isEmpty() || lastError.startsWith("query returned")) {
                // keep query-null error if set
            }
            if (list.isNotEmpty() || lastError.isEmpty()) {
                lastError = ""
            }
            backendLabel = "content://${QueueContentProvider.AUTHORITY} (${list.size} items)"
            Log.i(TAG, "queue load size=${list.size} via package=${context.packageName}")
            list
        } catch (e: Exception) {
            lastError = "load failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError, e)
            emptyList()
        }
    }

    suspend fun upsert(entry: QueueEntry) = withContext(Dispatchers.IO) { upsertSync(entry) }

    fun upsertSync(entry: QueueEntry, seedContext: Context? = null) {
        if (entry.adamId.isBlank()) return
        if (!ensureInit(seedContext)) {
            Log.w(TAG, "queue upsert aborted adamId=${entry.adamId} err=$lastError")
            return
        }
        val context = ctx()
        if (context == null) {
            lastError = "upsert skipped: SharedQueueStore not initialized"
            Log.w(TAG, lastError)
            return
        }
        try {
            val values = QueueContentProvider.entryToValues(entry)
            val uri = context.contentResolver.insert(QueueContentProvider.CONTENT_URI, values)
            if (uri == null) {
                lastError = "insert returned null uri (provider rejected?)"
                Log.e(TAG, "queue upsert adamId=${entry.adamId} $lastError")
                return
            }
            lastError = ""
            backendLabel = "content://${QueueContentProvider.AUTHORITY}"
            Log.i(
                TAG,
                "queue upsert adamId=${entry.adamId} status=${entry.status} uri=$uri via=${context.packageName}",
            )
        } catch (e: Exception) {
            lastError = "upsert failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError, e)
        }
    }

    suspend fun delete(adamId: String) = withContext(Dispatchers.IO) { deleteSync(adamId) }

    fun deleteSync(adamId: String) {
        if (!ensureInit()) return
        val context = ctx() ?: return
        try {
            val uri = Uri.withAppendedPath(QueueContentProvider.CONTENT_URI, adamId)
            context.contentResolver.delete(uri, null, null)
            lastError = ""
        } catch (e: Exception) {
            lastError = "delete failed: ${e.javaClass.simpleName}: ${e.message}"
            Log.e(TAG, lastError, e)
        }
    }

    suspend fun get(adamId: String): QueueEntry? = withContext(Dispatchers.IO) {
        if (!ensureInit()) return@withContext null
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
            lastError = "get failed: ${e.javaClass.simpleName}: ${e.message}"
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
