package com.aliflix.app

import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/** Start account hydration alongside startup; rendering never awaits the network. */
class MobileAliflixApplication : AliflixApplication() {
    override fun onCreate() {
        super.onCreate()
        accountScope.launch {
            yield()
            accountServices
            com.aliflix.app.talk.TalkAccountArchive.get(this@MobileAliflixApplication)
        }
    }
}
