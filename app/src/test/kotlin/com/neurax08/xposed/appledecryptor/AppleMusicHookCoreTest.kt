package com.neurax08.xposed.appledecryptor

import android.util.Log
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import java.io.ByteArrayOutputStream

class AppleMusicHookCoreTest {
    @Test
    fun targetPackageMatchesAppleMusicOnly() {
        assertTrue(AppleMusicHookCore.isTargetPackage("com.apple.android.music"))
        assertFalse(AppleMusicHookCore.isTargetPackage("com.apple.android.music.beta"))
        assertFalse(AppleMusicHookCore.isTargetPackage("com.neurax08.xposed.appledecryptor"))
    }

    @Test
    fun hlsAssetKindsMatchOriginalFridaRequestAssetCall() {
        assertContentEquals(arrayOf("HLS"), AppleMusicHookCore.hlsAssetKinds())
    }

    @Test
    fun describeRequestAssetArgsShowsPrimitiveAndAssetKinds() {
        val args = arrayOf<Any?>(42L, "song-id", arrayOf("MP4", "HLS"), true)

        val description = AppleMusicHookCore.describeRequestAssetArgs(args)

        assertEquals("id=42, adamId=song-id, assetKinds=[MP4, HLS], force=true", description)
    }

    @Test
    fun describeRequestAssetArgsHandlesMissingOrUnexpectedValues() {
        val args = arrayOf<Any?>(null, 7, null)

        val description = AppleMusicHookCore.describeRequestAssetArgs(args)

        assertEquals("id=null, adamId=7, assetKinds=null, force=<missing>", description)
    }

    @Test
    fun describeRequestAssetResultLogsDownloadUrlWhenAvailable() {
        val result = MediaAssetInfoFixture("https://example.invalid/stream.m3u8")

        val description = AppleMusicHookCore.describeRequestAssetResult(result)

        assertEquals(
            "com.neurax08.xposed.appledecryptor.AppleMusicHookCoreTest\$MediaAssetInfoFixture, downloadUrl=https://example.invalid/stream.m3u8",
            description,
        )
    }

    @Test
    fun describeRequestAssetResultHandlesNullAndMissingDownloadUrl() {
        assertEquals("null", AppleMusicHookCore.describeRequestAssetResult(null))
        assertEquals(
            "java.lang.Object, downloadUrl=<unavailable>",
            AppleMusicHookCore.describeRequestAssetResult(Any()),
        )
    }

    @Test
    fun extractDownloadUrlReturnsUrlWhenMediaAssetInfoExposesIt() {
        val result = MediaAssetInfoFixture("https://example.invalid/stream.m3u8")

        val url = AppleMusicHookCore.extractDownloadUrl(result)

        assertEquals("https://example.invalid/stream.m3u8", url)
    }

    @Test
    fun extractDownloadUrlReturnsNullForUnavailableValues() {
        assertEquals(null, AppleMusicHookCore.extractDownloadUrl(null))
        assertEquals(null, AppleMusicHookCore.extractDownloadUrl(Any()))
        assertEquals(null, AppleMusicHookCore.extractDownloadUrl(MediaAssetInfoFixture("")))
        assertEquals(null, AppleMusicHookCore.extractDownloadUrl(NonStringMediaAssetInfoFixture(7)))
    }

    @Test
    fun decodeM3u8AdamIdReadsOriginalFridaLengthPrefixedProtocol() {
        val bytes = byteArrayOf(10) + "1258936985".encodeToByteArray()

        val adamId = AppleMusicHookCore.decodeM3u8AdamId(bytes)

        assertEquals("1258936985", adamId)
    }

    @Test
    fun decodeM3u8AdamIdRejectsEmptyOrIncompletePayloads() {
        assertNull(AppleMusicHookCore.decodeM3u8AdamId(byteArrayOf()))
        assertNull(AppleMusicHookCore.decodeM3u8AdamId(byteArrayOf(0)))
        assertNull(AppleMusicHookCore.decodeM3u8AdamId(byteArrayOf(3, '1'.code.toByte())))
    }

    @Test
    fun encodeM3u8ResponseMatchesOriginalFridaLineProtocol() {
        val response = AppleMusicHookCore.encodeM3u8Response("https://example.invalid/stream.m3u8")

        assertContentEquals("https://example.invalid/stream.m3u8\n".encodeToByteArray(), response)
    }

    @Test
    fun encodeM3u8ResponseReturnsBlankLineWhenUrlUnavailable() {
        assertContentEquals("\n".encodeToByteArray(), AppleMusicHookCore.encodeM3u8Response(null))
        assertContentEquals("\n".encodeToByteArray(), AppleMusicHookCore.encodeM3u8Response(""))
    }

