package com.aliflix.app.talk

import com.aliflix.app.BuildConfig
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationAiClient
import com.aliflix.app.recommendation.V3CatalogMedia
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Uses the same catalogue search as the app, including original titles and
 * alternate editions. A mistaken year must not make a real title disappear. */
internal fun talkCatalogueMatches(rows:List<V3CatalogMedia>,title:String,kind:String?,year:Int):List<TalkTitleCard> {
    fun key(value:String)=value.lowercase(java.util.Locale.ROOT).replace(Regex("^(the|a|an)\\s+"),"").filter{it.isLetterOrDigit()}
    val name=title.replace(Regex("\\s*\\(?(?:18|19|20)\\d{2}\\)?$"),"").trim()
    val exact=rows.filter{(kind==null||it.mediaType==kind)&&listOfNotNull(it.title,it.originalTitle).any{v->key(v)==key(name)}}
    val matches=exact.ifEmpty{rows.filter{(kind==null||it.mediaType==kind)&&key(name).length>=3&&listOfNotNull(it.title,it.originalTitle).any{v->key(v).startsWith(key(name))}}}
    val selected=matches.filter{year>0&&it.releaseDate?.take(4)?.toIntOrNull()==year}.ifEmpty{matches}
    return selected.distinctBy{it.mediaType+it.tmdbId}.take(3).map{TalkTitleCard(it.tmdbId,MediaType.from(it.mediaType),it.title,it.releaseDate?.take(4).orEmpty(),it.posterPath)}
}
internal class TalkCatalogue(private val known:()->TalkCardContext) {
    private val client=RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL)
    suspend fun find(title:String,kind:String?=null,year:Int=0):List<TalkTitleCard> {
        if(title.lowercase(java.util.Locale.ROOT).trim().trim('.','?','!') in setOf("","it","that","this","the movie","the film","the series","that title","this title","mentioned title","the mentioned title","the mentioned movie","that movie","that film","that series"))
            return known().visible.ifEmpty{known().known.take(1)}.filter{kind==null||it.type.routeName==kind}
        val cached=known().select(listOf(title)).filter{kind==null||it.type.routeName==kind}
        if(cached.isNotEmpty())return cached
        val query=title.replace(Regex("\\s*\\(?(?:18|19|20)\\d{2}\\)?$"),"").trim().take(160)
        if(query.isBlank())return known().visible.ifEmpty{known().group}
        return withTimeout(7000){talkCatalogueMatches(client.searchTitles(query),query,kind,year)}
    }
    suspend fun cards(args:JSONObject):List<TalkTitleCard> {
        val names=args.optJSONArray("titles")
        val requested=(0 until minOf(names?.length()?:0,3)).map{names!!.optString(it)}
        if(requested.isEmpty())return known().visible.ifEmpty{known().group}.ifEmpty{known().known.take(3)}
        return requested.flatMap{find(it,args.optString("kind").takeIf{v->v in setOf("movie","tv")},args.optInt("year"))}.distinctBy{it.key}.take(3)
    }
}
