package com.aliflix.app.player

import kotlin.math.*

/** Long dialogue pauses survive translation and different cue segmentation.
 * Fit earlier pause boundaries, independently verify withheld boundaries across
 * the complete release, and verify occupancy in every informative timeline block.
 * This supplies a caption-to-caption clock only; heard words must still verify it.
 */
internal fun matchCaptionGaps(target: List<SubtitleCue>, reference: List<SubtitleCue>, cancelled: () -> Unit = {}): AudioSubtitleCorrection? {
    data class Gap(val end: Double, val duration: Double)
    fun signal(cues: List<SubtitleCue>) = cues.filter(AdaptiveSubtitleSynchronizer::dialogue).sortedBy { it.startSeconds }
    if (target.size > 20_000 || reference.size > 20_000 || (target + reference).any { it.endSeconds > 21_600 }) return null
    val a = signal(target); val b = signal(reference)
    if (a.size < 20 || b.size < 20) return null
    fun gaps(cues: List<SubtitleCue>): List<Gap> {
        var end = cues.first().endSeconds
        val result = mutableListOf<Gap>()
        for (cue in cues.drop(1)) {
            val pause = cue.startSeconds - end
            if (pause >= 6) result.add(Gap(cue.startSeconds, pause))
            end = max(end, cue.endSeconds)
        }
        return if (result.size <= 512) result else (0 until 512).map { result[it * (result.size-1) / 511] }
    }
    val left = gaps(a); val right = gaps(b)
    if (left.size < 12 || right.size < 12) return null
    data class Fit(val rate: Double, val offset: Double, val votes: Int, val quality: Double)
    fun median(values: List<Double>) = values.sorted().let { (it[(it.size-1)/2] + it[it.size/2]) / 2 }
    fun occupancy(cues: List<SubtitleCue>, rate: Double, offset: Double, size: Int): BooleanArray {
        val bits = BooleanArray(size)
        cues.forEach { cue ->
            val from = ((cue.startSeconds * rate + offset) * 4).roundToInt().coerceIn(0,size)
            val until = ((cue.endSeconds * rate + offset) * 4).roundToInt().coerceIn(0,size)
            for (i in from until until) bits[i] = true
        }
        return bits
    }
    val size = (max(a.last().endSeconds * 1.1 + 600, b.last().endSeconds) * 4).toInt().coerceIn(1,90_000)
    val observed = occupancy(b,1.0,0.0,size)
    val fits = mutableListOf<Fit>()
    for (rate in AdaptiveSubtitleSynchronizer.rates) {
        cancelled()
        val histogram = mutableMapOf<Int,Int>()
        left.forEachIndexed { index,x ->
            if (index % 16 == 0) cancelled()
            right.forEach { y ->
            val offset = y.end - x.end * rate
            if (abs(offset) <= 600 && abs(y.duration - x.duration * rate) <= max(2.0,x.duration * .15)) {
                val bin = (offset * 2).roundToInt()
                histogram[bin] = histogram.getOrDefault(bin,0) + 1
            }
        } }
        for (seed in histogram.entries.sortedByDescending { it.value }.take(4)) {
            cancelled()
            val provisional = seed.key / 2.0
            val pairs = left.mapNotNull { x ->
                val y = right.minBy { abs(it.end - x.end * rate - provisional) }
                if (abs(y.end - x.end * rate - provisional) <= .75 &&
                    abs(y.duration - x.duration * rate) <= max(2.0,x.duration * .15)) x to y else null
            }.distinctBy { it.second.end }
            if (pairs.size < max(12.0,min(left.size,right.size) * .6) ||
                pairs.last().first.end - pairs.first().first.end < max(180.0,(a.last().endSeconds-a.first().startSeconds)*.7)) continue
            val training = pairs.filterIndexed { i,_ -> i % 3 != 2 }
            val held = pairs.filterIndexed { i,_ -> i % 3 == 2 }
            // Translation editors differ in how early a cue appears and how
            // long it remains. Balance both sides of each matching pause so
            // onset-only fitting cannot systematically pull captions early.
            val offset = median(training.flatMap { (x,y) -> listOf(
                y.end - x.end * rate,
                (y.end-y.duration) - (x.end-x.duration) * rate,
            ) })
            if (held.size < 4 || held.count { (x,y) -> abs(y.end-x.end*rate-offset) <= .6 } < held.size * .9 ||
                training.count { (x,y) -> abs(y.end-x.end*rate-offset) <= .6 } < training.size * .9) continue
            val predicted = occupancy(a,rate,offset,size)
            // Exclude empty post-credit tails, rather than letting shared silence
            // inflate similarity. Each block must contain varying dialogue.
            val end = min(size, (min(a.last().endSeconds*rate+offset,b.last().endSeconds)*4).toInt())
            val scores = (0 until 8).mapNotNull { block ->
                val from = block*end/8; val until = (block+1)*end/8; val count = until-from
                if (count <= 0) return@mapNotNull null
                var x=0;var y=0;var both=0
                for (i in from until until) { if (predicted[i]) x++;if (observed[i]) y++;if (predicted[i] && observed[i]) both++ }
                val mx=x.toDouble()/count;val my=y.toDouble()/count
                if (mx !in .04.. .96 || my !in .04.. .96) null
                else (both.toDouble()/count-mx*my)/sqrt(mx*(1-mx)*my*(1-my))
            }
            if (scores.size < 4 || scores.any { it < .55 } || median(scores) < .72) continue
            fits.add(Fit(rate,offset,pairs.size,median(scores)))
        }
    }
    val ordered = fits.sortedWith(compareByDescending<Fit> { it.votes }.thenByDescending { it.quality })
    val best = ordered.firstOrNull() ?: return null
    if (ordered.drop(1).any { it.votes >= best.votes * .9 &&
        abs((it.rate-best.rate)*a.last().endSeconds+it.offset-best.offset) > .6 }) return null
    return AudioSubtitleCorrection(best.offset,best.rate,best.quality,model="verified_caption_gaps")
}
