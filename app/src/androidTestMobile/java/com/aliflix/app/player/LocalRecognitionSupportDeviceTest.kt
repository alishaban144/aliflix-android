package com.aliflix.app.player

import android.content.Intent
import android.speech.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Read-only platform capability diagnostic. No microphone or media is supplied. */
@android.annotation.TargetApi(34)
class LocalRecognitionSupportDeviceTest {
    @Test fun inspectInstalledOnDeviceSpeechLanguages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("inspectLocalSpeech")=="true" && android.os.Build.VERSION.SDK_INT>=34)
        val instrument=InstrumentationRegistry.getInstrumentation();val context=instrument.targetContext
        val rows=JSONArray()
        for(language in listOf("en-US","de-DE","es-ES")) {
            val result=CompletableFuture<JSONObject>();var recognizer:SpeechRecognizer?=null
            instrument.runOnMainSync {
                if(!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) result.complete(JSONObject().put("unavailable",true))
                else {
                    recognizer=SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                    val request=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE,language).putExtra(RecognizerIntent.EXTRA_REQUEST_WORD_TIMING,true)
                        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE,true)
                    recognizer!!.checkRecognitionSupport(request,context.mainExecutor,object:RecognitionSupportCallback {
                        override fun onSupportResult(support:RecognitionSupport) {
                            result.complete(JSONObject().put("installed",JSONArray(support.installedOnDeviceLanguages))
                                .put("supported",JSONArray(support.supportedOnDeviceLanguages)).put("pending",JSONArray(support.pendingOnDeviceLanguages)))
                        }
                        override fun onError(error:Int) { result.complete(JSONObject().put("error",error)) }
                    })
                }
            }
            try { rows.put(result.get(7,TimeUnit.SECONDS).put("language",language)) }
            finally { instrument.runOnMainSync { recognizer?.destroy() } }
        }
        File(context.getExternalFilesDir(null),"local-speech-support.json").writeText(rows.toString())
    }
}
