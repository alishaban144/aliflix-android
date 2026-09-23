package com.aliflix.app.data

import android.content.Context
import com.aliflix.app.model.Media
import org.json.JSONArray

/** Bounded, persistent title activity; opening a trailer card is not a watch. */
class ActivityShelves(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("activity-shelves", Context.MODE_PRIVATE)
    fun read(shelf: String): List<Media> = runCatching {
        val array = JSONArray(prefs.getString(shelf, "[]"))
        (0 until array.length()).map { Media.fromJson(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun record(shelf: String, media: Media) {
        val items = (listOf(media) + read(shelf).filterNot { it.key == media.key }).take(60)
        prefs.edit().putString(shelf, JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()).apply()
    }
    fun remove(shelf: String, media: Media) {
        val items = read(shelf).filterNot { it.key == media.key }
        prefs.edit().putString(shelf, JSONArray().apply { items.forEach { put(it.toJson()) } }.toString()).apply()
    }
    fun clear(shelf: String) { prefs.edit().remove(shelf).apply() }
}
