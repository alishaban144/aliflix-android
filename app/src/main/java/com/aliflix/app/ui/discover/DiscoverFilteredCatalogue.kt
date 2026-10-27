package com.aliflix.app.ui.discover

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.*
import com.aliflix.app.ui.theme.*
import kotlinx.coroutines.*

internal class FilteredCatalogueSession {
    var items by mutableStateOf<List<Media>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf(false)
    var loaded by mutableStateOf(false)
    var hasMore by mutableStateOf(true)
    val requests=linkedMapOf<String,V3RecommendationRequest>()
    val finished=mutableSetOf<String>()
    val pending=mutableSetOf<String>()
    val batch=linkedMapOf<String,List<V3RecommendationResult>>()
}

internal class DiscoverFilterStore(private val fetch:suspend(V3RecommendationRequest)->V3RecommendationResponse) {
    constructor(client:RecommendationAiClient):this({client.getRecommendations(it)})
    private val sessions=linkedMapOf<String,FilteredCatalogueSession>()
    fun session(spec:CatalogDiscoverySpec,type:String,query:String):FilteredCatalogueSession =
        sessions.getOrPut("$type:$query:$spec") {
            if(sessions.size>=12)sessions.remove(sessions.keys.first())
            FilteredCatalogueSession()
        }
    suspend fun load(spec:CatalogDiscoverySpec,type:String,query:String,more:Boolean=false) {
        val state=session(spec,type,query)
        if(state.loading||state.loaded&&!more||more&&!state.hasMore)return
        state.loading=true;state.error=false
        try {
            val kinds=when(type){"Movies"->listOf(RecommendationMediaKind.MOVIE);"Series"->listOf(RecommendationMediaKind.SERIES);else->if(spec.requiredStatus!=null)listOf(RecommendationMediaKind.SERIES)else listOf(RecommendationMediaKind.MOVIE,RecommendationMediaKind.SERIES)}
            // Retain completed sibling pages across a failed request. Commit
            // one batch atomically so a late type cannot reorder visible rows.
            if(state.pending.isEmpty())state.pending.addAll(kinds.map{it.name}.filterNot{it in state.finished})
            for(kind in kinds) {
                val key=kind.name;if(key !in state.pending)continue
                val request=state.requests.getOrPut(key) {
                    AskAliflixRequestMapper.map(AskAliflixRequest.Filters(spec.forCatalogueKind(kind).copy(discoveryText=query,
                        requiredStatus=if(kind==RecommendationMediaKind.SERIES)spec.requiredStatus else null))).workerRequest
                }
                val response=fetch(request)
                state.batch[key]=response.results
                state.requests[key]=request.copy(cursor=response.nextCursor)
                if(!response.hasMore||response.nextCursor==null)state.finished.add(key)
                state.pending.remove(key)
            }
            val pages=kinds.map{state.batch[it.name].orEmpty()}
            val interleaved=(0 until (pages.maxOfOrNull{it.size}?:0)).flatMap{index->pages.mapNotNull{it.getOrNull(index)}}
            val ordered=when(spec.sortBy){
                RecommendationSort.NEWEST_FIRST->interleaved.sortedByDescending{it.releaseDate.orEmpty()}
                RecommendationSort.OLDEST_FIRST->interleaved.sortedBy{it.releaseDate?:"9999"}
                RecommendationSort.HIGHEST_RATED->interleaved.sortedByDescending{it.tmdbRating?:0.0}
                RecommendationSort.MOST_VOTED->interleaved.sortedByDescending{it.tmdbVoteCount?:0}
                else->interleaved
            }
            val incoming=ordered.map { result -> Media(id=result.tmdbId,type=MediaType.from(result.mediaType),title=result.title,
                overview=result.overview.orEmpty(),posterPath=result.posterPath,backdropPath=result.backdropPath,
                year=result.releaseDate?.take(4).orEmpty(),rating=result.tmdbRating?:0.0,tmdbVoteCount=result.tmdbVoteCount,genres=result.genres,status=result.status.orEmpty()) }
            state.items=(state.items+incoming).distinctBy(Media::key)
            state.batch.clear()
            state.loaded=true;state.hasMore=state.finished.size<kinds.size
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){state.error=true}
        finally{state.loading=false}
    }
}

@Composable internal fun DiscoverFilteredCatalogue(store:DiscoverFilterStore,spec:CatalogDiscoverySpec,type:String,query:String,onOpen:(Media)->Unit,header:@Composable ()->Unit,modifier:Modifier=Modifier,onSortChanged:(RecommendationSort)->Unit={}) {
    val state=store.session(spec,type,query);val scope=rememberCoroutineScope()
    val grid=rememberLazyGridState()
    LaunchedEffect(state){store.load(spec,type,query)}
    LazyVerticalGrid(columns=GridCells.Adaptive(112.dp),state=grid,modifier=modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=28.dp)) {
        item("header",span={GridItemSpan(maxLineSpan)}){header()}
        if(state.items.isNotEmpty())item("sort",span={GridItemSpan(maxLineSpan)}) {
            Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.End) {
                DiscoverSortControl(spec.sortBy,onSortChanged)
            }
        }
        items(state.items,key=Media::key){DiscoverPosterCard(it,onOpen,Modifier.padding(horizontal=8.dp))}
        item("status",span={GridItemSpan(maxLineSpan)}) {
            Column(Modifier.fillMaxWidth().padding(16.dp),horizontalAlignment=androidx.compose.ui.Alignment.CenterHorizontally) {
                if(state.loading)CircularProgressIndicator(Modifier.size(24.dp),color=AliflixAccentSecondary,strokeWidth=2.dp)
                else if(state.error)TextButton(onClick={scope.launch{store.load(spec,type,query,more=state.loaded)}}){Text("Retry")}
                else {
                    if(state.loaded&&state.items.isEmpty())Text("No results",color=AliflixContentTertiary)
                    if(state.hasMore)TextButton(onClick={scope.launch{store.load(spec,type,query,more=true)}}){Text("Load more")}
                }
            }
        }
    }
}

internal fun CatalogDiscoverySpec.forCatalogueKind(kind:RecommendationMediaKind):CatalogDiscoverySpec {
    fun canonical(genres:List<String>)=genres.flatMap { genre ->
        if(kind==RecommendationMediaKind.SERIES)listOf(when(genre){
            "Science Fiction","Fantasy"->"Sci-Fi & Fantasy"
            "Action","Adventure"->"Action & Adventure"
            "War"->"War & Politics"
            else->genre
        })else when(genre){
            "Sci-Fi & Fantasy"->listOf("Science Fiction","Fantasy")
            "Action & Adventure"->listOf("Action","Adventure")
            "War & Politics"->listOf("War")
            else->listOf(genre)
        }
    }.distinct()
    return copy(mediaKind=kind,includedGenres=canonical(includedGenres),excludedGenres=canonical(excludedGenres))
}
