package com.neurax08.xposed.appledecryptor.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Cross-process queue store backed by a shared JSON file.
 *
 * Primary path:  /sdcard/Music/AppleDecryptor/queue.json
 * Fallback path: /data/local/tmp/appledecryptor_queue.json  (world-readable when rooted)
 *
 * Both the module UI process and the Apple Music hook process use this file so the
 * download list is visible across process boundaries.
 */
object SharedQueueStore {
    private const val TAG = "AppleDecryptor"
    private const val MAX_ITEMS = 200

    private val CANDIDATE_PATHS = listOf(
        "/sdcard/Music/AppleDecryptor/queue.json",
        "/storage/emulated/0/Music/AppleDecryptor/queue.json",
        "/data/local/tmp/appledecryptor_queue.json",
    )

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

    private val memoryItems = mutableListOf<QueueEntry>()
    private val changeCounter = AtomicLong(0)

    @Volatile
    private var activePath: String? = null

    @Volatile
    var lastError: String = ""
        private set

    fun resolvePath(): String? {
        activePath?.let { existing ->
            val f = File(existing)
            if (f.parentFile?.exists() == true || f.parentFile?.mkdirs() == true) {
                return existing
            }
        }
        for (path in CANDIDATE_PATHS) {
            val file = File(path)
            val parent = file.parentFile ?: continue
            try {
                if (!parent.exists() && !parent.mkdirs()) {
                    continue
                }
                // Prove we can write.
                val probe = File(parent, ".queue_write_probe")
                FileOutputStream(probe).use { it.write(1) }
                probe.delete()
                activePath = path
                Log.i(TAG, "SharedQueueStore path=$path")
                return path
            } catch (e: Exception) {
                Log.w(TAG, "queue path unavailable $path: ${e.message}")
            }
        }
        lastError = "No writable queue path among $CANDIDATE_PATHS"
        Log.e(TAG, lastError)
        return null
    }

    /**
     * Load queue from disk. Returns a snapshot sorted by createdAt DESC.
     */
    suspend fun load(): List<QueueEntry> = withContext(Dispatchers.IO) {
        loadSync()
    }

    fun loadSync(): List<QueueEntry> {
        return try {
            val path = resolvePath()
            if (path == null) {
                synchronized(memoryItems) { return memoryItems.toList() }
            }
            val file = File(path)
            if (!file.exists() || file.length() == 0L) {
                synchronized(memoryItems) { return memoryItems.toList() }
            }
            val text = withSharedLock(file, shared = true) {
                file.readText()
            } ?: file.readText()
            if (text.isBlank()) {
                synchronized(memoryItems) { return memoryItems.toList() }
            }
            val arr = JSONArray(text)
            val loaded = (0 until arr.length())
                .map { parseEntry(arr.getJSONObject(it)) }
                .filter { it.adamId.isNotBlank() }
                .sortedByDescending { it.createdAt }
            synchronized(memoryItems) {
                memoryItems.clear()
                memoryItems.addAll(loaded)
            }
            changeCounter.incrementAndGet()
            loaded
        } catch (e: Exception) {
            lastError = "load failed: ${e.message}"
            Log.w(TAG, lastError, e)
            synchronized(memoryItems) { memoryItems.toList() }
        }
    }

    /**
     * Add or update an entry and persist to disk.
     * Merges non-blank fields from [entry] over the existing row when present.
     */
    suspend fun upsert(entry: QueueEntry) = withContext(Dispatchers.IO) {
        upsertSync(entry)
    }

    fun upsertSync(entry: QueueEntry) {
        if (entry.adamId.isBlank()) return
        try {
            // Always reload from disk first so we don't clobber other process updates.
            loadSync()
            synchronized(memoryItems) {
                val idx = memoryItems.indexOfFirst { it.adamId == entry.adamId }
                if (idx >= 0) {
                    val old = memoryItems[idx]
                    memoryItems[idx] = merge(old, entry)
                } else {
                    memoryItems.add(0, entry)
                    while (memoryItems.size > MAX_ITEMS) {
                        memoryItems.removeAt(memoryItems.lastIndex)
                    }
                }
            }
            persistSync()
            changeCounter.incrementAndGet()
            Log.i(TAG, "queue upsert adamId=${entry.adamId} status=${entry.status} path=${activePath}")
        } catch (e: Exception) {
            lastError = "upsert failed: ${e.message}"
            Log.w(TAG, lastError, e)
        }
    }

