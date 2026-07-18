package com.neurax08.xposed.appledecryptor

import android.util.Log

class AppleMusicNativeBridgeState(
    loadLibrary: () -> Boolean,
    private val nativeIsAvailable: () -> Boolean,
    private val nativeResolverStatus: () -> String,
    private val nativePrepareSession: (String, String) -> Boolean,
    private val nativeDecryptSample: (ByteArray) -> ByteArray,
    private val nativeDecryptSamples: (Array<ByteArray>) -> Array<ByteArray>? = { null },
    private val nativeLastDecryptReturnValue: () -> Long = { 0L },
    private val nativeDrainLogs: () -> Array<String> = { emptyArray() },
    logger: (Int, String) -> Unit = { _, _ -> },
) {
    @Volatile
    private var logger: (Int, String) -> Unit = logger
    private val loaded: Boolean = runCatching {
        loadLibrary()
    }.getOrDefault(false)
    private var sessionPrepared: Boolean = false
    private var presharePrepared: Boolean = false
    private var activeAdamId: String? = null
    private var activeUri: String? = null
    private var lastAvailabilityLog: String? = null

    fun setLogger(logger: (Int, String) -> Unit) {
        this.logger = logger
    }

    fun isAvailable(): Boolean {
        val available = loaded && runCatching { nativeIsAvailable() }.getOrDefault(false)
        logAvailability(available)
        drainNativeLogs()
        return available
    }

    fun resolverStatus(): String {
        if (!loaded) {
            return "unavailable"
        }

        val status = nativeResolverStatus().takeIf { it.isNotBlank() } ?: "unavailable"
        drainNativeLogs()
        return status
    }

    @Synchronized
    fun prepareSession(adamId: String, uri: String): Boolean {
        if (!isAvailable()) {
            logger(Log.WARN, "prepareSession skipped: native unavailable adamId=$adamId status=${resolverStatus()}")
            return false
        }

        if (adamId == "0") {
            if (sessionPrepared && activeAdamId == adamId && activeUri == uri) {
                logger(Log.INFO, "prepareSession reuse adamId=$adamId uri=${AppleMusicHookCore.summarizeUri(uri)}")
                return true
            }

            logger(Log.INFO, "prepareSession preshare start uri=${AppleMusicHookCore.summarizeUri(uri)}")
            presharePrepared = nativePrepareSession(adamId, uri)
            drainNativeLogs()
            sessionPrepared = presharePrepared
            activeAdamId = if (presharePrepared) adamId else null
            activeUri = if (presharePrepared) uri else null
            logger(Log.INFO, "prepareSession preshare result=$presharePrepared status=${resolverStatus()}")
            return presharePrepared
        }

        if (!presharePrepared) {
            logger(Log.INFO, "prepareSession preshare start uri=${AppleMusicHookCore.summarizeUri(AppleMusicHookCore.PRESHARE_KEY_URI)}")
            presharePrepared = nativePrepareSession("0", AppleMusicHookCore.PRESHARE_KEY_URI)
            drainNativeLogs()
            logger(Log.INFO, "prepareSession preshare result=$presharePrepared status=${resolverStatus()}")
            if (!presharePrepared) {
                sessionPrepared = false
                activeAdamId = null
                activeUri = null
                return false
            }
        }

        if (sessionPrepared && activeAdamId == adamId && activeUri == uri) {
            logger(Log.INFO, "prepareSession reuse adamId=$adamId uri=${AppleMusicHookCore.summarizeUri(uri)}")
            return true
        }

        logger(Log.INFO, "prepareSession target start adamId=$adamId uri=${AppleMusicHookCore.summarizeUri(uri)}")
        sessionPrepared = nativePrepareSession(adamId, uri)
        drainNativeLogs()
        activeAdamId = if (sessionPrepared) adamId else null
        activeUri = if (sessionPrepared) uri else null
        logger(Log.INFO, "prepareSession target result=$sessionPrepared adamId=$adamId status=${resolverStatus()}")
        return sessionPrepared
    }

    @Synchronized
    fun decryptSample(sample: ByteArray): ByteArray? {
        if (!isAvailable() || !sessionPrepared) {
            logger(Log.WARN, "decryptSample skipped: available=${isAvailable()} sessionPrepared=$sessionPrepared in=${sample.size} status=${resolverStatus()}")
            return null
        }

        logger(Log.INFO, "decryptSample before=${AppleMusicHookCore.hexPreview(sample)}")
        val decrypted = nativeDecryptSample(sample).takeIf { it.isNotEmpty() }
        drainNativeLogs()
        if (decrypted == null) {
            logger(Log.WARN, "decryptSample failed: in=${sample.size} out=0 status=${resolverStatus()}")
        } else {
            val returnValue = lastDecryptReturnValue()
            logger(Log.INFO, "decryptSample after=${AppleMusicHookCore.hexPreview(decrypted)}")
            logger(Log.INFO, "decryptSample ok: in=${sample.size} out=${decrypted.size} ret=$returnValue")
        }
        return decrypted
    }

    @Synchronized
    fun decryptSamples(samples: Array<ByteArray>): List<ByteArray>? {
        if (!isAvailable() || !sessionPrepared) {
            logger(Log.WARN, "decryptSamples skipped: available=${isAvailable()} sessionPrepared=$sessionPrepared count=${samples.size} status=${resolverStatus()}")
            return null
        }

        val results = nativeDecryptSamples(samples)
        drainNativeLogs()
        if (results == null) {
            logger(Log.WARN, "decryptSamples failed: count=${samples.size} out=null")
            return null
        }

        return results.toList()
    }

    fun lastDecryptReturnValue(): Long {
        return runCatching { nativeLastDecryptReturnValue() }.getOrDefault(0L)
    }

    private fun logAvailability(available: Boolean) {
        val status = if (!loaded) "unavailable" else runCatching { nativeResolverStatus() }.getOrDefault("unavailable")
        val message = "available=$available status=$status"
        if (message != lastAvailabilityLog) {
            logger(Log.INFO, "native bridge $message")
            lastAvailabilityLog = message
        }
    }

    private fun drainNativeLogs() {
        runCatching { nativeDrainLogs() }
            .getOrDefault(emptyArray())
            .forEach { message ->
                if (message.isNotBlank()) {
                    val (priority, text) = AppleMusicHookCore.parseNativeLogEntry(message)
                    logger(priority, "native: $text")
                }
            }
    }

    private companion object {
        const val TAG = "AppleDecryptor"
    }
}

