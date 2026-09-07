package com.aliflix.app.data

import android.content.Context
import androidx.core.content.edit
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class PlaybackProgress(
    val key: String,
    val media: Media,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeTitle: String? = null,
    val positionSeconds: Double,
    val durationSeconds: Double,
    val updatedAtMillis: Long,
    val completed: Boolean,
    val explicitlyRestarted: Boolean = false,
) {
    val progressFraction: Double
        get() = if (durationSeconds > 0.0) {
            (positionSeconds / durationSeconds).coerceIn(0.0, 1.0)
        } else {
            0.0
        }

    val resumeEligible: Boolean
        get() = !completed && positionSeconds.isFinite() && positionSeconds > 0.0

}

sealed interface PlaybackProgressMutation {
    data class Changed(
        val progress: PlaybackProgress,
        val urgentCloudSync: Boolean,
    ) : PlaybackProgressMutation
}

class PlaybackProgressStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<Map<String, PlaybackProgress>> = _entries.asStateFlow()
    private val _mutations = MutableSharedFlow<PlaybackProgressMutation>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val mutations: SharedFlow<PlaybackProgressMutation> = _mutations

    fun progressFor(selection: PlaybackSelection): PlaybackProgress? =
        _entries.value[playbackProgressKey(selection)]

    fun resumeSelection(selection: PlaybackSelection): PlaybackSelection {
        if (selection.media.type != MediaType.TV || selection.seasonNumber != null || selection.episodeNumber != null) return selection
        val latest = _entries.value.values.filter { it.media.key == selection.media.key && it.resumeEligible }
            .maxByOrNull { it.updatedAtMillis } ?: return selection
        return selection.copy(seasonNumber = latest.seasonNumber, episodeNumber = latest.episodeNumber,
            episodeTitle = latest.episodeTitle)
    }

    @Synchronized
    fun savePlayerProgress(
        selection: PlaybackSelection,
        positionSeconds: Double,
        durationSeconds: Double,
        urgentCloudSync: Boolean,
        nowMillis: Long = System.currentTimeMillis(),
        ended: Boolean = false,
    ): PlaybackProgress? {
        if (!positionSeconds.isFinite() || positionSeconds <= 0.0) return null
        if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return null
        if (durationSeconds > MAX_REASONABLE_DURATION_SECONDS) return null
        if (positionSeconds > durationSeconds + 1.0) return null
        val previous = progressFor(selection)
        if (previous != null && nowMillis < previous.updatedAtMillis) return null
        val safePosition = positionSeconds.coerceAtMost(durationSeconds)
        if (previous != null && previous.positionSeconds == safePosition &&
            previous.durationSeconds == durationSeconds && previous.completed == ended) return previous
        val progress = PlaybackProgress(
            key = playbackProgressKey(selection),
            media = selection.media,
            seasonNumber = selection.seasonNumber.takeIf { selection.media.type == MediaType.TV },
            episodeNumber = selection.episodeNumber.takeIf { selection.media.type == MediaType.TV },
            episodeTitle = selection.episodeTitle,
            positionSeconds = safePosition,
            durationSeconds = durationSeconds,
            updatedAtMillis = nowMillis.coerceAtLeast(0L),
            completed = ended && safePosition > 0.0,
        )
        put(progress, emitMutation = true, urgentCloudSync = urgentCloudSync)
        return progress
    }

    @Synchronized
    fun startOver(
        selection: PlaybackSelection,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val current = progressFor(selection) ?: return
        put(
            current.copy(
                positionSeconds = 0.0,
                completed = false,
                explicitlyRestarted = true,
                updatedAtMillis = nowMillis.coerceAtLeast(0L),
            ),
            emitMutation = true,
            urgentCloudSync = true,
        )
    }

    fun snapshot(): List<PlaybackProgress> = _entries.value.values
        .sortedByDescending(PlaybackProgress::updatedAtMillis)

    /** Applies an account/cloud snapshot without producing a write-back mutation. */
    @Synchronized
    fun applySyncedEntries(entries: List<PlaybackProgress>) {
        val normalized = entries
            .filter(::isValidPlaybackProgress)
            .associateBy(PlaybackProgress::key)
        _entries.value = normalized
        writeEntries(normalized.values, synchronous = true)
    }

    private fun put(
        progress: PlaybackProgress,
        emitMutation: Boolean,
        urgentCloudSync: Boolean,
    ) {
        if (!isValidPlaybackProgress(progress)) return
        val updated = _entries.value.toMutableMap().apply { put(progress.key, progress) }
        _entries.value = updated
        writeEntries(updated.values, synchronous = urgentCloudSync)
        if (emitMutation) {
            _mutations.tryEmit(
                PlaybackProgressMutation.Changed(progress, urgentCloudSync),
            )
        }
    }

    private fun readEntries(): Map<String, PlaybackProgress> = runCatching {
        val array = JSONArray(preferences.getString(KEY_ENTRIES, "[]"))
        (0 until array.length())
            .mapNotNull { index ->
                array.optJSONObject(index)?.let(::playbackProgressFromJson)
            }
            .filter(::isValidPlaybackProgress)
            .associateBy(PlaybackProgress::key)
    }.getOrDefault(emptyMap())

    private fun writeEntries(
        entries: Collection<PlaybackProgress>,
        synchronous: Boolean = false,
    ) {
        val json = JSONArray()
        entries.sortedByDescending(PlaybackProgress::updatedAtMillis)
            .forEach { json.put(it.toJson()) }
        preferences.edit(commit = synchronous) { putString(KEY_ENTRIES, json.toString()) }
    }

    private companion object {
        const val PREFERENCES_NAME = "aliflix_playback_progress"
        const val KEY_ENTRIES = "entries"
        const val MAX_REASONABLE_DURATION_SECONDS = 7 * 24 * 60 * 60.0
    }
}

