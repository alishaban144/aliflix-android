# Aliflix mobile 3.1.183

This release addresses repeated speech cutoffs, slow human interruption and the gap between Talk's claimed actions and real native execution.

1. **One owner for microphone turns.** Confirmed local speech opens and closes explicit Live activity. Late transcripts and provider activity metadata cannot start a new user turn or flush playing audio.
2. **Fixed the silent-control continuation race.** A tool-only completion no longer injects a second user prompt over a scheduled WHEN_IDLE answer. Recovery waits for microphone, generation and output to be idle and retains the same user-turn boundary.
3. **Faster human interruption.** Exact 32 ms neural frames replace the asynchronous probability queue and stacked gates. Playback stops locally after confirmed onset, before a server round trip; an 800 ms prefix retains opening words.
4. **Speech-aware pause handling.** Short clause continuations retain their text while superseded partial lookups are cancelled. A genuine replacement abandons the old answer. Mute closes an open input activity once.
5. **Removed repeated search speech.** The fixed genre/mood phrase pool, cached bridge audio, and in-progress filler injection are gone.
6. **Natural recommendation narration.** The model composes the current answer in connected prose. Canonical selections and that narration are checked together before playback; approved audio streams immediately instead of waiting for a whole-answer transcript buffer.
7. **Generated fresh-call welcome.** The model composes a brief welcome in the device language with warm prosody. No preset greeting pool, automatic feature pitch or repeated reconnect greeting.
8. **Real voice changes.** A voice command creates a newly configured session and acknowledges through the selected voice after readiness. The completed command remains in conversation continuity.
9. **Background downloads inside Talk.** An exact movie or episode request resolves the source, selects the requested quality tier, verifies requested captions and enters the existing native Media3 download service. Arabic and other native language codes normalize correctly.
10. **Observable and controllable work.** Background work shows real preparation stages, actual rendition, transfer progress, pause/resume, retry, source verification and cancellation. A new conversation turn does not cancel an explicitly requested download. Preparation specifications persist; active work cannot be trimmed out of view by history limits.
11. **Removed the static task starter.** The three-star task entry and canned Catch up/Plan nights/Track releases chips are removed. Actual work appears contextually and remains accessible through the conversation menu.
12. **Simpler voice controls.** Voice mode has microphone mute/unmute and end. The extra stop-audio button is removed.
13. **Calmer, purposeful orb attention.** Smaller context shifts, retained motion velocity, sustained title attention and restrained thinking/listening transitions replace large phase-to-phase travel. Random gaze waypoints are removed; the same face moves through voice, cards, chat and panels.
14. **Cards follow film discussion.** The conversation contract requires real cards for discussed titles as well as explicit card requests, with exact established identities retained for follow-ups.
15. **Current capability context.** Structured commands, actual settings, selected/connected voice, current jobs, navigation and concrete boundaries replace long static feature descriptions. Background monitoring consent and native verification remain explicit.
16. **Requested Morph Loading animation.** Similar and Describe use the native adaptation of Jatin Yadav's four-shape morph animation, with Aliflix colours, original timing/stagger/easing and a reduced-motion state. One primary loader remains visible; For You stays hidden after a search begins.
17. **Filter corrections.** Genres starts collapsed. Japanese and American animation can be selected together as alternative origins within Animation. Running/Ended selections no longer activate the separate Filters highlight.

Research, reassessment and qualified test evidence are recorded in [validation](mobile-183-validation.md). Source attribution for the requested loader is [here](third-party-morph-loading-attribution.md).
