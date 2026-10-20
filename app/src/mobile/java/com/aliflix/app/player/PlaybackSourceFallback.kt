package com.aliflix.app.player

import com.aliflix.app.model.*

/** Keep content/progress identity while trying every available provider automatically. */
internal fun playbackSourceFallbacks(selection: PlaybackSelection, preferences: PlaybackPreferences): List<PlaybackSelection> =
    listOf(selection) + (listOf(MobilePlaybackProvider.ANIWORLD, MobilePlaybackProvider.SERIENSTREAM, MobilePlaybackProvider.FILMO, MobilePlaybackProvider.FLIXER, PlaybackProviderId.MIRURO,
        PlaybackProviderId.ANIKURO, PlaybackProviderId.MOVIEPIRE, PlaybackProviderId.CINEJOY,
        PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY,
        MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY))
        .filter { it.isAvailableFor(selection.media) && it != selection.source.identity }
        .map { selection.copy(source = preferences.sourceFor(selection.media, it)) }

/** Priority controls launch order; an unavailable priority must never serialize fallback. */
internal fun initialPlaybackRace(candidates: List<PlaybackSelection>): List<PlaybackSelection> {
    val anime = candidates.filter { it.source.identity.isAnimeNative }
    return when {
        candidates.firstOrNull()?.media?.let { it.type == MediaType.TV && it.isJapaneseAnime } == true && anime.isNotEmpty() ->
            anime + candidates.filterNot { it.source.identity.isAnimeNative }
        else -> candidates
    }
}

/** Give the chosen catalogue a brief head start, with bounded concurrent alternatives. */
internal suspend fun <T : Any> racePlaybackSources(candidates: List<PlaybackSelection>, attempt: suspend (PlaybackSelection) -> T): T =
    firstSuccessful(candidates.mapIndexed { index, candidate -> suspend {
        if (index > 0) kotlinx.coroutines.delay(900)
        attempt(candidate)
    } }, parallelism = 4)
