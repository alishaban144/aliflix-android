package com.aliflix.app.talk

import org.json.JSONObject

internal fun talkPendingContinuation(question:String,result:JSONObject?,state:JSONObject):String {
    if(result!=null)return "Continue the unanswered user request using this completed app lookup. It belongs to the current request and survived a transport reconnection. Do not repeat completed actions, research or recommendations. Follow the lookup's instruction, including present_recommendations approval if required. Keep the established language and spoiler boundaries; do not acknowledge the reconnection. User request: "+question+"\nCompleted lookup: "+result.toString().take(24000)
    return "Finish this unanswered user request: "+question+". Reuse the established exact title identities in app state: "+state+". If its lookup was interrupted, perform that lookup again. Keep the established language and spoiler boundaries. Avoid repeating completed speech or acknowledging this reconnection."
}
