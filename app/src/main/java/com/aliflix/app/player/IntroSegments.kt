package com.aliflix.app.player

import org.json.JSONObject

internal enum class IntroSegmentKind(val label: String) { INTRO("Skip intro"), OUTRO("Skip outro") }

internal data class IntroSegment(val kind: IntroSegmentKind, val startMs: Long, val endMs: Long) {
    fun isActive(positionMs: Long, durationMs: Long): Boolean =
        startMs >= 0 && endMs > startMs && endMs <= durationMs && positionMs >= startMs && positionMs < endMs
}

/** Identity and timestamp validation prevents another episode or malformed marker from seeking. */
internal fun parseIntroSegments(raw: String, imdbId: String, season: Int, episode: Int): List<IntroSegment> {
    val json = JSONObject(raw)
    require(json.getString("imdb_id") == imdbId && json.getInt("season") == season && json.getInt("episode") == episode)
    return IntroSegmentKind.entries.mapNotNull { kind ->
        val item = json.optJSONObject(kind.name.lowercase()) ?: return@mapNotNull null
        val start = item.optDouble("start_ms", Double.NaN)
        val end = item.optDouble("end_ms", Double.NaN)
        if (!start.isFinite() || !end.isFinite() || start < 0 || end <= start || end > 24 * 60 * 60 * 1000) null
        else IntroSegment(kind, start.toLong(), end.toLong())
    }.sortedBy { it.startMs }
}
