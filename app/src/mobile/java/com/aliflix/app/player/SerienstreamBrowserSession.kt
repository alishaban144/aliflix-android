package com.aliflix.app.player

import android.content.Context
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/** Runs the site's own browser flow. It never clicks a challenge or creates a verification token. */
internal object SerienstreamBrowserSession {
    private val gateLock = Mutex()

    suspend fun approve(activity: ComponentActivity, host: FrameLayout, gate: SerienstreamVerificationRequired): Boolean =
        gateLock.withLock {
            withContext(Dispatchers.Main.immediate) {
                var browser: WebView? = null
                try {
                    val embed = withTimeoutOrNull(8_000) {
                        suspendCancellableCoroutine<String?> { result ->
                            browser = createView(activity, gate.episodeUrl, gate.language, gate.playUrl,
                                { if (result.isActive) result.resume(it) }, { if (result.isActive) result.resume(null) })
                            host.addView(browser, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    } ?: return@withContext false
                    SerienstreamNativeCatalog.recordVerifiedEmbed(activity, gate.selection, gate.language, embed)
                    true
                } finally {
                    browser?.let { web ->
                        (web.parent as? ViewGroup)?.removeView(web)
                        if (!web.isDestroyedForProvider) { web.stopLoading(); web.destroy() }
                    }
                }
            }
        }

    // A renderer crash destroys its WebView before invoking the owner callback.
    private var WebView.isDestroyedForProvider: Boolean
        get() = getTag(com.aliflix.app.R.id.serienstream_browser_destroyed) == true
        set(value) { setTag(com.aliflix.app.R.id.serienstream_browser_destroyed, value) }

    fun createView(context: Context, url: String, language: String, playUrl: String,
        onVerified: (String) -> Unit, onUnavailable: () -> Unit): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.userAgentString = SerienstreamNativeCatalog.USER_AGENT
        settings.setSupportMultipleWindows(false)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        val delivered = AtomicBoolean(false)
        fun deliverFrame(raw: String): Boolean {
            val canonical = canonicalVoeEmbed(raw) ?: return false
            if (delivered.compareAndSet(false, true)) post { onVerified(canonical) }
            return true
        }
        webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(web: WebView, detail: RenderProcessGoneDetail): Boolean {
                (web.parent as? ViewGroup)?.removeView(web)
                web.isDestroyedForProvider = true
                web.destroy()
                onUnavailable()
                return true
            }
            override fun shouldOverrideUrlLoading(web: WebView, request: WebResourceRequest): Boolean {
                // Android omits subsequent redirect URLs from shouldInterceptRequest.
                if (deliverFrame(request.url.toString())) return true
                return request.isForMainFrame && request.url.host !in setOf("serienstream.to", "serienstream.cx")
            }
            override fun onLoadResource(web: WebView, resourceUrl: String) { deliverFrame(resourceUrl) }
            override fun onPageFinished(web: WebView, finishedUrl: String) {
                if (runCatching { URI(finishedUrl).path == URI(url).path }.getOrDefault(false)) web.evaluateJavascript("""
                    (() => {
                      document.querySelector('#episode-links [data-provider-name="VOE"][data-language-id="${if (language == "de") "1" else "2"}"]')?.click();
                      if (window.__aliflixProviderContinue) return;
                      let submitted = false;
                      const timer = window.__aliflixProviderContinue = setInterval(() => {
                        const form = document.getElementById('player-prepare-form');
                        const response = form?.querySelector('[name="cf-turnstile-response"]');
                        // Continue only AFTER the site's unmodified security script reports success.
                        // Interactive challenges remain untouched and are shown to the viewer.
                        if (!submitted && response?.value && form.requestSubmit) {
                          submitted = true; clearInterval(timer); form.requestSubmit();
                        }
                      }, 200);
                      setTimeout(() => clearInterval(timer), 120000);
                    })();
                """.trimIndent(), null)
            }
            override fun shouldInterceptRequest(web: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                if (uri.host in setOf("serienstream.to", "serienstream.cx") && uri.path == "/r" && uri.getQueryParameter("t") != null &&
                    uri.getQueryParameter("t") != android.net.Uri.parse(playUrl).getQueryParameter("t")) return emptyFrame()
                if (deliverFrame(uri.toString())) return emptyFrame()
                return null
            }
            private fun emptyFrame() = WebResourceResponse("text/html", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
        }
        loadUrl(url)
    }
}
