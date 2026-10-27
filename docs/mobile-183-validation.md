# Mobile 3.1.183 research and validation

Scope: Android mobile, existing Gemini Live/Tavily/TMDB paths, Aliflix actions only. TV version and release remain unchanged. Original follow-up request is retained in [talk-183-user-request.md](talk-183-user-request.md).

## Failure model and researched decisions

| Failure or practical concern | Decision | Primary reference |
| --- | --- | --- |
| Independently ordered input transcripts could call beginUserTurn and flush speech without any new microphone activity. | A single local microphone detector owns explicit activityStart/activityEnd. Transcripts only append to an already owned microphone turn. Typed turns reject late microphone transcripts. | [Gemini Live capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities), [WebSocket reference](https://ai.google.dev/api/live.md) |
| A silent local tool's turnComplete raced its WHEN_IDLE continuation: the client injected another user prompt immediately. | No immediate recovery prompt at a tool-only completion. Give the scheduled continuation its own bounded response window. Delayed reconnect continuation also checks the same user turn, microphone, generation and rendered output. | [Live tool scheduling](https://ai.google.dev/gemini-api/docs/live-api/tools) |
| Two stacked high-confidence/duration gates and a network acknowledgement delayed genuine interruption. | Exact 32 ms Silero frames; 64 ms confirmed onset; local AudioTrack stop before sending activityStart; 800 ms retained prefix and 704 ms endpoint silence. Probability is evaluated against its actual frame, never an asynchronously stale frame. | [Silero source](https://github.com/snakers4/silero-vad/blob/master/src/silero_vad/utils_vad.py), [maintainer tuning guidance](https://github.com/snakers4/silero-vad/wiki/FAQ) |
| Echo suppression and human floor ownership were intertwined. | Bounded far-end correlation is evidence only. The single speech gate owns prefix/timing. Reference expires after its output tail; no old speech vetoes a later user. | [Android AEC API](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler), Silero source above |
| A fixed approved search phrase and cached PCM made identical requests sound identical. | Remove the phrase pool, cached bridge playback and in-progress filler injection. Generate one fresh-call welcome and compose the actual recommendation narration for the current request. Reconnects and voice changes do not greet again. | [Live native audio capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities) |
| Whole-answer transcript validation delayed the first recommendation audio. | Validate selected identities and model-composed narration before speech, then stream that approved answer. Uncommitted recommendations remain withheld. No fixed colon-list template is required in protocol 9. | Live streaming/tool contracts above |
| A spoken voice-change acknowledgement did not prove a new voice configuration. | Reopen a constrained session with the chosen voice; acknowledge only after the new session is ready. Preserve conversation, language and the completed command. | [Live session management](https://ai.google.dev/gemini-api/docs/live-api/session-management) |
| Download navigation was merely an options screen. | Persist exact episode/quality/subtitle specifications, resolve and verify tracks, then enqueue the existing native foreground download service. Talk shows actual stages and transfer progress. Conversation replacement does not cancel explicit background work. | [Media3 downloads](https://developer.android.com/media/media3/exoplayer/downloading-media) |
| A busy orb kept retargeting during transient phases and speech text. | Restrained context destinations, retained critically damped velocity, shorter attention range, sustained title attention and no random gaze waypoints. Keep one face across layouts. | [Material motion](https://m3.material.io/styles/motion), [Lumaform Regard source](https://github.com/abektes/lumaform-orb/blob/main/packages/orb/src/engines/regard-engine.js) |
| Voice controls mixed stop-output, mute and end. | Voice mode exposes mute/unmute and end. Actual background work is independently visible and cancellable; silencing speech does not silently cancel a download. | [Google call control conventions](https://developers.google.com/cars/design/automotive-os/apps/dialer/interaction-model/managing-calls) |
| Animation was requested from a specific reference. | Port the actual four morph paths, keyframes, stagger and easing to native Canvas, with Aliflix colours and reduced-motion support. No remote WebView or demo media is shipped. | [Jatin Yadav Morph Loading](https://21st.dev/@jatin-yadav05/components/morph-loading), [attribution](third-party-morph-loading-attribution.md) |

## Adversarial reassessment

- Confirmed turn ownership is separate from provider events, transcripts, tool scheduling and transport recovery. Increasing a VAD threshold alone would not fix the tool-continuation race and would worsen human interruption latency.
- A provider-only interrupted event cannot masquerade as microphone activity and flush already queued speech. A genuine local activity immediately abandons stale narration and turn-scoped lookup work.
- No automatic recovery prompt may replace active microphone input, generation or speaker output. A valid session resumption first lets the provider continue its own answer.
- Queued, resolving, caption verification, enqueue, transfer and completion are distinct. Arabic language codes are normalized against the actual native enum. A requested unavailable subtitle/unknown rendition fails; it is never replaced silently. Source verification uses the real native flow only when the viewer opens it.
- Preparation specifications survive process loss and Activity changes; a resolver needing an Activity resumes when an Aliflix Activity is available. Media3 owns already queued transfers. The voice session owns neither transfer lifetime nor its cancellation.
- Active work survives the bounded history list. Account changes invalidate preparation ownership and refresh workspace context. Cancelling completed work cannot claim that a completed file was undone.
- App context returns structured supported commands, current preferences, selected versus connected voice, actual jobs and factual navigation. It does not substitute cached documentation for observed execution or promise unavailable tracks.
- Series status belongs to its own selector; it does not light up the separate Filters action. Japanese and American animation are compatible OR origins within Animation, rather than a single mutually exclusive value.

## Physical evidence collected during development

These results precede the final frozen-source gate; final release evidence must identify the final source and tests separately.

- Pixel 7a `41301JEHN02777`, matching debug signer, data-preserving `adb install -r`; no uninstall/wipe or emulator.
- Two turn-ownership tests passed: late transcript/provider activity cannot stop the real AudioTrack; owned speech through real Silero interrupts locally in **302 ms**, before a network acknowledgement.
- Cold spoken comedy, sci-fi, developed Beehive discussion and “And?” passed with zero self-interruptions in the sampled speaker/microphone turns. This run exposed whole-answer buffering delay, which was then removed.
- Fresh generated welcome, actual Charon session/acknowledgement, and discussed Color of Pomegranates card passed, with zero user activities or self-interruptions during output.
- Real Futurama S1E1 LOW/ar request reached Media3 and continued transferring during an unrelated question; receipt showed 57% transfer at capture. LOW selected the lowest verified rendition on that discovered source (720p in this run), not a fictional lower resolution.
- Twelve repeated owned speech clips played through the actual speaker with actual microphone/AEC active: **41.522 seconds**, 898,200 rendered samples, **zero microphone activities**, selected volume 10/15. The app did not change system volume.
- Twelve physical speaker/discovery/orb tests passed, including initially collapsed Genres, simultaneous Japanese/American selection, one primary loader/no For You after searching, stable layout reading, and one retained face across eight measured contexts.

## Failed checks that led to corrections

- Initial live connection failed because the client inspected a setup field intentionally omitted by constrained-token provisioning. Explicit inputActivity metadata and a mint-contract regression now cover that boundary.
- The first real Arabic download command failed because the tool supplied lowercase `ar` while the native enum stores uppercase `AR`. Shared normalization and native transfer checks cover the corrected path.
- The initial unrestricted local Worker run encountered local runtime/storage errors and a WatchParty timeout. A bounded-concurrency run passed; its result is recorded separately from the failed run.

Owned speech injection is synthetic input through the production pipeline on physical hardware. It is not proof of a human speaking in every room, headset or network condition. Debug installation is not a production-signer upgrade test. Public artifact, updater and signer verification must be reported separately.

## Final candidate results

The final mobile candidate is **3.1.183 / 273**, installed on the Pixel 7a without clearing data. Production Android source remained unchanged throughout these final hardware checks. A later Worker-only prompt audit removed the old instruction to explain taste connections, so that it cannot contradict the prohibition on unsolicited personal-match explanations.

| Gate | Result |
| --- | --- |
| Android unit suite | 762 tests, zero failures/errors, 3 skipped |
| Android lint | 0 errors, 173 warnings, 4 hints; lint, mobile APK, test APK and TV debug assembly succeeded in 5m57s |
| Worker typecheck / tests / dry-run | All passed; 272 tests across 29 files, final suite 24.23s |
| Turn ownership | All 3 physical cases passed: late/provider metadata cannot flush audio; real neural input stops output locally; mute preserves output and short Stop then interrupts |
| Captured local interruption | 279ms for the longer owned fixture; 410ms for the short Stop after mute/unmute, including fixture leading audio |
| Wire-order replays | All 8 passed, including the silent-tool continuation race, late transcription and abandoning superseded work |
| Playback independence | An 80ms reply started and drained through the real AudioTrack with microphone reads stopped |
| Speaker/microphone stress | 12 owned speech clips, 41.425s, 898200 rendered samples, zero microphone activities; actual speaker route, volume10/15, no app volume change |
| Orb | One retained face stayed inside the viewport through all 8 measured contexts, including title attention, thinking, chat and return |
| Discovery UI | All 10 passed, including collapsed Genres, simultaneous Japanese/American origins, Running/Ended without Filters selection, stable reading and one loader/no For You after submission |
| Morph renderer | Separate real Activity-frame-clock check passed: the central loading shapes changed between captures, outside Compose's infinite-animation test policy |
| Live voice / native actions / spoken discovery | All 3 journeys passed in201.724s. Fresh welcome, actual Charon session and acknowledgement, real discussed-title card, constrained background download, comedy/sci-fi, developed Beehive discussion and And continuation |
| Additional live interruption journey | Passed in114.729s against the deployed backend: developed 3-Iron discussion with real speaker/capture and zero self-barges; And continued it; a closed 90-minute Primer/Vast of Night comparison performed no recommendation search; owned spoken interruption then produced exactly one local barge and one service interruption |

The final spoken journey recorded **0 interruptions and 0 unsolicited provider interruptions**. Its first-audio timings were 11.892s,14.561s and12.547s from the beginning of each owned spoken request; those include the utterance itself and any research. Speech rendered without a second utterance. The final voice-change/card journey also recorded zero interruptions and selected/connected voice both Charon.

The exact download entered Media3 as tv:615:s1:e1, quality720p (the selected source's lowest verified rendition), subtitleLanguage ar,16768 caption characters,128788503 transferred bytes and64.912285percent at capture. It continued while Talk answered an unrelated question. Only the test-created job was cancelled afterward.

One final UI batch initially passed23/24: the remaining assertion targeted a leaf Text semantics node rather than its parent button. Correcting that selector and rerunning the entire 10-test UI suite passed; no production change was made. An earlier replay fixture's automatic socket and the spoken fixture's legitimate greeting barge were also corrected, with the original production assertions retained. Controlled Compose screenshots do not prove animation playback; the additional real Activity-clock test verifies that separately.

The normal-clock morph captures and the Talk film-card capture were visually inspected. The Pixel is left on Home with test voice sessions suspended. No emulator, data wipe or production-signer replacement was used.

Additional protocol cross-check: Google's [manual activity wire sequence](https://github.com/google/skills/blob/main/skills/cloud/gemini-live-api/references/client_server_messages.md) agrees with the explicit activityStart/audio/activityEnd framing. Timing values are app choices supported by the tests above, rather than universal guarantees from the provider.
