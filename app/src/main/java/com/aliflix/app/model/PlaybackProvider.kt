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
    BCINE(
        displayName = "Bcine",
        defaultBaseUrl = "https://bcine.ru/",
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
                        provider == BCINE &&
                            value.equals("bcine", ignoreCase = true)
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

        PlaybackProviderId.BCINE -> {
            val base = baseUrl.trimEnd('/')
            val route = if (media.type == MediaType.TV) {
                val s = seasonNumber ?: 1
                val e = episodeNumber ?: 1
                "/tv/${media.id}/$s/$e"
            } else {
                "/movie/${media.id}"
            }
            "$base$route"
        }
    }

    companion object {
        fun ramoflix(config: RamoflixConfig = RamoflixConfig()) =
            PlaybackSource(PlaybackProviderId.RAMOFLIX, config.baseUrl)

        fun bcine(
            baseUrl: String = PlaybackProviderId.BCINE.defaultBaseUrl,
        ) = PlaybackSource(PlaybackProviderId.BCINE, baseUrl)
    }
}

data class PlaybackPreferences(
    val generalProvider: PlaybackProviderId = PlaybackProviderId.BCINE,
    val ramoflixConfig: RamoflixConfig = RamoflixConfig(),
    val bcineBaseUrl: String = PlaybackProviderId.BCINE.defaultBaseUrl,
) {
    val safeGeneralProvider: PlaybackProviderId
        get() = generalProvider.takeIf(PlaybackProviderId::supportsGeneralPlayback)
            ?: PlaybackProviderId.BCINE

    fun sourceFor(
        media: Media,
        requestedProvider: PlaybackProviderId? = null,
    ): PlaybackSource {
        val provider = requestedProvider
            ?.takeIf { candidate -> candidate.isAvailableFor(media) }
            ?: safeGeneralProvider
        return when (provider) {
            PlaybackProviderId.RAMOFLIX -> PlaybackSource.ramoflix(ramoflixConfig)
            PlaybackProviderId.BCINE -> PlaybackSource.bcine(bcineBaseUrl)
        }
    }
}
