package com.aliflix.app.recommendation

import android.content.Context
import com.aliflix.app.data.LibraryStore
import com.aliflix.app.model.Media
import org.json.JSONArray
import org.json.JSONObject

/** Explicit rejection is separate from Favorites, saved intent and playback. */
class AskTaste(context:Context) {
    private val appContext=context.applicationContext
    private val prefs get()=appContext.getSharedPreferences("aliflix-ask-feedback-"+(com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?:"guest"),Context.MODE_PRIVATE)
    fun rejected():Set<String> = prefs.getStringSet("rejected",emptySet()).orEmpty().toSet()
    fun reject(media:Media){prefs.edit().putStringSet("rejected",(rejected()+media.key).toList().takeLast(160).toSet()).apply()}
    fun unreject(media:Media){prefs.edit().putStringSet("rejected",rejected()-media.key).apply()}
    fun library(store:LibraryStore):JSONArray {
        val records=store.likes.value.take(24).map{it to "favorite"}+store.myList.value.take(16).map{it to "saved"}+store.recent.value.take(24).map{it to "watched"}
        val array=JSONArray()
        rejected().take(16).forEach { key -> val p=key.split(':');val id=p.getOrNull(1)?.toIntOrNull();if(id!=null&&p[0] in setOf("movie","tv"))array.put(JSONObject().put("id",id).put("type",p[0]).put("kind","dislike")) }
        records.distinctBy{it.first.key}.forEach{(m,k)->array.put(JSONObject().put("id",m.id).put("type",m.type.routeName).put("kind",k))}
        return array
    }
}
