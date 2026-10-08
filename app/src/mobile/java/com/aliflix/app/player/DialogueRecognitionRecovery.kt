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

/** Caption language can select a decoder for an unlabeled soundtrack. It cannot
 * prove its language or timing: independently matched spoken phrases must do so.
 * Explicitly labeled other-language tracks retain native presence alignment. */
internal fun dialogueRecognitionLanguage(audio: String, captions: String): String? {
    fun english(value: String) = value.lowercase().let { it == "en" || it == "eng" || it.startsWith("en-") }
    val unknown = audio.lowercase() in setOf("", "und", "unknown")
    return "en-US".takeIf { english(audio) || (unknown && english(captions)) }
}
