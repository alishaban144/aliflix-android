package com.aliflix.app.talk

import android.content.Intent
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule

/** Keep synthetic turns out of the viewer's real archive without changing preferences. */
internal fun freshTalkValidationRule(localAudio:Boolean=false) = AndroidComposeTestRule(
    ActivityScenarioRule<TalkVoiceActivity>(Intent(ApplicationProvider.getApplicationContext(),TalkVoiceActivity::class.java)
        .putExtra("validationFreshHistory",true).putExtra("validationLocalAudio",localAudio)),
    activityProvider={rule->lateinit var activity:TalkVoiceActivity;rule.scenario.onActivity{activity=it};activity},
)
