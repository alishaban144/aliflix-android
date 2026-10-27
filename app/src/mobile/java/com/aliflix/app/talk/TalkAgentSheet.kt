package com.aliflix.app.talk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aliflix.app.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TalkAgentSheet(session:TalkVoiceSession,onDismiss:()->Unit,onOpen:(TalkTitleCard)->Unit) {
 val missions by session.agent.missions.collectAsState()
 val downloads by session.downloadWork.jobs.collectAsState()
 val scope=rememberCoroutineScope()
 val uri=LocalUriHandler.current
 var busy by remember{mutableStateOf(false)}
 var failed by remember{mutableStateOf(false)}
 fun refresh(){scope.launch{busy=true;failed=runCatching{session.agent.refresh()}.isFailure;busy=false}}
 LaunchedEffect(Unit){while(true){busy=true;failed=runCatching{session.agent.refresh()}.isFailure;busy=false;delay(6000)}}
 ModalBottomSheet(onDismissRequest=onDismiss,modifier=Modifier.testTag("talk-agent-workspace")) {
  Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),verticalAlignment=Alignment.CenterVertically){
   Text("Background work",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
   IconButton(onClick={refresh()},enabled=!busy){Icon(if(failed)Icons.Rounded.Refresh else Icons.Rounded.Sync,"Refresh tasks")}
  }
  LazyColumn(Modifier.fillMaxWidth().heightIn(min=220.dp,max=620.dp).navigationBarsPadding(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
   if(downloads.none{it.stage!="Cancelled"}&&missions.none{it.optString("status")!="cancelled"})item("idle"){Text("Idle",color=MaterialTheme.colorScheme.onSurfaceVariant)}
   items(downloads.filterNot{it.stage=="Cancelled"},key={"download:"+it.id}){job->
    Column(Modifier.fillMaxWidth().testTag("talk-download-${job.id}")){
     Row(verticalAlignment=Alignment.CenterVertically){
      Column(Modifier.weight(1f)){
       Text(job.card.title,style=MaterialTheme.typography.titleMedium)
       Text(listOfNotNull(job.season?.let{"S$it · E${job.episode}"},job.quality,job.subtitles.takeIf{it.isNotBlank()}?.uppercase()).joinToString(" · "),fontSize=12.sp)
       Text(listOfNotNull(job.stage,job.detail.takeIf{it.isNotBlank()&&job.stage!="Failed"}).joinToString(" · "),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
      when(job.stage){
       "Failed"->IconButton(onClick={session.downloadWork.retry(job.id)}){Icon(Icons.Rounded.Refresh,"Retry download")}
       "Verification required"->TextButton(onClick={session.downloadWork.verify(job.id)}){Text("Verify")}
       "Paused"->IconButton(onClick={session.downloadWork.resume(job.id)}){Icon(Icons.Rounded.PlayArrow,"Resume download")}
       "Downloading","Waiting for network"->IconButton(onClick={session.downloadWork.pause(job.id)}){Icon(Icons.Rounded.Pause,"Pause download")}
      }
      if(job.stage!="Completed")IconButton(onClick={session.downloadWork.cancel(job.id)}){Icon(Icons.Rounded.Close,"Cancel download")}
     }
     if(job.active&&job.stage!="Paused"){
      val progress=job.progress
      if(progress!=null)LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
     }
     if(job.detail.isNotBlank()&&job.stage=="Failed")Text(job.detail,fontSize=12.sp,color=MaterialTheme.colorScheme.error)
    }
   }
   items(missions.filterNot{it.optString("status")=="cancelled"},key={it.getString("id")}){mission->
    val plan=mission.getJSONObject("plan");val status=mission.optString("status")
    var episodeLimit by remember(mission.optInt("revision")){mutableIntStateOf(24)}
    Column(Modifier.fillMaxWidth().testTag("agent-task-${mission.getString("id")}")){
     Row(verticalAlignment=Alignment.CenterVertically){
      Column(Modifier.weight(1f)){Text(plan.getString("name"),style=MaterialTheme.typography.titleMedium);Text(status.replace('_',' '),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
      if(status!="cancelled"){
       if(status=="needs_input")TextButton(onClick={onDismiss();session.sendText("Read my task "+mission.getString("id")+" and help me adjust the specific constraint that prevents it from finishing. Preserve its goal and ask me before relaxing a requirement.")}){Text("Adjust")}
       else {
        val op=when(status){"paused","failed"->"resume";"ready"->"refresh";else->"pause"}
        IconButton(onClick={scope.launch{failed=runCatching{session.agent.command(JSONObject().put("operation",op).put("id",mission.getString("id")).put("expectedRevision",mission.getInt("revision")))}.isFailure}}){Icon(when(op){"refresh"->Icons.Rounded.Refresh;"resume"->Icons.Rounded.PlayArrow;else->Icons.Rounded.Pause},when(op){"refresh"->"Refresh task";"resume"->"Resume task";else->"Pause task"})}
       }
       IconButton(onClick={scope.launch{failed=runCatching{session.agent.command(JSONObject().put("operation","cancel").put("id",mission.getString("id")).put("expectedRevision",mission.getInt("revision")))}.isFailure}}){Icon(Icons.Rounded.Close,"Cancel task")}
      }
     }
     val steps=plan.getJSONArray("steps");val last=steps.getJSONObject(steps.length()-1).getString("id")
     val output=mission.optJSONObject("outputs")?.optJSONObject(last)
     val results=output?.optJSONArray("results")
     if(results!=null&&output.optJSONArray("festival")==null)for(index in 0 until results.length()){
      val row=results.getJSONObject(index)
      val card=TalkTitleCard(row.getInt("tmdbId"),MediaType.from(row.optString("mediaType","movie")),row.getString("title"),row.optString("releaseDate").take(4),row.optString("posterPath").takeIf{it.startsWith('/')})
      Row(Modifier.fillMaxWidth().clickable{onOpen(card)}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
       Text("${index+1}",Modifier.width(30.dp),color=MaterialTheme.colorScheme.primary)
       Text(card.title,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge)
       Text(row.optInt("runtimeMinutes").takeIf{it>0}?.let{"$it min"}?:card.year,fontSize=12.sp)
      }
     }
     output?.optJSONArray("episodes")?.let{episodes->
      for(index in 0 until minOf(episodes.length(),episodeLimit)){val episode=episodes.getJSONObject(index)
       Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text("S${episode.getInt("season")} · E${episode.getInt("episode")}    ${episode.getString("airDate")}",Modifier.weight(1f).padding(vertical=6.dp),fontSize=14.sp)
        IconButton(onClick={session.openAgentEpisode(episode)}){Icon(Icons.Rounded.PlayArrow,"Play S${episode.getInt("season")} E${episode.getInt("episode")}")}
       }
      }
     }
     output?.optJSONArray("festival")?.let{days->for(index in 0 until days.length()){
      val day=days.getJSONObject(index);Text(day.getString("date")+" · "+day.getInt("minutes")+" min",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleSmall)
      val films=day.getJSONArray("results");for(n in 0 until films.length()){val film=films.getJSONObject(n)
       Text(film.getString("title"),Modifier.fillMaxWidth().clickable{onOpen(TalkTitleCard(film.getInt("tmdbId"),MediaType.from(film.optString("mediaType","movie")),film.getString("title"),film.optString("releaseDate").take(4)))}.padding(vertical=6.dp))
      }
     }}
     output?.optJSONArray("calendar")?.let{days->for(index in 0 until days.length()){
      val day=days.getJSONObject(index);val episodes=day.getJSONArray("episodes")
      if(episodes.length()>0){Text(day.getString("date")+" · "+day.getInt("minutes")+" min",Modifier.padding(top=12.dp),style=MaterialTheme.typography.titleSmall)
       for(n in 0 until episodes.length()){val episode=episodes.getJSONObject(n)
        TextButton(onClick={session.openAgentEpisode(episode)}){Icon(Icons.Rounded.PlayArrow,null);Text("S${episode.getInt("season")} · E${episode.getInt("episode")}")}
       }
      }
     }}
     output?.optJSONArray("evidence")?.let{sources->for(index in 0 until minOf(sources.length(),8)){val source=sources.getJSONObject(index)
      Text(source.getString("title"),Modifier.fillMaxWidth().clickable{runCatching{uri.openUri(source.getString("url"))}}.padding(vertical=8.dp),color=MaterialTheme.colorScheme.primary,fontSize=13.sp)
     }}
     if(output?.optBoolean("moreResults")==true||(output?.optJSONArray("episodes")?.length()?:0)>episodeLimit)TextButton(onClick={scope.launch{session.agent.command(JSONObject().put("operation","read").put("id",mission.getString("id")));episodeLimit+=24}}){Text("More")}
    }
   }
  }
 }
}
