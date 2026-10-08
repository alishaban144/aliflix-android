package com.aliflix.app.player

import kotlin.math.abs

/** Different releases in the spoken language often share uniquely identified
 * full sentences. Their complete timelines can establish an edition's frame
 * rate before matching current speech; translated files use presence alignment.
 */
internal fun matchCaptionTimelines(target: List<SubtitleCue>, reference: List<SubtitleCue>, cancelled: () -> Unit = {}): AudioSubtitleCorrection? {
    fun phrase(text: String) = text.replace(Regex("<[^>]*>"), "").lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().takeIf { it.length >= 18 && it.split(' ').distinct().size >= 4 }
    val references = reference.mapNotNull { cue -> phrase(cue.text)?.let { it to cue } }.groupBy({ it.first }, { it.second })
        .mapNotNull { (text, cues) -> cues.singleOrNull()?.let { text to it } }.toMap()
    val anchors = target.mapNotNull { cue -> phrase(cue.text)?.let(references::get)?.let { cue to it } }
    if (anchors.size < 9 || anchors.last().first.endSeconds - anchors.first().first.endSeconds < 180) return null
    data class Fit(val offset: Double, val rate: Double, val agreeing: Int, val residual: Double)
    fun median(values: List<Double>) = values.sorted().let { (it[(it.size - 1) / 2] + it[it.size / 2]) / 2 }
    val fits = AdaptiveSubtitleSynchronizer.rates.mapNotNull { rate ->
        cancelled()
        val training = anchors.filterIndexed { i, _ -> i % 3 != 2 }
        val held = anchors.filterIndexed { i, _ -> i % 3 == 2 }
        val offset = median(training.map { (a, b) -> b.endSeconds - a.endSeconds * rate })
        if (abs(offset) > 600) return@mapNotNull null
        fun error(pair: Pair<SubtitleCue, SubtitleCue>) = abs(pair.second.endSeconds - pair.first.endSeconds * rate - offset)
        val agreeing = anchors.filter { error(it) <= .5 }
        if (agreeing.size < anchors.size * .85 || held.count { error(it) <= .5 } < held.size * .85 ||
            agreeing.last().first.endSeconds - agreeing.first().first.endSeconds < 180) null
        else Fit(offset, rate, agreeing.size, median(agreeing.map(::error)))
    }.sortedWith(compareByDescending<Fit> { it.agreeing }.thenBy { it.residual })
    val best = fits.firstOrNull() ?: return null
    if (fits.drop(1).any { rival -> rival.agreeing >= best.agreeing - 2 &&
        abs((rival.rate - best.rate) * target.last().endSeconds + rival.offset - best.offset) > .6 }) return null
    return AudioSubtitleCorrection(best.offset, best.rate, .95)
}
