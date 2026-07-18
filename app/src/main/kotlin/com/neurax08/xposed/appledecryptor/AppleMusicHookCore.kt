package com.neurax08.xposed.appledecryptor

import java.io.OutputStream

object AppleMusicHookCore {
    private const val MAX_DECRYPT_SAMPLE_LOGS = 3

    const val TARGET_PACKAGE = "com.apple.android.music"
    const val NATIVE_TARGET_LIBRARY = "libandroidappmusic.so"
    const val SESSION_CTRL_INSTANCE_SYMBOL = "_ZN21SVFootHillSessionCtrl8instanceEv"
    const val GET_PERSISTENT_KEY_SYMBOL = "_ZN21SVFootHillSessionCtrl16getPersistentKeyERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEES8_S8_S8_S8_S8_S8_S8_"
    const val DECRYPT_CONTEXT_SYMBOL = "_ZN21SVFootHillSessionCtrl14decryptContextERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEERKN11SVDecryptor15SVDecryptorTypeERKb"
    const val RESET_ALL_CONTEXTS_SYMBOL = "_ZN21SVFootHillSessionCtrl16resetAllContextsEv"
    const val DECRYPT_SAMPLE_SYMBOL = "NfcRKVnxuKZy04KWbdFu71Ou"
    const val PRESHARE_KEY_URI = "skd://itunes.apple.com/P000000000/s1/e1"
    const val KEY_FORMAT = "com.apple.streamingkeydelivery"
    const val KEY_FORMAT_VERSION = "1"
    const val SERVER_URI = "https://play.itunes.apple.com/WebObjects/MZPlay.woa/music/fps"
    const val PROTOCOL_TYPE = "simplified"
    // FairPlay certificate is managed in the C++ native layer (appledecryptor.cpp).
    // The C++ code applies a two-byte patch at offset 3218 to reconstruct the full
    // 3464-byte Apple FPS certificate. Kotlin does not hold a copy of the certificate.

    data class DecryptRequest(
        val adamId: String,
        val uri: String,
        val bytesRead: Int,
    )

    fun isTargetPackage(packageName: String): Boolean = packageName == TARGET_PACKAGE

    fun hlsAssetKinds(): Array<String> = arrayOf("HLS")

    fun describeRequestAssetArgs(args: Array<Any?>): String {
        val id = args.getOrNull(0)
        val adamId = args.getOrNull(1)
        val assetKinds = args.getOrNull(2).describeValue()
        val force = args.getOrNull(3) ?: "<missing>"

        return "id=$id, adamId=$adamId, assetKinds=$assetKinds, force=$force"
    }

    fun describeRequestAssetResult(result: Any?): String {
        if (result == null) {
            return "null"
        }

        val downloadUrl = extractDownloadUrl(result) ?: "<unavailable>"

        return "${result.javaClass.name}, downloadUrl=$downloadUrl"
    }

    fun extractDownloadUrl(result: Any?): String? {
        if (result == null) {
            return null
        }

        val value = runCatching {
            result.javaClass.getDeclaredMethod("getDownloadUrl").apply {
                isAccessible = true
            }.invoke(result)
        }.getOrNull()

        return (value as? String)?.takeIf { it.isNotBlank() }
    }

    fun decodeM3u8AdamId(payload: ByteArray): String? {
        return decodeLengthPrefixedString(payload)
    }

    fun decodeDecryptRequest(payload: ByteArray): DecryptRequest? {
        val adamId = decodeLengthPrefixedString(payload) ?: return null
        val uriOffset = 1 + adamId.encodeToByteArray().size
        val uri = decodeLengthPrefixedString(payload, uriOffset) ?: return null

        return DecryptRequest(
            adamId = adamId,
            uri = uri,
            bytesRead = uriOffset + 1 + uri.encodeToByteArray().size,
        )
    }

    private fun decodeLengthPrefixedString(payload: ByteArray, offset: Int = 0): String? {
        if (payload.size <= offset) {
            return null
        }

        val size = payload[offset].toInt() and 0xff
        if (size == 0 || payload.size < offset + size + 1) {
            return null
        }

        return payload.copyOfRange(offset + 1, offset + size + 1).decodeToString()
    }

    fun decodeDecryptSampleFrame(payload: ByteArray): ByteArray? {
        if (payload.size < 4) {
            return null
        }

        val size = (payload[0].toInt() and 0xff) or
            ((payload[1].toInt() and 0xff) shl 8) or
            ((payload[2].toInt() and 0xff) shl 16) or
            ((payload[3].toInt() and 0xff) shl 24)
        if (size <= 0 || payload.size < 4 + size) {
            return null
        }

        return payload.copyOfRange(4, 4 + size)
    }

