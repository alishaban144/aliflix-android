package com.aliflix.app.talk

import org.json.JSONObject

internal fun talkPendingContinuation(question:String,result:JSONObject?,state:JSONObject):String {
    if(result!=null)return "Continue the unanswered user request using this completed app lookup. It belongs to the current request and completed independently of the voice generation. Do not repeat completed actions, research or recommendations. Follow the lookup's instruction, including present_recommendations approval if required. Keep the established language and spoiler boundaries; do not announce callback or transport bookkeeping. User request: "+question+"\nCompleted lookup: "+result.toString().take(24000)
    return "Finish this unanswered user request: "+question+". Reuse the established exact title identities in app state: "+state+". If its lookup was interrupted, perform that lookup again. Keep the established language and spoiler boundaries. Avoid repeating completed speech or acknowledging this reconnection."
}

/** A queued acknowledgement closes its provider function ID. Its later facts
 * are app-owned context, even when the underlying socket has not changed. */
internal fun talkLookupUsesContinuation(acknowledged:Boolean,sameSocket:Boolean)=acknowledged||!sameSocket
