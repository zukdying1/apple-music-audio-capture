package com.neurax08.xposed.appledecryptor

import android.content.Intent
import android.util.Log
import com.neurax08.xposed.appledecryptor.download.DownloadManager
import com.neurax08.xposed.appledecryptor.download.DownloadNotificationService
import com.neurax08.xposed.appledecryptor.download.DownloadSettings
import com.neurax08.xposed.appledecryptor.download.SharedQueueStore
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.IOException
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AppleDecryptorModule : XposedModule() {
    private val playbackLeaseProxy = AtomicReference<Any?>()
    private val requestAssetMethod = AtomicReference<Method?>()
    private val targetClassLoader = AtomicReference<ClassLoader?>()
    private val m3u8ServerStarted = AtomicBoolean(false)
    private val decryptServerStarted = AtomicBoolean(false)
    private val m3u8Executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AppleDecryptor-M3U8").apply { isDaemon = true }
    }
    private val decryptExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "AppleDecryptor-Decrypt").apply { isDaemon = true }
    }

    // Captured at onPackageReady so hooks can resolve the Application context later.
    private val packageReadyParamRef = AtomicReference<PackageReadyParam?>(null)
    // Cached Application context, resolved once and reused.
    private val resolvedAppContext = AtomicReference<android.content.Context?>(null)
    companion object {
        const val TAG = "AppleDecryptor"
        const val PLAYBACK_LEASE_PROXY_CLASS = "com.apple.android.music.playback.SVPlaybackLeaseManagerProxy"
        const val REQUEST_ASSET_METHOD = "requestAsset"
        const val M3U8_PORT = 20020
        const val DECRYPT_PORT = 10020

        @Volatile
        var lastSeenAdamId: String? = null
            private set
        @Volatile
        var lastSeenHlsUrl: String? = null
            private set
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!AppleMusicHookCore.isTargetPackage(param.packageName)) {
            return
        }

        AppleMusicNativeBridge.setLogger { priority, message -> moduleLog(priority, TAG, message) }
        targetClassLoader.set(param.classLoader)
        packageReadyParamRef.set(param)

        DownloadSettings.ensureLoaded()

        // Resolve application context from PackageReadyParam (libxposed) or ActivityThread fallback.
        // NOTE: onPackageReady often runs BEFORE Application is ready → context may be null.
        // SharedQueueStore / DownloadManager also lazy-init later from hooks.
        val appContext = resolveApplicationContext(param)
        if (appContext != null) {
            bindDownloadStack(appContext, "packageReady")
        } else {
            moduleLog(
                Log.WARN,
                TAG,
                "Application context unavailable at packageReady; will lazy-init on first requestAsset",
            )
            // Retry shortly: Application is usually ready within a few hundred ms.
            Thread({
                for (attempt in 1..20) {
                    try {
                        Thread.sleep(250L)
                    } catch (_: InterruptedException) {
                        return@Thread
                    }
                    val ctx = resolveApplicationContext(param) ?: resolveAppContext()
                    if (ctx != null) {
                        bindDownloadStack(ctx, "deferred#$attempt")
                        return@Thread
                    }
                }
                moduleLog(Log.WARN, TAG, "Application context still null after deferred retries")
            }, "AppleDecryptor-CtxInit").apply {
                isDaemon = true
                start()
            }
        }

        installAppleMusicHooks(param.classLoader)

        // Socket servers are deprecated but kept for debugging when enabled in settings.
        if (DownloadSettings.isKeepSocketServersEnabled()) {
            startM3u8Server()
            startDecryptServer()
            moduleLog(Log.INFO, TAG, "Legacy socket servers enabled")
        } else {
            moduleLog(Log.INFO, TAG, "Legacy socket servers disabled by settings")
        }
    }

    private fun installAppleMusicHooks(classLoader: ClassLoader) {
        // Primary: SVPlaybackLeaseManagerProxy.requestAsset (returns MediaAssetInfo)
        runCatching {
            val proxyClass = classLoader.loadClass(PLAYBACK_LEASE_PROXY_CLASS)
            val requestAsset = proxyClass.getDeclaredMethod(
                REQUEST_ASSET_METHOD,
                Long::class.javaPrimitiveType,
                String::class.java,
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
            )

            hook(requestAsset).intercept { chain ->
                playbackLeaseProxy.compareAndSet(null, chain.thisObject)
                requestAssetMethod.compareAndSet(null, requestAsset)

                val args = chain.args.toTypedArray()
                // Apple Music 5.2.1 smali: p3 (String) is immediately overwritten by released flag read.
                // Real song id is always the long p1/p2 (args[0]). Force HLS flavor list on args[2].
                val adamIdFromArgs = extractAdamId(args)
                moduleLog(Log.WARN, TAG, "requestAsset before: ${AppleMusicHookCore.describeRequestAssetArgs(args)}")
                if (args.size >= 3) {
                    args[2] = AppleMusicHookCore.hlsAssetKinds()
                }
                moduleLog(Log.WARN, TAG, "requestAsset after: ${AppleMusicHookCore.describeRequestAssetArgs(args)}")

                val result = chain.proceed(args)
                val meta = AppleMusicHookCore.extractMediaAssetMeta(result)
                moduleLog(
                    Log.WARN,
                    TAG,
                    "requestAsset result: ${meta?.describe() ?: AppleMusicHookCore.describeRequestAssetResult(result)}",
                )

                // Prefer adamId from MediaAssetInfo.getAdamId(); fall back to long arg.
                val adamId = meta?.adamId?.takeIf { it != 0L }?.toString()
                    ?: adamIdFromArgs
                val hlsUrl = meta?.downloadUrl
                    ?: AppleMusicHookCore.extractDownloadUrl(result)
                    ?: lastSeenHlsUrl?.takeIf { lastSeenAdamId == adamId }

                handleAssetCaptured(
                    adamId = adamId,
                    hlsUrl = hlsUrl,
                    flavor = meta?.flavor,
                    keyServerUrl = meta?.keyServerUrl,
                    source = "requestAsset",
                )

                result
            }
            moduleLog(Log.WARN, TAG, "Hooked $PLAYBACK_LEASE_PROXY_CLASS.$REQUEST_ASSET_METHOD")
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "Failed to hook Apple Music lease request", error)
        }

        // Backup: MediaAssetInfo.setDownloadUrl(String) — filled by createHlsMediaAssetInfo
        // from PlaybackAssetNative.getUrlString(). Captures URL even if requestAsset hook misses meta.
        runCatching {
            val mediaAssetClass = classLoader.loadClass(
                "com.apple.android.music.playback.model.MediaAssetInfo",
            )
            val setDownloadUrl = mediaAssetClass.getDeclaredMethod(
                "setDownloadUrl",
                String::class.java,
            )
            hook(setDownloadUrl).intercept { chain ->
                val url = chain.args.getOrNull(0) as? String
                val host = chain.thisObject
                val adamFromObj = runCatching {
                    val m = host.javaClass.getMethod("getAdamId")
                    (m.invoke(host) as? Long)?.takeIf { it != 0L }?.toString()
                }.getOrNull()
                val flavor = runCatching {
                    host.javaClass.getMethod("getFlavor").invoke(host) as? String
                }.getOrNull()

                if (!url.isNullOrBlank() && AppleMusicHookCore.looksLikeMediaUrl(url)) {
                    moduleLog(
                        Log.WARN,
                        TAG,
                        "setDownloadUrl capture adamId=${adamFromObj ?: lastSeenAdamId} flavor=$flavor url=${AppleMusicHookCore.summarizeUri(url)}",
                    )
                    handleAssetCaptured(
                        adamId = adamFromObj ?: lastSeenAdamId,
                        hlsUrl = url,
                        flavor = flavor,
                        keyServerUrl = runCatching {
                            host.javaClass.getMethod("getKeyServerUrl").invoke(host) as? String
                        }.getOrNull(),
                        source = "setDownloadUrl",
                    )
                }
                chain.proceed(chain.args.toTypedArray())
            }
            moduleLog(Log.WARN, TAG, "Hooked MediaAssetInfo.setDownloadUrl")
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "Failed to hook MediaAssetInfo.setDownloadUrl", error)
        }
    }

    /**
     * Common path: write shared queue + optionally start download.
     * Safe to call from requestAsset and setDownloadUrl hooks.
     */
    private fun handleAssetCaptured(
        adamId: String?,
        hlsUrl: String?,
        flavor: String?,
        keyServerUrl: String?,
        source: String,
    ) {
        if (adamId.isNullOrBlank() || adamId == "0") {
            moduleLog(
                Log.WARN,
                TAG,
                "AutoTracker skip source=$source adamId=${adamId ?: "<null>"} hls=${hlsUrl?.let(AppleMusicHookCore::summarizeUri) ?: "<null>"}",
            )
            return
        }

        lastSeenAdamId = adamId
        if (!hlsUrl.isNullOrBlank()) {
            lastSeenHlsUrl = hlsUrl
        }
        DownloadNotificationService.updateTrackInfo(adamId, "Track $adamId", flavor.orEmpty())

        val liveCtx = resolveAppContext()
        if (liveCtx != null && !SharedQueueStore.isInitialized()) {
            bindDownloadStack(liveCtx, source)
        }

        val titleLabel = buildString {
            append("Track $adamId")
            if (!flavor.isNullOrBlank()) append(" [$flavor]")
            if (hlsUrl.isNullOrBlank()) append(" (waiting HLS)")
        }

        moduleLog(
            Log.WARN,
            TAG,
            "AutoTracker source=$source adamId=$adamId flavor=$flavor " +
                "hls=${hlsUrl?.let(AppleMusicHookCore::summarizeUri) ?: "<null>"} " +
                "keyServer=${keyServerUrl?.take(48) ?: "<null>"} " +
                "autoDl=${DownloadSettings.isAutoDownloadEnabled()}",
        )

        runCatching {
            SharedQueueStore.ensureInit(liveCtx)
            SharedQueueStore.upsertSync(
                SharedQueueStore.QueueEntry(
                    adamId = adamId,
                    title = titleLabel,
                    status = "QUEUED",
                    hlsUrl = hlsUrl.orEmpty(),
                ),
                seedContext = liveCtx,
            )
            moduleLog(
                Log.WARN,
                TAG,
                "SharedQueue wrote adamId=$adamId path=${SharedQueueStore.getActivePath()} " +
                    "err=${SharedQueueStore.lastError} init=${SharedQueueStore.isInitialized()} source=$source",
            )
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "SharedQueue write failed source=$source", error)
        }

        if (liveCtx != null) {
            runCatching { DownloadManager.init(liveCtx, asExecutor = true) }
        }

        if (!hlsUrl.isNullOrBlank()) {
            runCatching {
                DownloadManager.provideHlsUrl(
                    adamId = adamId,
                    hlsUrl = hlsUrl,
                    autoEnqueue = DownloadSettings.isAutoDownloadEnabled(),
                )
            }
        } else {
            runCatching {
                DownloadManager.enqueue(adamId = adamId, title = titleLabel, hlsUrl = "")
            }
        }
    }

    fun getLastSeenAdamId(): String? = lastSeenAdamId

    fun getLastSeenHlsUrl(): String? = lastSeenHlsUrl

    /**
     * Apple Music 5.2.1 requestAsset(long id, String ignored, String[] kinds, boolean force):
     * the String parameter is discarded immediately in smali — real id is the long.
     * Still accept non-blank String for older builds.
     */
    private fun extractAdamId(args: Array<Any?>): String? {
        val asLong = when (val v = args.getOrNull(0)) {
            is Long -> v
            is Int -> v.toLong()
            is Number -> v.toLong()
            else -> null
        }
        if (asLong != null && asLong != 0L) {
            return asLong.toString()
        }
        val asString = args.getOrNull(1) as? String
        if (!asString.isNullOrBlank() && asString != "0") {
            return asString
        }
        return null
    }

    private fun bindDownloadStack(appContext: android.content.Context, source: String) {
        runCatching {
            SharedQueueStore.init(appContext)
            moduleLog(
                Log.INFO,
                TAG,
                "SharedQueueStore init source=$source backend=${SharedQueueStore.getActivePath()} pkg=${appContext.packageName}",
            )
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "SharedQueueStore init failed source=$source", error)
        }

        runCatching {
            // Executor mode: only this process has libandroidappmusic + native decrypt.
            DownloadManager.init(appContext, asExecutor = true)
            moduleLog(Log.INFO, TAG, "DownloadManager initialized as executor source=$source")
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "Failed to init DownloadManager source=$source", error)
        }

        // Foreground service uses module resources / components — only safe in module process.
        // In Apple Music host process, skip starting the service to avoid crashes.
        // Track-info updates still work via DownloadNotificationService.updateTrackInfo statics.
        val isHostProcess = appContext.packageName != "com.neurax08.xposed.appledecryptor"
        if (isHostProcess) {
            moduleLog(Log.INFO, TAG, "Skip DownloadNotificationService in host process source=$source")
        } else {
            runCatching {
                val intent = Intent(appContext, DownloadNotificationService::class.java)
                appContext.startForegroundService(intent)
                moduleLog(Log.INFO, TAG, "DownloadNotificationService started source=$source")
            }.onFailure { error ->
                moduleLog(Log.WARN, TAG, "Failed to start notification service source=$source", error)
            }
        }
    }

    private fun resolveApplicationContext(param: PackageReadyParam?): android.content.Context? {
        // Prefer PackageReadyParam.application when present (libxposed API).
        if (param != null) {
            val fromParam = runCatching {
                param.javaClass.methods
                    .firstOrNull { it.name == "getApplication" && it.parameterCount == 0 }
                    ?.invoke(param) as? android.content.Context
                    ?: param.javaClass.methods
                        .firstOrNull { it.name == "getBaseContext" && it.parameterCount == 0 }
                        ?.invoke(param) as? android.content.Context
            }.getOrNull()
            if (fromParam != null) {
                return fromParam
            }
        }

        // Fallback: ActivityThread.currentApplication()
        return resolveActivityThreadApp()
    }

    /** Multi-strategy context resolution for use inside hook callbacks. */
    private fun resolveAppContext(): android.content.Context? {
        // 1. Already cached?
        val cached = resolvedAppContext.get()
        if (cached != null) return cached

        // 2. From stored PackageReadyParam (libxposed)
        val param = packageReadyParamRef.get()
        if (param != null) {
            // Try getApplication() via reflection (libxposed API)
            val fromParam: android.content.Context? = runCatching {
                param.javaClass.methods
                    .firstOrNull { it.name == "getApplication" && it.parameterCount == 0 }
                    ?.invoke(param) as? android.content.Context
            }.getOrNull()
            if (fromParam != null) {
                resolvedAppContext.compareAndSet(null, fromParam)
                moduleLog(Log.INFO, TAG, "resolveAppContext via PackageReadyParam pkg=${fromParam.packageName}")
                return fromParam
            }
            // Try getBaseContext() as fallback
            val fromBaseCtx: android.content.Context? = runCatching {
                param.javaClass.methods
                    .firstOrNull { it.name == "getBaseContext" && it.parameterCount == 0 }
                    ?.invoke(param) as? android.content.Context
            }.getOrNull()
            if (fromBaseCtx != null) {
                resolvedAppContext.compareAndSet(null, fromBaseCtx)
                moduleLog(Log.INFO, TAG, "resolveAppContext via PackageReadyParam.baseCtx pkg=${fromBaseCtx.packageName}")
                return fromBaseCtx
            }
        }

        // 3. ActivityThread.currentApplication() (one-step reflection)
        val fromAt = resolveActivityThreadApp()
        if (fromAt != null) {
            resolvedAppContext.compareAndSet(null, fromAt)
            moduleLog(Log.INFO, TAG, "resolveAppContext via ActivityThread pkg=${fromAt.packageName}")
            return fromAt
        }

        // 4. ActivityThread.currentActivityThread().getApplication() (two-step, more reliable)
        val fromAt2: android.content.Context? = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val currentAt = atClass.getMethod("currentActivityThread").invoke(null)
            val app = currentAt?.javaClass?.getMethod("getApplication")?.invoke(currentAt)
            app as? android.content.Context
        }.onFailure { e ->
            moduleLog(Log.WARN, TAG, "ActivityThread two-step failed: ${e.message}")
        }.getOrNull()
        if (fromAt2 != null) {
            resolvedAppContext.compareAndSet(null, fromAt2)
            moduleLog(Log.INFO, TAG, "resolveAppContext via ActivityThread.twoStep pkg=${fromAt2.packageName}")
            return fromAt2
        }

        // 5. Module self: try as Context, then unwrap ContextWrapper to get app context
        runCatching {
            val moduleCtx = (this as? android.content.Context)?.let { ctx ->
                // ModuleContext is a ContextWrapper; unwrap to get real app context
                var current = ctx
                while (current is android.content.ContextWrapper) {
                    val base = current.baseContext
                    if (base == null || base == current) break
                    current = base
                }
                current
            }
            if (moduleCtx != null) {
                val appCtx = moduleCtx.applicationContext
                resolvedAppContext.compareAndSet(null, appCtx)
                moduleLog(Log.INFO, TAG, "resolveAppContext via moduleSelf pkg=${appCtx.packageName}")
                return appCtx
            }
        }.onFailure { e ->
            moduleLog(Log.WARN, TAG, "resolveAppContext via moduleSelf failed", e)
        }

        moduleLog(Log.WARN, TAG, "resolveAppContext FAILED all strategies")
        return null
    }

    private fun resolveActivityThreadApp(): android.content.Context? {
        return runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApp = activityThreadClass.getMethod("currentApplication").invoke(null)
            currentApp as? android.content.Context
        }.onFailure { e ->
            moduleLog(Log.WARN, TAG, "ActivityThread.currentApplication() failed: ${e.message}")
        }.getOrNull()
    }

    private fun startM3u8Server() {
        if (!m3u8ServerStarted.compareAndSet(false, true)) {
            return
        }

        m3u8Executor.execute {
            runCatching {
                ServerSocket(M3U8_PORT, 50, InetAddress.getByName("127.0.0.1")).use { server ->
                    moduleLog(Log.INFO, TAG, "M3U8 socket listening on 127.0.0.1:$M3U8_PORT")
                    while (!Thread.currentThread().isInterrupted) {
                        val socket = server.accept()
                        m3u8Executor.execute { handleM3u8Client(socket) }
                    }
                }
            }.onFailure { error ->
                moduleLog(Log.WARN, TAG, "M3U8 socket server stopped", error)
                m3u8ServerStarted.set(false)
            }
        }
    }

    private fun handleM3u8Client(socket: Socket) {
        socket.use { client ->
            runCatching {
                val input = client.getInputStream()
                val size = input.read()
                if (size <= 0) {
                    AppleMusicHookCore.writeM3u8Response(client.getOutputStream(), null)
                    client.shutdownOutput()
                    return
                }

                val payload = ByteArray(size + 1)
                payload[0] = size.toByte()
                var offset = 1
                while (offset < payload.size) {
                    val read = input.read(payload, offset, payload.size - offset)
                    if (read < 0) {
                        break
                    }
                    offset += read
                }

                val adamId = AppleMusicHookCore.decodeM3u8AdamId(payload)
                val url = adamId?.let(::requestHlsUrl)
                AppleMusicHookCore.writeM3u8Response(client.getOutputStream(), url)
                client.shutdownOutput()
                moduleLog(Log.INFO, TAG, "M3U8 response adamId=$adamId url=${url ?: "<unavailable>"}")
            }.onFailure { error ->
                if (error !is IOException) {
                    moduleLog(Log.WARN, TAG, "M3U8 request failed", error)
                }
            }
        }
    }

    private fun startDecryptServer() {
        if (!decryptServerStarted.compareAndSet(false, true)) {
            return
        }

        decryptExecutor.execute {
            runCatching {
                ServerSocket(DECRYPT_PORT, 50, InetAddress.getByName("127.0.0.1")).use { server ->
                    moduleLog(Log.INFO, TAG, "Decrypt socket listening on 127.0.0.1:$DECRYPT_PORT")
                    while (!Thread.currentThread().isInterrupted) {
                        val socket = server.accept()
                        decryptExecutor.execute { handleDecryptClient(socket) }
                    }
                }
            }.onFailure { error ->
                moduleLog(Log.WARN, TAG, "Decrypt socket server stopped", error)
                decryptServerStarted.set(false)
            }
        }
    }

    private fun handleDecryptClient(socket: Socket) {
        socket.use { client ->
            runCatching {
                val input = client.getInputStream()
                sessionLoop@ while (true) {
                    val adamIdSize = input.read()
                    if (adamIdSize <= 0) {
                        break
                    }

                    val header = readDecryptHeader(input, adamIdSize)
                    val request = AppleMusicHookCore.decodeDecryptRequest(header)
                    val stats = AppleMusicHookCore.DecryptSessionStats(request?.adamId ?: "<invalid>")
                    if (request != null && AppleMusicHookCore.shouldRefreshPlaybackLease(request.adamId)) {
                        moduleLog(Log.INFO, TAG, "Decrypt lease refresh start adamId=${request.adamId}")
                        val refreshedUrl = requestHlsUrl(request.adamId)
                        moduleLog(Log.INFO, TAG, "Decrypt lease refresh result adamId=${request.adamId} url=${refreshedUrl ?: "<unavailable>"}")
                    }
                    val prepared = request?.let { AppleMusicNativeBridge.prepareSession(it.adamId, it.uri) } == true
                    moduleLog(
                        Log.INFO,
                        TAG,
                        "Decrypt session adamId=${request?.adamId ?: "<invalid>"} uri=${request?.uri?.let(AppleMusicHookCore::summarizeUri) ?: "<invalid>"} available=${AppleMusicNativeBridge.isAvailable()} status=${AppleMusicNativeBridge.resolverStatus()} prepared=$prepared",
                    )

                    if (!prepared) {
                        stats.recordFailure()
                        moduleLog(Log.WARN, TAG, stats.describeSummary())
                        AppleMusicHookCore.writeDecryptResponse(client.getOutputStream(), null)
                        break
                    }

                    sampleLoop@ while (true) {
                        when (val frame = readDecryptFrame(input)) {
                            DecryptFrameRead.EndConnection -> {
                                moduleLog(Log.INFO, TAG, stats.describeSummary())
                                break@sessionLoop
                            }
                            DecryptFrameRead.EndSession -> {
                                moduleLog(Log.INFO, TAG, stats.describeSummary())
                                break@sampleLoop
                            }
                            is DecryptFrameRead.Frame -> {
                                val encryptedSample = AppleMusicHookCore.decodeDecryptSampleFrame(frame.payload)
                                if (encryptedSample == null) {
                                    stats.recordFailure()
                                    moduleLog(Log.WARN, TAG, stats.describeSummary())
                                    break@sessionLoop
                                }

                                stats.recordSample(encryptedSample.size)
                                val decrypted = AppleMusicNativeBridge.decryptSample(encryptedSample)
                                val returnValue = AppleMusicNativeBridge.lastDecryptReturnValue()
                                if (decrypted == null) {
                                    stats.recordFailure()
                                }
                                AppleMusicHookCore.writeDecryptResponse(
                                     client.getOutputStream(),
                                     decrypted?.let(AppleMusicHookCore::encodeDecryptSampleFrame),
                                 )
                                moduleLog(Log.INFO, TAG, stats.describeSample(encryptedSample.size, returnValue))
                                if (stats.shouldLogSample()) {
                                    moduleLog(Log.INFO, TAG, "[decrypt] before=${AppleMusicHookCore.hexPreview(encryptedSample)}")
                                    moduleLog(Log.INFO, TAG, "[decrypt] after=${AppleMusicHookCore.hexPreview(decrypted ?: byteArrayOf())}")
                                    stats.recordLoggedSample()
                                }
                            }
                        }
                    }
                }

                client.shutdownOutput()
            }.onFailure { error ->
                if (error !is IOException) {
                    moduleLog(Log.WARN, TAG, "Decrypt request failed", error)
                }
            }
        }
    }

    private fun readDecryptHeader(input: java.io.InputStream, adamIdSize: Int): ByteArray {
        val adamId = readExactly(input, adamIdSize) ?: return byteArrayOf(adamIdSize.toByte())
        val uriSize = input.read()
        if (uriSize < 0) {
            return byteArrayOf(adamIdSize.toByte()) + adamId
        }
        val uri = readExactly(input, uriSize) ?: byteArrayOf()

        return byteArrayOf(adamIdSize.toByte()) + adamId + byteArrayOf(uriSize.toByte()) + uri
    }

    private fun readDecryptFrame(input: java.io.InputStream): DecryptFrameRead {
        val sizeBytes = readExactly(input, 4) ?: return DecryptFrameRead.EndConnection
        val size = ByteBuffer.wrap(sizeBytes).order(ByteOrder.LITTLE_ENDIAN).int
        if (size <= 0) {
            return DecryptFrameRead.EndSession
        }

        val sample = readExactly(input, size) ?: return DecryptFrameRead.EndConnection
        return DecryptFrameRead.Frame(sizeBytes + sample)
    }

    private sealed class DecryptFrameRead {
        data class Frame(val payload: ByteArray) : DecryptFrameRead()
        data object EndSession : DecryptFrameRead()
        data object EndConnection : DecryptFrameRead()
    }

    private fun readExactly(input: java.io.InputStream, size: Int): ByteArray? {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = input.read(buffer, offset, size - offset)
            if (read < 0) {
                return null
            }
            offset += read
        }
        return buffer
    }

    private fun requestHlsUrl(adamId: String): String? {
        var proxy = playbackLeaseProxy.get()
        var method = requestAssetMethod.get()

        if (proxy == null || method == null) {
            proxy = findPlaybackLeaseProxyFromClassLoader()
            method = proxy?.let { resolveRequestAssetMethod(it.javaClass) }
            if (proxy == null || method == null) {
                moduleLog(Log.WARN, TAG, "M3U8 request skipped: proxy/method unavailable ${describeLeaseRefreshState()}")
                return null
            }
        }

        return runCatching {
            val result = method.invoke(proxy, adamId.toLongOrNull() ?: 0L, null, AppleMusicHookCore.hlsAssetKinds(), false)
            AppleMusicHookCore.extractDownloadUrl(result)
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "M3U8 requestAsset refresh failed for adamId=$adamId", error)
            // If invocation fails, proxy may be stale — clear for rediscovery
            if (error is IllegalArgumentException || error is NullPointerException) {
                playbackLeaseProxy.compareAndSet(proxy, null)
            }
        }.getOrNull()
    }

    private fun findPlaybackLeaseProxyFromClassLoader(): Any? {
        val classLoader = targetClassLoader.get() ?: return null
        return runCatching {
            val proxyClass = classLoader.loadClass(PLAYBACK_LEASE_PROXY_CLASS)
            AppleMusicHookCore.findStaticInstance(proxyClass, proxyClass)?.also { proxy ->
                playbackLeaseProxy.set(proxy)
                moduleLog(Log.INFO, TAG, "Found playback lease proxy from static field: ${proxy.javaClass.name}")
            }
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "Failed to probe playback lease proxy ${describeLeaseRefreshState()}", error)
        }.getOrNull()
    }

    private fun resolveRequestAssetMethod(proxyClass: Class<*>): Method? {
        return runCatching {
            proxyClass.getDeclaredMethod(
                REQUEST_ASSET_METHOD,
                Long::class.javaPrimitiveType,
                String::class.java,
                Array<String>::class.java,
                Boolean::class.javaPrimitiveType,
            ).apply {
                isAccessible = true
                requestAssetMethod.set(this)
            }
        }.onFailure { error ->
            moduleLog(Log.WARN, TAG, "Failed to resolve requestAsset method from cached proxy", error)
        }.getOrNull()
    }

    private fun describeLeaseRefreshState(): String {
        val proxy = playbackLeaseProxy.get()
        val method = requestAssetMethod.get()
        val classLoader = targetClassLoader.get()
        val proxyClass = runCatching { classLoader?.loadClass(PLAYBACK_LEASE_PROXY_CLASS) }.getOrNull()
        val overloads = proxyClass?.declaredMethods?.count { it.name == REQUEST_ASSET_METHOD } ?: 0
        return AppleMusicHookCore.describeLeaseProbeState(
            proxyClassFound = proxyClass != null,
            requestAssetOverloads = overloads,
            cachedProxy = proxy != null,
            methodAvailable = method != null,
            classLoaderAvailable = classLoader != null,
        )
    }

    private fun moduleLog(priority: Int, tag: String, message: String) {
        if (AppleMusicHookCore.shouldLog(priority, BuildConfig.DEBUG)) {
            super.log(priority, tag, message)
        }
    }

    private fun moduleLog(priority: Int, tag: String, message: String, throwable: Throwable) {
        if (AppleMusicHookCore.shouldLog(priority, BuildConfig.DEBUG)) {
            super.log(priority, tag, message, throwable)
        }
    }
}
