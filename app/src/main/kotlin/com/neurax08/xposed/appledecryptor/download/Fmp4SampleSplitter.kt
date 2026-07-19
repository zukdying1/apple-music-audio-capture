package com.neurax08.xposed.appledecryptor.download

import android.util.Log

/**
 * Split an fMP4 fragment into individual media samples using moof/trun.
 *
 * Matches the original alac-download / Frida socket contract:
 *   each sample payload is one FairPlay decryptSample() unit.
 *
 * Does NOT write containers. Callers decrypt each [EncryptedSample.data] separately.
 */
object Fmp4SampleSplitter {
    private const val TAG = "AppleDecryptor"

    data class EncryptedSample(
        val data: ByteArray,
        val durationUs: Long = 0L,
        val isKeyFrame: Boolean = true,
    )

    data class SplitResult(
        val samples: List<EncryptedSample>,
        val trackInfo: TrackInfo?,
        /** True when sizes came from trun (or equal-size default) and cover mdat. */
        val fromTrun: Boolean = false,
        val mdatSize: Int = 0,
        val diagnostics: String = "",
    )

    /**
     * @param defaultTimescale used when moof has no timescale (typically sampleRate from init)
     * @param defaultSampleDurationTicks from trex/tfhd, in timescale units
     * @param frameLengthSamples ALAC frame length (e.g. 4096) for duration fallback
     */
    fun split(
        fmp4Data: ByteArray,
        segmentDurationSec: Float = 0f,
        defaultTimescale: Long = 0L,
        defaultSampleDurationTicks: Long = 0L,
        frameLengthSamples: Int = 4096,
    ): SplitResult {
        var offset = 0
        val sampleSizes = mutableListOf<Int>()
        val sampleDurations = mutableListOf<Long>() // timescale ticks
        var timescale = defaultTimescale
        var defaultSampleDuration = defaultSampleDurationTicks
        var dataOffsetIntoMdat = 0
        var trackInfo: TrackInfo? = null
        val mdatPayloads = mutableListOf<ByteArray>()
        var moofStartInFile = -1

        while (offset + 8 <= fmp4Data.size) {
            val boxSize = readU32(fmp4Data, offset)
            if (boxSize < 8L) break
            val end = (offset + boxSize.toInt()).coerceAtMost(fmp4Data.size)
            if (end <= offset + 8) break
            val type = fourcc(fmp4Data, offset + 4)
            val body = fmp4Data.copyOfRange(offset + 8, end)

            when (type) {
                "moov" -> {
                    val parsed = parseInitMoov(body)
                    trackInfo = parsed.trackInfo ?: trackInfo
                    if (parsed.timescale > 0) timescale = parsed.timescale
                    if (parsed.defaultSampleDuration > 0) defaultSampleDuration = parsed.defaultSampleDuration
                }
                "moof" -> {
                    moofStartInFile = offset
                    val meta = parseMoof(body, sampleSizes, sampleDurations)
                    if (meta.defaultSampleDuration > 0) defaultSampleDuration = meta.defaultSampleDuration
                    if (meta.dataOffsetRelativeToMoof != null && moofStartInFile >= 0) {
                        // data_offset is typically relative to start of moof box
                        val abs = moofStartInFile + meta.dataOffsetRelativeToMoof
                        // Will convert to mdat-relative after we know mdat position
                        dataOffsetIntoMdat = abs // temp absolute; fix below
                    }
                }
                "mdat" -> mdatPayloads.add(body)
            }
            offset = end
        }

        // Find first mdat file offset for data_offset conversion
        var mdatFileOffset = -1
        run {
            var o = 0
            while (o + 8 <= fmp4Data.size) {
                val sz = readU32(fmp4Data, o).toInt()
                if (sz < 8) break
                if (fourcc(fmp4Data, o + 4) == "mdat") {
                    mdatFileOffset = o + 8
                    break
                }
                o += sz
                if (o <= 0) break
            }
        }

        val mdat = when {
            mdatPayloads.isEmpty() -> ByteArray(0)
            mdatPayloads.size == 1 -> mdatPayloads[0]
            else -> {
                val total = mdatPayloads.sumOf { it.size }
                val merged = ByteArray(total)
                var pos = 0
                for (part in mdatPayloads) {
                    System.arraycopy(part, 0, merged, pos, part.size)
                    pos += part.size
                }
                merged
            }
        }

        if (mdat.isEmpty()) {
            val diag = "empty mdat fileSize=${fmp4Data.size} sizes=${sampleSizes.size}"
            Log.w(TAG, "fmp4 split: $diag")
            return SplitResult(emptyList(), trackInfo, fromTrun = false, mdatSize = 0, diagnostics = diag)
        }

        // Convert absolute data_offset → offset into mdat payload
        var mdatCursorStart = 0
        if (dataOffsetIntoMdat > 0 && mdatFileOffset >= 0) {
            val rel = dataOffsetIntoMdat - mdatFileOffset
            if (rel in 0 until mdat.size) {
                mdatCursorStart = rel
            }
        }

        val ts = if (timescale > 0) timescale else {
            // ALAC timescale is almost always sample rate
            trackInfo?.sampleRate?.toLong()?.takeIf { it > 0 } ?: 44100L
        }

        val samples = mutableListOf<EncryptedSample>()
        val sizeSum = sampleSizes.sum()
        val usable = sampleSizes.isNotEmpty() &&
            sizeSum > 0 &&
            mdatCursorStart + sizeSum <= mdat.size

        if (usable) {
            var cursor = mdatCursorStart
            val count = sampleSizes.size
            val segmentDurationUs = (segmentDurationSec * 1_000_000f).toLong()
            val frameDurUs = if (ts > 0 && frameLengthSamples > 0) {
                frameLengthSamples.toLong() * 1_000_000L / ts
            } else {
                0L
            }
            for (i in 0 until count) {
                val size = sampleSizes[i]
                if (size <= 0 || cursor + size > mdat.size) break
                val payload = mdat.copyOfRange(cursor, cursor + size)
                cursor += size

                val durationUs = when {
                    i < sampleDurations.size && sampleDurations[i] > 0 && ts > 0 ->
                        sampleDurations[i] * 1_000_000L / ts
                    defaultSampleDuration > 0 && ts > 0 ->
                        defaultSampleDuration * 1_000_000L / ts
                    frameDurUs > 0 -> frameDurUs
                    count > 0 && segmentDurationUs > 0 -> segmentDurationUs / count
                    else -> 0L
                }

                samples.add(
                    EncryptedSample(
                        data = payload,
                        durationUs = durationUs,
                        isKeyFrame = true,
                    ),
                )
            }
            val leftover = mdat.size - cursor
            val diag =
                "trun samples=${samples.size} mdat=${mdat.size} sizeSum=$sizeSum " +
                    "cursorStart=$mdatCursorStart leftover=$leftover ts=$ts defDur=$defaultSampleDuration"
            Log.i(TAG, "fmp4 split: $diag")
            return SplitResult(
                samples = samples,
                trackInfo = trackInfo,
                fromTrun = samples.isNotEmpty(),
                mdatSize = mdat.size,
                diagnostics = diag,
            )
        }

        // No usable trun: DO NOT pretend whole mdat is one ALAC sample when it may still be
        // multi-sample. Return single mdat only as last-resort encrypted unit (caller decides).
        val durationUs = (segmentDurationSec * 1_000_000f).toLong()
        val diag =
            "fallback single mdat=${mdat.size} trunSizes=${sampleSizes.size} sizeSum=$sizeSum " +
                "cursorStart=$mdatCursorStart usable=$usable"
        Log.w(TAG, "fmp4 split: $diag")
        samples.add(EncryptedSample(data = mdat, durationUs = durationUs, isKeyFrame = true))
        return SplitResult(
            samples = samples,
            trackInfo = trackInfo,
            fromTrun = false,
            mdatSize = mdat.size,
            diagnostics = diag,
        )
    }

