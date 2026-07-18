package com.neurax08.xposed.appledecryptor.download

import android.util.Log
import java.nio.ByteBuffer

/**
 * Split an encrypted fMP4 fragment into individual media samples using moof/trun
 * sample sizes when available. Falls back to treating whole mdat as one sample.
 *
 * FairPlay sample decryption matches the original socket path (one decrypt call per sample).
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
    )

    fun split(fmp4Data: ByteArray, segmentDurationSec: Float = 0f): SplitResult {
        var offset = 0
        val sampleSizes = mutableListOf<Int>()
        val sampleDurations = mutableListOf<Long>() // in timescale units if known
        var timescale = 0L
        var defaultSampleDuration = 0L
        var trackInfo: TrackInfo? = null
        val mdatPayloads = mutableListOf<ByteArray>()

        while (offset + 8 <= fmp4Data.size) {
            val boxSize = readU32(fmp4Data, offset)
            if (boxSize < 8L) break
            val end = (offset + boxSize.toInt()).coerceAtMost(fmp4Data.size)
            if (end <= offset + 8) break
            val type = fourcc(fmp4Data, offset + 4)
            val body = fmp4Data.copyOfRange(offset + 8, end)

            when (type) {
                "moov" -> {
                    // Init segment may carry track info; rare for media fragments.
                    trackInfo = parseTrackInfoFromMoov(body) ?: trackInfo
                }
                "moof" -> parseMoof(body, sampleSizes, sampleDurations).also { meta ->
                    if (meta.timescale > 0) timescale = meta.timescale
                    if (meta.defaultSampleDuration > 0) defaultSampleDuration = meta.defaultSampleDuration
                }
                "mdat" -> mdatPayloads.add(body)
            }
            offset = end
        }

        val mdat = if (mdatPayloads.size == 1) {
            mdatPayloads[0]
        } else if (mdatPayloads.isEmpty()) {
            ByteArray(0)
        } else {
            // Concatenate multiple mdat boxes (uncommon).
            val total = mdatPayloads.sumOf { it.size }
            val merged = ByteArray(total)
            var pos = 0
            for (part in mdatPayloads) {
                System.arraycopy(part, 0, merged, pos, part.size)
                pos += part.size
            }
            merged
        }

        if (mdat.isEmpty()) {
            Log.w(TAG, "fmp4 split: empty mdat size=${fmp4Data.size}")
            return SplitResult(emptyList(), trackInfo)
        }

        val samples = mutableListOf<EncryptedSample>()
        if (sampleSizes.isNotEmpty() && sampleSizes.sum() <= mdat.size) {
            var cursor = 0
            val count = sampleSizes.size
            val segmentDurationUs = (segmentDurationSec * 1_000_000f).toLong()
            for (i in 0 until count) {
                val size = sampleSizes[i]
                if (cursor + size > mdat.size) break
                val payload = mdat.copyOfRange(cursor, cursor + size)
                cursor += size

                val durationUs = when {
                    timescale > 0 && i < sampleDurations.size && sampleDurations[i] > 0 ->
                        sampleDurations[i] * 1_000_000L / timescale
                    timescale > 0 && defaultSampleDuration > 0 ->
                        defaultSampleDuration * 1_000_000L / timescale
                    count > 0 && segmentDurationUs > 0 ->
                        segmentDurationUs / count
                    else -> 0L
                }

                samples.add(
                    EncryptedSample(
                        data = payload,
                        durationUs = durationUs,
                        isKeyFrame = true,
                    )
                )
            }
            // Leftover bytes after trun sizes: treat as final sample if any.
            if (cursor < mdat.size && samples.isNotEmpty()) {
                Log.w(TAG, "fmp4 split: ${mdat.size - cursor} leftover mdat bytes after trun sizes")
            }
            Log.i(TAG, "fmp4 split: ${samples.size} samples from trun (mdat=${mdat.size})")
        } else {
            // No usable trun sizes — whole mdat as one sample (segment-level decrypt fallback).
            val durationUs = (segmentDurationSec * 1_000_000f).toLong()
            samples.add(EncryptedSample(data = mdat, durationUs = durationUs, isKeyFrame = true))
            Log.i(TAG, "fmp4 split: single sample fallback mdat=${mdat.size} sizes=${sampleSizes.size}")
        }

        return SplitResult(samples = samples, trackInfo = trackInfo)
    }

    private data class MoofMeta(
        val timescale: Long = 0L,
        val defaultSampleDuration: Long = 0L,
    )

    private fun parseMoof(
        moof: ByteArray,
        sampleSizes: MutableList<Int>,
        sampleDurations: MutableList<Long>,
    ): MoofMeta {
        var offset = 0
        var timescale = 0L
        var defaultSampleDuration = 0L
        while (offset + 8 <= moof.size) {
            val boxSize = readU32(moof, offset)
            if (boxSize < 8L) break
            val end = (offset + boxSize.toInt()).coerceAtMost(moof.size)
            val type = fourcc(moof, offset + 4)
            val body = moof.copyOfRange(offset + 8, end)
            if (type == "traf") {
                val meta = parseTraf(body, sampleSizes, sampleDurations)
                if (meta.timescale > 0) timescale = meta.timescale
                if (meta.defaultSampleDuration > 0) defaultSampleDuration = meta.defaultSampleDuration
            }
            offset = end
        }
        return MoofMeta(timescale = timescale, defaultSampleDuration = defaultSampleDuration)
    }

    private fun parseTraf(
        traf: ByteArray,
        sampleSizes: MutableList<Int>,
        sampleDurations: MutableList<Long>,
    ): MoofMeta {
        var offset = 0
        var defaultSampleDuration = 0L
        var defaultSampleSize = 0
        var timescale = 0L

        while (offset + 8 <= traf.size) {
            val boxSize = readU32(traf, offset)
            if (boxSize < 8L) break
            val end = (offset + boxSize.toInt()).coerceAtMost(traf.size)
            val type = fourcc(traf, offset + 4)
            val body = traf.copyOfRange(offset + 8, end)
            when (type) {
                "tfhd" -> {
                    // fullbox: version(1)+flags(3) already stripped? body starts after size/type, includes version+flags
                    if (body.size >= 8) {
                        val flags = ((body[1].toInt() and 0xff) shl 16) or
                            ((body[2].toInt() and 0xff) shl 8) or
                            (body[3].toInt() and 0xff)
                        var p = 8 // after version/flags + track_ID
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
                "tfdt" -> {
                    // optional, ignore
                }
                "trun" -> {
                    parseTrun(body, defaultSampleDuration, defaultSampleSize, sampleSizes, sampleDurations)
                }
            }
            offset = end
        }
        return MoofMeta(timescale = timescale, defaultSampleDuration = defaultSampleDuration)
    }

    private fun parseTrun(
        body: ByteArray,
        defaultSampleDuration: Long,
        defaultSampleSize: Int,
        sampleSizes: MutableList<Int>,
        sampleDurations: MutableList<Long>,
    ) {
        if (body.size < 8) return
        val version = body[0].toInt() and 0xff
        val flags = ((body[1].toInt() and 0xff) shl 16) or
            ((body[2].toInt() and 0xff) shl 8) or
            (body[3].toInt() and 0xff)
        val sampleCount = readU32(body, 4).toInt()
        var p = 8
        if (flags and 0x000001 != 0) p += 4 // data_offset
        if (flags and 0x000004 != 0) p += 4 // first_sample_flags

        val hasDuration = flags and 0x000100 != 0
        val hasSize = flags and 0x000200 != 0
        val hasFlags = flags and 0x000400 != 0
        val hasCto = flags and 0x000800 != 0

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
                val ctoSize = if (version == 0) 4 else 4
                if (p + ctoSize > body.size) break
                p += ctoSize
            }
            if (size > 0) {
                sampleSizes.add(size)
                sampleDurations.add(duration)
            }
        }
    }

    private fun parseTrackInfoFromMoov(moov: ByteArray): TrackInfo? {
        // Minimal: look for stsd alac entry sample rate / channels / magic cookie via AlacFrameExtractor helpers
        // Rebuild a fake ftyp+moov for extractor path is heavy; keep simple defaults.
        return null
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
