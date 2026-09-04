package com.aliflix.app.player

import com.aliflix.app.BuildConfig
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class SubtitleTrack(
    val id: String,
    val languageCode: String,
    val languageName: String,
    val releaseName: String,
    val fileName: String,
    val hearingImpaired: Boolean,
    val format: String,
    val fps: String?,
    val downloadToken: String,
)

data class SubtitleCue(
    val startSeconds: Double,
    val endSeconds: Double,
    val text: String,
)

internal fun subtitleContentKey(selection: PlaybackSelection): String = when (selection.media.type) {
    MediaType.MOVIE -> "movie:${selection.media.id}"
    MediaType.TV -> "tv:${selection.media.id}:s${selection.seasonNumber ?: 1}:e${selection.episodeNumber ?: 1}"
}

class SubdlSubtitleRepository(
    baseUrl: String = BuildConfig.RECOMMENDATION_AI_BASE_URL,
) {
    private val baseUrl = baseUrl.trimEnd('/')

    suspend fun search(selection: PlaybackSelection): Result<List<SubtitleTrack>> = runCatching {
        withContext(Dispatchers.IO) {
            val query = buildString {
                append("$baseUrl/v3/subtitles?type=${selection.media.type.routeName}")
                append("&tmdbId=${selection.media.id}")
                if (selection.media.type == MediaType.TV) {
                    append("&season=${selection.seasonNumber ?: 1}")
                    append("&episode=${selection.episodeNumber ?: 1}")
                }
            }
            val document = JSONObject(readText(query))
            val expectedKey = subtitleContentKey(selection)
            if (document.optString("mediaKey") != expectedKey) {
                throw SubtitleException("Subtitle results did not match this title")
            }
            val tracks = document.optJSONArray("tracks") ?: return@withContext emptyList()
            (0 until tracks.length()).mapNotNull { index ->
                val item = tracks.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").trim()
                val token = item.optString("downloadToken").trim()
                if (id.isBlank() || !token.matches(Regex("[A-Za-z0-9_-]{8,900}"))) return@mapNotNull null
                SubtitleTrack(
                    id = id,
                    languageCode = item.optString("languageCode", "SUB").trim().uppercase(),
                    languageName = item.optString("languageName", "Subtitle").trim(),
                    releaseName = item.optString("releaseName", "SubDL subtitle").trim(),
                    fileName = item.optString("fileName", "subtitle.srt").trim(),
                    hearingImpaired = item.optBoolean("hearingImpaired"),
                    format = item.optString("format").trim().lowercase(),
                    fps = item.optString("fps").trim().takeIf(String::isNotBlank),
                    downloadToken = token,
                )
            }.distinctBy(SubtitleTrack::id)
        }
    }

    suspend fun download(track: SubtitleTrack): Result<List<SubtitleCue>> = runCatching {
        withContext(Dispatchers.IO) {
            val response = readBytes("$baseUrl/v3/subtitles/download/${track.downloadToken}")
            val subtitleBytes = if (response.isZip()) {
                extractSubtitleFromZip(response, track.fileName)
            } else {
                response
            }
            val decoded = decodeSubtitleText(subtitleBytes)
            val cues = if (
                track.format in setOf("ass", "ssa") ||
                track.fileName.endsWith(".ass", ignoreCase = true) ||
                track.fileName.endsWith(".ssa", ignoreCase = true) ||
                decoded.lineSequence().any { it.trim().equals("[Events]", ignoreCase = true) }
            ) {
                parseAssSubtitleCues(decoded)
            } else {
                parseTimedTextSubtitleCues(decoded)
            }
            if (cues.isEmpty()) throw SubtitleException("This subtitle file has no readable cues")
            cues
        }
    }

    private fun readText(url: String): String = decodeSubtitleText(readBytes(url))

    private fun readBytes(url: String): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json, application/zip, text/plain, text/vtt")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use(::readBounded) ?: ByteArray(0)
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(decodeSubtitleText(bytes)).optJSONObject("error")?.optString("message")
                }.getOrNull().takeUnless(String?::isNullOrBlank)
                throw SubtitleException(message ?: "Subtitles are temporarily unavailable")
            }
            return bytes
        } finally {
            connection.disconnect()
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_SUBTITLE_BYTES) throw SubtitleException("Subtitle file is too large")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 15_000
        const val MAX_SUBTITLE_BYTES = 8 * 1024 * 1024
    }
}

private class SubtitleException(message: String) : Exception(message)

private fun ByteArray.isZip(): Boolean = size >= 4 &&
    this[0] == 0x50.toByte() && this[1] == 0x4b.toByte() &&
    this[2] in listOf(0x03.toByte(), 0x05.toByte(), 0x07.toByte())

private fun extractSubtitleFromZip(bytes: ByteArray, preferredName: String): ByteArray {
    data class Entry(val name: String, val bytes: ByteArray)

    val entries = mutableListOf<Entry>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        repeat(100) {
            val entry = zip.nextEntry ?: return@use
            val name = entry.name.substringAfterLast('/').substringAfterLast('\\')
            if (!entry.isDirectory && name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXTENSIONS) {
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = zip.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_UNPACKED_SUBTITLE_BYTES) {
                        throw SubtitleException("Subtitle file is too large")
                    }
                    output.write(buffer, 0, read)
                }
                entries += Entry(name, output.toByteArray())
            }
            zip.closeEntry()
        }
    }
    if (entries.isEmpty()) throw SubtitleException("The subtitle archive has no supported text file")
    val preferredStem = preferredName.substringBeforeLast('.').lowercase()
    return entries.maxByOrNull { entry ->
        val stem = entry.name.substringBeforeLast('.').lowercase()
        commonPrefixLength(stem, preferredStem) + if (entry.name.endsWith(".srt", true)) 10 else 0
    }!!.bytes
}

