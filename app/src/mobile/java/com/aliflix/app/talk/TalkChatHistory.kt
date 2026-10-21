package com.aliflix.app.talk

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

internal data class TalkChat(val id:String, val title:String, val updatedAt:Long, val lines:List<TalkLine> = emptyList())
internal fun TalkChat.json():JSONObject {
    val json=JSONObject().put("id",id).put("title",title.take(100)).put("updatedAt",updatedAt)
    .put("lines",JSONArray(lines.takeLast(64).map { line -> JSONObject().put("role",line.role).put("text",line.text.take(6000))
        .put("sources",JSONArray(line.sources.take(2).filter { it.url.startsWith("https://") }.map { JSONObject().put("title",it.title.take(160)).put("url",it.url.take(600)).put("license",it.license.take(240)) })) }))
    val rows=json.getJSONArray("lines")
    while(json.toString().toByteArray(Charsets.UTF_8).size>21_000) {
        if(rows.length()>1)rows.remove(0) else {
            val row=rows.getJSONObject(0);val text=row.getString("text");row.put("text",text.take(text.length/2))
        }
    }
    return json
}
internal fun parseTalkChat(json:JSONObject):TalkChat {
    val array=json.optJSONArray("lines") ?: JSONArray()
    val lines=(0 until array.length()).map { index ->
        val line=array.getJSONObject(index)
        val refs=line.optJSONArray("sources") ?: JSONArray()
        val sources=(0 until refs.length()).map { at ->
            val ref=refs.getJSONObject(at)
            TalkReference(ref.getString("title"),ref.getString("url"),ref.optString("license"))
        }
        TalkLine(line.getString("role"),line.getString("text"),sources)
    }
    return TalkChat(json.getString("id"),json.getString("title"),json.getLong("updatedAt"),lines)
}

/** Local writes are immediate. Account-scoped outbox survives offline use and process death. */
internal class TalkChatHistory(context:Context, private val scope:CoroutineScope, private val post:suspend (String,JSONObject)->JSONObject,
    private val ownerProvider:()->String={FirebaseAuth.getInstance().currentUser?.uid ?: "guest"}) {
    private val prefs=context.getSharedPreferences("talk-chats-v1",Context.MODE_PRIVATE)
    private fun owner()=ownerProvider()
    private fun local(uid:String)=runCatching { val a=JSONArray(prefs.getString("chats:$uid","[]"));(0 until a.length()).map { parseTalkChat(a.getJSONObject(it)) } }.getOrDefault(emptyList())
    private val _chats=MutableStateFlow(local(owner()))
    val chats=_chats.asStateFlow()
    private val _status=MutableStateFlow("")
    val status=_status.asStateFlow()
    private var job:Job?=null
    private var requested=false
    private var syncingOwner:String?=null
    private fun write(uid:String,chats:List<TalkChat>) {
        val bounded=chats.distinctBy { it.id }.sortedByDescending { it.updatedAt }.take(30)
        prefs.edit().putString("chats:$uid",JSONArray(bounded.map { it.json() }).toString()).apply()
        if(owner()==uid)_chats.value=bounded
    }
    fun save(chat:TalkChat) {
        if(chat.lines.isEmpty())return
        val uid=owner()
        val previous=local(uid).firstOrNull { it.id==chat.id }
        val bounded=parseTalkChat(chat.copy(updatedAt=maxOf(chat.updatedAt,(previous?.updatedAt?:0L)+1)).json())
        if(previous?.lines==bounded.lines&&previous.title==bounded.title)return
        write(uid,listOf(bounded)+local(uid).filterNot { it.id==chat.id })
        prefs.edit().putBoolean("dirty:$uid:${chat.id}",true).apply()
        schedule()
    }
    fun refresh(){_chats.value=local(owner());schedule(0)}
    private fun schedule(delayMs:Long=900) {
        val uid=owner()
        requested=true
        if(syncingOwner!=uid){job?.cancel();job=null;syncingOwner=uid}
        if(uid=="guest"){_status.value="Saved on this device";return}
        if(job?.isActive==true)return
        job=scope.launch {
          do {
            requested=false
            delay(delayMs);if(owner()!=uid)return@launch
            _status.value="Syncing"
            try {
                if(prefs.getBoolean("clear:$uid",false)) {post(uid,JSONObject().put("action","clear"));prefs.edit().remove("clear:$uid").apply()}
                val deleted=prefs.all.keys.filter { it.startsWith("delete:$uid:") }
                for(key in deleted) {post(uid,JSONObject().put("action","delete").put("id",key.substringAfterLast(':')));prefs.edit().remove(key).apply()}
                for(chat in local(uid).filter { it.lines.isNotEmpty() && prefs.getBoolean("dirty:$uid:${it.id}",false) }) {
                    val result=post(uid,JSONObject().put("action","save").put("chat",chat.json()))
                    if(result.optBoolean("deleted"))write(uid,local(uid).filterNot { it.id==chat.id })
                    if(local(uid).firstOrNull { it.id==chat.id }==chat)prefs.edit().remove("dirty:$uid:${chat.id}").apply()
                }
                val remote=post(uid,JSONObject().put("action","list")).optJSONArray("chats") ?: JSONArray()
                if(owner()!=uid)return@launch
                val cached=local(uid).associateBy { it.id }
                val merged=(0 until remote.length()).map { parseTalkChat(remote.getJSONObject(it)) }.map { summary ->
                    cached[summary.id]?.takeIf { it.updatedAt>=summary.updatedAt } ?: summary
                }
                val pending=local(uid).filter { prefs.getBoolean("dirty:$uid:${it.id}",false) }
                write(uid,(pending+merged).filterNot { prefs.getBoolean("delete:$uid:${it.id}",false) });_status.value="Synced with your account"
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(owner()==uid){_status.value="Saved on device · Tap to retry sync";delay(15_000);requested=true}}
          } while(requested&&owner()==uid)
        }
    }
    suspend fun read(id:String):TalkChat? {
        val uid=owner();local(uid).firstOrNull { it.id==id&&it.lines.isNotEmpty() }?.let { return it }
        if(uid=="guest")return null
        return try {
            val value=post(uid,JSONObject().put("action","read").put("id",id)).optJSONObject("chat") ?: return null
            if(owner()!=uid)return null
            parseTalkChat(value).also { write(uid,listOf(it)+local(uid).filterNot { c->c.id==id }) }
        } catch(cancelled:CancellationException){throw cancelled} catch(_:Exception){_status.value="Couldn't open chat · Try again";null}
    }
    fun delete(id:String){val uid=owner();write(uid,local(uid).filterNot { it.id==id });prefs.edit().remove("dirty:$uid:$id").putBoolean("delete:$uid:$id",true).apply();schedule(0)}
    fun clear(){val uid=owner();job?.cancel();write(uid,emptyList());val editor=prefs.edit();prefs.all.keys.filter { it.startsWith("dirty:$uid:") }.forEach(editor::remove);editor.putBoolean("clear:$uid",true).apply();schedule(0)}
    fun close(){job?.cancel()}
    fun forgetAccount(uid:String) {
        job?.cancel();job=null
        prefs.edit().also { editor -> prefs.all.keys.filter { it=="chats:$uid" || it=="clear:$uid" || it.startsWith("dirty:$uid:") || it.startsWith("delete:$uid:") }.forEach(editor::remove) }.apply()
        if(owner()==uid)_chats.value=emptyList()
    }
}
