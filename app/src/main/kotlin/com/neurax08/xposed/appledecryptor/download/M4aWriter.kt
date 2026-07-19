package com.neurax08.xposed.appledecryptor.download

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes decrypted audio for maximum device playback compatibility.
 *
 * Priority:
 *  1) MediaCodec ALAC → PCM → WAV  (universal; MediaPlayer always plays WAV)
 *  2) Manual ISOBMFF ALAC M4A      (if decoder missing but we still want lossless)
 *  3) MediaMuxer ALAC M4A          (rare; many OEMs reject addTrack)
 *
 * No external MP4Box required.
 */
class M4aWriter(private val context: Context? = null) {
    companion object {
        private const val TAG = "AppleDecryptor"
        private const val ALAC_MIME = "audio/alac"
        private const val PCM_MIME = "audio/raw"
        const val OUTPUT_DIR = OutputPaths.LEGACY_PUBLIC
    }

    enum class OutputMode {
        ALAC_TO_WAV,      // preferred
        ALAC_M4A_MANUAL,
        ALAC_M4A_MUXER,
        PCM_WAV,
        NONE,
    }

    private var muxer: MediaMuxer? = null
    private var trackIndex: Int = -1
    private var isStarted: Boolean = false
    private var outputPath: String = ""
    private var outputMode: OutputMode = OutputMode.NONE
    private var wavOutputStream: FileOutputStream? = null
    private var wavDataSize: Int = 0
    private var wavSampleRate: Int = 44100
    private var wavChannels: Int = 2
    private var wavBits: Int = 16
    private var nextPresentationTimeUs: Long = 0L
    private var initError: String? = null
    private var manualWriter: IsoBmffAlacWriter? = null
    private var pcmDecoder: AlacPcmDecoder? = null
    private var effectiveTrackInfo: TrackInfo? = null
    private var activeOutputDir: File? = null
    private var samplesIn = 0
    private var pcmBytesOut = 0

    fun init(title: String, trackInfo: TrackInfo): Boolean {
        reset()

        val dir = OutputPaths.musicDir(context)
        activeOutputDir = dir
        if (!dir.exists() && !dir.mkdirs()) {
            initError = "Cannot create output dir: ${dir.absolutePath}"
            Log.e(TAG, initError!!)
            return false
        }

        val normalized = normalizeTrackInfo(trackInfo)
        effectiveTrackInfo = normalized
        nextPresentationTimeUs = 0L

        val mime = normalized.mime.ifBlank { ALAC_MIME }
        val isPcm = mime.equals(PCM_MIME, ignoreCase = true) ||
            mime.equals("audio/pcm", ignoreCase = true)

        Log.i(TAG, "M4aWriter init dir=${dir.absolutePath} title=$title mime=$mime")

        if (isPcm) {
            return initRawPcmWav(dir, title, normalized)
        }

        // 1) Preferred: decode ALAC → WAV (plays everywhere, no MP4Box)
        val wavPath = File(dir, OutputPaths.safeFileName(title, "wav")).absolutePath
        val decoder = AlacPcmDecoder()
        if (decoder.init(normalized)) {
            try {
                wavOutputStream = FileOutputStream(wavPath)
                // placeholder header
                wavOutputStream?.write(ByteArray(44))
                wavDataSize = 0
                wavSampleRate = decoder.getSampleRate()
                wavChannels = decoder.getChannelCount()
                wavBits = 16
                pcmDecoder = decoder
                isStarted = true
                outputMode = OutputMode.ALAC_TO_WAV
                outputPath = wavPath
                initError = null
                Log.i(TAG, "ALAC→WAV decoder writer ready: $wavPath")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "WAV open failed, will try M4A: ${e.message}")
                decoder.release()
                runCatching { File(wavPath).delete() }
            }
        } else {
            Log.w(TAG, "ALAC MediaCodec decoder unavailable: ${decoder.getError()}")
        }

        // 2) Manual ISOBMFF ALAC M4A
        val m4aPath = File(dir, OutputPaths.safeFileName(title, "m4a")).absolutePath
        val manual = IsoBmffAlacWriter(context)
        if (manual.init(m4aPath, normalized)) {
            manualWriter = manual
            isStarted = true
            outputMode = OutputMode.ALAC_M4A_MANUAL
            outputPath = m4aPath
            initError = null
            Log.i(TAG, "M4A manual ISOBMFF writer ready: $m4aPath")
            return true
        }

        // 3) MediaMuxer (usually fails for ALAC)
        if (tryInitMediaMuxer(m4aPath, normalized)) {
            return true
        }

