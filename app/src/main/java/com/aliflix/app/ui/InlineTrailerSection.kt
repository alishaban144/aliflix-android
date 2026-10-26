package com.aliflix.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.aliflix.app.model.Media
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixSurfaceSecondary

@Composable
internal fun InlineTrailerSection(media: Media) {
    val videoId = media.trailerKey?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) } ?: return
    key(media.key, videoId) { TrailerPlayer(videoId, media) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TrailerPlayer(videoId: String, media: Media) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var started by remember(videoId) { mutableStateOf(false) }
    var loadingError by remember(videoId) { mutableStateOf(false) }
    var webView by remember(videoId) { mutableStateOf<WebView?>(null) }

    fun attachPlayer(container: FrameLayout, player: WebView) {
        if (player.parent !== container) {
            (player.parent as? ViewGroup)?.removeView(player)
            container.removeAllViews()
            container.addView(
                player,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        player.visibility = View.VISIBLE
        player.requestLayout()
        player.invalidate()
    }

    fun destroyPlayer() {
        webView?.apply {
            stopLoading()
            loadUrl("about:blank")
            (parent as? ViewGroup)?.removeView(this)
            destroy()
        }
        webView = null
    }

    fun startTrailer() {
        com.aliflix.app.data.ActivityShelves(context).record("trailers", media)
        loadingError = false
        started = true
    }

    fun obtainPlayer(): WebView {
        webView?.let { return it }
        return WebView(context).apply {
            setBackgroundColor(android.graphics.Color.BLACK)

            // HTML5/YouTube video must stay on the activity's hardware compositor.
            // Do not force the WebView into a software or extra offscreen layer.
            setLayerType(View.LAYER_TYPE_NONE, null)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.setSupportMultipleWindows(false)
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                private fun fail(view: WebView) {
                    view.post {
                        if (webView === view) {
                            destroyPlayer()
                            loadingError = true
                            started = false
                        }
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) fail(view)
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    response: WebResourceResponse,
                ) {
                    if (request.isForMainFrame && response.statusCode >= 400) fail(view)
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val host = request.url.host?.lowercase()
                    val path = request.url.path.orEmpty()
                    val allowedMainFrame = when {
                        host == context.packageName.lowercase() -> true
                        host in setOf("www.youtube.com", "www.youtube-nocookie.com", "m.youtube.com") &&
                            path.startsWith("/embed/") -> true
                        host in setOf("consent.youtube.com", "consent.google.com", "accounts.google.com") -> true
                        else -> false
                    }
                    return !allowedMainFrame
                }
            }

            val appOrigin = "https://${context.packageName}"
            val encodedOrigin = Uri.encode(appOrigin)
            val html = """
                <!doctype html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1">
                    <meta name="referrer" content="strict-origin-when-cross-origin">
                    <style>
                        html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}
                        iframe{position:absolute;inset:0;width:100%;height:100%;border:0;display:block}
                    </style>
                </head>
                <body>
                    <iframe
                        src="https://www.youtube.com/embed/$videoId?autoplay=1&amp;playsinline=1&amp;controls=1&amp;fs=0&amp;rel=0&amp;enablejsapi=1&amp;origin=$encodedOrigin&amp;widget_referrer=$encodedOrigin"
                        referrerpolicy="strict-origin-when-cross-origin"
                        allow="autoplay; encrypted-media; picture-in-picture"
                    ></iframe>
                </body>
                </html>
            """.trimIndent()

            loadDataWithBaseURL(
                "$appOrigin/",
                html,
                "text/html",
                "UTF-8",
                null,
            )
            webView = this
        }
    }

    DisposableEffect(lifecycleOwner, videoId) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webView?.onPause()
                Lifecycle.Event.ON_RESUME -> webView?.apply {
                    onResume()
                    post {
                        requestLayout()
                        invalidate()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            destroyPlayer()
        }
    }

    DetailInfoSection(title = "Trailer") {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(AliflixSurfaceSecondary, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (!started) {
                AsyncImage(
                    model = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                    contentDescription = "Trailer thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(18.dp))
                        .clickable(onClick = ::startTrailer),
                )
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f))
                        .clickable(onClick = ::startTrailer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = if (loadingError) "Retry trailer" else "Play trailer",
                        tint = AliflixAccentPrimary,
                        modifier = Modifier.size(32.dp),
                    )
                }
            } else {
                // Intentionally do not clip the native video surface. Clipping/extra
                // Compose layers can leave HTML5 video black while audio keeps playing.
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(1.dp),
                    factory = { viewContext ->
                        FrameLayout(viewContext).apply {
                            setBackgroundColor(android.graphics.Color.BLACK)
                            attachPlayer(this, obtainPlayer())
                        }
                    },
                    update = { container ->
                        attachPlayer(container, obtainPlayer())
                    },
                )
            }
        }
    }
}
