package com.aliflix.app.player

import com.aliflix.app.model.*
import kotlinx.coroutines.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

internal data class SerienstreamLink(val server: String, val language: String, val url: String) {
    val label get() = "${formatAudioTrackLabel(language, null)} · $server"
}

internal class SerienstreamVerificationRequired(val selection: PlaybackSelection, val episodeUrl: String, val language: String, val playUrl: String) : Exception("Serienstream needs a quick human verification")

/** The inspected VOE mirror uses the same file IDs as its canonical public embed. */
internal fun canonicalVoeEmbed(raw: String): String? {
    val uri = runCatching { URI(raw) }.getOrNull() ?: return null
    if (uri.scheme != "https" || uri.userInfo != null || uri.port !in setOf(-1, 443) ||
        uri.host !in setOf("voe.sx", "megankiddifference.com") || !uri.path.orEmpty().matches(Regex("/e/[A-Za-z0-9]+"))) return null
    return "https://voe.sx${uri.path}"
}

/** Site language links are separate editions, not interchangeable HLS audio renditions. */
internal object SerienstreamNativeCatalog {
    const val BASE = "https://serienstream.to"
    const val USER_AGENT = "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36"
    private val titles = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val pages = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<SerienstreamLink>>>()
    private val verifiedEmbeds = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String>>()
    private const val VERIFIED_FRAME_TTL = 10 * 60_000L
    private fun frameKey(selection: PlaybackSelection, language: String) = "${selection.source.baseUrl}:${selection.key}:$language"
    @Volatile private var sessionRestored = false
    @Synchronized fun restoreSession(context: android.content.Context) {
        if (sessionRestored) return
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        val saved = context.getSharedPreferences("serienstream-verified-frames", 0)
        val expired = saved.edit()
        saved.all.forEach { (key, value) ->
            val pieces = (value as? String)?.split('\n', limit = 2).orEmpty()
            val timestamp = pieces.firstOrNull()?.toLongOrNull()
            val url = pieces.getOrNull(1)
            val age = timestamp?.let { System.currentTimeMillis() - it }
            val uri = url?.let { runCatching { URI(it) }.getOrNull() }
            if (timestamp != null && url != null && age != null && age in 0 until VERIFIED_FRAME_TTL && uri?.scheme == "https" && uri.host == "voe.sx" &&
                uri.path.matches(Regex("/e/[A-Za-z0-9]+"))) verifiedEmbeds[key] = timestamp to url
            else expired.remove(key)
        }
        expired.apply()
        sessionRestored = true
    }
    fun recordVerifiedEmbed(context: android.content.Context, selection: PlaybackSelection, language: String, url: String) {
        restoreSession(context)
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "voe.sx" && uri.path.matches(Regex("/e/[A-Za-z0-9]+")))
        val key = frameKey(selection, language)
        val now = System.currentTimeMillis()
        verifiedEmbeds[key] = now to url
        context.getSharedPreferences("serienstream-verified-frames", 0).edit()
            .putString(key, "$now\n$url").apply()
        SerienstreamHttp.persistSession()
    }
    private suspend fun page(url: String, referer: String = BASE): Document {
        val response = SerienstreamHttp.get(url, referer)
        return Jsoup.parse(String(response.bytes, Charsets.UTF_8), response.finalUrl.ifBlank { url })
    }

    suspend fun links(selection: PlaybackSelection): List<SerienstreamLink> = withTimeout(12_000) {
        require(selection.media.type == MediaType.TV) { "Serienstream's catalogue contains series" }
        val key = "${selection.source.baseUrl}:${selection.media.key}:${selection.seasonNumber ?: 1}:${selection.episodeNumber ?: 1}"
        pages[key]?.takeIf { System.currentTimeMillis() - it.first < 120_000 }?.let { return@withTimeout it.second }
        val base = selection.source.baseUrl.trimEnd('/')
        val titleKey = "$base:${selection.media.key}"
        val titleUrl = titles[titleKey] ?: run {
            val slug = java.text.Normalizer.normalize(selection.media.title.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFKD)
                .replace(Regex("\\p{M}"), "").replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val direct = "$base/serie/$slug"
            val directMatch = try { slug.isNotBlank() && serienstreamTitleMatches(page(direct), selection.media) }
                catch (_: Exception) { currentCoroutineContext().ensureActive(); false }
            if (directMatch) return@run direct.also { titles[titleKey] = it }
            val search = page("$base/suche?term=${URLEncoder.encode(selection.media.title, "UTF-8")}")
            val candidates = serienstreamTitleCandidates(search, selection.media)
            val verified = candidates.map { url -> suspend {
                val title = page(url)
                if (serienstreamTitleMatches(title, selection.media)) url else error("Title identity mismatch")
            } }
            require(verified.isNotEmpty()) { "Serienstream has no matching series" }
            firstSuccessful(verified, parallelism = 2).also { titles[titleKey] = it }
        }
        val episode = "$titleUrl/staffel-${selection.seasonNumber ?: 1}/episode-${selection.episodeNumber ?: 1}"
        serienstreamLinks(page(episode)).also {
            require(it.isNotEmpty()) { "Serienstream has no supported video hosts for this episode" }
            pages[key] = System.currentTimeMillis() to it
        }
    }

    suspend fun resolve(selection: PlaybackSelection, positionMs: Long, excluded: Set<String>,
        preferredServer: String?, strict: Boolean, onServers: (List<String>) -> Unit,
        onServer: (String) -> Unit, audioLanguage: String? = null): NativePlaybackRequest = withTimeout(15_000) {
        val links = links(selection)
        onServers(links.map { it.label })
        val preferredLanguage = audioLanguage ?: links.firstOrNull { it.label == preferredServer }?.language
            ?: selection.media.originalLanguage.takeIf { it.isNotBlank() } ?: "en"
        val available = links.filter { it.label !in excluded && (!strict || it.label == preferredServer) }
        require(available.isNotEmpty()) { "The selected Serienstream language or host is unavailable" }
        val sameLanguage = available.filter { it.language.equals(preferredLanguage, true) }
        // Try the original language's hosts first. Explicit audio choices never fall through to another language.
        if (audioLanguage != null) require(sameLanguage.isNotEmpty()) { "Selected audio is unavailable" }
        val candidates = (sameLanguage.ifEmpty { available }).sortedBy { if (it.label == preferredServer) 0 else 1 }
        val attempts = candidates.map { link -> suspend {
            val request = resolveLink(selection, link, positionMs)
            link to request
        } }
        val (link, request) = firstSuccessful(attempts, parallelism = 2)
        onServer(link.label)
        request.copy(sourceAudioLanguage = link.language, sourceAudioOptions = JSONArray().apply {
            links.distinctBy { it.language }.forEach { put(JSONObject().put("language", it.language)
                .put("label", formatAudioTrackLabel(it.language, null))) }
        }.toString())
    }

    private suspend fun resolveLink(selection: PlaybackSelection, link: SerienstreamLink, positionMs: Long): NativePlaybackRequest {
        val verified = verifiedEmbeds[frameKey(selection, link.language)]
            ?.takeIf { System.currentTimeMillis() - it.first < VERIFIED_FRAME_TTL }?.second
        var document = page(verified ?: link.url)
        if (document.html().contains("frameBridge")) {
            val title = titles["${selection.source.baseUrl.trimEnd('/')}:${selection.media.key}"] ?: error("Missing provider title")
            throw SerienstreamVerificationRequired(selection, "$title/staffel-${selection.seasonNumber ?: 1}/episode-${selection.episodeNumber ?: 1}", link.language, link.url)
        }
        // VOE rotates its embed hostname through a same-file HTTPS document redirect.
        repeat(2) {
            val redirect = Regex("window\\.location\\.href\\s*=\\s*['\"](https://[^'\"]+)['\"]")
                .find(document.html())?.groupValues?.get(1)
            if (redirect != null) {
                require(URI(redirect).path.matches(Regex("/e/[A-Za-z0-9]+"))) { "Unexpected video document redirect" }
                document = page(redirect, link.url)
            }
        }
        if (document.select("script[type=application/json]").isEmpty()) {
            android.util.Log.w("AliflixSerienstream", "Host configuration absent: host=${URI(document.location()).host}, title=${document.title()}")
        }
        val config = decodeVoeConfig(document)
        val stream = config.optString("source").takeIf(::isNativeStreamUrl)
            ?: error("VOE did not expose a playable stream")
        // Preserve the multivariant master, subtitles and embedded audio exactly as supplied by the host.
        return NativePlaybackRequest(stream, if (stream.substringBefore('?').endsWith(".m3u8")) "application/x-mpegURL" else "video/mp4",
            document.location(), USER_AGENT, "", selection.media.title, positionMs, false,
            selectionJson = selection.nativeJson(), sourceSubtitleOptions = config.optJSONArray("captions")?.toString().orEmpty())
    }
}

