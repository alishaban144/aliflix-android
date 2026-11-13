package com.aliflix.app.talk

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.json.JSONObject

internal data class TalkFrameTarget(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun inVideo(video: Rect) = Rect(
        video.left + left * video.width, video.top + top * video.height,
        video.left + right * video.width, video.top + bottom * video.height,
    )
}

internal class TalkFrameAttention(args: JSONObject, frameId: String) {
    private data class Cue(val target: TalkFrameTarget, val phrase: Regex)
    private data class Mention(val target: TalkFrameTarget, val index: Int)

    private val cues = buildList {
        if (args.optString("frameId") != frameId) return@buildList
        val targets = args.optJSONArray("targets") ?: return@buildList
        if (targets.length() !in 1..8) return@buildList
        for (index in 0 until targets.length()) {
            val cue = targets.optJSONObject(index) ?: continue
            val phrase = cue.optString("mention").trim()
            if (phrase.length !in 2..80) continue
            val box = cue.optJSONArray("box")?.takeIf { it.length() == 4 } ?: continue
            val values = (0..3).map { box.optDouble(it).toFloat() / 1000f }
            if (values.any { !it.isFinite() || it !in 0f..1f }) continue
            val (top, left, bottom, right) = values
            if (top >= bottom || left >= right || (bottom-top)*(right-left) > .55f) continue
            val words = phrase.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
            add(Cue(TalkFrameTarget(left, top, right, bottom),
                Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)))
        }
    }
    val available get() = cues.isNotEmpty()
    private var mentions = emptyList<Mention>()
    private var narration = ""
    private var firstSample = -1L
    private var samples = 0L
    private var complete = false
    private var currentIndex = -1

    @Synchronized fun update(text: String) {
        narration = text
        var cursor = 0
        mentions = cues.mapNotNull { cue ->
            cue.phrase.find(text, cursor)?.let { match ->
                cursor = match.range.last + 1
                Mention(cue.target, match.range.first)
            }
        }
    }

    @Synchronized fun receive(count: Int, start: Long) {
        if (firstSample < 0) firstSample = start
        samples += count
    }

    @Synchronized fun finish() { complete = true }

    @Synchronized fun at(rendered: Long): TalkFrameTarget? {
        if (firstSample < 0 || samples == 0L || rendered < firstSample || rendered >= firstSample + samples) return null
        val wordCount = narration.count { it.isWhitespace() } + 1
        val duration = if (complete) samples else maxOf(samples, (wordCount * 24_000 / 2.6).toLong())
        val cursor = ((rendered - firstSample + 4_800L).toDouble() / duration * narration.length).toInt()
        currentIndex = maxOf(currentIndex, mentions.indexOfLast { it.index <= cursor })
        return mentions.getOrNull(currentIndex)?.target
    }
}

internal fun talkOrbBesideTarget(target: Rect, home: Offset, viewport: Rect, radius: Float): Offset? {
    val clearance = radius * .8f
    val candidates = listOf(
        Offset(target.left - clearance, target.center.y),
        Offset(target.right + clearance, target.center.y),
        Offset(target.center.x, target.top - clearance),
        Offset(target.center.x, target.bottom + clearance),
    )
    return candidates.map { Offset(it.x.coerceIn(viewport.left, viewport.right), it.y.coerceIn(viewport.top, viewport.bottom)) }
        .filter { it.x <= target.left - clearance || it.x >= target.right + clearance ||
            it.y <= target.top - clearance || it.y >= target.bottom + clearance }
        .minByOrNull { (it-home).getDistance() }
}
