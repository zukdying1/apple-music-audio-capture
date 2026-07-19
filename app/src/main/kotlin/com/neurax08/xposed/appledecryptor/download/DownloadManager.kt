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
        DownloadSettings.ensureLoaded()
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
                    if (existing.status == "QUEUED") {
                        startDownload(adamId)
                    }
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

            // Only the Apple Music process can actually decrypt/download.
            // UI process just writes the shared queue; the executor poller picks it up.
            if (item.hlsUrl.isNotBlank() && canExecuteDownloads()) {
                startDownload(adamId)
            } else if (item.hlsUrl.isBlank()) {
                Log.i(TAG, "Queued without HLS URL adamId=$adamId; waiting for provideHlsUrl")
            } else {
                Log.i(TAG, "Queued for executor process adamId=$adamId")
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

    private suspend fun pollQueuedWork() {
        if (!canExecuteDownloads()) return
        val items = dao?.getAllItemsOnce().orEmpty()
        for (item in items) {
            if (item.status == "QUEUED" && item.hlsUrl.isNotBlank()) {
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
            // Media fragments rarely contain moov; without cookie MediaMuxer often rejects the track.
            var initTrackInfo: TrackInfo? = null
            val mapUri = playlist.mapUri
            if (!mapUri.isNullOrBlank()) {
                runCatching {
                    val initBytes = hlsDownloader.downloadSegment(mapUri)
                    Log.i(TAG, "init segment downloaded bytes=${initBytes.size} uri=$mapUri")
                    initTrackInfo = alacExtractor.extractSamplesFromFmp4(initBytes).trackInfo
                        ?: parseTrackInfoFromInitSegment(initBytes)
                    Log.i(
                        TAG,
                        "init trackInfo mime=${initTrackInfo?.mime} rate=${initTrackInfo?.sampleRate} " +
                            "ch=${initTrackInfo?.channelCount} csd0=${initTrackInfo?.csd0?.size}",
                    )
                }.onFailure { e ->
                    Log.w(TAG, "init segment parse failed: ${e.message}")
                }
            } else {
                Log.w(TAG, "No #EXT-X-MAP in playlist; ALAC cookie will use defaults if needed")
            }

            val preferSampleLevel = DownloadSettings.isPreferSampleLevelDecrypt()
            var presentationCursorUs = 0L
            var sampleCount = 0
            var writerReady = false
            val title = item.title.ifBlank { "Track_$adamId" }

            // Stream each segment → decrypt → write immediately.
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
                            preferSampleLevel = preferSampleLevel,
                            presentationCursorUs = presentationCursorUs,
                        )

                        if (!writerReady) {
                            val trackInfo = mergeTrackInfo(initTrackInfo, produced.trackInfo)
                            Log.i(
                                TAG,
                                "writer trackInfo mime=${trackInfo.mime} rate=${trackInfo.sampleRate} " +
                                    "ch=${trackInfo.channelCount} csd0=${trackInfo.csd0?.size}",
                            )
                            if (!m4aWriter.init(title, trackInfo)) {
                                val reason = m4aWriter.getInitError() ?: "ALAC M4A writers unavailable"
                                throw Exception(
                                    "Failed to create M4A (ALAC). WAV fallback disabled for compressed audio. $reason",
                                )
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
                        // Drop references ASAP for GC.
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
     * Prefer sample-level decrypt (matches original socket path):
     * 1) Parse moof/trun sample sizes from encrypted fMP4
     * 2) decrypt each sample (batched JNI when possible)
     * 3) if sample-level fails or produces empty, fall back to whole-segment decrypt + extractor
     */
    private fun decryptSegmentToSamples(
        encryptedSegment: ByteArray,
        segmentDurationSec: Float,
        preferSampleLevel: Boolean,
        presentationCursorUs: Long,
    ): SegmentDecryptResult {
        if (preferSampleLevel) {
            val split = Fmp4SampleSplitter.split(encryptedSegment, segmentDurationSec)
            if (split.samples.size > 1 ||
                (split.samples.size == 1 && split.samples[0].data.size < encryptedSegment.size)
            ) {
                val decryptedSamples = decryptSampleList(split.samples.map { it.data })
                if (decryptedSamples != null && decryptedSamples.size == split.samples.size) {
                    var pts = presentationCursorUs
                    val audioSamples = ArrayList<AudioSample>(split.samples.size)
                    for (i in split.samples.indices) {
                        val plain = decryptedSamples[i]
                        if (plain.isEmpty()) {
                            throw Exception("Empty decrypt result for sample index=$i")
                        }
                        // FairPlay decrypt must yield bare ALAC packets, not fMP4 boxes.
                        if (IsoBmffAlacWriter.looksLikeIsoBmff(plain)) {
                            Log.w(TAG, "sample-level decrypt[$i] still looks like ISOBMFF size=${plain.size}")
                            // Don't add container junk into muxer
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
                    if (audioSamples.isNotEmpty()) {
                        Log.i(TAG, "sample-level decrypt ok count=${audioSamples.size}")
                        return SegmentDecryptResult(audioSamples, split.trackInfo)
                    }
                }
                Log.w(TAG, "sample-level decrypt failed/mismatch; falling back to segment decrypt")
            }
        }

        // Fallback: decrypt whole segment then extract frames (legacy path).
        // FairPlay often encrypts only mdat sample payloads; whole-segment decrypt may
        // return a full fMP4 with plaintext mdat — never write that container as one ALAC sample.
        val decrypted = AppleMusicNativeBridge.decryptSample(encryptedSegment)
            ?: throw Exception("Segment decrypt returned null")
        if (decrypted.isEmpty()) {
            throw Exception("Segment decrypt returned empty")
        }

        val extracted = alacExtractor.extractSamplesFromFmp4(decrypted)
        if (extracted.success && extracted.samples.isNotEmpty()) {
            var pts = presentationCursorUs
            val remapped = extracted.samples.mapNotNull { sample ->
                // Drop accidental ISOBMFF boxes
                val head = ByteArray(minOf(8, sample.size))
                sample.data.duplicate().apply { position(0) }.get(head)
                if (IsoBmffAlacWriter.looksLikeIsoBmff(head) && sample.size > 16) {
                    Log.w(TAG, "drop extracted sample that still looks like ISOBMFF size=${sample.size}")
                    return@mapNotNull null
                }
                val duration = if (sample.durationUs > 0) {
                    sample.durationUs
                } else {
                    (segmentDurationSec * 1_000_000f).toLong() / extracted.samples.size.coerceAtLeast(1)
                }
                val out = AudioSample(
                    data = sample.data.duplicate(),
                    size = sample.size,
                    presentationTimeUs = if (sample.presentationTimeUs > 0) sample.presentationTimeUs else pts,
                    durationUs = duration,
                    isKeyFrame = sample.isKeyFrame,
                )
                pts = out.presentationTimeUs + duration
                out
            }
            if (remapped.isNotEmpty()) {
                return SegmentDecryptResult(remapped, extracted.trackInfo)
            }
        }

        // Last resort: only if decrypted payload is NOT an fMP4 container (bare ALAC frame).
        if (IsoBmffAlacWriter.looksLikeIsoBmff(decrypted)) {
            // Try splitting plaintext fMP4 via trun sizes (mdat is already decrypted in place).
            val splitPlain = Fmp4SampleSplitter.split(decrypted, segmentDurationSec)
            if (splitPlain.samples.isNotEmpty() &&
                !IsoBmffAlacWriter.looksLikeIsoBmff(splitPlain.samples[0].data)
            ) {
                var pts = presentationCursorUs
                val audioSamples = splitPlain.samples.map { s ->
                    val duration = if (s.durationUs > 0) s.durationUs else {
                        val total = (segmentDurationSec * 1_000_000f).toLong()
                        if (splitPlain.samples.isNotEmpty()) total / splitPlain.samples.size else 0L
                    }
                    val out = AudioSample(
                        data = ByteBuffer.wrap(s.data),
                        size = s.data.size,
                        presentationTimeUs = pts,
                        durationUs = duration,
                        isKeyFrame = s.isKeyFrame,
                    )
                    pts += duration
                    out
                }
                Log.i(TAG, "plaintext fMP4 split ok count=${audioSamples.size}")
                return SegmentDecryptResult(audioSamples, splitPlain.trackInfo ?: extracted.trackInfo)
            }
            throw Exception(
                "Decrypted segment is still fMP4 container and frame extraction failed " +
                    "(size=${decrypted.size}). Cannot mux as ALAC sample.",
            )
        }

        val durationUs = (segmentDurationSec * 1_000_000f).toLong()
        return SegmentDecryptResult(
            samples = listOf(
                AudioSample(
                    data = ByteBuffer.wrap(decrypted),
                    size = decrypted.size,
                    presentationTimeUs = presentationCursorUs,
                    durationUs = durationUs,
                ),
            ),
            trackInfo = extracted.trackInfo,
        )
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
    fun provideHlsUrl(adamId: String, hlsUrl: String, autoEnqueue: Boolean = true) {
        if (!initialized || adamId.isBlank() || hlsUrl.isBlank()) {
            return
        }
        scope.launch {
            val existing = dao?.getItem(adamId)
            if (existing != null) {
                val waitingForUrl = existing.hlsUrl.isBlank()
                if (existing.hlsUrl != hlsUrl) {
                    dao?.update(existing.copy(hlsUrl = hlsUrl))
                }
                // Update shared queue
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
                val shouldStart = (existing.status == "QUEUED" || existing.status == "FAILED") &&
                    (autoEnqueue || waitingForUrl)
                if (shouldStart && canExecuteDownloads()) {
                    startDownload(adamId)
                }
                return@launch
            }

            // New track from hook
            dao?.insert(
                DownloadQueueItem(
                    adamId = adamId,
                    title = "Track $adamId",
                    status = "QUEUED",
                    hlsUrl = hlsUrl,
                ),
            )
            // Shared queue
            runCatching {
                SharedQueueStore.upsertSync(
                    SharedQueueStore.QueueEntry(
                        adamId = adamId,
                        title = "Track $adamId",
                        hlsUrl = hlsUrl,
                    )
                )
            }
            if (autoEnqueue && canExecuteDownloads()) {
                startDownload(adamId)
                Log.i(TAG, "Auto-download started adamId=$adamId")
            } else {
                Log.i(TAG, "Recorded track without auto-start adamId=$adamId auto=$autoEnqueue executor=$executorMode")
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
