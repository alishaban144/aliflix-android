package com.aliflix.app.data
import org.junit.Test
import org.junit.Assert.*
class EpisodeTitleIdentityTest {
 @Test fun partVariantsRetainExactIdentity(){
  val one=normalizedEpisodeRatingTitle("Perverse Instantiation (1)")
  for(name in listOf("Perverse Instantiation: Part One","Perverse Instantiation - Part I","Perverse Instantiation: Pt. 1")) assertEquals(one,normalizedEpisodeRatingTitle(name))
  assertNotEquals(one,normalizedEpisodeRatingTitle("Perverse Instantiation: Part Two"))
 }
 @Test fun rtMayOmitPartSuffixButNumbersAreNotGenerallyStripped(){
  assertEquals("perverse instantiation",episodeRatingTitleBase("Perverse Instantiation (2)"))
  assertEquals("ocean s 11",episodeRatingTitleBase("Ocean's 11"))
  assertEquals("1917",episodeRatingTitleBase("1917"))
 }
 @org.junit.Test fun currentRtIdentityUsesExactEpisodeAndSeasonWhenPartSuffixAndNumberAreAbsent(){
  val client=RottenTomatoesClient { "" }
  val media=com.aliflix.app.model.Media(48866,com.aliflix.app.model.MediaType.TV,"The 100",year="2014",imdbId="tt2661044")
  val episode=com.aliflix.app.model.Episode(3,15,"Perverse Instantiation (1)")
  val html="""<script type="application/ld+json">{"@type":"TVEpisode","name":"Perverse Instantiation","partOfSeries":{"name":"The 100"},"partOfSeason":{"seasonNumber":"3"}}</script>"""
  org.junit.Assert.assertTrue(client.isEpisodeIdentityVerified(html,"https://www.rottentomatoes.com/tv/the_100/s03/e15",media,episode))
  org.junit.Assert.assertFalse(client.isEpisodeIdentityVerified(html,"https://www.rottentomatoes.com/tv/the_100/s03/e16",media,episode))
  org.junit.Assert.assertFalse(client.isEpisodeIdentityVerified(html.replace("\"3\"","\"2\""),"https://www.rottentomatoes.com/tv/the_100/s03/e15",media,episode))
 }
}
