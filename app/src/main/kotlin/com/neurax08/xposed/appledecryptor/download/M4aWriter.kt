package com.neurax08.xposed.appledecryptor.download

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
 * Writes decrypted audio samples.
 *
 * Preferred path: ALAC frames into MPEG-4 via MediaMuxer.
 * Fallback path: manual ISOBMFF ALAC writer ([IsoBmffAlacWriter]) when MediaMuxer
 * rejects audio/alac (common on OEM ROMs even with API 30+).
 * WAV fallback is ONLY used for raw PCM.
 */
class M4aWriter {
    companion object {
        private const val TAG = "AppleDecryptor"
        private const val ALAC_MIME = "audio/alac"
        private const val PCM_MIME = "audio/raw"
        const val OUTPUT_DIR = "/sdcard/Music/AppleDecryptor"
    }

    enum class OutputMode {
        ALAC_M4A_MUXER,
        ALAC_M4A_MANUAL,
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
    private var nextPresentationTimeUs: Long = 0L
    private var initError: String? = null
    private var manualWriter: IsoBmffAlacWriter? = null
    private var effectiveTrackInfo: TrackInfo? = null

    fun init(title: String, trackInfo: TrackInfo): Boolean {
        reset()

        val dir = File(OUTPUT_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }

        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .takeIf { it.isNotBlank() } ?: "unknown"
        val m4aPath = "$OUTPUT_DIR/$safeTitle.m4a"
        val wavPath = "$OUTPUT_DIR/$safeTitle.wav"
        nextPresentationTimeUs = 0L

        val mime = trackInfo.mime.ifBlank { ALAC_MIME }
        val isPcm = mime.equals(PCM_MIME, ignoreCase = true) ||
            mime.equals("audio/pcm", ignoreCase = true)

        // Ensure ALAC has a usable magic cookie for muxers / players.
        val normalized = normalizeTrackInfo(trackInfo)
        effectiveTrackInfo = normalized

        if (!isPcm) {
            // 1) Try MediaMuxer
            val muxerOk = tryInitMediaMuxer(m4aPath, normalized)
            if (muxerOk) return true

            // 2) Manual ISOBMFF fallback (always works if we can write the file)
            val manual = IsoBmffAlacWriter()
            if (manual.init(m4aPath, normalized)) {
                manualWriter = manual
                isStarted = true
                outputMode = OutputMode.ALAC_M4A_MANUAL
                outputPath = m4aPath
                initError = null
                Log.i(TAG, "M4A manual ISOBMFF writer initialized: $m4aPath")
                return true
            }

            initError = listOfNotNull(
                initError,
                manual.getError(),
            ).joinToString(" | ").ifBlank { "ALAC M4A writers unavailable" }
            isStarted = false
            outputMode = OutputMode.NONE
            Log.e(TAG, "All ALAC M4A writers failed: $initError")
            return false
        }

        // PCM-only WAV path
        return try {
            wavOutputStream = FileOutputStream(wavPath)
            wavSampleRate = normalized.sampleRate.coerceAtLeast(1)
            wavChannels = normalized.channelCount.coerceAtLeast(1)
            wavOutputStream?.write(ByteArray(44))
            wavDataSize = 0
            isStarted = true
            outputMode = OutputMode.PCM_WAV
            outputPath = wavPath
            Log.i(TAG, "PCM WAV writer initialized: $wavPath")
            true
        } catch (e: Exception) {
            Log.e(TAG, "WAV init failed", e)
            initError = "WAV init failed: ${e.message}"
            false
        }
    }

    fun writeSample(sample: AudioSample) {
        if (!isStarted) return
        when (outputMode) {
            OutputMode.ALAC_M4A_MUXER -> writeM4aSample(sample)
            OutputMode.ALAC_M4A_MANUAL -> manualWriter?.writeSample(sample)
            OutputMode.PCM_WAV -> writeWavSample(sample)
            OutputMode.NONE -> Unit
        }
    }

    fun finish(): String? {
        if (!isStarted) return null
        return try {
            val path = when (outputMode) {
                OutputMode.ALAC_M4A_MUXER -> finishM4a()
                OutputMode.ALAC_M4A_MANUAL -> {
                    val p = manualWriter?.finish()
                    isStarted = false
                    p ?: outputPath
                }
                OutputMode.PCM_WAV -> finishWav()
                OutputMode.NONE -> null
            }
            path?.takeIf { it.isNotBlank() && File(it).exists() && File(it).length() > 0 }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize output file", e)
            null
        }
    }

    fun getOutputPath(): String = outputPath
    fun getOutputMode(): OutputMode = outputMode
    fun getInitError(): String? = initError
    fun isFallbackWav(): Boolean = outputMode == OutputMode.PCM_WAV

