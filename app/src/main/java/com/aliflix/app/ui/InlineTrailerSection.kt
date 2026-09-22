package com.aliflix.app.ui

import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
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

/** YouTube trailers must remain embedded in YouTube's player, not passed to ExoPlayer. */
@Composable
internal fun InlineTrailerSection(media: Media) {
    val video = media.trailerKey?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{11}")) } ?: return
    key(media.key, video) { TrailerPlayer(video) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerPlayer(video: String) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val owner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(false) }
    var player by remember { mutableStateOf<WebView?>(null) }
    var customView by remember { mutableStateOf<View?>(null) }
    var customCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    val previousOrientation = remember(activity) { activity?.requestedOrientation }

    fun dismissFullscreen() {
        val view = customView ?: return
        customView = null
        (view.parent as? ViewGroup)?.removeView(view)
        val callback = customCallback
        customCallback = null
        callback?.onCustomViewHidden()
        if (previousOrientation != null) activity?.requestedOrientation = previousOrientation
    }

    BackHandler(enabled = customView != null) { dismissFullscreen() }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    player?.evaluateJavascript("document.querySelector('video')?.pause()", null)
                    player?.onPause()
                }
                Lifecycle.Event.ON_RESUME -> player?.onResume()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            dismissFullscreen()
            player?.apply {
                stopLoading()
                loadUrl("about:blank")
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
            player = null
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Trailer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AliflixSurfacePrimary,
            border = BorderStroke(1.dp, AliflixBorderStrong),
        ) {
            Column {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    if (!started) {
                        AsyncImage(
                            model = "https://i.ytimg.com/vi/$video/hqdefault.jpg",
                            contentDescription = "Trailer thumbnail",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clickable { started = true },
                        )
                        IconButton(
                            onClick = { started = true },
                            modifier = Modifier.align(Alignment.Center).size(58.dp).background(AliflixAccentPrimary, CircleShape),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "Play trailer", tint = Color.White)
                        }
                    } else {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = {
                                WebView(context).apply {
                                    setBackgroundColor(android.graphics.Color.BLACK)
                                    setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.mediaPlaybackRequiresUserGesture = false
                                    settings.allowFileAccess = false
                                    settings.allowContentAccess = false
                                    webViewClient = WebViewClient()
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                            if (customView != null) {
                                                callback.onCustomViewHidden()
                                                return
                                            }
                                            customView = view
                                            customCallback = callback
                                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                        }
                                        override fun onHideCustomView() { dismissFullscreen() }
                                    }
                                    // A real HTTPS document and Referer avoid the synthetic package
                                    // origin, which YouTube can reject with a black embedded frame.
                                    loadUrl(
                                        "https://www.youtube.com/embed/$video?autoplay=1&playsinline=1&enablejsapi=1&rel=0&fs=1",
                                        mapOf("Referer" to "https://www.youtube.com/"),
                                    )
                                    player = this
                                }
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Trailer", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = {
                        if (!started) started = true
                        else player?.evaluateJavascript(
                            "document.querySelector('iframe')?.requestFullscreen?.() || document.querySelector('video')?.requestFullscreen?.()",
                            null,
                        )
                    }) {
                        Icon(Icons.Rounded.Fullscreen, "Fullscreen trailer", tint = AliflixContentSecondary)
                    }
                }
            }
        }
    }
    customView?.let { view ->
        Dialog(
            onDismissRequest = { dismissFullscreen() },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            AndroidView(
                factory = {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view
                },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
    }
}
