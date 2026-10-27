package com.aliflix.app.talk

import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule

/** Stage owned fixtures with run-as in private storage; older device runs may
 * still use app-created external storage. Shell-owned external folders are not
 * readable by ordinary app processes on every Android storage implementation. */
internal fun talkValidationFixture(name:String):java.io.File {
    val context=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val privateFile=java.io.File(context.filesDir,"talk-validation/$name")
    return privateFile.takeIf{it.isFile}?:java.io.File(context.getExternalFilesDir(null),"talk-validation/$name")
}

/** Keep synthetic turns out of the viewer's real archive without changing preferences. */
internal fun freshTalkValidationRule(localAudio:Boolean=false) = AndroidComposeTestRule(
    ActivityScenarioRule<TalkVoiceActivity>(Intent(ApplicationProvider.getApplicationContext(),TalkVoiceActivity::class.java)
        .putExtra("validationFreshHistory",true).putExtra("validationLocalAudio",localAudio)),
    activityProvider={rule->lateinit var activity:TalkVoiceActivity;rule.scenario.onActivity{activity=it};activity},
)
