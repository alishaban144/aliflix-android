package com.aliflix.app.data.omdb

import org.json.JSONArray
import org.json.JSONObject

data class OmdbTitleMetadata(
    val found: Boolean,
    val imdbId: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val yearText: String? = null,
    val type: String? = null, // "movie", "series", "episode"
    val genres: List<String> = emptyList(),
    val plot: String? = null,
    val runtimeMinutes: Int? = null,
    val director: String? = null,
    val writers: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val contentRating: String? = null,
    val awards: String? = null,
    val imdbRating: Double? = null,
    val imdbVotes: Int? = null,
    val rottenTomatoesRating: Int? = null,
    val metascore: Int? = null,
    val totalSeasons: Int? = null,
    val source: String = "OMDB"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("found", found)
        imdbId?.let { put("imdbId", it) }
        title?.let { put("title", it) }
        year?.let { put("year", it) }
        yearText?.let { put("yearText", it) }
        type?.let { put("type", it) }
        put("genres", JSONArray(genres))
        plot?.let { put("plot", it) }
        runtimeMinutes?.let { put("runtimeMinutes", it) }
        director?.let { put("director", it) }
        put("writers", JSONArray(writers))
        put("actors", JSONArray(actors))
        put("languages", JSONArray(languages))
        put("countries", JSONArray(countries))
        contentRating?.let { put("contentRating", it) }
        awards?.let { put("awards", it) }
        imdbRating?.let { put("imdbRating", it) }
        imdbVotes?.let { put("imdbVotes", it) }
        rottenTomatoesRating?.let { put("rottenTomatoesRating", it) }
        metascore?.let { put("metascore", it) }
        totalSeasons?.let { put("totalSeasons", it) }
        put("source", source)
    }

    companion object {
        private fun optStringClean(json: JSONObject, key: String): String? =
            json.optString(key).takeIf { it.isNotBlank() && it != "null" }

        private fun optList(json: JSONObject, key: String): List<String> {
            val arr = json.optJSONArray(key) ?: return emptyList()
            return List(arr.length()) { arr.getString(it) }.filter { it.isNotBlank() && it != "null" }
        }

        fun fromJson(json: JSONObject): OmdbTitleMetadata {
            val found = json.optBoolean("found", false)
            if (!found) {
                return OmdbTitleMetadata(found = false)
            }
            return OmdbTitleMetadata(
                found = true,
                imdbId = optStringClean(json, "imdbId"),
                title = optStringClean(json, "title"),
                year = if (json.isNull("year")) null else json.optInt("year").takeIf { it > 0 },
                yearText = optStringClean(json, "yearText"),
                type = optStringClean(json, "type"),
                genres = optList(json, "genres"),
                plot = optStringClean(json, "plot"),
                runtimeMinutes = if (json.isNull("runtimeMinutes")) null else json.optInt("runtimeMinutes").takeIf { it > 0 },
                director = optStringClean(json, "director"),
                writers = optList(json, "writers"),
                actors = optList(json, "actors"),
                languages = optList(json, "languages"),
                countries = optList(json, "countries"),
                contentRating = optStringClean(json, "contentRating"),
                awards = optStringClean(json, "awards"),
                imdbRating = if (json.isNull("imdbRating")) null else json.optDouble("imdbRating").takeIf { !it.isNaN() && it > 0.0 },
                imdbVotes = if (json.isNull("imdbVotes")) null else json.optInt("imdbVotes").takeIf { it >= 0 },
                rottenTomatoesRating = if (json.isNull("rottenTomatoesRating")) null else json.optInt("rottenTomatoesRating").takeIf { it in 0..100 },
                metascore = if (json.isNull("metascore")) null else json.optInt("metascore").takeIf { it in 0..100 },
                totalSeasons = if (json.isNull("totalSeasons")) null else json.optInt("totalSeasons").takeIf { it > 0 }
            )
        }
    }
}

data class OmdbLookupRequest(
    val candidateId: String? = null,
    val imdbId: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val mediaType: String // "movie" | "series"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        candidateId?.let { put("candidateId", it) }
        imdbId?.let { put("imdbId", it) }
        title?.let { put("title", it) }
        year?.let { put("year", it) }
        put("mediaType", mediaType.lowercase())
    }
}

data class OmdbBatchResponseItem(
    val candidateId: String,
    val metadata: OmdbTitleMetadata?,
    val status: String // "VERIFIED" | "NOT_FOUND" | "UNAVAILABLE"
)

data class OmdbBatchResponse(
    val results: List<OmdbBatchResponseItem>
) {
    companion object {
        fun fromJson(json: JSONObject): OmdbBatchResponse {
            val arr = json.optJSONArray("results") ?: JSONArray()
            val list = mutableListOf<OmdbBatchResponseItem>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val cid = obj.getString("candidateId")
                val status = obj.optString("status", "UNAVAILABLE")
                val metaObj = obj.optJSONObject("metadata")
                val metadata = metaObj?.let { OmdbTitleMetadata.fromJson(it) }
                list.add(OmdbBatchResponseItem(cid, metadata, status))
            }
            return OmdbBatchResponse(list)
        }
    }
}
