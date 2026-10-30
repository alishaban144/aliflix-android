package com.aliflix.app.talk

internal data class TalkConversationHandoff(val state:TalkVoiceState,val language:String?)

/** Ephemeral handoff only: no transcript in an Intent and no extra history storage. */
internal object TalkConversationRoutes {
    private val conversations=linkedMapOf<String,TalkConversationHandoff>()
    fun publish(value:TalkConversationHandoff) {
        conversations.remove(value.state.conversationId)
        conversations[value.state.conversationId]=value
        while(conversations.size>8)conversations.remove(conversations.keys.first())
    }
    fun latest(id:String)=conversations[id]
    fun clear(){conversations.clear()}
}
