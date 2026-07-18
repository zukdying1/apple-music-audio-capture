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
 * WAV fallback is ONLY used for raw PCM (mime audio/raw). Compressed ALAC frames
 * are never written as WAV (that would produce a broken file).
 */
class M4aWriter {
    companion object {
        private const val TAG = "AppleDecryptor"
        private const val ALAC_MIME = "audio/alac"
        private const val PCM_MIME = "audio/raw"
        const val OUTPUT_DIR = "/sdcard/Music/AppleDecryptor"
    }

    enum class OutputMode {
        ALAC_M4A,
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

        // Prefer ALAC M4A when not raw PCM.
        if (!isPcm) {
            try {
                val format = createAlacMediaFormat(trackInfo)
                if (format != null) {
                    muxer = MediaMuxer(m4aPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    trackIndex = muxer!!.addTrack(format)
                    muxer!!.start()
                    isStarted = true
                    outputMode = OutputMode.ALAC_M4A
                    outputPath = m4aPath
                    Log.i(TAG, "M4A writer initialized: $m4aPath track=$trackIndex")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "ALAC M4A init failed: ${e.message}", e)
                runCatching { muxer?.stop() }
                muxer?.release()
                muxer = null
                initError = "MediaMuxer ALAC failed: ${e.message}"
            }
            // Do NOT fall back to WAV for compressed ALAC — fail cleanly.
            isStarted = false
            outputMode = OutputMode.NONE
            Log.e(TAG, "Refusing WAV fallback for compressed ALAC (${trackInfo.mime})")
            return false
        }

        // PCM-only WAV path
        return try {
            wavOutputStream = FileOutputStream(wavPath)
            wavSampleRate = trackInfo.sampleRate.coerceAtLeast(1)
            wavChannels = trackInfo.channelCount.coerceAtLeast(1)
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
            OutputMode.ALAC_M4A -> writeM4aSample(sample)
            OutputMode.PCM_WAV -> writeWavSample(sample)
            OutputMode.NONE -> Unit
        }
    }

    fun finish(): String? {
        if (!isStarted) return null
        return try {
            when (outputMode) {
                OutputMode.ALAC_M4A -> finishM4a()
                OutputMode.PCM_WAV -> finishWav()
                OutputMode.NONE -> return null
            }
            outputPath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to finalize output file", e)
            null
        }
    }

    fun getOutputPath(): String = outputPath
    fun getOutputMode(): OutputMode = outputMode
    fun getInitError(): String? = initError
    fun isFallbackWav(): Boolean = outputMode == OutputMode.PCM_WAV

    private fun reset() {
        runCatching {
            if (isStarted && muxer != null) {
                muxer?.stop()
            }
        }
        muxer?.release()
        muxer = null
        runCatching { wavOutputStream?.close() }
        wavOutputStream = null
        isStarted = false
        trackIndex = -1
        outputMode = OutputMode.NONE
        wavDataSize = 0
        nextPresentationTimeUs = 0L
        outputPath = ""
        initError = null
    }

    private fun createAlacMediaFormat(trackInfo: TrackInfo): MediaFormat? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "ALAC M4A requires API 30+, device has ${Build.VERSION.SDK_INT}")
            initError = "API ${Build.VERSION.SDK_INT} < 30 for MediaMuxer ALAC"
            return null
        }

        val sampleRate = trackInfo.sampleRate.coerceAtLeast(1)
        val channelCount = trackInfo.channelCount.coerceAtLeast(1)
        val format = MediaFormat.createAudioFormat(ALAC_MIME, sampleRate, channelCount)
        format.setInteger(MediaFormat.KEY_BIT_RATE, trackInfo.bitrate.takeIf { it > 0 } ?: 256000)

        if (trackInfo.csd0 != null && trackInfo.csd0.isNotEmpty()) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(trackInfo.csd0))
        } else {
            // Many devices reject ALAC tracks without magic cookie.
            Log.w(TAG, "ALAC csd-0 (magic cookie) missing; MediaMuxer may reject track")
        }

        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, sampleRate * channelCount * 4)
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

    private fun finishM4a() {
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

    private fun finishWav() {
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
