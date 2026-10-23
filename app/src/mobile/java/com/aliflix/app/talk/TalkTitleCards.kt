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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.Media
import com.aliflix.app.model.MediaType
import com.aliflix.app.ui.discover.AskAliflixPoster
import com.aliflix.app.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay

internal data class TalkTitleCard(val id:Int,val type:MediaType,val title:String,val year:String="",val posterPath:String?=null) {
    val key get()="${type.routeName}:$id"
    fun media()=Media(id,type,title,year=year,posterPath=posterPath)
    fun json()=JSONObject().put("id",id).put("type",type.routeName).put("title",title).put("year",year).apply{posterPath?.let{put("posterPath",it)}}
}
internal fun parseTalkTitles(rows:JSONArray?):List<TalkTitleCard> =
    (0 until (rows?.length()?:0)).mapNotNull { i -> runCatching {
        val row=rows!!.getJSONObject(i);val id=row.optInt("tmdbId",row.optInt("id"));val title=row.getString("title").trim()
        val type=row.optString("mediaType",row.optString("type"));require(id>0&&title.isNotBlank()&&type in setOf("movie","tv"))
        val poster=row.optString("posterPath").takeIf{it.startsWith('/')&&!it.startsWith("//")&&it.length<=200}
        TalkTitleCard(id,MediaType.from(type),title.take(180),row.optString("year").take(4),poster)
    }.getOrNull() }.distinctBy{it.key}.take(3)

/** Quiet, compact companions to the reply; the orb and topic keep the stage. */
@Composable internal fun TalkTitleCards(titles:List<TalkTitleCard>,onOpen:(TalkTitleCard)->Unit,modifier:Modifier=Modifier,motion:Boolean=com.aliflix.app.ui.common.rememberMobileMotionEnabled()) {
    var retained by remember { mutableStateOf(titles) }
    LaunchedEffect(titles) {
        retained=(retained+titles).distinctBy{it.key}.map{old->titles.firstOrNull{it.key==old.key}?:old}
        if(motion)delay(520)
        retained=titles
    }
    if(retained.isEmpty()&&titles.isEmpty())return
    BoxWithConstraints(modifier.fillMaxWidth()) {
    val singleWidth=minOf(maxWidth*.84f,300.dp)
    val groupWidth=(maxWidth-10.dp*(maxOf(2,titles.size)-1))/maxOf(2,titles.size)
    val cardWidth by animateDpAsState(if(titles.size==1)singleWidth else groupWidth,spring(dampingRatio=.88f,stiffness=if(motion)170f else 10000f),label="card-focus-width")
    val edge by animateDpAsState(if(titles.size==1)(maxWidth-cardWidth)/2 else 0.dp,tween(if(motion)480 else 0),label="card-centering")
    LazyRow(Modifier.fillMaxWidth().testTag("talk-title-cards"),horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(horizontal=edge,vertical=8.dp)) {
        items(retained,key={it.key}) { title ->
            val shown=titles.any{it.key==title.key}
            val direction=if(retained.indexOf(title)<retained.indexOfFirst{it.key==titles.firstOrNull()?.key})-1 else 1
            val tilt by animateFloatAsState(if(shown)0f else direction*9f,tween(if(motion)420 else 0),label="card-turn")
            AnimatedVisibility(shown,modifier=Modifier.animateItem(placementSpec=if(motion)spring(dampingRatio=.86f,stiffness=170f)else snap()),
                enter=if(motion)fadeIn(tween(280))+slideInHorizontally(spring(stiffness=200f)){it/5}+scaleIn(initialScale=.94f) else EnterTransition.None,
                exit=if(motion)fadeOut(tween(280))+slideOutHorizontally(tween(420)){direction*it/2}+shrinkHorizontally(tween(480))+scaleOut(targetScale=.84f) else ExitTransition.None) {
            val shape=RoundedCornerShape(20.dp)
            Box(Modifier.width(cardWidth).graphicsLayer{rotationZ=tilt}.clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF211E2A).copy(alpha=.92f),Color(0xFF15141C).copy(alpha=.96f))))
                .border(.5.dp,Color.White.copy(alpha=.11f),shape)
                .clickable(role=Role.Button,onClick={onOpen(title)}).padding(9.dp).testTag("talk-title-${title.key}")) {
              AnimatedContent(titles.size==1,transitionSpec={fadeIn(tween(if(motion)280 else 0)) togetherWith fadeOut(tween(if(motion)140 else 0))},label="card-focus-layout") { focused ->
              if(focused)Row(Modifier.height(82.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                AskAliflixPoster(title.media(),Modifier.width(54.dp).height(81.dp),cornerRadius=12.dp)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(title.title,color=AliflixContentPrimary,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(listOf(if(title.type==MediaType.MOVIE)"Film" else "Series",title.year).filter{it.isNotBlank()}.joinToString(" · "),
                        color=AliflixContentSecondary,fontSize=11.sp,letterSpacing=.2.sp)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward,"Open ${title.title}",Modifier.size(17.dp),tint=AliflixAccentSecondary.copy(alpha=.8f))
              } else Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                AskAliflixPoster(title.media(),Modifier.fillMaxWidth().height(104.dp),cornerRadius=12.dp)
                Text(title.title,color=AliflixContentPrimary,fontSize=12.sp,lineHeight=15.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.height(30.dp))
                Text(title.year,color=AliflixContentSecondary,fontSize=10.sp)
              }
              }
            }
        } }
    }
    }
}

internal fun talkAnswerNamesTitle(text:String,titles:List<TalkTitleCard>):Boolean {
    fun normalized(v:String)=" "+v.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()+" "
    val answer=normalized(text)
    return titles.any{answer.contains(normalized(it.title))||answer.contains(normalized(it.title.replace(Regex("^(The|A|An) ",RegexOption.IGNORE_CASE),"")))}
}
