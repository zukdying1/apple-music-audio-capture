package com.neurax08.xposed.appledecryptor.download

import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.nio.ByteBuffer

data class AudioSample(
    val data: ByteBuffer,
    val size: Int,
    val presentationTimeUs: Long,
    val durationUs: Long,
    val isKeyFrame: Boolean = true,
)

data class TrackInfo(
    val mime: String,
    val sampleRate: Int,
    val channelCount: Int,
    val csd0: ByteArray?, // codec specific data (ALAC magic cookie)
    val durationUs: Long,
    val bitrate: Int = 0,
)

class AlacFrameExtractor {
    companion object {
        private const val TAG = "AppleDecryptor"
        private const val ALAC_MIME = "audio/alac"

        fun isAlacMime(mime: String): Boolean = mime.equals(ALAC_MIME, ignoreCase = true)

        fun isAudioMime(mime: String): Boolean = mime.startsWith("audio/")

        fun readUint32(data: ByteArray, offset: Int): Long {
            if (offset + 4 > data.size) return 0L
            return ((data[offset].toInt() and 0xFF).toLong() shl 24) or
                ((data[offset + 1].toInt() and 0xFF).toLong() shl 16) or
                ((data[offset + 2].toInt() and 0xFF).toLong() shl 8) or
                (data[offset + 3].toInt() and 0xFF).toLong()
        }

        fun readUint16(data: ByteArray, offset: Int): Int {
            if (offset + 2 > data.size) return 0
            return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
        }
    }

    data class ExtractionResult(
        val samples: List<AudioSample>,
        val trackInfo: TrackInfo?,
        val success: Boolean,
    )

    private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * Extract ALAC audio samples from a decrypted fMP4 segment using MediaExtractor.
     * Falls back to manual ISOBMFF parsing if MediaExtractor is unavailable.
     */
    fun extractSamplesFromFmp4(
        fmp4Data: ByteArray,
        trackInfo: TrackInfo? = null,
    ): ExtractionResult {
        val mediaExtractorResult = tryExtractWithMediaExtractor(fmp4Data)
        if (mediaExtractorResult != null) {
            return mediaExtractorResult
        }
        return extractFromFileFormat(fmp4Data, trackInfo)
    }

    private fun tryExtractWithMediaExtractor(fmp4Data: ByteArray): ExtractionResult? {
        var tempFile: java.io.File? = null
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor()
            tempFile = java.io.File.createTempFile("alac_", ".mp4")
            tempFile.writeBytes(fmp4Data)
            extractor.setDataSource(tempFile.absolutePath)

            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                return null
            }

