package com.aliflix.app.talk

import android.content.Context
import com.aliflix.app.AliflixApplication
import com.aliflix.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One durable outbox per process. A private account signal refreshes other devices. */
internal object TalkAccountArchive {
    private var instance: TalkChatHistory? = null
    suspend fun deleteAccount(context:Context,uid:String) {
        instance?.close()
        ArchiveClient(context).post(uid,JSONObject().put("action","clear"))
        instance?.forgetAccount(uid)
    }
    @Synchronized fun get(context: Context): TalkChatHistory {
        instance?.let { return it }
        val app = context.applicationContext as AliflixApplication
        val auth = FirebaseAuth.getInstance()
        val db = FirebaseFirestore.getInstance()
        val client = ArchiveClient(app)
        val history = TalkChatHistory(app, app.accountScope, { uid, value ->
            val result = client.post(uid, value)
            if (value.optString("action") in setOf("save", "delete", "clear") && auth.currentUser?.uid == uid) {
                db.collection("users").document(uid).collection("talkArchive").document("main")
                    .set(mapOf("updatedAt" to FieldValue.serverTimestamp()))
            }
            result
        })
        instance = history
        var listener: com.google.firebase.firestore.ListenerRegistration? = null
        auth.addAuthStateListener { current ->
            listener?.remove()
            history.refresh()
            listener = current.currentUser?.let { user ->
                db.collection("users").document(user.uid).collection("talkArchive").document("main")
                    .addSnapshotListener { snapshot, error ->
                        if (error == null && snapshot != null && !snapshot.metadata.hasPendingWrites() && auth.currentUser?.uid == user.uid)
                            history.refresh()
                    }
            }
        }
        return history
    }
}

private class ArchiveClient(context: Context) {
    private val prefs = context.getSharedPreferences("talk-archive-identity", Context.MODE_PRIVATE)
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build()
    private val lock = Mutex()
    private val base = BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/')
    suspend fun post(uid: String, value: JSONObject): JSONObject = lock.withLock {
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        var token = prefs.getString("token", null).takeIf { prefs.getString("owner", null) == uid && prefs.getLong("expires", 0) > System.currentTimeMillis() + 60_000 }
        if (token == null) {
            val user = requireNotNull(FirebaseAuth.getInstance().currentUser)
            val id = requireNotNull(user.getIdToken(false).await().token)
            check(user.uid == uid && FirebaseAuth.getInstance().currentUser?.uid == uid)
            val identity = execute("/v1/party/identity", JSONObject().put("idToken", id), null)
            token = identity.getString("token")
            prefs.edit().putString("owner", uid).putString("token", token).putLong("expires", identity.getLong("expires")).apply()
        }
        check(FirebaseAuth.getInstance().currentUser?.uid == uid)
        execute("/v1/talk/chats", value, token)
    }
    private suspend fun execute(path: String, value: JSONObject, token: String?): JSONObject = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder().url(base + path).apply { token?.let { header("Authorization", "Bearer $it") } }
            .post(value.toString().toRequestBody("application/json".toMediaType())).build()
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object: Callback {
            override fun onFailure(call: Call, error: java.io.IOException) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            override fun onResponse(call: Call, response: Response) { response.use {
                try {
                    if (it.code == 401) prefs.edit().clear().apply()
                    check(it.isSuccessful)
                    val source = requireNotNull(it.body).source(); source.request(128_001); require(source.buffer.size <= 128_000)
                    val result = JSONObject(source.buffer.readUtf8())
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                } catch (error: Exception) { if (continuation.isActive) continuation.resumeWith(Result.failure(error)) }
            } }
        })
    }
}