    private data class MoofMeta(
        val defaultSampleDuration: Long = 0L,
        val dataOffsetRelativeToMoof: Int? = null,
    )

    private data class InitMeta(
        val trackInfo: TrackInfo? = null,
        val timescale: Long = 0L,
        val defaultSampleDuration: Long = 0L,
    )

    private fun parseMoof(
        moof: ByteArray,
        sampleSizes: MutableList<Int>,
        sampleDurations: MutableList<Long>,
    ): MoofMeta {
        var offset = 0
        var defaultSampleDuration = 0L
        var dataOffset: Int? = null
        while (offset + 8 <= moof.size) {
            val boxSize = readU32(moof, offset)
            if (boxSize < 8L) break
            val end = (offset + boxSize.toInt()).coerceAtMost(moof.size)
            val type = fourcc(moof, offset + 4)
            val body = moof.copyOfRange(offset + 8, end)
            if (type == "traf") {
                val meta = parseTraf(body, sampleSizes, sampleDurations)
                if (meta.defaultSampleDuration > 0) defaultSampleDuration = meta.defaultSampleDuration
                if (meta.dataOffsetRelativeToMoof != null) dataOffset = meta.dataOffsetRelativeToMoof
            }
            offset = end
        }
        return MoofMeta(
            defaultSampleDuration = defaultSampleDuration,
            dataOffsetRelativeToMoof = dataOffset,
        )
    }

