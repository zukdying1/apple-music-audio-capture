package com.neurax08.xposed.appledecryptor.download

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Resolve writable output directories.
 *
 * Downloads run inside the Apple Music process (hook). Scoped storage blocks
 * /sdcard/Music/... (EPERM). Prefer app-scoped external / internal dirs which
 * never need legacy WRITE_EXTERNAL_STORAGE.
 */
object OutputPaths {
    private const val TAG = "AppleDecryptor"
    private const val SUBDIR = "AppleDecryptor"
    const val LEGACY_PUBLIC = "/sdcard/Music/AppleDecryptor"

    @Volatile
    private var resolvedDir: File? = null

    fun musicDir(context: Context?): File {
        resolvedDir?.let { if (canWrite(it)) return it }

        val candidates = buildList {
            if (context != null) {
                // 1) App-scoped external Music — no permission, user-visible via file managers
                //    /storage/emulated/0/Android/data/<pkg>/files/Music/AppleDecryptor
                runCatching {
                    context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
                }.getOrNull()?.let { add(File(it, SUBDIR)) }

                // 2) App-scoped external files root
                runCatching {
                    context.getExternalFilesDir(null)
                }.getOrNull()?.let { add(File(it, SUBDIR)) }

                // 3) Internal files
                add(File(context.filesDir, SUBDIR))

                // 4) Cache (always writable)
                add(File(context.cacheDir, SUBDIR))
            }
            // 5) Legacy public (often EPERM on Android 11+)
            add(File(LEGACY_PUBLIC))
            // 6) Emulated path variant
            add(File("/storage/emulated/0/Music/AppleDecryptor"))
            // 7) tmp (may need root)
            add(File("/data/local/tmp/AppleDecryptor"))
        }

        for (dir in candidates) {
            if (ensureWritable(dir)) {
                resolvedDir = dir
                Log.i(TAG, "OutputPaths using ${dir.absolutePath}")
                return dir
            } else {
                Log.w(TAG, "OutputPaths not writable: ${dir.absolutePath}")
            }
        }

        // Absolute last resort: java temp
        val fallback = File(System.getProperty("java.io.tmpdir") ?: "/data/local/tmp", SUBDIR)
        ensureWritable(fallback)
        resolvedDir = fallback
        Log.w(TAG, "OutputPaths fallback ${fallback.absolutePath}")
        return fallback
    }

    fun tempDir(context: Context?): File {
        // Prefer cache for intermediate .mdat.tmp — never touch /sdcard
        if (context != null) {
            val cache = File(context.cacheDir, "$SUBDIR/tmp")
            if (ensureWritable(cache)) return cache
            val files = File(context.filesDir, "$SUBDIR/tmp")
            if (ensureWritable(files)) return files
        }
        val musicTmp = File(musicDir(context), "tmp")
        if (ensureWritable(musicTmp)) return musicTmp
        return musicDir(context)
    }

    fun safeFileName(title: String, ext: String): String {
        val base = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .trim()
            .takeIf { it.isNotBlank() } ?: "unknown"
        val e = if (ext.startsWith(".")) ext else ".$ext"
        return base + e
    }

    private fun ensureWritable(dir: File): Boolean {
        return try {
            if (!dir.exists() && !dir.mkdirs()) return false
            if (!dir.isDirectory) return false
            val probe = File(dir, ".write_probe_${System.nanoTime()}")
            probe.writeText("ok")
            val ok = probe.exists() && probe.length() > 0
            probe.delete()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "ensureWritable failed ${dir.absolutePath}: ${e.message}")
            false
        }
    }

    private fun canWrite(dir: File): Boolean {
        return try {
            dir.isDirectory && dir.canWrite()
        } catch (_: Exception) {
            false
        }
    }
}
