package com.aliflix.app.data

import android.content.Context
import androidx.core.content.edit
import com.aliflix.app.account.AccountMergePolicy
import com.aliflix.app.account.LibrarySnapshot
import com.aliflix.app.account.RecentMediaEntry
import com.aliflix.app.model.Media
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

sealed interface LibraryMutation {
    data class MyListChanged(val media: Media, val added: Boolean) : LibraryMutation
    data class FavoriteChanged(val media: Media, val added: Boolean) : LibraryMutation
    data class RecentPlayed(val entry: RecentMediaEntry) : LibraryMutation
    data class RecentRemoved(val mediaKey: String) : LibraryMutation
    data object RecentCleared : LibraryMutation
}

class LibraryStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("aliflix_library", Context.MODE_PRIVATE)

    private val _myList = MutableStateFlow(read("my_list"))
    val myList: StateFlow<List<Media>> = _myList.asStateFlow()

    private val initialRecentEntries = readRecent()
    private val _recent = MutableStateFlow(initialRecentEntries.map(RecentMediaEntry::media))
    val recent: StateFlow<List<Media>> = _recent.asStateFlow()
    private val _recentEntries = MutableStateFlow(initialRecentEntries)
    val recentEntries: StateFlow<List<RecentMediaEntry>> = _recentEntries.asStateFlow()

    private val _likes = MutableStateFlow(read("liked_titles"))
    val likes: StateFlow<List<Media>> = _likes.asStateFlow()

    private val _mutations = MutableSharedFlow<LibraryMutation>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val mutations: SharedFlow<LibraryMutation> = _mutations

    fun toggleMyList(item: Media) {
        val current = _myList.value.toMutableList()
        val index = current.indexOfFirst { it.key == item.key }
        val added = index < 0
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        _myList.value = current
        write("my_list", current)
        _mutations.tryEmit(LibraryMutation.MyListChanged(item, added))
    }

    fun isInMyList(item: Media): Boolean = _myList.value.any { it.key == item.key }

    fun toggleLike(item: Media) {
        val current = _likes.value.toMutableList()
        val index = current.indexOfFirst { it.key == item.key }
        val added = index < 0
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        _likes.value = current
        write("liked_titles", current)
        _mutations.tryEmit(LibraryMutation.FavoriteChanged(item, added))
    }

    fun isLiked(item: Media): Boolean = _likes.value.any { it.key == item.key }

    fun markPlayed(item: Media) {
        val entry = RecentMediaEntry(item, System.currentTimeMillis())
        val updated = listOf(entry) + _recentEntries.value.filterNot { it.media.key == item.key }
        applyRecent(updated.take(AccountMergePolicy.MAX_RECENT))
        _mutations.tryEmit(LibraryMutation.RecentPlayed(entry))
    }

    fun removeRecent(item: Media) {
        applyRecent(_recentEntries.value.filterNot { it.media.key == item.key })
        _mutations.tryEmit(LibraryMutation.RecentRemoved(item.key))
    }

    fun clearRecent() {
        applyRecent(emptyList())
        _mutations.tryEmit(LibraryMutation.RecentCleared)
    }

    fun refreshMetadata(item: Media) {
        val updatedList = _myList.value.map { saved ->
            if (saved.key == item.key) item else saved
        }
        if (updatedList != _myList.value) {
            _myList.value = updatedList
            write("my_list", updatedList)
            _mutations.tryEmit(LibraryMutation.MyListChanged(item, added = true))
        }

        val updatedRecent = _recentEntries.value.map { played ->
            if (played.media.key == item.key) played.copy(media = item) else played
        }
        if (updatedRecent != _recentEntries.value) {
            applyRecent(updatedRecent)
            updatedRecent.firstOrNull { played -> played.media.key == item.key }
                ?.let { played -> _mutations.tryEmit(LibraryMutation.RecentPlayed(played)) }
        }

        val updatedLikes = _likes.value.map { liked ->
            if (liked.key == item.key) item else liked
        }
        if (updatedLikes != _likes.value) {
            _likes.value = updatedLikes
            write("liked_titles", updatedLikes)
            _mutations.tryEmit(LibraryMutation.FavoriteChanged(item, added = true))
        }
    }

    fun snapshot(): LibrarySnapshot = LibrarySnapshot(
        myList = _myList.value,
        favorites = _likes.value,
        recent = _recentEntries.value,
    )

    /** Applies a cloud/account-scope snapshot without emitting local sync mutations. */
    fun applySyncedSnapshot(snapshot: LibrarySnapshot) {
        val myList = snapshot.myList.distinctBy(Media::key)
        val favorites = snapshot.favorites.distinctBy(Media::key)
        _myList.value = myList
        _likes.value = favorites
        write("my_list", myList)
        write("liked_titles", favorites)
        applyRecent(
            snapshot.recent
                .distinctBy { it.media.key }
                .sortedByDescending(RecentMediaEntry::lastPlayedAtMillis)
                .take(AccountMergePolicy.MAX_RECENT),
        )
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

    private fun readRecent(): List<RecentMediaEntry> = runCatching {
        val array = JSONArray(preferences.getString("recently_played", "[]"))
        val migrationBase = preferences.getLong(
            "recent_timestamp_migration_base",
            System.currentTimeMillis(),
        ).also { base ->
            if (!preferences.contains("recent_timestamp_migration_base")) {
                preferences.edit { putLong("recent_timestamp_migration_base", base) }
            }
        }
        (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                RecentMediaEntry(
                    media = Media.fromJson(json),
                    lastPlayedAtMillis = json.optLong(RECENT_TIMESTAMP_KEY)
                        .takeIf { it > 0L }
                        ?: (migrationBase - index),
                )
            }.getOrNull()
        }.take(AccountMergePolicy.MAX_RECENT)
    }.getOrDefault(emptyList())

    private fun applyRecent(entries: List<RecentMediaEntry>) {
        _recentEntries.value = entries
        _recent.value = entries.map(RecentMediaEntry::media)
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject(entry.media.toJson().toString())
                    .put(RECENT_TIMESTAMP_KEY, entry.lastPlayedAtMillis),
            )
        }
        preferences.edit { putString("recently_played", array.toString()) }
    }

    private companion object {
        const val RECENT_TIMESTAMP_KEY = "_lastPlayedAtMillis"
    }
}
