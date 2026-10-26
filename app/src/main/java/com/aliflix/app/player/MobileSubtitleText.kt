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
    val code = canonicalSubtitleLanguageCode(language)
    val detected = detectedLatinSubtitleLanguage(sample)
    if (detected != null && detected != code) return false
    fun fraction(predicate: (Char) -> Boolean) = letters.count(predicate).toDouble() / letters.length
    return when (code) {
        "EN" -> letters.count { it in 'A'..'Z' || it in 'a'..'z' }.toDouble() / letters.length > 0.85
        "AR" -> letters.count { it.isArabicLetter() }.toDouble() / letters.length > 0.5 &&
            !looksPersianOrUrdu(sample)
        "FA", "UR" -> letters.count { it.isArabicLetter() }.toDouble() / letters.length > 0.5
        "RU", "UK" -> fraction { it in '\u0400'..'\u052f' } > .5
        "EL" -> fraction { it in '\u0370'..'\u03ff' } > .5
        "HE" -> fraction { it in '\u0590'..'\u05ff' } > .5
        "HI" -> fraction { it in '\u0900'..'\u097f' } > .5
        "BN" -> fraction { it in '\u0980'..'\u09ff' } > .5
        "TH" -> fraction { it in '\u0e00'..'\u0e7f' } > .5
        "JA" -> fraction { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' } > .5
        "ZH" -> fraction { it in '\u4e00'..'\u9fff' } > .5 && sample.none { it in '\u3040'..'\u30ff' }
        "KO" -> fraction { it in '\uac00'..'\ud7af' || it in '\u1100'..'\u11ff' } > .5
        "DE", "ES", "FR", "IT", "PT", "TR", "NL", "PL", "SV", "DA", "NO", "FI", "RO", "CS", "HU", "VI", "ID" ->
            fraction { it.code < 0x0250 } > .8
        else -> true
    }
}

private fun detectedLatinSubtitleLanguage(sample: String): String? {
    val words = sample.lowercase(java.util.Locale.ROOT).split(Regex("[^\\p{L}]+")).toSet()
    val markers = mapOf(
        "EN" to setOf("the", "you", "your", "are", "what", "with", "have", "this", "that", "we"),
        "ES" to setOf("estás", "estoy", "quiero", "tienes", "pero", "aquí", "gracias", "usted", "nosotros", "nosotras", "tenemos", "ellos", "ellas", "puedes", "porque", "volver"),
        "FR" to setOf("vous", "nous", "avec", "pour", "cette", "être", "suis", "mais", "bonjour", "merci"),
        "DE" to setOf("ich", "nicht", "dich", "wir", "ist", "sind", "haben", "hier", "aber", "danke"),
        "IT" to setOf("sono", "siamo", "questo", "voglio", "grazie", "perché", "cosa", "della", "anche"),
        "PT" to setOf("você", "vocês", "não", "estou", "obrigado", "obrigada", "isso", "então"),
        "TR" to setOf("ben", "sen", "için", "değil", "evet", "hayır", "neden", "teşekkür"),
        "NL" to setOf("jij", "jouw", "niet", "zijn", "hebben", "maar", "dank", "waarom"),
    )
    val scores = markers.mapValues { (_, values) -> values.count { it in words } }.entries.sortedByDescending { it.value }
    return scores.firstOrNull()?.takeIf { it.value >= 3 && it.value - (scores.getOrNull(1)?.value ?: 0) >= 2 }?.key
}

private fun looksPersianOrUrdu(text: String): Boolean {
    // These languages share Arabic script. Require repeated distinctive letters or words,
    // so an occasional Persian proper name in an Arabic translation remains valid.
    val normalized = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
    val words = normalized.split(Regex("[^\\p{L}]+"))
    val markers = setOf("است", "هست", "هستم", "نیست", "برای", "این", "آن", "شما", "منو", "میشه", "بود", "ہے", "ہیں", "اور", "کیا")
    val markerCount = words.count { it in markers }
    val distinctiveWords = words.count { word -> word.any { it in "پچژگکںےٹڈڑ" } }
    return markerCount >= 3 || distinctiveWords >= 4
}

/** Reject explicitly wrong episodes, then rank matching release tokens without guessing offsets. */
internal fun mobileSubtitleCandidates(
    tracks: List<SubtitleTrack>, language: String, season: Int?, episode: Int?, releaseHint: String = "",
): List<SubtitleTrack> {
    val episodePattern = Regex("(?i)(?:s(\\d{1,2})[ ._-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))")
    val hint = releaseHint.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 3 && it !in setOf("https", "com", "m3u8", "mp4", "video", "stream") }.toSet()
    fun score(track: SubtitleTrack) = (track.releaseName + " " + track.fileName).lowercase()
        .split(Regex("[^a-z0-9]+" )).distinct().count { it in hint }
    return tracks.filter { canonicalSubtitleLanguageCode(it.languageCode) == canonicalSubtitleLanguageCode(language) }
        .filter { track -> canonicalSubtitleLanguageCode(language) != "AR" ||
            !Regex("(?i)(?:^|[ ._\\[\\]()-])(?:persian|farsi|fas|per|urdu|fa|ur)(?:$|[ ._\\[\\]()-])")
                .containsMatchIn("${track.languageName} ${track.fileName} ${track.releaseName}") }
        .filter { track ->
            val identity = episodePattern.find(track.fileName) ?: episodePattern.find(track.releaseName)
            season == null || episode == null || identity == null ||
                (identity.groupValues[1].ifEmpty { identity.groupValues[3] }.toInt() == season &&
                    identity.groupValues[2].ifEmpty { identity.groupValues[4] }.toInt() == episode)
        }.sortedWith(compareByDescending<SubtitleTrack> { it.hashMatched }.thenByDescending(::score).thenBy { it.hearingImpaired }
            .thenBy { it.format.lowercase() !in setOf("srt", "vtt") })
}

internal fun normalizeMobileSubtitleTracks(tracks: List<SubtitleTrack>): List<SubtitleTrack> = tracks.map { track ->
    val code = canonicalSubtitleLanguageCode(track.languageCode).let { raw ->
        if (raw in setOf("SUB", "UNKNOWN", "UND")) canonicalSubtitleLanguageCode(track.languageName) else raw
    }
    val namedCode = canonicalSubtitleLanguageCode(track.languageName)
    val knownCodes = com.aliflix.app.model.SubtitleLanguage.entries.map { it.code }
    val safeCode = if (code in knownCodes && namedCode in knownCodes && code != namedCode) "UND" else code
    val name = com.aliflix.app.model.SubtitleLanguage.entries.firstOrNull { it.code == code }?.displayName
        ?: java.util.Locale.forLanguageTag(code.lowercase()).getDisplayLanguage(java.util.Locale.ENGLISH).takeIf { it.isNotBlank() }
        ?: track.languageName
    track.copy(languageCode = safeCode, languageName = if (safeCode == "UND") track.languageName else name)
}.groupBy { it.downloadToken }.values.map { copies -> copies.firstOrNull { it.hashMatched } ?: copies.first() }
