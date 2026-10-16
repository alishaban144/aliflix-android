package com.aliflix.app.player

/** Independent decoder output only. Discontinuous PTS never become one ASR clip. */
internal class DecodedDialogueClips {
    private data class Run(val boundary: Long, val start: Double, val frames: MutableList<ShortArray>)
    private val runs = java.util.ArrayDeque<Run>()
    @Synchronized fun append(time: Double, pcm: ShortArray, boundary: Long) {
        if (!time.isFinite() || time < 0 || pcm.size != 320) return
        var last = runs.peekLast()
        val expected = last?.let { it.start + it.frames.size * .02 }
        if (expected != null && time < expected - .003 && last.boundary == boundary) return
        if (last == null || last.boundary != boundary || kotlin.math.abs(time - expected!!) > .003) {
            last = Run(boundary, time, mutableListOf()); runs.add(last)
        }
        last.frames.add(pcm.copyOf())
        while (runs.size > 32 || (runs.size > 1 && runs.sumOf { it.frames.size } > 6050)) runs.removeFirst()
        if (last.frames.size > 6050) runs.removeLast() // Corrupt/unbounded input is never analyzed.
    }
    @Synchronized fun snapshot(): List<PlayedDialoguePcm> = runs.filter { it.frames.size >= 200 }
        .map { PlayedDialoguePcm(it.start, it.frames.toList()) }
}
