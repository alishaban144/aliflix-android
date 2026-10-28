package com.aliflix.app.talk

import androidx.compose.foundation.background
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.semantics.*

internal data class TalkTitleCard(val id:Int,val type:MediaType,val title:String,val year:String="",val posterPath:String?=null,val originalTitle:String?=null) {
    val key get()="${type.routeName}:$id"
    fun media()=Media(id,type,title,year=year,posterPath=posterPath)
    fun json()=JSONObject().put("id",id).put("type",type.routeName).put("title",title).put("year",year).apply{posterPath?.let{put("posterPath",it)};originalTitle?.let{put("originalTitle",it)}}
}
internal fun parseTalkTitles(rows:JSONArray?,limit:Int=8):List<TalkTitleCard> =
    (0 until (rows?.length()?:0)).mapNotNull { i -> runCatching {
        val row=rows!!.getJSONObject(i);val id=row.optInt("tmdbId",row.optInt("id"));val title=row.getString("title").trim()
        val type=row.optString("mediaType",row.optString("type"));require(id>0&&title.isNotBlank()&&type in setOf("movie","tv"))
        val poster=talkPosterPath(row.optString("posterPath",row.optString("poster_path")))
        TalkTitleCard(id,MediaType.from(type),title.take(180),row.optString("year",row.optString("releaseDate")).take(4),poster,row.optString("originalTitle").takeIf{it.isNotBlank()&&it!="null"})
    }.getOrNull() }.distinctBy{it.key}.take(limit.coerceIn(1,24))

internal fun talkPosterPath(value:String?):String? {
    val path=value?.replace(Regex("^https://image\\.tmdb\\.org/t/p/(?:w\\d+|original)/"),"/")?:return null
    return path.takeIf{Regex("^/[A-Za-z0-9._-]+\\.(?:jpg|png|webp)$",RegexOption.IGNORE_CASE).matches(it)&&it.length<=200}
}

/** Native adaptation of Kokonut UI Momentum Rail / RailCard by Dorian Baffier,
 * MIT, d33a196aa574553b6c49f947ed6e510bfe1c06c3. The image, corner badge,
 * separate metadata and focus depth come from the authored component. Chat uses
 * its flat, all-details-visible layout so long collections remain scannable. */
@Composable internal fun TalkTitleCards(
    titles:List<TalkTitleCard>,onOpen:(TalkTitleCard)->Unit,modifier:Modifier=Modifier,
    motion:Boolean=com.aliflix.app.ui.common.rememberMobileMotionEnabled(),
    collection:Boolean=false,focusedKey:String?=null,onBounds:(String,Rect)->Unit={_,_->},
) {
    if(!collection){TalkVoiceCardStage(titles,onOpen,modifier,motion,focusedKey,onBounds);return}
    if(titles.isEmpty())return
    if(collection) BoxWithConstraints(modifier.fillMaxWidth()) {
      val columns=if(maxWidth>=320.dp&&LocalDensity.current.fontScale<=1.3f)3 else 2
      val width=minOf(112.dp,(maxWidth-12.dp*(columns-1))/columns)
      Column(verticalArrangement=Arrangement.spacedBy(14.dp)) {
        titles.chunked(columns).forEach { row ->
            Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                row.forEach { title -> key(title.key) { TalkRailCard(title,onOpen,Modifier.width(width),motion,focusedKey==title.key,onBounds) } }
            }
        }
      }
    }
}

@Composable internal fun TalkRailCard(title:TalkTitleCard,onOpen:(TalkTitleCard)->Unit,modifier:Modifier,motion:Boolean,focused:Boolean,onBounds:(String,Rect)->Unit,enabled:Boolean=true) {
    val depth by animateFloatAsState(if(focused)1f else .96f,spring(dampingRatio=1f,stiffness=if(motion)170f else 10000f),label="rail-depth")
    Column(modifier.graphicsLayer{scaleX=depth;scaleY=depth}.onGloballyPositioned{onBounds(title.key,it.findRootCoordinates().localBoundingBoxOf(it,clipBounds=false))}
        .clickable(enabled=enabled,role=Role.Button,onClick={onOpen(title)}).semantics(mergeDescendants=true){}.testTag("talk-title-${title.key}"),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f/3f).clip(RoundedCornerShape(16.dp))
            .border(if(focused)1.5.dp else .5.dp,if(focused)AliflixAccentSecondary else Color.White.copy(alpha=.12f),RoundedCornerShape(16.dp))) {
            TalkPoster(title,Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha=.18f),Color.Transparent,Color.Black.copy(alpha=.18f)))))
            if(title.year.isNotBlank())Text(title.year,color=Color.White,fontSize=10.sp,lineHeight=12.sp,fontWeight=FontWeight.Medium,
                modifier=Modifier.padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha=.68f)).padding(horizontal=6.dp,vertical=3.dp))
        }
        Text(title.title,color=AliflixContentPrimary,fontSize=13.sp,lineHeight=17.sp,fontWeight=FontWeight.Medium,maxLines=2,overflow=TextOverflow.Ellipsis)
        Text(if(title.type==MediaType.TV)"Series" else "Film",color=AliflixContentTertiary,fontSize=11.sp,lineHeight=13.sp)
    }
}
