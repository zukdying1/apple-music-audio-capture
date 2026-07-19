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
 * Builds a progressive (non-fragmented) M4A that Android MediaPlayer can open:
 *   ftyp + moov + mdat   (moov-first / faststart — required by many OEM players)
 *
 * Implementation: stream samples into a side .mdat.tmp file, then on finish()
 * write final file as ftyp + moov + mdat payload. Avoids 64-bit "largesize" mdat
 * (common cause of setDataSource status=0x80000000 on Android).
 */
class IsoBmffAlacWriter(
    private val context: android.content.Context? = null,
) {
    companion object {
        private const val TAG = "AppleDecryptor"

        /**
         * Pure ALACSpecificConfig (24 bytes) — matches ExoPlayer
         * CodecSpecificDataUtil.parseAlacAudioSpecificConfig and AtomParsers csd-0.
         *
         * Layout (Apple Music 5.2.1 / ExoPlayer):
         *   0-3  frameLength (uint32) = 0x1000 (4096) per SVAudioRendererConfig
         *   4    compatibleVersion
         *   5    bitDepth          ← ExoPlayer reads here
         *   6-8  pb, mb, kb
         *   9    numChannels       ← ExoPlayer reads here
         *   10-11 maxRun
         *   12-15 maxFrameBytes
         *   16-19 avgBitRate
         *   20-23 sampleRate       ← ExoPlayer reads here
         *
         * Nested stsd 'alac' atom is: size(4)+type(4)+version/flags(4)+this(24).
         * MediaCodec csd-0 is THIS 24-byte config only (not including version/flags).
         */
        fun buildDefaultAlacCookie(sampleRate: Int, channelCount: Int, bitDepth: Int = 16): ByteArray {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            buf.putInt(4096) // frameLength = NUM_OF_ALAC_SAMPLES_PER_FRAME
            buf.put(0) // compatibleVersion
            buf.put(bitDepth.toByte()) // bitDepth @ offset 5
            buf.put(40) // pb
            buf.put(10) // mb
            buf.put(14) // kb
            buf.put(channelCount.coerceIn(1, 8).toByte()) // channels @ offset 9
            buf.putShort(255) // maxRun
            buf.putInt(0) // maxFrameBytes
            buf.putInt(0) // avgBitRate
            buf.putInt(sampleRate.coerceAtLeast(1)) // sampleRate @ offset 20
            return buf.array()
        }

        /**
         * Normalize any raw cookie/csd into pure 24-byte ALACSpecificConfig for MediaCodec csd-0.
         * Accepts: 24 pure, 28 version+config, full nested atom (36+), or stsd leftovers.
         */
        fun normalizeAlacCookie(raw: ByteArray, sampleRate: Int, channelCount: Int): ByteArray {
            if (raw.isEmpty()) return buildDefaultAlacCookie(sampleRate, channelCount)
            // Full nested atom: size(4)+'alac'(4)+ver(4)+config(24)
            if (raw.size >= 36 && fourcc(raw, 4) == "alac") {
                return raw.copyOfRange(12, 36)
            }
            // version/flags(4) + config(24)
            if (raw.size >= 28 && raw[0] == 0.toByte() && raw[1] == 0.toByte() &&
                raw[2] == 0.toByte() && raw[3] == 0.toByte()
            ) {
                return raw.copyOfRange(4, 28)
            }
            // pure 24+
            if (raw.size >= 24) {
                return raw.copyOfRange(0, 24)
            }
            return buildDefaultAlacCookie(sampleRate, channelCount)
        }

        /** Nested atom payload for stsd: version/flags(4) + pure config(24). */
        fun nestedAlacAtomPayload(pureConfig24: ByteArray): ByteArray {
            val cfg = if (pureConfig24.size >= 24) pureConfig24.copyOfRange(0, 24)
            else buildDefaultAlacCookie(44100, 2)
            return ByteBuffer.allocate(28).order(ByteOrder.BIG_ENDIAN).apply {
                putInt(0) // version + flags
                put(cfg)
            }.array()
        }

        fun frameLengthFromCookie(pureConfig24: ByteArray): Int {
            if (pureConfig24.size < 4) return 4096
            val fl = ByteBuffer.wrap(pureConfig24, 0, 4).order(ByteOrder.BIG_ENDIAN).int
            return if (fl in 1..16384) fl else 4096
        }

        fun sampleRateFromCookie(pureConfig24: ByteArray): Int {
            if (pureConfig24.size < 24) return 0
            return ByteBuffer.wrap(pureConfig24, 20, 4).order(ByteOrder.BIG_ENDIAN).int
        }

        fun channelsFromCookie(pureConfig24: ByteArray): Int {
            if (pureConfig24.size < 10) return 0
            return pureConfig24[9].toInt() and 0xff
        }

        fun bitDepthFromCookie(pureConfig24: ByteArray): Int {
            if (pureConfig24.size < 6) return 16
            return pureConfig24[5].toInt() and 0xff
        }

        private fun fourcc(data: ByteArray, offset: Int): String {
            if (offset + 4 > data.size) return "????"
            return String(data, offset, 4, Charsets.ISO_8859_1)
        }

        /**
         * True if payload looks like an ISOBMFF top-level box (ftyp/moof/mdat/moov/...)
         * rather than a bare ALAC packet.
         */
        fun looksLikeIsoBmff(payload: ByteArray): Boolean {
            if (payload.size < 8) return false
            val type = fourcc(payload, 4)
            return type == "ftyp" || type == "moof" || type == "mdat" || type == "moov" ||
                type == "free" || type == "sidx" || type == "styp" || type == "skip"
        }
    }

    private var mdatTmp: RandomAccessFile? = null
    private var mdatTmpFile: File? = null
    private var outputPath: String = ""
    private var sampleRate: Int = 44100
    private var channelCount: Int = 2
    private var bitDepth: Int = 16
    private var frameLength: Int = 4096
    private var csd0: ByteArray = buildDefaultAlacCookie(44100, 2)
    private val sampleSizes = ArrayList<Int>(8192)
    private val sampleDurations = ArrayList<Int>(8192) // timescale ticks (= sampleRate units)
    private var totalTicks: Long = 0L
    private var mdatBytes: Long = 0L
    private var started = false
    private var error: String? = null
    private var samplesWritten = 0
    private var samplesSkipped = 0

    fun getError(): String? = error
    fun getOutputPath(): String = outputPath
    fun isStarted(): Boolean = started
    fun getSampleCount(): Int = samplesWritten

    fun init(path: String, trackInfo: TrackInfo): Boolean {
        closeQuietly()
        sampleSizes.clear()
        sampleDurations.clear()
        totalTicks = 0L
        mdatBytes = 0L
        samplesWritten = 0
        samplesSkipped = 0
        error = null

        sampleRate = trackInfo.sampleRate.coerceAtLeast(1)
        channelCount = trackInfo.channelCount.coerceAtLeast(1)
        // csd0 stored as pure 24-byte ALACSpecificConfig (ExoPlayer / MediaCodec)
        csd0 = normalizeAlacCookie(
            trackInfo.csd0 ?: ByteArray(0),
            sampleRate,
            channelCount,
        )
        frameLength = frameLengthFromCookie(csd0)
        val cookieRate = sampleRateFromCookie(csd0)
        if (cookieRate in 8000..384000) sampleRate = cookieRate
        val cookieCh = channelsFromCookie(csd0)
        if (cookieCh in 1..8) channelCount = cookieCh
        val cookieBd = bitDepthFromCookie(csd0)
        if (cookieBd in 16..32) bitDepth = cookieBd

        return try {
            // Resolve a writable final path: prefer the requested path, else OutputPaths.musicDir.
            var outFile = File(path)
            var parent = outFile.parentFile
            fun parentWritable(p: File?): Boolean {
                if (p == null) return false
                return try {
                    if (!p.exists() && !p.mkdirs()) return false
                    val probe = File(p, ".wprobe_${System.nanoTime()}")
                    probe.writeText("ok")
                    probe.delete()
                    true
                } catch (_: Exception) {
                    false
                }
            }
            if (!parentWritable(parent)) {
                val fallbackDir = OutputPaths.musicDir(context)
                outFile = File(fallbackDir, outFile.name)
                parent = fallbackDir
                Log.w(TAG, "final path parent not writable; fallback=${outFile.absolutePath}")
            }
            if (outFile.exists()) outFile.delete()

            // ALWAYS put .mdat.tmp in cache/temp — never on /sdcard (EPERM).
            val tmpDir = OutputPaths.tempDir(context)
            val tmp = File(tmpDir, outFile.name + ".mdat.tmp")
            if (tmp.exists()) tmp.delete()
            mdatTmpFile = tmp
            mdatTmp = RandomAccessFile(tmp, "rw")
            outputPath = outFile.absolutePath
            started = true
            Log.i(
                TAG,
                "IsoBmffAlacWriter init path=$outputPath tmp=${tmp.absolutePath} " +
                    "rate=$sampleRate ch=$channelCount bitDepth=$bitDepth frameLen=$frameLength " +
                    "csd0=${csd0.size} csd0hex=${csd0.take(16).joinToString("") { "%02x".format(it) }}",
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
        val out = mdatTmp ?: return
        if (!started || sample.size <= 0) return
        try {
            val size = sample.size
            val buf = sample.data.duplicate()
            buf.position(0)
            buf.limit(size.coerceAtMost(buf.capacity()))
            val bytes = ByteArray(size)
            buf.get(bytes, 0, size)

            // Reject ISOBMFF containers accidentally treated as ALAC frames.
            if (looksLikeIsoBmff(bytes)) {
                samplesSkipped++
                if (samplesSkipped <= 3) {
                    Log.w(
                        TAG,
                        "skip non-ALAC sample (looks like ISOBMFF type=${String(bytes, 4, 4, Charsets.ISO_8859_1)}) size=$size",
                    )
                }
                return
            }

            // Reject empty / tiny garbage
            if (bytes.size < 2) {
                samplesSkipped++
                return
            }

            out.write(bytes)
            mdatBytes += bytes.size.toLong()

            val durationTicks = durationToTicks(sample.durationUs, sampleRate)
            sampleSizes.add(bytes.size)
            sampleDurations.add(durationTicks)
            totalTicks += durationTicks.toLong()
            samplesWritten++

            if (samplesWritten == 1) {
                Log.i(
                    TAG,
                    "first ALAC sample size=$size durationTicks=$durationTicks " +
                        "head=${bytes.take(12).joinToString("") { "%02x".format(it) }}",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "IsoBmff writeSample failed: ${e.message}")
        }
    }

    fun finish(): String? {
        val tmp = mdatTmp ?: return null
        if (!started) return null
        return try {
            if (sampleSizes.isEmpty() || mdatBytes <= 0L) {
                error = "No ALAC samples written (skipped=$samplesSkipped)"
                Log.e(TAG, error!!)
                closeQuietly()
                return null
            }

            tmp.fd.sync()
            tmp.seek(0)

            val finalFile = File(outputPath)
            if (finalFile.exists()) finalFile.delete()
            RandomAccessFile(finalFile, "rw").use { out ->
                // 1) ftyp
                writeBox(out, "ftyp") {
                    writeFourcc(it, "M4A ")
                    writeU32(it, 0)
                    writeFourcc(it, "M4A ")
                    writeFourcc(it, "mp42")
                    writeFourcc(it, "isom")
                    writeFourcc(it, "iso2")
                }

                // 2) moov (before mdat — faststart; Android players need this more often)
                writeMoov(out, mdatPayloadOffsetPlaceholder = 0L) // stco patched below

                val moovEnd = out.filePointer

                // 3) mdat with 32-bit size (NOT largesize — OEM MediaPlayer often fails on 64-bit mdat)
                val mdatBoxSize = 8L + mdatBytes
                if (mdatBoxSize > 0xFFFFFFFFL) {
                    error = "mdat too large for 32-bit box (${mdatBytes} bytes)"
                    Log.e(TAG, error!!)
                    closeQuietly()
                    return null
                }
                val mdatHeaderPos = out.filePointer
                writeU32(out, mdatBoxSize.toInt())
                writeFourcc(out, "mdat")
                val mdatDataStart = out.filePointer

                // Copy sample payload from tmp
                val copyBuf = ByteArray(256 * 1024)
                var remaining = mdatBytes
                while (remaining > 0) {
                    val n = tmp.read(copyBuf, 0, minOf(copyBuf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(copyBuf, 0, n)
                    remaining -= n.toLong()
                }
                if (remaining != 0L) {
                    error = "mdat copy incomplete remaining=$remaining"
                    Log.e(TAG, error!!)
                    closeQuietly()
                    return null
                }

                // Patch stco chunk offset inside moov to absolute file offset of mdat payload.
                // We re-scan moov for 'stco'/'co64' and write the single chunk offset.
                patchChunkOffset(out, moovSearchStart = 0L, moovSearchEnd = moovEnd, chunkOffset = mdatDataStart)

                out.fd.sync()
            }

            Log.i(
                TAG,
                "IsoBmffAlacWriter finished samples=$samplesWritten skipped=$samplesSkipped " +
                    "mdatBytes=$mdatBytes path=$outputPath size=${finalFile.length()}",
            )
            closeQuietly()
            outputPath
        } catch (e: Exception) {
            error = "IsoBmff finish failed: ${e.message}"
            Log.e(TAG, error!!, e)
            closeQuietly()
            null
        }
    }

    /**
     * Find first stco/co64 in [moovSearchStart, moovSearchEnd) and set single chunk offset.
     */
    private fun patchChunkOffset(
        out: RandomAccessFile,
        moovSearchStart: Long,
        moovSearchEnd: Long,
        chunkOffset: Long,
    ) {
        // Read moov region and find "stco" or "co64" fourcc
        val len = (moovSearchEnd - moovSearchStart).toInt().coerceAtLeast(0)
        if (len <= 0) return
        val bytes = ByteArray(len)
        out.seek(moovSearchStart)
        out.readFully(bytes)

        fun findFourcc(tag: String): Int {
            val t = tag.toByteArray(Charsets.ISO_8859_1)
            for (i in 0 until bytes.size - 4) {
                if (bytes[i] == t[0] && bytes[i + 1] == t[1] &&
                    bytes[i + 2] == t[2] && bytes[i + 3] == t[3]
                ) {
                    // Prefer real box: previous 4 bytes is size
                    if (i >= 4) return i
                }
            }
            return -1
        }

        val stcoAt = findFourcc("stco")
        if (stcoAt >= 0) {
            // stco layout: size(4) type(4) version/flags(4) entry_count(4) offset(4)...
            // offset field starts at stcoAt+12 relative to type start → file pos = moovSearchStart + stcoAt + 12
            // Wait: stcoAt points to type. version@+4, count@+8, first offset@+12
            val offsetFieldPos = moovSearchStart + stcoAt + 12
            out.seek(offsetFieldPos)
            writeU32(out, chunkOffset.toInt())
            Log.i(TAG, "patched stco chunkOffset=$chunkOffset at $offsetFieldPos")
            return
        }
        val co64At = findFourcc("co64")
        if (co64At >= 0) {
            val offsetFieldPos = moovSearchStart + co64At + 12
            out.seek(offsetFieldPos)
            writeU64(out, chunkOffset)
            Log.i(TAG, "patched co64 chunkOffset=$chunkOffset at $offsetFieldPos")
        }
    }

    private fun writeMoov(out: RandomAccessFile, mdatPayloadOffsetPlaceholder: Long) {
        val timescale = sampleRate
        // Use 64-bit durations via version 1 only if needed; keep version 0 for max compatibility
        // when duration fits in 32-bit.
        val duration = totalTicks.coerceAtLeast(1L)
        val duration32 = if (duration > 0xFFFFFFFFL) 0xFFFFFFFF.toInt() else duration.toInt()

        writeBox(out, "moov") {
            writeBox(it, "mvhd") { mvhd ->
                writeU32(mvhd, 0) // version/flags
                writeU32(mvhd, 0) // creation
                writeU32(mvhd, 0) // modification
                writeU32(mvhd, timescale)
                writeU32(mvhd, duration32)
                writeU32(mvhd, 0x00010000) // rate 1.0
                writeU16(mvhd, 0x0100) // volume
                writeU16(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0)
                // unity matrix
                writeU32(mvhd, 0x00010000); writeU32(mvhd, 0); writeU32(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0x00010000); writeU32(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0); writeU32(mvhd, 0x40000000)
                writeU32(mvhd, 0); writeU32(mvhd, 0); writeU32(mvhd, 0)
                writeU32(mvhd, 0); writeU32(mvhd, 0); writeU32(mvhd, 0)
                writeU32(mvhd, 2) // next_track_ID
            }
            writeBox(it, "trak") { trak ->
                writeBox(trak, "tkhd") { tkhd ->
                    // flags: track_enabled | track_in_movie | track_in_preview
                    writeU32(tkhd, 0x00000007)
                    writeU32(tkhd, 0); writeU32(tkhd, 0)
                    writeU32(tkhd, 1) // track_ID
                    writeU32(tkhd, 0)
                    writeU32(tkhd, duration32)
                    writeU32(tkhd, 0); writeU32(tkhd, 0)
                    writeU16(tkhd, 0) // layer
                    writeU16(tkhd, 0) // alternate group
                    writeU16(tkhd, 0x0100) // volume
                    writeU16(tkhd, 0)
                    writeU32(tkhd, 0x00010000); writeU32(tkhd, 0); writeU32(tkhd, 0)
                    writeU32(tkhd, 0); writeU32(tkhd, 0x00010000); writeU32(tkhd, 0)
                    writeU32(tkhd, 0); writeU32(tkhd, 0); writeU32(tkhd, 0x40000000)
                    writeU32(tkhd, 0) // width
                    writeU32(tkhd, 0) // height
                }
                writeBox(trak, "mdia") { mdia ->
                    writeBox(mdia, "mdhd") { mdhd ->
                        writeU32(mdhd, 0)
                        writeU32(mdhd, 0); writeU32(mdhd, 0)
                        writeU32(mdhd, timescale)
                        writeU32(mdhd, duration32)
                        // language "und" = 0x55c4
                        writeU16(mdhd, 0x55c4)
                        writeU16(mdhd, 0)
                    }
                    writeBox(mdia, "hdlr") { hdlr ->
                        writeU32(hdlr, 0)
                        writeU32(hdlr, 0) // pre_defined
                        writeFourcc(hdlr, "soun")
                        writeU32(hdlr, 0); writeU32(hdlr, 0); writeU32(hdlr, 0)
                        // name (null-terminated C string)
                        hdlr.write(byteArrayOf(0))
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
                                    writeU32(url, 0x00000001) // self-contained flag
                                }
                            }
                        }
                        writeBox(minf, "stbl") { stbl ->
                            writeStsd(stbl)
                            writeStts(stbl)
                            writeStsc(stbl)
                            writeStsz(stbl)
                            writeStco(stbl, mdatPayloadOffsetPlaceholder)
                        }
                    }
                }
            }
        }
    }

    private fun writeStsd(parent: RandomAccessFile) {
        writeBox(parent, "stsd") { stsd ->
            writeU32(stsd, 0) // version/flags
            writeU32(stsd, 1) // entry count
            // AudioSampleEntry 'alac' — ISO 14496-12 / QT
            writeBox(stsd, "alac") { alac ->
                // reserved(6) + data_reference_index(2)
                writeU32(alac, 0)
                writeU16(alac, 0)
                writeU16(alac, 1)
                // version(2) + revision(2) + vendor(4)
                writeU16(alac, 0)
                writeU16(alac, 0)
                writeU32(alac, 0)
                // channelcount(2) + samplesize(2)
                writeU16(alac, channelCount)
                writeU16(alac, bitDepth)
                // compressionID(2) + packetSize(2)
                writeU16(alac, 0)
                writeU16(alac, 0)
                // sampleRate 16.16 fixed
                writeU32(alac, (sampleRate shl 16))
                // Nested ALAC magic cookie atom:
                // size(4)+'alac'(4)+version/flags(4)+ALACSpecificConfig(24)
                // ExoPlayer AtomParsers skips 12 bytes then uses pure 24 as csd-0.
                val nestedPayload = nestedAlacAtomPayload(csd0)
                writeU32(alac, 8 + nestedPayload.size)
                writeFourcc(alac, "alac")
                alac.write(nestedPayload)
            }
        }
    }

    private fun writeStts(parent: RandomAccessFile) {
        data class Run(var count: Int, val delta: Int)
        val runs = ArrayList<Run>()
        if (sampleDurations.isNotEmpty()) {
            var cur = sampleDurations[0]
            var cnt = 1
            for (i in 1 until sampleDurations.size) {
                val d = sampleDurations[i]
                if (d == cur) {
                    cnt++
                } else {
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
        // One chunk, all samples
        writeBox(parent, "stsc") { stsc ->
            writeU32(stsc, 0)
            writeU32(stsc, 1)
            writeU32(stsc, 1) // first_chunk
            writeU32(stsc, sampleSizes.size) // samples_per_chunk
            writeU32(stsc, 1) // sample_description_index
        }
    }

    private fun writeStsz(parent: RandomAccessFile) {
        writeBox(parent, "stsz") { stsz ->
            writeU32(stsz, 0)
            writeU32(stsz, 0) // sample_size=0 → table
            writeU32(stsz, sampleSizes.size)
            for (s in sampleSizes) writeU32(stsz, s)
        }
    }

    private fun writeStco(parent: RandomAccessFile, mdatStart: Long) {
        // Always stco with 32-bit offset placeholder; patched after mdat position is known.
        // If final offset exceeds 32-bit (unlikely for our music files), we'd need co64 —
        // music tracks are far below 4GB.
        writeBox(parent, "stco") { stco ->
            writeU32(stco, 0)
            writeU32(stco, 1)
            writeU32(stco, mdatStart.toInt()) // placeholder, patched in finish()
        }
    }

    private fun durationToTicks(durationUs: Long, rate: Int): Int {
        if (durationUs <= 0L) return frameLength.coerceAtLeast(1)
        val ticks = (durationUs * rate) / 1_000_000L
        return ticks.toInt().coerceAtLeast(1)
    }

    private fun closeQuietly() {
        runCatching { mdatTmp?.close() }
        mdatTmp = null
        runCatching { mdatTmpFile?.delete() }
        mdatTmpFile = null
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
        require(b.size == 4) { "fourcc must be 4 chars: $fourcc" }
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
