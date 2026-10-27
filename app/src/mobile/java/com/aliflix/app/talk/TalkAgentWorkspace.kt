package com.aliflix.app.talk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.aliflix.app.AliflixApplication
import com.aliflix.app.recommendation.AskResearchClient
import com.aliflix.app.recommendation.AskTaste
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Durable missions use no microphone, player PCM, provider URL or ambient data.
 * Account ownership is checked again after every asynchronous boundary. */
internal class TalkAgentWorkspace(private val context:Context) {
 private val app=context.applicationContext as AliflixApplication
 private val client=AskResearchClient(context)
 private val owner get()=FirebaseAuth.getInstance().currentUser?.uid?:"guest"
 private fun preferences(owner:String)=context.getSharedPreferences("talk-agent-$owner",Context.MODE_PRIVATE)
 private val _missions=MutableStateFlow<List<JSONObject>>(emptyList())
 private val details=mutableMapOf<String,JSONObject>()
 val missions=_missions.asStateFlow()
 suspend fun command(args:JSONObject):JSONObject {
  val captured=owner
  val request=JSONObject(args.toString()).put("library",AskTaste(context).library(app.libraryStore)).put("timeZone",java.util.TimeZone.getDefault().id)
   .put("completed",JSONArray(app.playbackProgressStore.entries.value.values.filter{it.completed}.map{it.key}.take(1000)))
  val result=client.request("agent",request)
  if(captured!=owner)throw CancellationException("Account changed")
  result.optJSONArray("memories")?.let{preferences(captured).edit().putString("memories",it.toString()).apply()}
  if(args.optString("operation") in setOf("create","update","resume","refresh"))schedule(context)
  if(args.optString("operation")=="list")accept(result,captured)
  else if(result.has("id")) {
   if(args.optString("operation")=="read")details[result.getString("id")]=result
   _missions.value=(_missions.value.filterNot{it.optString("id")==result.optString("id")}+result).sortedByDescending{it.optLong("updatedAt")}
   persist(captured)
  }
  return result
 }
 suspend fun refresh(){command(JSONObject().put("operation","list"))}
 fun preferences():JSONArray=runCatching{JSONArray(preferences(owner).getString("memories","[]"))}.getOrDefault(JSONArray())
 fun restore(){
  details.clear()
  val raw=runCatching{JSONArray(preferences(owner).getString("missions","[]"))}.getOrDefault(JSONArray())
  _missions.value=(0 until raw.length()).mapNotNull{raw.optJSONObject(it)}
 }
 private fun persist(captured:String){preferences(captured).edit().putString("missions",JSONArray(_missions.value).toString()).apply()}
 private fun accept(result:JSONObject,captured:String){
  val rows=result.optJSONArray("missions")?:JSONArray()
  _missions.value=(0 until rows.length()).map{index->val summary=rows.getJSONObject(index);details[summary.getString("id")]?.takeIf{it.optInt("revision")==summary.optInt("revision")&&it.optLong("updatedAt")==summary.optLong("updatedAt")}?:summary};persist(captured)
  val prefs=preferences(captured)
  for(m in _missions.value){
   val id=m.getString("id");val change=m.optInt("change");val previous=prefs.getInt("change:$id",0)
   if(change>previous&&m.optString("status") in setOf("ready","waiting")){
    notify(m);prefs.edit().putInt("change:$id",change).apply()
   }
  }
  if(_missions.value.none{it.optString("status") in setOf("queued","running","waiting")})context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
 }
 private fun notify(m:JSONObject){
  val manager=context.getSystemService(NotificationManager::class.java)
  manager.createNotificationChannel(NotificationChannel("talk-agent","Aliflix tasks",NotificationManager.IMPORTANCE_DEFAULT))
  val intent=Intent(context,TalkVoiceActivity::class.java).putExtra("agentTasks",true)
  val pending=PendingIntent.getActivity(context,m.getString("id").hashCode(),intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
  val notification=NotificationCompat.Builder(context,"talk-agent").setSmallIcon(android.R.drawable.ic_media_play)
   .setContentTitle(m.getJSONObject("plan").getString("name")).setContentText("Ready").setAutoCancel(true).setContentIntent(pending).build()
  runCatching{manager.notify(m.getString("id").hashCode(),notification)}
 }
 companion object {
  private const val JOB_ID=4182
  fun schedule(context:Context){
   val scheduler=context.getSystemService(JobScheduler::class.java)
   if(scheduler.getPendingJob(JOB_ID)==null)scheduler.schedule(JobInfo.Builder(JOB_ID,ComponentName(context,TalkAgentJob::class.java))
    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).setPeriodic(15*60000L).build())
  }
 }
}

class TalkAgentJob:JobService() {
 private var job:Job?=null
 override fun onStartJob(params:JobParameters):Boolean {
  job=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate).launch {
   val success=runCatching{TalkAgentWorkspace(this@TalkAgentJob).refresh()}.isSuccess
   jobFinished(params,!success)
  };return true
 }
 override fun onStopJob(params:JobParameters):Boolean{job?.cancel();job=null;return true}
}
