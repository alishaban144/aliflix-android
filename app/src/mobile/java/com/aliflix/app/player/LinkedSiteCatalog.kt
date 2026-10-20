package com.aliflix.app.player

import androidx.activity.ComponentActivity
import com.aliflix.app.model.*
import com.aliflix.app.data.MobileEpisodeRepository
import com.aliflix.app.recommendation.RecommendationAiClient
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

internal data class SitePlaybackLink(val server: String, val language: String, val url: String, val token: String = "") {
    val audioLabel: String get() = when(language) {
        "ja-de" -> "Japanese · German subtitles"
        "ja-en" -> "Japanese · English subtitles"
        else -> formatAudioTrackLabel(language, null)
    }
    val label get() = "$audioLabel · $server"
}

private fun siteTitle(value: String) = java.text.Normalizer.normalize(value.lowercase(), java.text.Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}"), "").replace(Regex("[^\\p{L}\\p{N}]"), "")
private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
private fun ownedSiteUrl(raw: String, base: String, path: Regex): String? = runCatching {
    URI(base).resolve(raw).takeIf { it.scheme == "https" && it.host == URI(base).host && it.userInfo == null &&
        it.port in setOf(-1,443) && path.matches(it.path) }?.toString()
}.getOrNull()

/** Public site actions only: opaque provider tokens are submitted unchanged to
 * their own origin. Audio editions, hardsub variants and TV episode numbering
 * stay explicit; no guessed video URL or title-only substring match is used. */
internal class LinkedSiteCatalog(private val activity: ComponentActivity) {
    private suspend fun page(url: String): Document {
        val response=SerienstreamHttp.get(url,url)
        return Jsoup.parse(String(response.bytes,Charsets.UTF_8),response.finalUrl.ifBlank { url })
    }
    private suspend fun moviePage(url:String):Document {
        val first=page(url)
        if(first.selectFirst("html")?.attr("lang")?.substringBefore('-')=="en")return first
        // Locale cookies can expire or be cleared while the process survives.
        // Inspect the actual document rather than caching a permanent Boolean.
        localeLock.withLock { page("https://filmo.to/lang/en?redirect=%2F") }
        return page(url)
    }
    suspend fun links(selection: PlaybackSelection): Pair<Document,List<SitePlaybackLink>> = withTimeout(30_000) {
        if (selection.source.identity == MobilePlaybackProvider.FILMO) {
            val base=selection.source.baseUrl.trimEnd('/')
            val cached=titles["filmo:${selection.media.key}"]
            val direct=cached ?: "$base/movies/${selection.media.title.lowercase().replace(Regex("[^a-z0-9]+"),"-").trim('-')}"
            val first=(try { moviePage(direct) } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { currentCoroutineContext().ensureActive();null })?.takeIf { filmoTitleMatches(it,selection.media) }
            val document=first ?: run {
                val response=SerienstreamHttp.get("$base/search/suggest?q=${encoded(selection.media.title)}",base)
                val results=filmoSearchMovies(String(response.bytes,Charsets.UTF_8))
                if(results.length()==0) throw ProviderItemUnavailable(selection.source.identity)
                val candidates=(0 until results.length()).mapNotNull { index ->
                    ownedSiteUrl(results.getJSONObject(index).optString("url"),base,Regex("/movies/[^/]+"))
                }.distinct().take(12)
                firstSuccessful(candidates.map { url -> suspend {
                    moviePage(url).also { require(filmoTitleMatches(it,selection.media)) { "Filmo title/year mismatch" } }
                } },parallelism=2)
            }
            titles["filmo:${selection.media.key}"]=document.location()
            document to filmoPlaybackLinks(document)
        } else {
            require(selection.source.identity == MobilePlaybackProvider.ANIWORLD && selection.media.isJapaneseAnime)
            val base=selection.source.baseUrl.trimEnd('/')
            val titleKey="aniworld:${selection.media.key}"
            val titleUrl=titles[titleKey] ?: run {
                val raw=SerienstreamHttp.post("$base/ajax/search",base,"keyword=${encoded(selection.media.title)}")
                val results=JSONArray(raw)
                if(results.length()==0) throw ProviderItemUnavailable(selection.source.identity)
                val candidates=(0 until results.length()).mapNotNull { index -> ownedSiteUrl(
                    results.getJSONObject(index).optString("link"),base,Regex("/anime/stream/[^/]+")) }.distinct().take(10)
                firstSuccessful(candidates.map { url -> suspend {
                    require(aniworldTitleMatches(page(url),selection.media)) { "AniWorld title identity mismatch" };url
                } },parallelism=2).also { titles[titleKey]=it }
            }
            val index=try { page(titleUrl).also { require(aniworldTitleMatches(it,selection.media)) { "AniWorld title changed" } } }
                catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { titles.remove(titleKey,titleUrl);throw error }
            val repository=MobileEpisodeRepository(activity,RecommendationAiClient(com.aliflix.app.BuildConfig.RECOMMENDATION_AI_BASE_URL))
            val seasons=repository.seasons(selection.media.id).filter { it.number>0 }
            val requestedSeason=selection.seasonNumber ?: 1
            require(seasons.any { it.number==requestedSeason && (selection.episodeNumber ?: 1) in 1..it.episodeCount }) { "Unknown catalogue episode" }
            var remaining=seasons.filter { it.number<requestedSeason }.sumOf { it.episodeCount }+(selection.episodeNumber ?: 1)
            require(remaining>0)
            val siteSeasons=index.select("a[href]").mapNotNull { ownedSiteUrl(it.absUrl("href"),base,
                Regex("/anime/stream/[^/]+/staffel-[1-9][0-9]*")) }.distinct().sortedBy { it.substringAfterLast("staffel-").toInt() }
            var episodePage:Document?=null
            for (seasonUrl in siteSeasons.take(80)) {
                currentCoroutineContext().ensureActive()
                val season=page(seasonUrl)
                val episodes=season.select("a[href]").mapNotNull { ownedSiteUrl(it.absUrl("href"),base,
                    Regex(Regex.escape(URI(seasonUrl).path)+"/episode-[1-9][0-9]*")) }.distinct()
                    .sortedBy { it.substringAfterLast("episode-").toInt() }
                if (remaining<=episodes.size) { episodePage=page(episodes[remaining-1]);break }
                remaining-=episodes.size
            }
            val document=episodePage ?: error("AniWorld episode mapping could not be verified")
            document to aniworldPlaybackLinks(document)
        }
    }

