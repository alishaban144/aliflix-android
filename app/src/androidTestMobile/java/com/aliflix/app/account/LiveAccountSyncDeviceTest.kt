package com.aliflix.app.account

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import com.aliflix.app.data.*
import com.aliflix.app.model.*
import com.aliflix.app.recommendation.RecommendationStore
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.*
import com.google.firebase.firestore.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Two independent SDK/cache/store instances on the phone, one disposable UID.
 * The viewer's default Firebase session and app preferences are never changed. */
class LiveAccountSyncDeviceTest {
    @Test fun independentClientsShareChangesAndRejectStaleOfflineWrites()=runBlocking {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveAccountSync")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=FirebaseAuth.getInstance().currentUser?.uid
        cleanupOldFixtures(context,original)
        val suffix=java.util.UUID.randomUUID().toString()
        val appA=FirebaseApp.initializeApp(context,FirebaseApp.getInstance().options,"sync-proof-a-$suffix")
        val appB=FirebaseApp.initializeApp(context,FirebaseApp.getInstance().options,"sync-proof-b-$suffix")
        val authA=FirebaseAuth.getInstance(appA);val authB=FirebaseAuth.getInstance(appB)
        val dbA=FirebaseFirestore.getInstance(appA);val dbB=FirebaseFirestore.getInstance(appB)
        dbA.firestoreSettings=FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
        dbB.firestoreSettings=FirebaseFirestoreSettings.Builder().setPersistenceEnabled(false).build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val a=IsolatedSyncContext(context,"sync-proof-a-$suffix")
        val b=IsolatedSyncContext(context,"sync-proof-b-$suffix")
        var syncA:FirebaseAccountSyncRepository?=null;var syncB:FirebaseAccountSyncRepository?=null
        var user:FirebaseUser?=null
        val receipt=JSONObject()
        suspend fun until(check:()->Boolean)=withTimeout(30_000) { while(!check())delay(80) }
        try {
            try { user=withTimeout(20_000) {
                try { authA.signInAnonymously().await().user }
                catch(error:FirebaseAuthException) {
                    if(error.errorCode !in setOf("ERROR_ADMIN_RESTRICTED_OPERATION","ERROR_OPERATION_NOT_ALLOWED"))throw error
                    // Reserved, undeliverable domain; no verification mail is sent.
                    authA.createUserWithEmailAndPassword("sync-proof-$suffix@example.invalid",java.util.UUID.randomUUID().toString()+"Aa!7").await().user
                }
            } }
            catch(error:FirebaseAuthException) {
                receipt.put("unavailable",error.errorCode)
                Assume.assumeTrue("Disposable Firebase test authentication unavailable: ${error.errorCode}",false)
            }
            val testUser=requireNotNull(user);assertNotEquals(original,testUser.uid)
            authB.updateCurrentUser(testUser).await()
            val account=SyncFixtureAccount(testUser)
            lateinit var libraryA:LibraryStore;lateinit var libraryB:LibraryStore
            lateinit var progressA:PlaybackProgressStore;lateinit var progressB:PlaybackProgressStore
            val lostInitialItems=java.util.concurrent.atomic.AtomicBoolean(false)
            val importCounts=java.util.concurrent.CopyOnWriteArrayList<Int>()
            var importMonitor:Job?=null
            withContext(Dispatchers.Main) {
                libraryA=LibraryStore(a);libraryB=LibraryStore(b)
                progressA=PlaybackProgressStore(a);progressB=PlaybackProgressStore(b)
                repeat(32) { index ->
                    val imported=Media(2147482800+index,MediaType.MOVIE,"Import fixture $index",year="2026")
                    libraryA.toggleLike(imported)
                    if(index<4)libraryA.markPlayed(imported)
                }
                importMonitor=scope.launch { libraryA.likes.collect { if(importCounts.lastOrNull()!=it.size)importCounts+=it.size;if(it.size<32)lostInitialItems.set(true) } }
                syncA=FirebaseAccountSyncRepository(a,account,libraryA,PlaybackProviderRepository(a),progressA,RecommendationStore(a),scope,dbA)
                syncB=FirebaseAccountSyncRepository(b,account,libraryB,PlaybackProviderRepository(b),progressB,RecommendationStore(b),scope,dbB)
            }
            until { syncA!!.state.value==AccountSyncState.Synced&&syncB!!.state.value==AccountSyncState.Synced }
            until { libraryB.likes.value.size==32&&libraryB.recent.value.size==4 }
            importMonitor?.cancel()
            receipt.put("initialImportCounts",org.json.JSONArray(importCounts.toList()))
            assertFalse("Initial remote snapshots discarded local imports",lostInitialItems.get())
            assertEquals(32,libraryA.likes.value.size)
            receipt.put("initialImportsPreservedAndShared",true)
            val media=Media(2147482905,MediaType.MOVIE,"Account sync fixture",year="2026")
            val started=android.os.SystemClock.elapsedRealtime()
            withContext(Dispatchers.Main) { libraryA.toggleLike(media);libraryA.toggleMyList(media);libraryA.markPlayed(media) }
            until { libraryB.likes.value.any { it.key==media.key }&&libraryB.myList.value.any { it.key==media.key }&&libraryB.recent.value.any { it.key==media.key } }
            receipt.put("libraryMs",android.os.SystemClock.elapsedRealtime()-started)
            withContext(Dispatchers.Main) { libraryB.toggleLike(media) }
            until { libraryA.likes.value.none { it.key==media.key } }
            receipt.put("remoteRemoval",true)
            val selection=PlaybackSelection(media)
            withContext(Dispatchers.Main) { progressA.savePlayerProgress(selection,42.0,120.0,true) }
            until { progressB.progressFor(selection)?.positionSeconds==42.0 }
            withContext(Dispatchers.Main) { progressB.savePlayerProgress(selection,110.0,120.0,true) }
            until { progressA.progressFor(selection)?.completed==true }
            receipt.put("resumeAndCompletion",true)
            withContext(Dispatchers.Main) { a.getSharedPreferences("native-audio-choice",0).edit().putString("language","de").apply() }
            until { b.getSharedPreferences("native-audio-choice",0).getString("language",null)=="de" }
            receipt.put("audioChoice",true)
            withContext(Dispatchers.Main) {
                a.getSharedPreferences("aliflix-talk",0).edit().putString("voice","Kore").putBoolean("keep-history",true).apply()
                a.getSharedPreferences("aliflix-ask-feedback-${testUser.uid}",0).edit().putStringSet("rejected",setOf(media.key)).apply()
                ActivityShelves(a).record("trailers",media)
                a.getSharedPreferences("account-caption-files",0).edit().putString("fixture","Caption payload").apply()
            }
            until { b.getSharedPreferences("aliflix-talk",0).getString("voice",null)=="Kore"&&
                b.getSharedPreferences("aliflix-ask-feedback-${testUser.uid}",0).getStringSet("rejected",emptySet())==setOf(media.key)&&
                ActivityShelves(b).read("trailers").any { it.key==media.key }&&
                b.getSharedPreferences("account-caption-files",0).getString("fixture",null)=="Caption payload" }
            receipt.put("talkTasteActivityAndCaptions",true)
            withContext(Dispatchers.Main) { libraryB.removeRecent(media);progressB.removeMedia(media) }
            until { libraryA.recent.value.none { it.key==media.key }&&progressA.progressFor(selection)==null }
            receipt.put("historyAndProgressDeletion",true)
            dbA.disableNetwork().await()
            withContext(Dispatchers.Main) { libraryA.toggleLike(media) }
            delay(250)
            withContext(Dispatchers.Main) { libraryB.toggleLike(media) }
            until { libraryB.likes.value.any { it.key==media.key } }
            delay(250)
            withContext(Dispatchers.Main) { libraryB.toggleLike(media) }
            val tombstone=dbB.collection("users").document(testUser.uid).collection("favorites").document(media.key)
            withTimeout(20_000) { while(tombstone.get(Source.SERVER).await().getBoolean("deleted")!=true)delay(100) }
            dbA.enableNetwork().await()
            until { libraryA.likes.value.none { it.key==media.key }&&libraryB.likes.value.none { it.key==media.key } }
            receipt.put("offlineOlderAddCannotResurrectRemoval",true)
            assertEquals(original,FirebaseAuth.getInstance().currentUser?.uid)
        } finally {
            withContext(Dispatchers.Main) { syncA?.close();syncB?.close();scope.cancel() }
            withTimeoutOrNull(20_000) {
                dbA.enableNetwork().await()
                user?.let { testUser ->
                    check(testUser.uid!=original)
                    AccountCloudDeletionPlan.collections.forEach { collection ->
                        val rows=dbA.collection("users").document(testUser.uid).collection(collection).get(Source.SERVER).await()
                        if(!rows.isEmpty) { val batch=dbA.batch();rows.documents.forEach { batch.delete(it.reference) };batch.commit().await() }
                    }
                    testUser.delete().await();receipt.put("disposableAccountRemoved",true)
                }
            }
            authA.signOut();authB.signOut();dbA.terminate().await();dbB.terminate().await();appA.delete();appB.delete()
            a.clearFixturePreferences();b.clearFixturePreferences()
            File(context.getExternalFilesDir(null),"account-sync-177.json").writeText(receipt.toString(2))
        }
    }
}

