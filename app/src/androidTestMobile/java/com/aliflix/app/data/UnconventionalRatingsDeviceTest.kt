package com.aliflix.app.data
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.model.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.json.*
import java.io.File
class UnconventionalRatingsDeviceTest {
 @Test fun partNumberAliasesAndUnconventionalMoviesHaveVerifiedSourceRatings(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveRatings")=="true")
  val out=JSONArray()
  val repo=DefaultImdbRatingRepository(null,{url->withContext(Dispatchers.IO){val c=java.net.URL(url).openConnection() as java.net.HttpURLConnection;c.connectTimeout=8000;c.readTimeout=8000;try{c.inputStream.bufferedReader().use{it.readText()}}finally{c.disconnect()}}},HttpImdbGraphQlTransport())
  runBlocking {
   val series=Media(48866,MediaType.TV,"The 100",year="2014",imdbId="tt2661044")
   val episodes=listOf(Episode(3,15,"Perverse Instantiation (1)"),Episode(3,16,"Perverse Instantiation (2)"))
   val imdb=repo.ratingsForEpisodes(series,3,episodes)
   for(e in episodes){val r=requireNotNull(imdb[e.number]);assertEquals(RatingSourceState.VERIFIED,r.state);assertTrue((r.rating?:0.0)>0);out.put(JSONObject().put("episode",e.number).put("imdbId",r.imdbId).put("rating",r.rating))}
   val rt=RottenTomatoesClient().loadEpisodeRatings(series,episodes)
   for(e in episodes){val r=requireNotNull(rt[e.number]);assertTrue(r.toString(),r.state in listOf(RatingSourceState.VERIFIED,RatingSourceState.NOT_RATED));out.put(JSONObject().put("episode",e.number).put("rt",r.rating).put("state",r.state.name))}
   for(m in listOf(Media(807,MediaType.MOVIE,"Se7en",year="1995",imdbId="tt0114369"),Media(10681,MediaType.MOVIE,"WALL·E",year="2008",imdbId="tt0910970"),Media(194,MediaType.MOVIE,"Amélie",year="2001",imdbId="tt0211915"))){val r=repo.ratingFor(m);assertEquals(RatingSourceState.VERIFIED,r.state);out.put(JSONObject().put("title",m.title).put("imdbId",r.identity.imdbId).put("rating",r.rating))}
  }
  File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),"unconventional-ratings.json").writeText(out.toString(2))
 }
}
