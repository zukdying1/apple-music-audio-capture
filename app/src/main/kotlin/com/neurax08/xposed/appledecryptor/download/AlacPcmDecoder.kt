package com.neurax08.xposed.appledecryptor.download

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

/**
 * Decode ALAC packets → PCM using Android MediaCodec.
 *
 * Many devices (including those running Apple Music) ship an ALAC decoder.
 * Output is little-endian PCM (typically 16-bit) that any player can open as WAV.
 */
class AlacPcmDecoder {
    companion object {
        private const val TAG = "AppleDecryptor"
        private const val ALAC_MIME = "audio/alac"
        private const val TIMEOUT_US = 10_000L
    }

    private var codec: MediaCodec? = null
    private var started = false
    private var sampleRate = 44100
    private var channelCount = 2
    private var pcmEncodingBits = 16
    private var error: String? = null
    private var framesDecoded = 0
    private var inputEos = false

    fun getError(): String? = error
    fun getSampleRate(): Int = sampleRate
    fun getChannelCount(): Int = channelCount
    fun getPcmBits(): Int = pcmEncodingBits
    fun isStarted(): Boolean = started
    fun getFramesDecoded(): Int = framesDecoded

    fun init(trackInfo: TrackInfo): Boolean {
        release()
        sampleRate = trackInfo.sampleRate.coerceAtLeast(1)
        channelCount = trackInfo.channelCount.coerceAtLeast(1)
        val csd = IsoBmffAlacWriter.normalizeAlacCookie(
            trackInfo.csd0 ?: ByteArray(0),
            sampleRate,
            channelCount,
        )
        val cookieRate = IsoBmffAlacWriter.sampleRateFromCookie(csd)
        if (cookieRate in 8000..384000) sampleRate = cookieRate
        val cookieCh = IsoBmffAlacWriter.channelsFromCookie(csd)
        if (cookieCh in 1..8) channelCount = cookieCh
        val bitDepth = IsoBmffAlacWriter.bitDepthFromCookie(csd)
        if (bitDepth in 16..32) pcmEncodingBits = if (bitDepth <= 16) 16 else 24

        return try {
            val format = MediaFormat.createAudioFormat(ALAC_MIME, sampleRate, channelCount)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            // Optional keys some OEMs want
            runCatching {
                format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
            }
            if (Build.VERSION.SDK_INT >= 24) {
                runCatching {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                }
            }

            val c = MediaCodec.createDecoderByType(ALAC_MIME)
            c.configure(format, null, null, 0)
            c.start()
            codec = c
            started = true
            inputEos = false
            Log.i(
                TAG,
                "AlacPcmDecoder started rate=$sampleRate ch=$channelCount bits=$pcmEncodingBits " +
                    "csd0=${csd.size} hex=${csd.take(12).joinToString("") { "%02x".format(it) }}",
            )
            true
        } catch (e: Exception) {
            error = "MediaCodec ALAC decoder unavailable: ${e.message}"
            Log.w(TAG, error!!, e)
            release()
            false
        }
    }

    /**
     * Decode one ALAC packet into raw PCM bytes (may be empty if codec is buffering).
     * Call [flushRemaining] after last packet.
     */
    fun decodePacket(packet: ByteArray, presentationTimeUs: Long): ByteArray {
        if (!started || packet.isEmpty()) return ByteArray(0)
        val c = codec ?: return ByteArray(0)
        val out = ArrayList<ByteArray>()

        try {
            // Feed input
            val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val inBuf = c.getInputBuffer(inIndex)
                inBuf?.clear()
                inBuf?.put(packet)
                c.queueInputBuffer(inIndex, 0, packet.size, presentationTimeUs, 0)
            }

            // Drain available output
            val info = MediaCodec.BufferInfo()
            var drain = true
            while (drain) {
                val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> drain = false
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channelCount = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
                        Log.i(TAG, "AlacPcmDecoder output format rate=$sampleRate ch=$channelCount")
                    }
                    outIndex >= 0 -> {
                        if (info.size > 0) {
                            val outBuf = c.getOutputBuffer(outIndex)
                            if (outBuf != null) {
                                val chunk = ByteArray(info.size)
                                outBuf.position(info.offset)
                                outBuf.get(chunk)
                                out.add(chunk)
                                framesDecoded++
                            }
                        }
                        c.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            drain = false
                        }
                    }
                    else -> drain = false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "decodePacket failed: ${e.message}")
        }

        if (out.isEmpty()) return ByteArray(0)
        if (out.size == 1) return out[0]
        val total = out.sumOf { it.size }
        val merged = ByteArray(total)
        var pos = 0
        for (b in out) {
            System.arraycopy(b, 0, merged, pos, b.size)
            pos += b.size
        }
        return merged
    }

    fun flushRemaining(): ByteArray {
        if (!started) return ByteArray(0)
        val c = codec ?: return ByteArray(0)
        val out = ArrayList<ByteArray>()
        try {
            if (!inputEos) {
                val inIndex = c.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    c.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputEos = true
                }
            }
            val info = MediaCodec.BufferInfo()
            var guard = 0
            while (guard++ < 256) {
                val outIndex = c.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) break
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) continue
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        val outBuf = c.getOutputBuffer(outIndex)
                        if (outBuf != null) {
                            val chunk = ByteArray(info.size)
                            outBuf.position(info.offset)
                            outBuf.get(chunk)
                            out.add(chunk)
                        }
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "flushRemaining failed: ${e.message}")
        }
        if (out.isEmpty()) return ByteArray(0)
        val total = out.sumOf { it.size }
        val merged = ByteArray(total)
        var pos = 0
        for (b in out) {
            System.arraycopy(b, 0, merged, pos, b.size)
            pos += b.size
        }
        return merged
    }

    fun release() {
        runCatching {
            codec?.stop()
        }
        runCatching {
            codec?.release()
        }
        codec = null
        started = false
        inputEos = false
    }
}