    private fun parseTraf(
        traf: ByteArray,
        sampleSizes: MutableList<Int>,
        sampleDurations: MutableList<Long>,
    ): MoofMeta {
        var offset = 0
        var defaultSampleDuration = 0L
        var defaultSampleSize = 0
        var dataOffset: Int? = null

        while (offset + 8 <= traf.size) {
            val boxSize = readU32(traf, offset)
            if (boxSize < 8L) break
            val end = (offset + boxSize.toInt()).coerceAtMost(traf.size)
            val type = fourcc(traf, offset + 4)
            val body = traf.copyOfRange(offset + 8, end)
            when (type) {
                "tfhd" -> {
                    if (body.size >= 8) {
                        val flags = ((body[1].toInt() and 0xff) shl 16) or
                            ((body[2].toInt() and 0xff) shl 8) or
                            (body[3].toInt() and 0xff)
                        var p = 8 // version/flags + track_ID
                        if (flags and 0x000001 != 0) p += 8 // base_data_offset
                        if (flags and 0x000002 != 0) p += 4 // sample_description_index
                        if (flags and 0x000008 != 0 && p + 4 <= body.size) {
                            defaultSampleDuration = readU32(body, p)
                            p += 4
                        }
                        if (flags and 0x000010 != 0 && p + 4 <= body.size) {
                            defaultSampleSize = readU32(body, p).toInt()
                            p += 4
                        }
                    }
                }
                "trun" -> {
                    val trunMeta = parseTrun(body, defaultSampleDuration, defaultSampleSize, sampleSizes, sampleDurations)
                    if (trunMeta.dataOffset != null) dataOffset = trunMeta.dataOffset
                    if (trunMeta.defaultDuration > 0) defaultSampleDuration = trunMeta.defaultDuration
                }
            }
            offset = end
        }
        return MoofMeta(
            defaultSampleDuration = defaultSampleDuration,
            dataOffsetRelativeToMoof = dataOffset,
        )
    }

    private data class TrunMeta(val dataOffset: Int? = null, val defaultDuration: Long = 0L)

