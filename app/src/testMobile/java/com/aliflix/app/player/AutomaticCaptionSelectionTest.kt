package com.aliflix.app.player

import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticCaptionSelectionTest {
    private val movie = PlaybackSelection(Media(218, MediaType.MOVIE, "Movie"))
    @Test fun aSplitHalfCannotBecomeTheAutomaticFullMovieCaptionFile() {
        fun cues(end: Double) = listOf(SubtitleCue(20.0,24.0,"Someone painted purple windows"),
            SubtitleCue(end-4,end,"Several foxes crossed snowy fields"))
        assertFalse(automaticCaptionCoversMovie(cues(3200.0),movie,6_460_000))
        assertTrue(automaticCaptionCoversMovie(cues(6000.0),movie,6_460_000))
        assertFalse(automaticCaptionCoversMovie(cues(6000.0).map { it.copy(startSeconds=it.startSeconds.coerceAtLeast(3300.0)) },movie,6_460_000))
        assertTrue(automaticCaptionCoversMovie(cues(3200.0),movie.copy(media=movie.media.copy(type=MediaType.TV)),6_460_000))
        assertTrue(automaticCaptionCoversMovie(cues(200.0),movie,0))
    }
    @Test fun completeMatchingFrameRateIsRankedButNeverCertifiesTiming() {
        fun track(id:String,file:String,fps:String) = SubtitleTrack(id,"en","English","Release",file,false,"srt",fps,id)
        val split = track("split","Movie.CD1.srt","23.976")
        val pal = track("pal","Movie.srt","25")
        val ntsc = track("ntsc","Movie.full.srt","23.976")
        assertEquals(listOf(ntsc,pal,split),rankCompleteSubtitleCandidates(listOf(split,pal,ntsc),23.976f))
        assertEquals(listOf(ntsc,split,pal),interleaveSubtitleCandidates(listOf(ntsc,pal),listOf(split,ntsc)))
    }
}
