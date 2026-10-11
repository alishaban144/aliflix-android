package com.aliflix.app.player

import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlin.math.abs

internal class ReferenceAlignmentTimeout : RuntimeException()
internal const val AUTOMATIC_CAPTION_SELECTION_POLICY = 3

internal fun refreshAutomaticCaptionCache(automatic: Boolean, policy: Int, online: Boolean, offlineDownload: Boolean): Boolean =
    automatic && policy < AUTOMATIC_CAPTION_SELECTION_POLICY && online && !offlineDownload

/** Metadata ranks candidates only. Actual dialogue independently verifies timing. */
internal fun rankCompleteSubtitleCandidates(tracks: List<SubtitleTrack>, frameRate: Float): List<SubtitleTrack> {
    val split = Regex("(?i)(?:^|[ ._()\\[\\]-])(?:cd|disc|disk|part)[ ._-]*[12](?:$|[ ._()\\[\\]-])")
    val modernEdition = Regex("(?i)(?:blu[ ._-]?ray|bd[ ._-]?rip|br[ ._-]?rip|web[ ._-]?(?:dl|rip))")
    val palEdition = Regex("(?i)(?:^|[ ._()\\[\\]-])(?:pal|25r)(?:$|[ ._()\\[\\]-])")
    return tracks.sortedWith(compareByDescending<SubtitleTrack> { it.hashMatched }
        .thenBy { split.containsMatchIn(it.fileName + " " + it.releaseName) }
        .thenBy { track ->
            val name = track.fileName + " " + track.releaseName
            // A release hint ranks an automatic candidate; it never certifies
            // its clock or changes a viewer's manually selected subtitle file.
            val rate = track.fps?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 }
                ?: when {
                    palEdition.containsMatchIn(name) -> 25.0
                    frameRate in 23.9f..24.1f && modernEdition.containsMatchIn(name) -> 23.976
                    else -> null
                }
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
