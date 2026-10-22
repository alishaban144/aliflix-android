package com.aliflix.app.talk

import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
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
@Composable internal fun TalkTitleCards(titles:List<TalkTitleCard>,onOpen:(TalkTitleCard)->Unit,modifier:Modifier=Modifier) {
    if(titles.isEmpty())return
    LazyRow(modifier.fillMaxWidth().testTag("talk-title-cards"),horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=6.dp)) {
        items(titles,key={it.key}) { title ->
            val shape=RoundedCornerShape(20.dp)
            Row(Modifier.width(if(titles.size==1)290.dp else 264.dp).height(100.dp).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF211E2A).copy(alpha=.92f),Color(0xFF15141C).copy(alpha=.96f))))
                .border(.5.dp,Color.White.copy(alpha=.11f),shape)
                .clickable(role=Role.Button,onClick={onOpen(title)}).padding(9.dp).testTag("talk-title-${title.key}"),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                AskAliflixPoster(title.media(),Modifier.width(54.dp).height(81.dp),cornerRadius=12.dp)
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                    Text(title.title,color=AliflixContentPrimary,fontSize=14.sp,lineHeight=18.sp,fontWeight=FontWeight.SemiBold,maxLines=2,overflow=TextOverflow.Ellipsis)
                    Text(listOf(if(title.type==MediaType.MOVIE)"Film" else "Series",title.year).filter{it.isNotBlank()}.joinToString(" · "),
                        color=AliflixContentSecondary,fontSize=11.sp,letterSpacing=.2.sp)
                }
                Icon(Icons.AutoMirrored.Rounded.ArrowForward,"Open ${title.title}",Modifier.size(17.dp),tint=AliflixAccentSecondary.copy(alpha=.8f))
            }
        }
    }
}

internal fun talkAnswerNamesTitle(text:String,titles:List<TalkTitleCard>):Boolean {
    fun normalized(v:String)=" "+v.lowercase(java.util.Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+")," ").trim()+" "
    val answer=normalized(text)
    return titles.any{answer.contains(normalized(it.title))||answer.contains(normalized(it.title.replace(Regex("^(The|A|An) ",RegexOption.IGNORE_CASE),"")))}
}
