package com.aliflix.app.player

import java.net.URI

object PlaybackNavigationPolicy {
    private val defaultApprovedPlaybackHosts = setOf(
        "ramoflix.net",
        "67movies.nl",
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
}
