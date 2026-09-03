package com.aliflix.app.player

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.InputDevice
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
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
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
) {
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
    private var lastLocalProgressWriteAt = 0L
    private var pendingSeekSeconds: Double? = null
    private var pendingServerKey: String? = null
    private var serverSwitchStartedAt = 0L
    private var moviepireEpisodeBootstrapReloads = 0
    private var playerVisible = false
    private var nativeFullscreenRequested = false

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

    fun viewFor(selection: PlaybackSelection): WebView {
        val view = webView ?: createWebView().also { webView = it }
        if (activeSelection?.key != selection.key) flushProgress(urgentCloudSync = true)
        activeSelection = selection
        configureMobileMoviepireDocumentStartProtection(view, selection)
        val defaultKey = selection.key
        if (loadedKey != defaultKey) {
            loadedKey = defaultKey
            latestPositionSeconds = 0.0
            latestDurationSeconds = 0.0
            lastLocalProgressWriteAt = 0L
            _moviepireServers.value = emptyList()
            _switchingMoviepireServer.value = false
            _playing.value = false
            moviepireEpisodeBootstrapReloads = 0
            _error.value = null
            view.stopLoading()
            loadSelection(view, selection)
        }
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    fun savedProgressFor(selection: PlaybackSelection): PlaybackProgress? =
        playbackProgressStore.progressFor(selection)

    fun prepareSelection(selection: PlaybackSelection) {
        if (activeSelection?.key == selection.key) return
        flushProgress(urgentCloudSync = true)
        activeSelection = selection
        pendingSeekSeconds = null
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

    fun selectMoviepireServer(option: MoviepireServerOption) {
        val selection = activeSelection ?: return
        val view = webView ?: return
        if (BuildConfig.IS_TV || !selection.source.provider.usesMoviepire) return
        if (_moviepireServers.value.none { it.key == option.key }) return
        if (option.selected || _moviepireServers.value.any { it.key == option.key && it.selected }) {
            return
        }
        val restorePosition = latestPositionSeconds.takeIf { it > 0.0 }
            ?: playbackProgressStore.progressFor(selection)?.positionSeconds?.takeIf { it > 0.0 }
        pendingSeekSeconds = restorePosition
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
        } else {
            setSystemBarsVisible(activity, !visible)
        }
        webView?.let {
            it.visibility = if (visible) View.VISIBLE else View.INVISIBLE
            if (visible) {
                it.onResume()
                it.resumeTimers()
                it.requestFocus()
            } else {
                it.onPause()
            }
        }
    }

    fun reload() {
        _error.value = null
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
        val opened = runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_CAST_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.isSuccess
        if (!opened) {
            Toast.makeText(
                activity,
                "Cast settings are unavailable on this device",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun requestMoviepireFullscreen() {
        val selection = activeSelection ?: return
        val view = webView ?: return
        if (BuildConfig.IS_TV || !selection.source.provider.usesMoviepire) return
        nativeFullscreenRequested = true
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        setSystemBarsVisible(activity, false)
        view.evaluateJavascript(
            """
            (() => {
              const frames = Array.from(document.querySelectorAll("iframe"))
                .filter(frame => {
                  const rect = frame.getBoundingClientRect();
                  return rect.width > 0 && rect.height > 0;
                })
                .sort((left, right) => {
                  const a = left.getBoundingClientRect();
                  const b = right.getBoundingClientRect();
                  return (b.width * b.height) - (a.width * a.height);
                });
              const frame = frames[0];
              if (frame && !window.__aliflixFullscreenLayout) {
                window.__aliflixFullscreenLayout = {
                  frame,
                  frameStyle: frame.style.cssText,
                  bodyOverflow: document.body?.style.overflow || "",
                  rootOverflow: document.documentElement.style.overflow || ""
                };
                frame.style.setProperty("position", "fixed", "important");
                frame.style.setProperty("inset", "0", "important");
                frame.style.setProperty("width", "100vw", "important");
                frame.style.setProperty("height", "100vh", "important");
                frame.style.setProperty("max-width", "none", "important");
                frame.style.setProperty("max-height", "none", "important");
                frame.style.setProperty("z-index", "2147483647", "important");
                if (document.body) document.body.style.overflow = "hidden";
                document.documentElement.style.overflow = "hidden";
              }
              const message = { type: "aliflix-fullscreen" };
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
        setSystemBarsVisible(activity, true)
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
        loadedKey = null
        activeSelection = null
        _switchingMoviepireServer.value = false
    }

    fun onAppBackground() {
        flushProgress(urgentCloudSync = true)
    }

    private fun loadSelection(
        view: WebView,
        selection: PlaybackSelection,
    ) {
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
        if (BuildConfig.IS_TV) return
        if (!selection.source.provider.usesMoviepire) {
            view.setDownloadListener(null)
            moviepireDocumentStartScriptHandler?.remove()
            moviepireDocumentStartScriptHandler = null
            moviepireProtectedSourceHost = null
            moviepireProtectedNativeMode = false
            removeMoviepireMessageListener(view)
            return
        }
        view.setDownloadListener { _, _, _, _, _ ->
            // Embedded playback never initiates downloads. Moviepire ad scripts use fake
            // APK/security downloads as a conversion path; consume them in-app.
        }

        val sourceHost = selection.source.cleanDomain.lowercase().removePrefix("www.")
        val nativeMode = !BuildConfig.IS_TV && selection.source.provider.usesMoviepire
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
                    "\n" + mobileMoviepireProgressBridgeScript()
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
                mobileMoviepireShieldOriginRules(sourceHost),
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy,
                    ) {
                        val selection = activeSelection ?: return
                        if (BuildConfig.IS_TV || !selection.source.provider.usesMoviepire) return
                        if (!isApprovedMoviepireProgressOrigin(sourceOrigin, selection)) return
                        handlePlaybackMessage(view, selection, message.data)
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
        val host = origin.host?.lowercase()?.removePrefix("www.") ?: return false
        val approved = PlaybackNavigationPolicy.moviepirePlayerDocumentHosts +
            selection.source.cleanDomain.lowercase().removePrefix("www.")
        return approved.any { allowed -> host == allowed || host.endsWith(".$allowed") }
    }

    private fun handlePlaybackMessage(
        view: WebView,
        selection: PlaybackSelection,
        raw: String?,
    ) {
        if (!isActiveSelection(view, selection) || raw.isNullOrBlank()) return
        val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return
        val event = payload.optString("event")
        val position = payload.optDouble("positionSeconds", Double.NaN)
        val duration = payload.optDouble("durationSeconds", Double.NaN)
        if (
            !position.isFinite() || position < 0.0 ||
            !duration.isFinite() || duration < MIN_TRACKABLE_VIDEO_DURATION_SECONDS ||
            duration > MAX_REASONABLE_VIDEO_DURATION_SECONDS ||
            position > duration + 5.0
        ) {
            return
        }
        latestPositionSeconds = position.coerceAtMost(duration)
        latestDurationSeconds = duration
        when (event) {
            "play" -> _playing.value = true
            "pause", "ended" -> _playing.value = false
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
                postSeekToMoviepirePlayer(view, seek)
            }
            if (position >= seek - SEEK_RESTORE_TOLERANCE_SECONDS) {
                pendingSeekSeconds = null
                pendingServerKey = null
                _switchingMoviepireServer.value = false
            }
        }
        val now = SystemClock.elapsedRealtime()
        val urgent = event in setOf("pause", "ended", "seeked")
        if (urgent || now - lastLocalProgressWriteAt >= LOCAL_PROGRESS_INTERVAL_MILLIS) {
            playbackProgressStore.savePlayerProgress(
                selection = selection,
                positionSeconds = latestPositionSeconds,
                durationSeconds = latestDurationSeconds,
                urgentCloudSync = urgent,
            )
            lastLocalProgressWriteAt = now
        }
    }

    private fun flushProgress(urgentCloudSync: Boolean) {
        val selection = activeSelection ?: return
        if (BuildConfig.IS_TV || !selection.source.provider.usesMoviepire) return
        if (latestDurationSeconds <= 0.0) return
        playbackProgressStore.savePlayerProgress(
            selection = selection,
            positionSeconds = latestPositionSeconds,
            durationSeconds = latestDurationSeconds,
            urgentCloudSync = urgentCloudSync,
        )
        lastLocalProgressWriteAt = SystemClock.elapsedRealtime()
    }

    private fun postSeekToMoviepirePlayer(view: WebView, seconds: Double) {
        if (!seconds.isFinite() || seconds < 0.0) return
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
        return object : WebView(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (BuildConfig.IS_TV && event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_NUMPAD_ENTER,
                        KeyEvent.KEYCODE_BUTTON_A,
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                        KeyEvent.KEYCODE_MEDIA_PLAY,
                        KeyEvent.KEYCODE_MEDIA_PAUSE,
                        -> {
                            if (event.repeatCount == 0) {
                                activateTvPlayerSelection(this)
                            }
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN,
                        -> {
                            if (isMoviepireWrapper(this)) {
                                return super.dispatchKeyEvent(event)
                            }
                            if (event.repeatCount == 0) {
                                moveTvWebFocus(this, event.keyCode)
                            }
                            return true
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
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
                userAgentString = browserCompatibleUserAgent(userAgentString.orEmpty())
            }
            cookieManager.setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    _loading.value = true
                    _error.value = null
                    view?.alpha = 1f
                    val selection = activeSelection
                    if (!BuildConfig.IS_TV && selection?.source?.provider?.usesMoviepire == true) {
                        _moviepireServers.value = emptyList()
                    }
                    if (
                        view != null &&
                        selection?.source?.provider?.usesMoviepire == true &&
                        !BuildConfig.IS_TV
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
                            shouldResolveMobileMoviepireEpisode(selection)
                        ) {
                            view.alpha = 0f
                            resolveMobileMoviepireEpisode(view, selection, attempt = 0)
                            return
                        }
                        if (
                            selection != null &&
                            selection.source.provider == PlaybackProviderId.RAMOFLIX &&
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
                            prepareTvWebFocus(view)
                        }
                        if (
                            BuildConfig.IS_TV &&
                            url.toUri().host
                                ?.removePrefix("www.")
                                ?.let { it == "soap2night.cc" || it.endsWith(".soap2night.cc") } == true
                        ) {
                            view.postDelayed(
                                { promoteTvPlayerFrame(view, attempt = 0) },
                                900L,
                            )
                        }
                        if (selection != null) {
                            if (
                                !BuildConfig.IS_TV &&
                                selection.source.provider.usesMoviepire
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
                            if (selection.source.provider != PlaybackProviderId.RAMOFLIX) {
                                view.postDelayed(
                                    {
                                        if (isActiveSelection(view, selection)) {
                                            alignProviderContent(view, selection)
                                            if (playerVisible) prepareTvWebFocus(view)
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
                    if (!isMobileMoviepireSelection()) {
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
                        isMobileMoviepireSelection() &&
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
                    if (!isMobileMoviepireSelection()) {
                        Toast.makeText(
                            activity,
                            "Pop-up blocked",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return false
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    if (isMobileMoviepireSelection()) {
                        request?.deny()
                    } else {
                        super.onPermissionRequest(request)
                    }
                }

                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?,
                ) {
                    if (isMobileMoviepireSelection()) {
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
                    if (!isMobileMoviepireSelection()) return false
                    filePathCallback?.onReceiveValue(null)
                    return true
                }

                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (!isMobileMoviepireSelection()) {
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
                    if (!isMobileMoviepireSelection()) {
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
                    if (!isMobileMoviepireSelection()) {
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
        return selection.source.provider == PlaybackProviderId.RAMOFLIX &&
            cleanHost == selection.source.cleanDomain &&
            uri.getQueryParameter("s") != null
    }

    private fun customHostsForActiveSelection(): Set<String> =
        activeSelection?.source?.approvedTopLevelHosts.orEmpty()

    private fun isMobileMoviepireSelection(): Boolean =
        !BuildConfig.IS_TV &&
            activeSelection?.source?.provider?.usesMoviepire == true

    private fun isMoviepireWrapper(view: WebView): Boolean {
        val selection = activeSelection ?: return false
        if (!selection.source.provider.usesMoviepire) return false
        val currentHost = runCatching {
            URI(view.url.orEmpty()).host?.removePrefix("www.")
        }.getOrNull()
        return currentHost.equals(selection.source.cleanDomain, ignoreCase = true)
    }

    private fun prepareTvWebFocus(view: WebView) {
        if (!BuildConfig.IS_TV) return
        view.evaluateJavascript(
            """
            (() => {
              const visible = (element) => {
                const rect = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return rect.width > 8 && rect.height > 8 &&
                  style.display !== "none" && style.visibility !== "hidden";
              };
              const frames = Array.from(document.querySelectorAll("iframe"))
                .filter(visible)
                .sort((a, b) => {
                  const ar = a.getBoundingClientRect();
                  const br = b.getBoundingClientRect();
                  return (br.width * br.height) - (ar.width * ar.height);
                });
              frames.forEach((frame) => frame.tabIndex = 0);
              const play = Array.from(
                document.querySelectorAll(
                  "#pl_but, #pl_but_background, #play, .btn-watchnow"
                )
              ).find(visible);
              if (play) {
                play.tabIndex = 0;
                play.setAttribute("role", "button");
                play.setAttribute("aria-label", "Play video");
                play.focus({ preventScroll: true });
              } else {
                frames[0]?.focus({ preventScroll: true });
              }
              return frames.length + (play ? 1 : 0);
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun moveTvWebFocus(view: WebView, keyCode: Int) {
        val direction = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> "left"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "right"
            KeyEvent.KEYCODE_DPAD_UP -> "up"
            else -> "down"
        }
        view.evaluateJavascript(
            """
            (() => {
              const direction = "$direction";
              const visible = (element) => {
                const rect = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return rect.width > 8 && rect.height > 8 &&
                  style.display !== "none" && style.visibility !== "hidden" &&
                  style.opacity !== "0";
              };
              const candidates = Array.from(document.querySelectorAll(
                'iframe, #pl_but, #pl_but_background, #play, .btn-watchnow, ' +
                'a[href], button, input, select, ' +
                '[role="button"], [onclick], [tabindex]'
              )).filter((element, index, all) =>
                visible(element) && !element.disabled && all.indexOf(element) === index
              );
              candidates.forEach((element) => {
                if (element.tabIndex < 0) element.tabIndex = 0;
              });
              if (!candidates.length) return false;

              let current = document.activeElement;
              if (!candidates.includes(current)) {
                current = candidates
                  .filter((element) => element.tagName === "IFRAME")
                  .sort((a, b) => {
                    const ar = a.getBoundingClientRect();
                    const br = b.getBoundingClientRect();
                    return (br.width * br.height) - (ar.width * ar.height);
                  })[0] || candidates[0];
                current.focus({ preventScroll: true });
                return true;
              }

              const source = current.getBoundingClientRect();
              const sx = source.left + source.width / 2;
              const sy = source.top + source.height / 2;
              const directional = candidates
                .filter((candidate) => candidate !== current)
                .map((candidate) => {
                  const rect = candidate.getBoundingClientRect();
                  const x = rect.left + rect.width / 2;
                  const y = rect.top + rect.height / 2;
                  const dx = x - sx;
                  const dy = y - sy;
                  const allowed =
                    (direction === "left" && dx < -4) ||
                    (direction === "right" && dx > 4) ||
                    (direction === "up" && dy < -4) ||
                    (direction === "down" && dy > 4);
                  if (!allowed) return null;
                  const primary = direction === "left" || direction === "right"
                    ? Math.abs(dx) : Math.abs(dy);
                  const secondary = direction === "left" || direction === "right"
                    ? Math.abs(dy) : Math.abs(dx);
                  return { candidate, score: primary + secondary * 2.4 };
                })
                .filter(Boolean)
                .sort((a, b) => a.score - b.score);
              const next = directional[0]?.candidate;
              if (!next) return false;
              next.focus({ preventScroll: true });
              next.scrollIntoView({ block: "nearest", inline: "nearest" });
              return true;
            })();
            """.trimIndent(),
            null,
        )
    }

    private fun activateTvPlayerSelection(view: WebView) {
        view.evaluateJavascript(
            """
            (() => {
              const visible = (element) => {
                const rect = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return rect.width > 8 && rect.height > 8 &&
                  style.display !== "none" && style.visibility !== "hidden";
              };
              const active = document.activeElement;
              if (
                active &&
                active !== document.body &&
                active !== document.documentElement &&
                active.tagName !== "IFRAME"
              ) {
                const isPlayerBootstrap = active.matches(
                  "#play, .btn-watchnow"
                );
                active.click();
                if (isPlayerBootstrap) {
                  window.setTimeout(() => {
                    const frame = Array.from(document.querySelectorAll("iframe"))
                      .filter((candidate) => {
                        const rect = candidate.getBoundingClientRect();
                        return rect.width > 100 && rect.height > 100 &&
                          candidate.src && candidate.src !== "about:blank";
                      })
                      .sort((a, b) => {
                        const ar = a.getBoundingClientRect();
                        const br = b.getBoundingClientRect();
                        return (br.width * br.height) - (ar.width * ar.height);
                      })[0];
                    if (frame?.src?.startsWith("https://")) {
                      location.assign(frame.src);
                    }
                  }, 700);
                }
                return JSON.stringify({
                  action: isPlayerBootstrap ? "clicked-play" : "clicked"
                });
              }
              const directPlay = Array.from(
                document.querySelectorAll(
                  "#pl_but, #pl_but_background, #play, .btn-watchnow"
                )
              ).find(visible);
              if (directPlay) {
                directPlay.click();
                window.setTimeout(() => {
                  const frame = Array.from(document.querySelectorAll("iframe"))
                    .filter((candidate) => {
                      const rect = candidate.getBoundingClientRect();
                      return rect.width > 100 && rect.height > 100 &&
                        candidate.src && candidate.src !== "about:blank";
                    })
                    .sort((a, b) => {
                      const ar = a.getBoundingClientRect();
                      const br = b.getBoundingClientRect();
                      return (br.width * br.height) - (ar.width * ar.height);
                    })[0];
                  if (frame?.src?.startsWith("https://")) {
                    location.assign(frame.src);
                  }
                }, 700);
                return JSON.stringify({ action: "clicked-play" });
              }
              const clickNestedPlayer = (root, depth = 0) => {
                if (depth > 3) return false;
                for (const nestedFrame of root.querySelectorAll("iframe")) {
                  try {
                    const nestedDocument = nestedFrame.contentDocument;
                    if (!nestedDocument) continue;
                    const nestedControl = Array.from(
                      nestedDocument.querySelectorAll(
                        "#pl_but, #pl_but_background, #play, .btn-watchnow"
                      )
                    ).find((element) => {
                      const rect = element.getBoundingClientRect();
                      const style = element.ownerDocument.defaultView
                        .getComputedStyle(element);
                      return rect.width > 8 && rect.height > 8 &&
                        style.display !== "none" &&
                        style.visibility !== "hidden";
                    });
                    if (nestedControl) {
                      nestedControl.click();
                      return true;
                    }
                    const nestedVideo = nestedDocument.querySelector("video");
                    if (nestedVideo) {
                      if (nestedVideo.paused) nestedVideo.play();
                      else nestedVideo.pause();
                      return true;
                    }
                    if (clickNestedPlayer(nestedDocument, depth + 1)) {
                      return true;
                    }
                  } catch (_) {
                    // Cross-origin frames are handled by the approved-host fallback.
                  }
                }
                return false;
              };
              if (clickNestedPlayer(document)) {
                return JSON.stringify({ action: "nested-player" });
              }
              const frames = Array.from(document.querySelectorAll("iframe"))
                .filter(visible)
                .sort((a, b) => {
                  const ar = a.getBoundingClientRect();
                  const br = b.getBoundingClientRect();
                  return (br.width * br.height) - (ar.width * ar.height);
                });
              const frame = active?.tagName === "IFRAME" && visible(active)
                ? active
                : frames[0];
              if (!frame) {
                const video = document.querySelector("video");
                if (video) {
                  if (video.paused) video.play(); else video.pause();
                  return JSON.stringify({ action: "video" });
                }
                return JSON.stringify({ action: "none" });
              }
              frame.focus({ preventScroll: true });
              const rect = frame.getBoundingClientRect();
              if (frame.src?.startsWith("https://")) {
                return JSON.stringify({
                  action: "promote",
                  url: frame.src,
                  x: rect.left + rect.width / 2,
                  y: rect.top + rect.height / 2,
                  viewportWidth: innerWidth,
                  viewportHeight: innerHeight
                });
              }
              return JSON.stringify({
                action: "tap",
                x: rect.left + rect.width / 2,
                y: rect.top + rect.height / 2,
                viewportWidth: innerWidth,
                viewportHeight: innerHeight
              });
            })();
            """.trimIndent(),
        ) { result ->
            val payload = runCatching {
                JSONTokener(result).nextValue() as? String
            }.getOrNull().orEmpty()
            val value = runCatching { JSONObject(payload) }.getOrNull() ?: return@evaluateJavascript
            when (value.optString("action")) {
                "clicked-play" -> {
                    view.postDelayed(
                        { promoteTvPlayerFrame(view, attempt = 0) },
                        700L,
                    )
                }
                "promote" -> {
                    val target = value.optString("url")
                    if (
                        PlaybackNavigationPolicy.isAllowedTopLevel(
                            target,
                            customHostsForActiveSelection(),
                        )
                    ) {
                        view.loadUrl(target)
                    } else {
                        view.post {
                            dispatchWebViewTap(
                                view = view,
                                x = value.optDouble("x").toFloat(),
                                y = value.optDouble("y").toFloat(),
                                viewportWidth = value.optDouble("viewportWidth").toFloat(),
                                viewportHeight = value.optDouble("viewportHeight").toFloat(),
                            )
                        }
                    }
                }
                "tap" -> {
                    view.post {
                        if (!clickTvPlayerAccessibilityNode(view)) {
                            dispatchWebViewTap(
                                view = view,
                                x = value.optDouble("x").toFloat(),
                                y = value.optDouble("y").toFloat(),
                                viewportWidth = value.optDouble("viewportWidth").toFloat(),
                                viewportHeight = value.optDouble("viewportHeight").toFloat(),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun promoteTvPlayerFrame(view: WebView, attempt: Int) {
        if (!BuildConfig.IS_TV || attempt > 8 || webView !== view) return
        view.evaluateJavascript(
            """
            (() => {
              const usable = (frame) => {
                const rect = frame.getBoundingClientRect();
                const style = getComputedStyle(frame);
                return frame.src && frame.src !== "about:blank" &&
                  style.display !== "none" && style.visibility !== "hidden" &&
                  (rect.width > 100 || rect.height > 100);
              };
              const frame = Array.from(document.querySelectorAll("iframe"))
                .filter((candidate) =>
                  usable(candidate)
                )
                .sort((a, b) => {
                  const ar = a.getBoundingClientRect();
                  const br = b.getBoundingClientRect();
                  return (br.width * br.height) - (ar.width * ar.height);
                })[0];
              return frame?.src || "";
            })();
            """.trimIndent(),
        ) { result ->
            if (webView !== view) return@evaluateJavascript
            val target = runCatching {
                JSONTokener(result).nextValue() as? String
            }.getOrNull().orEmpty()
            if (
                target.isNotBlank() &&
                PlaybackNavigationPolicy.isAllowedTopLevel(
                    target,
                    customHostsForActiveSelection(),
                )
            ) {
                view.loadUrl(target)
            } else if (attempt < 8) {
                view.postDelayed(
                    { promoteTvPlayerFrame(view, attempt + 1) },
                    450L,
                )
            }
        }
    }

    private fun clickTvPlayerAccessibilityNode(view: WebView): Boolean {
        val root = view.createAccessibilityNodeInfo() ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = 0
        var visited = 0

        while (queue.isNotEmpty() && visited < 500) {
            val node = queue.removeFirst()
            visited += 1
            val id = node.viewIdResourceName
                ?.substringAfterLast('/')
                ?.lowercase()
                .orEmpty()
            val score = when {
                id == "pl_but" -> 100
                id == "pl_but_background" -> 90
                id == "play" -> 80
                id.contains("play") && node.isClickable -> 50
                else -> 0
            }
            if (node.isVisibleToUser && node.isEnabled && node.isClickable && score > bestScore) {
                bestNode = node
                bestScore = score
            }
            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return bestNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun dispatchWebViewTap(
        view: WebView,
        x: Float,
        y: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ) {
        if (
            !x.isFinite() ||
            !y.isFinite() ||
            viewportWidth <= 0f ||
            viewportHeight <= 0f
        ) {
            return
        }
        val viewX = x * (view.width / viewportWidth)
        val viewY = y * (view.height / viewportHeight)
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            viewX,
            viewY,
            0,
        ).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        view.dispatchTouchEvent(down)
        down.recycle()
        view.postDelayed(
            {
                val upTime = SystemClock.uptimeMillis()
                val up = MotionEvent.obtain(
                    downTime,
                    upTime,
                    MotionEvent.ACTION_UP,
                    viewX,
                    viewY,
                    0,
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
                view.dispatchTouchEvent(up)
                up.recycle()
            },
            70L,
        )
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
        when (selection.source.provider) {
            PlaybackProviderId.RAMOFLIX -> alignRamoflixContent(view, selection)
            PlaybackProviderId.MOVIEPIRE -> {
                if (!BuildConfig.IS_TV) installMobileMoviepireAdShield(view, selection)
                if (!BuildConfig.IS_TV) {
                    discoverMoviepireServers(view, selection)
                }
            }
            PlaybackProviderId.DORABY -> { /* Doraby webpage loads directly */ }
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
            BuildConfig.IS_TV ||
            !selection.source.provider.usesMoviepire ||
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
        if (BuildConfig.IS_TV || !selection.source.provider.usesMoviepire) return
        if (!isActiveSelection(view, selection)) return
        view.evaluateJavascript(
            mobileMoviepireAdShieldScript() + if (!BuildConfig.IS_TV) {
                "\n" + mobileMoviepireProgressBridgeScript()
            } else {
                ""
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
        if (customView != null) {
            callback?.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
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
        webView?.evaluateJavascript(
            """
            (() => {
              const state = window.__aliflixFullscreenLayout;
              if (!state) return false;
              if (state.frame?.isConnected) state.frame.style.cssText = state.frameStyle;
              if (document.body) document.body.style.overflow = state.bodyOverflow;
              document.documentElement.style.overflow = state.rootOverflow;
              delete window.__aliflixFullscreenLayout;
              return true;
            })();
            """.trimIndent(),
            null,
        )
        nativeFullscreenRequested = false
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
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
        const val SEEK_RESTORE_TOLERANCE_SECONDS = 3.0
        const val MOVIEPIRE_EPISODE_RESOLUTION_RETRY_MILLIS = 450L
        const val MAX_MOVIEPIRE_EPISODE_RESOLUTION_ATTEMPTS = 32
        const val MAX_MOVIEPIRE_EPISODE_BOOTSTRAP_RELOADS = 1
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
    val minimum = if (switchingServer) 1.0 else PlaybackProgress.RESUME_MINIMUM_SECONDS
    return positionSeconds >= minimum && positionSeconds < durationSeconds
}

internal fun shouldResolveMobileMoviepireEpisode(selection: PlaybackSelection): Boolean =
    !BuildConfig.IS_TV &&
        selection.source.provider.usesMoviepire &&
        selection.media.type == MediaType.TV

internal fun mobileMoviepireEpisodeBootstrapUrl(selection: PlaybackSelection): String? =
    selection.takeIf(::shouldResolveMobileMoviepireEpisode)?.let {
        "${it.source.baseUrl.trimEnd('/')}/series/${it.media.id}"
    }

/**
 * Replays Moviepire's own exact episode flow. Moviepire decides movie-versus-series from its
 * router query, while its details page supplies the authoritative episode link and `me` count.
 * Selecting the original season control and clicking that original link keeps this resilient to
 * player-source changes without constructing any embedded-player URL in Aliflix.
 */
internal fun moviepireExactEpisodeResolverScript(
    tmdbId: Int,
    seasonNumber: Int,
    episodeNumber: Int,
): String =
    """
    (() => {
      const expectedId = $tmdbId;
      const expectedSeason = $seasonNumber;
      const expectedEpisode = $episodeNumber;
      const current = new URL(location.href);
      const watchPath = "/watch/" + expectedId;
      const seriesPath = "/series/" + expectedId;
      const currentPath = current.pathname.replace(/\/+$/, "") || "/";

      if (currentPath === watchPath) {
        const exact = Number(current.searchParams.get("s")) === expectedSeason &&
          Number(current.searchParams.get("e")) === expectedEpisode;
        if (!exact) return "wrong-route";
        return document.querySelector(".player iframe, iframe[title^='Video Player']")
          ? "ready"
          : "waiting-player";
      }
      if (currentPath !== seriesPath) return "wrong-route";

      const seasonSelect = Array.from(document.querySelectorAll("select")).find((candidate) => {
        const options = Array.from(candidate.options || []);
        return options.some((option) => Number(option.value) === expectedSeason) &&
          options.some((option) => /season/i.test(option.textContent || ""));
      });
      if (!seasonSelect) return "waiting-details";
      if (Number(seasonSelect.value) !== expectedSeason) {
        seasonSelect.value = String(expectedSeason);
        seasonSelect.dispatchEvent(new Event("input", { bubbles: true }));
        seasonSelect.dispatchEvent(new Event("change", { bubbles: true }));
        return "season-changed";
      }

      const episodeLinks = Array.from(document.querySelectorAll("a[href]"));
      const exactLink = episodeLinks.find((link) => {
        try {
          const target = new URL(link.href, location.href);
          return target.pathname.replace(/\/+$/, "") === watchPath &&
            Number(target.searchParams.get("s")) === expectedSeason &&
            Number(target.searchParams.get("e")) === expectedEpisode;
        } catch (_) {
          return false;
        }
      });
      if (exactLink) {
        exactLink.click();
        return "episode-clicked";
      }

      const episodeSection = Array.from(document.querySelectorAll("section,div")).find((node) => {
        const heading = node.querySelector(":scope > h2, :scope > h3, :scope > .title-row h3");
        return /episodes/i.test(heading?.textContent || "");
      });
      const expandButton = episodeSection?.querySelector(
        ".title-extend button, button [class*='chevron-down']"
      )?.closest("button");
      if (expandButton) {
        expandButton.click();
        return "episodes-expanded";
      }
      return "waiting-episodes";
    })();
    """.trimIndent()

internal fun mobileMoviepireShieldOriginRules(sourceHost: String): Set<String> {
    val cleanSourceHost = sourceHost
        .trim()
        .lowercase()
        .removePrefix("www.")
        .takeIf { host ->
            host.isNotBlank() &&
                host.none(Char::isWhitespace) &&
                host.all { character ->
                    character.isLetterOrDigit() || character == '.' || character == '-'
                }
        }
    return (PlaybackNavigationPolicy.moviepirePlayerDocumentHosts + listOfNotNull(cleanSourceHost))
        .flatMapTo(linkedSetOf()) { host ->
            listOf("https://$host", "https://*.$host")
        }
}

/**
 * Finds Moviepire's own source selector without relying on a server label or a generated class
 * name. The selector is tagged before it is hidden so native choices can still dispatch the
 * original React input/change events.
 */
internal fun moviepireServerDiscoveryScript(): String =
    """
    (() => {
      const normalize = (value) => String(value || "").replace(/\s+/g, " ").trim();
      const candidates = Array.from(document.querySelectorAll("select"));
      const select = candidates.find((candidate) => {
        if (candidate.options.length < 1) return false;
        const identity = normalize([
          candidate.getAttribute("aria-label"),
          candidate.getAttribute("name"),
          candidate.id,
          candidate.getAttribute("data-testid")
        ].filter(Boolean).join(" ")).toLowerCase();
        const positivelyNamed = /(?:video\s*)?(?:source|server|stream)/i.test(identity);
        const controls = candidate.closest(".player-controls,[class*='player-controls']");
        const playerContext = Boolean(controls && (
          controls.parentElement?.querySelector("iframe,video") ||
          document.querySelector("iframe[src],video")
        ));
        return positivelyNamed || playerContext;
      });
      if (!select) return "[]";

      select.dataset.aliflixServerSelect = "true";
      const wrapperControls = select.closest(".player-controls,[class*='player-controls']");
      const servers = Array.from(select.options).map((option, index) => {
        const label = normalize(option.label || option.textContent || option.value);
        const key = "option:" + index + ":" + String(option.value || "");
        option.dataset.aliflixServerKey = key;
        return { key, label, selected: option.selected || select.selectedIndex === index };
      }).filter((option) => option.label.length > 0);
      if (servers.length === 0) return "[]";

      select.style.setProperty("visibility", "hidden", "important");
      select.style.setProperty("opacity", "0", "important");
      select.style.setProperty("pointer-events", "none", "important");
      select.setAttribute("aria-hidden", "true");
      select.tabIndex = -1;
      const downloadSelector = [
        "a[download]",
        "a[href*='download' i]",
        "[aria-label*='download' i]",
        "[title*='download' i]",
        "[data-testid*='download' i]",
        "[class*='download' i]"
      ].join(",");
      Array.from(document.querySelectorAll(downloadSelector)).forEach((control) => {
        const target = control.closest("a,button") || control;
        if (!target.querySelector("iframe,video")) {
          target.style.setProperty("display", "none", "important");
          target.setAttribute("aria-hidden", "true");
          target.tabIndex = -1;
        }
      });
      if (wrapperControls && !wrapperControls.querySelector("iframe,video")) {
        wrapperControls.dataset.aliflixWrapperControls = "true";
        wrapperControls.style.setProperty("display", "none", "important");
      }
      return JSON.stringify(servers);
    })();
    """.trimIndent()

internal fun parseMoviepireServerDiscovery(payload: String): List<MoviepireServerOption> =
    runCatching {
        val array = JSONArray(payload)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val key = item.optString("key").trim()
            val label = item.optString("label").trim()
            if (key.isBlank() || label.isBlank()) return@mapNotNull null
            MoviepireServerOption(
                key = key,
                label = label,
                selected = item.optBoolean("selected"),
            )
        }.distinctBy(MoviepireServerOption::key)
    }.getOrDefault(emptyList())

/** Runs only in origin-restricted Moviepire/player documents registered with AndroidX WebKit. */
internal fun mobileMoviepireProgressBridgeScript(): String =
    """
    (() => {
      "use strict";
      if (window.__aliflixPlaybackProgressBridge === true) return true;
      window.__aliflixPlaybackProgressBridge = true;
      const bridge = window.AliflixPlaybackProgress;
      if (!bridge || typeof bridge.postMessage !== "function") return false;

      let activeVideo = null;
      let lastTimeUpdateSentAt = 0;
      const minimumDurationSeconds = 60;
      const validNumber = (value) => Number.isFinite(value) && value >= 0;
      const videoScore = (video) => {
        if (!video) return -1;
        const rect = video.getBoundingClientRect();
        const area = Math.max(0, rect.width) * Math.max(0, rect.height);
        const playingScore = video.paused ? 0 : 1000000000;
        const visibleScore = getComputedStyle(video).visibility === "hidden" ? 0 : 100000000;
        return playingScore + visibleScore + area;
      };
      const activate = (video) => {
        if (!activeVideo || !activeVideo.isConnected ||
            video === activeVideo || videoScore(video) >= videoScore(activeVideo)) {
          activeVideo = video;
        }
        return activeVideo === video;
      };
      const post = (event, video) => {
        if (!video || !validNumber(video.currentTime) ||
            !Number.isFinite(video.duration) || video.duration < minimumDurationSeconds ||
            !activate(video)) return;
        try {
          bridge.postMessage(JSON.stringify({
            event,
            positionSeconds: video.currentTime,
            durationSeconds: video.duration
          }));
        } catch (_) {}
      };
      const onTimeUpdate = (event) => {
        const now = Date.now();
        if (now - lastTimeUpdateSentAt < 1000) return;
        lastTimeUpdateSentAt = now;
        post("timeupdate", event.currentTarget);
      };
      const attach = (video) => {
        if (!video || video.dataset.aliflixProgressAttached === "true") return;
        video.dataset.aliflixProgressAttached = "true";
        activate(video);
        video.addEventListener("loadedmetadata", (event) => post("loadedmetadata", event.currentTarget));
        video.addEventListener("timeupdate", onTimeUpdate);
        ["play", "pause", "seeking", "seeked", "ended"].forEach((eventName) => {
          video.addEventListener(eventName, (event) => post(eventName, event.currentTarget));
        });
        if (video.readyState >= 1) post("loadedmetadata", video);
      };
      const scan = () => document.querySelectorAll("video").forEach(attach);
      new MutationObserver(scan).observe(document.documentElement || document, {
        childList: true,
        subtree: true
      });
      document.addEventListener("DOMContentLoaded", scan, { once: true });
      scan();

      window.addEventListener("message", (event) => {
        const data = event.data;
        if (!data) return;
        if (data.type === "aliflix-fullscreen") {
          document.querySelectorAll("iframe").forEach(frame => {
            try { frame.contentWindow?.postMessage(data, "*"); } catch (_) {}
          });
          const video = activeVideo || document.querySelector("video");
          if (video) {
            const request = video.requestFullscreen || video.webkitRequestFullscreen ||
              video.webkitEnterFullscreen;
            if (typeof request === "function") {
              try {
                const result = request.call(video);
                if (result && typeof result.catch === "function") result.catch(() => {});
              } catch (_) {}
              return;
            }
          }
          const fullscreenControl = Array.from(
            document.querySelectorAll('button,[role="button"]')
          ).find(control => {
            const label = [
              control.getAttribute("aria-label"),
              control.getAttribute("title"),
              control.dataset?.title
            ].filter(Boolean).join(" ");
            return /(?:^|\s)full\s*screen(?:\s|$)/i.test(label);
          });
          fullscreenControl?.click();
          return;
        }
        if (data.type !== "aliflix-seek") return;
        const seconds = Number(data.seconds);
        if (!validNumber(seconds)) return;
        const video = activeVideo || document.querySelector("video");
        if (!video) return;
        const seek = () => {
          if (!Number.isFinite(video.duration) || video.duration <= 0) return false;
          video.currentTime = Math.min(seconds, Math.max(0, video.duration - 0.25));
          return true;
        };
        if (!seek()) video.addEventListener("loadedmetadata", seek, { once: true });
      });
      return true;
    })();
    """.trimIndent()

/**
 * Runs at document start in Moviepire and each of its player frames. Native interception remains
 * authoritative; this layer prevents inline/dynamically-generated ad code and removes scam UI
 * without altering video, player controls, fullscreen, or Cast APIs.
 */
internal fun mobileMoviepireAdShieldScript(): String {
    val blockedHostsJson = PlaybackNavigationPolicy.blockedAdvertisingHosts
        .sorted()
        .joinToString(separator = ",") { host -> JSONObject.quote(host) }
    return """
        (() => {
          "use strict";
          if (window.__aliflixMoviepireAdShield === true) return true;
          try {
            Object.defineProperty(window, "__aliflixMoviepireAdShield", {
              value: true,
              configurable: false,
              writable: false
            });
          } catch (_) {
            window.__aliflixMoviepireAdShield = true;
          }

          const blockedHosts = [$blockedHostsJson];
          const cleanHost = (host) => String(host || "").toLowerCase().replace(/^www\./, "");
          const hostBlocked = (host) => {
            const clean = cleanHost(host);
            return blockedHosts.some((blocked) =>
              clean === blocked || clean.endsWith("." + blocked)
            );
          };
          const urlBlocked = (raw) => {
            if (!raw) return false;
            try {
              const target = new URL(String(raw), location.href);
              if (hostBlocked(target.hostname)) return true;
              const path = target.pathname.toLowerCase();
              if (cleanHost(target.hostname).endsWith("vidrock.ru") && path === "/sbx.js") {
                return true;
              }
              return ["/popunder", "/clickunder", "/push-sdk", "/adserver/", "/ads/"]
                .some((token) => path.includes(token));
            } catch (_) {
              return false;
            }
          };
          const resourceUrl = (node) => {
            if (!node || !node.tagName) return "";
            const tag = node.tagName.toUpperCase();
            if (tag === "OBJECT") return node.getAttribute("data") || "";
            if (tag === "SCRIPT" || tag === "IFRAME" || tag === "EMBED" || tag === "SOURCE") {
              return node.getAttribute("src") || "";
            }
            if (tag === "A" || tag === "LINK") return node.getAttribute("href") || "";
            return "";
          };
          const blockedNode = (node) => urlBlocked(resourceUrl(node));

          const nativeAppendChild = Node.prototype.appendChild;
          const nativeInsertBefore = Node.prototype.insertBefore;
          Node.prototype.appendChild = function(node) {
            if (blockedNode(node)) return node;
            return nativeAppendChild.call(this, node);
          };
          Node.prototype.insertBefore = function(node, reference) {
            if (blockedNode(node)) return node;
            return nativeInsertBefore.call(this, node, reference);
          };

          const nativeAddEventListener = EventTarget.prototype.addEventListener;
          EventTarget.prototype.addEventListener = function(type, listener, options) {
            if (this === document && type === "click" && listener) {
              let source = "";
              try { source = Function.prototype.toString.call(listener).toLowerCase(); } catch (_) {}
              if ([
                "adnetworks", "adlastfired", "popunder", "clickunder",
                "profiton", "hilltopads", "monetag"
              ].some((token) => source.includes(token))) {
                return;
              }
            }
            return nativeAddEventListener.call(this, type, listener, options);
          };

          const noPopup = () => null;
          try {
            Object.defineProperty(window, "open", {
              value: noPopup,
              configurable: false,
              writable: false
            });
          } catch (_) {
            window.open = noPopup;
          }

          const adSelector = [
            "#paldo-ad",
            "ins.adsbygoogle",
            "[id^='google_ads']",
            "[id^='ad-']",
            "[id^='ads-']",
            "[class~='ad']",
            "[class^='ad-']",
            "[class*=' ad-']",
            "[class*='ad-container']",
            "[class*='ad-banner']",
            "[class*='advertisement']",
            "[class*='adsbox']",
            "[data-ad-slot]",
            "[data-ad-unit]",
            "[data-advertisement]",
            "[aria-label='advertisement']",
            "[aria-label='sponsored']"
          ].join(",");
          const scamPhrases = [
            "system warning detected",
            "device has been infected",
            "security alert",
            "file is ready to download",
            "install the recommended security app",
            "run the app to remove all threats",
            "tap the button below",
            "time remaining",
            "your sim card",
            "remove all threats"
          ];
          const ownsVideo = (node) => Boolean(
            node && (
              node.matches && node.matches("video, audio") ||
              node.querySelector && node.querySelector("video, audio")
            )
          );
          const removeNode = (node) => {
            if (!node || ownsVideo(node)) return;
            try { node.remove(); } catch (_) {}
          };
          const ensureStyle = () => {
            if (document.getElementById("aliflix-moviepire-ad-style")) return;
            const parent = document.head || document.documentElement;
            if (!parent) return;
            const style = document.createElement("style");
            style.id = "aliflix-moviepire-ad-style";
            style.textContent = adSelector + "{" +
              "display:none!important;visibility:hidden!important;" +
              "pointer-events:none!important;width:0!important;height:0!important;" +
              "min-width:0!important;min-height:0!important;margin:0!important;padding:0!important}";
            nativeAppendChild.call(parent, style);
          };
          const cleanup = () => {
            ensureStyle();
            try {
              document.querySelectorAll(adSelector).forEach(removeNode);
              document.querySelectorAll(
                "script[src],iframe[src],object[data],embed[src],source[src],link[href]"
              ).forEach((node) => {
                if (blockedNode(node)) removeNode(node);
              });
              document.querySelectorAll("a[href]").forEach((anchor) => {
                if (urlBlocked(anchor.getAttribute("href"))) {
                  removeNode(anchor);
                  return;
                }
                const style = getComputedStyle(anchor);
                const rect = anchor.getBoundingClientRect();
                const viewport = Math.max(1, innerWidth * innerHeight);
                const coverage = (rect.width * rect.height) / viewport;
                const external = (() => {
                  try {
                    return cleanHost(new URL(anchor.href, location.href).hostname) !==
                      cleanHost(location.hostname);
                  } catch (_) { return false; }
                })();
                if (
                  external &&
                  !ownsVideo(anchor) &&
                  (style.position === "fixed" || style.position === "sticky") &&
                  coverage > 0.2
                ) {
                  removeNode(anchor);
                }
              });
              document.querySelectorAll(
                "[role='dialog'],dialog,aside,[class*='modal'],[class*='popup'],[class*='overlay']"
              ).forEach((node) => {
                if (ownsVideo(node)) return;
                const copy = String(node.innerText || node.textContent || "").toLowerCase();
                const matches = scamPhrases.reduce(
                  (count, phrase) => count + (copy.includes(phrase) ? 1 : 0),
                  0
                );
                if (matches >= 2) removeNode(node);
              });
            } catch (_) {}
          };

          let cleanupScheduled = false;
          const scheduleCleanup = () => {
            if (cleanupScheduled) return;
            cleanupScheduled = true;
            requestAnimationFrame(() => {
              cleanupScheduled = false;
              cleanup();
            });
          };
          const observer = new MutationObserver(scheduleCleanup);
          observer.observe(document, { childList: true, subtree: true });
          nativeAddEventListener.call(document, "DOMContentLoaded", cleanup, { once: true });
          nativeAddEventListener.call(document, "click", (event) => {
            const anchor = event.target && event.target.closest
              ? event.target.closest("a[href]")
              : null;
            if (!anchor || !urlBlocked(anchor.getAttribute("href"))) return;
            event.preventDefault();
            event.stopImmediatePropagation();
            removeNode(anchor);
          }, true);
          scheduleCleanup();
          return true;
        })();
    """.trimIndent()
}
