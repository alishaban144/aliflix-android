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
    RIVESTREAM(
        displayName = "Rivestream",
        defaultBaseUrl = "https://www.rivestream.app/",
        supportsGeneralPlayback = true,
    ),
    MOVIES_67(
        displayName = "67 Movies",
        defaultBaseUrl = "https://67movies.nl/",
        supportsGeneralPlayback = true,
    );

    fun isAvailableFor(@Suppress("UNUSED_PARAMETER") media: Media): Boolean =
        supportsGeneralPlayback

    companion object {
        fun fromStoredValue(value: String?): PlaybackProviderId? =
            entries.firstOrNull { provider ->
                provider.name.equals(value, ignoreCase = true) ||
                    provider.displayName.equals(value, ignoreCase = true) ||
                    (
                        provider == RIVESTREAM &&
                            value.equals("rivestream", ignoreCase = true)
                        ) ||
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

    fun buildEntryUrl(
        media: Media,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): String? = when (provider) {
        PlaybackProviderId.RAMOFLIX ->
            RamoflixConfig(baseUrl).buildWatchUrl(media.title)

        PlaybackProviderId.RIVESTREAM -> {
            val base = baseUrl.trimEnd('/')
            if (media.type == MediaType.TV) {
                val s = seasonNumber ?: 1
                val e = episodeNumber ?: 1
                "$base/detail?type=tv&id=${media.id}&season=$s&episode=$e"
            } else {
                "$base/detail?type=movie&id=${media.id}"
            }
        }

        PlaybackProviderId.MOVIES_67 -> {
            val route = if (media.type == MediaType.TV) {
                "/watch/tv/${media.id}/${seasonNumber ?: 1}/${episodeNumber ?: 1}"
            } else {
                "/watch/movie/${media.id}"
            }
            "${baseUrl.trimEnd('/')}$route"
        }
    }

    companion object {
        fun ramoflix(config: RamoflixConfig = RamoflixConfig()) =
            PlaybackSource(PlaybackProviderId.RAMOFLIX, config.baseUrl)

        fun rivestream(
            baseUrl: String = PlaybackProviderId.RIVESTREAM.defaultBaseUrl,
        ) = PlaybackSource(PlaybackProviderId.RIVESTREAM, baseUrl)

        fun movies67(
            baseUrl: String = PlaybackProviderId.MOVIES_67.defaultBaseUrl,
        ) = PlaybackSource(PlaybackProviderId.MOVIES_67, baseUrl)
    }
}

data class PlaybackPreferences(
    val generalProvider: PlaybackProviderId = PlaybackProviderId.RIVESTREAM,
    val ramoflixConfig: RamoflixConfig = RamoflixConfig(),
    val rivestreamBaseUrl: String = PlaybackProviderId.RIVESTREAM.defaultBaseUrl,
    val movies67BaseUrl: String = PlaybackProviderId.MOVIES_67.defaultBaseUrl,
) {
    val safeGeneralProvider: PlaybackProviderId
        get() = generalProvider.takeIf(PlaybackProviderId::supportsGeneralPlayback)
            ?: PlaybackProviderId.RIVESTREAM

    fun sourceFor(
        media: Media,
        requestedProvider: PlaybackProviderId? = null,
    ): PlaybackSource {
        val provider = requestedProvider
            ?.takeIf { candidate -> candidate.isAvailableFor(media) }
            ?: safeGeneralProvider
        return when (provider) {
            PlaybackProviderId.RAMOFLIX -> PlaybackSource.ramoflix(ramoflixConfig)
            PlaybackProviderId.RIVESTREAM -> PlaybackSource.rivestream(rivestreamBaseUrl)
            PlaybackProviderId.MOVIES_67 -> PlaybackSource.movies67(movies67BaseUrl)
        }
    }
}
