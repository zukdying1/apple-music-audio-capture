package com.neurax08.xposed.appledecryptor.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HlsSegment(
    val url: String,
    val duration: Float,
    val sequenceNumber: Int
)

data class HlsPlaylist(
    val segments: List<HlsSegment>,
    val keyUri: String?,
    val isMaster: Boolean,
    val variantUrl: String?,
)

class HlsDownloader {
    companion object {
        private const val TAG = "AppleDecryptor"
        private const val TIMEOUT_SECONDS = 30L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    suspend fun fetchAndParseM3U8(m3u8Url: String): HlsPlaylist = withContext(Dispatchers.IO) {
        val body = downloadText(m3u8Url)
        parseM3U8(body, m3u8Url)
    }

    private suspend fun downloadText(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AppleCoreMedia/1.0.0.20 (iPhone; U; CPU OS 17_4 like Mac OS X; en_us)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            response.body?.string() ?: throw IOException("Empty response body for $url")
        }
    }

    suspend fun downloadSegment(url: String): ByteArray = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AppleCoreMedia/1.0.0.20 (iPhone; U; CPU OS 17_4 like Mac OS X; en_us)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for segment $url")
            }
            response.body?.bytes() ?: throw IOException("Empty segment body for $url")
        }
    }

    private fun parseM3U8(content: String, baseUrl: String): HlsPlaylist {
        val lines = content.lines()
        var expectingVariant = false
        var isMaster = false
        var variantUrl: String? = null
        val segments = mutableListOf<HlsSegment>()
        var keyUri: String? = null
        var currentDuration = 0f
        var sequenceNumber = 0

        val basePath = baseUrl.substringBeforeLast('/')

        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-STREAM-INF") -> {
                    isMaster = true
                    expectingVariant = true
                }
                expectingVariant && trimmed.isNotBlank() && !trimmed.startsWith("#") -> {
                    variantUrl = if (trimmed.startsWith("http")) {
                        trimmed
                    } else {
                        "$basePath/$trimmed"
                    }
                    expectingVariant = false
                }
                trimmed.startsWith("#EXTINF:") -> {
                    val durationStr = trimmed
                        .removePrefix("#EXTINF:")
                        .substringBefore(',')
                        .trim()
                    currentDuration = durationStr.toFloatOrNull() ?: 0f
                }
                trimmed.startsWith("#EXT-X-KEY:") -> {
                    val uriMatch = Regex("URI=\"([^\"]+)\"").find(trimmed)
                    if (uriMatch != null) {
                        val raw = uriMatch.groupValues[1]
                        keyUri = if (raw.startsWith("http") || raw.startsWith("skd://")) {
                            raw
                        } else {
                            "$basePath/$raw"
                        }
                    }
                }
                trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    sequenceNumber = trimmed.removePrefix("#EXT-X-MEDIA-SEQUENCE:").trim().toIntOrNull() ?: 0
                }
                !isMaster && trimmed.isNotBlank() && !trimmed.startsWith("#") -> {
                    val segmentUrl = if (trimmed.startsWith("http")) trimmed else "$basePath/$trimmed"
                    segments.add(
                        HlsSegment(
                            url = segmentUrl,
                            duration = currentDuration,
                            sequenceNumber = sequenceNumber,
                        )
                    )
                    sequenceNumber++
                }
            }
        }

        if (variantUrl != null) {
            Log.d(TAG, "M3U8 is master playlist, resolving variant: $variantUrl")
        }

        return HlsPlaylist(
            segments = segments,
            keyUri = keyUri,
            isMaster = isMaster || variantUrl != null,
            variantUrl = variantUrl,
        )
    }
}