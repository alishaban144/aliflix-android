package com.aliflix.app.ui

import android.annotation.SuppressLint
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun InlineTrailerSection(media: Media) {
    val video = media.trailerKey?.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{11}")) } ?: return
    key(media.key, video) { TrailerPlayer(video) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerPlayer(video: String) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var started by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var fullscreenView by remember { mutableStateOf<View?>(null) }
    var fullscreenCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    fun closeFullscreen() {
        fullscreenView?.let { (it.parent as? ViewGroup)?.removeView(it) }
        fullscreenView = null
        fullscreenCallback?.onCustomViewHidden()
        fullscreenCallback = null
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                webView?.evaluateJavascript("if(window.player)player.pauseVideo();", null)
                webView?.onPause()
            } else if (event == Lifecycle.Event.ON_RESUME) webView?.onResume()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            closeFullscreen()
            webView?.apply { stopLoading(); (parent as? ViewGroup)?.removeView(this); destroy() }
            webView = null
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trailer", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Surface(shape = CircleShape, color = AliflixAccentPrimary.copy(alpha = .18f)) {
                Text("✓ Official", style = MaterialTheme.typography.labelSmall, color = AliflixAccentSecondary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Surface(shape = RoundedCornerShape(20.dp), color = AliflixSurfacePrimary,
            border = BorderStroke(1.dp, AliflixBorderStrong)) {
            Column {
                if (!started) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clickable { started = true }) {
                        AsyncImage("https://i.ytimg.com/vi/$video/hqdefault.jpg", "Play official trailer",
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                } else AndroidView(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp).aspectRatio(16f / 9f),
                    factory = {
                        WebView(context).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.mediaPlaybackRequiresUserGesture = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean = request.isForMainFrame
                            }
                            webChromeClient = object : WebChromeClient() {
                                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                                    if (fullscreenView != null) { callback.onCustomViewHidden(); return }
                                    fullscreenView = view; fullscreenCallback = callback
                                }
                                override fun onHideCustomView() { closeFullscreen() }
                            }
                            val origin = "https://${context.packageName}"
                            loadDataWithBaseURL(origin, """
                                <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
                                <style>html,body,#player{margin:0;width:100%;height:100%;background:#000;overflow:hidden}</style></head>
                                <body><div id="player"></div><script src="https://www.youtube.com/iframe_api"></script>
                                <script>var player;function onYouTubeIframeAPIReady(){player=new YT.Player('player',{
                                videoId:'$video',playerVars:{playsinline:1,autoplay:1,rel:0,origin:'$origin'},
                                events:{onReady:function(e){e.target.playVideo();}}});}</script></body></html>
                            """.trimIndent(), "text/html", "UTF-8", null)
                            webView = this
                        }
                    },
                )
                Row(Modifier.fillMaxWidth().clickable {
                    if (!started) started = true else webView?.evaluateJavascript("if(window.player)player.playVideo();", null)
                }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(AliflixAccentPrimary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, "Play trailer", tint = Color.White)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Play trailer", fontWeight = FontWeight.SemiBold)
                        Text("Official trailer", style = MaterialTheme.typography.bodySmall, color = AliflixContentSecondary)
                    }
                    IconButton(onClick = {
                        if (!started) started = true
                        else webView?.evaluateJavascript("var f=document.querySelector('iframe');if(f&&f.requestFullscreen)f.requestFullscreen();", null)
                    }) { Icon(Icons.Rounded.Fullscreen, "Fullscreen", tint = AliflixContentSecondary) }
                }
            }
        }
    }
    fullscreenView?.let { view ->
        Dialog(onDismissRequest = { closeFullscreen() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            BackHandler { closeFullscreen() }
            AndroidView(factory = { (view.parent as? ViewGroup)?.removeView(view); view }, modifier = Modifier.fillMaxSize().background(Color.Black))
        }
    }
}
