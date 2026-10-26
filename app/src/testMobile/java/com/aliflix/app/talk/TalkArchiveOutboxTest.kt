package com.aliflix.app.talk

import android.app.Application
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class TalkArchiveOutboxTest {
    private fun context():Context=RuntimeEnvironment.getApplication().also { it.getSharedPreferences("talk-chats-v1",0).edit().clear().commit() }
    private fun chat(text:String,time:Long)=TalkChat("00000000-0000-4000-8000-000000000001","Test",time,listOf(TalkLine("user",text)))
    @Test fun messageWrittenDuringUploadSurvivesOldSummaryAndUploadsNext()=runTest {
        val ctx=context();val gate=CompletableDeferred<Unit>();val uploaded=mutableListOf<String>()
        val archive=TalkChatHistory(ctx,backgroundScope,{ _,request ->
            when(request.getString("action")) {
                "save" -> {val value=parseTalkChat(request.getJSONObject("chat"));uploaded+=value.lines.single().text;if(uploaded.size==1)gate.await();JSONObject()}
                else -> JSONObject().put("chats",JSONArray().put(chat("first",1).json()))
            }
        },{"a"})
        archive.save(chat("first",1));advanceTimeBy(1000);runCurrent()
        archive.save(chat("latest",2));gate.complete(Unit);runCurrent()
        assertEquals("latest",archive.chats.value.single().lines.single().text)
        advanceTimeBy(1000);runCurrent()
        assertEquals(listOf("first","latest"),uploaded)
        assertEquals("latest",archive.chats.value.single().lines.single().text)
        archive.close()
    }
    @Test fun offlineOutboxSurvivesRecreationAndNeverCrossesAccounts()=runTest {
        val ctx=context();var owner="a"
        val offline=TalkChatHistory(ctx,backgroundScope,{ _,_->throw java.io.IOException("offline") },{owner})
        offline.save(chat("offline message",5));advanceTimeBy(1000);runCurrent();offline.close()
        val writes=mutableListOf<String>()
        val restored=TalkChatHistory(ctx,backgroundScope,{ uid,request ->
            if(request.getString("action")=="save")writes+=uid+":"+parseTalkChat(request.getJSONObject("chat")).lines.single().text
            JSONObject().put("chats",if(uid=="a")JSONArray().put(chat("offline message",5).json())else JSONArray())
        },{owner})
        restored.refresh();runCurrent();assertEquals(listOf("a:offline message"),writes)
        owner="b";restored.refresh();runCurrent()
        assertEquals(1,writes.size)
        assertTrue(restored.chats.value.isEmpty())
        restored.close()
    }
    @Test fun deletingDuringSaveDoesNotResurrectChat()=runTest {
        val ctx=context();val gate=CompletableDeferred<Unit>()
        val archive=TalkChatHistory(ctx,backgroundScope,{ _,request ->
            if(request.getString("action")=="save")gate.await()
            JSONObject().put("chats",JSONArray().put(chat("old",1).json()))
        },{"a"})
        archive.save(chat("old",1));advanceTimeBy(1000);runCurrent()
        archive.delete(chat("old",1).id);gate.complete(Unit);runCurrent()
        assertTrue(archive.chats.value.isEmpty());archive.close()
    }
    @Test fun longUnicodeRepliesStayWithinWireBudgetAndKeepMoreThanTwelveShortMessages() {
        assertTrue(chat("😀".repeat(6000),1).json().toString().toByteArray().size<=21_000)
        val lines=(1..40).map { TalkLine(if(it%2==0)"assistant" else "user","Message $it") }
        assertEquals(40,parseTalkChat(chat("",1).copy(lines=lines).json()).lines.size)
    }
}
