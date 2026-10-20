package com.aliflix.app.data

import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.RecommendationAiClient
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

class RequestedMetadataDeviceTest {
    @Test fun exactRequestedIdentitiesHaveIndependentEpisodesAndHonestRatings() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveRequestedMetadata")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val api=RecommendationAiClient(BuildConfig.RECOMMENDATION_AI_BASE_URL)
        val repo=DefaultImdbRatingRepository(null,{ url->withContext(Dispatchers.IO) {
            val connection=java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout=6000;connection.readTimeout=6000
            try { connection.inputStream.bufferedReader().use { it.readText() } } finally { connection.disconnect() }
        } },HttpImdbGraphQlTransport())
        val rows=JSONArray()
        runBlocking {
            for(media in listOf(Media(180,MediaType.MOVIE,"Minority Report",year="2002"),Media(67415,MediaType.MOVIE,"Incoherence",year="2025"),Media(86328,MediaType.TV,"Don't Hug Me I'm Scared",year="2011"))) {
                val details=api.getTitleDetails(media.type.routeName,media.id)
                val identified=media.copy(imdbId=details.imdbId)
                val rating=withTimeout(25000) { repo.ratingFor(identified) }
                val rt=RottenTomatoesClient().loadRating(identified)
                rows.put(JSONObject().put("title",media.title).put("imdbId",identified.imdbId).put("imdb",rating.rating).put("imdbState",rating.state.name).put("rt",rt.rating).put("rtState",rt.state.name))
                File(context.getExternalFilesDir(null),"metadata-177.json").writeText(rows.toString(2))
                assertTrue("IMDb ${media.title}: ${rating.state}",rating.rating!=null&&rating.rating>0)
            }
            val episodes=MobileEpisodeRepository(context,api).episodes(86328,1)
            assertEquals(6,episodes.size);assertEquals("Creativity",episodes.first().title)
        }
    }
}
