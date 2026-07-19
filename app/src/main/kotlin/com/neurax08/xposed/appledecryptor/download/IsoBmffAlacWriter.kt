package com.neurax08.xposed.appledecryptor.download

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/**
 * Manual ISOBMFF / M4A writer for ALAC frames.
 *
 * MediaMuxer rejects audio/alac on many OEM ROMs even when API >= 30.
 * This writer builds a progressive (non-fragmented) M4A:
 *   ftyp + mdat(samples) + moov(stbl with stsz/stts/stco/stsc/stsd)
 *
 * Samples are written first into mdat; moov is appended at the end with absolute
 * chunk offsets patched after we know mdat start.
 */
class IsoBmffAlacWriter {
    companion object {
        private const val TAG = "AppleDecryptor"

        /**
         * Build ALACSpecificConfig (24 bytes) prefixed with version/flags (4 zero bytes)
         * — the usual shape of the inner 'alac' atom payload / MediaCodec csd-0.
         */
        fun buildDefaultAlacCookie(sampleRate: Int, channelCount: Int, bitDepth: Int = 16): ByteArray {
            val buf = ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(0) // version + flags
            buf.putInt(4096) // frameLength (Apple Music ALAC typically 4096)
            buf.put(0) // compatibleVersion
            buf.put(bitDepth.toByte()) // bitDepth
            buf.put(40) // pb
            buf.put(10) // mb
            buf.put(14) // kb
            buf.put(channelCount.coerceIn(1, 8).toByte())
            buf.putShort(255) // maxRun
            buf.putInt(0) // maxFrameBytes
            buf.putInt(0) // avgBitRate
            buf.putInt(sampleRate.coerceAtLeast(1))
            return buf.array()
        }

        fun normalizeAlacCookie(raw: ByteArray, sampleRate: Int, channelCount: Int): ByteArray {
            return when {
                raw.size >= 24 -> raw
                raw.isEmpty() -> buildDefaultAlacCookie(sampleRate, channelCount)
                else -> buildDefaultAlacCookie(sampleRate, channelCount)
            }
        }
    }

    private var raf: RandomAccessFile? = null
    private var outputPath: String = ""
    private var mdatDataStart: Long = 0L
    private var mdatHeaderPos: Long = 0L
    private var sampleRate: Int = 44100
    private var channelCount: Int = 2
    private var csd0: ByteArray = buildDefaultAlacCookie(44100, 2)
    private val sampleSizes = ArrayList<Int>(4096)
    private val sampleDurations = ArrayList<Int>(4096) // in timescale ticks
    private var totalTicks: Long = 0L
    private var started = false
    private var error: String? = null

    fun getError(): String? = error
    fun getOutputPath(): String = outputPath
    fun isStarted(): Boolean = started

    fun init(path: String, trackInfo: TrackInfo): Boolean {
        closeQuietly()
        sampleSizes.clear()
        sampleDurations.clear()
        totalTicks = 0L
        error = null

        sampleRate = trackInfo.sampleRate.coerceAtLeast(1)
        channelCount = trackInfo.channelCount.coerceAtLeast(1)
        csd0 = when {
            trackInfo.csd0 != null && trackInfo.csd0!!.isNotEmpty() -> trackInfo.csd0!!
            else -> buildDefaultAlacCookie(sampleRate, channelCount)
        }

        return try {
            val file = File(path)
            file.parentFile?.mkdirs()
            if (file.exists()) file.delete()
            val out = RandomAccessFile(file, "rw")
            // ftyp
            writeBox(out, "ftyp") {
                writeFourcc(it, "M4A ")
                writeU32(it, 0)
                writeFourcc(it, "M4A ")
                writeFourcc(it, "mp42")
                writeFourcc(it, "isom")
            }
            // mdat header placeholder (size patched later); 64-bit size for large files
            mdatHeaderPos = out.filePointer
            writeU32(out, 1) // box size = 1 → 64-bit largesize follows
            writeFourcc(out, "mdat")
            writeU64(out, 0) // placeholder largesize
            mdatDataStart = out.filePointer

            raf = out
            outputPath = path
            started = true
            Log.i(
                TAG,
                "IsoBmffAlacWriter init path=$path rate=$sampleRate ch=$channelCount csd0=${csd0.size}",
            )
            true
        } catch (e: Exception) {
            error = "IsoBmff init failed: ${e.message}"
            Log.e(TAG, error!!, e)
            closeQuietly()
            false
        }
    }

