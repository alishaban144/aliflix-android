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
    val preferEmbeddedSubtitles: Boolean = false,
    val offlineDownloadId: String = "",
    val offlineAutoSubtitles: Boolean = true,
    val streamUrlRules: String = "",
    val preferredVideoWidth: Int = 0,
    val preferredVideoHeight: Int = 0,
) {
    fun toJson(): String = JSONObject().apply {
        put("streamUrlRules", streamUrlRules)
        put("preferredVideoWidth", preferredVideoWidth); put("preferredVideoHeight", preferredVideoHeight)
        put("offlineDownloadId", offlineDownloadId); put("offlineAutoSubtitles", offlineAutoSubtitles)
        put("url", url); put("mimeType", mimeType); put("referer", referer)
        put("userAgent", userAgent); put("cookie", cookie); put("title", title)
        put("positionMs", positionMs); put("playing", playing); put("subtitlesVtt", subtitlesVtt)
        put("selectionJson", selectionJson)
        put("preferEmbeddedSubtitles", preferEmbeddedSubtitles)
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
                json.optBoolean("preferEmbeddedSubtitles", false),
                json.optString("offlineDownloadId"),
                json.optBoolean("offlineAutoSubtitles", true),
                json.optString("streamUrlRules"),
                json.optInt("preferredVideoWidth"),
                json.optInt("preferredVideoHeight"),
            )
        }
    }
}

/** A variant/media playlist is playable but can omit every alternate audio rendition in its master. */
internal fun shouldAwaitHlsMaster(stream: JSONObject, ageMs: Long): Boolean =
    (stream.optString("mimeType").contains("mpegurl", ignoreCase = true) ||
        stream.optString("url").contains(".m3u8", ignoreCase = true)) &&
        !stream.optString("manifestKind").equals("master", ignoreCase = true) &&
        ageMs in 0 until 8_000L

/** Never let later video/progress reports demote an already-discovered master playlist. */
internal fun shouldReplaceNativeStream(previous: JSONObject?, candidate: JSONObject): Boolean {
    fun rank(stream: JSONObject): Int = when (stream.optString("manifestKind")) {
        "master" -> 3
        "variant" -> 2
        else -> 1
    }
    return previous == null || rank(candidate) > rank(previous) ||
        (rank(candidate) == rank(previous) && candidate.optString("url") != previous.optString("url"))
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

/** Observe the provider's own requests. Prefer its HLS multivariant master, retaining AUDIO renditions. */
internal fun nativeStreamDiscoveryScript(): String = """
    (() => {
      if (window.__aliflixStreamDiscovery) return;
      window.__aliflixStreamDiscovery = true;
      let master = null, variant = null, reportedUrl = '', reportedRank = 0;
      const startedAt = Date.now();
      const isHls = url => /\.m3u8(?:[?#]|$)/i.test(String(url || ''));
      const reportStream = (url, mimeType, rank) => {
        const clean = String(url || '');
        if (!/^https?:\/\//i.test(clean) || rank < reportedRank ||
            (rank === reportedRank && clean === reportedUrl)) return;
        reportedRank = rank;
        reportedUrl = clean;
        const stream = {
          url: clean,
          mimeType: mimeType || 'application/x-mpegURL',
          referer: location.href,
          manifestKind: rank === 3 ? 'master' : rank === 2 ? 'variant' : 'direct'
        };
        const bridge = window.AliflixPlaybackProgress;
        if (bridge && typeof bridge.postMessage === 'function') {
          try { bridge.postMessage(JSON.stringify({event: 'aliflix-stream', nativeStream: stream})); } catch (_) {}
        }
      };
      const remember = (url, text) => {
        if (!/^https?:\/\//i.test(String(url || '')) ||
            typeof text !== 'string' || !text.trimStart().startsWith('#EXTM3U')) return;
        // A master carries variant streams or EXT-X-MEDIA audio groups. A child media playlist
        // frequently has only one preselected language; handing it off discards other tracks.
        const isMaster = /^#EXT-X-STREAM-INF:/m.test(text) ||
            /^#EXT-X-MEDIA\s*:\s*TYPE=AUDIO/im.test(text);
        if (isMaster) {
          master = url;
          reportStream(url, 'application/x-mpegURL', 3);
        } else if (!variant) {
          variant = url;
        }
      };
      const originalFetch = window.fetch;
      if (originalFetch) window.fetch = function(...args) {
        return originalFetch.apply(this, args).then(response => {
          const type = response.headers.get('content-type') || '';
          if (/mpegurl/i.test(type) || isHls(response.url)) {
            response.clone().text().then(text => remember(response.url, text)).catch(() => {});
          }
          return response;
        });
      };
      const originalOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function(...args) {
        this.addEventListener('load', () => {
          try {
            if (this.responseType === '' || this.responseType === 'text') remember(this.responseURL, this.responseText);
            else if (this.responseType === 'arraybuffer' && this.response?.byteLength < 2097152)
              remember(this.responseURL, new TextDecoder().decode(this.response));
          } catch (_) {}
        }, {once: true});
        return originalOpen.apply(this, args);
      };
      window.__aliflixNativeStream = video => {
        if (video?.mediaKeys) return null;
        if (master) return {url:master, mimeType:'application/x-mpegURL', referer:location.href, manifestKind:'master'};
        const src = video ? (video.currentSrc || video.src) : '';
        if (variant) return {url:variant, mimeType:'application/x-mpegURL', referer:location.href, manifestKind:'variant'};
        if (/^https?:\/\//i.test(src)) return {
          url:src, mimeType:isHls(src) ? 'application/x-mpegURL' : 'video/mp4',
          referer:location.href, manifestKind:isHls(src) ? 'variant' : 'direct'
        };
        return null;
      };
      const watcher = window.setInterval(() => {
        if (master) {
          reportStream(master, 'application/x-mpegURL', 3);
          window.clearInterval(watcher);
          return;
        }
        const candidate = window.__aliflixNativeStream(document.querySelector('video'));
        // A progressive file is complete. For HLS, wait for the master/alternate audio:
        // a child media playlist frequently carries a single preselected language, and
        // handing it off discards every other track (e.g. Dark S1E1 German on CineJoy).
        if (candidate && (candidate.mimeType === 'video/mp4' || Date.now() - startedAt >= 8000)) {
          reportStream(candidate.url, candidate.mimeType, candidate.manifestKind === 'variant' ? 2 : 1);
        }
        if (Date.now() - startedAt > 60000) window.clearInterval(watcher);
      }, 350);
      window.__aliflixNativeStreamUrl = () => {
        const stream = window.__aliflixNativeStream(document.querySelector('video'));
        return stream ? String(stream.url || '') : '';
      };
    })();
""".trimIndent()
