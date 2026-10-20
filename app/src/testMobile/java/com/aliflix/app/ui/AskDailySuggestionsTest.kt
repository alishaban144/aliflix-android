package com.aliflix.app.ui
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.discover.retainedAskDailySuggestions
import com.aliflix.app.ui.discover.parseAskDailySuggestions
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.*
class AskDailySuggestionsTest {
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
}
