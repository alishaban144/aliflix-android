package com.aliflix.app.player

import kotlin.math.abs

internal data class SubtitleTimingMatch(val offset: Double, val rate: Double, val anchors: Int, val residual: Double) {
    fun apply(cues: List<SubtitleCue>) = cues.mapNotNull { cue ->
        val end = cue.endSeconds * rate + offset
        if (end <= 0) null else cue.copy(startSeconds = (cue.startSeconds * rate + offset).coerceAtLeast(0.0), endSeconds = end)
    }
}

private val speechMarkup = Regex("<[^>]*>|\\[[^]]*]")
private val speechSeparator = Regex("[^\\p{L}\\p{N}]+")
private fun speechTokens(text: String) = text.replace(speechMarkup, " ").lowercase()
    .split(speechSeparator).filter { it.isNotEmpty() }

/** Require independently agreeing dialogue in separated scenes before changing offset or frame rate. */
internal fun matchSubtitleTiming(cues: List<SubtitleCue>, words: List<TimedSpeechWord>): SubtitleTimingMatch? {
    data class Anchor(val subtitle: Double, val audio: Double, val sample: Int)
    val flattened = words.flatMap { word -> speechTokens(word.text).map { it to word } }
    val phrases = listOf(4, 5).flatMap { size -> flattened.windowed(size).filter { window ->
        window.first().second.sample == window.last().second.sample && window.last().second.seconds - window.first().second.seconds < 7
    } }.groupBy { window -> window.joinToString(" ") { it.first } }
    val anchors = cues.mapNotNull { cue ->
        val tokens = speechTokens(cue.text).take(5)
        if (tokens.size < 4 || tokens.distinct().size < 3) return@mapNotNull null
        val hits = phrases[tokens.joinToString(" ")].orEmpty()
        if (hits.size != 1) null else hits.single().first().second.let { Anchor(cue.startSeconds, it.seconds, it.sample) }
    }.distinctBy { it.audio }
    if (anchors.size < 4) return null
    val models = anchors.flatMap { a -> anchors.mapNotNull { b ->
        if (a.sample == b.sample || b.subtitle - a.subtitle < 120) return@mapNotNull null
        val measured = (b.audio - a.audio) / (b.subtitle - a.subtitle)
        // Only recognized frame-rate conversions; an arbitrary fit can drift badly later in a film.
        val nearestRate = listOf(1.0, 25.0 / 24.0, 24.0 / 25.0, 25.0 / 23.976, 23.976 / 25.0, 24.0 / 23.976, 23.976 / 24.0)
            .minBy { abs(it - measured) }
        // Sub-second caption lead/lag cannot establish a 0.1% frame-rate mismatch.
        val rate = if (abs(nearestRate - 1) * (b.subtitle - a.subtitle) < 2.0) 1.0 else nearestRate
        val offset = a.audio - a.subtitle * rate
        if (rate !in .90..1.10 || abs(offset) > 600) return@mapNotNull null
        val agreeing = anchors.filter { abs(it.audio - (it.subtitle * rate + offset)) <= .8 }
        if (agreeing.size < 4 || agreeing.groupBy { it.sample }.count { it.value.size >= 2 } < 2) return@mapNotNull null
        SubtitleTimingMatch(offset, rate, agreeing.size, agreeing.map { abs(it.audio - (it.subtitle * rate + offset)) }.average())
    } }
    return models.sortedWith(compareByDescending<SubtitleTimingMatch> { it.anchors }.thenBy { it.residual }).firstOrNull()
}

/** Pick speech-dense windows in different scenes; skip credits and short/non-dialogue captions. */
internal fun subtitleProbeWindows(cues: List<SubtitleCue>, duration: Double): List<Double> {
    val usable = cues.filter { it.startSeconds > 90 && it.startSeconds < duration - 45 && speechTokens(it.text).size >= 4 }
    val first = usable.filter { it.startSeconds < duration * .4 }.maxByOrNull { cue ->
        usable.count { it.startSeconds in cue.startSeconds..(cue.startSeconds + 14) }
    }?.startSeconds ?: return emptyList()
    val second = usable.filter { it.startSeconds > first + 180 }.maxByOrNull { cue ->
        usable.count { it.startSeconds in cue.startSeconds..(cue.startSeconds + 14) }
    }?.startSeconds ?: return emptyList()
    return listOf((first - 2).coerceAtLeast(0.0), second - 2)
}
