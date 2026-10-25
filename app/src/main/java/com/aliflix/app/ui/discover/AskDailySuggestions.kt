package com.aliflix.app.ui.discover

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.V3RecommendationResult
import org.json.JSONObject

/** A stable hourly window from the daily researched pool. Rotation is local:
 * it never spends another search/model call or relabels a cached query match. */
internal fun rotatingAskDailySuggestions(items:List<Media>,atMillis:Long,seed:Int,limit:Int=8):List<Media> {
    val pool=items.distinctBy{it.key}.sortedBy{it.key}.shuffled(kotlin.random.Random(seed xor (atMillis/86_400_000L).toInt()))
    if(pool.size<=limit)return pool
    val start=Math.floorMod((atMillis/3_600_000L)*limit,pool.size.toLong()).toInt()
    return List(limit){pool[(start+it)%pool.size]}
}

/** Cached catalogue identities remain browsing suggestions, never invented query matches. */
internal fun parseAskDailySuggestions(json:JSONObject,type:MediaType):List<Media> {
    val rows=(json.optJSONObject(type.routeName)?:json).optJSONArray("results")?:return emptyList()
    return (0 until rows.length()).mapNotNull { n->runCatching {
        val v=V3RecommendationResult.fromJson(rows.getJSONObject(n))
        require(v.tmdbId>0&&v.title.isNotBlank()&&MediaType.from(v.mediaType)==type)
        Media(v.tmdbId,type,v.title,posterPath=v.posterPath,backdropPath=v.backdropPath,
            year=v.releaseDate?.take(4).orEmpty(),rating=v.tmdbRating?:0.0,genres=v.genres)
    }.getOrNull() }.distinctBy{it.key}.take(32)
}

internal fun retainedAskDailySuggestions(type:MediaType,cached:()->String,bundled:()->String):List<Media> =
    runCatching{parseAskDailySuggestions(JSONObject(cached()),type)}.getOrDefault(emptyList()).ifEmpty {
        runCatching{parseAskDailySuggestions(JSONObject(bundled()),type)}.getOrDefault(emptyList())
    }

internal fun researchBackedAskDailySuggestions(json:JSONObject,type:MediaType):Boolean {
    val collection=json.optJSONObject(type.routeName)?:json
    if(collection.optString("source")!="research"||parseAskDailySuggestions(collection,type).isEmpty())return false
    val rows=collection.optJSONArray("results")?:return false
    return (0 until rows.length()).all{n->val row=rows.optJSONObject(n);row!=null&&(row.optJSONArray("retrievalSources")?.length()?:0)>0}
}
