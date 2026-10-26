package com.aliflix.app.player

import android.content.Intent
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.aliflix.app.data.PlaybackProgressStore
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeResolverSilenceTest {
    @Test fun providerCannotUnmutePreparationOrTakeFocus() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<ComponentActivity>(Intent(context, ComponentActivity::class.java), nativePhoneLaunchOptions()).use { scenario ->
            lateinit var controller: WebPlayerController
            lateinit var web: WebView
            fun find(view: android.view.View): WebView? = if (view is WebView) view else
                (view as? ViewGroup)?.let { group -> (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) } }
            scenario.onActivity { activity ->
                controller = WebPlayerController(activity, PlaybackProgressStore(activity), nativePreparation = true)
                val host = controller.viewFor(PlaybackSelection(Media(1, MediaType.MOVIE, "Resolver fixture"),
                    source = PlaybackSource(MobilePlaybackProvider.FLIXER)))
                host.alpha = 0f
                activity.setContentView(host)
                web = requireNotNull(find(host))
                web.stopLoading()
                controller.setVisible(true)
                web.loadDataWithBaseURL("https://flixer.gd/", "<html><body><video autoplay></video></body></html>", "text/html", "utf-8", null)
            }
            try {
                Thread.sleep(1_000)
                val latch = CountDownLatch(1)
                var result = ""
                scenario.onActivity {
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.MUTE_AUDIO)) assertTrue(WebViewCompat.isAudioMuted(web))
                    web.evaluateJavascript("(()=>{const v=document.querySelector('video');v.muted=false;v.volume=1;return JSON.stringify({muted:v.muted,volume:v.volume})})()") {
                        result = it; latch.countDown()
                    }
                }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertTrue("Provider script could unmute a preparation video: $result", result.contains("true"))
                assertTrue(result.contains("0"))
                scenario.onActivity { assertEquals(0f, (web.parent as android.view.View).alpha, 0f) }
            } finally { scenario.onActivity { controller.destroy() } }
        }
    }
}
