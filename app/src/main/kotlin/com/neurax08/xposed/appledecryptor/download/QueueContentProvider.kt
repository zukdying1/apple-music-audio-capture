package com.neurax08.xposed.appledecryptor.download

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Cross-process queue storage.
 *
 * Host (Apple Music hook) writes via ContentResolver.
 * Module UI reads via ContentResolver / SharedQueueStore.
 * Data lives in THIS app's private filesDir — always writable.
 */
class QueueContentProvider : ContentProvider() {
    companion object {
        private const val TAG = "AppleDecryptor"
        const val AUTHORITY = "com.neurax08.xposed.appledecryptor.queue"
        const val PATH_ITEMS = "items"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_ITEMS")

        private const val CODE_ITEMS = 1
        private const val CODE_ITEM_ID = 2
        private const val FILE_NAME = "queue.json"

        private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_ITEMS, CODE_ITEMS)
            addURI(AUTHORITY, "$PATH_ITEMS/*", CODE_ITEM_ID)
        }

        private val lock = ReentrantReadWriteLock()

        val COLUMNS = arrayOf(
            "adamId", "title", "artist", "status", "progress", "filePath",
            "createdAt", "errorMessage", "hlsUrl", "totalSegments", "completedSegments",
        )

        fun file(context: Context): File = File(context.filesDir, FILE_NAME)

        fun readAll(context: Context): MutableList<JSONObject> {
            return lock.read {
                val f = file(context)
                if (!f.exists() || f.length() == 0L) {
                    return@read mutableListOf()
                }
                try {
                    val arr = JSONArray(f.readText())
                    MutableList(arr.length()) { arr.getJSONObject(it) }
                } catch (e: Exception) {
                    Log.w(TAG, "queue read failed: ${e.message}")
                    mutableListOf()
                }
            }
        }

        fun writeAll(context: Context, items: List<JSONObject>) {
            lock.write {
                val arr = JSONArray()
                items.forEach { arr.put(it) }
                val f = file(context)
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(arr.toString())
                if (!tmp.renameTo(f)) {
                    f.writeText(arr.toString())
                    tmp.delete()
                }
            }
        }

        fun entryToValues(entry: SharedQueueStore.QueueEntry): ContentValues {
            return ContentValues().apply {
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

        fun valuesToJson(values: ContentValues, existing: JSONObject? = null): JSONObject {
            val base = existing ?: JSONObject()
            fun putStr(key: String) {
                if (values.containsKey(key)) {
                    base.put(key, values.getAsString(key) ?: "")
                } else if (!base.has(key)) {
                    base.put(key, "")
                }
            }
            fun putInt(key: String, default: Int = 0) {
                if (values.containsKey(key)) {
                    base.put(key, values.getAsInteger(key) ?: default)
                } else if (!base.has(key)) {
                    base.put(key, default)
                }
            }
            fun putLong(key: String, default: Long) {
                if (values.containsKey(key)) {
                    base.put(key, values.getAsLong(key) ?: default)
                } else if (!base.has(key)) {
                    base.put(key, default)
                }
            }
            putStr("adamId")
            putStr("title")
            putStr("artist")
            putStr("status")
            putInt("progress")
            putStr("filePath")
            putLong("createdAt", System.currentTimeMillis())
            putStr("errorMessage")
            putStr("hlsUrl")
            putInt("totalSegments")
            putInt("completedSegments")
            return base
        }

        fun jsonToEntry(obj: JSONObject): SharedQueueStore.QueueEntry {
            return SharedQueueStore.QueueEntry(
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
    }

    override fun onCreate(): Boolean {
        Log.i(TAG, "QueueContentProvider onCreate uid=${Binder.getCallingUid()}")
        return true
    }

    override fun getType(uri: Uri): String {
        return when (matcher.match(uri)) {
            CODE_ITEMS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.item"
            CODE_ITEM_ID -> "vnd.android.cursor.item/vnd.$AUTHORITY.item"
            else -> "vnd.android.cursor.dir/vnd.$AUTHORITY.item"
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: return MatrixCursor(COLUMNS)
        val cols = projection ?: COLUMNS
        val cursor = MatrixCursor(cols)
        val items = readAll(ctx)
        val adamFilter = when (matcher.match(uri)) {
            CODE_ITEM_ID -> uri.lastPathSegment
            else -> selectionArgs?.firstOrNull()?.takeIf { selection?.contains("adamId") == true }
        }
        val filtered = if (adamFilter.isNullOrBlank()) {
            items
        } else {
            items.filter { it.optString("adamId") == adamFilter }
        }
        val sorted = filtered.sortedByDescending { it.optLong("createdAt", 0L) }
        for (obj in sorted) {
            val row = Array(cols.size) { idx ->
                val key = cols[idx]
                when (key) {
                    "progress", "totalSegments", "completedSegments" -> obj.optInt(key, 0)
                    "createdAt" -> obj.optLong(key, 0L)
                    else -> obj.optString(key, "")
                }
            }
            cursor.addRow(row)
        }
        cursor.setNotificationUri(ctx.contentResolver, CONTENT_URI)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val ctx = context ?: return null
        if (values == null) return null
        val adamId = values.getAsString("adamId")?.trim().orEmpty()
        if (adamId.isEmpty()) return null

        val items = readAll(ctx)
        val idx = items.indexOfFirst { it.optString("adamId") == adamId }
        val merged = if (idx >= 0) {
            valuesToJson(values, items[idx]).also { items[idx] = it }
        } else {
            valuesToJson(values, null).also {
                if (!it.has("status") || it.optString("status").isBlank()) {
                    it.put("status", "QUEUED")
                }
                if (!it.has("createdAt") || it.optLong("createdAt") == 0L) {
                    it.put("createdAt", System.currentTimeMillis())
                }
                items.add(0, it)
            }
        }
        // Cap size
        while (items.size > 200) {
            items.removeAt(items.lastIndex)
        }
        writeAll(ctx, items)
        ctx.contentResolver.notifyChange(CONTENT_URI, null)
        Log.i(TAG, "queue provider insert/upsert adamId=$adamId status=${merged.optString("status")} fromUid=${Binder.getCallingUid()}")
        return ContentUris.withAppendedId(CONTENT_URI, adamId.hashCode().toLong() and 0x7fffffff)
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val ctx = context ?: return 0
        if (values == null) return 0
        val adamId = when (matcher.match(uri)) {
            CODE_ITEM_ID -> uri.lastPathSegment
            else -> values.getAsString("adamId") ?: selectionArgs?.firstOrNull()
        }?.trim().orEmpty()
        if (adamId.isEmpty()) return 0

        val items = readAll(ctx)
        val idx = items.indexOfFirst { it.optString("adamId") == adamId }
        if (idx < 0) {
            // Treat update as insert
            insert(CONTENT_URI, values.apply { put("adamId", adamId) })
            return 1
        }
        items[idx] = valuesToJson(values, items[idx])
        writeAll(ctx, items)
        ctx.contentResolver.notifyChange(CONTENT_URI, null)
        Log.i(TAG, "queue provider update adamId=$adamId status=${items[idx].optString("status")}")
        return 1
    }

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        val ctx = context ?: return 0
        val adamId = when (matcher.match(uri)) {
            CODE_ITEM_ID -> uri.lastPathSegment
            else -> selectionArgs?.firstOrNull()
        }?.trim().orEmpty()

        val items = readAll(ctx)
        val before = items.size
        if (adamId.isBlank()) {
            // delete completed only if selection says so
            if (selection?.contains("COMPLETED") == true) {
                items.removeAll { it.optString("status") == "COMPLETED" }
            } else {
                items.clear()
            }
        } else {
            items.removeAll { it.optString("adamId") == adamId }
        }
        val removed = before - items.size
        if (removed > 0) {
            writeAll(ctx, items)
            ctx.contentResolver.notifyChange(CONTENT_URI, null)
        }
        return removed
    }
}
