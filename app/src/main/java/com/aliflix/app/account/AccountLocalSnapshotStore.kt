package com.aliflix.app.account

import android.content.Context
import androidx.core.content.edit
import com.aliflix.app.model.Media
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.data.playbackProgressFromJson
import com.aliflix.app.data.toJson
import org.json.JSONArray
import org.json.JSONObject

class AccountLocalSnapshotStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    val activeScope: String
        get() = preferences.getString(KEY_ACTIVE_SCOPE, AccountMergePolicy.GUEST_SCOPE)
            ?: AccountMergePolicy.GUEST_SCOPE

    fun setActiveScope(scope: String) {
        preferences.edit { putString(KEY_ACTIVE_SCOPE, scope) }
    }

    fun existingUserScopes(): Set<String> =
        preferences.getStringSet(KEY_USER_SCOPES, emptySet()).orEmpty().toSet()

    fun save(scope: String, snapshot: AccountLocalSnapshot) {
        val json = JSONObject()
            .put("library", snapshot.library.toJson())
            .put("playbackProgress", JSONArray().apply {
                snapshot.playbackProgress.forEach { put(it.toJson()) }
            })
            .put("settings", snapshot.settings.toJson())
        preferences.edit {
            putString(snapshotKey(scope), json.toString())
            if (scope != AccountMergePolicy.GUEST_SCOPE) {
                putStringSet(KEY_USER_SCOPES, existingUserScopes() + scope)
            }
        }
    }

    fun load(scope: String): AccountLocalSnapshot? = runCatching {
        val raw = preferences.getString(snapshotKey(scope), null) ?: return null
        val json = JSONObject(raw)
        AccountLocalSnapshot(
            library = json.getJSONObject("library").toLibrarySnapshot(),
            playbackProgress = json.optJSONArray("playbackProgress")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(::playbackProgressFromJson)
                }
            }.orEmpty(),
            settings = json.getJSONObject("settings").toSettingsSnapshot(),
        )
    }.getOrNull()

    fun remove(scope: String) {
        preferences.edit {
            remove(snapshotKey(scope))
            if (scope != AccountMergePolicy.GUEST_SCOPE) {
                putStringSet(KEY_USER_SCOPES, existingUserScopes() - scope)
            }
        }
    }

    private fun snapshotKey(scope: String) = "snapshot:$scope"

    private companion object {
        const val PREFERENCES_NAME = "aliflix_account_local_snapshots"
        const val KEY_ACTIVE_SCOPE = "active_scope"
        const val KEY_USER_SCOPES = "user_scopes"
    }
}

private fun LibrarySnapshot.toJson() = JSONObject()
    .put("myList", mediaArray(myList))
    .put("favorites", mediaArray(favorites))
    .put("recent", JSONArray().apply {
        recent.forEach { entry ->
            put(
                JSONObject()
                    .put("media", entry.media.toJson())
                    .put("lastPlayedAtMillis", entry.lastPlayedAtMillis),
            )
        }
    })

private fun AccountSettingsSnapshot.toJson() = JSONObject()
    .put("generalProvider", generalProvider)
    .put("ramoflixUrl", ramoflixUrl)
    .put("moviepireUrl", moviepireUrl)
    .put("dorabyUrl", dorabyUrl)
    .put("askAliflixEnabled", askAliflixEnabled)
    .put("recommendationAiModel", recommendationAiModel)
    .put("updatedAtMillis", updatedAtMillis)
    .put("hasExplicitLocalValues", hasExplicitLocalValues)

private fun JSONObject.toLibrarySnapshot() = LibrarySnapshot(
    myList = optJSONArray("myList").mediaList(),
    favorites = optJSONArray("favorites").mediaList(),
    recent = optJSONArray("recent")?.let { array ->
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val media = entry.optJSONObject("media")?.let { json ->
                runCatching { Media.fromJson(json) }.getOrNull()
            } ?: return@mapNotNull null
            RecentMediaEntry(media, entry.optLong("lastPlayedAtMillis").coerceAtLeast(0L))
        }
    }.orEmpty(),
)

private fun JSONObject.toSettingsSnapshot() = AccountSettingsSnapshot(
    generalProvider = optString("generalProvider"),
    ramoflixUrl = optString("ramoflixUrl"),
    moviepireUrl = optString("moviepireUrl"),
    dorabyUrl = optString("dorabyUrl"),
    askAliflixEnabled = optBoolean("askAliflixEnabled", true),
    recommendationAiModel = optString("recommendationAiModel"),
    updatedAtMillis = optLong("updatedAtMillis").coerceAtLeast(0L),
    hasExplicitLocalValues = optBoolean("hasExplicitLocalValues"),
)

private fun mediaArray(items: List<Media>) = JSONArray().apply {
    items.forEach { put(it.toJson()) }
}

private fun JSONArray?.mediaList(): List<Media> = if (this == null) {
    emptyList()
} else {
    (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.let { json -> runCatching { Media.fromJson(json) }.getOrNull() }
    }
}
