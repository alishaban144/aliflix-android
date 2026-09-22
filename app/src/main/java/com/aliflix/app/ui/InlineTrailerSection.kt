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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    var started by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var manuallyFullscreen by remember { mutableStateOf(false) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var loadingError by remember { mutableStateOf(false) }

    fun setFullscreenWindow(fullscreen: Boolean) {
        if (fullscreen) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            if (!wasWindowFullscreen) activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            originalOrientation?.let { activity?.requestedOrientation = it }
        }
    }

    fun exitFullscreen() {
        if (customView != null) {
            val oldView = customView
            customView = null
            (oldView?.parent as? ViewGroup)?.removeView(oldView)
            val callback = customCallback
            customCallback = null
            callback?.onCustomViewHidden()
        }
        manuallyFullscreen = false
        setFullscreenWindow(false)
    }

    fun openYouTube() {
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
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
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
                    val embed = request.url.path?.startsWith("/embed/") == true
                    if (host in setOf("www.youtube.com", "www.youtube-nocookie.com") && embed) return false
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
                    manuallyFullscreen = false
                    customView = view
                    customCallback = callback
                    setFullscreenWindow(true)
                }

                override fun onHideCustomView() { exitFullscreen() }
            }
            // A valid app-identifying HTTPS Referer is required by YouTube embeds.
            // Do not use a fake Android package origin or spoof YouTube as the app.
            loadUrl(
                "https://www.youtube.com/embed/$videoId?autoplay=1&playsinline=1&rel=0&fs=1",
                mapOf("Referer" to "https://github.com/alishaban144/aliflix-android/"),
            )
            webView = this
        }
    }

    BackHandler(enabled = customView != null || manuallyFullscreen) { exitFullscreen() }
    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView?.evaluateJavascript(
                        "document.querySelectorAll('video').forEach(function(v){v.pause()})", null,
                    )
                    webView?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> webView?.onResume()
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
                    IconButton(onClick = {
                        started = true
                        manuallyFullscreen = true
                        setFullscreenWindow(true)
                    }) {
                        Icon(Icons.Rounded.Fullscreen, "Fullscreen trailer", tint = AliflixContentSecondary)
                    }
                }
            }
        }
    }

    // WebChromeClient hands us a separate view for YouTube's own fullscreen control.
    // The app fullscreen control instead moves the EXISTING WebView so playback continues.
    customView?.let { view ->
        Dialog(
            onDismissRequest = ::exitFullscreen,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AndroidView(
                factory = { (view.parent as? ViewGroup)?.removeView(view); view },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
    if (manuallyFullscreen && customView == null) {
        Dialog(
            onDismissRequest = ::exitFullscreen,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AndroidView(
                factory = { obtainPlayer().also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
}
