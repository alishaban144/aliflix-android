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

    private val blockedAdvertisingHosts = setOf(
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
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        (uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) &&
            blockedAdvertisingHosts.any { blocked ->
                host == blocked || host.endsWith(".$blocked")
            }
    }.getOrDefault(false)
}
