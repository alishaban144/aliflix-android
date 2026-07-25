package com.aliflix.app.model

import com.aliflix.app.data.RamoflixConfig
import java.net.URI

enum class PlaybackProviderId(
    val displayName: String,
    val defaultBaseUrl: String,
    val supportsGeneralPlayback: Boolean,
) {
    RAMOFLIX(
        displayName = "Ramoflix",
        defaultBaseUrl = RamoflixConfig.DEFAULT_URL,
        supportsGeneralPlayback = true,
    ),
    MOVIES_67(
        displayName = "67 Movies",
        defaultBaseUrl = "https://67movies.nl/",
        supportsGeneralPlayback = true,
    ),
    MIRURO(
        displayName = "Miruro",
        defaultBaseUrl = "https://www.miruro.tv/",
        supportsGeneralPlayback = false,
    );

    companion object {
        fun fromStoredValue(value: String?): PlaybackProviderId? =
            entries.firstOrNull { provider ->
                provider.name.equals(value, ignoreCase = true) ||
                    provider.displayName.equals(value, ignoreCase = true) ||
                    (
                        provider == MOVIES_67 &&
                            value.equals("67movies", ignoreCase = true)
                        )
            }
    }
}

data class PlaybackSource(
    val provider: PlaybackProviderId,
    val baseUrl: String = provider.defaultBaseUrl,
) {
    val cleanDomain: String
        get() = runCatching {
            URI(baseUrl).host?.removePrefix("www.") ?: baseUrl
        }.getOrDefault(baseUrl)

    val approvedTopLevelHosts: Set<String>
        get() = setOf(cleanDomain).filter(String::isNotBlank).toSet()

    /**
     * Miruro uses AniList IDs rather than TMDB IDs, so its URL is resolved
     * asynchronously by the player controller.
     */
    fun buildEntryUrl(
        media: Media,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): String? = when (provider) {
        PlaybackProviderId.RAMOFLIX ->
            RamoflixConfig(baseUrl).buildWatchUrl(media.title)

        PlaybackProviderId.MOVIES_67 -> {
            val route = if (media.type == MediaType.TV) {
                "/embed/tv/${media.id}/${seasonNumber ?: 1}/${episodeNumber ?: 1}"
            } else {
                "/embed/movie/${media.id}"
            }
            "https://player.vidlove.cc$route" +
                "?autoplay=true&poster=true&chromecast=true&servericon=true" +
                "&setting=true&pip=true&font=Roboto&fontcolor=ffffff&fontsize=20" +
                "&opacity=0.5&primarycolor=ffffff&secondarycolor=ffffff" +
                "&iconcolor=ffffff&server=Dark"
        }

        PlaybackProviderId.MIRURO -> null
    }

    companion object {
        fun ramoflix(config: RamoflixConfig = RamoflixConfig()) =
            PlaybackSource(PlaybackProviderId.RAMOFLIX, config.baseUrl)

        fun movies67() = PlaybackSource(PlaybackProviderId.MOVIES_67)

        fun miruro() = PlaybackSource(PlaybackProviderId.MIRURO)
    }
}

data class PlaybackPreferences(
    val generalProvider: PlaybackProviderId = PlaybackProviderId.RAMOFLIX,
    val ramoflixConfig: RamoflixConfig = RamoflixConfig(),
) {
    val safeGeneralProvider: PlaybackProviderId
        get() = generalProvider.takeIf(PlaybackProviderId::supportsGeneralPlayback)
            ?: PlaybackProviderId.RAMOFLIX

    fun sourceFor(
        media: Media,
        requestedProvider: PlaybackProviderId? = null,
    ): PlaybackSource {
        if (media.isJapaneseAnime) return PlaybackSource.miruro()

        val provider = requestedProvider
            ?.takeIf(PlaybackProviderId::supportsGeneralPlayback)
            ?: safeGeneralProvider
        return when (provider) {
            PlaybackProviderId.RAMOFLIX -> PlaybackSource.ramoflix(ramoflixConfig)
            PlaybackProviderId.MOVIES_67 -> PlaybackSource.movies67()
            PlaybackProviderId.MIRURO -> PlaybackSource.ramoflix(ramoflixConfig)
        }
    }
}
