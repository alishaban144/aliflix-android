package com.aliflix.app.talk

/** A model-composed observation, never a rotating phrase bank. It may not
 * announce searching, promise an outcome, name provisional picks or outlive
 * the user turn. Its complete spoken transcript must match before playback. */
internal fun talkResearchNoteAllowed(text:String)=text.length in 12..240&&
    !Regex("https?://|\\b(?:searching|looking for|hang on|please wait|bear with|stay with you|i(?:'m| am) (?:finding|checking|looking)|i(?:'ll| will) (?:find|search|look))\\b",RegexOption.IGNORE_CASE).containsMatchIn(text)

internal data class TalkResearchNote(val turn:Long,val text:String,val expiresAt:Long)

/** A tool-only turn can complete before its continuing response starts
 * speaking. That empty boundary does not belong to the accepted bridge. */
internal fun talkResearchNoteHasOutput(text:String,hasAudio:Boolean)=text.isNotBlank()||hasAudio

internal fun talkResearchNoteEligible(textMode:Boolean,lookupPending:Boolean,outputPlaying:Boolean,usedTurn:Long,currentTurn:Long)=
    !textMode&&lookupPending&&!outputPlaying&&usedTurn!=currentTurn

/** The note is held until its complete transcript is approved. Recover a
 * stalled, unheard note, while leaving any audible output uninterrupted. */
internal fun talkResearchNoteNeedsRecovery(note:TalkResearchNote?,turn:Long,now:Long,outputPlaying:Boolean)=
    note!=null&&note.turn==turn&&now>=note.expiresAt&&!outputPlaying
