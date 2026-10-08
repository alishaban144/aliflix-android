package com.aliflix.app.player

/** Outro metadata is optional. A finite episode always has a final offer window. */
internal fun upNextWindowStart(durationMs: Long, segments: List<IntroSegment>): Long? {
    if (durationMs <= 0) return null
    val tail = (durationMs / 10).coerceIn(1_000, 30_000)
    val marker = segments.filter { it.kind == IntroSegmentKind.OUTRO &&
        it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= durationMs }
        .minOfOrNull { it.startMs }
    return minOf(marker ?: durationMs, durationMs - tail)
}
