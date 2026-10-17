package com.aliflix.app.talk
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.*
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real direct Gemini Live / physical AudioTrack. Synthetic owned PCM avoids
 * recording ambient conversations. No episode soundtrack is ever uploaded. */
class TalkGeminiLiveAcceptanceDeviceTest {
 @get:Rule val compose=createAndroidComposeRule<TalkVoiceActivity>()
 @Test fun tenContinuousTurnsPinnedVoiceMultilingualInterruptionAndResumption(){
  Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkAcceptance")=="true")
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val session=compose.activity.voice
  val receipts=JSONArray()
  val prompts=listOf(
   "Explain the ending of The Shining (1980).",
   "What does the final photograph mean? Distinguish what is shown from interpretation.",
   "Erkläre auf Deutsch, warum die Bedeutung dieses Fotos nicht eindeutig ist.",
   "اشرح بالعربية الفرق بين ما نراه في الصورة وبين تفسير تناسخ جاك في الفيلم نفسه.",
   "Explain Jack and the Overlook in more detail, including several different interpretations.",
   "Back to The Shining (1980): what can we establish as fact about the final photograph?",
   "Discuss only Dark (2017) season 1 episode 1. What do we know about Mikkel by the end of that episode? No later-episode spoilers.",
   "@pcm-de", "@pcm-ar",
   "Return to Dark season 1 episode 1 only. Explain the mystery as it stands there without any later revelations."
  )
  try { runBlocking {
   for((index,prompt) in prompts.withIndex()){
    val before=session.state.value.lines.count{it.role=="assistant"}
    val began=android.os.SystemClock.elapsedRealtime()
    withContext(Dispatchers.Main){
     if(prompt.startsWith("@pcm-"))session.debugInjectUtterance(File(context.getExternalFilesDir(null),"talk-validation/question-${prompt.takeLast(2)}.pcm").readBytes())
     else session.debugInjectText(prompt)
    }
    withTimeout(40000){while(session.debugFirstAudioAt==0L)delay(20)}
    assertEquals("gemini-3.8-live",session.debugModel);assertEquals("Aoede",session.debugVoice)
    val item=JSONObject().put("turn",index+1).put("prompt",prompt).put("model",session.debugModel).put("voice",session.debugVoice)
      .put("firstRenderedAudioMs",session.debugFirstAudioAt-began)
    if(index==4){
     val interruptions=session.debugInterruptions
     withContext(Dispatchers.Main){session.debugInjectUtterance(File(context.getExternalFilesDir(null),"talk-validation/question-en.pcm").readBytes())}
     withTimeout(12000){while(session.debugInterruptions<=interruptions)delay(20)}
     item.put("nativeBargeIn",true)
    }
    withTimeout(65000){while(session.debugFirstAudioAt==0L||session.debugTurnCompleteAt<session.debugFirstAudioAt||session.state.value.phase=="Speaking")delay(50)}
    val answer=session.state.value.lines.lastOrNull{it.role=="assistant"}?.text.orEmpty()
    assertTrue("Expected substantive native output transcription: $answer",answer.length>20)
    if(index==0){assertTrue(answer.lowercase().let{ "jack" in it && ("1921" in it||"photograph" in it) });assertFalse(answer.contains("permission",true));assertFalse("Incorrect survivor/driver: $answer",answer.contains("driven by Dick Hallorann",true));assertFalse(answer.contains("Hallorann drives",true))}
    if(index==1)assertTrue(answer.lowercase().let{"photograph" in it||"photo" in it||"1921" in it})
    if(index==6||index==9)for(leak in listOf("Mikkel is Michael","Mikkel becomes Michael","Michael Kahnwald is Mikkel","Jonas is Adam"))assertFalse("Future-episode leakage: $answer",answer.contains(leak,true))
    item.put("totalMs",android.os.SystemClock.elapsedRealtime()-began).put("answer",answer).put("renderedSamples",session.debugRenderedSamples)
    receipts.put(item)
    File(context.getExternalFilesDir(null),"talk-validation/gemini-ten-turns.json").writeText(receipts.toString(2))
    if(index==5){
     withTimeout(5000){while(!session.debugResumeAvailable)delay(20)}
     val history=session.state.value.lines
     val previous=session.debugReadyGeneration
     withContext(Dispatchers.Main){session.debugReconnect()}
     withTimeout(20000){while(session.debugReadyGeneration<=previous||session.state.value.phase !in listOf("Listening","Muted"))delay(30)}
     assertEquals(history,session.state.value.lines);assertEquals("Aoede",session.debugVoice)
     item.put("resumed",true)
    }
   }
   assertEquals(10,receipts.length())
  } }catch(error:Throwable){
    File(context.getExternalFilesDir(null),"talk-validation/gemini-failure.json").writeText(JSONObject().put("error",error.toString()).put("phase",session.state.value.phase).put("firstAudioAt",session.debugFirstAudioAt).put("turnCompleteAt",session.debugTurnCompleteAt).put("rendered",session.debugRenderedSamples).put("lastAnswer",session.state.value.lines.lastOrNull()?.text).toString())
    throw error
  }finally { compose.runOnUiThread{session.suspendSession()} }
 }
}
