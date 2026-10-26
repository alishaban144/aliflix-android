package com.aliflix.app.talk

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay

/** Artwork recovery belongs to the exact ID, never another title search. Both
 * missing metadata and an expired CDN path recover without changing the card. */
@Composable internal fun TalkPoster(card:TalkTitleCard,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    var path by remember(card.key,card.posterPath){mutableStateOf(talkPosterPath(card.posterPath))}
    var attempt by remember(card.key){mutableIntStateOf(0)}
    var loaded by remember(card.key,path,attempt){mutableStateOf(false)}
    var failed by remember(card.key,path,attempt){mutableStateOf(false)}
    LaunchedEffect(card.key,path,failed) {
        if(path==null||failed&&attempt<2) {
            if(failed)delay(350)
            val hydrated=runCatching{TalkCatalogue{TalkCardContext().show(listOf(card))}.hydrate(card)}.getOrNull()
            val repaired=talkPosterPath(hydrated?.posterPath)
            if(repaired!=null&&repaired!=path){path=repaired;attempt=0}
            else if(path!=null&&attempt<2)attempt++
        }
    }
    val size=if(attempt==0)"w342" else if(attempt==1)"w500" else "original"
    val request=remember(path,attempt){ImageRequest.Builder(context).data(path?.let{"https://image.tmdb.org/t/p/$size$it"}).crossfade(220).build()}
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF252331))
        .testTag("talk-poster-${card.key}-${if(loaded)"loaded" else if(failed||path==null)"missing" else "loading"}"),contentAlignment=Alignment.Center) {
        if(!loaded) {
            if(path!=null&&!failed)CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=1.dp,color=Color(0xFFBFB2E8))
            else Text(card.title.firstOrNull()?.uppercase().orEmpty(),color=Color(0xFFBFB2E8))
        }
        AsyncImage(request,contentDescription="${card.title} poster",contentScale=ContentScale.Crop,modifier=Modifier.fillMaxSize(),
            onSuccess={loaded=true;failed=false},onError={failed=true})
    }
}
