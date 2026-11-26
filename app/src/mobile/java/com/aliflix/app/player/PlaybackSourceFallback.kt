package com.aliflix.app.player

import com.aliflix.app.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Keep content/progress identity while trying every available provider automatically. */
internal fun playbackSourceFallbacks(selection: PlaybackSelection, preferences: PlaybackPreferences): List<PlaybackSelection> {
    val indian = if (selection.media.isIndianTitle) listOf(MobilePlaybackProvider.HIMOVIES, MobilePlaybackProvider.CINEGO) else emptyList()
    return listOf(selection) + (indian + listOf(MobilePlaybackProvider.ANIWORLD, MobilePlaybackProvider.SERIENSTREAM, MobilePlaybackProvider.FILMO, MobilePlaybackProvider.FLIXER, PlaybackProviderId.MIRURO,
        PlaybackProviderId.ANIKURO, PlaybackProviderId.MOVIEPIRE, PlaybackProviderId.CINEJOY,
        PlaybackProviderId.RAMOFLIX, PlaybackProviderId.DORABY,
        MobilePlaybackProvider.SEVEN_MOVIES, MobilePlaybackProvider.MOVY))
        .filter { it.isAvailableFor(selection.media) && it != selection.source.identity }
        .map { selection.copy(source = preferences.sourceFor(selection.media, it)) }
}

/** Priority controls launch order; an unavailable priority must never serialize fallback. */
internal fun initialPlaybackRace(candidates: List<PlaybackSelection>): List<PlaybackSelection> {
    val anime = candidates.filter { it.source.identity.isAnimeNative }
    return when {
        candidates.firstOrNull()?.media?.let { it.type == MediaType.TV && it.isJapaneseAnime } == true && anime.isNotEmpty() ->
            anime + candidates.filterNot { it.source.identity.isAnimeNative }
        candidates.firstOrNull()?.media?.isIndianTitle == true -> candidates.sortedBy {
            when (val provider = it.source.identity) {
                MobilePlaybackProvider.FLIXER, PlaybackProviderId.MOVIEPIRE -> 1
                else -> if (provider.isIndianCatalogue) 0 else 2
            }
        }
        else -> candidates
    }
}

internal fun alternativePlaybackSources(
    selection: PlaybackSelection,
    preferences: PlaybackPreferences,
): List<PlaybackSelection> {
    val anime = selection.media.isJapaneseAnime
    val specialist = when {
        anime -> null
        selection.media.type == MediaType.TV -> MobilePlaybackProvider.SERIENSTREAM
        else -> MobilePlaybackProvider.FILMO
    }
    return playbackSourceFallbacks(selection, preferences).drop(1).sortedBy { candidate ->
        val provider = candidate.source.identity
        when {
            selection.media.isIndianTitle && provider.isIndianCatalogue -> 0
            (anime && provider.isAnimeNative) || provider == specialist -> 0
            provider == preferences.effectiveGeneralProvider -> 1
            else -> 2
        }
    }
}

/** Give the chosen catalogue a brief head start, with bounded concurrent alternatives. */
internal suspend fun <T : Any> racePlaybackSources(candidates: List<PlaybackSelection>, attempt: suspend (PlaybackSelection) -> T): T {
    val indianCatalogue = candidates.firstOrNull()?.source?.identity?.isIndianCatalogue == true
    val filmOAttempt = candidates.firstOrNull()
        ?.takeIf { it.source.identity == MobilePlaybackProvider.FILMO }
        ?.let { CompletableDeferred<Unit>() }
    return firstSuccessful(candidates.mapIndexed { index, candidate -> suspend {
        if (index == 0) {
            // FilmO must open an external host and validate its media before it can win.
            try {
                attempt(candidate)
            } finally {
                filmOAttempt?.complete(Unit)
            }
        } else {
            if (indianCatalogue && (candidate.source.identity.isIndianCatalogue ||
                    candidate.source.identity == MobilePlaybackProvider.FLIXER ||
                    candidate.source.identity == PlaybackProviderId.MOVIEPIRE)) delay(250)
            else if (filmOAttempt == null) delay(900)
            else withTimeoutOrNull(8_000) { filmOAttempt.await() }
            attempt(candidate)
        }
    } }, parallelism = 4)
}
