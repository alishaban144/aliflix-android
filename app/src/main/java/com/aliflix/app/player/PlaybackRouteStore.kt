package com.aliflix.app.player

import com.aliflix.app.data.playbackProgressKey
import com.aliflix.app.model.PlaybackSelection
import org.json.JSONObject
import java.io.File

internal data class SavedPlaybackRoute(val selection: PlaybackSelection, val server: String, val request: NativePlaybackRequest?, val savedAt: Long)

/** App-private, excluded from backup. Successful routes outlive process restarts. */
internal class PlaybackRouteStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) {
    private fun file(selection: PlaybackSelection) = File(directory, playbackProgressKey(selection).replace(':', '_') + ".json")

    fun load(selection: PlaybackSelection): SavedPlaybackRoute? = runCatching {
        val json = JSONObject(file(selection).readText())
        val saved = nativeSelection(json.getString("selection"))
        require(playbackProgressKey(saved) == playbackProgressKey(selection))
        val savedAt = json.getLong("savedAt")
        SavedPlaybackRoute(saved.copy(media = selection.media, availableEpisodes = selection.availableEpisodes),
            json.getString("server"),
            json.optString("request").takeIf { it.isNotBlank() && now() - savedAt in 0..DIRECT_STREAM_MAX_AGE_MS }
                ?.let { runCatching { NativePlaybackRequest.fromJson(it) }.getOrNull() }, savedAt)
    }.getOrNull()

    fun save(selection: PlaybackSelection, server: String, request: NativePlaybackRequest) {
        directory.mkdirs()
        val target = file(selection)
        val temp = File(directory, target.name + ".tmp")
        temp.writeText(JSONObject().put("selection", selection.nativeJson()).put("server", server)
            .put("savedAt", now()).put("request", request.copy(subtitlesVtt = "").toJson()).toString())
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        directory.listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() }
            ?.drop(150)?.forEach { it.delete() }
    }

    fun invalidateStream(selection: PlaybackSelection) {
        val target = file(selection)
        runCatching {
            val json = JSONObject(target.readText()).put("request", "")
            target.writeText(json.toString())
        }
    }

    companion object { const val DIRECT_STREAM_MAX_AGE_MS = 6L * 60 * 60 * 1000 }
}
