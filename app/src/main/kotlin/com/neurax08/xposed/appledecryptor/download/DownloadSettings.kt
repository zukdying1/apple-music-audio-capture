package com.neurax08.xposed.appledecryptor.download

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Cross-process settings stored on shared external path so the module UI process
 * and the Apple Music (hook) process see the same values.
 */
object DownloadSettings {
    private const val TAG = "AppleDecryptor"
    private const val SETTINGS_DIR = "/sdcard/Music/AppleDecryptor"
    private const val SETTINGS_FILE = "settings.json"

    // Defaults match design: auto-detect can enqueue; sockets kept for compatibility.
    private val autoDownload = AtomicBoolean(true)
    private val keepSocketServers = AtomicBoolean(true)
    private val preferSampleLevelDecrypt = AtomicBoolean(true)

    @Volatile
    private var loaded = false

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

    fun settingsFile(): File = File(SETTINGS_DIR, SETTINGS_FILE)

    private fun loadFromDisk() {
        runCatching {
            val file = settingsFile()
            if (!file.exists()) {
                return
            }
            val json = JSONObject(file.readText())
            autoDownload.set(json.optBoolean("autoDownload", true))
            keepSocketServers.set(json.optBoolean("keepSocketServers", true))
            preferSampleLevelDecrypt.set(json.optBoolean("preferSampleLevelDecrypt", true))
            Log.i(
                TAG,
                "settings loaded autoDownload=${autoDownload.get()} keepSocket=${keepSocketServers.get()} sampleDecrypt=${preferSampleLevelDecrypt.get()}",
            )
        }.onFailure { error ->
            Log.w(TAG, "settings load failed: ${error.message}")
        }
    }

    private fun persist() {
        runCatching {
            val dir = File(SETTINGS_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            val json = JSONObject()
                .put("autoDownload", autoDownload.get())
                .put("keepSocketServers", keepSocketServers.get())
                .put("preferSampleLevelDecrypt", preferSampleLevelDecrypt.get())
            settingsFile().writeText(json.toString())
        }.onFailure { error ->
            Log.w(TAG, "settings persist failed: ${error.message}")
        }
    }
}
