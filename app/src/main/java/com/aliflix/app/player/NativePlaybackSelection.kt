package com.aliflix.app.player

import android.app.Activity
import android.content.Intent
import com.aliflix.app.model.*
import org.json.JSONArray
import org.json.JSONObject

internal fun PlaybackSelection.nativeJson(): String = JSONObject().apply {
    put("media", media.toJson()); put("season", seasonNumber); put("episode", episodeNumber)
    put("episodeTitle", episodeTitle); put("provider", source.provider.name); put("baseUrl", source.baseUrl)
    put("episodes", JSONArray().apply { availableEpisodes.forEach { episode ->
        put(JSONObject().put("season", episode.seasonNumber).put("number", episode.number).put("title", episode.title))
    } })
}.toString()

internal fun nativeSelection(raw: String): PlaybackSelection = JSONObject(raw).let { json ->
    val episodes = json.optJSONArray("episodes") ?: JSONArray()
    PlaybackSelection(Media.fromJson(json.getJSONObject("media")),
        seasonNumber = json.optInt("season", 1), episodeNumber = json.optInt("episode", 1),
        episodeTitle = json.optString("episodeTitle").takeIf { it.isNotBlank() && it != "null" },
        availableEpisodes = (0 until episodes.length()).map { index -> episodes.getJSONObject(index).let {
            Episode(it.getInt("season"), it.getInt("number"), it.getString("title"))
        } },
        source = PlaybackSource(PlaybackProviderId.valueOf(json.getString("provider")), json.getString("baseUrl")))
}

internal fun launchNativeSelection(activity: Activity, selection: PlaybackSelection, language: String, autoSubtitles: Boolean) {
    activity.startActivity(Intent().setClassName(activity, "com.aliflix.app.player.NativePlayerActivity")
        .putExtra("selection", selection.nativeJson()).putExtra("subtitleLanguage", language)
        .putExtra("autoSubtitles", autoSubtitles))
}

/** Provider labels can include decorations, e.g. 'Vid • HD'. Avoid matching unrelated Videasy. */
internal fun nativeServerRank(label: String): Int = listOf("Vid", "Mist", "Mistify", "Flix", "Peach")
    .indexOfFirst { preferred -> label.trim().matches(Regex("^" + preferred + "(?:\\b|[\\s•-]).*", RegexOption.IGNORE_CASE)) }
    .takeIf { it >= 0 } ?: 5

internal fun orderedNativeServers(servers: List<MoviepireServerOption>): List<MoviepireServerOption> =
    servers.distinctBy { it.key }.sortedBy { nativeServerRank(it.label) }

/** Same public embeds used by Moviepire, avoiding catalogue/bootstrap round trips. */
internal fun preferredNativeEmbeds(selection: PlaybackSelection): List<Pair<String, String>> {
    if (!selection.source.provider.usesMoviepire) return emptyList()
    val route = if (selection.media.type == MediaType.TV) "tv/${selection.media.id}/${selection.seasonNumber ?: 1}/${selection.episodeNumber ?: 1}"
        else "movie/${selection.media.id}"
    return listOf("Vid" to "https://embed.wplay.me/embed/$route", "Mist" to "https://play.xpass.top/e/$route",
        "Mistify" to "https://vaplayer.ru/embed/$route", "Flix" to "https://vidbolt.xyz/$route",
        "Peach" to "https://peachify.top/embed/$route")
}

/** Runs only in the temporary resolver, on approved origins; destroyed after handoff. */
internal fun nativePreparationScript(): String = """
    (() => {
      const deadline = Date.now() + 45000;
      const clicked = new WeakMap();
      const host = location.hostname;
      const isHost = name => host === name || host.endsWith('.' + name);
      const selectors = ['.vjs-big-play-button', '.jw-icon-display', '.plyr__control--overlaid', 'button[aria-label="Play"]', 'button[title="Play Video"]'];
      // Flix has an idle cover before mounting its video; other controls toggle playback.
      if (isHost('vidbolt.xyz')) selectors.unshift('.embed-idle__play-btn');
      // Peach uses an icon-only overlay with no accessible label; target that play cover only.
      if (isHost('peachify.top')) selectors.unshift('div.bg-black\\/30.visible > button.pointer-events-auto');
      const prepare = () => {
        document.querySelectorAll('video').forEach(video => {
          if (video.mediaKeys) return;
          video.muted = true;
          video.setAttribute('playsinline', '');
          video.preload = 'auto';
          if (video.paused) { const promise = video.play(); if (promise) promise.catch(() => {}); }
        });
        const videoPlaying = Array.from(document.querySelectorAll('video')).some(v => !v.paused && v.readyState >= 2);
        if (!videoPlaying) {
          const button = Array.from(document.querySelectorAll(selectors.join(','))).find(b => !b.disabled && b.getBoundingClientRect().width > 0);
          if (button) {
            const state = clicked.get(button) || { count: 0, at: 0 };
            if (state.count < 3 && Date.now() - state.at > 3000) {
              clicked.set(button, { count: state.count + 1, at: Date.now() }); button.click();
            }
          }
        }
        if (Date.now() < deadline) setTimeout(prepare, 750);
      };
      prepare();
    })();
""".trimIndent()
