package com.aliflix.app.player

import org.json.JSONArray

internal fun subtitleCuesJson(cues: List<SubtitleCue>): String = JSONArray().apply {
    cues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) }
}.toString()

internal fun correctedMobileVtt(originals: List<SubtitleCue>, correction: AudioSubtitleCorrection?, delay: Double): String {
    val corrected = correction?.apply(originals) ?: originals
    return nativeSubtitlesVtt(subtitleCuesJson(corrected.map { it.copy(text = mobileCaptionText(it.text)) }), delay)
}
