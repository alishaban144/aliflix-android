package com.aliflix.app.player

import org.json.JSONArray

internal fun subtitleCuesJson(cues: List<SubtitleCue>): String = JSONArray().apply {
    cues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) }
}.toString()

internal fun correctedMobileVtt(originals: List<SubtitleCue>, correction: AudioSubtitleCorrection?, delay: Double): String {
    // Apply automatic and manual timing together, before clipping at zero.
    val corrected = correction?.apply(originals, delay) ?: originals
    return nativeSubtitlesVtt(subtitleCuesJson(corrected.map { it.copy(text = mobileCaptionText(it.text)) }), if (correction == null) delay else 0.0)
}

/** Keep the manual slider and Reset intact while applying the verified clock.
 * A later manual adjustment remains relative to this synchronized presentation.
 */
internal fun AudioSubtitleCorrection.compensatingManualDelay(delay: Double): AudioSubtitleCorrection? {
    if (!delay.isFinite()) return null
    val adjusted = copy(offset = offset - delay, regions = regions.map { it.copy(offset = it.offset - delay) })
    return adjusted.takeIf { it.offset in -600.0..600.0 && it.regions.all { region -> region.offset in -600.0..600.0 } }
}
