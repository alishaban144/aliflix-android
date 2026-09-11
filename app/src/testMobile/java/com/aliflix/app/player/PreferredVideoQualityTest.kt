package com.aliflix.app.player

import androidx.media3.common.*
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test

class PreferredVideoQualityTest {
    private fun tracks() = Tracks(ImmutableList.of(Tracks.Group(TrackGroup(
        Format.Builder().setSampleMimeType("video/avc").setWidth(1920).setHeight(1080).setAverageBitrate(4000000).build(),
        Format.Builder().setSampleMimeType("video/avc").setWidth(640).setHeight(360).build(),
        Format.Builder().setSampleMimeType("video/avc").setWidth(320).setHeight(180).build()
    ), true, intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED, C.FORMAT_UNSUPPORTED_TYPE), booleanArrayOf(true, false, false))))
    @Test fun lowChoosesLowestSupportedResolutionEvenWithoutBitrates() {
        assertEquals(listOf(1), lowestVideoTrack(tracks())!!.trackIndices)
    }
    @Test fun autoClearsPreviousLowOverrideForNewServer() {
        val low = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon().addOverride(lowestVideoTrack(tracks())!!).setForceLowestBitrate(true).build()
        val auto = preferredQualityParameters(low, PreferredVideoQuality.AUTO)
        assertFalse(auto.forceLowestBitrate); assertTrue(auto.overrides.isEmpty())
        assertTrue(preferredQualityParameters(auto, PreferredVideoQuality.LOW).forceLowestBitrate)
    }
    @Test fun noVideoTracksDoesNotOverrideAudio() { assertNull(lowestVideoTrack(Tracks.EMPTY)) }
}
