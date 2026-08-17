package com.aliflix.app.player

import java.net.URI

object PlaybackNavigationPolicy {
    private val defaultApprovedPlaybackHosts = setOf(
        "ramoflix.net",
        "moviepire.ru",
        "doraby.com",
        "moviepire.net",
        "moviepire.com",
        "player.vidlove.cc",
        "345movie.nl",
        "456movie.nl",
        "67movie.nl",
        "67movies.nl",
        "67movie.org",
        "67movies.org",
        "67movies.net",
        "player.videasy.net",
        "player.videasy.to",
        "vidlink.pro",
        "111movies.net",
        "nontongo.win",
        "soap2night.cc",
        "cloudorchestranova.com",
    )

    /**
     * Host suffixes which are never required for playback. Keep this list deterministic: the
     * WebView client uses it before a response body is executed and the document-start shield
     * mirrors it for dynamically-created script/iframe elements.
     */
    internal val blockedAdvertisingHosts = setOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "adnxs.com",
        "criteo.com",
        "taboola.com",
        "outbrain.com",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "monetag.com",
        "adsterra.com",
        "exoclick.com",
        "trafficjunky.net",
        "juicyads.com",
        "clickadu.com",
        "hilltopads.net",
        "realsrv.com",
        "onclicka.com",
        "onclickperformance.com",
        "adskeeper.co.uk",
        "mgid.com",
        "revcontent.com",
        "admaven.com",
        "ad-maven.com",
        "pushground.com",
        "richads.com",
        "evadav.com",
        "zeropark.com",
        "clickaine.com",
        "cpmstar.com",
        "yllix.com",
        "adcash.com",
        "directrev.com",
        // Moviepire wrapper advertising observed in production.
        "balkersestian.com",
        "crowdsynonym.com",
        "push-sdk.com",
        "mploejuiashsatea.com",
        "dcbbwymp1bhlf.cloudfront.net",
        "blowersdialer.com",
        "profitablebutton.com",
        // The default Moviepire (Azute/vidrock) player advertising and redirect helpers.
        "acscdn.com",
        "hebamicmopeds.com",
        "histats.com",
        "sbx-2dl.pages.dev",
    )

    /**
     * Origins used by Moviepire's selectable embedded players. This is deliberately an origin
     * list, not a network allowlist: video manifests and segments can still come from provider
     * CDNs, while the document-start shield is limited to Moviepire/player documents.
     */
    internal val moviepirePlayerDocumentHosts = setOf(
        "moviepire.com",
        "moviepire.net",
        "moviepire.ru",
        "moviepire.co",
        "vidrock.ru",
        "videasy.net",
        "vidnest.fun",
        "xpass.top",
        "peachify.top",
        "vidcore.net",
        "vaplayer.ru",
        "zxcstream.xyz",
        "nhdapi.com",
        "cinesrc.st",
        "vidlux.site",
        "vidsrc.cc",
        "cineby.homes",
        "vidup.to",
        "tanime.tv",
        "vixsrc.to",
        "vidify.top",
        "vidking.net",
        "vidrush.net",
        "vidzee.wtf",
        "mapple.uk",
        "anyembed.xyz",
        "rivestream.org",
        "vidlink.pro",
        "vidfast.pro",
        "vidflix.club",
        "vidsrc.su",
        "vidsrc.wtf",
        "vidsrcme.ru",
        "2embed.stream",
        "primesrc.me",
        "frembed.asia",
        "moviesapi.to",
        "111movies.com",
        "autoembed.app",
        "modocine.com",
        "vidplus.to",
        "superflixapi.buzz",
        "uembed.xyz",
    )

    fun isAllowedTopLevel(url: String, customHosts: Set<String> = emptySet()): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host?.lowercase() ?: return false
        val allHosts = defaultApprovedPlaybackHosts + customHosts
        uri.scheme.equals("https", ignoreCase = true) &&
            allHosts.any { approved ->
                val cleanApproved = approved.lowercase().removePrefix("www.")
                val cleanHost = host.removePrefix("www.")
                cleanHost == cleanApproved || cleanHost.endsWith(".$cleanApproved")
            }
    }.getOrDefault(false)

    fun isBlockedAdResource(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.cleanHost() ?: return false
        (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            host.matchesAnySuffix(blockedAdvertisingHosts)
    }.getOrDefault(false)

    /**
     * Moviepire-specific network containment. In addition to known advertising hosts, block the
     * current Azute anti-embed redirect and unambiguous ad/push script paths. Legitimate player
     * pages, manifests, subtitles, video segments, casting scripts, and API calls remain allowed.
     */
    fun isBlockedMoviepireResource(url: String): Boolean = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "https" && scheme != "http") return false
        if (isBlockedAdResource(url)) return true

        val host = uri.cleanHost() ?: return false
        val path = uri.rawPath.orEmpty().lowercase()
        if (host.matchesAnySuffix(setOf("vidrock.ru")) && path == "/sbx.js") return true

        val hostLabels = host.split('.')
        val hasUnambiguousAdLabel = hostLabels.any { label ->
            label in setOf(
                "ad",
                "ads",
                "adserver",
                "adservice",
                "clickunder",
                "popunder",
                "pushads",
            )
        }
        val hasUnambiguousAdPath = listOf(
            "/popunder",
            "/clickunder",
            "/push-sdk",
            "/adserver/",
            "/ads/",
        ).any(path::contains)
        hasUnambiguousAdLabel || hasUnambiguousAdPath
    }.getOrDefault(false)

    /** Returns true when a Moviepire child frame navigation must be cancelled synchronously. */
    fun isBlockedMoviepireSubframeNavigation(url: String): Boolean = runCatching {
        if (url.equals("about:blank", ignoreCase = true)) return false
        val scheme = URI(url).scheme?.lowercase() ?: return true
        when (scheme) {
            "http", "https" -> isBlockedMoviepireResource(url)
            // MediaSource playback commonly uses blob URLs, but external-app and script schemes
            // are never needed as frame destinations.
            "blob" -> false
            else -> true
        }
    }.getOrDefault(true)

    private fun URI.cleanHost(): String? = host?.lowercase()?.removePrefix("www.")

    private fun String.matchesAnySuffix(suffixes: Set<String>): Boolean =
        suffixes.any { suffix -> this == suffix || endsWith(".$suffix") }
}
