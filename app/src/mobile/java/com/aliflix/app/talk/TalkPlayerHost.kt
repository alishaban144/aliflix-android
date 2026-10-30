package com.aliflix.app.talk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.lang.ref.WeakReference

/** The visible native player owns all source, subtitle and synchronization work. */
internal interface TalkPlayerHost {
    fun talkSnapshot(): JSONObject
    suspend fun talkCommand(command: JSONObject): JSONObject
}

internal object TalkPlayerConnection {
    private var reference = WeakReference<TalkPlayerHost>(null)
    fun attach(host: TalkPlayerHost) { reference = WeakReference(host) }
    fun detach(host: TalkPlayerHost) { if (reference.get() === host) reference.clear() }
    fun current(): TalkPlayerHost? = reference.get()
    suspend fun await(contentKey: String? = null,launchTaskId:String?=null): TalkPlayerHost = withContext(Dispatchers.Main.immediate) {
        withTimeout(90_000) {
            while (true) {
                val host = current()
                if (host != null && (contentKey == null || host.talkSnapshot().optString("contentKey") == contentKey) &&
                    (launchTaskId==null||host.talkSnapshot().optString("launchTaskId")==launchTaskId)) return@withTimeout host
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") error("Player unavailable")
        }
    }
}
