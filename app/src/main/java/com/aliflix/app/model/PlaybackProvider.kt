package com.aliflix.app.model

import com.aliflix.app.data.RamoflixConfig
import java.net.URI

enum class PlaybackProviderId(
    val displayName: String,
    val defaultBaseUrl: String,
    val supportsGeneralPlayback: Boolean,
    val isBeta: Boolean = false,
) {
    RAMOFLIX(
        displayName = "Ramoflix",
        defaultBaseUrl = RamoflixConfig.DEFAULT_URL,
        supportsGeneralPlayback = true,
    ),
    DORABY(
        displayName = "Doraby",
        defaultBaseUrl = "https://doraby.com/",
        supportsGeneralPlayback = true,
    ),
    MOVIEPIRE(
        displayName = "Moviepire",
        defaultBaseUrl = "https://moviepire.ru/",
        supportsGeneralPlayback = true,
        isBeta = true,
    );

    fun isAvailableFor(@Suppress("UNUSED_PARAMETER") media: Media): Boolean =
        supportsGeneralPlayback

    companion object {
        fun fromStoredValue(value: String?): PlaybackProviderId? =
            entries.firstOrNull { provider ->
                provider.name.equals(value, ignoreCase = true) ||
                    provider.displayName.equals(value, ignoreCase = true) ||
                    (
                        provider == MOVIEPIRE &&
                            value.equals("bcine", ignoreCase = true)
                        ) ||
                    (
                        provider == DORABY &&
                            value.equals("doraby", ignoreCase = true)
                        ) ||
                    (
                        provider == MOVIEPIRE &&
                            value.equals("moviepire", ignoreCase = true)
                        )
            }
    }
}

internal fun defaultGeneralPlaybackProvider(isTv: Boolean): PlaybackProviderId =
    if (isTv) PlaybackProviderId.RAMOFLIX else PlaybackProviderId.MOVIEPIRE

internal fun mobileGeneralPlaybackProviders(): List<PlaybackProviderId> = buildList {
    add(PlaybackProviderId.MOVIEPIRE)
    addAll(
        PlaybackProviderId.entries.filter { provider ->
            provider.supportsGeneralPlayback && provider != PlaybackProviderId.MOVIEPIRE
        },
    )
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

        PlaybackProviderId.MOVIEPIRE -> {
            val base = baseUrl.trimEnd('/')
            val route = if (media.type == MediaType.TV) {
                val s = seasonNumber ?: 1
                val e = episodeNumber ?: 1
                "/watch/${media.id}?s=$s&e=$e"
            } else {
                "/watch/${media.id}"
            }
            "$base$route"
        }

        PlaybackProviderId.DORABY -> {
            val base = baseUrl.trimEnd('/')
            val slug = media.title.lowercase()
                .replace(Regex("[^a-z0-9\\s-]"), "")
                .trim()
                .replace(Regex("\\s+"), "-")
            "$base/$slug/"
        }
    }

    companion object {
        fun ramoflix(config: RamoflixConfig = RamoflixConfig()) =
            PlaybackSource(PlaybackProviderId.RAMOFLIX, config.baseUrl)

        fun moviepire(
            baseUrl: String = PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
        ) = PlaybackSource(PlaybackProviderId.MOVIEPIRE, baseUrl)

        fun doraby(
            baseUrl: String = PlaybackProviderId.DORABY.defaultBaseUrl,
        ) = PlaybackSource(PlaybackProviderId.DORABY, baseUrl)
    }
}

data class PlaybackPreferences(
    val generalProvider: PlaybackProviderId = PlaybackProviderId.RAMOFLIX,
    val ramoflixConfig: RamoflixConfig = RamoflixConfig(),
    val dorabyBaseUrl: String = PlaybackProviderId.DORABY.defaultBaseUrl,
    val moviepireBaseUrl: String = PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
) {
    val safeGeneralProvider: PlaybackProviderId
        get() = generalProvider.takeIf(PlaybackProviderId::supportsGeneralPlayback)
            ?: PlaybackProviderId.RAMOFLIX

    fun sourceFor(
        media: Media,
        requestedProvider: PlaybackProviderId? = null,
    ): PlaybackSource {
        val provider = requestedProvider
            ?.takeIf { candidate -> candidate.isAvailableFor(media) }
            ?: safeGeneralProvider
        return when (provider) {
            PlaybackProviderId.RAMOFLIX -> PlaybackSource.ramoflix(ramoflixConfig)
            PlaybackProviderId.MOVIEPIRE -> PlaybackSource.moviepire(moviepireBaseUrl)
            PlaybackProviderId.DORABY -> PlaybackSource.doraby(dorabyBaseUrl)
        }
    }
}
