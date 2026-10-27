package com.aliflix.app.recommendation

import com.aliflix.app.AliflixApplication
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=AliflixApplication::class)
class AskResearchOwnershipTest {
 private val context get()=RuntimeEnvironment.getApplication() as AliflixApplication
 private fun response(request:Request,code:Int,json:String)=Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Owned fixture").body(json.toResponseBody("application/json".toMediaType())).build()
 @Test fun identityReturningAfterAccountSwitchCannotPersistOrPostThePreviousPrivateBody()=runBlocking {
  val account=AtomicReference(AskAccountTicket("guest","guest",0));val entered=CountDownLatch(1);val release=CountDownLatch(1);val privatePosts=AtomicInteger()
  val name="ask-owned-${UUID.randomUUID()}"
  val http=OkHttpClient.Builder().addInterceptor{chain->
   if(chain.request().url.encodedPath.endsWith("identity")){
    entered.countDown();check(release.await(5,TimeUnit.SECONDS));response(chain.request(),200,JSONObject().put("token","old-owner-token").put("expires",System.currentTimeMillis()+3600000).toString())
   }else{privatePosts.incrementAndGet();response(chain.request(),200,"{}")}
  }.build()
  val client=AskResearchClient(context,http,"https://fixture.aliflix.test",{account.get()},name,{null})
  val job=async(Dispatchers.Default){try{client.request("agent",JSONObject().put("library","private old owner"));false}catch(_:CancellationException){true}}
  try{
   assertTrue(entered.await(5,TimeUnit.SECONDS));account.set(AskAccountTicket("other","user:other",1));release.countDown()
   assertTrue(withTimeout(5000){job.await()})
   assertEquals(0,privatePosts.get());assertFalse(context.getSharedPreferences(name,0).contains("token"))
  }finally{release.countDown();job.cancelAndJoin();context.getSharedPreferences(name,0).edit().clear().commit();http.dispatcher.executorService.shutdown()}
 }
 @Test fun aLateUnauthorizedResponseCannotEraseAFreshIdentity()=runBlocking {
  val entered=CountDownLatch(1);val release=CountDownLatch(1);val name="ask-owned-${UUID.randomUUID()}"
  val prefs=context.getSharedPreferences(name,0)
  prefs.edit().putString("owner","guest").putString("token","old").putLong("expires",System.currentTimeMillis()+3600000).putBoolean("persistentGuest",true).commit()
  val http=OkHttpClient.Builder().addInterceptor{chain->entered.countDown();check(release.await(5,TimeUnit.SECONDS));response(chain.request(),401,"{\"error\":{\"code\":\"UNAUTHORIZED\"}}")}.build()
  val client=AskResearchClient(context,http,"https://fixture.aliflix.test",{AskAccountTicket("guest","guest",0)},name,{null})
  val job=async(Dispatchers.Default){runCatching{client.request("agent",JSONObject())}}
  try{
   assertTrue(entered.await(5,TimeUnit.SECONDS));prefs.edit().putString("token","new").commit();release.countDown()
   assertTrue(withTimeout(5000){job.await()}.isFailure)
   assertEquals("new",prefs.getString("token",null))
  }finally{release.countDown();job.cancelAndJoin();prefs.edit().clear().commit();http.dispatcher.executorService.shutdown()}
 }
}
