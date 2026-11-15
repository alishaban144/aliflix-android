package com.aliflix.app.player

/** Read-only video PTS cadence, used solely to rank automatic caption editions
 * when the stream omits frame-rate metadata. It never supplies a sync answer. */
internal class PlaybackVideoClock {
    private var previous: Long? = null
    private val deltas = ArrayDeque<Long>()
    private var sinceEstimate = 0
    @Volatile var releaseTimeNs = 0L
        private set
    @Volatile var frameRate = -1f
        private set

    @Synchronized fun reset() { previous = null; deltas.clear(); sinceEstimate = 0; frameRate = -1f; releaseTimeNs = 0L }

    @Synchronized fun frame(timeUs: Long, releaseNs: Long = 0L) {
        releaseTimeNs = if (releaseNs > 0) releaseNs else System.nanoTime()
        val before = previous
        previous = timeUs
        if (before == null || timeUs == before) return
        val delta = timeUs - before
        if (delta !in 5_000L..100_000L) { deltas.clear(); sinceEstimate = 0; frameRate = -1f; return }
        deltas.addLast(delta)
        if (deltas.size > 60) deltas.removeFirst()
        sinceEstimate++
        if (deltas.size < 12 || sinceEstimate < 12) return
        sinceEstimate = 0
        val sorted = deltas.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        frameRate = if (sorted.count { kotlin.math.abs(it - median) <= median * .02 } >= sorted.size * .8)
            (1_000_000.0 / median).toFloat() else -1f
    }
}
