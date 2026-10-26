package com.aliflix.app.recommendation

import org.json.JSONObject

/** Whole-list snapshots can be conflated safely. A stale or foreign event may
 * never replace the current query, and a lost connection retains real matches. */
internal class AskResearchStream(private val requestId: String) {
    private var revision = 0L
    var latest: JSONObject? = null
        private set
    @Volatile var finished = false
        private set

    fun accept(event: JSONObject): JSONObject? {
        require(event.optString("requestId") == requestId) { "Foreign research response" }
        val next = event.optLong("revision", 0)
        if (next <= revision || finished) return null
        revision = next
        return when (event.optString("type")) {
            "progress" -> null
            "results" -> {
                require(event.has("results")) { "Missing research results" }
                val results = event.getJSONArray("results")
                require(results.length() <= 60) { "Research limit" }
                val identities = HashSet<String>()
                for (i in 0 until results.length()) {
                    val result = results.getJSONObject(i)
                    val id = result.getInt("tmdbId")
                    val type = result.optString("mediaType", "movie")
                    require(id > 0 && type in setOf("movie", "tv")) { "Invalid research identity" }
                    require(identities.add("$type:$id")) { "Duplicate research identity" }
                }
                // Published cards own their identity and position until a new
                // user query. Conflation or an older server may reorder a frame;
                // retain the visible prefix and append unseen identities only.
                val stable=org.json.JSONArray()
                val keys=HashSet<String>()
                for(array in listOfNotNull(latest?.optJSONArray("results"),results))for(i in 0 until array.length()){
                    val row=array.getJSONObject(i);val key=row.optString("mediaType","movie")+":"+row.getInt("tmdbId")
                    if(keys.add(key))stable.put(row)
                }
                val accepted=JSONObject(event.toString()).put("results",stable)
                finished = !event.optBoolean("inProgress")
                latest = accepted
                accepted
            }
            "error" -> {
                finished = true
                val error = event.optJSONObject("error")
                val retained = retained()
                if (retained != null) retained else throw RecommendationAiClientException(
                    error?.optString("code") ?: "RESEARCH_UNAVAILABLE", true, "Research unavailable"
                )
            }
            else -> error("Invalid research event")
        }
    }

    fun retained(): JSONObject? = latest?.takeIf { (it.optJSONArray("results")?.length() ?: 0) > 0 }
        ?.let { JSONObject(it.toString()).put("inProgress", false).put("incomplete", true) }
}
