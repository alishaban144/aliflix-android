package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks

internal fun preferredQualityParameters(current: TrackSelectionParameters, quality: PreferredVideoQuality): TrackSelectionParameters =
    current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        .setForceLowestBitrate(quality == PreferredVideoQuality.LOW).build()

/** Some providers omit bitrates: use resolution first, then bitrate, among supported video tracks. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun lowestVideoTrack(tracks: Tracks): TrackSelectionOverride? {
    val candidates = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it) }.map { group to it }
    }
    val lowest = candidates.minWithOrNull(compareBy<Pair<Tracks.Group, Int>> {
        val f = it.first.getTrackFormat(it.second)
        if (f.width > 0 && f.height > 0) f.width.toLong() * f.height else Long.MAX_VALUE
    }.thenBy { it.first.getTrackFormat(it.second).bitrate.takeIf { rate -> rate > 0 } ?: Int.MAX_VALUE }) ?: return null
    return TrackSelectionOverride(lowest.first.mediaTrackGroup, lowest.second)
}

internal fun applyLowestVideoTrack(player: Player, tracks: Tracks) {
    val override = lowestVideoTrack(tracks) ?: return
    if (player.trackSelectionParameters.overrides[override.mediaTrackGroup] != override) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(override).build()
    }
}
