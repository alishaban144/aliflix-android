package com.aliflix.app.talk

/** A model-composed observation, never a rotating phrase bank. It may not
 * announce searching, promise an outcome, name provisional picks or outlive
 * the user turn. Its complete spoken transcript must match before playback. */
internal fun talkResearchNoteAllowed(text:String)=text.length in 12..240&&
    !Regex("https?://|\\b(?:searching|looking for|hang on|please wait|bear with|stay with you|i(?:'m| am) (?:finding|checking|looking)|i(?:'ll| will) (?:find|search|look))\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)

internal data class TalkResearchNote(val turn:Long,val text:String,val expiresAt:Long)

internal fun talkResearchNoteEligible(textMode:Boolean,lookupPending:Boolean,outputPlaying:Boolean,usedTurn:Long,currentTurn:Long)=
    !textMode&&lookupPending&&!outputPlaying&&usedTurn!=currentTurn

/** The note is held until its complete transcript is approved. Recover a
 * stalled, unheard note, while leaving any audible output uninterrupted. */
internal fun talkResearchNoteNeedsRecovery(note:TalkResearchNote?,turn:Long,now:Long,outputPlaying:Boolean)=
    note!=null&&note.turn==turn&&now>=note.expiresAt&&!outputPlaying
