package com.aliflix.app.player

/** Android recognition sessions can disconnect independently of media playback.
 * Unsupported models/permissions need an honest unavailable result; transient
 * session errors recover in the background without waiting on a sync tap.
 */
internal fun dialogueRecognitionCanRetry(error: Int): Boolean = error in -2..-1 || error in 1..8 || error in 10..11

internal fun dialogueRecognitionRetryDelay(error: Int, failures: Int): Long = when (error) {
    6, 7 -> 0L // A later voiced frame starts the next phrase after silence.
    10 -> 30_000L
    else -> (500L shl (failures.coerceIn(1, 4) - 1))
}

/** Audio metadata selects a local decoder, never a timing answer. The original
 * language is only a candidate for unlabeled audio; independently recognized
 * phrases must prove it. Caption language need not match spoken language. */
internal fun dialogueRecognitionLanguage(audio: String, captions: String, original: String = ""): String? {
    fun known(value: String) = value.lowercase().takeUnless { it in setOf("", "und", "unknown") }
    val candidate = known(audio) ?: known(original) ?: known(captions)?.takeIf { it in setOf("en", "eng") }
    return when (candidate?.substringBefore('-')) {
        "en", "eng" -> "en-US"
        "fr", "fra", "fre" -> "fr-FR"
        "de", "deu", "ger" -> "de-DE"
        "es", "spa" -> "es-ES"
        "it", "ita" -> "it-IT"
        "pt", "por" -> "pt-BR"
        "ja", "jpn" -> "ja-JP"
        "ko", "kor" -> "ko-KR"
        "ar", "ara" -> "ar"
        "hi", "hin" -> "hi-IN"
        "zh", "zho", "chi" -> "zh-CN"
        else -> null
    }
}