    @Test
    fun writeM3u8ResponseFlushesEncodedLine() {
        val output = ByteArrayOutputStream()

        AppleMusicHookCore.writeM3u8Response(output, "https://example.invalid/stream.m3u8")

        assertContentEquals("https://example.invalid/stream.m3u8\n".encodeToByteArray(), output.toByteArray())
    }

    @Test
    fun nativeSymbolsMatchOriginalFridaScript() {
        assertEquals("libandroidappmusic.so", AppleMusicHookCore.NATIVE_TARGET_LIBRARY)
        assertEquals("_ZN21SVFootHillSessionCtrl8instanceEv", AppleMusicHookCore.SESSION_CTRL_INSTANCE_SYMBOL)
        assertEquals(
            "_ZN21SVFootHillSessionCtrl16getPersistentKeyERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEES8_S8_S8_S8_S8_S8_S8_",
            AppleMusicHookCore.GET_PERSISTENT_KEY_SYMBOL,
        )
        assertEquals(
            "_ZN21SVFootHillSessionCtrl14decryptContextERKNSt6__ndk112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEERKN11SVDecryptor15SVDecryptorTypeERKb",
            AppleMusicHookCore.DECRYPT_CONTEXT_SYMBOL,
        )
        assertEquals("_ZN21SVFootHillSessionCtrl16resetAllContextsEv", AppleMusicHookCore.RESET_ALL_CONTEXTS_SYMBOL)
        assertEquals("NfcRKVnxuKZy04KWbdFu71Ou", AppleMusicHookCore.DECRYPT_SAMPLE_SYMBOL)
    }

    @Test
    fun fairPlayDefaultsMatchOriginalScript() {
        assertEquals("skd://itunes.apple.com/P000000000/s1/e1", AppleMusicHookCore.PRESHARE_KEY_URI)
        assertEquals("com.apple.streamingkeydelivery", AppleMusicHookCore.KEY_FORMAT)
        assertEquals("1", AppleMusicHookCore.KEY_FORMAT_VERSION)
        assertEquals("https://play.itunes.apple.com/WebObjects/MZPlay.woa/music/fps", AppleMusicHookCore.SERVER_URI)
        assertEquals("simplified", AppleMusicHookCore.PROTOCOL_TYPE)
        // FAIRPLAY_CERTIFICATE removed from Kotlin — the certificate is managed
        // exclusively in the C++ native layer (appledecryptor.cpp), which applies
        // a two-byte patch at offset 3218 to reconstruct the full 3464-byte cert.
    }

    @Test
    fun decodeDecryptRequestUsesOriginalSocketProtocol() {
        val payload = byteArrayOf(10) + "1821585531".encodeToByteArray() + byteArrayOf(33) +
            "skd://itunes.apple.com/P000000001".encodeToByteArray()

        val request = AppleMusicHookCore.decodeDecryptRequest(payload)

        assertEquals(
            AppleMusicHookCore.DecryptRequest(
                adamId = "1821585531",
                uri = "skd://itunes.apple.com/P000000001",
                bytesRead = payload.size,
            ),
            request,
        )
    }

    @Test
    fun decryptSessionsRefreshPlaybackLeaseForTargetAdamIdsOnly() {
        assertFalse(AppleMusicHookCore.shouldRefreshPlaybackLease("0"))
        assertFalse(AppleMusicHookCore.shouldRefreshPlaybackLease(""))
        assertTrue(AppleMusicHookCore.shouldRefreshPlaybackLease("1821585531"))
    }

    @Test
    fun leaseProbeStateSummarizesClassMethodProxyAndClassLoaderAvailability() {
        val summary = AppleMusicHookCore.describeLeaseProbeState(
            proxyClassFound = true,
            requestAssetOverloads = 2,
            cachedProxy = false,
            methodAvailable = true,
            classLoaderAvailable = true,
        )

        assertEquals(
            "proxyClass=true requestAssetOverloads=2 cachedProxy=false method=true classLoader=true",
            summary,
        )
    }

    @Test
    fun staticLeaseProxyFallbackFindsRuntimeInstanceFromStaticFields() {
        assertSame(
            LeaseProxyFixture.INSTANCE,
            AppleMusicHookCore.findStaticInstance(LeaseProxyFixture::class.java, LeaseProxyFixture::class.java),
        )
        assertNull(AppleMusicHookCore.findStaticInstance(EmptyStaticFixture::class.java, LeaseProxyFixture::class.java))
    }

