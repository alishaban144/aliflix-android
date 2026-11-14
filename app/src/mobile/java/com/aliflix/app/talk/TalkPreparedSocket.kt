package com.aliflix.app.talk

import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

internal class TalkPreparedSocket(
    http: OkHttpClient,
    request: Request,
    val credential: JSONObject,
    val context: JSONObject,
    val key: String,
    scope: CoroutineScope,
) {
    val ready = CompletableDeferred<Boolean>()
    private var valid = true
    private var receiver: ((JSONObject) -> Unit)? = null
    private var failure: ((Boolean) -> Unit)? = null
    private var setupAt = 0L
    private var handle: String? = null
    val socket = http.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(JSONObject().put("setup", credential.getJSONObject("setup")).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (text.length > 1_000_000) { webSocket.close(1009, null); return }
            val message = runCatching { JSONObject(text) }.getOrNull() ?: return
            scope.launch(Dispatchers.Main.immediate) {
                if (!valid) return@launch
                val active = receiver
                if (active != null) active(message)
                else when {
                    message.has("setupComplete") -> {
                        setupAt = SystemClock.elapsedRealtime()
                        ready.complete(true)
                    }
                    message.has("error") || message.has("goAway") -> close()
                    else -> message.optJSONObject("sessionResumptionUpdate")?.let {
                        if(it.optBoolean("resumable"))handle=it.optString("newHandle").takeIf(String::isNotBlank)
                    }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(if(code==1005)1000 else code,null)
            scope.launch(Dispatchers.Main.immediate) { failed(false) }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch(Dispatchers.Main.immediate) { failed(response?.code==429) }
        }
    })

    fun claim(receive: (JSONObject) -> Unit, onFailure: (Boolean) -> Unit): Boolean {
        if (!valid || setupAt == 0L || SystemClock.elapsedRealtime()-setupAt > 30_000) return false
        receiver = receive
        failure = onFailure
        handle?.let { receive(JSONObject().put("sessionResumptionUpdate",
            JSONObject().put("resumable",true).put("newHandle",it))) }
        return true
    }

    private fun failed(limited: Boolean) {
        if (!valid) return
        valid = false
        ready.complete(false)
        failure?.invoke(limited)
    }

    fun close() {
        valid = false
        ready.complete(false)
        socket.close(1000, null)
    }
}
