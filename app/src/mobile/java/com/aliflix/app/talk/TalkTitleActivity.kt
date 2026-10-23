package com.aliflix.app.talk

import com.aliflix.app.MainActivity
import com.aliflix.app.model.Media
import org.json.JSONObject

/** A regular native details screen above Talk. Back returns to the same chat,
 * orb and topic; the existing conversation is never replaced or discarded. */
class TalkTitleActivity : MainActivity() {
    override val conversationAction: JSONObject? by lazy {
        intent.getStringExtra("assistantAction")?.let{runCatching{JSONObject(it)}.getOrNull()}
    }
    override val conversationTitle: Media? by lazy {
        runCatching { Media.fromJson(JSONObject(intent.getStringExtra("title")?:"{}")).takeIf{it.id>0&&it.title.isNotBlank()} }.getOrNull()
    }
}