    private fun normalizeTrackInfo(trackInfo: TrackInfo): TrackInfo {
        val rate = trackInfo.sampleRate.takeIf { it > 0 } ?: 44100
        val ch = trackInfo.channelCount.takeIf { it > 0 } ?: 2
        val raw = trackInfo.csd0
        // Pure 24-byte ALACSpecificConfig for MediaCodec csd-0 (ExoPlayer AtomParsers)
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
            Log.w(TAG, "MediaMuxer ALAC requires API 30+, device has ${Build.VERSION.SDK_INT}")
            initError = "API ${Build.VERSION.SDK_INT} < 30 for MediaMuxer ALAC"
            return false
        }
        return try {
            val format = createAlacMediaFormat(trackInfo)
            val m = MediaMuxer(m4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val idx = m.addTrack(format)
            m.start()
            muxer = m
            trackIndex = idx
            isStarted = true
            outputMode = OutputMode.ALAC_M4A_MUXER
            outputPath = m4aPath
            initError = null
            Log.i(TAG, "M4A MediaMuxer initialized: $m4aPath track=$idx csd0=${trackInfo.csd0?.size}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaMuxer ALAC init failed: ${e.message}", e)
            runCatching { muxer?.stop() }
            muxer?.release()
            muxer = null
            trackIndex = -1
            isStarted = false
            outputMode = OutputMode.NONE
            initError = "MediaMuxer ALAC failed: ${e.message}"
            // delete partial file
            runCatching { File(m4aPath).delete() }
            false
        }
    }

    private fun reset() {
        runCatching {
            if (isStarted && muxer != null) {
                muxer?.stop()
            }
        }
        muxer?.release()
        muxer = null
        runCatching { manualWriter?.finish() }
        manualWriter = null
        runCatching { wavOutputStream?.close() }
        wavOutputStream = null
        isStarted = false
        trackIndex = -1
        outputMode = OutputMode.NONE
        wavDataSize = 0
        nextPresentationTimeUs = 0L
        outputPath = ""
        initError = null
        effectiveTrackInfo = null
    }

    private fun createAlacMediaFormat(trackInfo: TrackInfo): MediaFormat {
        val sampleRate = trackInfo.sampleRate.coerceAtLeast(1)
        val channelCount = trackInfo.channelCount.coerceAtLeast(1)
        val format = MediaFormat.createAudioFormat(ALAC_MIME, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, trackInfo.bitrate.takeIf { it > 0 } ?: 256000)

        val csd = trackInfo.csd0
        if (csd != null && csd.isNotEmpty()) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }

        // Some devices want max input size
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, (sampleRate * channelCount).coerceAtLeast(4096) * 4)
        return format
    }

    private fun writeM4aSample(sample: AudioSample) {
        try {
            val buffer = sample.data.duplicate()
            buffer.position(0)
            buffer.limit(sample.size.coerceAtMost(buffer.capacity()))

            val pts = if (sample.presentationTimeUs > 0) {
                sample.presentationTimeUs
            } else {
                nextPresentationTimeUs
            }
            val duration = if (sample.durationUs > 0) sample.durationUs else 23_219L
            nextPresentationTimeUs = pts + duration

            val info = MediaCodec.BufferInfo()
            info.offset = 0
            info.size = sample.size
            info.presentationTimeUs = pts
            info.flags = if (sample.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer?.writeSampleData(trackIndex, buffer, info)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write M4A sample", e)
        }
    }

    private fun finishM4a(): String? {
        muxer?.let {
            try {
                it.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Muxer stop error", e)
            }
            it.release()
        }
        muxer = null
        isStarted = false
        return outputPath
    }

    private fun writeWavSample(sample: AudioSample) {
        try {
            val data = ByteArray(sample.size)
            sample.data.position(0)
            sample.data.get(data, 0, sample.size)
            wavOutputStream?.write(data)
            wavDataSize += sample.size
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write WAV sample", e)
        }
    }

    private fun finishWav(): String? {
        wavOutputStream?.let { stream ->
            try {
                stream.channel.position(0)
                stream.write(createWavHeader(wavDataSize, wavSampleRate, wavChannels))
                stream.close()
            } catch (e: Exception) {
                Log.w(TAG, "WAV finish error", e)
            }
        }
        wavOutputStream = null
        isStarted = false
        return outputPath
    }

    private fun createWavHeader(dataSize: Int, sampleRate: Int, channels: Int): ByteArray {
        val byteRate = sampleRate * channels * 2
        val blockAlign = channels * 2
        val totalSize = 36 + dataSize
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        return buffer.array()
    }
}
