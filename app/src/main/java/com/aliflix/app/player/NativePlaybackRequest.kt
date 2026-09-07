package com.aliflix.app.player

import org.json.JSONObject
import java.net.URI

/** A playable resource reported by the currently playing, approved provider frame. */
internal data class NativePlaybackRequest(
    val url: String,
    val mimeType: String,
    val referer: String,
    val userAgent: String,
    val cookie: String,
    val title: String,
    val positionMs: Long,
    val playing: Boolean,
    val subtitlesVtt: String = "",
    val selectionJson: String = "",
    val subtitleLanguage: String = "en",
    val subtitleLabel: String = "Aliflix subtitles",
) {
    fun toJson(): String = JSONObject().apply {
        put("url", url); put("mimeType", mimeType); put("referer", referer)
        put("userAgent", userAgent); put("cookie", cookie); put("title", title)
        put("positionMs", positionMs); put("playing", playing); put("subtitlesVtt", subtitlesVtt)
        put("selectionJson", selectionJson)
        put("subtitleLanguage", subtitleLanguage); put("subtitleLabel", subtitleLabel)
    }.toString()

    companion object {
        fun fromJson(raw: String): NativePlaybackRequest {
            val json = JSONObject(raw)
            val url = json.getString("url")
            require(isNativeStreamUrl(url)) { "The provider has not exposed a playable video stream" }
            return NativePlaybackRequest(
                url, json.getString("mimeType"), json.getString("referer"),
                json.getString("userAgent"), json.optString("cookie"), json.getString("title"),
                json.optLong("positionMs").coerceAtLeast(0), json.optBoolean("playing", true),
                json.optString("subtitlesVtt"),
                json.optString("selectionJson"),
                json.optString("subtitleLanguage", "en"),
                json.optString("subtitleLabel", "Aliflix subtitles"),
            )
        }
    }
}

internal fun isNativeStreamUrl(raw: String): Boolean = runCatching {
    val uri = URI(raw)
    uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
}.getOrDefault(false)

internal fun nativePlaybackOriginRules(sourceHost: String): Set<String> =
    (PlaybackNavigationPolicy.defaultApprovedPlaybackHosts + PlaybackNavigationPolicy.moviepirePlayerDocumentHosts + sourceHost)
        .flatMap { host -> listOf("https://$host", "https://*.$host") }.toSet()

internal fun nativeSubtitlesVtt(raw: String?, delaySeconds: Double): String {
    if (raw == null) return ""
    val cues = org.json.JSONArray(raw)
    fun timestamp(seconds: Double): String {
        val ms = (seconds.coerceAtLeast(0.0) * 1000).toLong()
        return "%02d:%02d:%02d.%03d".format(java.util.Locale.ROOT, ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000)
    }
    return buildString {
        append("WEBVTT\n\n")
        for (i in 0 until cues.length()) {
            val cue = cues.getJSONArray(i)
            val end = cue.getDouble(1) + delaySeconds
            if (end <= 0) continue
            append(timestamp(cue.getDouble(0) + delaySeconds)); append(" --> "); append(timestamp(end)); append('\n')
            append(cue.getString(2).replace("\r", "").replace("\n\n", "\n")); append("\n\n")
        }
    }
}

/** Capture HLS master/variant URLs in the same frame as the actual video, including MSE/blob players. */
internal fun nativeStreamDiscoveryScript(): String = """
    (() => {
      if (window.__aliflixStreamDiscovery) return;
      window.__aliflixStreamDiscovery = true;
      let manifest = null;
      const remember = (url, text) => {
        if (typeof text !== 'string' || !text.trimStart().startsWith('#EXTM3U')) return;
        const master = text.includes('#EXT-X-STREAM-INF');
        if (!manifest || master || !manifest.master) manifest = {url, master};
      };
      const fetchOriginal = window.fetch;
      if (fetchOriginal) window.fetch = function(...args) {
        return fetchOriginal.apply(this, args).then(response => {
          const type = response.headers.get('content-type') || '';
          if (/mpegurl/i.test(type) || /\.m3u8(?:[?#]|$)/i.test(response.url)) {
            response.clone().text().then(text => remember(response.url, text)).catch(() => {});
          }
          return response;
        });
      };
      const open = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(...args) {
        this.addEventListener('load', () => {
          try {
            if (this.responseType === '' || this.responseType === 'text') remember(this.responseURL, this.responseText);
            else if (this.responseType === 'arraybuffer' && this.response.byteLength < 2097152)
              remember(this.responseURL, new TextDecoder().decode(this.response));
          } catch (_) {}
        }, {once:true});
        return open.apply(this, args);
      };
      window.__aliflixNativeStream = video => {
        if (video.mediaKeys) return null;
        const src = video.currentSrc || video.src;
        if (/^https?:\/\//.test(src)) return {url:src, mimeType:/\.m3u8(?:[?#]|$)/i.test(src) ? 'application/x-mpegURL' : 'video/mp4', referer:location.href};
        return manifest ? {url:manifest.url, mimeType:'application/x-mpegURL', referer:location.href} : null;
      };
    })();
""".trimIndent()
