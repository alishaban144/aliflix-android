package com.aliflix.app.player

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.MobilePlaybackProvider
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

internal object IndianPlaybackCatalogue {
    private val playerHosts = setOf(
        "moviesapi.to", "megacloudx.net", "vidfast.vc", "vidfast.pro", "vixsrc.to",
        "vidsrcme.ru", "vidsrc.cc", "primesrc.me", "player.vidlove.cc",
    )
    private val hiMoviesId = Regex("[A-Za-z0-9]{3,40}")
    private val yearPattern = Regex("(?:18|19|20)[0-9]{2}")
    private val cinegoPlayers = Regex("""\bconst\s+pl_url\s*=\s*['"]([^'"]+)['"]""")

    suspend fun resolveServers(selection: PlaybackSelection): List<SitePlaybackLink> = withTimeout(11_000) {
        val hiMovies = selection.source.identity == MobilePlaybackProvider.HIMOVIES
        val base = selection.source.baseUrl.trimEnd('/')
        val query = URLEncoder.encode(selection.media.title.trim(), "UTF-8")
        val url = if (hiMovies) "$base/search/${query.lowercase(Locale.ROOT).replace('+', '-')}"
            else "$base/search?keyword=$query"
        val search = page(url, base)
        val verified = findTitle(search, base, selection.media, hiMovies)
        val title = verified ?: findTitle(search, base, selection.media, hiMovies, allowMissingYear = true)
            ?: throw ProviderItemUnavailable(selection.source.identity)
        val links = if (hiMovies) {
            if (verified == null && !releaseMatches(page(title, base), selection.media, true)) {
                throw ProviderItemUnavailable(selection.source.identity)
            }
            hiMoviesServers(base, title, selection)
        } else cinegoServers(base, title, selection, verifyRelease = verified == null)
        if (links.isEmpty()) throw ProviderItemUnavailable(selection.source.identity)
        links
    }

    suspend fun playerUrl(link: SitePlaybackLink): String {
        if (link.token.isBlank()) return link.url
        val response = SerienstreamHttp.get(link.url, link.token)
        require(sameProviderOrigin(URI(link.url), URI(response.finalUrl)))
        return verifiedPlayerUrl(JSONObject(response.bytes.toString(Charsets.UTF_8)).getString("link"))
            ?: error("Unrecognized video player")
    }

    private suspend fun hiMoviesServers(base: String, title: String, selection: PlaybackSelection): List<SitePlaybackLink> {
        val id = URI(title).path.substringAfterLast('-').takeIf(hiMoviesId::matches)
            ?: throw ProviderItemUnavailable(selection.source.identity)
        val route = if (selection.media.type == MediaType.MOVIE) "/ajax/episode/list/$id" else {
            val season = selection.seasonNumber ?: 1
            val seasonId = hiMoviesNumberedId(page("$base/ajax/season/list/$id", title), ".ss-item[data-id]", "Season", season)
                ?: throw ProviderItemUnavailable(selection.source.identity)
            val episodeId = hiMoviesNumberedId(page("$base/ajax/season/episodes/$seasonId", title), ".eps-item[data-id]", "Eps", selection.episodeNumber ?: 1)
                ?: throw ProviderItemUnavailable(selection.source.identity)
            "/ajax/episode/servers/$episodeId"
        }
        return parseHiMoviesServers(page(base + route, title), base, title)
    }

    private suspend fun cinegoServers(base: String, title: String, selection: PlaybackSelection, verifyRelease: Boolean): List<SitePlaybackLink> {
        val tv = selection.media.type == MediaType.TV
        val season = selection.seasonNumber ?: 1
        val watch = if (tv) "${title.trimEnd('/')}/$season-${selection.episodeNumber ?: 1}/" else title
        val detail = page(watch, title)
        if (verifyRelease && !releaseMatches(detail, selection.media, false)) throw ProviderItemUnavailable(selection.source.identity)
        if (tv && (URI(detail.location()).path.trimEnd('/') != URI(watch).path.trimEnd('/') ||
                detail.selectFirst("#current-ss")?.text()?.trim() != "Season $season")) {
            throw ProviderItemUnavailable(selection.source.identity)
        }
        val action = detail.select("script").firstNotNullOfOrNull { cinegoPlayers.find(it.data())?.groupValues?.get(1) }
            ?.let { sameSite(it, base, Regex("/ajax/ajax\\.php")) }
            ?: throw ProviderItemUnavailable(selection.source.identity)
        return parseCinegoServers(page(action, watch))
    }

