package com.aliflix.app.ui
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.discover.retainedAskDailySuggestions
import com.aliflix.app.ui.discover.parseAskDailySuggestions
import com.aliflix.app.ui.discover.researchBackedAskDailySuggestions
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class AskDailySuggestionsTest {
 @Test fun hourlyWindowsChangeWithoutRepeatingIdentitiesOrMovingWithinTheHour() {
  val pool=(1..24).map{com.aliflix.app.model.Media(it,MediaType.MOVIE,"Film $it")}
  val first=com.aliflix.app.ui.discover.rotatingAskDailySuggestions(pool,3_600_001,137)
  val same=com.aliflix.app.ui.discover.rotatingAskDailySuggestions(pool,7_199_999,137)
  val next=com.aliflix.app.ui.discover.rotatingAskDailySuggestions(pool,7_200_000,137)
  assertEquals(first,same);assertEquals(8,first.size);assertTrue(first.none{it in next})
  assertEquals(pool.toSet(),(0..2).flatMap{com.aliflix.app.ui.discover.rotatingAskDailySuggestions(pool,it*3_600_000L,137)}.toSet())
  assertNotEquals(first,com.aliflix.app.ui.discover.rotatingAskDailySuggestions(pool,86_400_000L+3_600_001,137))
 }
 private val movie="""{"results":[{"tmdbId":1,"mediaType":"movie","title":"Verified film"}]}"""
 private val bundle="""{"movie":$movie,"tv":{"results":[{"tmdbId":2,"mediaType":"tv","title":"Verified series"}]}}"""
 @Test fun offlineOrCorruptCacheUsesTheCorrectBundledCatalogue() {
  assertEquals("Verified film",retainedAskDailySuggestions(MediaType.MOVIE,{error("offline")},{bundle}).single().title)
  assertEquals("Verified series",retainedAskDailySuggestions(MediaType.TV,{"broken"},{bundle}).single().title)
  assertEquals("Verified series",retainedAskDailySuggestions(MediaType.TV,{movie},{bundle}).single().title)
 }
 @Test fun lastGoodCacheSurvivesAnUnavailableBundleOrNetwork() {
  assertEquals("Verified film",retainedAskDailySuggestions(MediaType.MOVIE,{movie},{error("unavailable")}).single().title)
 }
 @Test fun malformedRowsCannotBlankValidSuggestions() {
  val json=JSONObject("""{"results":[{}, {"tmdbId":1,"mediaType":"movie","title":"Verified film"}, {"tmdbId":1,"mediaType":"movie","title":"Duplicate"}]}""")
  assertEquals(1,parseAskDailySuggestions(json,MediaType.MOVIE).size)
 }
 @Test fun catalogueBrowsingCannotMasqueradeAsAResearchedSurprisePool() {
  assertFalse(researchBackedAskDailySuggestions(JSONObject(movie),MediaType.MOVIE))
  val researched=JSONObject(movie).put("source","research")
  researched.getJSONArray("results").getJSONObject(0).put("finalScore",0.8).put("retrievalSources",org.json.JSONArray().put("https://en.wikipedia.org/wiki/Film"))
  assertTrue(researchBackedAskDailySuggestions(researched,MediaType.MOVIE))
  assertFalse(researchBackedAskDailySuggestions(researched,MediaType.TV))
  researched.put("source","catalogue")
  assertFalse(researchBackedAskDailySuggestions(researched,MediaType.MOVIE))
 }
}
