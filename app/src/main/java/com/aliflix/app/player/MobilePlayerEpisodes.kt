package com.aliflix.app.player

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aliflix.app.model.Episode
import com.aliflix.app.model.Season
import com.aliflix.app.ui.common.*
import com.aliflix.app.ui.theme.*

/** Native, adaptive episode browser. Catalogue season availability is independent
 * of the episodes already loaded; changing a season does not move playback. */
@Composable internal fun MobileEpisodesOverlay(
    visible:Boolean,isLandscape:Boolean,episodes:List<Episode>,currentSeason:Int?,currentEpisode:Int?,
    onSelectEpisode:(Episode)->Unit,onDismiss:()->Unit,modifier:Modifier=Modifier,
    seasonOptions:List<Season> = emptyList(),loadingSeason:Int?=null,seasonError:String?=null,
    onSeasonSelected:(Int)->Unit={},contentKey:String="",
) {
    val seasons=remember(seasonOptions,episodes) { (seasonOptions.map { it.number }+episodes.map { it.seasonNumber }).distinct().sorted() }
    var selectedSeason by remember(currentSeason,contentKey) { mutableIntStateOf(currentSeason ?: seasons.firstOrNull() ?: 1) }
    LaunchedEffect(visible,selectedSeason) { if(visible) onSeasonSelected(selectedSeason) }
    val rows=remember(episodes,selectedSeason) { episodes.filter { it.seasonNumber==selectedSeason }.sortedBy { it.number } }
    val motion=rememberMobileMotionEnabled()
    AnimatedVisibility(visible,enter=fadeIn(animationSpec=if(motion) androidx.compose.animation.core.tween(160) else androidx.compose.animation.core.snap()),exit=fadeOut(animationSpec=if(motion) androidx.compose.animation.core.tween(100) else androidx.compose.animation.core.snap())) {
        Box(modifier.fillMaxSize().background(Color.Black.copy(alpha=.60f)).clickable(
            interactionSource=remember { MutableInteractionSource() },indication=null,onClick=onDismiss)) {
            val panel=if(isLandscape) Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max=420.dp).fillMaxWidth(.48f)
                else Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(.80f)
            AliflixSurface(modifier=panel.clickable(interactionSource=remember { MutableInteractionSource() },indication=null) {},
                shape=AliflixCorners.Panel,level=AliflixSurfaceLevel.Elevated) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))) {
                    Row(Modifier.fillMaxWidth().padding(start=20.dp,end=8.dp,top=8.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Episodes",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                            Text("Season $selectedSeason",style=MaterialTheme.typography.labelMedium,color=AliflixAccentSecondary)
                        }
                        AliflixIconButton(onClick=onDismiss) { Icon(Icons.Rounded.Close,"Close episodes") }
                    }
                    LazyRow(Modifier.fillMaxWidth(),contentPadding=PaddingValues(horizontal=16.dp,vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(seasons,key={it}) { season ->
                            val selected=selectedSeason==season
                            AliflixSurface(shape=AliflixCorners.Card,level=if(selected) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content) {
                                Row(Modifier.heightIn(min=48.dp).selectable(selected,role=Role.Tab) { selectedSeason=season }
                                    .padding(horizontal=16.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    Text("S$season",fontWeight=if(selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color=if(selected) AliflixAccentSecondary else AliflixContentSecondary)
                                    if(loadingSeason==season) CircularProgressIndicator(Modifier.size(14.dp),strokeWidth=1.5.dp,color=AliflixAccentSecondary)
                                }
                            }
                        }
                    }
                    if(loadingSeason==selectedSeason && rows.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp,color=AliflixAccentSecondary)
                    } else if(rows.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
                        TextButton(onClick={onSeasonSelected(selectedSeason)},modifier=Modifier.heightIn(min=48.dp)) {
                            Icon(Icons.Rounded.Refresh,null,Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text(if(seasonError!=null) "Retry" else "Load episodes")
                        }
                    } else key(selectedSeason) {
                        val listState=rememberLazyListState(initialFirstVisibleItemIndex=rows.indexOfFirst { it.seasonNumber==currentSeason && it.number==currentEpisode }.coerceAtLeast(0))
                        LazyColumn(Modifier.fillMaxWidth().weight(1f),state=listState,contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        items(rows,key={"${it.seasonNumber}:${it.number}"}) { episode ->
                            val current=episode.seasonNumber==currentSeason && episode.number==currentEpisode
                            AliflixSurface(shape=AliflixCorners.Card,level=if(current) AliflixSurfaceLevel.Selected else AliflixSurfaceLevel.Content) {
                                Row(Modifier.fillMaxWidth().heightIn(min=88.dp).clickable { onSelectEpisode(episode);onDismiss() }
                                    .padding(10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                    Box(Modifier.size(96.dp,60.dp).background(AliflixSurfacePrimary,AliflixCorners.Small),contentAlignment=Alignment.Center) {
                                        AsyncImage(episode.stillUrl,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)
                                        if(current) Icon(Icons.Rounded.PlayCircleFilled,"Current episode",Modifier.size(28.dp),tint=AliflixAccentSecondary)
                                    }
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(5.dp)) {
                                        Text(episode.title.ifBlank { "Episode ${episode.number}" },maxLines=2,overflow=TextOverflow.Ellipsis,
                                            style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                                            Text("E${episode.number}",color=AliflixAccentSecondary,style=MaterialTheme.typography.labelMedium)
                                            if(episode.runtime.isNotBlank()) Text(episode.runtime,color=AliflixContentTertiary,style=MaterialTheme.typography.labelSmall)
                                            episode.imdbRating?.takeIf { it>0 }?.let { rating ->
                                                Row(Modifier.semantics { contentDescription="IMDb $rating" },horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                                                    Text("IMDb",color=Color(0xFFF5C518),fontSize=10.sp,fontWeight=FontWeight.Bold)
                                                    Text(String.format(java.util.Locale.US,"%.1f",rating),fontSize=11.sp,color=AliflixContentSecondary)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        }
                    }
                }
            }
        }
    }
}
