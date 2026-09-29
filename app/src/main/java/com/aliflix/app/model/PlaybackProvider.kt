package com.aliflix.app.model

import com.aliflix.app.data.RamoflixConfig
import java.net.URI

sealed interface PlaybackProvider {
    val name: String
    val displayName: String
    val defaultBaseUrl: String
    val supportsGeneralPlayback: Boolean
    val isBeta: Boolean
    val usesMoviepire: Boolean
    val isAnimeNative: Boolean
    fun isAvailableFor(media: Media): Boolean

    companion object {
        fun fromStoredValue(value: String?): PlaybackProvider? =
            PlaybackProviderId.fromStoredValue(value) ?: MobilePlaybackProvider.entries.firstOrNull {
                !com.aliflix.app.BuildConfig.IS_TV && (it.name.equals(value, true) || it.displayName.equals(value, true))
            }

        fun valueOf(value: String): PlaybackProvider =
            requireNotNull(fromStoredValue(value)) { "Unknown playback provider: $value" }
    }
}

enum class MobilePlaybackProvider(
    override val displayName: String,
    override val defaultBaseUrl: String,
) : PlaybackProvider {
    SEVEN_MOVIES("7Movies", "https://7movies.ac/"),
    MOVY("Movy", "https://www.movy.sx/");

    override val supportsGeneralPlayback: Boolean get() = !com.aliflix.app.BuildConfig.IS_TV
    override val isBeta: Boolean = false
    override val usesMoviepire: Boolean = false
    override val isAnimeNative: Boolean = false
    override fun isAvailableFor(media: Media): Boolean = supportsGeneralPlayback
}

