package com.aliflix.app.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks

/** Compare the serialized format identity: MediaSession rewrites group IDs and omits metadata. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun nativeAudioChoice(tracks: Tracks, controllerGroup: TrackGroup, index: Int): TrackSelectionOverride? {
    if (index !in 0 until controllerGroup.length) return null
    fun identity(format: Format): Format = Format.fromBundle(format.toBundle()).buildUpon()
        .setPrimaryTrackGroupId(null).build()
    val requested = identity(controllerGroup.getFormat(index))
    val matches = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it, true) &&
            identity(group.getTrackFormat(it)) == requested
        }.map { TrackSelectionOverride(group.mediaTrackGroup, it) }
    }
    return matches.singleOrNull()
}