        initError = listOfNotNull(
            decoder.getError(),
            manual.getError(),
            initError,
        ).joinToString(" | ").ifBlank { "No audio writer available" }
        isStarted = false
        outputMode = OutputMode.NONE
        Log.e(TAG, "All writers failed: $initError")
        return false
    }

    fun writeSample(sample: AudioSample) {
        if (!isStarted) return
        when (outputMode) {
            OutputMode.ALAC_TO_WAV -> writeDecodedWav(sample)
            OutputMode.ALAC_M4A_MUXER -> writeM4aSample(sample)
            OutputMode.ALAC_M4A_MANUAL -> manualWriter?.writeSample(sample)
            OutputMode.PCM_WAV -> writeRawWavSample(sample)
            OutputMode.NONE -> Unit
        }
    }

    fun finish(): String? {
        if (!isStarted) return null
        return try {
            val path = when (outputMode) {
                OutputMode.ALAC_TO_WAV -> finishDecodedWav()
                OutputMode.ALAC_M4A_MUXER -> finishM4a()
                OutputMode.ALAC_M4A_MANUAL -> {
                    val p = manualWriter?.finish()
                    isStarted = false
                    p ?: outputPath
                }
                OutputMode.PCM_WAV -> finishRawWav()
                OutputMode.NONE -> null
            }
            val ok = path?.takeIf { it.isNotBlank() && File(it).exists() && File(it).length() > 44 }
            Log.i(
                TAG,
                "writer finish mode=$outputMode path=$ok samplesIn=$samplesIn pcmBytes=$pcmBytesOut " +
                    "size=${ok?.let { File(it).length() }}",
            )
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize output file", e)
            null
        }
    }

    fun getOutputPath(): String = outputPath
    fun getOutputMode(): OutputMode = outputMode
    fun getInitError(): String? = initError
    fun isFallbackWav(): Boolean =
        outputMode == OutputMode.PCM_WAV || outputMode == OutputMode.ALAC_TO_WAV
    fun getActiveOutputDir(): String = activeOutputDir?.absolutePath.orEmpty()

    private fun initRawPcmWav(dir: File, title: String, trackInfo: TrackInfo): Boolean {
        val wavPath = File(dir, OutputPaths.safeFileName(title, "wav")).absolutePath
        return try {
            wavOutputStream = FileOutputStream(wavPath)
            wavOutputStream?.write(ByteArray(44))
            wavDataSize = 0
            wavSampleRate = trackInfo.sampleRate.coerceAtLeast(1)
            wavChannels = trackInfo.channelCount.coerceAtLeast(1)
            wavBits = 16
            isStarted = true
            outputMode = OutputMode.PCM_WAV
            outputPath = wavPath
            true
        } catch (e: Exception) {
            initError = "PCM WAV init failed: ${e.message}"
            false
        }
    }

    private fun writeDecodedWav(sample: AudioSample) {
        val decoder = pcmDecoder ?: return
        try {
            if (IsoBmffAlacWriter.looksLikeIsoBmff(
                    ByteArray(minOf(8, sample.size)).also { head ->
                        sample.data.duplicate().apply { position(0) }.get(head)
                    },
                )
            ) {
                Log.w(TAG, "skip ISOBMFF-looking sample size=${sample.size}")
                return
            }
            val packet = ByteArray(sample.size)
            sample.data.duplicate().apply {
                position(0)
                limit(sample.size.coerceAtMost(capacity()))
            }.get(packet)

            val pts = if (sample.presentationTimeUs > 0) {
                sample.presentationTimeUs
            } else {
                nextPresentationTimeUs
            }
            val duration = if (sample.durationUs > 0) sample.durationUs else 92_879L // ~4096@44.1k
            nextPresentationTimeUs = pts + duration

            val pcm = decoder.decodePacket(packet, pts)
            if (pcm.isNotEmpty()) {
                wavOutputStream?.write(pcm)
                wavDataSize += pcm.size
                pcmBytesOut += pcm.size
            }
            samplesIn++
            if (samplesIn == 1) {
                Log.i(
                    TAG,
                    "first packet size=${packet.size} pcmOut=${pcm.size} " +
                        "head=${packet.take(8).joinToString("") { "%02x".format(it) }}",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "writeDecodedWav failed: ${e.message}")
        }
    }

    private fun finishDecodedWav(): String? {
        val decoder = pcmDecoder
        try {
            val tail = decoder?.flushRemaining() ?: ByteArray(0)
            if (tail.isNotEmpty()) {
                wavOutputStream?.write(tail)
                wavDataSize += tail.size
                pcmBytesOut += tail.size
            }
        } catch (e: Exception) {
            Log.w(TAG, "decoder flush: ${e.message}")
        }
        // refresh rate/ch from decoder if changed
        decoder?.let {
            wavSampleRate = it.getSampleRate()
            wavChannels = it.getChannelCount()
        }
        decoder?.release()
        pcmDecoder = null
        return finishRawWav()
    }

    private fun normalizeTrackInfo(trackInfo: TrackInfo): TrackInfo {
        val rate = trackInfo.sampleRate.takeIf { it > 0 } ?: 44100
        val ch = trackInfo.channelCount.takeIf { it > 0 } ?: 2
        val raw = trackInfo.csd0
        val cookie = if (raw != null && raw.isNotEmpty()) {
            IsoBmffAlacWriter.normalizeAlacCookie(raw, rate, ch)
        } else {
            Log.w(TAG, "ALAC csd-0 missing; using default cookie rate=$rate ch=$ch")
            IsoBmffAlacWriter.buildDefaultAlacCookie(rate, ch)
        }
        var finalRate = rate
        var finalCh = ch
        val cookieRate = IsoBmffAlacWriter.sampleRateFromCookie(cookie)
        if (cookieRate in 8000..384000) finalRate = cookieRate
        val cookieCh = IsoBmffAlacWriter.channelsFromCookie(cookie)
        if (cookieCh in 1..8) finalCh = cookieCh
        Log.i(
            TAG,
            "normalizeTrackInfo rate=$finalRate ch=$finalCh csd0=${cookie.size} " +
                "frameLen=${IsoBmffAlacWriter.frameLengthFromCookie(cookie)} " +
                "hex=${cookie.take(12).joinToString("") { "%02x".format(it) }}",
        )
        return trackInfo.copy(
            mime = trackInfo.mime.ifBlank { ALAC_MIME },
            sampleRate = finalRate,
            channelCount = finalCh,
            csd0 = cookie,
        )
    }

    private fun tryInitMediaMuxer(m4aPath: String, trackInfo: TrackInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            initError = "API ${Build.VERSION.SDK_INT} < 30 for MediaMuxer ALAC"
            return false
        }
        return try {
            val format = MediaFormat.createAudioFormat(
                ALAC_MIME,
                trackInfo.sampleRate.coerceAtLeast(1),
                trackInfo.channelCount.coerceAtLeast(1),
            )
            trackInfo.csd0?.let { format.setByteBuffer("csd-0", ByteBuffer.wrap(it)) }
            val m = MediaMuxer(m4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val idx = m.addTrack(format)
            m.start()
            muxer = m
            trackIndex = idx
            isStarted = true
            outputMode = OutputMode.ALAC_M4A_MUXER
            outputPath = m4aPath
            initError = null
            Log.i(TAG, "MediaMuxer ALAC ok: $m4aPath")
            true
        } catch (e: Exception) {
            initError = "MediaMuxer ALAC failed: ${e.message}"
            Log.w(TAG, initError!!)
            runCatching { muxer?.release() }
            muxer = null
            runCatching { File(m4aPath).delete() }
            false
        }
    }

    private fun reset() {
        runCatching { if (isStarted && muxer != null) muxer?.stop() }
        muxer?.release()
        muxer = null
        runCatching { manualWriter?.finish() }
        manualWriter = null
        pcmDecoder?.release()
        pcmDecoder = null
        runCatching { wavOutputStream?.close() }
        wavOutputStream = null
        isStarted = false
        trackIndex = -1
        outputMode = OutputMode.NONE
        wavDataSize = 0
        nextPresentationTimeUs = 0L
        outputPath = ""
        initError = null
        samplesIn = 0
        pcmBytesOut = 0
    }

    private fun writeM4aSample(sample: AudioSample) {
        try {
            val buffer = sample.data.duplicate()
            buffer.position(0)
            buffer.limit(sample.size.coerceAtMost(buffer.capacity()))
            val pts = if (sample.presentationTimeUs > 0) sample.presentationTimeUs else nextPresentationTimeUs
            val duration = if (sample.durationUs > 0) sample.durationUs else 92_879L
            nextPresentationTimeUs = pts + duration
            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = sample.size
            info.presentationTimeUs = pts
            info.flags = if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer?.writeSampleData(trackIndex, buffer, info)
            samplesIn++
        } catch (e: Exception) {
            Log.w(TAG, "writeM4aSample failed", e)
        }
    }

    private fun finishM4a(): String? {
        muxer?.let {
            runCatching { it.stop() }
            it.release()
        }
        muxer = null
        isStarted = false
        return outputPath
    }

    private fun writeRawWavSample(sample: AudioSample) {
        try {
            val data = ByteArray(sample.size)
            sample.data.position(0)
            sample.data.get(data, 0, sample.size)
            wavOutputStream?.write(data)
            wavDataSize += sample.size
            samplesIn++
        } catch (e: Exception) {
            Log.w(TAG, "writeRawWavSample failed", e)
        }
    }

    private fun finishRawWav(): String? {
        wavOutputStream?.let { stream ->
            try {
                stream.channel.position(0)
                stream.write(createWavHeader(wavDataSize, wavSampleRate, wavChannels, wavBits))
                stream.close()
            } catch (e: Exception) {
                Log.w(TAG, "WAV finish error", e)
            }
        }
        wavOutputStream = null
        isStarted = false
        if (wavDataSize <= 0) {
            Log.e(TAG, "WAV empty dataSize=0 path=$outputPath")
            return null
        }
        return outputPath
    }

    private fun createWavHeader(
        dataSize: Int,
        sampleRate: Int,
        channels: Int,
        bits: Int,
    ): ByteArray {
        val bytesPerSample = (bits / 8).coerceAtLeast(1)
        val byteRate = sampleRate * channels * bytesPerSample
        val blockAlign = channels * bytesPerSample
        val totalSize = 36 + dataSize
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bits.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        return buffer.array()
    }
}