    fun writeSample(sample: AudioSample) {
        val out = raf ?: return
        if (!started || sample.size <= 0) return
        try {
            val size = sample.size
            val buf = sample.data.duplicate()
            buf.position(0)
            buf.limit(size.coerceAtMost(buf.capacity()))
            val bytes = ByteArray(size)
            buf.get(bytes, 0, size)
            out.write(bytes)

            val durationTicks = durationToTicks(sample.durationUs, sampleRate)
            sampleSizes.add(size)
            sampleDurations.add(durationTicks)
            totalTicks += durationTicks.toLong()
        } catch (e: Exception) {
            Log.w(TAG, "IsoBmff writeSample failed: ${e.message}")
        }
    }

    fun finish(): String? {
        val out = raf ?: return null
        if (!started) return null
        return try {
            if (sampleSizes.isEmpty()) {
                error = "No samples written"
                closeQuietly()
                return null
            }

            val mdatEnd = out.filePointer
            val mdatBoxSize = mdatEnd - mdatHeaderPos // includes header
            // patch mdat largesize (header: size=1, type=mdat, largesize=8bytes)
            out.seek(mdatHeaderPos + 8)
            writeU64(out, mdatBoxSize)

            // append moov at end
            out.seek(mdatEnd)
            writeMoov(out, mdatDataStart)

            out.fd.sync()
            closeQuietly()
            Log.i(TAG, "IsoBmffAlacWriter finished samples=${sampleSizes.size} path=$outputPath")
            outputPath
        } catch (e: Exception) {
            error = "IsoBmff finish failed: ${e.message}"
            Log.e(TAG, error!!, e)
            closeQuietly()
            null
        }
    }

    private fun writeMoov(out: RandomAccessFile, mdatStart: Long) {
        val timescale = sampleRate
        val duration = totalTicks.coerceAtLeast(1L)

        writeBox(out, "moov") {
            writeBox(it, "mvhd") { mvhd ->
                writeU32(mvhd, 0)
                writeU32(mvhd, 0)
                writeU32(mvhd, 0)
                writeU32(mvhd, timescale)
                writeU32(mvhd, duration.toInt().coerceAtLeast(1))
                writeU32(mvhd, 0x00010000)
                writeU16(mvhd, 0x0100)
                writeU16(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0)
                writeU32(mvhd, 0x00010000); writeU32(mvhd, 0); writeU32(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0x00010000); writeU32(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0); writeU32(mvhd, 0x40000000)
                writeU32(mvhd, 0); writeU32(mvhd, 0); writeU32(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0); writeU32(mvhd, 0)
                writeU32(mvhd, 2)
            }
            writeBox(it, "trak") { trak ->
                writeBox(trak, "tkhd") { tkhd ->
                    writeU32(tkhd, 0x00000007)
                    writeU32(tkhd, 0); writeU32(tkhd, 0)
                    writeU32(tkhd, 1)
                    writeU32(tkhd, 0)
                    writeU32(tkhd, duration.toInt().coerceAtLeast(1))
                    writeU32(tkhd, 0); writeU32(tkhd, 0)
                    writeU16(tkhd, 0); writeU16(tkhd, 0)
                    writeU16(tkhd, 0x0100); writeU16(tkhd, 0)
                    writeU32(tkhd, 0x00010000); writeU32(tkhd, 0); writeU32(tkhd, 0)
                    writeU32(tkhd, 0); writeU32(tkhd, 0x00010000); writeU32(tkhd, 0)
                    writeU32(tkhd, 0); writeU32(tkhd, 0); writeU32(tkhd, 0x40000000)
                    writeU32(tkhd, 0); writeU32(tkhd, 0)
                }
                writeBox(trak, "mdia") { mdia ->
                    writeBox(mdia, "mdhd") { mdhd ->
                        writeU32(mdhd, 0)
                        writeU32(mdhd, 0); writeU32(mdhd, 0)
                        writeU32(mdhd, timescale)
                        writeU32(mdhd, duration.toInt().coerceAtLeast(1))
                        writeU16(mdhd, 0x55c4)
                        writeU16(mdhd, 0)
                    }
                    writeBox(mdia, "hdlr") { hdlr ->
                        writeU32(hdlr, 0)
                        writeU32(hdlr, 0)
                        writeFourcc(hdlr, "soun")
                        writeU32(hdlr, 0); writeU32(hdlr, 0); writeU32(hdlr, 0)
                        hdlr.write("SoundHandler\u0000".toByteArray(StandardCharsets.UTF_8))
                    }
                    writeBox(mdia, "minf") { minf ->
                        writeBox(minf, "smhd") { smhd ->
                            writeU32(smhd, 0)
                            writeU16(smhd, 0); writeU16(smhd, 0)
                        }
                        writeBox(minf, "dinf") { dinf ->
                            writeBox(dinf, "dref") { dref ->
                                writeU32(dref, 0)
                                writeU32(dref, 1)
                                writeBox(dref, "url ") { url ->
                                    writeU32(url, 0x00000001)
                                }
                            }
                        }
                        writeBox(minf, "stbl") { stbl ->
                            writeStsd(stbl)
                            writeStts(stbl)
                            writeStsc(stbl)
                            writeStsz(stbl)
                            writeStco(stbl, mdatStart)
                        }
                    }
                }
            }
        }
    }

