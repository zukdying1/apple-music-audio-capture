package com.neurax08.xposed.appledecryptor.download

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cross-process settings.
 *
 * Prefer module ContentProvider-backed file is not available from host process for settings;
 * use multi-path candidates that both module UI and Apple Music can often share:
 *  - /sdcard/Music/AppleDecryptor/settings.json (legacy, often EPERM)
 *  - app external/files dirs when Context known
 *  - /data/local/tmp/appledecryptor_settings.json (debug fallback)
 *
 * Defaults: autoDownload OFF.
 */
object DownloadSettings {
    private const val TAG = "AppleDecryptor"
    private const val SETTINGS_NAME = "settings.json"

    private val autoDownload = AtomicBoolean(false)
    private val keepSocketServers = AtomicBoolean(false)
    private val preferSampleLevelDecrypt = AtomicBoolean(true)

    private val appContext = AtomicReference<Context?>(null)

    @Volatile
    private var loaded = false

    @Volatile
    private var activePath: String = "uninitialized"

    fun init(context: Context) {
        appContext.set(context.applicationContext)
        // Reload from disk with context-aware paths
        loaded = false
        ensureLoaded()
    }

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            loadFromDisk()
            loaded = true
        }
    }

    fun isAutoDownloadEnabled(): Boolean {
        ensureLoaded()
        return autoDownload.get()
    }

    fun setAutoDownloadEnabled(enabled: Boolean) {
        ensureLoaded()
        autoDownload.set(enabled)
        persist()
        Log.i(TAG, "setAutoDownloadEnabled=$enabled path=$activePath")
    }

    fun isKeepSocketServersEnabled(): Boolean {
        ensureLoaded()
        return keepSocketServers.get()
    }

    fun setKeepSocketServersEnabled(enabled: Boolean) {
        ensureLoaded()
        keepSocketServers.set(enabled)
        persist()
    }

    fun isPreferSampleLevelDecrypt(): Boolean {
        ensureLoaded()
        return preferSampleLevelDecrypt.get()
    }

    fun setPreferSampleLevelDecrypt(enabled: Boolean) {
        ensureLoaded()
        preferSampleLevelDecrypt.set(enabled)
        persist()
    }

    fun getActivePath(): String {
        ensureLoaded()
        return activePath
    }

    fun settingsFile(): File {
        return candidateFiles().firstOrNull { it.exists() }
            ?: candidateFiles().first()
    }

    private fun candidateFiles(): List<File> {
        val list = ArrayList<File>()
        val ctx = appContext.get()
        if (ctx != null) {
            list.add(File(ctx.filesDir, "appledecryptor_$SETTINGS_NAME"))
            ctx.getExternalFilesDir(null)?.let {
                list.add(File(it, "AppleDecryptor/$SETTINGS_NAME"))
            }
        }
        list.add(File("/sdcard/Music/AppleDecryptor/$SETTINGS_NAME"))
        list.add(File("/storage/emulated/0/Music/AppleDecryptor/$SETTINGS_NAME"))
        list.add(File("/data/local/tmp/appledecryptor_$SETTINGS_NAME"))
        return list
    }

    private fun loadFromDisk() {
        for (file in candidateFiles()) {
            runCatching {
                if (!file.exists() || file.length() == 0L) return@runCatching
                val json = JSONObject(file.readText())
                autoDownload.set(json.optBoolean("autoDownload", false))
                keepSocketServers.set(json.optBoolean("keepSocketServers", false))
                preferSampleLevelDecrypt.set(json.optBoolean("preferSampleLevelDecrypt", true))
                activePath = file.absolutePath
                Log.i(
                    TAG,
                    "settings loaded from $activePath autoDownload=${autoDownload.get()} " +
                        "keepSocket=${keepSocketServers.get()} sampleDecrypt=${preferSampleLevelDecrypt.get()}",
                )
                return
            }.onFailure { e ->
                Log.w(TAG, "settings load skip ${file.absolutePath}: ${e.message}")
            }
        }
        activePath = "defaults (no file)"
        Log.i(TAG, "settings using defaults autoDownload=false")
    }

    private fun persist() {
        val json = JSONObject()
            .put("autoDownload", autoDownload.get())
            .put("keepSocketServers", keepSocketServers.get())
            .put("preferSampleLevelDecrypt", preferSampleLevelDecrypt.get())
            .toString()

        var saved = false
        for (file in candidateFiles()) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    file.writeText(json)
                    tmp.delete()
                }
                // verify
                if (file.exists() && file.length() > 0) {
                    activePath = file.absolutePath
                    saved = true
                    Log.i(TAG, "settings persisted $activePath")
                    return
                }
            }.onFailure { e ->
                Log.w(TAG, "settings persist skip ${file.absolutePath}: ${e.message}")
            }
        }
        if (!saved) {
            Log.e(TAG, "settings persist FAILED all paths")
        }
    }
}
