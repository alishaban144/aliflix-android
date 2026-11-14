package com.aliflix.app.talk

internal class TalkOpeningSpeech {
    private val pending = ArrayDeque<Pair<TalkInputTurnGate.Decision, Long>>()
    private var bytes = 0
    private var overflow = false

    fun offer(decision: TalkInputTurnGate.Decision, generation: Long) {
        if (overflow || pending.isEmpty() && !decision.start) return
        bytes += decision.frames.sumOf { it.size }
        if (bytes > 480_000 || pending.size >= 480) {
            clear()
            overflow = true
            return
        }
        pending.addLast(decision to generation)
    }

    fun drain(): List<Pair<TalkInputTurnGate.Decision, Long>> = pending.toList().also { clear() }

    fun clear() {
        pending.clear()
        bytes = 0
        overflow = false
    }
}