    private fun parseTrun(
        body: ByteArray,
        defaultSampleDuration: Long,
        defaultSampleSize: Int,
        sampleSizes: MutableList<Int>,
        sampleDurations: MutableList<Long>,
    ): TrunMeta {
        if (body.size < 8) return TrunMeta()
        val version = body[0].toInt() and 0xff
        val flags = ((body[1].toInt() and 0xff) shl 16) or
            ((body[2].toInt() and 0xff) shl 8) or
            (body[3].toInt() and 0xff)
        val sampleCount = readU32(body, 4).toInt()
        if (sampleCount <= 0 || sampleCount > 1_000_000) return TrunMeta()
        var p = 8
        var dataOffset: Int? = null
        if (flags and 0x000001 != 0) {
            if (p + 4 > body.size) return TrunMeta()
            dataOffset = readU32(body, p).toInt()
            p += 4
        }
        if (flags and 0x000004 != 0) {
            if (p + 4 > body.size) return TrunMeta()
            p += 4 // first_sample_flags
        }

        val hasDuration = flags and 0x000100 != 0
        val hasSize = flags and 0x000200 != 0
        val hasFlags = flags and 0x000400 != 0
        val hasCto = flags and 0x000800 != 0

        // If neither size-per-sample nor default size, cannot split.
        if (!hasSize && defaultSampleSize <= 0) {
            Log.w(TAG, "trun has no sample sizes sampleCount=$sampleCount flags=0x${flags.toString(16)}")
            return TrunMeta(dataOffset = dataOffset, defaultDuration = defaultSampleDuration)
        }

        for (i in 0 until sampleCount) {
            var duration = defaultSampleDuration
            var size = defaultSampleSize
            if (hasDuration) {
                if (p + 4 > body.size) break
                duration = readU32(body, p)
                p += 4
            }
            if (hasSize) {
                if (p + 4 > body.size) break
                size = readU32(body, p).toInt()
                p += 4
            }
            if (hasFlags) {
                if (p + 4 > body.size) break
                p += 4
            }
            if (hasCto) {
                if (p + 4 > body.size) break
                p += 4
            }
            if (size > 0) {
                sampleSizes.add(size)
                sampleDurations.add(duration)
            }
        }
        return TrunMeta(dataOffset = dataOffset, defaultDuration = defaultSampleDuration)
    }

    /**
     * Parse init segment moov: mdhd timescale, trex defaults, stsd alac cookie.
     */
    private fun parseInitMoov(moov: ByteArray): InitMeta {
        var timescale = 0L
        var defaultSampleDuration = 0L
        var trackInfo: TrackInfo? = null
        walkBoxes(moov) { type, body ->
            when (type) {
                "mdhd" -> {
                    if (body.size >= 20) {
                        val version = body[0].toInt() and 0xff
                        if (version == 1 && body.size >= 32) {
                            timescale = readU32(body, 20)
                        } else if (version == 0) {
                            timescale = readU32(body, 12)
                        }
                    }
                }
                "trex" -> {
                    // version/flags(4)+track_ID(4)+default_sample_description_index(4)
                    // +default_sample_duration(4)+default_sample_size(4)+default_sample_flags(4)
                    if (body.size >= 24) {
                        val d = readU32(body, 12)
                        if (d > 0) defaultSampleDuration = d
                    }
                }
                "stsd" -> {
                    trackInfo = parseStsd(body) ?: trackInfo
                }
            }
            // recurse into containers
            if (type == "trak" || type == "mdia" || type == "minf" || type == "stbl" ||
                type == "mvex" || type == "udta"
            ) {
                // walkBoxes is flat only; nested handled below
            }
        }
        // Nested walk for stsd/mdhd/trex deeper
        if (trackInfo == null || timescale == 0L || defaultSampleDuration == 0L) {
            deepWalk(moov) { type, body ->
                when (type) {
                    "mdhd" -> {
                        if (timescale == 0L && body.size >= 20) {
                            val version = body[0].toInt() and 0xff
                            timescale = if (version == 1 && body.size >= 32) {
                                readU32(body, 20)
                            } else {
                                readU32(body, 12)
                            }
                        }
                    }
                    "trex" -> {
                        if (defaultSampleDuration == 0L && body.size >= 24) {
                            val d = readU32(body, 12)
                            if (d > 0) defaultSampleDuration = d
                        }
                    }
                    "stsd" -> {
                        if (trackInfo == null) trackInfo = parseStsd(body)
                    }
                }
            }
        }
        return InitMeta(trackInfo = trackInfo, timescale = timescale, defaultSampleDuration = defaultSampleDuration)
    }

