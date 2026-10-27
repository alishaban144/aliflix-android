package com.aliflix.app.ui.discover

import android.content.Context
import android.content.Intent
import com.aliflix.app.model.Media

internal fun openAskConversation(context:Context,anchor:Media?=null) {
    context.startActivity(Intent().setClassName(context,"com.aliflix.app.talk.TalkVoiceActivity")
        .putExtra("textMode",true).apply {
            anchor?.let { putExtra("initialTitle",it.toJson().toString());putExtra("initialPrompt","Find titles similar to ${it.title} (${it.year}).") }
        })
}
