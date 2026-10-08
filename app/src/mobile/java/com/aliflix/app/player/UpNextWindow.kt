package com.aliflix.app.player

/** Outro metadata is optional. A finite episode always has a final offer window. */
internal fun upNextWindowStart(durationMs: Long, segments: List<IntroSegment>): Long? {
    if (durationMs <= 0) return null
    val tail = (durationMs / 10).coerceIn(1_000, 30_000)
    // Up Next needs the verified credit START. A rip may end a few seconds
    // before the database end timestamp; that must not discard its valid start.
    val marker = segments.filter { it.kind == IntroSegmentKind.OUTRO &&
        it.startMs >= 0 && it.startMs < durationMs && it.endMs > it.startMs }
        .minOfOrNull { it.startMs }
    return minOf(marker ?: durationMs, durationMs - tail)
}
