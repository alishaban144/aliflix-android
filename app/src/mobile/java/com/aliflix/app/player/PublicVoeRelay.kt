package com.aliflix.app.player

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI

/** VOE's canonical document delegates the SAME file to a rotating HTTPS origin.
 * This permission is local to that handoff, never a global host allowlist or a
 * browser-bridge grant. Cookies remain scoped by CookieManager to each origin. */
internal fun publicVoeRelayTarget(page:Document):String? {
    val from=URI(page.location())
    if(from.host?.lowercase()?.removePrefix("www.") !in setOf("voe.sx","megankiddifference.com"))return null
    val raw=Regex("window\\.location\\.href\\s*=\\s*['\"](https://[^'\"]+)['\"]")
        .find(page.html())?.groupValues?.get(1) ?: return null
    val to=URI(raw);val host=to.host?.lowercase().orEmpty()
    require(to.scheme=="https" && to.userInfo==null && to.port in setOf(-1,443) && to.fragment==null &&
        host.matches(Regex("(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}")) &&
        host.substringAfterLast('.') !in setOf("localhost","local","internal","test","invalid","example","onion") &&
        from.rawPath==to.rawPath && from.rawQuery==to.rawQuery &&
        from.path.matches(Regex("/(?:e/)?[A-Za-z0-9]+"))) { "Unexpected VOE file handoff" }
    return to.toString()
}

internal suspend fun followPublicVoeRelay(initial:Document,referer:String):Document {
    val target=publicVoeRelayTarget(initial) ?: return initial
    val response=SerienstreamHttp.get(target,referer)
    return Jsoup.parse(String(response.bytes,Charsets.UTF_8),response.finalUrl)
}