    @Test
    fun uriSummaryKeepsLogsShort() {
        assertEquals("len=0 prefix=<empty>", AppleMusicHookCore.summarizeUri(""))
        assertEquals("len=10 prefix=skd://abcd", AppleMusicHookCore.summarizeUri("skd://abcd"))
        assertEquals(
            "len=33 prefix=skd://itunes.apple.com/P000000...",
            AppleMusicHookCore.summarizeUri("skd://itunes.apple.com/P000000001"),
        )
    }

    @Test
    fun decodeDecryptRequestRejectsInvalidPayloads() {
        assertNull(AppleMusicHookCore.decodeDecryptRequest(byteArrayOf()))
        assertNull(AppleMusicHookCore.decodeDecryptRequest(byteArrayOf(0)))
        assertNull(AppleMusicHookCore.decodeDecryptRequest(byteArrayOf(4, 'a'.code.toByte())))
        assertNull(AppleMusicHookCore.decodeDecryptRequest(byteArrayOf(1, '0'.code.toByte(), 3, 's'.code.toByte())))
    }

    @Test
    fun decodeDecryptSampleFrameReadsLittleEndianSizeAndBytes() {
        val payload = byteArrayOf(3, 0, 0, 0, 0x10, 0x20, 0x30)

        val frame = AppleMusicHookCore.decodeDecryptSampleFrame(payload)

        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30), frame)
    }

    @Test
    fun decodeDecryptSampleFrameRejectsStopOrIncompletePayloads() {
        assertNull(AppleMusicHookCore.decodeDecryptSampleFrame(byteArrayOf()))
        assertNull(AppleMusicHookCore.decodeDecryptSampleFrame(byteArrayOf(0, 0, 0, 0)))
        assertNull(AppleMusicHookCore.decodeDecryptSampleFrame(byteArrayOf(4, 0, 0, 0, 0x01)))
    }

    @Test
    fun encodeDecryptSampleFrameReturnsRawSampleBytes() {
        assertContentEquals(byteArrayOf(0x01, 0x02), AppleMusicHookCore.encodeDecryptSampleFrame(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun hexPreviewMatchesOriginalFridaFormat() {
        val bytes = byteArrayOf(0x00, 0x01, 0x0f, 0x10, 0xff.toByte())

        assertEquals("00 01 0f 10 ff", AppleMusicHookCore.hexPreview(bytes, 24))
        assertEquals("00 01 0f", AppleMusicHookCore.hexPreview(bytes, 3))
        assertEquals("", AppleMusicHookCore.hexPreview(byteArrayOf(), 24))
    }

    @Test
    fun decryptSessionStatsMatchOriginalFridaSummary() {
        val stats = AppleMusicHookCore.DecryptSessionStats("1821585531")

        assertTrue(stats.shouldLogSample())
        stats.recordSample(4)
        assertEquals("[decrypt] sample adamId=1821585531 n=1 size=4 ret=7", stats.describeSample(4, 7L))
        stats.recordLoggedSample()
        stats.recordSample(2)
        stats.recordFailure()

        assertEquals("[decrypt] summary adamId=1821585531 samples=2 bytes=6 failures=1", stats.describeSummary())
    }

    @Test
    fun releaseLogPolicySuppressesVerboseLogsAndKeepsWarnings() {
        assertTrue(AppleMusicHookCore.shouldLog(Log.INFO, debug = true))
        assertTrue(AppleMusicHookCore.shouldLog(Log.WARN, debug = false))
        assertTrue(AppleMusicHookCore.shouldLog(Log.ERROR, debug = false))
        assertFalse(AppleMusicHookCore.shouldLog(Log.INFO, debug = false))
        assertFalse(AppleMusicHookCore.shouldLog(Log.DEBUG, debug = false))
    }

    @Test
    fun nativeLogEntryParserPreservesPriorityPrefixes() {
        assertEquals(Log.ERROR to "native crash guarded", AppleMusicHookCore.parseNativeLogEntry("ERROR: native crash guarded"))
        assertEquals(Log.WARN to "decryptSample aborted", AppleMusicHookCore.parseNativeLogEntry("WARN: decryptSample aborted"))
        assertEquals(Log.INFO to "resolver ready", AppleMusicHookCore.parseNativeLogEntry("INFO: resolver ready"))
        assertEquals(Log.INFO to "legacy entry", AppleMusicHookCore.parseNativeLogEntry("legacy entry"))
    }

    @Test
    fun nativeBridgeStartsUnavailableUntilNativeLayerInitializes() {
        assertFalse(AppleMusicNativeBridge.isAvailable())
        assertEquals("unavailable", AppleMusicNativeBridge.resolverStatus())
        assertFalse(AppleMusicNativeBridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertNull(AppleMusicNativeBridge.decryptSample(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun nativeBridgeQueriesAvailabilityDynamicallyAfterLibraryLoad() {
        var nativeReady = false
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { nativeReady },
            nativeResolverStatus = { if (nativeReady) "ready" else "library_missing" },
            nativePrepareSession = { _, _ -> nativeReady },
            nativeDecryptSample = { sample -> if (nativeReady) sample else byteArrayOf() },
        )

        assertFalse(bridge.isAvailable())
        assertEquals("library_missing", bridge.resolverStatus())

        nativeReady = true

        assertTrue(bridge.isAvailable())
        assertEquals("ready", bridge.resolverStatus())
        assertTrue(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertContentEquals(byteArrayOf(0x01, 0x02), bridge.decryptSample(byteArrayOf(0x01, 0x02)))
    }

    @Test
    fun nativeBridgeDrainsNativeLogsThroughKotlinLogger() {
        val messages = mutableListOf<String>()
        var nativeLogs = arrayOf("resolver symbols: instance=0x1", "prepareSession start adamId=0")
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { _, _ -> true },
            nativeDecryptSample = { sample -> sample },
            nativeDrainLogs = {
                nativeLogs.also { nativeLogs = emptyArray() }
            },
            logger = { _, message -> messages += message },
        )

        assertTrue(bridge.isAvailable())
        assertTrue(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))

        assertTrue(messages.contains("native: resolver symbols: instance=0x1"))
        assertTrue(messages.contains("native: prepareSession start adamId=0"))
        assertEquals(1, messages.count { it == "native: resolver symbols: instance=0x1" })
    }

    @Test
    fun nativeBridgePreservesNativeWarningAndErrorPriorities() {
        val messages = mutableListOf<Pair<Int, String>>()
        var nativeLogs = arrayOf("WARN: decryptSample aborted", "ERROR: native crash guarded")
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { _, _ -> true },
            nativeDecryptSample = { sample -> sample },
            nativeDrainLogs = {
                nativeLogs.also { nativeLogs = emptyArray() }
            },
            logger = { priority, message -> messages += priority to message },
        )

        assertTrue(bridge.isAvailable())

        assertTrue(messages.contains(Log.WARN to "native: decryptSample aborted"))
        assertTrue(messages.contains(Log.ERROR to "native: native crash guarded"))
    }

    @Test
    fun nativeBridgeLoggerCanBeAttachedAfterConstruction() {
        val messages = mutableListOf<String>()
        var nativeLogs = arrayOf("failed to resolve libandroidappmusic.so: namespace miss")
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { false },
            nativeResolverStatus = { "library_missing" },
            nativePrepareSession = { _, _ -> false },
            nativeDecryptSample = { byteArrayOf() },
            nativeDrainLogs = {
                nativeLogs.also { nativeLogs = emptyArray() }
            },
        )

        bridge.setLogger { _, message -> messages += message }
        assertFalse(bridge.isAvailable())

        assertTrue(messages.contains("native: failed to resolve libandroidappmusic.so: namespace miss"))
    }

    @Test
    fun nativeBridgeDecryptsOnlyAfterSessionPreparationSucceeds() {
        var decryptCalls = 0
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { _, uri -> uri.startsWith("skd://") },
            nativeDecryptSample = { sample ->
                decryptCalls++
                sample
            },
        )

        assertNull(bridge.decryptSample(byteArrayOf(0x01)))
        assertEquals(0, decryptCalls)

        assertFalse(bridge.prepareSession("1821585531", "https://example.invalid/key"))
        assertNull(bridge.decryptSample(byteArrayOf(0x01)))
        assertEquals(0, decryptCalls)

        assertTrue(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertContentEquals(byteArrayOf(0x01), bridge.decryptSample(byteArrayOf(0x01)))
        assertEquals(1, decryptCalls)
    }

    @Test
    fun nativeBridgeLogsDecryptSamplePreviews() {
        val messages = mutableListOf<String>()
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { _, _ -> true },
            nativeDecryptSample = { byteArrayOf(0x10, 0x20, 0x30) },
            logger = { _, message -> messages += message },
        )

        assertTrue(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30), bridge.decryptSample(byteArrayOf(0x01, 0x02, 0x03)))

        assertTrue(messages.contains("decryptSample before=01 02 03"))
        assertTrue(messages.contains("decryptSample after=10 20 30"))
    }

    @Test
    fun nativeBridgeTracksOriginalDecryptSampleReturnValue() {
        val messages = mutableListOf<String>()
        var nativeReturnValue = 0L
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { _, _ -> true },
            nativeDecryptSample = {
                nativeReturnValue = 19L
                byteArrayOf(0x10)
            },
            nativeLastDecryptReturnValue = { nativeReturnValue },
            logger = { _, message -> messages += message },
        )

        assertTrue(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertContentEquals(byteArrayOf(0x10), bridge.decryptSample(byteArrayOf(0x01)))

        assertEquals(19L, bridge.lastDecryptReturnValue())
        assertTrue(messages.contains("decryptSample ok: in=1 out=1 ret=19"))
    }

    @Test
    fun nativeBridgePreparesPreshareBeforeTargetSessionAndReusesCurrentSession() {
        val prepared = mutableListOf<Pair<String, String>>()
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { adamId, uri ->
                prepared += adamId to uri
                uri.startsWith("skd://")
            },
            nativeDecryptSample = { sample -> sample },
        )

        assertTrue(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertTrue(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertTrue(bridge.prepareSession("1821585532", "skd://itunes.apple.com/P000000002"))

        assertEquals(
            listOf(
                "0" to AppleMusicHookCore.PRESHARE_KEY_URI,
                "1821585531" to "skd://itunes.apple.com/P000000001",
                "1821585532" to "skd://itunes.apple.com/P000000002",
            ),
            prepared,
        )
    }

    @Test
    fun nativeBridgeAllowsDirectPreshareSessionFromDecryptProtocol() {
        val prepared = mutableListOf<Pair<String, String>>()
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { adamId, uri ->
                prepared += adamId to uri
                true
            },
            nativeDecryptSample = { sample -> sample },
        )

        assertTrue(bridge.prepareSession("0", AppleMusicHookCore.PRESHARE_KEY_URI))
        assertContentEquals(byteArrayOf(0x01), bridge.decryptSample(byteArrayOf(0x01)))
        assertEquals(listOf("0" to AppleMusicHookCore.PRESHARE_KEY_URI), prepared)
    }

    @Test
    fun nativeBridgeDoesNotPrepareTargetSessionWhenPreshareFails() {
        val prepared = mutableListOf<Pair<String, String>>()
        val bridge = AppleMusicNativeBridgeState(
            loadLibrary = { true },
            nativeIsAvailable = { true },
            nativeResolverStatus = { "ready" },
            nativePrepareSession = { adamId, uri ->
                prepared += adamId to uri
                adamId != "0" && uri.startsWith("skd://")
            },
            nativeDecryptSample = { sample -> sample },
        )

        assertFalse(bridge.prepareSession("1821585531", "skd://itunes.apple.com/P000000001"))
        assertNull(bridge.decryptSample(byteArrayOf(0x01)))
        assertEquals(listOf("0" to AppleMusicHookCore.PRESHARE_KEY_URI), prepared)
    }

    @Test
    fun nativeResolverStatusesCoverLibrarySymbolAndReadyStates() {
        assertEquals("unavailable", AppleMusicHookCore.nativeResolverStatus(false, false, false, false, false, false, false))
        assertEquals("library_missing", AppleMusicHookCore.nativeResolverStatus(true, false, false, false, false, false, false))
        assertEquals("symbol_missing", AppleMusicHookCore.nativeResolverStatus(true, true, false, false, false, false, false))
        assertEquals("symbol_missing", AppleMusicHookCore.nativeResolverStatus(true, true, true, false, false, false, false))
        assertEquals("symbol_missing", AppleMusicHookCore.nativeResolverStatus(true, true, true, true, false, false, false))
        assertEquals("symbol_missing", AppleMusicHookCore.nativeResolverStatus(true, true, true, true, true, false, false))
        assertEquals("symbol_missing", AppleMusicHookCore.nativeResolverStatus(true, true, true, true, true, true, false))
        assertEquals("ready", AppleMusicHookCore.nativeResolverStatus(true, true, true, true, true, true, true))
    }

    private class MediaAssetInfoFixture(private val downloadUrl: String) {
        fun getDownloadUrl(): String = downloadUrl
    }

    private class NonStringMediaAssetInfoFixture(private val downloadUrl: Int) {
        fun getDownloadUrl(): Int = downloadUrl
    }

    private class LeaseProxyFixture {
        companion object {
            @JvmField
            val INSTANCE = LeaseProxyFixture()
        }
    }

    private class EmptyStaticFixture
}
