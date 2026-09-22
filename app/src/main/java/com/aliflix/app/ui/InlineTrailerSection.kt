package com.aliflix.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.aliflix.app.model.Media
import com.aliflix.app.ui.theme.*

/** Official TMDB trailer keys reference YouTube, whose player cannot be fed to ExoPlayer. */
@Composable
internal fun InlineTrailerSection(media: Media) {
    val videoId = media.trailerKey?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return
    key(media.key, videoId) { TrailerPlayer(videoId) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerPlayer(videoId: String) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val originalOrientation = remember(activity) { activity?.requestedOrientation }
    val wasWindowFullscreen = remember(activity) {
        activity?.window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0
    }
    val systemBarsWereVisible = remember(activity) {
        activity?.window?.decorView?.let { decor ->
            ViewCompat.getRootWindowInsets(decor)?.isVisible(WindowInsetsCompat.Type.systemBars())
        } ?: true
    }
    var started by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var manuallyFullscreen by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var loadingError by remember { mutableStateOf(false) }
    var fullscreenContainer by remember { mutableStateOf<FrameLayout?>(null) }
    var inlineContainer by remember { mutableStateOf<FrameLayout?>(null) }

    fun attachPlayer(container: FrameLayout, player: View) {
        if (player.parent !== container) {
            (player.parent as? ViewGroup)?.removeView(player)
            container.addView(player, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }
        player.requestLayout()
        player.invalidate()
    }

    fun setFullscreenWindow(fullscreen: Boolean) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        if (fullscreen) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            if (!wasWindowFullscreen) window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            if (systemBarsWereVisible) controller?.show(WindowInsetsCompat.Type.systemBars())
            originalOrientation?.let { activity?.requestedOrientation = it }
        }
    }

    // Keep Compose's AndroidView host attached. Only native children move, with
    // fresh MATCH_PARENT params instead of retaining the inline player's bounds.
    fun showFullscreenContent(view: View): Boolean {
        val content = activity?.window?.decorView
            ?.findViewById<FrameLayout>(android.R.id.content) ?: return false
        val container = fullscreenContainer ?: FrameLayout(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            isClickable = true
        }.also { overlay ->
            fullscreenContainer = overlay
            content.addView(
                overlay,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        attachPlayer(container, view)
        container.bringToFront()
        return true
    }

    fun exitFullscreen() {
        val wasActive = customView != null || manuallyFullscreen
        val oldView = customView
        val callback = customCallback
        customView = null
        customCallback = null
        manuallyFullscreen = false
        (oldView?.parent as? ViewGroup)?.removeView(oldView)
        webView?.let { player -> inlineContainer?.let { attachPlayer(it, player) } }
        fullscreenContainer?.let { overlay ->
            overlay.removeAllViews()
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        fullscreenContainer = null
        callback?.onCustomViewHidden()
        if (wasActive) setFullscreenWindow(false)
    }

    fun openYouTube() {
        webView?.evaluateJavascript("document.querySelector('iframe')?.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), 'https://www.youtube.com');", null)
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId"))
                    .addCategory(Intent.CATEGORY_BROWSABLE),
            )
        }.onFailure { loadingError = true }
    }

    fun stopFailedPlayer() {
        exitFullscreen()
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
        loadingError = true
    }

    fun obtainPlayer(): WebView {
        webView?.let { return it }
        return WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            // Use the activity's hardware-accelerated compositor without an
            // additional offscreen layer around the embedded video.
            setLayerType(View.LAYER_TYPE_NONE, null)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) view.post { if (webView === view) stopFailedPlayer() }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400) {
                        view.post { if (webView === view) stopFailedPlayer() }
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val host = request.url.host?.lowercase()
                    val path = request.url.path ?: ""
                    val embed = host in setOf("www.youtube.com", "www.youtube-nocookie.com", "m.youtube.com") &&
                        path.startsWith("/embed/")
                    val consent = host in setOf("consent.youtube.com", "consent.google.com", "accounts.google.com")
                    if (embed || consent) return false
                    if (request.url.scheme == "https") {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addCategory(Intent.CATEGORY_BROWSABLE))
                        }
                    }
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (customView != null) {
                        callback.onCustomViewHidden()
                        return
                    }
                    if (!showFullscreenContent(view)) {
                        callback.onCustomViewHidden()
                        return
                    }
                    customView = view
                    customCallback = callback
                    setFullscreenWindow(true)
                }

                override fun onHideCustomView() { exitFullscreen() }
            }
            // YouTube requires the installed app identity as the embed referrer.
            val origin = "https://${context.packageName}"
            loadDataWithBaseURL(
                origin,
                """
                <!doctype html><html><head>
                <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
                <meta name="referrer" content="strict-origin-when-cross-origin">
                <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
                iframe{position:absolute;inset:0;width:100%;height:100%;border:0;display:block}</style>
                </head><body>
                <iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&amp;playsinline=1&amp;controls=1&amp;fs=1&amp;rel=0&amp;enablejsapi=1&amp;origin=$origin"
                referrerpolicy="strict-origin-when-cross-origin"
                allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen></iframe>
                </body></html>
                """.trimIndent(),
                "text/html", "UTF-8", null,
            )
            webView = this
        }
    }

    fun enterFullscreen() {
        started = true
        if (customView != null || manuallyFullscreen) return
        if (!showFullscreenContent(obtainPlayer())) return
        manuallyFullscreen = true
        setFullscreenWindow(true)
    }

    BackHandler(enabled = customView != null || manuallyFullscreen) { exitFullscreen() }
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView?.evaluateJavascript(
                        "document.querySelector('iframe')?.contentWindow.postMessage(JSON.stringify({event:'command',func:'pauseVideo',args:[]}), 'https://www.youtube.com');", null,
                    )
                    webView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    webView?.onResume()
                    // MainActivity.onResume force-shows the bars; restore fullscreen after it.
                    if (manuallyFullscreen || customView != null) {
                        activity?.window?.decorView?.post {
                            if (manuallyFullscreen || customView != null) setFullscreenWindow(true)
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exitFullscreen()
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            webView = null
            inlineContainer = null
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Trailer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        // Do not clip the Android video surface into a rounded Compose layer.
        Box(Modifier.fillMaxWidth().background(AliflixSurfacePrimary)) {
            Column {
                Box(Modifier.fillMaxWidth().heightIn(min = 200.dp).aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                    when {
                        loadingError -> {
                            Button(onClick = ::openYouTube, shape = RoundedCornerShape(14.dp)) {
                                Icon(Icons.Rounded.OpenInNew, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Open trailer in YouTube")
                            }
                        }
                        !started -> {
                            AsyncImage(
                                model = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                                contentDescription = "Trailer thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize().clickable { started = true },
                            )
                            IconButton(
                                onClick = { started = true },
                                modifier = Modifier.size(58.dp).background(AliflixAccentPrimary, CircleShape),
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play trailer", tint = Color.White)
                            }
                        }
                        else -> {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = { viewContext ->
                                    FrameLayout(viewContext).apply {
                                        setBackgroundColor(android.graphics.Color.BLACK)
                                        inlineContainer = this
                                    }
                                },
                                update = { container ->
                                    if (!manuallyFullscreen) attachPlayer(container, obtainPlayer())
                                },
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Play trailer", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = ::openYouTube) {
                        Icon(Icons.Rounded.OpenInNew, "Open in YouTube", tint = AliflixContentSecondary)
                    }
                    IconButton(onClick = ::enterFullscreen) {
                        Icon(Icons.Rounded.Fullscreen, "Fullscreen trailer", tint = AliflixContentSecondary)
                    }
                }
            }
        }
    }
}