    suspend fun resolve(selection:PlaybackSelection,position:Long,excluded:Set<String>,preferred:String?,strict:Boolean,
        audio:String?,onServers:(List<String>)->Unit,onServer:(String)->Unit,
        embedded:suspend (String,String)->NativePlaybackRequest):NativePlaybackRequest = withTimeout(if(selection.source.identity==MobilePlaybackProvider.FILMO)18_000 else 30_000) {
        val (document,links)=links(selection)
        android.util.Log.i("AliflixSite","${selection.source.identity.name}: catalogue links=${links.size}")
        require(links.isNotEmpty()) { "No playable site links" }
        onServers(links.map { it.label })
        val desired=audio ?: links.firstOrNull { it.label==preferred }?.language ?: selection.media.originalLanguage
        val eligible=links.filter { it.label !in excluded && (!strict || it.label==preferred) &&
            (audio==null || it.language==audio) }
        val ordered=eligible.sortedWith(compareBy<SitePlaybackLink> { if(it.label==preferred) 0 else 1 }
            .thenBy { if(it.language==desired || it.language.substringBefore('-')==desired) 0 else 1 })
        require(ordered.isNotEmpty()) { "Selected site audio/host unavailable" }
        // Race host mirrors within a bounded budget; an unresponsive first host cannot hide a working second.
        val winner=firstSuccessful(ordered.mapIndexed { index,link -> suspend {
            if(index>0)delay(650)
            try {
                // Host discovery and actual HLS/audio validation have separate
                // deadlines. A valid URL found near the discovery deadline must
                // still get Media3's normal buffer validation, within the total
                // provider budget; cold CDN startup is not a broken mirror.
                val request=withTimeout(8_000) {
                val action=if(link.token.isNotBlank()) {
                    val csrf=document.selectFirst("meta[name=csrf-token]")?.attr("content") ?: error("Missing site session")
                    val result=JSONObject(SerienstreamHttp.post("https://filmo.to/n",document.location(),
                        JSONObject().put("p",link.token).toString(),json=true,csrf=csrf))
                    "https://filmo.to/n/${encoded(result.getString("x"))}"
                } else link.url
                val target=action
                val request=if(link.server.equals("VOE",true)) try { publicVoeRequest(selection,target,position) }
                    catch(cancelled:CancellationException) { throw cancelled }
                    catch(error:Exception) {
                        currentCoroutineContext().ensureActive()
                        android.util.Log.w("AliflixSite","${selection.source.identity.name}: public VOE ${error.javaClass.simpleName}: ${error.message.orEmpty().replace(Regex("https?://\\S+"),"[url]").take(160)}")
                        embedded(link.label,target)
                    }
                    else embedded(link.label,target)
                request
                }
                if(com.aliflix.app.BuildConfig.DEBUG)android.util.Log.d("AliflixSite","${selection.source.identity.name}: host resolved; validating native media")
                StartupStreamCache.awaitPlayable(activity,request.copy(sourceAudioLanguage=link.language.substringBefore('-')))
                val resolved=request.copy(sourceAudioLanguage=link.language,sourceAudioOptions=JSONArray().apply {
                    links.distinctBy { it.language }.forEach { put(JSONObject().put("language",it.language).put("label",it.audioLabel)) }
                }.toString())
                link.label to resolved
            } catch(timeout:TimeoutCancellationException) {
                currentCoroutineContext().ensureActive();throw timeout
            } catch(cancelled:CancellationException) { throw cancelled }
            catch(error:Exception) {
                currentCoroutineContext().ensureActive()
                android.util.Log.w("AliflixSite","${selection.source.identity.name}:${link.label}:${error.javaClass.simpleName}: ${error.message.orEmpty().replace(Regex("https?://\\S+"),"[url]").take(160)}")
                throw error
            }
        } },parallelism=2)
        onServer(winner.first)
        winner.second
    }
    private suspend fun publicVoeRequest(selection:PlaybackSelection,url:String,position:Long):NativePlaybackRequest {
        val document=followPublicVoeRelay(page(url),url)
        val config=decodeVoeConfig(document)
        val source=config.optString("source").takeIf(::isNativeStreamUrl) ?: error("Video host has no stream")
        return NativePlaybackRequest(source,if(source.substringBefore('?').endsWith(".m3u8")) "application/x-mpegURL" else "video/mp4",
            document.location(),SerienstreamNativeCatalog.USER_AGENT,"",selection.media.title,position,false,
            selectionJson=selection.nativeJson(),sourceSubtitleOptions=config.optJSONArray("captions")?.toString().orEmpty())
    }
    companion object {
        private val titles=ConcurrentHashMap<String,String>()
        private val localeLock=Mutex()
    }
}

internal fun filmoTitleMatches(page:Document,media:Media):Boolean = siteTitle(page.selectFirst("h1")?.text().orEmpty())==siteTitle(media.title) &&
    media.year.take(4).matches(Regex("[0-9]{4}")) && page.select(".ft-meta-label").any { it.text().trim()==media.year.take(4) }
internal fun filmoSearchMovies(raw:String):JSONArray = requireNotNull(JSONObject(raw).optJSONArray("movies")) { "Filmo search response changed" }
internal fun filmoPlaybackLinks(page:Document):List<SitePlaybackLink> = page.select(".provider-row").flatMap { row ->
    val language=when(row.selectFirst(".provider-row__lang")?.text()?.lowercase()) { "english"->"en";"deutsch","german"->"de";"español","spanish"->"es";else->return@flatMap emptyList() }
    row.select("[data-provider-chip][data-p]").mapNotNull { chip ->
        val name=chip.selectFirst(".provider-chip__name")?.text().orEmpty()
        // Byse currently rejects third-party embedding for the inspected files.
        // Do not advertise a mirror that requires publisher authorization.
        if(name!="VOE") null else SitePlaybackLink(name,language,page.location(),chip.attr("data-p"))
    }
}.distinctBy { it.label }
internal fun aniworldTitleMatches(page:Document,media:Media):Boolean {
    val imdb=page.select("a[data-imdb]").map { it.attr("data-imdb") }.filter { it.matches(Regex("tt[0-9]+")) }
    if(!media.imdbId.isNullOrBlank() && imdb.isNotEmpty()) return media.imdbId in imdb
    val title=page.selectFirst("h1") ?: return false
    return (listOf(title.text())+title.attr("data-alternativeTitles").split(',')).any { siteTitle(it)==siteTitle(media.title) } &&
        page.select("a[href*=/jahr/]").any { it.text()==media.year.take(4) }
}
internal fun aniworldPlaybackLinks(page:Document):List<SitePlaybackLink> = page.select(".hosterSiteVideo li[data-link-target]").mapNotNull { link ->
    val name=link.selectFirst("h4")?.text().orEmpty()
    val audio=when(link.attr("data-lang-key")) { "1"->"de";"2"->"ja-en";"3"->"ja-de";else->return@mapNotNull null }
    val url=ownedSiteUrl(link.attr("data-link-target"),page.location(),Regex("/redirect/[0-9]+")) ?: return@mapNotNull null
    if(name !in setOf("VOE","Doodstream","Filemoon","Vidmoly")) null else SitePlaybackLink(name,audio,url)
}.distinctBy { it.label }