    fun encodeDecryptSampleFrame(sample: ByteArray): ByteArray = sample

    data class DecryptSessionStats(
        val adamId: String,
        var samples: Int = 0,
        var bytes: Long = 0,
        var failures: Int = 0,
        var loggedSamples: Int = 0,
    ) {
        fun recordSample(size: Int) {
            samples += 1
            bytes += size.toLong()
        }

        fun recordFailure() {
            failures += 1
        }

        fun shouldLogSample(): Boolean = loggedSamples < MAX_DECRYPT_SAMPLE_LOGS

        fun recordLoggedSample() {
            loggedSamples += 1
        }

        fun describeSample(sampleSize: Int, returnValue: Long): String {
            return "[decrypt] sample adamId=$adamId n=$samples size=$sampleSize ret=$returnValue"
        }

        fun describeSummary(): String {
            return "[decrypt] summary adamId=$adamId samples=$samples bytes=$bytes failures=$failures"
        }
    }

    fun hexPreview(bytes: ByteArray, limit: Int = 24): String {
        val size = minOf(bytes.size, limit.coerceAtLeast(0))
        return buildString {
            for (index in 0 until size) {
                if (index > 0) {
                    append(' ')
                }
                append((bytes[index].toInt() and 0xff).toString(16).padStart(2, '0'))
            }
        }
    }

    fun shouldLog(priority: Int, debug: Boolean): Boolean = debug || priority >= android.util.Log.WARN

    fun parseNativeLogEntry(entry: String): Pair<Int, String> {
        return when {
            entry.startsWith("ERROR: ") -> android.util.Log.ERROR to entry.removePrefix("ERROR: ")
            entry.startsWith("WARN: ") -> android.util.Log.WARN to entry.removePrefix("WARN: ")
            entry.startsWith("INFO: ") -> android.util.Log.INFO to entry.removePrefix("INFO: ")
            entry.startsWith("DEBUG: ") -> android.util.Log.DEBUG to entry.removePrefix("DEBUG: ")
            else -> android.util.Log.INFO to entry
        }
    }

    fun shouldRefreshPlaybackLease(adamId: String): Boolean = adamId.isNotBlank() && adamId != "0"

    fun describeLeaseProbeState(
        proxyClassFound: Boolean,
        requestAssetOverloads: Int,
        cachedProxy: Boolean,
        methodAvailable: Boolean,
        classLoaderAvailable: Boolean,
    ): String {
        return "proxyClass=$proxyClassFound requestAssetOverloads=$requestAssetOverloads cachedProxy=$cachedProxy method=$methodAvailable classLoader=$classLoaderAvailable"
    }

    fun findStaticInstance(holderClass: Class<*>, expectedType: Class<*>): Any? {
        return holderClass.declaredFields.firstNotNullOfOrNull { field ->
            runCatching {
                if (!java.lang.reflect.Modifier.isStatic(field.modifiers)) {
                    return@runCatching null
                }

                field.isAccessible = true
                field.get(null)?.takeIf { expectedType.isInstance(it) }
            }.getOrNull()
        }
    }

    fun summarizeUri(uri: String): String {
        if (uri.isEmpty()) {
            return "len=0 prefix=<empty>"
        }

        val prefix = if (uri.length > 30) "${uri.take(30)}..." else uri
        return "len=${uri.length} prefix=$prefix"
    }

    fun nativeResolverStatus(
        nativeLoaded: Boolean,
        targetLibraryLoaded: Boolean,
        hasSessionCtrlInstance: Boolean,
        hasGetPersistentKey: Boolean,
        hasDecryptContext: Boolean,
        hasResetAllContexts: Boolean,
        hasDecryptSample: Boolean,
    ): String = when {
        !nativeLoaded -> "unavailable"
        !targetLibraryLoaded -> "library_missing"
        !hasSessionCtrlInstance || !hasGetPersistentKey || !hasDecryptContext || !hasResetAllContexts || !hasDecryptSample -> "symbol_missing"
        else -> "ready"
    }

    fun encodeM3u8Response(url: String?): ByteArray = "${url.orEmpty()}\n".encodeToByteArray()

    fun writeM3u8Response(output: OutputStream, url: String?) {
        output.write(encodeM3u8Response(url))
        output.flush()
    }

    fun writeDecryptResponse(output: OutputStream, sample: ByteArray?) {
        output.write(sample ?: byteArrayOf())
        output.flush()
    }

    private fun Any?.describeValue(): String = when (this) {
        is Array<*> -> joinToString(prefix = "[", postfix = "]")
        else -> toString()
    }
}