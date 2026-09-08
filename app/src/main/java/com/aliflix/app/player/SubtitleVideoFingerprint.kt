package com.aliflix.app.player

import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SubtitleVideoFingerprint(val hash: String, val size: Long, val fileName: String)

/** OpenSubtitles content hash: file length plus the first and last 64 KiB, in LE words. */
internal fun subtitleVideoHash(size: Long, first: ByteArray, last: ByteArray): String {
    require(size >= 65536 && first.size == 65536 && last.size == 65536)
    var hash = size
    for (bytes in listOf(first, last)) {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 8) hash += buffer.long
    }
    return java.lang.Long.toUnsignedString(hash, 16).padStart(16, '0')
}

/** Bounded range requests only; HLS/DASH manifests never masquerade as a whole-file hash. */
internal suspend fun subtitleVideoFingerprint(request: NativePlaybackRequest): SubtitleVideoFingerprint? = withContext(Dispatchers.IO) {
    if (request.mimeType !in setOf("video/mp4", "video/x-matroska", "video/webm")) return@withContext null
    fun range(start: Long, end: Long): Pair<ByteArray, Long>? {
        val connection = URL(request.url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 2000; connection.readTimeout = 2000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Range", "bytes=$start-$end")
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("User-Agent", request.userAgent)
            connection.setRequestProperty("Referer", request.referer)
            if (request.cookie.isNotBlank()) connection.setRequestProperty("Cookie", request.cookie)
            if (connection.responseCode != 206) return null
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(connection.getHeaderField("Content-Range").orEmpty()) ?: return null
            if (match.groupValues[1].toLong() != start || match.groupValues[2].toLong() != end) return null
            val bytes = ByteArray(65536)
            connection.inputStream.use { input ->
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) return null
                    count += read
                }
                if (input.read() != -1) return null
            }
            return bytes to match.groupValues[3].toLong()
        } finally { connection.disconnect() }
    }
    try {
        val first = range(0, 65535) ?: return@withContext null
        ensureActive()
        if (first.second < 65536) return@withContext null
        val last = range(first.second - 65536, first.second - 1) ?: return@withContext null
        if (first.second != last.second) return@withContext null
        SubtitleVideoFingerprint(subtitleVideoHash(first.second, first.first, last.first), first.second,
            URI(request.url).path.substringAfterLast('/').take(240))
    } catch (_: Exception) { ensureActive(); null }
}
