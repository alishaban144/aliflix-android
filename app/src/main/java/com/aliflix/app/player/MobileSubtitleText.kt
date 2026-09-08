package com.aliflix.app.player

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Phone-only decoding. JSON and the TV subtitle pipeline keep their existing decoder. */
internal fun decodeMobileSubtitleText(bytes: ByteArray, language: String): String {
    if (bytes.take(2) == listOf(0xff.toByte(), 0xfe.toByte())) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    if (bytes.take(2) == listOf(0xfe.toByte(), 0xff.toByte())) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    val code = canonicalSubtitleLanguageCode(language)
    val legacy = when (code) {
        "AR", "FA", "UR" -> "windows-1256"
        "RU", "UK", "BG" -> "windows-1251"
        "EL" -> "windows-1253"
        "HE" -> "windows-1255"
        "TR" -> "windows-1254"
        "PL", "CS", "RO", "HU" -> "windows-1250"
        "TH" -> "windows-874"
        "JA" -> "Shift_JIS"
        "ZH" -> "GB18030"
        "KO" -> "EUC-KR"
        else -> "windows-1252"
    }
    val decoded = strictDecode(bytes, Charsets.UTF_8)
        ?: strictDecode(bytes, Charset.forName(legacy))
        ?: (if (code == "EN") strictDecode(bytes, Charset.forName("windows-1256")) else null)
        ?: throw SubtitleException("This subtitle file has damaged text. Choose another version.")
    var text = decoded.removePrefix("\uFEFF")
    // Repair UTF-8 saved after an incorrect Latin-1/Windows-1252 decode, but only
    // when a lossless round trip removes recognizable mojibake sequences.
    repeat(2) {
        val bad = mojibakeCount(text)
        if (bad > 0) {
            val repaired = listOf("windows-1252", "ISO-8859-1").mapNotNull { name ->
                val charset = Charset.forName(name)
                if (!charset.newEncoder().canEncode(text)) null else strictDecode(text.toByteArray(charset), Charsets.UTF_8)
            }.minByOrNull(::mojibakeCount)
            if (repaired != null && mojibakeCount(repaired) < bad) text = repaired
        }
    }
    // Arabic CP1256 is often mislabeled English and may already be wrapped in UTF-8.
    // Require a dense run of impossible Latin accents before considering this repair.
    val letters = text.count(Char::isLetter).coerceAtLeast(1)
    val suspiciousLatin = text.count { it in '\u00c0'..'\u00ff' }
    if (suspiciousLatin >= 6 && suspiciousLatin.toDouble() / letters > 0.32 && code in setOf("AR", "FA", "UR", "EN")) {
        val cp1252 = Charset.forName("windows-1252")
        if (cp1252.newEncoder().canEncode(text)) {
            val arabic = strictDecode(text.toByteArray(cp1252), Charset.forName("windows-1256"))
            if (arabic != null && arabic.count { it.isArabicLetter() }.toDouble() / letters > 0.5 &&
                (code in setOf("AR", "FA", "UR", "EN"))) text = arabic
        }
        if (text.count { it.isArabicLetter() }.toDouble() / letters <= 0.5) {
            throw SubtitleException("This subtitle file has damaged text. Choose another version.")
        }
    }
    if ('\uFFFD' in text || text.any { it in '\u0080'..'\u009f' }) {
        throw SubtitleException("This subtitle file has damaged text. Choose another version.")
    }
    return text
}

private fun strictDecode(bytes: ByteArray, charset: Charset): String? = runCatching {
    charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}.getOrNull()

private fun mojibakeCount(text: String) = Regex("[ÃÂØÙÐÑ][\\u0080-\\u00bf]|â[€™œ]|ï»¿").findAll(text).count()
private fun Char.isArabicLetter() = isLetter() && (this in '\u0600'..'\u06ff' || this in '\ufb50'..'\ufeff')

internal fun subtitleLanguageIsPlausible(cues: List<SubtitleCue>, language: String): Boolean {
    val sample = cues.take(80).joinToString(" ") { it.text }.take(12_000)
    if (sample.isBlank() || '\uFFFD' in sample || mojibakeCount(sample) > 2) return false
    val letters = sample.filter(Char::isLetter)
    if (letters.isEmpty()) return false
    return when (canonicalSubtitleLanguageCode(language)) {
        "EN" -> letters.count { it in 'A'..'Z' || it in 'a'..'z' }.toDouble() / letters.length > 0.85
        "AR", "FA", "UR" -> letters.count { it.isArabicLetter() }.toDouble() / letters.length > 0.5
        else -> true
    }
}

/** Reject explicitly wrong episodes, then rank matching release tokens without guessing offsets. */
internal fun mobileSubtitleCandidates(
    tracks: List<SubtitleTrack>, language: String, season: Int?, episode: Int?, releaseHint: String = "",
): List<SubtitleTrack> {
    val episodePattern = Regex("(?i)s(\\d{1,2})[ ._-]*e(\\d{1,3})")
    val hint = releaseHint.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in setOf("https", "com", "m3u8", "mp4", "video", "stream") }.toSet()
    fun score(track: SubtitleTrack) = (track.releaseName + " " + track.fileName).lowercase()
        .split(Regex("[^a-z0-9]+" )).distinct().count { it in hint }
    return tracks.filter { canonicalSubtitleLanguageCode(it.languageCode) == canonicalSubtitleLanguageCode(language) }
        .filter { track ->
            val identity = episodePattern.find(track.fileName) ?: episodePattern.find(track.releaseName)
            season == null || episode == null || identity == null ||
                (identity.groupValues[1].toInt() == season && identity.groupValues[2].toInt() == episode)
        }.sortedWith(compareByDescending<SubtitleTrack> { it.hashMatched }.thenByDescending(::score).thenBy { it.hearingImpaired }
            .thenBy { it.format.lowercase() !in setOf("srt", "vtt") })
}

internal fun normalizeMobileSubtitleTracks(tracks: List<SubtitleTrack>): List<SubtitleTrack> = tracks.map { track ->
    val code = canonicalSubtitleLanguageCode(track.languageCode).let { raw ->
        if (raw in setOf("SUB", "UNKNOWN", "UND")) canonicalSubtitleLanguageCode(track.languageName) else raw
    }
    val name = com.aliflix.app.model.SubtitleLanguage.entries.firstOrNull { it.code == code }?.displayName
        ?: java.util.Locale.forLanguageTag(code.lowercase()).getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { it.isNotBlank() }
        ?: track.languageName
    track.copy(languageCode = code, languageName = name)
}.groupBy { it.downloadToken }.values.map { copies -> copies.firstOrNull { it.hashMatched } ?: copies.first() }
