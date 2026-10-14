@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
package com.aliflix.app.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.aliflix.app.model.*
import com.aliflix.app.player.NativePlaybackRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadQualityPreferenceTest {
    private fun discovery(vararg heights: Int): DownloadDiscovery {
        val source = PreparedDownload(PlaybackSelection(Media(1,MediaType.MOVIE,"Fixture")),
            NativePlaybackRequest("https://fixture.test/video","video/mp4","","","","Fixture",0,false),
            heights.map { DownloadQuality("${it}p",it,0,false,emptyList()) },false)
        return DownloadDiscovery(source.qualities.map { DownloadOption(it,source) },true)
    }
    @Test fun tiersUseAvailableOptionsRatherThanResolutionThresholds() {
        val d = discovery(930,560,320)
        assertEquals(320,preferredDownloadOption(d,DownloadQualityTier.LOW)!!.quality.height)
        assertEquals(560,preferredDownloadOption(d,DownloadQualityTier.MEDIUM)!!.quality.height)
        assertEquals(930,preferredDownloadOption(d,DownloadQualityTier.HIGH)!!.quality.height)
        val highOnlyVideo = discovery(4320,2160,1440)
        assertEquals(1440,preferredDownloadOption(highOnlyVideo,DownloadQualityTier.LOW)!!.quality.height)
        assertEquals(2160,preferredDownloadOption(highOnlyVideo,DownloadQualityTier.MEDIUM)!!.quality.height)
        DownloadQualityTier.entries.forEach { tier ->
            assertEquals(560,preferredDownloadOption(discovery(560),tier)!!.quality.height)
            assertEquals(0,preferredDownloadOption(discovery(0),tier)!!.quality.height)
            assertNull(preferredDownloadOption(discovery(),tier))
        }
        assertEquals(320,preferredDownloadOption(discovery(930,320),DownloadQualityTier.MEDIUM)!!.quality.height)
    }
    @Test fun defaultsAreRelativePerEpisodeWhileExplicitChoicesWin() {
        val first = discovery(1080,720,480)
        val second = discovery(2160,1440,1080)
        assertEquals(720,preferredDownloadQuality(first.prepared(),DownloadQualityTier.MEDIUM)!!.height)
        assertEquals(1440,preferredDownloadQuality(second.prepared(),DownloadQualityTier.MEDIUM)!!.height)
        assertEquals(480,selectedDownloadOption(first,DownloadQualityTier.HIGH,480)!!.quality.height)
        val progressive = discovery(1080,930,720,560,320)
        assertEquals(560,selectedDownloadOption(progressive,DownloadQualityTier.HIGH,560)!!.quality.height)
        assertEquals(720,selectedDownloadOption(first,DownloadQualityTier.MEDIUM,999)!!.quality.height)
    }
    @Test fun migrationIsPersistentIdempotentAndKeepsStreamingPreferencesSeparate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val p = context.getSharedPreferences("download-tier-test",Context.MODE_PRIVATE)
        mapOf(360 to DownloadQualityTier.LOW,480 to DownloadQualityTier.LOW,720 to DownloadQualityTier.MEDIUM,
            1080 to DownloadQualityTier.HIGH,2160 to DownloadQualityTier.HIGH).forEach { (height,tier) ->
            p.edit().clear().putInt("quality",height).putString("preferredVideoQuality","LOW").commit()
            assertEquals(tier,readDownloadQualityTier(p))
            p.edit().putInt("quality",360).commit()
            assertEquals(tier,readDownloadQualityTier(p))
            assertEquals("LOW",p.getString("preferredVideoQuality",null))
        }
        p.edit().clear().commit()
        assertEquals(DownloadQualityTier.MEDIUM,readDownloadQualityTier(p))
        p.edit().putString("qualityTier","future-value").putInt("quality",1080).commit()
        assertEquals(DownloadQualityTier.HIGH,readDownloadQualityTier(p))
    }
}
