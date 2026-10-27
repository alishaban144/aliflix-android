package com.aliflix.app.talk

import org.json.JSONArray
import org.json.JSONObject

/** A selected remake or series is an identity, not another title-name guess. */
internal fun talkCanonicalRecommendationArgs(args:JSONObject,question:String,cards:TalkCardContext):JSONObject {
    val result=JSONObject(args.toString()).apply{remove("canonicalAnchors")}
    val requested=args.optJSONArray("similarTo")
    val positive=question.replace(Regex("(?i)(?:do not|don't|not|avoid|exclude|without)\\s+[^,.!?;]*")," ")
    val names=if(requested!=null)(0 until minOf(3,requested.length())).map{requested.optString(it)} else
        if(Regex("(?i)\\b(?:similar|like|blend)\\b").containsMatchIn(positive))cards.references(positive).map{it.title+" ("+it.year+")"}else emptyList()
    if(names.isEmpty())return result
    val established=names.map { name ->
        val base=name.replace(Regex("\\s*\\((?:18|19|20)\\d{2}\\)$"),"")
        val attached=Regex(Regex.escape(base)+"\\s*\\(((?:18|19|20)\\d{2})\\)",RegexOption.IGNORE_CASE).find(question)?.groupValues?.get(1)?.toIntOrNull()
        cards.resolve(name,year=attached?:0).singleOrNull()
    }
    result.put("similarTo",JSONArray(names))
    if(established.all{it!=null})result.put("canonicalAnchors",JSONArray(established.filterNotNull().distinctBy{it.key}.map {
        JSONObject().put("tmdbId",it.id).put("mediaType",it.type.routeName).put("title",it.title)
    }))
    return result
}
