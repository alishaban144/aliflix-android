package com.aliflix.app.data

import android.content.Context
import androidx.core.content.edit
import com.aliflix.app.model.Media
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

class LibraryStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("aliflix_library", Context.MODE_PRIVATE)

    private val _myList = MutableStateFlow(read("my_list"))
    val myList: StateFlow<List<Media>> = _myList.asStateFlow()

    private val _recent = MutableStateFlow(read("recently_played"))
    val recent: StateFlow<List<Media>> = _recent.asStateFlow()

    private val _likes = MutableStateFlow(read("liked_titles"))
    val likes: StateFlow<List<Media>> = _likes.asStateFlow()

    fun toggleMyList(item: Media) {
        val current = _myList.value.toMutableList()
        val index = current.indexOfFirst { it.key == item.key }
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        _myList.value = current
        write("my_list", current)
    }

    fun isInMyList(item: Media): Boolean = _myList.value.any { it.key == item.key }

    fun toggleLike(item: Media) {
        val current = _likes.value.toMutableList()
        val index = current.indexOfFirst { it.key == item.key }
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        _likes.value = current
        write("liked_titles", current)
    }

    fun isLiked(item: Media): Boolean = _likes.value.any { it.key == item.key }

    fun markPlayed(item: Media) {
        val updated = _recent.value.filterNot { it.key == item.key }.toMutableList().apply {
            add(0, item)
        }.take(MAX_RECENT)
        _recent.value = updated
        write("recently_played", updated)
    }

    fun removeRecent(item: Media) {
        val updated = _recent.value.filterNot { it.key == item.key }
        _recent.value = updated
        write("recently_played", updated)
    }

    fun clearRecent() {
        _recent.value = emptyList()
        write("recently_played", emptyList())
    }

    fun refreshMetadata(item: Media) {
        val updatedList = _myList.value.map { saved ->
            if (saved.key == item.key) item else saved
        }
        if (updatedList != _myList.value) {
            _myList.value = updatedList
            write("my_list", updatedList)
        }

        val updatedRecent = _recent.value.map { played ->
            if (played.key == item.key) item else played
        }
        if (updatedRecent != _recent.value) {
            _recent.value = updatedRecent
            write("recently_played", updatedRecent)
        }

        val updatedLikes = _likes.value.map { liked ->
            if (liked.key == item.key) item else liked
        }
        if (updatedLikes != _likes.value) {
            _likes.value = updatedLikes
            write("liked_titles", updatedLikes)
        }
    }

    private fun read(key: String): List<Media> = runCatching {
        val array = JSONArray(preferences.getString(key, "[]"))
        (0 until array.length()).mapNotNull { index ->
            runCatching { Media.fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun write(key: String, items: List<Media>) {
        val array = JSONArray()
        items.forEach { array.put(it.toJson()) }
        preferences.edit {
            putString(key, array.toString())
        }
    }

    private companion object {
        const val MAX_RECENT = 30
    }
}