    suspend fun delete(adamId: String) = withContext(Dispatchers.IO) {
        deleteSync(adamId)
    }

    fun deleteSync(adamId: String) {
        try {
            loadSync()
            synchronized(memoryItems) {
                memoryItems.removeAll { it.adamId == adamId }
            }
            persistSync()
            changeCounter.incrementAndGet()
        } catch (e: Exception) {
            lastError = "delete failed: ${e.message}"
            Log.w(TAG, lastError, e)
        }
    }

    suspend fun get(adamId: String): QueueEntry? = withContext(Dispatchers.IO) {
        loadSync()
        synchronized(memoryItems) {
            memoryItems.find { it.adamId == adamId }
        }
    }

    fun snapshot(): List<QueueEntry> = synchronized(memoryItems) {
        memoryItems.toList()
    }

    fun getChangeCounter(): Long = changeCounter.get()

    fun getActivePath(): String = activePath ?: "(none)"

    private fun merge(old: QueueEntry, incoming: QueueEntry): QueueEntry {
        return QueueEntry(
            adamId = old.adamId,
            title = incoming.title.ifBlank { old.title },
            artist = incoming.artist.ifBlank { old.artist },
            status = incoming.status.ifBlank { old.status },
            progress = if (incoming.progress > 0 || incoming.status == "COMPLETED" || incoming.status == "FAILED") {
                incoming.progress
            } else {
                old.progress
            },
            filePath = incoming.filePath.ifBlank { old.filePath },
            createdAt = old.createdAt,
            errorMessage = when {
                incoming.status == "FAILED" -> incoming.errorMessage
                incoming.status == "QUEUED" || incoming.status == "DOWNLOADING" -> ""
                else -> incoming.errorMessage.ifBlank { old.errorMessage }
            },
            hlsUrl = incoming.hlsUrl.ifBlank { old.hlsUrl },
            totalSegments = if (incoming.totalSegments > 0) incoming.totalSegments else old.totalSegments,
            completedSegments = if (incoming.completedSegments > 0 || incoming.status == "COMPLETED") {
                incoming.completedSegments
            } else {
                old.completedSegments
            },
        )
    }

    private fun persistSync() {
        val path = resolvePath() ?: return
        val file = File(path)
        val parent = file.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        val arr = JSONArray()
        synchronized(memoryItems) {
            for (item in memoryItems) {
                arr.put(toJson(item))
            }
        }
        val payload = arr.toString()
        // Atomic-ish write via temp + rename.
        val tmp = File(file.parentFile, "${file.name}.tmp")
        withSharedLock(file, shared = false) {
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                // rename can fail across mounts; fall back to direct write.
                file.writeText(payload)
                tmp.delete()
            }
        } ?: run {
            tmp.writeText(payload)
            if (!tmp.renameTo(file)) {
                file.writeText(payload)
                tmp.delete()
            }
        }
    }

    private fun <T> withSharedLock(file: File, shared: Boolean, block: () -> T): T? {
        return try {
            if (!file.exists() && !shared) {
                file.parentFile?.mkdirs()
                file.writeText("[]")
            }
            if (!file.exists()) {
                return block()
            }
            FileOutputStream(file, true).channel.use { channel ->
                var lock: FileLock? = null
                try {
                    lock = if (shared) channel.tryLock(0L, Long.MAX_VALUE, true) else channel.tryLock()
                    block()
                } finally {
                    try {
                        lock?.release()
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "file lock skipped: ${e.message}")
            null
        }
    }

    private fun parseEntry(obj: JSONObject): QueueEntry {
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

    private fun toJson(entry: QueueEntry): JSONObject {
        return JSONObject().apply {
            put("adamId", entry.adamId)
            put("title", entry.title)
            put("artist", entry.artist)
            put("status", entry.status)
            put("progress", entry.progress)
            put("filePath", entry.filePath)
            put("createdAt", entry.createdAt)
            put("errorMessage", entry.errorMessage)
            put("hlsUrl", entry.hlsUrl)
            put("totalSegments", entry.totalSegments)
            put("completedSegments", entry.completedSegments)
        }
    }
}
