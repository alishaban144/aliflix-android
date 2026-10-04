package com.aliflix.app.player

import com.aliflix.app.model.*

/** Keep content/progress identity while trying every available provider automatically. */
internal fun playbackSourceFallbacks(selection: PlaybackSelection, preferences: PlaybackPreferences): List<PlaybackSelection> =
    listOf(selection) + (listOf(MobilePlaybackProvider.FLIXER, PlaybackProviderId.MIRURO,
        PlaybackProviderId.ANIKURO, PlaybackProviderId.MOVIEPIRE, PlaybackProviderId.CINEJOY,
        PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY,
        MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY))
        .filter { it.isAvailableFor(selection.media) && it != selection.source.identity }
        .map { selection.copy(source = preferences.sourceFor(selection.media, it)) }

/** Native anime sources retain their race; general playback advances through a bounded queue. */
internal fun initialPlaybackRace(candidates: List<PlaybackSelection>): List<PlaybackSelection> {
    val anime = candidates.filter { it.source.identity.isAnimeNative }
    return if (candidates.firstOrNull()?.source?.identity?.isAnimeNative == true) anime else candidates
}