internal fun serienstreamTitleCandidates(page: Document, media: Media): List<String> =
    page.select(".show-cover[href]").mapNotNull { anchor ->
        val title = anchor.selectFirst(".show-title")?.text() ?: anchor.attr("title").ifBlank { anchor.selectFirst("img")?.attr("alt").orEmpty() }
        val uri = runCatching { URI(anchor.absUrl("href")) }.getOrNull() ?: return@mapNotNull null
        if (uri.host != URI(page.location()).host || uri.scheme != "https" || !uri.path.matches(Regex("/serie/[^/]+"))) return@mapNotNull null
        uri.toString() to (normalizedSerienTitle(title) == normalizedSerienTitle(media.title))
    }.distinctBy { it.first }.sortedByDescending { it.second }.take(8).map { it.first }

private fun normalizedSerienTitle(title: String) = java.text.Normalizer.normalize(title.lowercase(java.util.Locale.ROOT), java.text.Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")

internal fun serienstreamTitleMatches(page: Document, media: Media): Boolean {
    val imdb = page.select("a[href*=imdb.com/title/]").mapNotNull { Regex("tt[0-9]+").find(it.attr("href"))?.value }.distinct()
    if (!media.imdbId.isNullOrBlank() && imdb.isNotEmpty()) return media.imdbId in imdb
    val title = page.selectFirst("h1")?.text().orEmpty()
    val year = page.select("a[href^=/jahr/]").firstOrNull()?.text().orEmpty()
    return normalizedSerienTitle(title) == normalizedSerienTitle(media.title) &&
        media.year.take(4).isNotBlank() && year == media.year.take(4)
}

internal fun serienstreamLinks(page: Document): List<SerienstreamLink> = page.select("#episode-links [data-play-url]").mapNotNull { button ->
    // Other host adapters can be added when verified. Never scrape unrelated advertising frames.
    if (!button.attr("data-provider-name").equals("VOE", true)) return@mapNotNull null
    val language = when (button.attr("data-language-id")) { "1" -> "de"; "2" -> "en"; else -> return@mapNotNull null }
    val url = button.absUrl("data-play-url")
    val uri = runCatching { URI(url) }.getOrNull() ?: return@mapNotNull null
    if (uri.scheme != "https" || uri.host != URI(page.location()).host || uri.path != "/r" || uri.userInfo != null) return@mapNotNull null
    SerienstreamLink("VOE", language, url)
}.distinctBy { it.label }

/** Pure decoding of the host's public player configuration; no downloaded JavaScript is executed. */
internal fun decodeVoeConfig(page: Document): JSONObject {
    val raw = page.select("script[type=application/json]").firstNotNullOfOrNull { script ->
        runCatching { JSONArray(script.data()).optString(0).takeIf { it.isNotBlank() } }.getOrNull()
    } ?: error("VOE configuration unavailable")
    require(raw.length <= 1_000_000)
    var text = raw.map { c -> when(c) {
        in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 13) % 26).toChar()
        in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 13) % 26).toChar()
        else -> c
    } }.joinToString("")
    listOf("@$", "^^", "~@", "%?", "*~", "!!", "#&").forEach { text = text.replace(it, "") }
    val shifted = String(Base64.getDecoder().decode(text), Charsets.ISO_8859_1).map { (it.code - 3).toChar() }.joinToString("").reversed()
    return JSONObject(String(Base64.getDecoder().decode(shifted), Charsets.UTF_8))
}