private fun commonPrefixLength(left: String, right: String): Int =
    left.zip(right).takeWhile { (a, b) -> a == b }.size

private fun decodeSubtitleText(bytes: ByteArray): String {
    if (bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte()) {
        return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
        return String(bytes, 2, bytes.size - 2, StandardCharsets.UTF_16BE)
    }
    val offset = if (
        bytes.size >= 3 && bytes[0] == 0xef.toByte() &&
        bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte()
    ) 3 else 0
    val utf8 = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return runCatching { utf8.decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString() }
        .getOrElse { String(bytes, offset, bytes.size - offset, CharsetForSubtitles.windows1252) }
}

private object CharsetForSubtitles {
    val windows1252 = java.nio.charset.Charset.forName("windows-1252")
}

internal fun parseTimedTextSubtitleCues(raw: String): List<SubtitleCue> {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")
    return normalized.split(Regex("\n[ \t]*\n+"))
        .asSequence()
        .mapNotNull { block ->
            val lines = block.lines()
            val timeIndex = lines.indexOfFirst { "-->" in it }
            if (timeIndex < 0) return@mapNotNull null
            val halves = lines[timeIndex].split("-->", limit = 2)
            val start = parseSubtitleTimestamp(halves.getOrNull(0)?.trim().orEmpty()) ?: return@mapNotNull null
            val endToken = halves.getOrNull(1)?.trim()?.substringBefore(' ').orEmpty()
            val end = parseSubtitleTimestamp(endToken) ?: return@mapNotNull null
            val text = sanitizeSubtitleText(lines.drop(timeIndex + 1).joinToString("\n"))
            if (end <= start || text.isBlank()) null else SubtitleCue(start, end, text)
        }
        .sortedBy(SubtitleCue::startSeconds)
        .take(MAX_SUBTITLE_CUES)
        .toList()
}

internal fun parseAssSubtitleCues(raw: String): List<SubtitleCue> {
    var inEvents = false
    var columns = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
    val cues = mutableListOf<SubtitleCue>()
    raw.lineSequence().forEach { original ->
        val line = original.trim()
        if (line.startsWith('[')) {
            inEvents = line.equals("[Events]", ignoreCase = true)
            return@forEach
        }
        if (!inEvents) return@forEach
        if (line.startsWith("Format:", ignoreCase = true)) {
            columns = line.substringAfter(':').split(',').map { it.trim().lowercase() }
            return@forEach
        }
        if (!line.startsWith("Dialogue:", ignoreCase = true)) return@forEach
        val values = line.substringAfter(':').split(',', limit = columns.size)
        if (values.size < columns.size) return@forEach
        val start = parseAssTimestamp(values.getOrNull(columns.indexOf("start")).orEmpty()) ?: return@forEach
        val end = parseAssTimestamp(values.getOrNull(columns.indexOf("end")).orEmpty()) ?: return@forEach
        val textIndex = columns.indexOf("text")
        val text = sanitizeSubtitleText(values.getOrNull(textIndex).orEmpty().replace("\\N", "\n").replace("\\n", "\n"))
        if (end > start && text.isNotBlank()) cues += SubtitleCue(start, end, text)
    }
    return cues.sortedBy(SubtitleCue::startSeconds).take(MAX_SUBTITLE_CUES)
}

private fun parseSubtitleTimestamp(value: String): Double? {
    val cleaned = value.substringBefore(' ').replace(',', '.')
    val parts = cleaned.split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last().toDoubleOrNull() ?: return null
    val minutes = parts[parts.lastIndex - 1].toLongOrNull() ?: return null
    val hours = if (parts.size == 3) parts.first().toLongOrNull() ?: return null else 0L
    return hours * 3600.0 + minutes * 60.0 + seconds
}

private fun parseAssTimestamp(value: String): Double? = parseSubtitleTimestamp(value.trim())

private fun stripAssOverrideTags(value: String): String {
    val sanitized = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '{' && index + 1 < value.length && value[index + 1] == '\\') {
            val closingBrace = value.indexOf('}', startIndex = index + 2)
            if (closingBrace >= 0) {
                index = closingBrace + 1
                continue
            }
        }
        sanitized.append(value[index])
        index += 1
    }
    return sanitized.toString()
}

private fun sanitizeSubtitleText(value: String): String = stripAssOverrideTags(value)
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ", ignoreCase = true)
    .replace("&amp;", "&", ignoreCase = true)
    .replace("&lt;", "<", ignoreCase = true)
    .replace("&gt;", ">", ignoreCase = true)
    .replace("&quot;", "\"", ignoreCase = true)
    .replace("&#39;", "'", ignoreCase = true)
    .lineSequence()
    .map(String::trim)
    .filter(String::isNotBlank)
    .joinToString("\n")
    .trim()

private val SUPPORTED_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
private const val MAX_UNPACKED_SUBTITLE_BYTES = 4 * 1024 * 1024
private const val MAX_SUBTITLE_CUES = 6_000