/** Recover cleanup even if an earlier instrumentation process crashed. */
private suspend fun cleanupOldFixtures(context:Context,defaultUid:String?) {
    val prefixes=File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().mapNotNull {
        Regex("sync-proof-a-([0-9a-f-]{36})-aliflix_library.xml").matchEntire(it.name)?.groupValues?.get(1)
    }.distinct()
    for(suffix in prefixes) {
        val apps=listOf("a","b").map { side ->
            val name="sync-proof-$side-$suffix"
            FirebaseApp.getApps(context).firstOrNull { it.name==name } ?: FirebaseApp.initializeApp(context,FirebaseApp.getInstance().options,name)
        }
        for(app in apps) {
            val auth=FirebaseAuth.getInstance(app);val user=auth.currentUser
            if(user!=null) {
                check(user.uid!=defaultUid&&(user.isAnonymous||user.email=="sync-proof-$suffix@example.invalid"))
                val db=FirebaseFirestore.getInstance(app)
                withTimeout(30_000) {
                    AccountCloudDeletionPlan.collections.forEach { name ->
                        val rows=db.collection("users").document(user.uid).collection(name).get(Source.SERVER).await()
                        if(!rows.isEmpty) { val batch=db.batch();rows.documents.forEach { batch.delete(it.reference) };batch.commit().await() }
                    }
                    try {user.delete().await()}catch(error:FirebaseAuthInvalidUserException){if(error.errorCode!="ERROR_USER_NOT_FOUND")throw error}
                }
                db.terminate().await()
            }
            auth.signOut();app.delete()
        }
        File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().filter { it.name.startsWith("sync-proof-a-$suffix-")||it.name.startsWith("sync-proof-b-$suffix-") }.forEach {
            context.getSharedPreferences(it.name.removeSuffix(".xml"),0).edit().clear().commit()
        }
    }
}

