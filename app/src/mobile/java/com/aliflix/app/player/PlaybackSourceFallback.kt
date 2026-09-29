package com.aliflix.app.player

import com.aliflix.app.model.*

/** Preserve the user's selected mirror first, then try each other configured source once. */
internal fun playbackSourceFallbacks(selection: PlaybackSelection, preferences: PlaybackPreferences): List<PlaybackSelection> =
    listOf(selection) + listOf(PlaybackProviderId.CINEJOY, PlaybackProviderId.MIRURO, PlaybackProviderId.ANIKURO, PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY, PlaybackProviderId.MOVIEPIRE, MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY)
        .filter { it.isAvailableFor(selection.media) }
        .filter { it != selection.source.identity }
        .map { selection.copy(source = preferences.sourceFor(selection.media, it)) }

/** Fresh playback races providers; saved/manual routes still get their explicit first attempt. */
internal fun initialPlaybackRace(candidates: List<PlaybackSelection>): List<PlaybackSelection> {
    val anime = candidates.filter { it.source.identity.isAnimeNative }
    return if (anime.isNotEmpty()) anime else candidates
}
