package com.aliflix.app.player

import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlin.math.abs

internal class ReferenceAlignmentTimeout : RuntimeException()

/** Metadata ranks candidates only. Actual dialogue independently verifies timing. */
internal fun rankCompleteSubtitleCandidates(tracks: List<SubtitleTrack>, frameRate: Float): List<SubtitleTrack> {
    val split = Regex("(?i)(?:^|[ ._()\\[\\]-])(?:cd|disc|disk|part)[ ._-]*[12](?:$|[ ._()\\[\\]-])")
    return tracks.sortedWith(compareByDescending<SubtitleTrack> { it.hashMatched }
        .thenBy { split.containsMatchIn(it.fileName + " " + it.releaseName) }
        .thenBy { track ->
            val rate = track.fps?.toDoubleOrNull()
            if (frameRate > 0 && rate != null && rate > 0) abs(rate - frameRate) else Double.MAX_VALUE
        })
}

/** Automatic full-movie selection must not silently use the first CD of a split file.
 * Short productions and unknown durations retain normal subtitle selection. */
internal fun automaticCaptionCoversMovie(cues: List<SubtitleCue>, selection: PlaybackSelection, durationMs: Long): Boolean {
    if (selection.media.type != MediaType.MOVIE || durationMs < 30 * 60_000L) return true
    val ends = cues.filter { AdaptiveSubtitleSynchronizer.dialogue(it) }.map { it.endSeconds }
    val from = cues.filter { AdaptiveSubtitleSynchronizer.dialogue(it) }.minOfOrNull { it.startSeconds } ?: return false
    return ends.maxOrNull()?.let { it >= durationMs / 1000.0 * .65 && it - from >= durationMs / 1000.0 * .55 } == true
}

internal fun interleaveSubtitleCandidates(primary: List<SubtitleTrack>, secondary: List<SubtitleTrack>): List<SubtitleTrack> =
    (0 until maxOf(primary.size, secondary.size)).flatMap { index ->
        listOfNotNull(primary.getOrNull(index), secondary.getOrNull(index))
    }.distinctBy { it.downloadToken }
