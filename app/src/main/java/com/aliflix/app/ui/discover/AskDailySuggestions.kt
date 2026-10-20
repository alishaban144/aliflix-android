package com.aliflix.app.ui.discover

import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.recommendation.V3RecommendationResult
import org.json.JSONObject

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