private class IsolatedSyncContext(base:Context,private val prefix:String):ContextWrapper(base) {
    private val names=mutableSetOf<String>()
    override fun getApplicationContext():Context=this
    override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
        names+=name
        return baseContext.getSharedPreferences("$prefix-$name",mode)
    }
    fun clearFixturePreferences(){names.forEach { baseContext.getSharedPreferences("$prefix-$it",0).edit().clear().commit() }}
}
private class SyncFixtureAccount(private val user:FirebaseUser):AccountRepository {
    override val state=MutableStateFlow(AccountState(AccountUser(user.uid,"Fixture",null,null,emptySet())))
    override val currentFirebaseUser get()=user
    override suspend fun signInWithGoogle(activity:Activity)=unused()
    override suspend fun createEmailAccount(email:String,password:String,displayName:String?)=unused()
    override suspend fun signInWithEmail(email:String,password:String)=unused()
    override suspend fun sendPasswordResetEmail(email:String)=unused()
    override suspend fun signOut()=unused()
    override suspend fun reauthenticateWithPassword(password:String)=unused()
    override suspend fun reauthenticateWithGoogle(activity:Activity)=unused()
    override suspend fun deleteCurrentUser()=unused()
    override fun clearMessage()=Unit
    override fun close()=Unit
    private fun unused():AccountActionResult=error("Fixture account action is not supported")
}
