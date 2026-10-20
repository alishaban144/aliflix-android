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
                val previous = latest?.optJSONArray("results")
                val results = event.getJSONArray("results")
                require(results.length() <= 60) { "Research limit" }
                if (previous != null) {
                    require(results.length() >= previous.length()) { "Shrinking research response" }
                    for (i in 0 until previous.length()) require(
                        previous.getJSONObject(i).getInt("tmdbId") == results.getJSONObject(i).getInt("tmdbId")
                    ) { "Reordered research response" }
                }
                finished = !event.optBoolean("inProgress")
                latest = event
                event
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
