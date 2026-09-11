package com.aliflix.app.player

import com.aliflix.app.data.RamoflixConfig
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder

/** Ramoflix and Doraby use the Fmovie theme. Resolve each selected site's own catalogue. */
internal class FmovieNativeCatalog {
    suspend fun embeds(selection: PlaybackSelection): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val entry = fmovieSearchUrl(selection)
        val search = Jsoup.connect(entry).timeout(15_000).get()
        val candidates = fmovieTitleLinks(search, selection)
        for (url in candidates) {
            ensureActive()
            val page = try { Jsoup.connect(url).timeout(15_000).get() }
                catch (_: Exception) { ensureActive(); continue }
            val embeds = fmoviePageEmbeds(page, selection)
            if (embeds.isNotEmpty()) return@withContext embeds
        }
        error("${selection.source.provider.displayName} did not provide servers for the selected title")
    }
}

internal fun fmovieSearchUrl(selection: PlaybackSelection): String =
    RamoflixConfig(selection.source.baseUrl).buildWatchUrl(selection.media.title)

internal fun fmovieTitleLinks(page: Document, selection: PlaybackSelection): List<String> =
    page.select(".filmlist .item a[href]").map { it.absUrl("href") }
        .filter { url -> runCatching {
            val uri = URI(url)
            uri.scheme == "https" && uri.host == URI(selection.source.baseUrl).host && uri.userInfo == null
        }.getOrDefault(false) }.distinct().take(20)

internal fun fmoviePageEmbeds(page: Document, selection: PlaybackSelection): List<Pair<String, String>> {
    val tv = selection.media.type == MediaType.TV
    val variable = if (tv) "Episodes" else "Servers"
    val script = page.getElementById(if (tv) "episodes-js-extra" else "servers-js-extra")?.data() ?: return emptyList()
    val raw = Regex("var\\s+$variable\\s*=\\s*(\\{.*?\\})\\s*;", RegexOption.DOT_MATCHES_ALL)
        .find(script)?.groupValues?.get(1) ?: return emptyList()
    val config = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
    // Never select a similarly named title, a remake, or a movie with the same numeric TV id.
    if (config.optString(if (tv) "tvid" else "id") != selection.media.id.toString()) return emptyList()
    return page.select("#servers .server").mapNotNull { server ->
        val name = server.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val url = if (tv) {
            val key = server.attr("data-load-embed-host").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val post = config.optString("post_id").takeIf { it.matches(Regex("[0-9]+")) } ?: return@mapNotNull null
            // The selected provider's own episode endpoint; no season-menu race or default S1E1.
            selection.source.baseUrl.trimEnd('/') + "/?player_tv=$post&s=${selection.seasonNumber ?: 1}&e=${selection.episodeNumber ?: 1}&sv=${URLEncoder.encode(key, "UTF-8")}&tv=true"
        } else {
            val key = Regex("loadServer\\(\\s*([A-Za-z0-9_]+)\\s*\\)")
                .find(server.attr("onclick"))?.groupValues?.get(1) ?: return@mapNotNull null
            config.optString(key)
        }
        if (isNativeStreamUrl(url) && URI(url).scheme == "https") name to url else null
    }.distinctBy { it.first }
}
