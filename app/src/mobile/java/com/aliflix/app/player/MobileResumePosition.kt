package com.aliflix.app.player

import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.PlaybackSelection

/** Watched/completed is a library status, not permission to discard an unfinished position. */
internal fun mobileSavedResumeMs(progress: PlaybackProgress?): Long {
    if (progress == null || progress.explicitlyRestarted || !progress.positionSeconds.isFinite() ||
        progress.positionSeconds <= 0 || progress.positionSeconds >= progress.durationSeconds) return 0
    return (progress.positionSeconds * 1000).toLong()
}

/** Provider and server are source identities; the resume identity is the movie/episode. */
internal fun mobileSameContent(a: PlaybackSelection?, b: PlaybackSelection?): Boolean =
    a != null && b != null && playbackProgressKey(a) == playbackProgressKey(b)
