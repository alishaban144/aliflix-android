package com.aliflix.app.ui.discover

import com.aliflix.app.recommendation.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DiscoverFilterStoreTest {
    private fun row(id:Int,type:String)=V3RecommendationResult.fromJson(JSONObject().put("tmdbId",id).put("mediaType",type).put("title","Owned fixture $id").put("releaseDate","2024-01-01"))
    @Test fun aFailedMixedTypePageResumesItsSiblingAndNeverReplacesVisibleResults()=runBlocking {
        val calls=mutableListOf<Pair<String,String?>>()
        var failSeries=true
        val store=DiscoverFilterStore { request ->
            calls+=request.mediaType to request.cursor
            if(request.mediaType=="tv"&&failSeries){failSeries=false;error("Owned transient failure")}
            val next=request.mediaType=="movie"&&request.cursor==null
            V3RecommendationResponse(request.requestId,listOf(row(if(request.cursor==null)1 else 2,request.mediaType)),3,if(next)"movie-next" else null,next)
        }
        val spec=CatalogDiscoverySpec(RecommendationMediaKind.MOVIE,yearMinimum=2020)
        val state=store.session(spec,"All","")
        store.load(spec,"All","")
        assertTrue(state.error);assertTrue(state.items.isEmpty())
        store.load(spec,"All","")
        assertEquals(listOf("movie" to null,"tv" to null,"tv" to null),calls)
        assertEquals(listOf("movie:1","tv:1"),state.items.map{it.key})
        val initial=state.items.toList()
        store.load(spec,"All","",more=true)
        assertEquals(initial,state.items.take(2))
        assertEquals("movie:2",state.items.last().key)
        assertFalse(state.hasMore)
    }
    @Test fun seriesStatusDoesNotLeakIntoMovieRequestsAndQueriesKeepTheirFilters()=runBlocking {
        val requests=mutableListOf<V3RecommendationRequest>()
        val store=DiscoverFilterStore { request ->requests+=request;V3RecommendationResponse(request.requestId,emptyList(),0,null,false)}
        val spec=CatalogDiscoverySpec(RecommendationMediaKind.SERIES,yearMinimum=2019,requiredStatus="Ended")
        store.load(spec,"Movies","space station")
        store.load(spec,"Series","space station")
        assertNull(requests.first{it.mediaType=="movie"}.filters.seriesStatus)
        assertEquals("ended",requests.first{it.mediaType=="tv"}.filters.seriesStatus)
        assertTrue(requests.all{it.query=="space station"&&it.filters.minimumYear==2019})
    }
    @Test fun allWithAnEndedSeriesConstraintCannotLeakUnrestrictedMovies()=runBlocking {
        val types=mutableListOf<String>()
        val store=DiscoverFilterStore { request ->types+=request.mediaType;V3RecommendationResponse(request.requestId,emptyList(),0,null,false)}
        store.load(CatalogDiscoverySpec(RecommendationMediaKind.SERIES,requiredStatus="Ended"),"All","")
        assertEquals(listOf("tv"),types)
    }
    @Test fun mixedCatalogueRequestsUseTheCorrectTvGenreTaxonomy() {
        val spec=CatalogDiscoverySpec(RecommendationMediaKind.MOVIE,includedGenres=listOf("Science Fiction","Action"),excludedGenres=listOf("War"))
        val tv=spec.forCatalogueKind(RecommendationMediaKind.SERIES)
        assertEquals(listOf("Sci-Fi & Fantasy","Action & Adventure"),tv.includedGenres)
        assertEquals(listOf("War & Politics"),tv.excludedGenres)
    }
}