    private fun parseStsd(body: ByteArray): TrackInfo? {
        // fullbox version/flags(4)+entry_count(4)+entries
        if (body.size < 16) return null
        val entryCount = readU32(body, 4).toInt()
        if (entryCount <= 0) return null
        var p = 8
        var sampleRate = 44100
        var channels = 2
        var cookie: ByteArray? = null
        var mime = "audio/alac"
        for (e in 0 until entryCount) {
            if (p + 8 > body.size) break
            val entrySize = readU32(body, p).toInt()
            if (entrySize < 16 || p + entrySize > body.size) break
            val format = fourcc(body, p + 4)
            if (format == "alac" || format == "enca") {
                // AudioSampleEntry: reserved6+data_ref(2) at +8, then version fields...
                // channelcount @ +24, samplesize @ +26, samplerate 16.16 @ +32
                if (p + 36 <= body.size) {
                    val ch = ((body[p + 24].toInt() and 0xff) shl 8) or (body[p + 25].toInt() and 0xff)
                    if (ch in 1..8) channels = ch
                    val rateFixed = readU32(body, p + 32)
                    val rate = (rateFixed shr 16).toInt()
                    if (rate in 8000..384000) sampleRate = rate
                }
                // Nested boxes after AudioSampleEntry header (36 bytes for version 0)
                var np = p + 36
                val entryEnd = p + entrySize
                while (np + 8 <= entryEnd) {
                    val nSize = readU32(body, np).toInt()
                    if (nSize < 8 || np + nSize > entryEnd) break
                    val nType = fourcc(body, np + 4)
                    if (nType == "alac") {
                        // payload = version/flags + ALACSpecificConfig
                        val payloadStart = np + 8
                        val payloadEnd = np + nSize
                        if (payloadEnd - payloadStart >= 24) {
                            cookie = body.copyOfRange(payloadStart, payloadEnd)
                        }
                    }
                    np += nSize
                }
                if (format == "alac") mime = "audio/alac"
            }
            p += entrySize
        }
        if (cookie == null && sampleRate == 44100 && channels == 2 && mime == "audio/alac") {
            // still ok — writer can synthesize cookie
        }
        return TrackInfo(
            mime = mime,
            sampleRate = sampleRate,
            channelCount = channels,
            csd0 = cookie,
            durationUs = 0L,
        )
    }

    private fun walkBoxes(data: ByteArray, visitor: (String, ByteArray) -> Unit) {
        var offset = 0
        while (offset + 8 <= data.size) {
            val boxSize = readU32(data, offset).toInt()
            if (boxSize < 8) break
            val end = (offset + boxSize).coerceAtMost(data.size)
            val type = fourcc(data, offset + 4)
            val body = data.copyOfRange(offset + 8, end)
            visitor(type, body)
            offset = end
        }
    }

    private fun deepWalk(data: ByteArray, visitor: (String, ByteArray) -> Unit) {
        var offset = 0
        while (offset + 8 <= data.size) {
            val boxSize = readU32(data, offset).toInt()
            if (boxSize < 8) break
            val end = (offset + boxSize).coerceAtMost(data.size)
            val type = fourcc(data, offset + 4)
            val body = data.copyOfRange(offset + 8, end)
            visitor(type, body)
            // Recurse into known containers
            if (type == "moov" || type == "trak" || type == "mdia" || type == "minf" ||
                type == "stbl" || type == "mvex" || type == "udta" || type == "edts"
            ) {
                deepWalk(body, visitor)
            }
            offset = end
        }
    }

    private fun readU32(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return 0L
        return ((data[offset].toInt() and 0xff).toLong() shl 24) or
            ((data[offset + 1].toInt() and 0xff).toLong() shl 16) or
            ((data[offset + 2].toInt() and 0xff).toLong() shl 8) or
            (data[offset + 3].toInt() and 0xff).toLong()
    }

    private fun fourcc(data: ByteArray, offset: Int): String {
        if (offset + 4 > data.size) return "????"
        return String(data, offset, 4, Charsets.ISO_8859_1)
    }
}
