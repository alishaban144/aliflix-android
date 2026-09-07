package com.aliflix.app.player

import android.content.Intent
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class WebStreamHandoffTest {
    @Test fun actualIframeVideoAndBlobManifestCanBeHandedToNativePlayback() {
        grantNativeFixtureNetworkPermission()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val videoBytes = instrumentation.context.assets.open("cast-test.mp4").use { it.readBytes() }
        val server = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { socket.use { client ->
                    val input = client.getInputStream().bufferedReader()
                    val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                    while (!input.readLine().isNullOrEmpty()) { }
                    val (type, body) = when (path) {
                        "/" -> "text/html" to "<iframe src='/frame' style='width:100%;height:100%'></iframe>".toByteArray()
                        "/frame" -> "text/html" to "<video controls src='$base/video.mp4' style='width:100%'></video>".toByteArray()
                        "/master.m3u8" -> "application/vnd.apple.mpegurl" to "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=10000\nvariant.m3u8\n".toByteArray()
                        else -> "video/mp4" to videoBytes
                    }
                    client.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                } } }
            }
        }
        val progress = AtomicReference<JSONObject?>()
        var view: WebView? = null
        val scenario = ActivityScenario.launch<NativePlayerActivity>(Intent(instrumentation.targetContext, NativePlayerActivity::class.java))
        try {
            scenario.onActivity { activity ->
                assertTrue(WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                val web = WebView(activity).apply { settings.javaScriptEnabled = true; settings.mediaPlaybackRequiresUserGesture = false }
                view = web
                WebViewCompat.addWebMessageListener(web, "AliflixPlaybackProgress", setOf(base)) { _, message, _, _, _ ->
                    runCatching { JSONObject(message.data.orEmpty()) }.getOrNull()?.let { progress.set(it) }
                }
                WebViewCompat.addDocumentStartJavaScript(web, nativeStreamDiscoveryScript() + "\n" + mobileMoviepireProgressBridgeScript() + "\n" + nativePreparationScript(), setOf(base))
                activity.setContentView(FrameLayout(activity).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) })
                web.loadUrl(base)
            }
            val deadline = android.os.SystemClock.elapsedRealtime() + 20_000
            while ((progress.get()?.optDouble("positionSeconds") ?: 0.0) < 1 && android.os.SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
            assertNotNull("A real iframe video must report its stream", progress.get())
            val message = progress.get()!!
            assertEquals("$base/video.mp4", message.getJSONObject("nativeStream").getString("url"))
            assertEquals("$base/frame", message.getJSONObject("nativeStream").getString("referer"))
            assertTrue(message.getDouble("durationSeconds") >= 89)
            val hls = AtomicReference<String?>()
            scenario.onActivity { view!!.evaluateJavascript("fetch('$base/master.m3u8').then(() => setTimeout(() => {window.hlsResult = window.__aliflixNativeStream({currentSrc:'blob:test',mediaKeys:null})},50))", null) }
            val hlsDeadline = android.os.SystemClock.elapsedRealtime() + 5000
            while (hls.get()?.contains("master.m3u8") != true && android.os.SystemClock.elapsedRealtime() < hlsDeadline) {
                scenario.onActivity { view!!.evaluateJavascript("JSON.stringify(window.hlsResult)") { hls.set(it) } }
                Thread.sleep(100)
            }
            assertTrue("MSE/blob playback must resolve to its HLS manifest", hls.get()?.contains("master.m3u8") == true)
            assertFalse(mobileMoviepireProgressBridgeScript().contains("sustainCastPlayback"))
        } finally {
            scenario.onActivity { view?.let { (it.parent as? android.view.ViewGroup)?.removeView(it); it.destroy() } }
            scenario.close(); server.close()
            instrumentation.targetContext.stopService(Intent(instrumentation.targetContext, NativePlaybackService::class.java))
        }
    }
}