            var audioTrackIndex = -1
            var parsedTrackInfo: TrackInfo? = null

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (isAudioMime(mime)) {
                    audioTrackIndex = i
                    parsedTrackInfo = TrackInfo(
                        mime = mime,
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE, 44100),
                        channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2),
                        csd0 = format.getByteBuffer("csd-0")?.let { bb ->
                            val bytes = ByteArray(bb.remaining())
                            bb.duplicate().get(bytes)
                            bytes
                        },
                        durationUs = format.getLong(MediaFormat.KEY_DURATION, 0L),
                    )
                    break
                }
            }

            if (audioTrackIndex < 0 || parsedTrackInfo == null) {
                return null
            }

            extractor.selectTrack(audioTrackIndex)

            val samples = mutableListOf<AudioSample>()
            val maxBufferSize = (parsedTrackInfo.sampleRate * parsedTrackInfo.channelCount * 4)
                .coerceAtLeast(65536)
            val buffer = ByteBuffer.allocate(maxBufferSize)

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val presentationTime = extractor.sampleTime
                val flags = extractor.sampleFlags
                val data = ByteArray(sampleSize)
                buffer.position(0)
                buffer.get(data, 0, sampleSize)

                val durationUs = if (parsedTrackInfo.sampleRate > 0) {
                    // Estimate; real duration comes from moof/trun when available
                    (1024L * 1_000_000L) / parsedTrackInfo.sampleRate.toLong()
                } else {
                    0L
                }

                samples.add(
                    AudioSample(
                        data = ByteBuffer.wrap(data),
                        size = sampleSize,
                        presentationTimeUs = presentationTime,
                        durationUs = durationUs,
                        isKeyFrame = flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0,
                    )
                )

                if (!extractor.advance()) break
            }

            ExtractionResult(
                samples = samples,
                trackInfo = parsedTrackInfo,
                success = samples.isNotEmpty(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "MediaExtractor extraction failed, falling back to manual parsing", e)
            null
        } finally {
            runCatching { extractor?.release() }
            runCatching { tempFile?.delete() }
        }
    }

    private fun extractFromFileFormat(
        data: ByteArray,
        trackInfo: TrackInfo?,
    ): ExtractionResult {
        val samples = mutableListOf<AudioSample>()
        var offset = 0
        var trackInfoInternal = trackInfo
        var presentationUs = 0L

        while (offset < data.size - 8) {
            val boxSize = readUint32(data, offset)
            if (boxSize < 8L) break
            if (offset + boxSize > data.size) break

            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val boxDataEnd = (offset + boxSize.toInt()).coerceAtMost(data.size)
            val boxData = data.copyOfRange(offset + 8, boxDataEnd)

            when (boxType) {
                "mdat" -> {
                    if (boxData.isNotEmpty()) {
                        // For fMP4 segments, mdat often contains one or more contiguous ALAC frames.
                        // Without trun sample sizes we treat the whole mdat payload as one sample.
                        val durationUs = trackInfoInternal?.let {
                            if (it.sampleRate > 0) (1024L * 1_000_000L) / it.sampleRate else 0L
                        } ?: 0L
                        samples.add(
                            AudioSample(
                                data = ByteBuffer.wrap(boxData),
                                size = boxData.size,
                                presentationTimeUs = presentationUs,
                                durationUs = durationUs,
                                isKeyFrame = true,
                            )
                        )
                        presentationUs += durationUs
                    }
                }
                "moov" -> {
                    parseMoovBox(boxData)?.let { trackInfoInternal = it }
                }
            }

            offset += boxSize.toInt()
        }

        return ExtractionResult(
            samples = samples,
            trackInfo = trackInfoInternal,
            success = samples.isNotEmpty(),
        )
    }

    private fun parseMoovBox(data: ByteArray): TrackInfo? {
        var offset = 0
        var mime = ALAC_MIME
        var sampleRate = 44100
        var channelCount = 2
        var csd0: ByteArray? = null

        while (offset < data.size - 8) {
            val boxSize = readUint32(data, offset)
            if (boxSize < 8L) break
            if (offset + boxSize > data.size) break
            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val boxData = data.copyOfRange(offset + 8, (offset + boxSize.toInt()).coerceAtMost(data.size))

            if (boxType == "trak") {
                val result = parseTrakBox(boxData)
                if (result != null) {
                    mime = result.first
                    sampleRate = result.second
                    channelCount = result.third
                    csd0 = result.fourth
                }
            }
            offset += boxSize.toInt()
        }

        return TrackInfo(
            mime = mime,
            sampleRate = sampleRate,
            channelCount = channelCount,
            csd0 = csd0,
            durationUs = 0L,
        )
    }

    private fun parseTrakBox(data: ByteArray): Quad<String, Int, Int, ByteArray?>? {
        var offset = 0
        var mime = ALAC_MIME
        var sampleRate = 44100
        var channelCount = 2
        var csd0: ByteArray? = null

        while (offset < data.size - 8) {
            val boxSize = readUint32(data, offset)
            if (boxSize < 8L) break
            if (offset + boxSize > data.size) break
            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val boxData = data.copyOfRange(offset + 8, (offset + boxSize.toInt()).coerceAtMost(data.size))
            if (boxType == "mdia") {
                val result = parseMdiaBox(boxData)
                mime = result.first
                sampleRate = result.second
                channelCount = result.third
                csd0 = result.fourth
            }
            offset += boxSize.toInt()
        }
        return Quad(mime, sampleRate, channelCount, csd0)
    }

    private fun parseMdiaBox(data: ByteArray): Quad<String, Int, Int, ByteArray?> {
        var offset = 0
        var mime = ALAC_MIME
        var sampleRate = 44100
        var channelCount = 2
        var csd0: ByteArray? = null

        while (offset < data.size - 8) {
            val boxSize = readUint32(data, offset)
            if (boxSize < 8L) break
            if (offset + boxSize > data.size) break
            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val boxData = data.copyOfRange(offset + 8, (offset + boxSize.toInt()).coerceAtMost(data.size))
            if (boxType == "minf") {
                val result = parseMinfBox(boxData)
                mime = result.first
                sampleRate = result.second
                channelCount = result.third
                csd0 = result.fourth
            }
            offset += boxSize.toInt()
        }
        return Quad(mime, sampleRate, channelCount, csd0)
    }

    private fun parseMinfBox(data: ByteArray): Quad<String, Int, Int, ByteArray?> {
        var offset = 0
        var mime = ALAC_MIME
        var sampleRate = 44100
        var channelCount = 2
        var csd0: ByteArray? = null

        while (offset < data.size - 8) {
            val boxSize = readUint32(data, offset)
            if (boxSize < 8L) break
            if (offset + boxSize > data.size) break
            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val boxData = data.copyOfRange(offset + 8, (offset + boxSize.toInt()).coerceAtMost(data.size))
            if (boxType == "stbl") {
                val result = parseStblBox(boxData)
                mime = result.first
                sampleRate = result.second
                channelCount = result.third
                csd0 = result.fourth
            }
            offset += boxSize.toInt()
        }
        return Quad(mime, sampleRate, channelCount, csd0)
    }

    private fun parseStblBox(data: ByteArray): Quad<String, Int, Int, ByteArray?> {
        var offset = 0
        var mime = ALAC_MIME
        var sampleRate = 44100
        var channelCount = 2
        var csd0: ByteArray? = null

        while (offset < data.size - 8) {
            val boxSize = readUint32(data, offset)
            if (boxSize < 8L) break
            if (offset + boxSize > data.size) break
            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val boxData = data.copyOfRange(offset + 8, (offset + boxSize.toInt()).coerceAtMost(data.size))
            if (boxType == "stsd") {
                val result = parseStsdBox(boxData)
                mime = result.first
                sampleRate = result.second
                channelCount = result.third
                csd0 = result.fourth
            }
            offset += boxSize.toInt()
        }
        return Quad(mime, sampleRate, channelCount, csd0)
    }

    private fun parseStsdBox(data: ByteArray): Quad<String, Int, Int, ByteArray?> {
        if (data.size < 8) return Quad(ALAC_MIME, 44100, 2, null)

        val entryCount = readUint32(data, 4)
        if (entryCount == 0L || data.size < 16) return Quad(ALAC_MIME, 44100, 2, null)

        var offset = 8
        var mime = ALAC_MIME
        var sampleRate = 44100
        var channelCount = 2
        var csd0: ByteArray? = null

        for (i in 0 until entryCount.toInt()) {
            if (offset + 8 > data.size) break
            val entrySize = readUint32(data, offset)
            if (entrySize < 16L) break
            if (offset + entrySize > data.size) break
            val format = String(data, offset + 4, 4, Charsets.ISO_8859_1)

            // AudioSampleEntry (ISO 14496-12) layout from entry start:
            // size(4)+type(4)+reserved(6)+data_ref(2)+version(2)+rev(2)+vendor(4)
            // +channelcount(2)@+24 +samplesize(2)@+26 +... +samplerate(4)@+32 (16.16 fixed)
            // Nested codec config atoms start at +36.
            if (offset + 36 <= data.size) {
                channelCount = readUint16(data, offset + 24).coerceAtLeast(1).coerceAtMost(8)
                val sampleRateFixed = readUint32(data, offset + 32)
                val rate = (sampleRateFixed shr 16).toInt()
                if (rate in 8000..384000) sampleRate = rate
            }

            if (format == "alac") {
                mime = ALAC_MIME
                // Prefer nested 'alac' atom payload (magic cookie / csd-0).
                csd0 = parseAlacMagicCookie(data, offset + 36, entrySize.toInt() - 36)
                    ?: parseAlacMagicCookie(data, offset + 8, entrySize.toInt() - 8)
            }

            offset += entrySize.toInt()
        }

        return Quad(mime, sampleRate, channelCount, csd0)
    }

    private fun parseAlacMagicCookie(data: ByteArray, dataOffset: Int, dataSize: Int): ByteArray? {
        var offset = dataOffset
        val end = (dataOffset + dataSize).coerceAtMost(data.size)

        while (offset + 8 <= end) {
            val boxSize = readUint32(data, offset)
            if (boxSize < 8L) break
            if (offset + boxSize > end) break
            val boxType = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            val boxDataOffset = offset + 8
            val boxDataSize = (boxSize.toInt() - 8).coerceAtMost(end - boxDataOffset)

            if (boxType == "alac" && boxDataSize > 0) {
                return data.copyOfRange(boxDataOffset, boxDataOffset + boxDataSize)
            }
            offset += boxSize.toInt()
        }
        return null
    }
}