    private fun writeStsd(parent: RandomAccessFile) {
        writeBox(parent, "stsd") { stsd ->
            writeU32(stsd, 0)
            writeU32(stsd, 1)
            writeBox(stsd, "alac") { alac ->
                writeU32(alac, 0); writeU16(alac, 0)
                writeU16(alac, 1)
                writeU16(alac, 0); writeU16(alac, 0)
                writeU32(alac, 0)
                writeU16(alac, channelCount)
                writeU16(alac, 16)
                writeU16(alac, 0); writeU16(alac, 0)
                writeU32(alac, (sampleRate shl 16))
                val cookie = normalizeAlacCookie(csd0, sampleRate, channelCount)
                writeU32(alac, 8 + cookie.size)
                writeFourcc(alac, "alac")
                alac.write(cookie)
            }
        }
    }

    private fun writeStts(parent: RandomAccessFile) {
        data class Run(val count: Int, val delta: Int)
        val runs = ArrayList<Run>()
        if (sampleDurations.isNotEmpty()) {
            var cur = sampleDurations[0]
            var cnt = 1
            for (i in 1 until sampleDurations.size) {
                val d = sampleDurations[i]
                if (d == cur) cnt++ else {
                    runs.add(Run(cnt, cur))
                    cur = d
                    cnt = 1
                }
            }
            runs.add(Run(cnt, cur))
        }
        writeBox(parent, "stts") { stts ->
            writeU32(stts, 0)
            writeU32(stts, runs.size)
            for (r in runs) {
                writeU32(stts, r.count)
                writeU32(stts, r.delta.coerceAtLeast(1))
            }
        }
    }

    private fun writeStsc(parent: RandomAccessFile) {
        writeBox(parent, "stsc") { stsc ->
            writeU32(stsc, 0)
            writeU32(stsc, 1)
            writeU32(stsc, 1)
            writeU32(stsc, sampleSizes.size)
            writeU32(stsc, 1)
        }
    }

    private fun writeStsz(parent: RandomAccessFile) {
        writeBox(parent, "stsz") { stsz ->
            writeU32(stsz, 0)
            writeU32(stsz, 0)
            writeU32(stsz, sampleSizes.size)
            for (s in sampleSizes) writeU32(stsz, s)
        }
    }

    private fun writeStco(parent: RandomAccessFile, mdatStart: Long) {
        if (mdatStart > 0xFFFFFFFFL) {
            writeBox(parent, "co64") { co64 ->
                writeU32(co64, 0)
                writeU32(co64, 1)
                writeU64(co64, mdatStart)
            }
        } else {
            writeBox(parent, "stco") { stco ->
                writeU32(stco, 0)
                writeU32(stco, 1)
                writeU32(stco, mdatStart.toInt())
            }
        }
    }

    private fun durationToTicks(durationUs: Long, rate: Int): Int {
        if (durationUs <= 0L) return 4096
        val ticks = (durationUs * rate) / 1_000_000L
        return ticks.toInt().coerceAtLeast(1)
    }

    private fun closeQuietly() {
        runCatching { raf?.close() }
        raf = null
        started = false
    }

    private fun writeBox(out: RandomAccessFile, type: String, body: (RandomAccessFile) -> Unit) {
        val start = out.filePointer
        writeU32(out, 0)
        writeFourcc(out, type)
        body(out)
        val end = out.filePointer
        val size = end - start
        out.seek(start)
        writeU32(out, size.toInt())
        out.seek(end)
    }

    private fun writeFourcc(out: RandomAccessFile, fourcc: String) {
        val b = fourcc.toByteArray(StandardCharsets.ISO_8859_1)
        require(b.size == 4)
        out.write(b)
    }

    private fun writeU32(out: RandomAccessFile, value: Int) {
        out.writeInt(value)
    }

    private fun writeU32(out: RandomAccessFile, value: Long) {
        out.writeInt(value.toInt())
    }

    private fun writeU16(out: RandomAccessFile, value: Int) {
        out.writeShort(value and 0xFFFF)
    }

    private fun writeU64(out: RandomAccessFile, value: Long) {
        out.writeLong(value)
    }
}
