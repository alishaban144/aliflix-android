package com.aliflix.app.talk

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TalkDiscoveryFollowUpDeviceTest {
    @Test fun otherDifferentOnesReturnsNewCardsInTheSameChat() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTalkDiscovery") == "true")
        val activity = launchFreshTalkActivity(localAudio = true)
        val voice = activity.voice
        try {
            runBlocking {
                withContext(Dispatchers.Main) { voice.setTextMode(true) }
                suspend fun ask(question: String): List<String> {
                    val start = voice.state.value.lines.size
                    withContext(Dispatchers.Main) { voice.sendText(question) }
                    return withTimeout(65_000) {
                        var cards = emptyList<String>()
                        while (cards.isEmpty()) {
                            val state = voice.state.value
                            check(state.phase !in setOf("Try again later", "Reconnect", "Audio unavailable")) { state.phase }
                            if (state.answerComplete && !state.sending) {
                                cards = state.lines.drop(start).filter { it.role == "assistant" }.flatMap { line -> line.titles.map { it.key } }
                            }
                            if (cards.isEmpty()) delay(100)
                        }
                        cards
                    }
                }
                val first = ask("Suggest some movies to watch tonight")
                val second = ask("Can you give me some other different ones?")
                assertTrue("The follow-up returned ${second.size} titles", second.isNotEmpty())
                assertTrue("The follow-up repeated a first-turn title", second.none(first::contains))
                android.util.Log.i("TalkDiscoveryCheck", "Initial movies=${first.size}; additional movies=${second.size}; repeats=0")
            }
        } finally {
            instrumentation.runOnMainSync { voice.suspendSession(); activity.finish() }
        }
    }
}
