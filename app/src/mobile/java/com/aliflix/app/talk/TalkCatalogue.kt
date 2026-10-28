package com.aliflix.app.talk

import com.aliflix.app.BuildConfig
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.RecommendationAiClient
import com.aliflix.app.recommendation.V3CatalogMedia
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject

/** Uses the same catalogue search as the app, including original titles and
 * alternate editions. A mistaken year must not make a real title disappear. */
internal fun talkCatalogueMatches(rows:List<V3CatalogMedia>,title:String,kind:String?,year:Int):List<TalkTitleCard> {
    fun key(value:String)=java.text.Normalizer.normalize(value,java.text.Normalizer.Form.NFKD).lowercase(java.util.Locale.ROOT).replace(Regex("^(the|a|an)\\s+"),"").filter{it.isLetterOrDigit()}
    val exactFull=rows.any{listOfNotNull(it.title,it.originalTitle).any{v->key(v)==key(title)}}
    val name=if(exactFull)title else title.replace(Regex("\\s*\\(?(?:18|19|20)\\d{2}\\)?$"),"").trim()
    val exact=rows.filter{(kind==null||it.mediaType==kind)&&listOfNotNull(it.title,it.originalTitle).any{v->key(v)==key(name)}}
    val matches=exact.ifEmpty{rows.filter{(kind==null||it.mediaType==kind)&&key(name).length>=3&&listOfNotNull(it.title,it.originalTitle).any{v->key(v).startsWith(key(name))}}}
    val selected=matches.filter{year>0&&it.releaseDate?.take(4)?.toIntOrNull()==year}.ifEmpty{matches}
    val ranked=selected.sortedByDescending{it.tmdbVoteCount?:0}
    val dominant=ranked.firstOrNull()?.takeIf{(it.tmdbVoteCount?:0)>=50&&(it.tmdbVoteCount?:0)>=10*(ranked.getOrNull(1)?.tmdbVoteCount?:0)}
    val resolved=if(dominant!=null&&(year<=0||ranked.all{it.releaseDate?.take(4)==dominant.releaseDate?.take(4)}))listOf(dominant)else selected
    return resolved.distinctBy{it.mediaType+it.tmdbId}.take(3).map{TalkTitleCard(it.tmdbId,MediaType.from(it.mediaType),it.title,it.releaseDate?.take(4).orEmpty(),talkPosterPath(it.posterPath),it.originalTitle)}
}
internal class TalkCatalogue(private val known:()->TalkCardContext) {
    private val client=RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL)
    suspend fun find(title:String,kind:String?=null,year:Int=0,id:Int=0):List<TalkTitleCard> {
        val established=known().resolve(title,kind,year,id)
        if(established.isNotEmpty())return established
        if(id>0&&kind!=null)return withTimeout(7000){val m=client.getTitleDetails(kind,id).media;listOf(TalkTitleCard(m.tmdbId,MediaType.from(m.mediaType),m.title,m.releaseDate?.take(4).orEmpty(),talkPosterPath(m.posterPath),m.originalTitle))}
        if(title.lowercase(java.util.Locale.ROOT).trim().trim('.','?','!') in setOf("","it","that","this","the movie","the film","the series","that title","this title","mentioned title","the mentioned title","the mentioned movie","that movie","that film","that series"))
            return known().visible.ifEmpty{known().known.take(1)}.filter{kind==null||it.type.routeName==kind}
        val explicitYear=year.takeIf{it>0}?:Regex("\\(((?:18|19|20)\\d{2})\\)$").find(title)?.groupValues?.get(1)?.toIntOrNull()?:0
        val query=title.replace(Regex("\\s*\\((?:18|19|20)\\d{2}\\)$"),"").trim().take(160)
        if(query.isBlank())return known().visible.ifEmpty{known().group}
        return withTimeout(7000){
            val rows=client.searchTitles(query)
            val matched=talkCatalogueMatches(rows,query,kind,explicitYear)
            if(matched.isNotEmpty())matched else {
                val suffix=Regex("\\s+((?:18|19|20)\\d{2})$").find(query)
                if(suffix==null)emptyList()else {
                    val stripped=query.substring(0,suffix.range.first).trim()
                    talkCatalogueMatches(client.searchTitles(stripped),stripped,kind,explicitYear.takeIf{it>0}?:suffix.groupValues[1].toInt())
                }
            }
        }
    }
    suspend fun cards(args:JSONObject):List<TalkTitleCard> {
        if(args.optBoolean("restore"))return known().group
        val names=args.optJSONArray("titles")
        val requested=(0 until minOf(names?.length()?:0,8)).map{names!!.optString(it)}
        if(requested.isEmpty())return known().visible.ifEmpty{known().group}.ifEmpty{known().known.take(8)}
        return requested.flatMap{find(it,args.optString("kind").takeIf{v->v in setOf("movie","tv")},args.optInt("year"))}.distinctBy{it.key}.take(8)
    }
    suspend fun hydrate(card:TalkTitleCard):TalkTitleCard = withTimeout(7000) {
        val m=client.getTitleDetails(card.type.routeName,card.id).media
        card.copy(posterPath=talkPosterPath(m.posterPath)?:card.posterPath,originalTitle=card.originalTitle?:m.originalTitle)
    }
    suspend fun facts(args:JSONObject):JSONObject = coroutineScope {
        val cards=cards(args)
        val facts=cards.map { card->async {
            val detail=withTimeout(7000){client.getTitleDetails(card.type.routeName,card.id)}
            val m=detail.media
            card.json().put("runtimeMinutes",m.runtimeMinutes?:JSONObject.NULL)
                .put("releaseDate",m.releaseDate?:JSONObject.NULL).put("genres",JSONArray(m.genres))
                .put("originalLanguage",m.originalLanguage?:JSONObject.NULL)
                .put("creators",JSONArray(detail.creators.map{JSONObject().put("id",it.tmdbId).put("name",it.name).put("role",it.role)}))
                .put("source","https://www.themoviedb.org/${card.type.routeName}/${card.id}")
        } }.map{it.await()}
        JSONObject().put("facts",JSONArray(facts)).put("titles",JSONArray(cards.map{it.json()}))
            .put("instruction","Use exact catalogue facts for the requested decision or mission planning; creator IDs can seed a verified filmography. Null means unknown. For a closed comparison use only the supplied choices and choose when asked. If none satisfies a hard constraint, say so. No unrelated recommendation search or generic suggestion bridge. These facts authorize no app mutation.")
    }
}
