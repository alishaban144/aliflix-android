package com.aliflix.app.talk

/** One original project and at most two alternatives per user turn. */
internal fun talkCanRotateQuotaProject(limited:Boolean,attempts:Int,hasSession:Boolean)=
    limited && hasSession && attempts in 0..1
