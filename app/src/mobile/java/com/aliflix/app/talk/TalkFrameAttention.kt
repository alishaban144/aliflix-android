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
    private data class Cue(val target: TalkFrameTarget, val phrases: List<Regex>, val fallback: String?)
    private data class Mention(val target: TalkFrameTarget, val index: Int, val cue: Int)

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
            val aliases = cue.optJSONArray("aliases")?.let { rows ->
                (0 until minOf(rows.length(), 3)).map { rows.optString(it) }
            }.orEmpty()
            val terms = (listOf(phrase) + aliases)
                .map(String::trim).filter { it.length in 2..80 }.distinct()
            val patterns = terms.map { term ->
                val words = term.split(Regex("[\\s\\p{Pd}]+")).joinToString("[\\s\\p{Pd}]+") { Regex.escape(it) }
                Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            }
            val fallback = Regex("[\\p{L}]{4,}").findAll(phrase).lastOrNull()?.value
                ?.takeUnless { it.lowercase() in setOf("there","here","that","this","with","near","left","right","side") }
            add(Cue(TalkFrameTarget(left, top, right, bottom), patterns, fallback))
        }
    }
    val available get() = cues.isNotEmpty()
    private var mentions = emptyList<Mention>()
    private var narration = ""
    private var firstSample = -1L
    private var samples = 0L
    private var complete = false
    private var currentCue = -1
    private var currentTarget: TalkFrameTarget? = null
    private var estimatedDuration = 0L

    @Synchronized fun update(text: String) {
        narration = text
        var cursor = 0
        mentions = cues.mapIndexedNotNull { index, cue ->
            val exact = cue.phrases.mapNotNull { it.find(text, cursor) }.minByOrNull { it.range.first }
            val fallback = cue.fallback?.takeIf { word -> cues.count { it.fallback.equals(word, true) } == 1 }
            val match = exact ?: fallback?.let { word ->
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])",RegexOption.IGNORE_CASE).find(text,cursor)
            }
            match?.let {
                cursor = it.range.last + 1
                Mention(cue.target, it.range.first, index)
            }
        }
        val words = text.count { it.isWhitespace() } + 1
        estimatedDuration = (words * 24_000 / 2.6).toLong()
    }

    @Synchronized fun receive(count: Int, start: Long) {
        if (firstSample < 0) firstSample = start
        samples += count
        complete = false
    }

    @Synchronized fun finish() { complete = true }

    @Synchronized fun at(rendered: Long): TalkFrameTarget? {
        if (firstSample < 0 || samples == 0L || rendered < firstSample || rendered >= firstSample + samples) return null
        val duration = if (complete) samples else maxOf(samples, estimatedDuration)
        val cursor = ((rendered - firstSample + 12_000L).toDouble() / duration * narration.length).toInt()
        mentions.lastOrNull { it.index <= cursor && it.cue > currentCue }?.let {
            currentCue = it.cue
            currentTarget = it.target
        }
        return currentTarget
    }
}

internal fun talkOrbGlideControl(from: Offset, to: Offset, viewport: Rect, radius: Float, target: Rect?, captions: Rect?): Offset {
    val travel = to - from
    val length = travel.getDistance()
    val middle = (from + to) / 2f
    if (length < radius * .35f) return middle

    val bend = minOf(length * .26f, radius * 1.4f)
    val normal = Offset(-travel.y, travel.x) * (bend / length)
    val candidates = listOf(middle + normal, middle - normal, middle)
    val safe = candidates.map { Offset(it.x.coerceIn(viewport.left, viewport.right), it.y.coerceIn(viewport.top, viewport.bottom)) }
    return safe.minBy { control ->
        (1..7).sumOf { step ->
            val point = talkOrbGlidePoint(from, control, to, step / 8f)
            val footprint = Rect(point.x-radius,point.y-radius,point.x+radius,point.y+radius)
            (if (captions?.overlaps(footprint) == true) 4 else 0) + (if (target?.overlaps(footprint) == true) 2 else 0)
        }
    }
}

internal fun talkOrbGlidePoint(from: Offset, control: Offset, to: Offset, progress: Float): Offset {
    val rest = 1f - progress
    return from * (rest * rest) + control * (2f * rest * progress) + to * (progress * progress)
}

internal fun talkOrbBesideTarget(target: Rect, from: Offset, viewport: Rect, radius: Float, avoid: Rect? = null): Offset? {
    val clearance = radius * 1.15f
    val candidates = listOf(
        Offset(target.left - clearance, target.center.y),
        Offset(target.right + clearance, target.center.y),
        Offset(target.center.x, target.top - clearance),
        Offset(target.center.x, target.bottom + clearance),
    )
    return candidates.map { Offset(it.x.coerceIn(viewport.left, viewport.right), it.y.coerceIn(viewport.top, viewport.bottom)) }
        .filter { it.x <= target.left - clearance || it.x >= target.right + clearance ||
            it.y <= target.top - clearance || it.y >= target.bottom + clearance }
        .filterNot { point -> avoid?.overlaps(Rect(
            point.x-radius,point.y-radius,point.x+radius,point.y+radius,
        )) == true }
        .minByOrNull { (it-from).getDistance() }
}
