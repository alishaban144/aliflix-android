package com.aliflix.app.player

/** Read-only video PTS cadence, used solely to rank automatic caption editions
 * when the stream omits frame-rate metadata. It never supplies a sync answer. */
internal class PlaybackVideoClock {
    private var previous: Long? = null
    private val deltas = ArrayDeque<Long>()
    @Volatile var frameRate = -1f
        private set

    @Synchronized fun reset() { previous = null; deltas.clear(); frameRate = -1f }

    @Synchronized fun frame(timeUs: Long) {
        val before = previous
        previous = timeUs
        if (before == null || timeUs == before) return
        val delta = timeUs - before
        if (delta !in 5_000L..100_000L) { deltas.clear(); frameRate = -1f; return }
        deltas.addLast(delta)
        if (deltas.size > 60) deltas.removeFirst()
        if (deltas.size < 12) return
        val sorted = deltas.sorted()
        val median = sorted[sorted.size / 2].toDouble()
        frameRate = if (sorted.count { kotlin.math.abs(it - median) <= median * .02 } >= sorted.size * .8)
            (1_000_000.0 / median).toFloat() else -1f
    }
}
