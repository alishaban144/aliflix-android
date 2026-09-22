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
import androidx.compose.foundation.BorderStroke
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

    // Fullscreen content lives in the ACTIVITY window. Moving the live player into a
    // separate Compose Dialog window changes its window token, so YouTube's decoded
    // frames stop arriving: audio keeps playing against a black surface.
    fun showFullscreenContent(view: View) {
        val content = activity?.window?.decorView
            ?.findViewById<FrameLayout>(android.R.id.content) ?: return
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
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view)
    }

    fun exitFullscreen() {
        val wasActive = customView != null || manuallyFullscreen
        if (customView != null) {
            val oldView = customView
            customView = null
            (oldView?.parent as? ViewGroup)?.removeView(oldView)
            val callback = customCallback
            customCallback = null
            callback?.onCustomViewHidden()
        }
        manuallyFullscreen = false
        if (wasActive) setFullscreenWindow(false)
        // Detach the player from the overlay right away; the inline AndroidView
        // re-attaches it during the recomposition this state change schedules.
        webView?.let { player ->
            (player.parent as? ViewGroup)?.takeIf { it === fullscreenContainer }?.removeView(player)
        }
        fullscreenContainer?.let { overlay ->
            if (overlay.childCount == 0) (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
        fullscreenContainer = null
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

    fun obtainPlayer(): WebView {
        webView?.let { return it }
        return WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            // Never force LAYER_TYPE_HARDWARE here: a mandated hardware layer makes
            // YouTube's decoded video frames composite to black while audio plays.
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loadingError = true
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400) loadingError = true
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
                    customView = view
                    customCallback = callback
                    setFullscreenWindow(true)
                    showFullscreenContent(view)
                }

                override fun onHideCustomView() { exitFullscreen() }
            }
            // A real HTTPS base URL plus YouTube's own sized viewport keeps the frame
            // visible; a synthetic package origin lets YouTube render a black embed.
            val origin = "https://github.com"
            loadDataWithBaseURL(
                origin,
                """
                <!doctype html><html><head>
                <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
                <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
                iframe{position:absolute;inset:0;width:100%;height:100%;border:0;display:block}</style>
                </head><body>
                <iframe src="https://www.youtube.com/embed/$videoId?autoplay=1&amp;playsinline=1&amp;controls=1&amp;fs=1&amp;rel=0&amp;enablejsapi=1&amp;origin=$origin"
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
        showFullscreenContent(obtainPlayer())
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
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Trailer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AliflixSurfacePrimary,
            border = BorderStroke(1.dp, AliflixBorderStrong),
        ) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
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
                        !manuallyFullscreen -> {
                            AndroidView(
                                modifier = Modifier.fillMaxSize(),
                                factory = {
                                    obtainPlayer().also { view ->
                                        (view.parent as? ViewGroup)?.removeView(view)
                                    }
                                },
                            )
                        }
                        else -> Box(Modifier.fillMaxSize().background(Color.Black))
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
