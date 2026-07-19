package com.neurax08.xposed.appledecryptor.download

import android.content.Context
import android.util.Log
import com.neurax08.xposed.appledecryptor.AppleMusicNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.nio.ByteBuffer

object DownloadManager {
    private const val TAG = "AppleDecryptor"
    private const val MAX_CONCURRENT_DOWNLOADS = 2
    private const val MAX_RETRIES = 3
    private const val BATCH_DECRYPT_SIZE = 16
    private const val QUEUE_POLL_INTERVAL_MS = 2000L

    private var initialized = false
    private var executorMode = false
    private var applicationContext: Context? = null
    private var database: DownloadDatabase? = null
    private var dao: DownloadQueueDao? = null
    private val hlsDownloader = HlsDownloader()
    private val alacExtractor = AlacFrameExtractor()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadSemaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)
    private val activeJobs = mutableMapOf<String, Job>()
    private val activeJobsMutex = Mutex()
    private var pollerJob: Job? = null

    private val _currentDownloads = MutableStateFlow<Map<String, DownloadProgress>>(emptyMap())
    val currentDownloads: StateFlow<Map<String, DownloadProgress>> = _currentDownloads

    data class DownloadProgress(
        val adamId: String,
        val title: String,
        val progress: Int,
        val status: String,
        val errorMessage: String = "",
    )

    /**
     * @param asExecutor true only inside the Apple Music process where native decrypt works.
     * UI process should call with asExecutor=false so it only observes/enqueues shared DB rows.
     */
    fun init(context: Context, asExecutor: Boolean = false) {
        if (initialized) {
            if (asExecutor && !executorMode) {
                executorMode = true
                startQueuePoller()
                Log.i(TAG, "DownloadManager elevated to executor mode")
            }
            return
        }
        applicationContext = context.applicationContext
        DownloadSettings.init(context.applicationContext)
        SharedQueueStore.init(context.applicationContext)
        try {
            database = DownloadDatabase.getInstance(context.applicationContext)
            dao = database?.downloadQueueDao()
            Log.i(TAG, "Database initialized at: ${DownloadDatabase.dbPathUsed}")
        } catch (e: Exception) {
            Log.e(TAG, "Database init failed; downloads will be unavailable", e)
            database = null
            dao = null
        }
        executorMode = asExecutor
        initialized = true
        Log.i(TAG, "DownloadManager initialized sharedDb=true executor=$asExecutor")
        if (asExecutor) {
            startQueuePoller()
        }
    }

    fun isExecutor(): Boolean = executorMode

    fun canExecuteDownloads(): Boolean {
        return executorMode && AppleMusicNativeBridge.isAvailable()
    }

    fun getAllItems(): Flow<List<DownloadQueueItem>> {
        return dao?.getAllItems() ?: MutableStateFlow(emptyList())
    }

    fun enqueue(adamId: String, title: String = "", artist: String = "", hlsUrl: String = "") {
        if (!initialized) {
            Log.w(TAG, "DownloadManager not initialized")
            return
        }
        if (adamId.isBlank() || adamId == "0") {
            Log.w(TAG, "enqueue skipped: invalid adamId=$adamId")
            return
        }

        scope.launch {
            val existing = dao?.getItem(adamId)
            if (existing != null && existing.status == "COMPLETED") {
                Log.i(TAG, "Skipping already downloaded adamId=$adamId")
                return@launch
            }
            if (existing != null && (existing.status == "DOWNLOADING" || existing.status == "QUEUED")) {
                if (hlsUrl.isNotBlank() && existing.hlsUrl.isBlank()) {
                    dao?.update(existing.copy(hlsUrl = hlsUrl, title = title.ifBlank { existing.title }))
                    // Do NOT auto-start here — Auto Download OFF must stay off.
                    // Explicit starts go through forceStart / FORCE_START broadcast.
                    Log.i(TAG, "Filled HLS on existing queue adamId=$adamId (no auto-start)")
                }
                Log.i(TAG, "Already queued/downloading adamId=$adamId status=${existing.status}")
                return@launch
            }

            val item = DownloadQueueItem(
                adamId = adamId,
                title = title.ifBlank { existing?.title.orEmpty() },
                artist = artist.ifBlank { existing?.artist.orEmpty() },
                status = "QUEUED",
                hlsUrl = hlsUrl.ifBlank { existing?.hlsUrl.orEmpty() },
            )
            dao?.insert(item)
            Log.i(TAG, "Enqueued download adamId=$adamId title=${item.title} executor=$executorMode")

            // Also persist to shared cross-process queue (visible to UI).
            runCatching {
                SharedQueueStore.upsertSync(
                    SharedQueueStore.QueueEntry(
                        adamId = adamId,
                        title = title.ifBlank { item.title },
                        artist = artist,
                        status = "QUEUED",
                        hlsUrl = hlsUrl.ifBlank { item.hlsUrl },
                    )
                )
            }

            // Never auto-start from enqueue — Auto Download uses poller / provideHlsUrl(auto=true).
            // Manual path: HostDownloadCommandReceiver → forceStart(adamId, hlsUrl).
            if (item.hlsUrl.isBlank()) {
                Log.i(TAG, "Queued without HLS URL adamId=$adamId; waiting for provideHlsUrl")
            } else {
                Log.i(TAG, "Queued adamId=$adamId executor=$executorMode (await forceStart or autoDl)")
            }
        }
    }

    private fun startQueuePoller() {
        if (pollerJob?.isActive == true) return
        pollerJob = scope.launch {
            while (isActive) {
                runCatching { pollQueuedWork() }
                delay(QUEUE_POLL_INTERVAL_MS)
            }
        }
        Log.i(TAG, "Queue poller started intervalMs=$QUEUE_POLL_INTERVAL_MS")
    }

    /**
     * Poller:
     * - Auto Download ON → start every QUEUED + URL item.
     * - Auto Download OFF → do nothing here; user must send FORCE_START / forceStart().
     * Manual "Download now" is handled by HostDownloadCommandReceiver → forceStart().
     */
    private suspend fun pollQueuedWork() {
        if (!canExecuteDownloads()) return
        if (!DownloadSettings.isAutoDownloadEnabled()) {
            return
        }
        val items = dao?.getAllItemsOnce().orEmpty()
        for (item in items) {
            if (item.status == "QUEUED" && item.hlsUrl.isNotBlank()) {
                Log.i(TAG, "poller auto-start adamId=${item.adamId}")
                startDownload(item.adamId)
            }
        }
    }

    private fun startDownload(adamId: String) {
        if (!canExecuteDownloads()) {
            Log.w(TAG, "startDownload ignored outside executor process adamId=$adamId")
            return
        }
        scope.launch {
            activeJobsMutex.withLock {
                if (activeJobs[adamId]?.isActive == true) {
                    Log.i(TAG, "Already downloading adamId=$adamId")
                    return@launch
                }
            }

            val job = scope.launch {
                downloadSemaphore.withPermit {
                    downloadTrack(adamId)
                }
            }

            activeJobsMutex.withLock {
                activeJobs[adamId] = job
            }

            job.invokeOnCompletion {
                scope.launch {
                    activeJobsMutex.withLock {
                        activeJobs.remove(adamId)
                    }
                }
            }
        }
    }

    fun cancel(adamId: String) {
        scope.launch {
            activeJobsMutex.withLock {
                activeJobs[adamId]?.cancel()
                activeJobs.remove(adamId)
            }
            dao?.getItem(adamId)?.let { item ->
                if (item.status == "DOWNLOADING") {
                    dao?.update(item.copy(status = "CANCELED"))
                }
            }
            updateProgress(adamId, "", 0, "CANCELED")
        }
    }

    fun retry(adamId: String) {
        scope.launch {
            dao?.getItem(adamId)?.let { item ->
                dao?.update(item.copy(status = "QUEUED", progress = 0, errorMessage = "", completedSegments = 0))
                // Executor process starts immediately; UI process relies on poller / next Music session.
                if (item.hlsUrl.isNotBlank() && canExecuteDownloads()) {
                    startDownload(adamId)
                }
            }
        }
    }

    fun clearCompleted() {
        scope.launch {
            dao?.deleteCompleted()
        }
    }

    private suspend fun downloadTrack(adamId: String) {
        val ctx = applicationContext ?: return
        updateProgress(adamId, "", 0, "DOWNLOADING")

        val item = dao?.getItem(adamId) ?: return
        // Pass host Context so OutputPaths can use getExternalFilesDir / filesDir / cacheDir
        // ( /sdcard/Music is EPERM under Apple Music process on Android 11+ ).
        val m4aWriter = M4aWriter(ctx)
        Log.i(TAG, "download output dir candidates via OutputPaths; primary probe=${OutputPaths.musicDir(ctx).absolutePath}")
        dao?.update(item.copy(status = "DOWNLOADING"))
        runCatching {
            SharedQueueStore.upsertSync(
                SharedQueueStore.QueueEntry(
                    adamId = adamId,
                    title = item.title,
                    artist = item.artist,
                    status = "DOWNLOADING",
                    progress = 0,
                    hlsUrl = item.hlsUrl,
                )
            )
        }

        try {
            val m3u8Url = item.hlsUrl
            if (m3u8Url.isBlank()) {
                failItem(item, adamId, "HLS URL not available")
                return
            }

            Log.i(TAG, "Fetching M3U8 for adamId=$adamId")
            var playlist = hlsDownloader.fetchAndParseM3U8(m3u8Url)
            var resolveTries = 0
            while (playlist.isMaster && playlist.variantUrl != null && resolveTries < 3) {
                playlist = hlsDownloader.fetchAndParseM3U8(playlist.variantUrl!!)
                resolveTries++
            }

            val segments = playlist.segments
            if (segments.isEmpty()) {
                throw Exception("No segments found in HLS playlist")
            }
            dao?.update(item.copy(totalSegments = segments.size))
            Log.i(TAG, "Found ${segments.size} segments for adamId=$adamId map=${playlist.mapUri}")

            val keyUri = playlist.keyUri?.takeIf { it.isNotBlank() } ?: m3u8Url
            if (!AppleMusicNativeBridge.prepareSession(adamId, keyUri)) {
                throw Exception(
                    "Failed to prepare decrypt session adamId=$adamId status=${AppleMusicNativeBridge.resolverStatus()}",
                )
            }

            // Download fMP4 init segment (#EXT-X-MAP) for ALAC magic cookie / sample rate.
            // Path A (alac-download parity): cookie from init, media samples via trun sizes only.
            var initTrackInfo: TrackInfo? = null
            var initTimescale = 0L
            var initDefaultSampleDuration = 0L
            var frameLength = 4096
            val mapUri = playlist.mapUri
            if (!mapUri.isNullOrBlank()) {
                runCatching {
                    val initBytes = hlsDownloader.downloadSegment(mapUri)
                    Log.i(TAG, "init segment downloaded bytes=${initBytes.size} uri=$mapUri")
                    // Prefer box walk (works without samples); MediaExtractor as secondary.
                    val fromBoxes = Fmp4SampleSplitter.split(initBytes, 0f)
                    initTrackInfo = fromBoxes.trackInfo
                        ?: alacExtractor.extractSamplesFromFmp4(initBytes).trackInfo
                        ?: parseTrackInfoFromInitSegment(initBytes)
                    initTimescale = initTrackInfo?.sampleRate?.toLong()?.takeIf { it > 0 } ?: 44100L
                    frameLength = IsoBmffAlacWriter.frameLengthFromCookie(
                        IsoBmffAlacWriter.normalizeAlacCookie(
                            initTrackInfo?.csd0 ?: ByteArray(0),
                            initTrackInfo?.sampleRate ?: 44100,
                            initTrackInfo?.channelCount ?: 2,
                        ),
                    )
                    Log.i(
                        TAG,
                        "init trackInfo mime=${initTrackInfo?.mime} rate=${initTrackInfo?.sampleRate} " +
                            "ch=${initTrackInfo?.channelCount} csd0=${initTrackInfo?.csd0?.size} " +
                            "frameLen=$frameLength ts=$initTimescale",
                    )
                }.onFailure { e ->
                    Log.w(TAG, "init segment parse failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "No #EXT-X-MAP in playlist; ALAC cookie will use defaults if needed")
            }

            // Always sample-level decrypt (alac-download / Frida socket contract).
            // Setting flag kept for logging only — we never whole-segment-decrypt containers.
            val preferSampleLevel = DownloadSettings.isPreferSampleLevelDecrypt()
            Log.i(TAG, "decrypt mode=sample-level (forced, setting prefer=$preferSampleLevel)")
            var presentationCursorUs = 0L
            var sampleCount = 0
            var writerReady = false
            val title = item.title.ifBlank { "Track_$adamId" }

            // Stream each segment → split trun → decrypt each sample → write immediately.
            // NEVER accumulate all samples in heap (was OOM at 512MB).
            for ((index, segment) in segments.withIndex()) {
                if (!isJobActive(adamId)) {
                    dao?.update(item.copy(status = "CANCELED"))
                    return
                }

                var segmentSuccess = false
                var segmentRetries = 0
                while (!segmentSuccess && segmentRetries < MAX_RETRIES) {
                    try {
                        val encryptedSegment = hlsDownloader.downloadSegment(segment.url)
                        Log.i(TAG, "segment $index/${segments.size} encrypted=${encryptedSegment.size}")

                        val produced = decryptSegmentToSamples(
                            encryptedSegment = encryptedSegment,
                            segmentDurationSec = segment.duration,
                            presentationCursorUs = presentationCursorUs,
                            defaultTimescale = initTimescale,
                            defaultSampleDurationTicks = initDefaultSampleDuration,
                            frameLengthSamples = frameLength,
                        )

                        if (!writerReady) {
                            val trackInfo = mergeTrackInfo(initTrackInfo, produced.trackInfo)
                            Log.i(
                                TAG,
                                "writer trackInfo mime=${trackInfo.mime} rate=${trackInfo.sampleRate} " +
                                    "ch=${trackInfo.channelCount} csd0=${trackInfo.csd0?.size}",
                            )
                            if (!m4aWriter.init(title, trackInfo)) {
                                val reason = m4aWriter.getInitError() ?: "audio writer unavailable"
                                throw Exception("Failed to create output file. $reason")
                            }
                            writerReady = true
                        }

                        for (sample in produced.samples) {
                            m4aWriter.writeSample(sample)
                            sampleCount++
                        }
                        if (produced.samples.isNotEmpty()) {
                            val last = produced.samples.last()
                            presentationCursorUs =
                                last.presentationTimeUs + last.durationUs.coerceAtLeast(0L)
                        }
                        segmentSuccess = true
                    } catch (e: Exception) {
                        segmentRetries++
                        Log.w(TAG, "Segment $index failed $segmentRetries/$MAX_RETRIES: ${e.message}")
                        if (segmentRetries >= MAX_RETRIES) {
                            throw Exception("Segment $index failed after $MAX_RETRIES retries: ${e.message}")
                        }
                    }
                }

                val progress = ((index + 1) * 100) / segments.size
                dao?.update(item.copy(progress = progress, completedSegments = index + 1))
                runCatching {
                    SharedQueueStore.upsertSync(
                        SharedQueueStore.QueueEntry(
                            adamId = adamId,
                            title = item.title,
                            status = "DOWNLOADING",
                            progress = progress,
                            totalSegments = segments.size,
                            completedSegments = index + 1,
                            hlsUrl = item.hlsUrl,
                        )
                    )
                }
                updateProgress(adamId, item.title, progress, "DOWNLOADING")
                // Hint GC between segments to keep heap free for next fMP4.
                if (index % 4 == 3) {
                    System.gc()
                }
            }

            if (!writerReady || sampleCount == 0) {
                throw Exception("No audio samples produced after decrypt")
            }

            val outputPath = m4aWriter.finish()
                ?: throw Exception("Failed to finalize output file")

            Log.i(TAG, "Download complete adamId=$adamId path=$outputPath samples=$sampleCount")
            dao?.update(
                item.copy(
                    status = "COMPLETED",
                    progress = 100,
                    filePath = outputPath,
                ),
            )
            runCatching {
                SharedQueueStore.upsertSync(
                    SharedQueueStore.QueueEntry(
                        adamId = adamId,
                        title = item.title,
                        status = "COMPLETED",
                        progress = 100,
                        filePath = outputPath,
                    ),
                )
            }
            updateProgress(adamId, item.title, 100, "COMPLETED")
            runCatching { showDownloadCompleteNotification(ctx, title, outputPath) }
        } catch (e: Exception) {
            Log.e(TAG, "Download failed adamId=$adamId: ${e.message}", e)
            dao?.update(
                item.copy(
                    status = "FAILED",
                    errorMessage = e.message ?: "Unknown error",
                ),
            )
            runCatching {
                SharedQueueStore.upsertSync(
                    SharedQueueStore.QueueEntry(
                        adamId = adamId,
                        title = item.title,
                        artist = item.artist,
                        status = "FAILED",
                        errorMessage = e.message ?: "Unknown error",
                        hlsUrl = item.hlsUrl,
                    )
                )
            }
            updateProgress(adamId, item.title, 0, "FAILED", e.message ?: "Unknown error")
            // Never let notification resource lookup crash the host process.
            runCatching {
                showDownloadFailedNotification(
                    ctx,
                    item.title.ifBlank { adamId },
                    e.message ?: "Unknown error",
                )
            }.onFailure { ne ->
                Log.w(TAG, "Failed notification suppressed: ${ne.message}")
            }
        }
    }

    /**
     * Prefer init-segment TrackInfo (has ALAC cookie); fill gaps from media segment.
     */
    private fun mergeTrackInfo(init: TrackInfo?, media: TrackInfo?): TrackInfo {
        val base = init ?: media
        if (base == null) {
            return TrackInfo(
                mime = "audio/alac",
                sampleRate = 44100,
                channelCount = 2,
                csd0 = IsoBmffAlacWriter.buildDefaultAlacCookie(44100, 2),
                durationUs = 0L,
            )
        }
        val rate = when {
            base.sampleRate > 0 -> base.sampleRate
            media != null && media.sampleRate > 0 -> media.sampleRate
            else -> 44100
        }
        val ch = when {
            base.channelCount > 0 -> base.channelCount
            media != null && media.channelCount > 0 -> media.channelCount
            else -> 2
        }
        val cookie = when {
            base.csd0 != null && base.csd0!!.isNotEmpty() -> base.csd0
            media?.csd0 != null && media.csd0!!.isNotEmpty() -> media.csd0
            else -> IsoBmffAlacWriter.buildDefaultAlacCookie(rate, ch)
        }
        return TrackInfo(
            mime = base.mime.ifBlank { media?.mime ?: "audio/alac" }.ifBlank { "audio/alac" },
            sampleRate = rate,
            channelCount = ch,
            csd0 = cookie,
            durationUs = base.durationUs.takeIf { it > 0 } ?: media?.durationUs ?: 0L,
            bitrate = base.bitrate.takeIf { it > 0 } ?: media?.bitrate ?: 0,
        )
    }

    /** Parse moov/stsd from raw init fMP4 bytes when MediaExtractor has no samples. */
    private fun parseTrackInfoFromInitSegment(initBytes: ByteArray): TrackInfo? {
        // AlacFrameExtractor's extractFromFileFormat walks moov boxes.
        val result = alacExtractor.extractSamplesFromFmp4(initBytes)
        if (result.trackInfo != null) return result.trackInfo
        // Fallback: scan for 'alac' fourcc and surrounding AudioSampleEntry fields.
        return scanAlacFromBytes(initBytes)
    }

    private fun scanAlacFromBytes(data: ByteArray): TrackInfo? {
        // Find "alac" fourcc that is likely a SampleEntry or config atom.
        val needle = byteArrayOf('a'.code.toByte(), 'l'.code.toByte(), 'a'.code.toByte(), 'c'.code.toByte())
        var i = 0
        var sampleRate = 44100
        var channels = 2
        var cookie: ByteArray? = null
        while (i + 8 < data.size) {
            if (data[i] == needle[0] && data[i + 1] == needle[1] &&
                data[i + 2] == needle[2] && data[i + 3] == needle[3]
            ) {
                // Possible AudioSampleEntry: size is 4 bytes before type at i-4
                // channels@+24, sampleRate 16.16@+32; nested cookie often after +36
                val entryStart = i - 4
                if (entryStart >= 0 && entryStart + 36 <= data.size) {
                    val ch = AlacFrameExtractor.readUint16(data, entryStart + 24)
                    val rateFixed = AlacFrameExtractor.readUint32(data, entryStart + 32)
                    if (ch in 1..8) channels = ch
                    val rate = (rateFixed shr 16).toInt()
                    if (rate in 8000..384000) sampleRate = rate
                }
                // Nested 'alac' atom: size(4)+type(4)+payload — payload starts at i+4 when type is at i
                // Prefer full 28-byte cookie (version/flags + ALACSpecificConfig)
                if (i + 4 + 28 <= data.size) {
                    cookie = data.copyOfRange(i + 4, i + 4 + 28)
                }
            }
            i++
        }
        if (cookie == null && sampleRate == 44100 && channels == 2) {
            // still return defaults so writer can proceed
            return TrackInfo(
                mime = "audio/alac",
                sampleRate = sampleRate,
                channelCount = channels,
                csd0 = IsoBmffAlacWriter.buildDefaultAlacCookie(sampleRate, channels),
                durationUs = 0L,
            )
        }
        return TrackInfo(
            mime = "audio/alac",
            sampleRate = sampleRate,
            channelCount = channels,
            csd0 = cookie ?: IsoBmffAlacWriter.buildDefaultAlacCookie(sampleRate, channels),
            durationUs = 0L,
        )
    }

    private data class SegmentDecryptResult(
        val samples: List<AudioSample>,
        val trackInfo: TrackInfo?,
    )

    /**
     * Path A — alac-download / Frida parity:
     * 1) Parse moof/trun sample sizes from encrypted fMP4 (never decrypt the container)
     * 2) decryptSample() once per sample payload (batched JNI)
     * 3) emit bare ALAC packets only
     *
     * Whole-segment decrypt is intentionally NOT used: FairPlay encrypts sample payloads,
     * and decrypting moof+mdat as one blob produces unplayable garbage.
     */
    private fun decryptSegmentToSamples(
        encryptedSegment: ByteArray,
        segmentDurationSec: Float,
        presentationCursorUs: Long,
        defaultTimescale: Long,
        defaultSampleDurationTicks: Long,
        frameLengthSamples: Int,
    ): SegmentDecryptResult {
        val split = Fmp4SampleSplitter.split(
            fmp4Data = encryptedSegment,
            segmentDurationSec = segmentDurationSec,
            defaultTimescale = defaultTimescale,
            defaultSampleDurationTicks = defaultSampleDurationTicks,
            frameLengthSamples = frameLengthSamples,
        )
        Log.i(
            TAG,
            "split diag fromTrun=${split.fromTrun} count=${split.samples.size} " +
                "mdat=${split.mdatSize} ${split.diagnostics}",
        )
        if (split.samples.isEmpty()) {
            throw Exception("fMP4 split produced 0 samples (${split.diagnostics})")
        }

        // Even single-sample (whole mdat) is OK if it is pure sample payload without boxes.
        val encryptedPayloads = split.samples.map { it.data }
        // Reject if the "sample" still looks like a top-level box (wrong split).
        val first = encryptedPayloads[0]
        if (IsoBmffAlacWriter.looksLikeIsoBmff(first) && first.size > 16) {
            throw Exception(
                "split sample still looks like ISOBMFF (fromTrun=${split.fromTrun} " +
                    "size=${first.size}). ${split.diagnostics}",
            )
        }

        val decryptedSamples = decryptSampleList(encryptedPayloads)
            ?: throw Exception("sample-level decrypt failed (null/empty batch)")
        if (decryptedSamples.size != encryptedPayloads.size) {
            throw Exception(
                "decrypt count mismatch in=${encryptedPayloads.size} out=${decryptedSamples.size}",
            )
        }

        var pts = presentationCursorUs
        val audioSamples = ArrayList<AudioSample>(decryptedSamples.size)
        var skippedIso = 0
        for (i in decryptedSamples.indices) {
            val plain = decryptedSamples[i]
            if (plain.isEmpty()) {
                throw Exception("Empty decrypt result for sample index=$i")
            }
            if (IsoBmffAlacWriter.looksLikeIsoBmff(plain)) {
                skippedIso++
                if (skippedIso <= 2) {
                    Log.w(
                        TAG,
                        "decrypt[$i] still ISOBMFF size=${plain.size} " +
                            "head=${plain.take(8).joinToString("") { "%02x".format(it) }}",
                    )
                }
                continue
            }
            val duration = split.samples[i].durationUs.let { d ->
                if (d > 0) d else {
                    val total = (segmentDurationSec * 1_000_000f).toLong()
                    if (split.samples.isNotEmpty()) total / split.samples.size else 0L
                }
            }
            audioSamples.add(
                AudioSample(
                    data = ByteBuffer.wrap(plain),
                    size = plain.size,
                    presentationTimeUs = pts,
                    durationUs = duration,
                    isKeyFrame = split.samples[i].isKeyFrame,
                ),
            )
            pts += duration
        }
        if (audioSamples.isEmpty()) {
            throw Exception(
                "all samples skipped after decrypt (isoBmff=$skippedIso total=${decryptedSamples.size})",
            )
        }
        if (audioSamples.size == 1 && audioSamples[0].size > 256 * 1024) {
            Log.w(
                TAG,
                "only 1 large sample size=${audioSamples[0].size} — trun may be missing; " +
                    "playback may fail. ${split.diagnostics}",
            )
        }
        Log.i(
            TAG,
            "sample-level decrypt ok count=${audioSamples.size} skippedIso=$skippedIso " +
                "fromTrun=${split.fromTrun} firstSize=${audioSamples[0].size} " +
                "firstHead=${ByteArray(minOf(8, audioSamples[0].size)).also { h ->
                    audioSamples[0].data.duplicate().apply { position(0) }.get(h)
                }.joinToString("") { "%02x".format(it) }}",
        )
        return SegmentDecryptResult(audioSamples, split.trackInfo)
    }

    private fun decryptSampleList(encryptedSamples: List<ByteArray>): List<ByteArray>? {
        if (encryptedSamples.isEmpty()) return emptyList()

        // Prefer batch JNI to reduce crossing cost; fall back per-sample.
        val out = ArrayList<ByteArray>(encryptedSamples.size)
        var index = 0
        while (index < encryptedSamples.size) {
            val end = (index + BATCH_DECRYPT_SIZE).coerceAtMost(encryptedSamples.size)
            val batch = encryptedSamples.subList(index, end).toTypedArray()
            val batchResult = AppleMusicNativeBridge.decryptSamples(batch)
            if (batchResult != null && batchResult.size == batch.size && batchResult.all { it.isNotEmpty() }) {
                out.addAll(batchResult)
            } else {
                for (sample in batch) {
                    val one = AppleMusicNativeBridge.decryptSample(sample)
                    if (one == null || one.isEmpty()) {
                        return null
                    }
                    out.add(one)
                }
            }
            index = end
        }
        return out
    }

    private suspend fun failItem(item: DownloadQueueItem, adamId: String, message: String) {
        dao?.update(item.copy(status = "FAILED", errorMessage = message))
        updateProgress(adamId, item.title, 0, "FAILED", message)
    }

    private suspend fun isJobActive(adamId: String): Boolean {
        return activeJobsMutex.withLock {
            activeJobs[adamId]?.isActive == true
        }
    }

    private fun updateProgress(
        adamId: String,
        title: String,
        progress: Int,
        status: String,
        errorMessage: String = "",
    ) {
        _currentDownloads.value = _currentDownloads.value + (adamId to DownloadProgress(
            adamId = adamId,
            title = title,
            progress = progress,
            status = status,
            errorMessage = errorMessage,
        ))
    }

    /**
     * Called from the requestAsset hook.
     * Auto-download is gated by DownloadSettings.isAutoDownloadEnabled().
     * Manual enqueue always works regardless of the toggle.
     */
    /**
     * Record HLS URL from hook. Starts download ONLY when [autoEnqueue] is true
     * (i.e. Auto Download setting ON). Never starts just because URL arrived.
     */
    fun provideHlsUrl(adamId: String, hlsUrl: String, autoEnqueue: Boolean = false) {
        if (!initialized || adamId.isBlank() || hlsUrl.isBlank()) {
            return
        }
        scope.launch {
            val existing = dao?.getItem(adamId)
            if (existing != null) {
                if (existing.hlsUrl != hlsUrl) {
                    dao?.update(existing.copy(hlsUrl = hlsUrl))
                }
                runCatching {
                    SharedQueueStore.upsertSync(
                        SharedQueueStore.QueueEntry(
                            adamId = adamId,
                            title = existing.title,
                            status = existing.status,
                            hlsUrl = hlsUrl,
                        )
                    )
                }
                // Only auto-start when explicitly allowed AND status is idle queued.
                // Do NOT start merely because URL was missing before.
                val shouldStart = autoEnqueue &&
                    existing.status == "QUEUED" &&
                    canExecuteDownloads()
                if (shouldStart) {
                    startDownload(adamId)
                    Log.i(TAG, "Auto-download started (existing) adamId=$adamId")
                } else {
                    Log.i(
                        TAG,
                        "URL recorded no-start adamId=$adamId status=${existing.status} auto=$autoEnqueue",
                    )
                }
                return@launch
            }

            dao?.insert(
                DownloadQueueItem(
                    adamId = adamId,
                    title = "Track $adamId",
                    status = "QUEUED",
                    hlsUrl = hlsUrl,
                ),
            )
            runCatching {
                SharedQueueStore.upsertSync(
                    SharedQueueStore.QueueEntry(
                        adamId = adamId,
                        title = "Track $adamId",
                        hlsUrl = hlsUrl,
                        status = "QUEUED",
                    )
                )
            }
            if (autoEnqueue && canExecuteDownloads()) {
                startDownload(adamId)
                Log.i(TAG, "Auto-download started adamId=$adamId")
            } else {
                Log.i(TAG, "Recorded track without auto-start adamId=$adamId auto=$autoEnqueue")
            }
        }
    }

    /**
     * Explicit user-triggered start (Manual Add / Retry / Download button / FORCE_START broadcast).
     * Must run in Apple Music executor process. UI process should use
     * [HostDownloadCommandReceiver.sendForceStart] instead.
     *
     * @param hlsUrlOverride if non-blank, write/use this URL immediately (avoids race with provideHlsUrl)
     * @param titleOverride optional title for new rows
     */
    fun forceStart(
        adamId: String,
        hlsUrlOverride: String = "",
        titleOverride: String = "",
    ) {
        if (!initialized || adamId.isBlank()) return
        scope.launch {
            val overrideUrl = hlsUrlOverride.trim()
            val overrideTitle = titleOverride.trim()
            var item = dao?.getItem(adamId)

            // Prefer override URL from FORCE_START broadcast extras (cross-process, no file race).
            if (overrideUrl.isNotBlank()) {
                if (item == null) {
                    item = DownloadQueueItem(
                        adamId = adamId,
                        title = overrideTitle.ifBlank { "Track $adamId" },
                        status = "QUEUED",
                        hlsUrl = overrideUrl,
                    )
                    dao?.insert(item!!)
                    Log.i(TAG, "forceStart: created item from override URL adamId=$adamId")
                } else if (item!!.hlsUrl != overrideUrl || item!!.status == "FAILED" || item!!.status == "CANCELED") {
                    item = item!!.copy(
                        hlsUrl = overrideUrl,
                        title = overrideTitle.ifBlank { item!!.title },
                        status = "QUEUED",
                        progress = 0,
                        errorMessage = "",
                    )
                    dao?.update(item!!)
                }
            }

            // Fallback: host-local Room only (Music process SharedQueue is NOT the UI file).
            item = dao?.getItem(adamId)
            if ((item == null || item!!.hlsUrl.isBlank())) {
                val shared = runCatching {
                    SharedQueueStore.loadSync().firstOrNull { it.adamId == adamId }
                }.getOrNull()
                if (shared != null && shared.hlsUrl.isNotBlank()) {
                    if (item == null) {
                        item = DownloadQueueItem(
                            adamId = adamId,
                            title = shared.title.ifBlank { "Track $adamId" },
                            artist = shared.artist,
                            status = "QUEUED",
                            hlsUrl = shared.hlsUrl,
                        )
                        dao?.insert(item!!)
                    } else {
                        item = item!!.copy(hlsUrl = shared.hlsUrl)
                        dao?.update(item!!)
                    }
                    Log.i(TAG, "forceStart: filled HLS from host local shared map adamId=$adamId")
                }
            }

            item = dao?.getItem(adamId)
            if (item == null) {
                Log.w(TAG, "forceStart: no local item adamId=$adamId")
                return@launch
            }
            if (item.hlsUrl.isBlank()) {
                Log.w(TAG, "forceStart: no HLS URL yet adamId=$adamId — play track once in Apple Music")
                runCatching {
                    SharedQueueStore.upsertSync(
                        SharedQueueStore.QueueEntry(
                            adamId = adamId,
                            title = item.title,
                            status = "FAILED",
                            errorMessage = "No HLS URL — play this track in Apple Music first",
                            hlsUrl = "",
                        ),
                    )
                }
                return@launch
            }
            if (item.status == "COMPLETED" && overrideUrl.isBlank()) {
                Log.i(TAG, "forceStart: already completed adamId=$adamId path=${item.filePath}")
                return@launch
            }
            dao?.update(
                item.copy(
                    status = "QUEUED",
                    progress = 0,
                    errorMessage = "",
                    completedSegments = 0,
                ),
            )
            runCatching {
                SharedQueueStore.upsertSync(
                    SharedQueueStore.QueueEntry(
                        adamId = adamId,
                        title = item.title,
                        status = "QUEUED",
                        progress = 0,
                        hlsUrl = item.hlsUrl,
                    ),
                )
            }
            if (canExecuteDownloads()) {
                startDownload(adamId)
                Log.i(TAG, "forceStart download adamId=$adamId urlLen=${item.hlsUrl.length}")
            } else {
                Log.w(
                    TAG,
                    "forceStart: executor not ready adamId=$adamId " +
                        "executorMode=$executorMode native=${AppleMusicNativeBridge.isAvailable()}",
                )
            }
        }
    }

    fun destroy() {
        pollerJob?.cancel()
        pollerJob = null
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        scope.cancel()
    }
}
