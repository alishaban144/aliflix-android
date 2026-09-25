package com.aliflix.app.player

import com.aliflix.app.model.*

/** Preserve the user's selected mirror first, then try each other configured source once. */
internal fun playbackSourceFallbacks(selection: PlaybackSelection, preferences: PlaybackPreferences): List<PlaybackSelection> =
    listOf(selection) + listOf(PlaybackProviderId.MIRURO, PlaybackProviderId.ANIKURO, PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY, PlaybackProviderId.MOVIEPIRE)
        .filter { it.isAvailableFor(selection.media) }
        .filter { it != selection.source.provider }
        .map { selection.copy(source = preferences.sourceFor(selection.media, it)) }
