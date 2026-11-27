package com.aliflix.app.player

import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.MobilePlaybackProvider

import android.annotation.SuppressLint
import android.app.Activity
import android.content.MutableContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aliflix.app.BuildConfig
import com.aliflix.app.data.PlaybackProgress
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.model.MediaType
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.isIndianCatalogue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.net.URI

data class MoviepireServerOption(
    val key: String,
    val label: String,
    val selected: Boolean,
)

class WebPlayerController(
    private val activity: ComponentActivity,
    private val playbackProgressStore: PlaybackProgressStore,
    private val nativePreparation: Boolean = false,
    private val nativeEmbedUrl: String? = null,
) {
    private var nativeStream: JSONObject? = null
    private var nativeStreamReceivedAt = 0L
    private var flixerVideoReady = false

    private fun rememberNativeStream(candidate: JSONObject?) {
        if (candidate != null && shouldReplaceNativeStream(nativeStream, candidate)) {
            nativeStream = candidate
            nativeStreamReceivedAt = SystemClock.elapsedRealtime()
        }
    }

    internal fun preparedNativeRequest(after: Long, positionMs: Long, allowUnready: Boolean = false): NativePlaybackRequest? {
        if (activeSelection?.source?.identity == MobilePlaybackProvider.FLIXER && !allowUnready && !flixerVideoReady) return null
        if (nativeStreamReceivedAt < after) return null
        val stream = nativeStream ?: return null
        val selection = activeSelection ?: return null
        val view = webView ?: return null
        val url = stream.optString("url")
        if (!isNativeStreamUrl(url)) return null
        if (selection.source.identity != MobilePlaybackProvider.FLIXER && shouldAwaitHlsMaster(stream, SystemClock.elapsedRealtime() - nativeStreamReceivedAt)) return null
        return NativePlaybackRequest(url, stream.optString("mimeType", "video/mp4"),
            stream.optString("referer"), view.settings.userAgentString,
            CookieManager.getInstance().getCookie(url).orEmpty(),
            selection.media.title, positionMs, true, selectionJson = selection.nativeJson())
    }

    internal fun refreshNativeServers() {
        val view = webView ?: return
        activeSelection?.let { discoverMoviepireServers(view, it) }
    }

    /**
     * Safety net for providers that render their player in the main document. The message bridge
     * is the primary path; this recovers the same manifest directly when the bridge is missing on
     * this WebView or its payload was dropped, so a playable stream is never thrown away.
     */
    internal fun probeNativeStreamUrl() {
        val view = webView ?: return
        if (activeSelection?.source?.identity == MobilePlaybackProvider.FLIXER) {
            view.evaluateJavascript("Boolean(document.querySelector('video')?.readyState >= 2 && !document.querySelector('video')?.error)") {
                flixerVideoReady = it == "true"
            }
        } else if (nativeStream != null) return
        view.evaluateJavascript(
            """
            (() => {
              try {
                const probe = window.__aliflixNativeStreamUrl;
                if (typeof probe === 'function') return String(probe() || '');
                const video = document.querySelector('video');
                const native = video && window.__aliflixNativeStream;
                const stream = native ? native(video) : null;
                return stream && stream.url ? String(stream.url) : '';
              } catch (_) { return ''; }
            })();
            """.trimIndent(),
        ) { value ->
            val url = value?.trim('"').orEmpty()
            if (!isNativeStreamUrl(url)) return@evaluateJavascript
            // A wrapper document has no meaningful URL of its own, so only accept a probe that
            // can name the page the manifest was requested from.
            val referer = view.url?.takeIf { it.startsWith("https://") } ?: return@evaluateJavascript
            val isHls = url.contains(".m3u8", ignoreCase = true)
            rememberNativeStream(JSONObject().apply {
                put("url", url)
                put("mimeType", if (isHls) "application/x-mpegURL" else "video/mp4")
                put("referer", referer)
                // A probed .m3u8 is a variant until the discovery bridge proves it is
                // a master; marking it direct would bypass the master wait and drop
                // alternate audio renditions on every source.
                put("manifestKind", if (isHls) "variant" else "direct")
            })
        }
    }

    private fun preparationScript() = if (nativePreparation) "\n" + nativePreparationScript() else ""
    private val resolvedEmbedOrigin get()=trustedNativeEmbedOrigin(nativeEmbedUrl, nativePreparation)
    // The public MoviesAPI player embeds the actual video one frame deeper.
    private val resolvedEmbedChildOrigins: Set<String> get() = if (resolvedEmbedOrigin == "https://moviesapi.to") {
        setOf("https://cdn.vidspark.to", "https://player.vidprime.top", "https://cdn.vidprime.top")
    } else emptySet()
    private fun nativeOriginRules(sourceHost:String)=nativePlaybackOriginRules(sourceHost)+setOfNotNull(resolvedEmbedOrigin)+resolvedEmbedChildOrigins
    private var webView: WebView? = null
    private var loadedKey: String? = null
    private var activeSelection: PlaybackSelection? = null
    private var customView: View? = null
    private var customViewContainer: FrameLayout? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var moviepireDocumentStartScriptHandler: ScriptHandler? = null
    private var moviepireProtectedSourceHost: String? = null
    private var moviepireProtectedNativeMode = false
    private var moviepireMessageListenerInstalled = false
    private var latestPositionSeconds = 0.0
    private var latestDurationSeconds = 0.0
    private var latestPlaybackEnded = false
    private var lastLocalProgressWriteAt = 0L
    private var pendingSeekSeconds: Double? = null
    private var pendingServerKey: String? = null
    private var serverSwitchStartedAt = 0L
    private var moviepireEpisodeBootstrapReloads = 0
    private var playerVisible = false
    private var nativeFullscreenRequested = false
    private var orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var localPlayerHost: FrameLayout? = null
    private var activePlayerReplyProxy: JavaScriptReplyProxy? = null
    private var activeSubtitleContentKey: String? = null
    private var activeSubtitleCuesJson: String? = null
    private var activeSubtitleDelaySeconds = 0.0
    private var activeSubtitleFontPercent = 100
    private var activeSubtitleLanguage = ""
    private var activeSubtitleLabel = "Aliflix"
    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _webViewGeneration = MutableStateFlow(0)
    val webViewGeneration: StateFlow<Int> = _webViewGeneration.asStateFlow()

    private val _moviepireServers = MutableStateFlow<List<MoviepireServerOption>>(emptyList())
    val moviepireServers: StateFlow<List<MoviepireServerOption>> = _moviepireServers.asStateFlow()

    private val _switchingMoviepireServer = MutableStateFlow(false)
    val switchingMoviepireServer: StateFlow<Boolean> =
        _switchingMoviepireServer.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    val isPlaying: StateFlow<Boolean> = _playing.asStateFlow()

    private val _videoFound = MutableStateFlow(false)
    val videoFound: StateFlow<Boolean> = _videoFound.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _bufferedPositionMs = MutableStateFlow(0L)
    val bufferedPositionMs: StateFlow<Long> = _bufferedPositionMs.asStateFlow()

    fun viewFor(selection: PlaybackSelection): FrameLayout {
        val host = localPlayerHost ?: FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
        }.also { localPlayerHost = it }
        val view = webView ?: createWebView().also { webView = it }
        if (activeSelection?.key != selection.key) {
            flushProgress(urgentCloudSync = true)
            if (activeSubtitleContentKey != subtitleContentKey(selection)) clearSubtitles()
        }
        activeSelection = selection
        configureMobileMoviepireDocumentStartProtection(view, selection)
        val defaultKey = selection.key
        if (loadedKey != defaultKey) {
            nativeStream = null
            flixerVideoReady = false
            loadedKey = defaultKey
            pendingSeekSeconds = playbackProgressStore.progressFor(selection)?.takeIf { it.resumeEligible }?.positionSeconds
            latestPositionSeconds = 0.0
            latestDurationSeconds = 0.0
            latestPlaybackEnded = false
            lastLocalProgressWriteAt = 0L
            _moviepireServers.value = emptyList()
            _switchingMoviepireServer.value = false
            _playing.value = false
            _videoFound.value = false
            _currentPositionMs.value = ((pendingSeekSeconds ?: 0.0) * 1000).toLong()
            _durationMs.value = 0L
            _bufferedPositionMs.value = 0L
            activePlayerReplyProxy = null
            moviepireEpisodeBootstrapReloads = 0
            _error.value = null
            view.stopLoading()
            loadSelection(view, selection)
        }
        attachPlayerView(host, activity)
        (host.parent as? ViewGroup)?.removeView(host)
        return host
    }

    fun savedProgressFor(selection: PlaybackSelection): PlaybackProgress? =
        playbackProgressStore.progressFor(selection)

    fun prepareSelection(selection: PlaybackSelection) {
        if (activeSelection?.key == selection.key) return
        flushProgress(urgentCloudSync = true)
        if (activeSubtitleContentKey != subtitleContentKey(selection)) clearSubtitles()
        activeSelection = selection
        pendingSeekSeconds = playbackProgressStore.progressFor(selection)?.takeIf { it.resumeEligible }?.positionSeconds
        latestPositionSeconds = 0.0
        latestDurationSeconds = 0.0
        latestPlaybackEnded = false
        pendingServerKey = null
        _switchingMoviepireServer.value = false
        webView?.apply {
            visibility = View.INVISIBLE
            onPause()
        }
    }

    fun requestResume(progress: PlaybackProgress) {
        if (!progress.resumeEligible) return
        pendingSeekSeconds = progress.positionSeconds
    }

    fun startOver(selection: PlaybackSelection) {
        pendingSeekSeconds = 0.0
        playbackProgressStore.startOver(selection)
    }

    fun setSubtitles(
        selection: PlaybackSelection,
        cues: List<SubtitleCue>,
        delaySeconds: Double,
        fontPercent: Int,
        languageCode: String,
        label: String,
    ) {
        if (
            !selection.source.identity.usesMoviepire ||
            activeSelection?.let(::subtitleContentKey) != subtitleContentKey(selection)
        ) return

        val cueArray = JSONArray()
        cues.asSequence().take(MAX_SUBTITLE_CUES).forEach { cue ->
            if (
                cue.startSeconds.isFinite() && cue.endSeconds.isFinite() &&
                cue.startSeconds >= 0.0 && cue.endSeconds > cue.startSeconds && cue.text.isNotBlank()
            ) {
                cueArray.put(
                    JSONArray()
                        .put(cue.startSeconds)
                        .put(cue.endSeconds)
                        .put(cue.text.take(MAX_SUBTITLE_CUE_TEXT_LENGTH)),
                )
            }
        }
        if (cueArray.length() == 0) return
        activeSubtitleContentKey = subtitleContentKey(selection)
        activeSubtitleCuesJson = cueArray.toString()
        activeSubtitleDelaySeconds = delaySeconds.coerceIn(-SUBTITLE_DELAY_LIMIT_SECONDS, SUBTITLE_DELAY_LIMIT_SECONDS)
        activeSubtitleFontPercent = fontPercent.coerceIn(MIN_SUBTITLE_FONT_PERCENT, MAX_SUBTITLE_FONT_PERCENT)
        activeSubtitleLanguage = languageCode.lowercase().take(8)
        activeSubtitleLabel = label.take(80).ifBlank { "Aliflix" }
        postActiveSubtitles()
    }

    fun updateSubtitlePresentation(delaySeconds: Double, fontPercent: Int) {
        if (activeSubtitleCuesJson == null) return
        activeSubtitleDelaySeconds = delaySeconds.coerceIn(-SUBTITLE_DELAY_LIMIT_SECONDS, SUBTITLE_DELAY_LIMIT_SECONDS)
        activeSubtitleFontPercent = fontPercent.coerceIn(MIN_SUBTITLE_FONT_PERCENT, MAX_SUBTITLE_FONT_PERCENT)
        postActiveSubtitles()
    }

    fun clearSubtitles() {
        val hadSubtitles = activeSubtitleCuesJson != null
        activeSubtitleContentKey = null
        activeSubtitleCuesJson = null
        activeSubtitleDelaySeconds = 0.0
        activeSubtitleFontPercent = 100
        activeSubtitleLanguage = ""
        activeSubtitleLabel = "Aliflix"
        if (hadSubtitles) {
            postSubtitleCommand(JSONObject().put("type", "aliflix-subtitles-clear").toString())
        }
    }

    private fun postActiveSubtitles(replyProxy: JavaScriptReplyProxy? = null) {
        val cues = activeSubtitleCuesJson ?: return
        val language = JSONObject.quote(activeSubtitleLanguage)
        val label = JSONObject.quote(activeSubtitleLabel)
        val command = """{"type":"aliflix-subtitles","cues":$cues,"delaySeconds":$activeSubtitleDelaySeconds,"fontPercent":$activeSubtitleFontPercent,"language":$language,"label":$label}"""
        postSubtitleCommand(command, replyProxy)
    }

    private fun postSubtitleCommand(
        command: String,
        replyProxy: JavaScriptReplyProxy? = null,
    ) {
        val view = webView ?: return
        (replyProxy ?: activePlayerReplyProxy)?.let { proxy ->
            runCatching { proxy.postMessage(command) }
        }
        view.evaluateJavascript(
            """
            (() => {
              const message = $command;
              window.postMessage(message, "*");
              document.querySelectorAll("iframe").forEach(frame => {
                try { frame.contentWindow?.postMessage(message, "*"); } catch (_) {}
              });
              return true;
            })();
            """.trimIndent(),
            null,
        )
    }

    fun selectMoviepireServer(option: MoviepireServerOption) {
        val selection = activeSelection ?: return
        val view = webView ?: return
        if (!selection.source.identity.usesMoviepire) return
        if (_moviepireServers.value.none { it.key == option.key }) return
        if (option.selected || _moviepireServers.value.any { it.key == option.key && it.selected }) {
            return
        }
        val restorePosition = latestPositionSeconds.takeIf { it > 0.0 }
            ?: playbackProgressStore.progressFor(selection)?.positionSeconds?.takeIf { it > 0.0 }
        pendingSeekSeconds = restorePosition
        nativeStream = null
        pendingServerKey = option.key
        serverSwitchStartedAt = SystemClock.elapsedRealtime()
        _switchingMoviepireServer.value = true
        flushProgress(urgentCloudSync = true)
        val quotedKey = JSONObject.quote(option.key)
        view.evaluateJavascript(
            """
            (() => {
              const select = document.querySelector('select[data-aliflix-server-select="true"]');
              if (!select) return false;
              const option = Array.from(select.options).find(
                candidate => candidate.dataset.aliflixServerKey === $quotedKey
              );
              if (!option) return false;
              select.value = option.value;
              option.selected = true;
              select.dispatchEvent(new Event('input', { bubbles: true }));
              select.dispatchEvent(new Event('change', { bubbles: true }));
              return true;
            })();
            """.trimIndent(),
        ) { result ->
            if (result == "true") {
                listOf(250L, 900L, 2_200L).forEach { delay ->
                    view.postDelayed(
                        { if (isActiveSelection(view, selection)) discoverMoviepireServers(view, selection) },
                        delay,
                    )
                }
                view.postDelayed(
                    {
                        if (
                            isActiveSelection(view, selection) &&
                            pendingServerKey == option.key
                        ) {
                            _switchingMoviepireServer.value = false
                            discoverMoviepireServers(view, selection)
                        }
                    },
                    SERVER_SWITCH_UI_TIMEOUT_MILLIS,
                )
            } else {
                pendingServerKey = null
                pendingSeekSeconds = null
                _switchingMoviepireServer.value = false
                discoverMoviepireServers(view, selection)
            }
        }
    }

    fun showProviderOptions() {
        activeSelection ?: return
        webView?.evaluateJavascript(
            """
            (() => {
              const label = Array.from(document.querySelectorAll('p,h2,h3,div'))
                .find((el) => /^(choose\s+)?server(s)?:?$/i.test(el.textContent.trim()));
              if (!label) return false;
              label.scrollIntoView({ behavior: "smooth", block: "start" });
              return true;
            })();
            """.trimIndent(),
            null,
        )
    }

    fun setVisible(visible: Boolean) {
        if (playerVisible && !visible) flushProgress(urgentCloudSync = true)
        playerVisible = visible
        if (!visible && (customView != null || nativeFullscreenRequested)) {
            hideCustomView()
        } else if (!nativePreparation) {
            setSystemBarsVisible(activity, !visible)
        }
        webView?.let {
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (visible) {
                it.onResume()
                it.resumeTimers()
                if (!nativePreparation) it.requestFocus()
            } else {
                it.onPause()
            }
        }
    }

    fun reload() {
        _error.value = null
        nativeStream = null
        val view = webView
        val selection = activeSelection
        if (view != null && selection != null) {
            view.stopLoading()
            loadSelection(view, selection)
        } else if (activeSelection != null) {
            _webViewGeneration.value += 1
        }
    }

    fun openCastPicker() {
        val selection = activeSelection ?: return
        val view = webView ?: return
        val stream = nativeStream
        if (stream == null || !isNativeStreamUrl(stream.optString("url"))) {
            Toast.makeText(activity, "Start the video first. If this server does not offer video casting, choose another server.", Toast.LENGTH_LONG).show()
            return
        }
        val url = stream.getString("url")
        val request = NativePlaybackRequest(
            url = url,
            mimeType = stream.optString("mimeType", "video/mp4"),
            referer = stream.getString("referer"),
            userAgent = view.settings.userAgentString,
            cookie = CookieManager.getInstance().getCookie(url).orEmpty(),
            title = selection.episodeTitle?.let { "${selection.media.title} · $it" } ?: selection.media.title,
            positionMs = (latestPositionSeconds * 1000).toLong(),
            playing = _playing.value,
            subtitlesVtt = nativeSubtitlesVtt(activeSubtitleCuesJson, activeSubtitleDelaySeconds),
            selectionJson = JSONObject().apply {
                put("media", selection.media.toJson()); put("season", selection.seasonNumber)
                put("episode", selection.episodeNumber); put("episodeTitle", selection.episodeTitle)
                put("provider", selection.source.identity.name); put("baseUrl", selection.source.baseUrl)
            }.toString(),
        )
        runCatching { NativePlaybackLauncher.launch(activity, request) }.onSuccess {
            // Hand ownership to the native service once. No background JavaScript play loops.
            postPlaybackControl(false)
        }.onFailure {
            Toast.makeText(activity, "Unable to open video casting", Toast.LENGTH_LONG).show()
        }
    }

    fun requestMoviepireFullscreen(): Boolean {
        val selection = activeSelection ?: return false
        val view = webView ?: return false
        if (!selection.source.identity.usesMoviepire) return false
        if (nativeFullscreenRequested) {
            exitNativeFullscreenMode()
            view.requestLayout()
            return false
        }
        orientationBeforeFullscreen = activity.requestedOrientation
        nativeFullscreenRequested = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setSystemBarsVisible(activity, false)
        view.requestLayout()
        return true
    }

    fun handleBack(): Boolean {
        if (customView != null) {
            hideCustomView()
            return true
        }
        if (nativeFullscreenRequested) {
            exitNativeFullscreenMode()
            return true
        }
        return false
    }

    fun destroy() {
        flushProgress(urgentCloudSync = true)
        hideCustomView()
        playerVisible = false
        if (!nativePreparation) setSystemBarsVisible(activity, true)
        moviepireDocumentStartScriptHandler?.remove()
        moviepireDocumentStartScriptHandler = null
        moviepireProtectedSourceHost = null
        removeMoviepireMessageListener(webView)
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            clearHistory()
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        localPlayerHost = null
        loadedKey = null
        activeSelection = null
        activePlayerReplyProxy = null
        activeSubtitleContentKey = null
        activeSubtitleCuesJson = null
        _switchingMoviepireServer.value = false
    }

    fun onAppBackground() {
        flushProgress(urgentCloudSync = true)
    }

    private fun attachPlayerView(host: FrameLayout, displayContext: android.content.Context) {
        val view = webView ?: return
        if (view.parent !== host) {
            (view.parent as? ViewGroup)?.removeView(view)
            host.removeAllViews()
            (view.context as? MutableContextWrapper)?.baseContext = displayContext
            host.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    val currentPositionSeconds: Double get() = latestPositionSeconds
    val durationSeconds: Double get() = latestDurationSeconds

    fun play() {
        _playing.value = true
        postPlaybackControl(true)
    }

    fun pause() {
        _playing.value = false
        postPlaybackControl(false)
    }

    fun togglePlayPause() {
        val nextPlaying = !_playing.value
        _playing.value = nextPlaying
        postPlaybackControl(nextPlaying)
    }

    fun seekTo(seconds: Double) {
        val view = webView ?: return
        val maxDur = latestDurationSeconds.takeIf { it > 0.0 } ?: Double.MAX_VALUE
        val target = seconds.coerceIn(0.0, maxDur)
        latestPositionSeconds = target
        _currentPositionMs.value = (target * 1000).toLong()
        postSeekToMoviepirePlayer(view, target, activePlayerReplyProxy)
    }

    fun seekTo(positionMs: Long) {
        val seconds = positionMs.coerceAtLeast(0L) / 1000.0
        seekTo(seconds)
    }

    fun seekBy(offsetMs: Long) {
        val current = _currentPositionMs.value
        val dur = _durationMs.value
        val target = if (dur > 0L) {
            (current + offsetMs).coerceIn(0L, dur)
        } else {
            (current + offsetMs).coerceAtLeast(0L)
        }
        seekTo(target)
    }

    fun setPlaybackSpeed(speed: Float) {
        val view = webView ?: return
        val clampedSpeed = speed.coerceIn(0.25f, 3.0f)
        val command = JSONObject()
            .put("type", "aliflix-speed")
            .put("speed", clampedSpeed.toDouble())
            .toString()
        activePlayerReplyProxy?.let { proxy -> runCatching { proxy.postMessage(command) } }
        view.evaluateJavascript(
            """
            (() => {
              const speed = $clampedSpeed;
              const v = document.querySelector("video");
              if (v) v.playbackRate = speed;
              document.querySelectorAll("iframe").forEach(f => {
                try { f.contentWindow?.postMessage({ type: "aliflix-speed", speed }, "*"); } catch (_) {}
              });
            })();
            """.trimIndent(),
            null,
        )
    }

    fun toggleOrientation() {
        val currentOrientation = activity.resources.configuration.orientation
        if (currentOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun resetOrientation() {
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun postPlaybackControl(shouldPlay: Boolean) {
        val view = webView ?: return
        val command = JSONObject()
            .put("type", "aliflix-playback-control")
            .put("shouldPlay", shouldPlay)
            .toString()
        activePlayerReplyProxy?.let { proxy -> runCatching { proxy.postMessage(command) } }
        view.evaluateJavascript(
            """
            (() => {
              const message = $command;
              window.postMessage(message, "*");
              document.querySelectorAll("iframe").forEach(frame => {
                try { frame.contentWindow?.postMessage(message, "*"); } catch (_) {}
              });
              return true;
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun loadSelection(
        view: WebView,
        selection: PlaybackSelection,
    ) {
        if (nativePreparation && nativeEmbedUrl != null) {
            if(selection.source.identity in setOf(MobilePlaybackProvider.FILMO,MobilePlaybackProvider.ANIWORLD,MobilePlaybackProvider.SERIENSTREAM)) {
                // The site's external-player action may forbid framing. Load
                // its normal document directly in the temporary muted resolver.
                view.loadUrl(nativeEmbedUrl)
                return
            }
            val safeEmbed=android.text.TextUtils.htmlEncode(nativeEmbedUrl)
            view.loadDataWithBaseURL(selection.entryUrl,
                "<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='margin:0;background:black'><iframe src='$safeEmbed' allow='autoplay; fullscreen' style='border:0;width:100vw;height:100vh'></iframe></body></html>",
                "text/html", "UTF-8", null)
            return
        }
        _loading.value = true
        _error.value = null
        pendingServerKey = null
        _switchingMoviepireServer.value = false
        view.alpha = 1f
        val entryUrl = mobileMoviepireEpisodeBootstrapUrl(selection) ?: selection.entryUrl
        if (entryUrl == null) {
            _loading.value = false
            _error.value = "This provider could not create a playback link."
        } else {
            view.loadUrl(entryUrl)
        }
    }

    private fun configureMobileMoviepireDocumentStartProtection(
        view: WebView,
        selection: PlaybackSelection,
    ) {

        if (!selection.source.identity.usesMoviepire) {
            val sourceHost = selection.source.cleanDomain.lowercase().removePrefix("www.")
            if (moviepireDocumentStartScriptHandler != null && moviepireProtectedSourceHost == sourceHost && !moviepireProtectedNativeMode) return
            view.setDownloadListener(null)
            moviepireDocumentStartScriptHandler?.remove()
            moviepireDocumentStartScriptHandler = null
            moviepireProtectedSourceHost = sourceHost
            moviepireProtectedNativeMode = false
            installMoviepireMessageListener(view, sourceHost)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                val shield = if (selection.source.identity == MobilePlaybackProvider.FLIXER) mobileMoviepireAdShieldScript() else ""
                // The native resolver already gives the HLS master time to arrive after a variant.
                val discovery = nativeStreamDiscoveryScript(
                    reportVariantsImmediately = nativePreparation && selection.source.identity.isIndianCatalogue,
                )
                moviepireDocumentStartScriptHandler = WebViewCompat.addDocumentStartJavaScript(
                    view, shield + discovery + "\n" + mobileMoviepireProgressBridgeScript() + preparationScript(),
                    nativeOriginRules(sourceHost),
                )
            }
            return
        }
        view.setDownloadListener { _, _, _, _, _ ->
            // Embedded playback never initiates downloads. Moviepire ad scripts use fake
            // APK/security downloads as a conversion path; consume them in-app.
        }

        val sourceHost = selection.source.cleanDomain.lowercase().removePrefix("www.")
        val nativeMode = selection.source.identity.usesMoviepire
        if (
            moviepireDocumentStartScriptHandler != null &&
            moviepireProtectedSourceHost == sourceHost &&
            moviepireProtectedNativeMode == nativeMode
        ) {
            return
        }
        moviepireDocumentStartScriptHandler?.remove()
        moviepireDocumentStartScriptHandler = null
        moviepireProtectedSourceHost = sourceHost
        moviepireProtectedNativeMode = nativeMode
        if (nativeMode) installMoviepireMessageListener(view, sourceHost) else removeMoviepireMessageListener(view)
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return

        moviepireDocumentStartScriptHandler = runCatching {
            WebViewCompat.addDocumentStartJavaScript(
                view,
                mobileMoviepireAdShieldScript() + if (nativeMode) {
                    "\n" + nativeStreamDiscoveryScript() + "\n" + mobileMoviepireProgressBridgeScript() + preparationScript()
                } else {
                    ""
                },
                mobileMoviepireShieldOriginRules(sourceHost),
            )
        }.getOrNull()
    }

    private fun installMoviepireMessageListener(view: WebView, sourceHost: String) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        removeMoviepireMessageListener(view)
        runCatching {
            WebViewCompat.addWebMessageListener(
                view,
                PLAYBACK_BRIDGE_NAME,
                nativeOriginRules(sourceHost),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        val selection = activeSelection ?: return
                        if (!isApprovedMoviepireProgressOrigin(sourceOrigin, selection)) return
                        handlePlaybackMessage(view, selection, message.data, replyProxy)
                    }
                },
            )
            moviepireMessageListenerInstalled = true
        }
    }

    private fun removeMoviepireMessageListener(view: WebView?) {
        if (!moviepireMessageListenerInstalled || view == null) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(view, PLAYBACK_BRIDGE_NAME) }
        }
        moviepireMessageListenerInstalled = false
    }

    private fun isApprovedMoviepireProgressOrigin(
        origin: Uri,
        selection: PlaybackSelection,
    ): Boolean {
        if (!origin.scheme.equals("https", ignoreCase = true)) return false
        val frameOrigin = trustedNativeEmbedOrigin(origin.toString(), preparing = true)
        if (frameOrigin != null && (frameOrigin == resolvedEmbedOrigin || frameOrigin in resolvedEmbedChildOrigins)) return true
        val host = origin.host?.lowercase()?.removePrefix("www.") ?: return false
        val approved = PlaybackNavigationPolicy.moviepirePlayerDocumentHosts + PlaybackNavigationPolicy.defaultApprovedPlaybackHosts +
            selection.source.cleanDomain.lowercase().removePrefix("www.")
        return approved.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    private fun handlePlaybackMessage(
        view: WebView,
        selection: PlaybackSelection,
        raw: String?,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!isActiveSelection(view, selection) || raw.isNullOrBlank()) return
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val event = payload.optString("event")
        val position = payload.optDouble("positionSeconds", Double.NaN)
        val duration = payload.optDouble("durationSeconds", Double.NaN)
        val documentHidden = payload.optBoolean("documentHidden", false)
        val stream = payload.optJSONObject("nativeStream")?.takeIf { isNativeStreamUrl(it.optString("url")) }
        if (
            !position.isFinite() || position < 0.0 ||
            !duration.isFinite() || duration < MIN_TRACKABLE_VIDEO_DURATION_SECONDS ||
            duration > MAX_REASONABLE_VIDEO_DURATION_SECONDS ||
            position > duration + 5.0
        ) {
            // A stream-only report carries no progress because the embedded player never started.
            // The manifest alone is enough to hand playback to the native player, so keep it and
            // wait rather than discarding a stream that is already playable.
            if (stream != null) {
                rememberNativeStream(stream)
            }
            return
        }
        rememberNativeStream(stream)
        if (nativePreparation) return // Resolution must never overwrite the saved native resume point.
        latestDurationSeconds = duration
        _durationMs.value = (duration * 1000).toLong()
        _videoFound.value = true
        val buffered = payload.optDouble("bufferedSeconds", Double.NaN)
        if (buffered.isFinite() && buffered >= 0.0) {
            _bufferedPositionMs.value = (buffered * 1000).toLong()
        }
        when (event) {
            "play", "playing" -> _playing.value = true
            "pause", "ended" -> _playing.value = false
        }
        activePlayerReplyProxy = replyProxy
        if (event in setOf("loadedmetadata", "play", "playing")) {
            // A different Moviepire server can replace the entire player frame. Reapply the
            // text track to the exact frame that has just reported its real video clock.
            postActiveSubtitles(replyProxy)
            suppressProviderVideoControls(view)
        }
        pendingSeekSeconds?.let { seek ->
            val switchingServer = pendingServerKey != null
            val switchReady = !switchingServer ||
                SystemClock.elapsedRealtime() - serverSwitchStartedAt >= SERVER_SWITCH_SEEK_DELAY_MILLIS
            if (
                switchReady &&
                event in setOf("loadedmetadata", "play", "timeupdate") &&
                playbackSeekEligible(seek, duration, switchingServer) &&
                position < seek - SEEK_RESTORE_TOLERANCE_SECONDS
            ) {
                postSeekToMoviepirePlayer(view, seek, replyProxy)
            }
            if (position >= seek - SEEK_RESTORE_TOLERANCE_SECONDS) {
                pendingSeekSeconds = null
                pendingServerKey = null
                _switchingMoviepireServer.value = false
            }
        }
        // A newly-created server frame normally reports 0 before it can accept a seek. Never let
        // that bootstrap position overwrite the durable resume point. The exact frame that emitted
        // this event receives the seek above and its later progress event confirms restoration.
        val stillRestoring = playbackRestoreStillPending(
            reportedPositionSeconds = position,
            pendingSeekSeconds = pendingSeekSeconds,
        )
        if (stillRestoring) return

        latestPositionSeconds = position.coerceAtMost(duration)
        _currentPositionMs.value = (latestPositionSeconds * 1000).toLong()
        latestPlaybackEnded = event == "ended" || (latestPlaybackEnded && event == "pause")
        // The browser is paused after handoff; only the native owner may persist its clock.
        if (NativePlaybackLauncher.ownsStream(nativeStream?.optString("url"))) return
        val now = SystemClock.elapsedRealtime()
        val urgent = event in setOf("pause", "ended", "seeked")
        if (urgent || now - lastLocalProgressWriteAt >= LOCAL_PROGRESS_INTERVAL_MILLIS) {
            playbackProgressStore.savePlayerProgress(
                selection = selection,
                positionSeconds = latestPositionSeconds,
                durationSeconds = latestDurationSeconds,
                urgentCloudSync = urgent,
                ended = latestPlaybackEnded,
            )
            lastLocalProgressWriteAt = now
        }
    }

    private fun suppressProviderVideoControls(view: WebView) {
        view.evaluateJavascript(
            """
            (() => {
              try {
                const styleId = "aliflix-hide-provider-controls";
                if (!document.getElementById(styleId)) {
                  const s = document.createElement("style");
                  s.id = styleId;
                  s.textContent = `
                    video::-webkit-media-controls { display: none !important; }
                    video::-webkit-media-controls-enclosure { display: none !important; }
                    .plyr__controls, .vjs-control-bar, .jw-controls { display: none !important; }
                  `;
                  document.head?.appendChild(s);
                }
                document.querySelectorAll("video").forEach(v => {
                  v.controls = false;
                  v.removeAttribute("controls");
                });
              } catch (_) {}
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun flushProgress(urgentCloudSync: Boolean) {
        if (nativePreparation) return
        val selection = activeSelection ?: return
        if (NativePlaybackLauncher.ownsStream(nativeStream?.optString("url"))) return
        if (latestDurationSeconds <= 0.0) return
        playbackProgressStore.savePlayerProgress(
            selection = selection,
            positionSeconds = latestPositionSeconds,
            durationSeconds = latestDurationSeconds,
            urgentCloudSync = urgentCloudSync,
            ended = latestPlaybackEnded,
        )
        lastLocalProgressWriteAt = SystemClock.elapsedRealtime()
    }

    private fun postSeekToMoviepirePlayer(
        view: WebView,
        seconds: Double,
        replyProxy: JavaScriptReplyProxy? = null,
    ) {
        if (!seconds.isFinite() || seconds < 0.0) return
        val command = JSONObject()
            .put("type", "aliflix-seek")
            .put("seconds", seconds)
            .toString()
        // WebKit replies to the precise origin/frame that reported its video state. This avoids
        // relying on how deeply a particular Moviepire server nests its actual player iframe.
        replyProxy?.let { proxy -> runCatching { proxy.postMessage(command) } }
        view.evaluateJavascript(
            """
            (() => {
              const message = { type: "aliflix-seek", seconds: $seconds };
              window.postMessage(message, "*");
              document.querySelectorAll("iframe").forEach(frame => {
                try { frame.contentWindow?.postMessage(message, "*"); } catch (_) {}
              });
              return true;
            })();
            """.trimIndent(),
            null,
        )
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
        }
        return object : WebView(MutableContextWrapper(activity)) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {

                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            if (nativePreparation && WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) {
                WebViewCompat.setAudioMuted(this, true)
            }
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            isFocusable = true
            isFocusableInTouchMode = true
            isLongClickable = false
            setOnLongClickListener { true }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                allowFileAccess = false
                allowContentAccess = false
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
                cacheMode = WebSettings.LOAD_DEFAULT
                useWideViewPort = true
                loadWithOverviewMode = true
                offscreenPreRaster = true
                userAgentString = browserCompatibleUserAgent(userAgentString.orEmpty())
            }
            setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    _loading.value = true
                    _error.value = null
                    activePlayerReplyProxy = null
                    view?.alpha = 1f
                    val selection = activeSelection
                    if (selection?.source?.identity?.usesMoviepire == true) {
                        _moviepireServers.value = emptyList()
                    }
                    if (
                        view != null &&
                        selection?.source?.identity?.usesMoviepire == true
                    ) {
                        // Document-start injection is the primary path. These short retries are a
                        // deterministic fallback for devices whose WebView provider is too old to
                        // support DOCUMENT_START_SCRIPT.
                        view.post {
                            if (isActiveSelection(view, selection)) {
                                installMobileMoviepireAdShield(view, selection)
                            }
                        }
                        view.postDelayed(
                            {
                                if (isActiveSelection(view, selection)) {
                                    installMobileMoviepireAdShield(view, selection)
                                }
                            },
                            180L,
                        )
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    CookieManager.getInstance().flush()
                    val selection = activeSelection
                    if (view != null && url != null) {
                        if (
                            selection != null &&
                            nativeEmbedUrl == null && shouldResolveMobileMoviepireEpisode(selection)
                        ) {
                            view.alpha = 0f
                            resolveMobileMoviepireEpisode(view, selection, attempt = 0)
                            return
                        }
                        if (
                            selection != null &&
                            nativeEmbedUrl == null && selection.source.identity == PlaybackProviderId.RAMOFLIX &&
                            isRamoflixSearchUrl(url, selection)
                        ) {
                            resolveRamoflixTitle(view, selection, attempt = 0)
                            return
                        }
                        _error.value = null
                        view.alpha = 1f
                        _loading.value = false
                        if (playerVisible) {
                            view.requestFocus()
                        }

                        if (selection != null) {
                            if (
                                selection.source.identity.usesMoviepire
                            ) {
                                installMobileMoviepireAdShield(view, selection)
                            }
                            view.postDelayed(
                                {
                                    if (isActiveSelection(view, selection)) {
                                        alignProviderContent(view, selection)
                                    }
                                },
                                350L,
                            )
                            view.postDelayed(
                                {
                                    if (isActiveSelection(view, selection)) {
                                        alignProviderContent(view, selection)
                                    }
                                },
                                1_400L,
                            )
                            if (selection.source.identity != PlaybackProviderId.RAMOFLIX) {
                                view.postDelayed(
                                    {
                                        if (isActiveSelection(view, selection)) {
                                            alignProviderContent(view, selection)
                                        }
                                    },
                                    3_000L,
                                )
                            }
                        }
                        return
                    }
                    view?.alpha = 1f
                    _loading.value = false
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): Boolean {
                    if (request == null) return true
                    val uri = request.url ?: return true
                    if (
                        isMobileMoviepireSelection() &&
                        !request.isForMainFrame &&
                        PlaybackNavigationPolicy.isBlockedMoviepireSubframeNavigation(
                            uri.toString(),
                        )
                    ) {
                        return true
                    }
                    if (!request.isForMainFrame) return false
                    val customHosts = customHostsForActiveSelection()
                    if (
                        PlaybackNavigationPolicy.isAllowedTopLevel(
                            url = uri.toString(),
                            customHosts = customHosts,
                        )
                    ) {
                        return false
                    }
                    view?.alpha = 1f
                    _loading.value = false
                    if (!nativePreparation && !isMobileMoviepireSelection()) {
                        Toast.makeText(
                            activity,
                            "External navigation blocked",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return true
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (
                        (isMobileMoviepireSelection() || nativePreparation) &&
                        request != null &&
                        PlaybackNavigationPolicy.isBlockedMoviepireResource(
                            request.url.toString(),
                        )
                    ) {
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        _loading.value = false
                        _switchingMoviepireServer.value = false
                        view?.alpha = 1f
                        _error.value = error?.description?.toString()
                            ?: "The player could not be loaded."
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    _loading.value = false
                    _switchingMoviepireServer.value = false
                    pendingServerKey = null
                    _error.value = "The web player stopped unexpectedly. Tap Retry."
                    if (webView === view) {
                        moviepireDocumentStartScriptHandler?.remove()
                        moviepireDocumentStartScriptHandler = null
                        moviepireProtectedSourceHost = null
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.destroy()
                        webView = null
                        loadedKey = null
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onCreateWindow(
                    view: WebView?,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: android.os.Message?,
                ): Boolean {
                    if (!nativePreparation && !isMobileMoviepireSelection()) {
                        Toast.makeText(
                            activity,
                            "Pop-up blocked",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return false
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (nativePreparation || isMobileMoviepireSelection()) {
                        request?.deny()
                    } else {
                        super.onPermissionRequest(request)
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?,
                ) {
                    if (nativePreparation || isMobileMoviepireSelection()) {
                        callback?.invoke(origin, false, false)
                    } else {
                        super.onGeolocationPermissionsShowPrompt(origin, callback)
                    }
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    if (!nativePreparation && !isMobileMoviepireSelection()) return false
                    filePathCallback?.onReceiveValue(null)
                    return true
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (!nativePreparation && !isMobileMoviepireSelection()) {
                        return super.onJsAlert(view, url, message, result)
                    }
                    result?.cancel()
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (!nativePreparation && !isMobileMoviepireSelection()) {
                        return super.onJsConfirm(view, url, message, result)
                    }
                    result?.cancel()
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?,
                ): Boolean {
                    if (!nativePreparation && !isMobileMoviepireSelection()) {
                        return super.onJsPrompt(view, url, message, defaultValue, result)
                    }
                    result?.cancel()
                    return true
                }

                override fun onShowCustomView(
                    view: View?,
                    callback: CustomViewCallback?,
                ) {
                    if (view == null) return
                    showCustomView(view, callback)
                }

                override fun onHideCustomView() {
                    hideCustomView()
                }
            }

        }
    }

    private fun isRamoflixSearchUrl(
        url: String,
        selection: PlaybackSelection,
    ): Boolean {
        val uri = url.toUri()
        val cleanHost = uri.host?.removePrefix("www.")
        return selection.source.identity == PlaybackProviderId.RAMOFLIX &&
            cleanHost == selection.source.cleanDomain &&
            uri.getQueryParameter("s") != null
    }

    private fun customHostsForActiveSelection(): Set<String> =
        activeSelection?.source?.approvedTopLevelHosts.orEmpty()+setOfNotNull(resolvedEmbedOrigin?.let { URI(it).host })

    private fun isMobileMoviepireSelection(): Boolean =
        activeSelection?.source?.identity?.usesMoviepire == true

    private fun isMoviepireWrapper(view: WebView): Boolean {
        val selection = activeSelection ?: return false
        if (!selection.source.identity.usesMoviepire) return false
        val currentHost = runCatching {
            URI(view.url.orEmpty()).host?.removePrefix("www.")
        }.getOrNull()
        return currentHost.equals(selection.source.cleanDomain, ignoreCase = true)
    }

    private fun resolveRamoflixTitle(
        view: WebView,
        selection: PlaybackSelection,
        attempt: Int,
    ) {
        val quotedTitle = JSONObject.quote(selection.media.title)
        val currentUri = view.url.orEmpty().toUri()
        val host = currentUri.host?.removePrefix("www.").orEmpty()
        val quotedHost = JSONObject.quote(host)
        view.evaluateJavascript(
            """
            (() => {
              const desired = $quotedTitle;
              const host = $quotedHost;
              const normalize = (value) => value
                .normalize("NFD")
                .replace(/[\u0300-\u036f]/g, "")
                .toLowerCase()
                .replace(/[^a-z0-9]+/g, " ")
                .trim();
              const wanted = normalize(desired);
              const links = Array.from(document.querySelectorAll('a[href]'))
                .filter((link) => {
                  const href = link.href || "";
                  let linkHost = "";
                  try {
                    linkHost = new URL(href).hostname.replace(/^www\./, "");
                  } catch (_) {
                    return false;
                  }
                  return (
                    host === "" ||
                    linkHost === host ||
                    linkHost.endsWith("." + host)
                  ) &&
                    !href.includes("/category/") &&
                    !href.includes("/genre/") &&
                    !href.includes("/page/") &&
                    !href.includes("/dmca") &&
                    !href.includes("/settings") &&
                    !href.includes("/privacy") &&
                    !href.includes("/login") &&
                    !href.includes("/register");
                });
              const exact = links.find((link) => {
                const pathName = new URL(link.href).pathname
                  .split("/")
                  .filter(Boolean)
                  .pop() || "";
                return normalize(link.textContent) === wanted ||
                  normalize(pathName.replace(/[-_]+/g, " ")) === wanted;
              });
              const close = links.find((link) => {
                const value = normalize(link.textContent);
                const pathName = normalize(new URL(link.href).pathname.replace(/[-_]+/g, " "));
                return (value && (value.includes(wanted) || wanted.includes(value))) ||
                  (pathName && (pathName.includes(wanted) || wanted.includes(pathName)));
              });
              return (exact || close || null)?.href || "";
            })();
            """.trimIndent(),
        ) { result ->
            if (
                !isActiveSelection(view, selection) ||
                !isRamoflixSearchUrl(view.url.orEmpty(), selection)
            ) {
                return@evaluateJavascript
            }
            val target = runCatching {
                JSONTokener(result).nextValue() as? String
            }.getOrNull().orEmpty()
            val allowedTarget = PlaybackNavigationPolicy.isAllowedTopLevel(
                url = target,
                customHosts = selection.source.approvedTopLevelHosts,
            )
            if (allowedTarget) {
                _error.value = null
                view.loadUrl(target)
            } else if (attempt < 6) {
                view.postDelayed(
                    {
                        if (isActiveSelection(view, selection)) {
                            resolveRamoflixTitle(view, selection, attempt + 1)
                        }
                    },
                    700L + (attempt * 250L),
                )
            } else {
                view.alpha = 1f
                _loading.value = false
                _error.value = "This title could not be found on Ramoflix."
            }
        }
    }

    private fun alignProviderContent(
        view: WebView,
        selection: PlaybackSelection,
    ) {
        if (nativePreparation && nativeEmbedUrl != null) return
        when (selection.source.identity) {
            PlaybackProviderId.CINEJOY -> Unit // Exact TMDB movie/episode route, native HLS discovery.
            MobilePlaybackProvider.FILMO, MobilePlaybackProvider.ANIWORLD, MobilePlaybackProvider.SERIENSTREAM, MobilePlaybackProvider.FLIXER, MobilePlaybackProvider.MOVY, MobilePlaybackProvider.SEVEN_MOVIES,
            MobilePlaybackProvider.HIMOVIES, MobilePlaybackProvider.CINEGO -> Unit // Native mobile catalogues and exact TMDB routes.
            PlaybackProviderId.RAMOFLIX -> alignRamoflixContent(view, selection)
            PlaybackProviderId.MOVIEPIRE -> {
                installMobileMoviepireAdShield(view, selection)
                discoverMoviepireServers(view, selection)
            }
            PlaybackProviderId.DORABY -> { /* Doraby webpage loads directly */ }
            PlaybackProviderId.MIRURO -> Unit // Resolved by the mobile native catalogue.
            PlaybackProviderId.ANIKURO -> Unit // Resolved by the mobile native catalogue.
        }
    }

    private fun resolveMobileMoviepireEpisode(
        view: WebView,
        selection: PlaybackSelection,
        attempt: Int,
    ) {
        if (!isActiveSelection(view, selection) || !shouldResolveMobileMoviepireEpisode(selection)) {
            return
        }
        val season = selection.seasonNumber ?: 1
        val episode = selection.episodeNumber ?: 1
        view.evaluateJavascript(
            moviepireExactEpisodeResolverScript(
                tmdbId = selection.media.id,
                seasonNumber = season,
                episodeNumber = episode,
            ),
        ) { result ->
            if (!isActiveSelection(view, selection)) return@evaluateJavascript
            val status = runCatching {
                JSONTokener(result).nextValue() as? String
            }.getOrNull().orEmpty()
            when (status) {
                "ready" -> {
                    moviepireEpisodeBootstrapReloads = 0
                    _error.value = null
                    _loading.value = false
                    view.alpha = 1f
                    installMobileMoviepireAdShield(view, selection)
                    alignProviderContent(view, selection)
                }
                "wrong-route" -> {
                    if (moviepireEpisodeBootstrapReloads < MAX_MOVIEPIRE_EPISODE_BOOTSTRAP_RELOADS) {
                        moviepireEpisodeBootstrapReloads += 1
                        val bootstrap = mobileMoviepireEpisodeBootstrapUrl(selection)
                        if (bootstrap != null) {
                            view.loadUrl(bootstrap)
                            return@evaluateJavascript
                        }
                    }
                    scheduleMoviepireEpisodeResolution(view, selection, attempt)
                }
                else -> scheduleMoviepireEpisodeResolution(view, selection, attempt)
            }
        }
    }

    private fun scheduleMoviepireEpisodeResolution(
        view: WebView,
        selection: PlaybackSelection,
        attempt: Int,
    ) {
        if (attempt >= MAX_MOVIEPIRE_EPISODE_RESOLUTION_ATTEMPTS) {
            _loading.value = false
            view.alpha = 1f
            _error.value = "Moviepire did not expose the selected episode. Please retry playback."
            return
        }
        view.postDelayed(
            {
                if (isActiveSelection(view, selection)) {
                    resolveMobileMoviepireEpisode(view, selection, attempt + 1)
                }
            },
            MOVIEPIRE_EPISODE_RESOLUTION_RETRY_MILLIS,
        )
    }

    private fun discoverMoviepireServers(
        view: WebView,
        selection: PlaybackSelection,
    ) {
        if (
            !selection.source.identity.usesMoviepire ||
            !isActiveSelection(view, selection) ||
            !isMoviepireWrapper(view)
        ) {
            return
        }
        view.evaluateJavascript(moviepireServerDiscoveryScript()) { result ->
            if (!isActiveSelection(view, selection)) return@evaluateJavascript
            val payload = runCatching {
                JSONTokener(result).nextValue() as? String
            }.getOrNull().orEmpty()
            val servers = parseMoviepireServerDiscovery(payload)
            if (servers.isNotEmpty()) {
                _moviepireServers.value = servers
                val target = pendingServerKey
                if (target != null && servers.any { it.key == target && it.selected }) {
                    val seek = pendingSeekSeconds
                    if (seek == null) {
                        pendingServerKey = null
                        _switchingMoviepireServer.value = false
                    } else {
                        listOf(450L, 1_200L, 2_800L).forEach { delay ->
                            view.postDelayed(
                                {
                                    if (isActiveSelection(view, selection) && pendingSeekSeconds != null) {
                                        postSeekToMoviepirePlayer(view, seek)
                                    }
                                },
                                delay,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun installMobileMoviepireAdShield(
        view: WebView,
        selection: PlaybackSelection,
    ) {
        if (!selection.source.identity.usesMoviepire) return
        if (!isActiveSelection(view, selection)) return
        view.evaluateJavascript(
            mobileMoviepireAdShieldScript() + kotlin.run {
                "\n" + nativeStreamDiscoveryScript() + "\n" + mobileMoviepireProgressBridgeScript()
            },
            null,
        )
    }

    private fun alignVidloveContent(
        view: WebView,
        selection: PlaybackSelection,
    ) {
        if (!isActiveSelection(view, selection)) return
        view.evaluateJavascript(
            """
            (() => {
              if (!document.getElementById("aliflix-vidlove-style")) {
                const style = document.createElement("style");
                style.id = "aliflix-vidlove-style";
                style.textContent = `
                  html, body {
                    width: 100% !important;
                    height: 100% !important;
                    margin: 0 !important;
                    overflow: hidden !important;
                    background: #000 !important;
                  }
                  video, .plyr, .plyr__video-wrapper, #player, [class*="player"] {
                    max-width: 100vw !important;
                    max-height: 100vh !important;
                  }
                  iframe {
                    position: fixed !important;
                    inset: 0 !important;
                    width: 100vw !important;
                    height: 100vh !important;
                    border: 0 !important;
                  }
                  a[aria-label="Back to home"] {
                    display: none !important;
                  }
                  button:focus, [role="button"]:focus, video:focus, select:focus {
                    outline: 4px solid #fff !important;
                    outline-offset: 2px !important;
                  }
                `;
                document.head.appendChild(style);
              }
              const video = document.querySelector("video");
              if (video) {
                video.setAttribute("tabindex", "0");
                video.setAttribute("playsinline", "");
              }
              const frame = document.querySelector('iframe[src*="vidlove"]') ||
                document.querySelector("iframe");
              if (frame) frame.setAttribute("tabindex", "0");
              const focusable = video ||
                frame ||
                document.querySelector('button[aria-label*="play" i], button[title*="play" i]') ||
                document.querySelector("button, [role='button']");
              focusable?.focus({ preventScroll: true });
              return Boolean(focusable);
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun alignRamoflixContent(
        view: WebView,
        selection: PlaybackSelection,
    ) {
        if (!isActiveSelection(view, selection)) return
        val isTv = selection.media.type == MediaType.TV
        val season = selection.seasonNumber ?: 1
        val episode = selection.episodeNumber ?: 1
        view.evaluateJavascript(
            """
            (() => {
              if (!document.getElementById("aliflix-player-style")) {
                const style = document.createElement("style");
                style.id = "aliflix-player-style";
                style.textContent = `
                  header, footer, .site-header, #masthead, nav[data-app-nav="true"], footer.footer-fade-in { display: none !important; }
                  html, body { background: #06070a !important; }
                  body { padding-top: 0px !important; }
                  main {
                    background: #06070a !important;
                    margin-top: 0 !important;
                  }
                  #the_frame, #player_iframe {
                    position: fixed !important;
                    inset: 0 !important;
                    width: 100vw !important;
                    height: 56.25vw !important;
                    min-height: 56.25vw !important;
                    margin: 0 !important;
                  }
                  a:focus, button:focus, [role="button"]:focus, video:focus,
                  input:focus, select:focus, #play:focus, .btn-watchnow:focus,
                  #pl_but:focus, #pl_but_background:focus, li:focus {
                    outline: 4px solid #ffffff !important;
                    outline-offset: 4px !important;
                  }
                `;
                document.head.appendChild(style);
              }
              const isTv = $isTv;
              if (!isTv) {
                window.scrollTo({ top: 0, behavior: "smooth" });
                return;
              }
              const desiredSeason = "Season $season";
              const desiredEpisode = "Episode $episode";
              const isVisible = (el) => {
                const style = window.getComputedStyle(el);
                const rect = el.getBoundingClientRect();
                return style.display !== "none" && style.visibility !== "hidden" &&
                  rect.width > 0 && rect.height > 0;
              };
              const links = Array.from(document.querySelectorAll('a'));
              const seasonLink = links.find(
                (el) => isVisible(el) && el.textContent.trim() === desiredSeason
              );
              const chooseEpisode = () => {
                const episodeLink = Array.from(document.querySelectorAll('a')).find((el) => {
                  const text = el.textContent.trim();
                  return isVisible(el) && (
                    text === desiredEpisode ||
                    text.startsWith(desiredEpisode + " ")
                  );
                });
                episodeLink?.click();
                window.setTimeout(() => window.scrollTo({ top: 0, behavior: "smooth" }), 180);
              };
              seasonLink?.click();
              window.setTimeout(chooseEpisode, 420);
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun isActiveSelection(
        view: WebView,
        selection: PlaybackSelection,
    ): Boolean = webView === view && activeSelection?.key == selection.key

    private fun showCustomView(
        view: View,
        callback: WebChromeClient.CustomViewCallback?,
    ) {
        if (nativePreparation || customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        orientationBeforeFullscreen = activity.requestedOrientation
        nativeFullscreenRequested = true
        val decor = activity.window.decorView as FrameLayout
        val container = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        customViewContainer = container
        decor.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setSystemBarsVisible(activity, false)
    }

    private fun hideCustomView() {
        if (nativePreparation && customView == null && !nativeFullscreenRequested) return
        customView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            customViewContainer?.let { container ->
                (container.parent as? ViewGroup)?.removeView(container)
            }
            customViewCallback?.onCustomViewHidden()
        }
        customView = null
        customViewContainer = null
        customViewCallback = null
        exitNativeFullscreenMode()
    }

    private fun exitNativeFullscreenMode() {
        nativeFullscreenRequested = false
        activity.requestedOrientation = orientationBeforeFullscreen
        orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        setSystemBarsVisible(activity, !playerVisible)
    }

    private fun setSystemBarsVisible(activity: Activity, visible: Boolean) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private companion object {
        const val PLAYBACK_BRIDGE_NAME = "AliflixPlaybackProgress"
        const val LOCAL_PROGRESS_INTERVAL_MILLIS = 7_000L
        const val MIN_TRACKABLE_VIDEO_DURATION_SECONDS = 60.0
        const val MAX_REASONABLE_VIDEO_DURATION_SECONDS = 7 * 24 * 60 * 60.0
        const val SERVER_SWITCH_SEEK_DELAY_MILLIS = 350L
        const val SERVER_SWITCH_UI_TIMEOUT_MILLIS = 12_000L
        const val SEEK_RESTORE_TOLERANCE_SECONDS = 0.05
        const val MOVIEPIRE_EPISODE_RESOLUTION_RETRY_MILLIS = 450L
        const val MAX_MOVIEPIRE_EPISODE_RESOLUTION_ATTEMPTS = 32
        const val MAX_MOVIEPIRE_EPISODE_BOOTSTRAP_RELOADS = 1
        const val MAX_SUBTITLE_CUES = 6_000
        const val MAX_SUBTITLE_CUE_TEXT_LENGTH = 500
        const val SUBTITLE_DELAY_LIMIT_SECONDS = 10.0
        const val MIN_SUBTITLE_FONT_PERCENT = 70
        const val MAX_SUBTITLE_FONT_PERCENT = 180
    }
}

internal fun browserCompatibleUserAgent(userAgent: String): String =
    userAgent
        .replace(Regex(""";\s*wv(?=\))""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+Version/4\.0""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s+Aliflix(?:Android|TV)/[^\s]+""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

internal fun playbackSeekEligible(
    positionSeconds: Double,
    durationSeconds: Double,
    switchingServer: Boolean,
): Boolean {
    if (!positionSeconds.isFinite() || !durationSeconds.isFinite()) return false
    return positionSeconds > 0.0 && positionSeconds <= durationSeconds
}

internal fun playbackRestoreStillPending(
    reportedPositionSeconds: Double,
    pendingSeekSeconds: Double?,
): Boolean = pendingSeekSeconds != null &&
    reportedPositionSeconds < pendingSeekSeconds - 0.05
