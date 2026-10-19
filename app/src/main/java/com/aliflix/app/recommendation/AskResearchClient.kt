package com.aliflix.app.recommendation

import android.content.Context
import com.aliflix.app.BuildConfig
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Mobile research uses the existing authenticated guest/account identity.
 * No provider credentials or movie prose are shipped to the device. */
class AskResearchClient(context:Context) {
    private val prefs=context.applicationContext.getSharedPreferences("aliflix-ask-identity",Context.MODE_PRIVATE)
    private val http=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(70,TimeUnit.SECONDS).callTimeout(75,TimeUnit.SECONDS).build()
    private val base=BuildConfig.RECOMMENDATION_AI_BASE_URL.trimEnd('/')
    private val identityLock=kotlinx.coroutines.sync.Mutex()
    private suspend fun identity():String {
        identityLock.lock()
        try {
            val user=FirebaseAuth.getInstance().currentUser
            val owner=user?.uid?:"guest"
            if(prefs.getString("owner",null)==owner&&prefs.getLong("expires",0)>System.currentTimeMillis()+60000)
                return prefs.getString("token","").orEmpty()
            val body=JSONObject();user?.getIdToken(false)?.await()?.token?.let{body.put("idToken",it)}
            val json=execute(Request.Builder().url("$base/v1/party/identity").post(body.toString().toRequestBody(JSON)).build())
            prefs.edit().putString("owner",owner).putString("token",json.getString("token")).putLong("expires",json.getLong("expires")).apply()
            return json.getString("token")
        } finally {identityLock.unlock()}
    }
    suspend fun request(path:String,body:JSONObject?=null):JSONObject {
        val token=identity()
        val request=Request.Builder().url("$base/v4/ask/$path").header("Authorization","Bearer $token")
            .apply{if(body!=null)post(body.toString().toRequestBody(JSON))}.build()
        return try{execute(request,if(path=="recommendations")75000 else 15000)}catch(e:kotlinx.coroutines.CancellationException){
            body?.optString("requestId")?.takeIf{it.isNotBlank()}?.let { id ->
                http.newCall(Request.Builder().url("$base/v4/ask/cancel").header("Authorization","Bearer $token")
                    .post(JSONObject().put("requestId",id).toString().toRequestBody(JSON)).build()).enqueue(object:Callback{
                    override fun onFailure(call:Call,e:IOException){}
                    override fun onResponse(call:Call,response:Response){response.close()}
                })
            };throw e
        }
    }
    private suspend fun execute(request:Request,timeout:Long=12000):JSONObject=withContext(Dispatchers.IO){suspendCancellableCoroutine { c ->
        val call=http.newBuilder().callTimeout(timeout,TimeUnit.MILLISECONDS).build().newCall(request);c.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,e:IOException){if(c.isActive)c.resumeWithException(e)}
            override fun onResponse(call:Call,response:Response){response.use {
                try {
                    val source=requireNotNull(it.body).source();source.request(256001);require(source.buffer.size<=256000)
                    val json=JSONObject(source.buffer.readUtf8())
                    if(it.code==401)prefs.edit().clear().apply()
                    if(!it.isSuccessful){val e=json.optJSONObject("error");throw RecommendationAiClientException(e?.optString("code")?:"RESEARCH_UNAVAILABLE",it.code==429||it.code>=500,e?.optString("message")?:"Research unavailable")}
                    if(c.isActive)c.resume(json)
                }catch(e:Throwable){if(c.isActive)c.resumeWithException(e)}
            }}
        })
    }}
    private companion object {val JSON="application/json; charset=utf-8".toMediaType()}
}