fun playbackProgressKey(selection: PlaybackSelection): String = when (selection.media.type) {
    MediaType.MOVIE -> "movie:${selection.media.id}"
    MediaType.TV -> "tv:${selection.media.id}:s${selection.seasonNumber ?: 1}:e${selection.episodeNumber ?: 1}"
}

internal fun PlaybackProgress.toJson(): JSONObject = JSONObject()
    .put("key", key)
    .put("media", media.toJson())
    .put("seasonNumber", seasonNumber)
    .put("episodeNumber", episodeNumber)
    .put("episodeTitle", episodeTitle)
    .put("positionSeconds", positionSeconds)
    .put("durationSeconds", durationSeconds)
    .put("updatedAtMillis", updatedAtMillis)
    .put("completed", completed)
    .put("completionVersion", 2)
    .put("explicitlyRestarted", explicitlyRestarted)

internal fun playbackProgressFromJson(json: JSONObject): PlaybackProgress? = runCatching {
    PlaybackProgress(
        key = json.getString("key"),
        media = Media.fromJson(json.getJSONObject("media")),
        seasonNumber = json.optInt("seasonNumber").takeIf { !json.isNull("seasonNumber") && it >= 0 },
        episodeNumber = json.optInt("episodeNumber").takeIf { json.has("episodeNumber") && it > 0 },
        episodeTitle = json.optString("episodeTitle").takeIf(String::isNotBlank),
        positionSeconds = json.getDouble("positionSeconds"),
        durationSeconds = json.getDouble("durationSeconds"),
        updatedAtMillis = json.getLong("updatedAtMillis"),
        completed = json.optBoolean("completed") &&
            (json.optInt("completionVersion") >= 2 || playbackCompleted(json.getDouble("positionSeconds"), json.getDouble("durationSeconds"))),
        explicitlyRestarted = json.optBoolean("explicitlyRestarted"),
    )
}.getOrNull()

internal fun isValidPlaybackProgress(progress: PlaybackProgress): Boolean =
    progress.key == playbackProgressKey(
        PlaybackSelection(
            media = progress.media,
            seasonNumber = progress.seasonNumber,
            episodeNumber = progress.episodeNumber,
            episodeTitle = progress.episodeTitle,
        ),
    ) &&
        progress.positionSeconds.isFinite() && progress.positionSeconds >= 0.0 &&
        progress.durationSeconds.isFinite() && progress.durationSeconds > 0.0 &&
        progress.positionSeconds <= progress.durationSeconds &&
        progress.updatedAtMillis >= 0L

internal fun playbackCompleted(positionSeconds: Double, durationSeconds: Double): Boolean =
    positionSeconds.isFinite() && durationSeconds.isFinite() && durationSeconds > 0.0 &&
        positionSeconds >= durationSeconds

internal fun mergePlaybackProgress(
    local: List<PlaybackProgress>,
    cloud: List<PlaybackProgress>,
): List<PlaybackProgress> = (local + cloud)
    .filter { isValidPlaybackProgress(it) && (it.positionSeconds > 0.0 || it.explicitlyRestarted) }
    .groupBy(PlaybackProgress::key)
    .values
    .mapNotNull { versions ->
        versions.maxWithOrNull(
            compareBy<PlaybackProgress>(PlaybackProgress::updatedAtMillis)
                .thenBy(PlaybackProgress::positionSeconds),
        )
    }
    .sortedByDescending(PlaybackProgress::updatedAtMillis)
