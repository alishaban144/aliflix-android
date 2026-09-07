package com.aliflix.app.player

import android.app.Activity
import android.content.Intent

internal object NativePlaybackLauncher {
    fun ownsStream(url: String?): Boolean = url != null && NativePlaybackService.activeStreamUrl == url

    fun launch(activity: Activity, request: NativePlaybackRequest) {
        // The Activity starts/binds the service while visible, satisfying background-start rules.
        val intent = Intent(activity, NativePlayerActivity::class.java)
        if (NativePlaybackService.activeStreamUrl != request.url) {
            val name = "native-request-${java.util.UUID.randomUUID()}.json"
            java.io.File(activity.cacheDir, name).writeText(request.toJson())
            intent.putExtra("requestFile", name)
        }
        activity.startActivity(intent, nativePhoneLaunchOptions())
    }
}

/** File handoff avoids Binder's transaction limit for long subtitle tracks. */
internal fun nativeRequestPayload(context: android.content.Context, intent: Intent, consume: Boolean = false): String? {
    intent.getStringExtra("request")?.let { return it }
    val name = intent.getStringExtra("requestFile") ?: return null
    require(name.matches(Regex("native-request-[a-f0-9-]{36}\\.json")))
    val file = java.io.File(context.cacheDir, name)
    require(file.length() in 1..8_388_608)
    return try { file.readText() } finally { if (consume) file.delete() }
}
