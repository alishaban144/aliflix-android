package com.aliflix.app.player

import kotlin.math.ceil
import kotlin.math.floor

/** Android reports onsets only. A completed phrase may additionally use its
 * independently decoded speech-to-silence boundary. Never infer durations from
 * caption times, unknown decoder gaps, another track, or an unfinished phrase. */
internal fun measuredDialogueEndings(words:List<HeardWord>,speech:List<SpeechWindow>):List<HeardWord> =
    words.mapIndexed { index,word ->
        if(word.measuredEnd || !word.startReliable || !word.start.isFinite()) return@mapIndexed word
        val next=words.getOrNull(index+1)?.takeIf { it.startReliable && it.start>word.start }?.start
        val limit=minOf(word.playedThrough,next?.minus(.08) ?: word.playedThrough,word.start+2.0)
        val window=speech.firstOrNull { it.start<=word.start && it.start+it.speech.size.toDouble()/SPEECH_HZ>=limit }
            ?: return@mapIndexed word
        val from=floor((word.start-window.start)*SPEECH_HZ).toInt().coerceAtLeast(0)
        val until=floor((limit-window.start)*SPEECH_HZ).toInt().coerceAtMost(window.speech.size)
        if(until-from<ceil(.4*SPEECH_HZ).toInt()) return@mapIndexed word
        val frames=window.speech
        if((from until until).any { !frames[it].isFinite() || frames[it] !in 0.0..1.0 }) return@mapIndexed word
        val onsetUntil=minOf(until,from+ceil(.35*SPEECH_HZ).toInt())
        if((from until onsetUntil).none { frames[it]>=.5 }) return@mapIndexed word
        val last=(until-1 downTo from).firstOrNull { frames[it]>=.5 } ?: return@mapIndexed word
        val end=window.start+(last+1).toDouble()/SPEECH_HZ
        if(end-word.start<.08 || limit-end<.30) return@mapIndexed word
        word.copy(end=end,endReliable=true,measuredEnd=true,playedThrough=maxOf(word.playedThrough,end+.30))
    }
