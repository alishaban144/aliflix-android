package com.aliflix.app.player
import com.aliflix.app.model.*
import org.junit.Assert.*
import org.junit.Test

class NativeSelectionRoundTripTest {
 @Test fun aMovieDoesNotBecomeSeasonOneEpisodeOneDuringPlayerOrDownloadHandoff(){
  val movie=PlaybackSelection(Media(184,MediaType.MOVIE,"Owned movie"))
  val restored=nativeSelection(movie.nativeJson())
  assertNull(restored.seasonNumber);assertNull(restored.episodeNumber)
  assertEquals(movie.media,restored.media)
 }
 @Test fun anExplicitSpecialStaysSeasonZeroAcrossTheSameHandoff(){
  val episode=PlaybackSelection(Media(184,MediaType.TV,"Owned series"),seasonNumber=0,episodeNumber=4)
  val restored=nativeSelection(episode.nativeJson())
  assertEquals(0,restored.seasonNumber);assertEquals(4,restored.episodeNumber)
 }
}
