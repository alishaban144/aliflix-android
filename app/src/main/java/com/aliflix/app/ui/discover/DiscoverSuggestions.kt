package com.aliflix.app.ui.discover

import com.aliflix.app.recommendation.RecommendationMediaKind
import kotlin.random.Random

internal data class DiscoverSuggestion(
    val id: String,
    val prompt: String,
    val mediaKind: RecommendationMediaKind,
)

internal val discoverSuggestionLibrary = listOf(
    DiscoverSuggestion(
        id = "movie-scary-120",
        prompt = "Something scary under 120 minutes",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "movie-heist-smart",
        prompt = "A clever heist movie with a great ensemble",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "movie-hidden-gem",
        prompt = "A recent hidden-gem movie with a hopeful ending",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "movie-rainy-night",
        prompt = "An atmospheric mystery for a rainy night",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "movie-sci-fi-human",
        prompt = "Thoughtful science fiction with a human story",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "movie-comedy-friends",
        prompt = "A funny movie to watch with friends",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "movie-quiet-drama",
        prompt = "A beautifully shot quiet drama from the last decade",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "movie-twist",
        prompt = "A tense movie with a genuinely earned twist",
        mediaKind = RecommendationMediaKind.MOVIE,
    ),
    DiscoverSuggestion(
        id = "series-breaking-bad",
        prompt = "Series similar to Breaking Bad",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
    DiscoverSuggestion(
        id = "series-finished-mystery",
        prompt = "A finished mystery series that stays strong",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
    DiscoverSuggestion(
        id = "series-short-episodes",
        prompt = "A feel-good series with short episodes",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
    DiscoverSuggestion(
        id = "series-hidden-gem",
        prompt = "An overlooked crime series with excellent writing",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
    DiscoverSuggestion(
        id = "series-family",
        prompt = "A warm series the whole family can enjoy",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
    DiscoverSuggestion(
        id = "series-mind-bending",
        prompt = "A mind-bending series with a complete story",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
    DiscoverSuggestion(
        id = "series-comedy",
        prompt = "A smart comedy series that gets good quickly",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
    DiscoverSuggestion(
        id = "series-slow-burn",
        prompt = "A character-driven slow-burn thriller series",
        mediaKind = RecommendationMediaKind.SERIES,
    ),
)

/**
 * Creates a fresh process-session order without using a fixed seed. The UI saves
 * the returned ids, so configuration changes do not reshuffle what the user saw.
 */
internal fun createSessionSuggestionOrder(
    random: Random = Random.Default,
): List<String> {
    val movies = discoverSuggestionLibrary
        .filter { it.mediaKind == RecommendationMediaKind.MOVIE }
        .shuffled(random)
        .take(6)
    val series = discoverSuggestionLibrary
        .filter { it.mediaKind == RecommendationMediaKind.SERIES }
        .shuffled(random)
        .take(6)
    return (movies + series)
        .shuffled(random)
        .map(DiscoverSuggestion::id)
}

/**
 * One immutable sample per app process. Activity recreation reuses it, while a
 * genuinely cold process gets fresh entropy instead of restoring an old order.
 */
private val processSessionSuggestionOrder: List<String> by lazy(
    LazyThreadSafetyMode.PUBLICATION,
) {
    createSessionSuggestionOrder()
}

internal fun currentSessionSuggestionOrder(): List<String> =
    processSessionSuggestionOrder

internal fun suggestionsForSession(
    order: List<String>,
    mediaKind: RecommendationMediaKind?,
    limit: Int = 6,
): List<DiscoverSuggestion> {
    val byId = discoverSuggestionLibrary.associateBy(DiscoverSuggestion::id)
    return order.asSequence()
        .mapNotNull(byId::get)
        .filter { mediaKind == null || it.mediaKind == mediaKind }
        .distinctBy { it.prompt }
        .take(limit.coerceAtLeast(0))
        .toList()
}