enum class PlaybackProviderId(
    override val displayName: String,
    override val defaultBaseUrl: String,
    override val supportsGeneralPlayback: Boolean,
    override val isBeta: Boolean = false,
) : PlaybackProvider {
    CINEJOY(
        displayName = "CineJoy",
        defaultBaseUrl = "https://cinejoy.pk/",
        supportsGeneralPlayback = true,
    ),
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
    ),
    MIRURO(
        displayName = "Miruro",
        defaultBaseUrl = "https://www.miruro.tv/",
        supportsGeneralPlayback = false,
    ),
    ANIKURO(
        displayName = "AniKuro",
        defaultBaseUrl = "https://anikuro.to/",
        supportsGeneralPlayback = false,
    );

    override val usesMoviepire: Boolean
        get() = this == MOVIEPIRE

    /** Anime-only sources resolved by a native catalogue and raced against each other. */
    override val isAnimeNative: Boolean
        get() = this == MIRURO || this == ANIKURO

    override fun isAvailableFor(media: Media): Boolean =
        (supportsGeneralPlayback && (this != CINEJOY || !com.aliflix.app.BuildConfig.IS_TV)) || (isAnimeNative && !com.aliflix.app.BuildConfig.IS_TV && media.isJapaneseAnime)

    companion object {
        fun fromStoredValue(value: String?): PlaybackProviderId? =
            if (
                value.equals("MOVIEPIRE_NATIVE", ignoreCase = true) ||
                value.equals("Moviepire Native", ignoreCase = true)
            ) {
                MOVIEPIRE
            } else entries.firstOrNull { provider ->
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
    if (isTv) PlaybackProviderId.RAMOFLIX else PlaybackProviderId.CINEJOY

internal fun mobileGeneralPlaybackProviders(): List<PlaybackProvider> = buildList {
    add(PlaybackProviderId.CINEJOY)
    add(PlaybackProviderId.MOVIEPIRE)
    addAll(
        PlaybackProviderId.entries.filter { provider ->
            provider.supportsGeneralPlayback && !provider.usesMoviepire && provider != PlaybackProviderId.CINEJOY
        },
    )
    addAll(MobilePlaybackProvider.entries.filter { it.supportsGeneralPlayback })
}

enum class SubtitleLanguage(
    val code: String,
    val displayName: String,
) {
    ENGLISH("EN", "English"),
    ARABIC("AR", "Arabic"),
    GERMAN("DE", "German"),
    SPANISH("ES", "Spanish"),
    FRENCH("FR", "French"),
    ITALIAN("IT", "Italian"),
    PORTUGUESE("PT", "Portuguese"),
    TURKISH("TR", "Turkish"),
    DUTCH("NL", "Dutch"),
    POLISH("PL", "Polish"),
    RUSSIAN("RU", "Russian"),
    UKRAINIAN("UK", "Ukrainian"),
    PERSIAN("FA", "Persian"),
    HINDI("HI", "Hindi"),
    INDONESIAN("ID", "Indonesian"),
    CHINESE("ZH", "Chinese"),
    JAPANESE("JA", "Japanese"),
    KOREAN("KO", "Korean"),
    GREEK("EL", "Greek"),
    SWEDISH("SV", "Swedish"),
    DANISH("DA", "Danish"),
    NORWEGIAN("NO", "Norwegian"),
    FINNISH("FI", "Finnish"),
    ROMANIAN("RO", "Romanian"),
    CZECH("CS", "Czech"),
    HUNGARIAN("HU", "Hungarian"),
    HEBREW("HE", "Hebrew"),
    VIETNAMESE("VI", "Vietnamese"),
    THAI("TH", "Thai"),
    BENGALI("BN", "Bengali"),
    URDU("UR", "Urdu");

    companion object {
        fun fromCode(value: String?): SubtitleLanguage {
            val normalized = value.orEmpty().trim().substringBefore('-').uppercase()
            return entries.firstOrNull { language ->
                language.code == normalized || language.name == normalized
            } ?: ENGLISH
        }
    }
}

data class PlaybackSource(
    val identity: PlaybackProvider,
    val baseUrl: String = identity.defaultBaseUrl,
) {
    /** The unchanged TV caller accepts nullable legacy IDs; mobile uses identity. */
    val provider: PlaybackProviderId?
        get() = identity as? PlaybackProviderId

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
    ): String? = when (identity) {
        PlaybackProviderId.CINEJOY -> baseUrl.trimEnd('/') + if (media.type == MediaType.TV) {
            "/watch/tv/${media.id}/${seasonNumber ?: 1}/${episodeNumber ?: 1}"
        } else "/watch/movie/${media.id}"
        MobilePlaybackProvider.SEVEN_MOVIES -> baseUrl.trimEnd('/') + if (media.type == MediaType.TV) {
            "/tv/${media.id}/watch?season=${seasonNumber ?: 1}&episode=${episodeNumber ?: 1}"
        } else "/movie/${media.id}/watch"
        MobilePlaybackProvider.MOVY -> baseUrl.trimEnd('/') + if (media.type == MediaType.TV) {
            "/tv/${media.id}/${seasonNumber ?: 1}/${episodeNumber ?: 1}?play=true"
        } else "/movie/${media.id}?play=true"
        // The native adapter maps TMDB identity and episode numbering before requesting a stream.
        PlaybackProviderId.MIRURO -> baseUrl
        PlaybackProviderId.ANIKURO -> baseUrl
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
    val generalProvider: PlaybackProvider = PlaybackProviderId.RAMOFLIX,
    val ramoflixConfig: RamoflixConfig = RamoflixConfig(),
    val dorabyBaseUrl: String = PlaybackProviderId.DORABY.defaultBaseUrl,
    val moviepireBaseUrl: String = PlaybackProviderId.MOVIEPIRE.defaultBaseUrl,
    val preferredSubtitleLanguage: SubtitleLanguage = SubtitleLanguage.ENGLISH,
    val autoDisplaySubtitles: Boolean = false,
) {
    val safeGeneralProvider: PlaybackProviderId
        get() = (generalProvider as? PlaybackProviderId)?.takeIf { it.supportsGeneralPlayback && (!com.aliflix.app.BuildConfig.IS_TV || it != PlaybackProviderId.CINEJOY) }
            ?: PlaybackProviderId.RAMOFLIX

    val effectiveGeneralProvider: PlaybackProvider
        get() = generalProvider.takeIf { !com.aliflix.app.BuildConfig.IS_TV && it.supportsGeneralPlayback }
            ?: safeGeneralProvider

    fun sourceFor(
        media: Media,
        requestedProvider: PlaybackProvider? = null,
    ): PlaybackSource {
        val provider = requestedProvider
            ?.takeIf { candidate -> candidate.isAvailableFor(media) }
            ?: effectiveGeneralProvider
        return when (provider) {
            PlaybackProviderId.CINEJOY -> PlaybackSource(PlaybackProviderId.CINEJOY)
            MobilePlaybackProvider.SEVEN_MOVIES -> PlaybackSource(MobilePlaybackProvider.SEVEN_MOVIES)
            MobilePlaybackProvider.MOVY -> PlaybackSource(MobilePlaybackProvider.MOVY)
            PlaybackProviderId.RAMOFLIX -> PlaybackSource.ramoflix(ramoflixConfig)
            PlaybackProviderId.MOVIEPIRE -> PlaybackSource.moviepire(moviepireBaseUrl)
            PlaybackProviderId.DORABY -> PlaybackSource.doraby(dorabyBaseUrl)
            PlaybackProviderId.MIRURO -> PlaybackSource(PlaybackProviderId.MIRURO)
            PlaybackProviderId.ANIKURO -> PlaybackSource(PlaybackProviderId.ANIKURO)
        }
    }
}

internal val Media.isJapaneseAnime: Boolean
    get() = originalLanguage.equals("ja", true) &&
        (genres + omdbGenres).any { it.equals("Animation", true) || it.equals("Anime", true) }
