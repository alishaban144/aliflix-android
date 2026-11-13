package com.aliflix.app.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.PlaybackPreferences
import com.aliflix.app.model.PlaybackSelection

internal data class SharedSceneRange(val startMs: Long, val endMs: Long) {
    init { require(startMs >= 0 && startMs < endMs && endMs <= MAX_SCENE_TIME_MS) }

    fun putInto(intent: Intent): Intent = intent.putExtra("sceneStart", startMs).putExtra("sceneEnd", endMs)

    companion object {
        fun from(intent: Intent): SharedSceneRange? {
            val start = intent.getLongExtra("sceneStart", -1)
            val end = intent.getLongExtra("sceneEnd", -1)
            return if (start >= 0 && start < end && end <= MAX_SCENE_TIME_MS) SharedSceneRange(start, end) else null
        }
    }
}

private const val MAX_SCENE_TIME_MS = 86_400_000L
private const val SCENE_HOST = "aliflix-recommendations.equable-equipment.workers.dev"
private val scenePath = Regex("/scene/(movie|tv)/([0-9]{1,10})/([0-9]{1,4})/([0-9]{1,4})/([0-9]{1,8})/([0-9]{1,8})")

internal data class SharedSceneLink(
    val media: Media,
    val season: Int?,
    val episode: Int?,
    val provider: PlaybackProvider?,
    val range: SharedSceneRange,
) {
    fun selection(preferences: PlaybackPreferences) =
        PlaybackSelection(media, season, episode, source = preferences.sourceFor(media, provider))

    fun uri(): Uri = Uri.Builder().scheme("https").authority(SCENE_HOST)
        .appendPath("scene").appendPath(media.type.routeName).appendPath(media.id.toString())
        .appendPath((season ?: 0).toString()).appendPath((episode ?: 0).toString())
        .appendPath(range.startMs.toString()).appendPath(range.endMs.toString())
        .appendQueryParameter("title", media.title.take(180))
        .apply {
            provider?.let { appendQueryParameter("source", it.name) }
            media.imdbId?.let { appendQueryParameter("imdb", it) }
            media.originalLanguage.takeIf(String::isNotBlank)?.let { appendQueryParameter("lang", it) }
            media.year.takeIf(String::isNotBlank)?.let { appendQueryParameter("year", it) }
            if ("Animation" in media.genres) appendQueryParameter("animation", "1")
        }.build()

    companion object {
        fun from(selection: PlaybackSelection, range: SharedSceneRange) = SharedSceneLink(
            selection.media,
            if (selection.media.type == MediaType.TV) selection.seasonNumber ?: 1 else null,
            if (selection.media.type == MediaType.TV) selection.episodeNumber ?: 1 else null,
            selection.source.identity,
            range,
        )

        fun parse(uri: Uri): SharedSceneLink? {
            if (uri.scheme !in setOf("https", "http") || uri.encodedAuthority != SCENE_HOST ||
                uri.fragment != null || uri.toString().length > 3_200) return null
            val match = scenePath.matchEntire(uri.encodedPath.orEmpty()) ?: return null
            val (kind, idText, seasonText, episodeText) = match.destructured
            val id = idText.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val season = seasonText.toInt()
            val episode = episodeText.toInt()
            val type = MediaType.from(kind)
            if (type == MediaType.TV && episode == 0 || type == MediaType.MOVIE && (season != 0 || episode != 0)) return null
            val start = match.groupValues[5].toLong()
            val end = match.groupValues[6].toLong()
            if (start >= end || end > MAX_SCENE_TIME_MS) return null
            val title = uri.getQueryParameter("title")?.take(180)?.filterNot(Char::isISOControl)
                ?.trim()?.takeIf(String::isNotEmpty) ?: "Aliflix"
            val media = Media(id, type, title,
                imdbId = uri.getQueryParameter("imdb")?.takeIf { it.matches(Regex("tt[0-9]{1,12}")) },
                originalLanguage = uri.getQueryParameter("lang")?.takeIf { it.matches(Regex("[a-z]{2,3}")) }.orEmpty(),
                year = uri.getQueryParameter("year")?.takeIf { it.matches(Regex("[0-9]{4}")) }.orEmpty(),
                genres = if (uri.getQueryParameter("animation") == "1") listOf("Animation") else emptyList(),
            )
            return SharedSceneLink(media, season.takeIf { type == MediaType.TV }, episode.takeIf { type == MediaType.TV },
                PlaybackProvider.fromStoredValue(uri.getQueryParameter("source")), SharedSceneRange(start, end))
        }
    }
}

class SharedSceneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scene = intent.data?.let(SharedSceneLink::parse)
        if (intent.action != Intent.ACTION_VIEW || scene == null) {
            Toast.makeText(this, "Invalid scene link", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val preferences = (application as AliflixApplication).playbackProviderRepository.preferences.value
        val selection = scene.selection(preferences)
        startActivity(scene.range.putInto(Intent(this, NativePlayerActivity::class.java))
            .putExtra("selection", selection.nativeJson())
            .putExtra("subtitleLanguage", preferences.preferredSubtitleLanguage.code)
            .putExtra("autoSubtitles", preferences.autoDisplaySubtitles), nativePhoneLaunchOptions(this))
        finish()
    }
}