    private suspend fun page(url: String, referer: String): Document {
        val response = SerienstreamHttp.get(url, referer)
        require(sameProviderOrigin(URI(url), URI(response.finalUrl)))
        return Jsoup.parse(response.bytes.toString(Charsets.UTF_8), response.finalUrl)
    }

    internal fun findTitle(document: Document, base: String, media: Media, hiMovies: Boolean, allowMissingYear: Boolean = false): String? {
        val type = if (media.type == MediaType.MOVIE) "movie" else if (hiMovies) "tv" else "series"
        val allowedPath = Regex("/$type/[^/]+/?")
        val expected = normalizeTitle(media.title)
        val year = media.year.take(4).takeIf(yearPattern::matches) ?: return null
        val dates = if (hiMovies) ".fdi-item" else ".film-info .item"
        return document.select(if (hiMovies) ".flw-item" else ".item-film").mapNotNull { card ->
            val anchor = card.selectFirst(".film-name a[href]") ?: return@mapNotNull null
            val listedYears = card.select(dates).map { it.text() }.filter(yearPattern::matches)
            if (normalizeTitle(anchor.text()) != expected || (year !in listedYears && !(allowMissingYear && listedYears.isEmpty()))) {
                return@mapNotNull null
            }
            sameSite(anchor.attr("href"), base, allowedPath)
        }.distinct().singleOrNull()
    }

    internal fun releaseMatches(document: Document, media: Media, hiMovies: Boolean): Boolean {
        val expected = media.year.take(4).takeIf(yearPattern::matches) ?: return false
        val date = if (hiMovies) document.selectFirst("[data-field=released-value]")?.text()
            else document.select(".item").firstOrNull { it.selectFirst(".name")?.text() == "Release" }
                ?.selectFirst(".item-body span")?.text()
        return date?.take(4) == expected
    }

    internal fun hiMoviesNumberedId(document: Document, selector: String, label: String, number: Int): String? {
        val cue = Regex("^${Regex.escape(label)}\\s+([0-9]+)(?:\\s*:|\\s*$)", RegexOption.IGNORE_CASE)
        return document.select(selector).firstNotNullOfOrNull { item ->
            val text = item.attr("title").ifBlank(item::text)
            item.attr("data-id").takeIf { cue.find(text)?.groupValues?.get(1)?.toIntOrNull() == number && hiMoviesId.matches(it) }
        }
    }

    internal fun parseHiMoviesServers(document: Document, base: String, referer: String): List<SitePlaybackLink> =
        document.select(".link-item[data-id]").mapNotNull { item ->
            val id = item.attr("data-id").takeIf(hiMoviesId::matches) ?: return@mapNotNull null
            val name = item.text().trim().takeIf { it.length in 1..40 } ?: return@mapNotNull null
            SitePlaybackLink(name, "", "$base/ajax/episode/sources/$id", token = referer)
        }.distinctBy { it.server }.sortedBy { !it.server.equals("MegaCloud", true) }

    internal fun parseCinegoServers(document: Document): List<SitePlaybackLink> =
        document.select(".sv-item[data-id][data-srv]").mapNotNull { item ->
            val url = verifiedPlayerUrl(item.attr("data-id")) ?: return@mapNotNull null
            val label = item.attr("data-srv").trim().takeIf { it.length in 1..40 } ?: return@mapNotNull null
            SitePlaybackLink(label, "", url)
        }.distinctBy { it.url }

    private fun verifiedPlayerUrl(value: String): String? = runCatching {
        val uri = URI(value)
        value.takeIf { uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.") in playerHosts }
    }.getOrNull()

    private fun sameSite(value: String, base: String, path: Regex): String? = runCatching {
        val origin = URI("$base/")
        val url = origin.resolve(value)
        url.takeIf { sameProviderOrigin(origin, it) && path.matches(it.path) }?.toString()
    }.getOrNull()

    private fun normalizeTitle(value: String): String = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
}
