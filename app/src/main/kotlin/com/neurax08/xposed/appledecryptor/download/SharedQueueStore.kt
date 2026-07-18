package com.neurax08.xposed.appledecryptor.download

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Cross-process queue.
 *
 * Write path (hook / host process):
 *   1) Explicit broadcast → module [QueueSyncReceiver] (primary, works Android 11+)
 *   2) ContentProvider insert (best-effort secondary)
 *
 * Read path (module UI process):
 *   Local filesDir/queue.json written by receiver / provider.
 *
 * Never depends on /sdcard.
 */
object SharedQueueStore {
    private const val TAG = "AppleDecryptor"
    private const val FILE_NAME = "queue.json"
    private const val MAX_ITEMS = 200

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
    private val lock = ReentrantReadWriteLock()

    @Volatile
    var lastError: String = ""
        private set

    @Volatile
    private var backendLabel: String = "uninitialized"

    fun init(context: Context) {
        val app = runCatching { context.applicationContext }.getOrNull() ?: context
        appContextRef.set(app)
        backendLabel = "local:${File(app.filesDir, FILE_NAME).absolutePath}"
        lastError = ""
        Log.i(TAG, "SharedQueueStore init package=${app.packageName} backend=$backendLabel")
    }

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
        lastError = "SharedQueueStore not initialized (no Application Context)"
        backendLabel = "uninitialized"
        Log.w(TAG, lastError)
        return false
    }

    fun getActivePath(): String = backendLabel
    fun isInitialized(): Boolean = appContextRef.get() != null

    private fun ctx(): Context? = appContextRef.get()

    private fun resolveActivityThreadApplication(): Context? {
        return runCatching {
            val cls = Class.forName("android.app.ActivityThread")
            cls.getMethod("currentApplication").invoke(null) as? Context
        }.getOrNull()
    }

    private fun queueFile(context: Context): File = File(context.filesDir, FILE_NAME)

    // ---------- local file CRUD (module process / after broadcast) ----------

    fun applyLocalUpsert(entry: QueueEntry) {
        if (entry.adamId.isBlank()) return
        if (!ensureInit()) return
        val context = ctx() ?: return
        lock.write {
            val items = readAllLocked(context)
            val idx = items.indexOfFirst { it.optString("adamId") == entry.adamId }
            val merged = if (idx >= 0) {
                mergeEntry(items[idx], entry)
            } else {
                entryToJson(entry)
            }
            if (idx >= 0) items[idx] = merged else items.add(0, merged)
            while (items.size > MAX_ITEMS) items.removeAt(items.lastIndex)
            writeAllLocked(context, items)
            backendLabel = "local:${queueFile(context).absolutePath} (${items.size} items)"
            lastError = ""
        }
        Log.i(TAG, "queue local upsert adamId=${entry.adamId} status=${entry.status}")
    }

    fun applyLocalDelete(adamId: String) {
        if (adamId.isBlank()) return
        if (!ensureInit()) return
        val context = ctx() ?: return
        lock.write {
            val items = readAllLocked(context)
            val before = items.size
            items.removeAll { it.optString("adamId") == adamId }
            if (items.size != before) {
                writeAllLocked(context, items)
            }
            lastError = ""
        }
    }

    private fun readAllLocked(context: Context): MutableList<JSONObject> {
        val f = queueFile(context)
        if (!f.exists() || f.length() == 0L) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            MutableList(arr.length()) { arr.getJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "queue file read failed: ${e.message}")
            mutableListOf()
        }
    }

    private fun writeAllLocked(context: Context, items: List<JSONObject>) {
        val arr = JSONArray()
        items.forEach { arr.put(it) }
        val f = queueFile(context)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(arr.toString())
            tmp.delete()
        }
    }

    private fun entryToJson(e: QueueEntry): JSONObject {
        return JSONObject()
            .put("adamId", e.adamId)
            .put("title", e.title)
            .put("artist", e.artist)
            .put("status", e.status)
            .put("progress", e.progress)
            .put("filePath", e.filePath)
            .put("createdAt", e.createdAt)
            .put("errorMessage", e.errorMessage)
            .put("hlsUrl", e.hlsUrl)
            .put("totalSegments", e.totalSegments)
            .put("completedSegments", e.completedSegments)
    }

    private fun mergeEntry(existing: JSONObject, e: QueueEntry): JSONObject {
        fun putStr(key: String, value: String, overwriteBlank: Boolean = false) {
            if (value.isNotBlank() || overwriteBlank || !existing.has(key)) {
                if (value.isNotBlank() || overwriteBlank) existing.put(key, value)
            }
        }
        // Always update status/progress fields when provided by writer.
        if (e.status.isNotBlank()) existing.put("status", e.status)
        existing.put("progress", e.progress)
        putStr("title", e.title)
        putStr("artist", e.artist)
        putStr("filePath", e.filePath)
        putStr("errorMessage", e.errorMessage, overwriteBlank = e.status != "FAILED")
        putStr("hlsUrl", e.hlsUrl)
        if (e.totalSegments > 0) existing.put("totalSegments", e.totalSegments)
        if (e.completedSegments > 0) existing.put("completedSegments", e.completedSegments)
        if (!existing.has("createdAt") || existing.optLong("createdAt") == 0L) {
            existing.put("createdAt", e.createdAt)
        }
        if (!existing.has("adamId")) existing.put("adamId", e.adamId)
        return existing
    }

    private fun jsonToEntry(obj: JSONObject): QueueEntry {
        return QueueEntry(
            adamId = obj.optString("adamId", ""),
            title = obj.optString("title", ""),
            artist = obj.optString("artist", ""),
            status = obj.optString("status", "QUEUED"),
            progress = obj.optInt("progress", 0),
            filePath = obj.optString("filePath", ""),
            createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
            errorMessage = obj.optString("errorMessage", ""),
            hlsUrl = obj.optString("hlsUrl", ""),
            totalSegments = obj.optInt("totalSegments", 0),
            completedSegments = obj.optInt("completedSegments", 0),
        )
    }

    // ---------- public API ----------

    suspend fun load(): List<QueueEntry> = withContext(Dispatchers.IO) { loadSync() }

    fun loadSync(): List<QueueEntry> {
        if (!ensureInit()) return emptyList()
        val context = ctx() ?: return emptyList()
        return lock.read {
            try {
                val list = readAllLocked(context).map { jsonToEntry(it) }
                    .sortedByDescending { it.createdAt }
                backendLabel = "local:${queueFile(context).absolutePath} (${list.size} items)"
                lastError = ""
                Log.i(TAG, "queue load size=${list.size} package=${context.packageName}")
                list
            } catch (e: Exception) {
                lastError = "load failed: ${e.message}"
                Log.e(TAG, lastError, e)
                emptyList()
            }
        }
    }

    suspend fun upsert(entry: QueueEntry) = withContext(Dispatchers.IO) { upsertSync(entry) }

    /**
     * Cross-process safe upsert.
     * Host process → broadcast to module package; if we ARE the module package, write local.
     */
    fun upsertSync(entry: QueueEntry, seedContext: Context? = null) {
        if (entry.adamId.isBlank()) return
        if (!ensureInit(seedContext)) {
            Log.w(TAG, "queue upsert aborted adamId=${entry.adamId} err=$lastError")
            return
        }
        val context = ctx() ?: return
        val pkg = context.packageName

        // Always write local if we are the module process (UI / receiver).
        if (pkg == QueueSyncReceiver.MODULE_PACKAGE) {
            applyLocalUpsert(entry)
        }

        // Always broadcast to module so UI process sees updates from host.
        val broadcastOk = sendUpsertBroadcast(context, entry)

        // Best-effort ContentProvider (may fail on package visibility).
        val providerOk = tryProviderInsert(context, entry)

        if (!broadcastOk && !providerOk && pkg != QueueSyncReceiver.MODULE_PACKAGE) {
            lastError = "cross-process write failed (broadcast+provider). Is module APK installed?"
            Log.e(TAG, lastError)
        } else {
            if (lastError.startsWith("cross-process")) lastError = ""
            Log.i(
                TAG,
                "queue upsert adamId=${entry.adamId} status=${entry.status} " +
                    "broadcast=$broadcastOk provider=$providerOk localPkg=$pkg",
            )
        }
    }

    private fun sendUpsertBroadcast(context: Context, entry: QueueEntry): Boolean {
        return try {
            val intent = Intent(QueueSyncReceiver.ACTION_UPSERT).apply {
                component = ComponentName(
                    QueueSyncReceiver.MODULE_PACKAGE,
                    QueueSyncReceiver.RECEIVER_CLASS,
                )
                setPackage(QueueSyncReceiver.MODULE_PACKAGE)
                putExtra(QueueSyncReceiver.EXTRA_ADAM_ID, entry.adamId)
                putExtra(QueueSyncReceiver.EXTRA_TITLE, entry.title)
                putExtra(QueueSyncReceiver.EXTRA_ARTIST, entry.artist)
                putExtra(QueueSyncReceiver.EXTRA_STATUS, entry.status)
                putExtra(QueueSyncReceiver.EXTRA_PROGRESS, entry.progress)
                putExtra(QueueSyncReceiver.EXTRA_FILE_PATH, entry.filePath)
                putExtra(QueueSyncReceiver.EXTRA_CREATED_AT, entry.createdAt)
                putExtra(QueueSyncReceiver.EXTRA_ERROR, entry.errorMessage)
                putExtra(QueueSyncReceiver.EXTRA_HLS_URL, entry.hlsUrl)
                putExtra(QueueSyncReceiver.EXTRA_TOTAL, entry.totalSegments)
                putExtra(QueueSyncReceiver.EXTRA_DONE, entry.completedSegments)
            }
            context.sendBroadcast(intent)
            true
        } catch (e: Exception) {
            Log.w(TAG, "broadcast upsert failed: ${e.message}")
            false
        }
    }

    private fun tryProviderInsert(context: Context, entry: QueueEntry): Boolean {
        return try {
            val values = QueueContentProvider.entryToValues(entry)
            val uri = context.contentResolver.insert(QueueContentProvider.CONTENT_URI, values)
            uri != null
        } catch (e: Exception) {
            Log.w(TAG, "provider upsert failed: ${e.message}")
            false
        }
    }

    suspend fun delete(adamId: String) = withContext(Dispatchers.IO) { deleteSync(adamId) }

    fun deleteSync(adamId: String) {
        if (!ensureInit()) return
        val context = ctx() ?: return
        if (context.packageName == QueueSyncReceiver.MODULE_PACKAGE) {
            applyLocalDelete(adamId)
        }
        try {
            val intent = Intent(QueueSyncReceiver.ACTION_DELETE).apply {
                component = ComponentName(
                    QueueSyncReceiver.MODULE_PACKAGE,
                    QueueSyncReceiver.RECEIVER_CLASS,
                )
                setPackage(QueueSyncReceiver.MODULE_PACKAGE)
                putExtra(QueueSyncReceiver.EXTRA_ADAM_ID, adamId)
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.w(TAG, "broadcast delete failed: ${e.message}")
        }
        try {
            val uri = android.net.Uri.withAppendedPath(QueueContentProvider.CONTENT_URI, adamId)
            context.contentResolver.delete(uri, null, null)
        } catch (_: Exception) {
        }
    }

    suspend fun get(adamId: String): QueueEntry? = withContext(Dispatchers.IO) {
        loadSync().firstOrNull { it.adamId == adamId }
    }

    fun snapshot(): List<QueueEntry> = loadSync()
}
