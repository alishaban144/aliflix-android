package com.aliflix.app.player

import androidx.media3.common.Format
import org.junit.Assert.*
import org.junit.Test

class NativeAudioDecoderIdentityTest {
    @Test fun enrichedDecoderMetadataStillConfirmsTheSameSoundtrack() {
        val manifest=Format.Builder().setId("audio0:Deutsch").setLanguage("de").setSampleMimeType("audio/mp4a-latm").build()
        val decoder=manifest.buildUpon().setId("elementary:1").setLanguage("deu").setCodecs("mp4a.40.2").setChannelCount(2).setSampleRate(48000).build()
        assertTrue(nativeAudioDecoderMatches(manifest,decoder))
        assertFalse(nativeAudioDecoderMatches(manifest,decoder.buildUpon().setLanguage("en").build()))
        assertFalse(nativeAudioDecoderMatches(manifest,decoder.buildUpon().setSampleMimeType("audio/ac3").build()))
    }
    @Test fun anonymousOrDifferentNamedTracksAreNotAssumedToBeTheRequestedAudio() {
        val selected=Format.Builder().setId("track2").setLabel("Director commentary").build()
        assertFalse(nativeAudioDecoderMatches(selected,Format.Builder().setId("track1").setLabel("Main soundtrack").build()))
        assertFalse(nativeAudioDecoderMatches(Format.Builder().build(),Format.Builder().build()))
        assertTrue(nativeAudioDecoderMatches(selected,selected.buildUpon().setChannelCount(2).build()))
    }
}
