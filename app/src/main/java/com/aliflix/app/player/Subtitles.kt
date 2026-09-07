package com.aliflix.app.player

import com.aliflix.app.BuildConfig
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.SubtitleLanguage
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

internal fun preferredSubtitleTrack(
    tracks: List<SubtitleTrack>,
    language: SubtitleLanguage,
): SubtitleTrack? = tracks.asSequence()
    .filter { track ->
        canonicalSubtitleLanguageCode(track.languageCode) == language.code ||
            track.languageName.equals(language.displayName, ignoreCase = true)
    }
    .sortedWith(
        compareBy<SubtitleTrack>(SubtitleTrack::hearingImpaired)
            .thenBy { track -> track.format !in setOf("srt", "vtt") }
            .thenBy(SubtitleTrack::id),
    )
    .firstOrNull()

private fun canonicalSubtitleLanguageCode(value: String): String {
    val normalized = value.trim().substringBefore('-').uppercase()
    return SUBTITLE_LANGUAGE_ALIASES[normalized] ?: normalized
}

private val SUBTITLE_LANGUAGE_ALIASES = mapOf(
    "ENG" to "EN", "ARA" to "AR", "GER" to "DE", "DEU" to "DE",
    "SPA" to "ES", "FRE" to "FR", "FRA" to "FR", "ITA" to "IT",
    "POR" to "PT", "TUR" to "TR", "DUT" to "NL", "NLD" to "NL",
    "POL" to "PL", "RUS" to "RU", "UKR" to "UK", "PER" to "FA",
    "FAS" to "FA", "HIN" to "HI", "IND" to "ID", "CHI" to "ZH",
    "ZHO" to "ZH", "JPN" to "JA", "KOR" to "KO", "GRE" to "EL",
    "ELL" to "EL", "SWE" to "SV", "DAN" to "DA", "NOR" to "NO",
    "FIN" to "FI", "RUM" to "RO", "RON" to "RO", "CZE" to "CS",
    "CES" to "CS", "HUN" to "HU", "HEB" to "HE", "VIE" to "VI",
    "THA" to "TH", "BEN" to "BN", "URD" to "UR",
)

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
             .sortedWith(
                 compareBy<SubtitleTrack> { track ->
                     if (track.languageCode.equals("EN", ignoreCase = true) ||
                         track.languageName.equals("EN", ignoreCase = true) ||
                         track.languageName.equals("English", ignoreCase = true)) 0 else 1
                 }.thenBy { it.languageName }
             )
        }
    }

    suspend fun download(track: SubtitleTrack): Result<List<SubtitleCue>> = runCatching {
        withContext(Dispatchers.IO) {
            val directUrl = directSubtitleUrl(track.downloadToken)
            val workerUrl = "$baseUrl/v3/subtitles/download/${track.downloadToken}"
            val response = try {
                if (directUrl != null) {
                    try {
                        readBytes(directUrl)
                    } catch (_: Exception) {
                        readBytes(workerUrl)
                    }
                } else {
                    readBytes(workerUrl)
                }
            } catch (e: Exception) {
                if (directUrl != null) {
                    readBytes(directUrl)
                } else throw e
            }
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

    private fun readBytes(url: String, redirectCount: Int = 0): ByteArray {
        if (redirectCount > 5) throw SubtitleException("Too many redirects downloading subtitle")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = NETWORK_TIMEOUT_MILLIS
            readTimeout = NETWORK_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "application/json, application/zip, text/plain, text/vtt, application/octet-stream, */*")
            setRequestProperty("Accept-Encoding", "identity")
        }
        try {
            val status = connection.responseCode
            if (status in listOf(HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308)) {
                val redirectUrl = connection.getHeaderField("Location")
                if (!redirectUrl.isNullOrBlank()) {
                    val target = URL(URL(url), redirectUrl).toString()
                    return readBytes(target, redirectCount + 1)
                }
            }
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use(::readBounded) ?: ByteArray(0)
            if (status !in 200..299) {
                val message = runCatching {
                    JSONObject(decodeSubtitleText(bytes)).optJSONObject("error")?.optString("message")
                }.getOrNull().takeUnless(String?::isNullOrBlank)
                throw SubtitleException(message ?: "Subtitles are temporarily unavailable ($status)")
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

internal fun directSubtitleUrl(token: String): String? {
    if (token.startsWith("https://") || token.startsWith("http://")) return token
    if (token.startsWith("/subtitle/")) return "https://dl.subdl.com$token"
    return try {
        val normalized = token.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        val decoded = String(java.util.Base64.getDecoder().decode(padded), Charsets.UTF_8).trim()
        if (decoded.startsWith("/subtitle/") || decoded.startsWith("/")) {
            "https://dl.subdl.com" + if (decoded.startsWith("/")) decoded else "/$decoded"
        } else if (decoded.startsWith("https://") || decoded.startsWith("http://")) {
            decoded
        } else null
    } catch (_: Exception) {
        null
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