object AppleMusicNativeBridge {
    private const val LIBRARY_NAME = "appledecryptor"

    private val state = AppleMusicNativeBridgeState(
        loadLibrary = {
            runCatching {
                System.loadLibrary(LIBRARY_NAME)
                safeLog(Log.INFO, "Loaded native library $LIBRARY_NAME")
                true
            }.onFailure { error ->
                safeLog(Log.WARN, "Failed to load native library $LIBRARY_NAME: ${error.javaClass.simpleName}: ${error.message}")
            }.getOrDefault(false)
        },
        nativeIsAvailable = { nativeIsAvailable() },
        nativeResolverStatus = { nativeResolverStatus() },
        nativePrepareSession = { adamId, uri -> nativePrepareSession(adamId, uri) },
        nativeDecryptSample = { sample -> nativeDecryptSample(sample) },
        nativeDecryptSamples = { samples -> nativeDecryptSamples(samples) },
        nativeLastDecryptReturnValue = { nativeLastDecryptReturnValue() },
        nativeDrainLogs = { nativeDrainLogs() },
        logger = ::safeLog,
    )

    fun isAvailable(): Boolean = state.isAvailable()

    fun resolverStatus(): String = state.resolverStatus()

    fun prepareSession(adamId: String, uri: String): Boolean = state.prepareSession(adamId, uri)

    fun decryptSample(sample: ByteArray): ByteArray? = state.decryptSample(sample)

    fun decryptSamples(samples: Array<ByteArray>): List<ByteArray>? = state.decryptSamples(samples)

    fun lastDecryptReturnValue(): Long = state.lastDecryptReturnValue()

    fun setLogger(logger: (Int, String) -> Unit) {
        state.setLogger(logger)
    }

    private fun safeLog(priority: Int, message: String) {
        if (AppleMusicHookCore.shouldLog(priority, BuildConfig.DEBUG)) {
            runCatching { Log.println(priority, "AppleDecryptor", message) }
        }
    }

    private external fun nativeIsAvailable(): Boolean

    private external fun nativeResolverStatus(): String

    private external fun nativePrepareSession(adamId: String, uri: String): Boolean

    private external fun nativeDecryptSample(sample: ByteArray): ByteArray

    private external fun nativeDecryptSamples(samples: Array<ByteArray>): Array<ByteArray>?

    private external fun nativeLastDecryptReturnValue(): Long

    private external fun nativeDrainLogs(): Array<String>
}
