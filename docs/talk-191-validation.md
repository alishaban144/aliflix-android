# Talk 3.1.191: voice entry, scrollable bubble and visual scene reasoning

## Interaction and motion

Opening player Talk selects speech mode, pauses playback, captures the current scene and starts preparing its temporal evidence. Typing is an explicit keyboard-icon choice. Reopening retains the conversation while placing earlier messages above the new live edge; scrolling retrieves the complete retained chat in both directions. The player reply has no Stop control. Voice return dismisses the composer.

The orb observes player-window touches without consuming them. Its existing spherical gaze follows the touch with damping, then recenters. The glow uses transparent radial gradients and circular clipping instead of the Android composed mask that could expose a square offscreen layer. Entry combines a damped spring, vertical settling and fade; exit reverses into a smaller retreat. System reduced motion is respected.

Talk chat loading is a native adaptation of [animbits Morphing Loader](https://21st.dev/@animbits/components/loaders-morphing), MIT. The inspected public preview uses a **2-second** cycle with 20/50/20/50/20% corner radii, 0/90/180/270/360-degree rotation and 1/1.2/1/1.2/1 scale. The native lavender shape retains those timings and eases each segment; it does not gate response completion.

## Scene evidence and research

A delivery-cache defect could suppress richer images after a prior one-frame context update. Its identity now includes the actually delivered frame timestamps. The initial context also includes a bounded temporal sequence for spoken questions, and the final human activity boundary refreshes the current question context before ending speech. Opening warms independent observation and earlier-source decoding rather than waiting for the first question. Observation has a bounded foreground wait; original temporal images remain available when that auxiliary service is slow.

Visual referring expressions now route to image evidence even when phrased as unusual questions. The answer contract explicitly resolves clothing, positions and objects; rejects absent or ambiguous referents and false premises; separates dialogue, visible action and inference; and requires another temporal lookup for an unsupported motion gap. This improves evidence use, not universal scene omniscience.

[Official Live capabilities](https://aistudio.google.com/docs/live-api/capabilities) and [API reference](https://aistudio.google.com/api-reference/live) establish the need for app-owned timestamps and boundaries: realtime video is limited to one frame per second and cross-stream arrival order is not guaranteed. Native source decoding and timestamped image packets remain distinct from pretending a still is a video clip. Research informed paired false-premise and missing-evidence tests, atomic answer checks, and separate scoring of visible facts and inferred causes. [OVO-Bench](https://arxiv.org/abs/2501.05510) distinguishes backward tracing, current understanding and waiting for sufficient evidence; [MovieRecapsQA](https://openaccess.thecvf.com/content/CVPR2026/html/Shaar_MovieRecapsQA_A_Multimodal_Open-Ended_Video_Question-Answering_Benchmark_CVPR_2026_paper.html) motivates claim-level evaluation.

## Validation record

Results, corrections and signed/public artifact verification are recorded below after the physical checks.

### Corrections established by the phone checks

The landscape keyboard initially squeezed the composer below its minimum height. The reading area now receives remaining weighted space after the fixed controls and composer, and the 17-case physical rerun passes both orientations. The scroll test now uses the correct merged/unmerged semantics, and the gaze test explicitly advances the Compose animation clock; its pixel checks establish actual eye movement and unchanged background pixels at the orb corners. UI-only player fixtures now use an explicit debug-only local connection instead of consuming live model sessions; genuine scene/voice tests retain live transport.

The first Parasite response was correct but slow: about 9.8 seconds were spent decoding caption-search matches for a current visual question. Independent earlier-video decoding now requires an earlier/timestamp request or genuinely insufficient current footage. Its retained rerun delivered useful text in 5.667 seconds, versus 15.974 seconds initially, with only 92 ms of transcript/recall preparation. The full answer identifies the rear passenger, her station/exit/boyfriend claim, and its unverified status.

The auxiliary observer returned 503 while server logs showed upstream 429 responses. It now backs off for 60 seconds after failure instead of retrying for every question; direct timestamped images and dialogue remain available to Live. One initial Better Call Saul turn also met explicit Live quota exhaustion; a later normal-capacity attempt completed the full answer. The app neither changes billing nor rotates keys to evade limits. The final image-question gate checks actual multi-frame delivery or a validated independent observation, rather than requiring the optional service even when direct images support the reviewed answer.

### Reviewed new scene cases

These questions were written after inspecting the footage and earlier story, before asking the model. Full unabridged answers and atomic checklists are in [the response record](talk-191-scene-results.json).

| Title / pause | Distinct reasoning checked | First useful / complete |
|---|---|---|
| Parasite / 30:00 | Driver versus rear passenger; stated station/exit/boyfriend reason versus corroboration. | 4.568 / 6.212 s |
| The Truman Show / 30:00 | Production instruction versus traffic report; Sylvia callback and limits of what one radio line proves. | 5.731 / 7.516 s |
| Severance / 20:00 | General versus personal memory, expected survey result, and the three-request release protocol. | 3.010 / 5.667 s |
| Better Call Saul / 20:00 | Alleged fracture versus established fact, video claimant, and attempted prevention of a police call. | 6.039 / 7.301 s |

All four retained English answers pass their final checks and manual claim review. This is evidence for these tested cases, not a guarantee for every title, cut or physical action. Captions can be imperfectly aligned, sparse frames can miss brief actions, and provider capacity remains external. No universal catalogue-wide index is claimed.

The final source passes the affected 161 host tests and mobile lint. The earlier broad gate passed 869 host tests and TV compatibility assembly. Physical final coverage passed 21 checks (20 combined plus the separately recorded native transition), followed by six larger-text/reduced-motion checks and two explicit static-gaze/long-answer checks. The speaker run rendered 898,200 samples over 41.721 seconds with zero activity events; two real capture-path interruptions measured 219 and 264 ms. A real English ASR/AudioTrack/Stop/resume probe retained the same conversation. Its deliberately interrupted recommendation is transport/continuity evidence, not a recommendation-quality result.

Whole-screen review includes the native entry/exit recording, the empty reopening, long streaming answers, full retained-chat scrolling, and both keyboard orientations. The bubble is opaque so movie subtitles do not show through its response text. Settled live-answer captures verify that the close/keyboard controls remain inside the bubble. Literal keyword checks initially rejected correct variants such as “the man drives while the woman sits in the back”; that oracle now accepts the observed semantic equivalent. Manual role/causality checks remain separate from keyword matching.

### Exact-detail, false-premise and spoken-scene follow-up

A later repeat preserved the correct reason but omitted the station exit number. The first candidate pipeline was canceled before publication. Exact-detail turns now receive a bounded verbatim current-dialogue cross-check, and the shared image packet gives both spoken and typed turns the same rules for factual qualifiers, visual referents, causes, claim status and complete question coverage. Bare pronoun/color questions such as “Is she wearing red?” also route to visual evidence.

The retained final Parasite answer includes the rear passenger, boyfriend claim, Hyehwa station **exit #3**, and the lack of corroboration. Its first useful text arrived in 4.568 seconds. In the same conversation, the follow-up deliberately misread her clothing and movement. Talk corrected both: dark clothing, not red, and still a rear-seat passenger. It completed that follow-up in 7.799 seconds without changing the conversation identity or language.

A separate **spoken** question on The Truman Show asked whether the radio could be a normal traffic update and why it said “extras.” Actual English ASR reproduced the question correctly; the answer explained that the voice was directing extras on a set. The test waited for the entire AudioTrack queue to drain: **201,120 written and rendered samples**, zero queued bytes, no writing/playing/speaking, final state Listening, same conversation, and no false barge-in. The raw receipt retains a recovered earlier session-expiry close; it is not presented as an uninterrupted network connection. Speech latency is measured from the start of the 5.535-second fixture, not compared directly with typed-question latency. All six reviewed question turns (four distinct titles, one contextual false-premise follow-up and one spoken scene question) are in the response record.

Actual swipe-down/up gestures additionally verify history navigation, followed by programmatic access to the oldest and live-edge items. The final reduced-motion gaze test asserts static eyes when Android animations are disabled; the motion-enabled physical pass separately asserts directional pixel movement. Test settings are restored afterward. TV is unchanged; the final voice-language backend changes are described below.

### Keyboard, caption gestures and release-gate corrections

The first complete CI device gate stopped at a system microphone-permission dialog: voice-first fixtures now explicitly grant the permission before injecting app-window gestures. The denied-permission initial state was reproduced on the physical phone, and all three affected native checks then passed.

A final holistic pass found two further interaction defects. Caption dragging beneath Talk could intercept bubble touches; its gesture bounds are now disabled while Talk is visible and restored on close, with an actual rendered-caption regression. The landscape IME could consume the entire history viewport. Landscape typing now shares one row with the controls, keeps the same focused composer while layouts move, and preserves reading space. The regression enters real text in both orientations and uses the actual Send action after swiping through history. The original failed attempts are retained locally rather than counted as passes.

History remains scrollable in speech mode too: opening the orb controls creates a reading area anchored after the retained messages, and scrolling back through older messages does not activate typing or the keyboard. A seven-check physical run verifies this voice-only history gesture, oldest/live-edge access, explicit typing, actual Send, both-orientation input, caption isolation, reopening and orb rendering. Four larger-text/reduced-motion checks separately cover that layout.

### Final voice-language, mode and halo review

Voice defaults to English and retains the chosen conversation language until a clear explicit request changes it. Sentence length, question marks and foreign script in ASR no longer choose a new language. Quoted or reported requests such as a character saying “speak Arabic” remain scene evidence. Typed questions still select their intentional written language. The native first welcome uses the established language rather than the device locale. The Worker now applies the same distinction in its conversation and scene contracts, and supplies documented input-transcription language hints. Native audio output does not support a forced speech language code; the [Live capabilities guide](https://aistudio.google.com/docs/live-api/capabilities#change-voice-and-language) and [transcription configuration](https://aistudio.google.com/api-reference/live#AudioTranscriptionConfig) distinguish these mechanisms.

The player now has one mode control: a keyboard icon in voice mode and a voice icon in typing mode. Its state comes directly from the session, removing the separate composer flag that could leave the microphone paused. Selecting voice reconnects the same conversation; collapsing or moving the typing bubble also returns to voice. Sending text dismisses the keyboard while retaining the explicit typing mode and draft control.

The halo now has rotating off-centre feathered lights. Rotating a symmetrical radial gradient had produced little visible movement. The shared Ask/player renderer preserves transparent circular clipping and freezes the halo under reduced motion. Pixel checks verify visible movement outside the face and unchanged background at the corners.

This final review passed **163 Android host tests**, Worker typechecking and **312 Worker tests**, followed by **21 physical UI/replay checks** and **six larger-text/reduced-motion checks**. Coverage includes repeated mode toggles with retained drafts, speech-mode history, actual Send, both keyboard orientations, caption gesture isolation, activity/late-ASR ordering, visible halo movement and transparent corners. Live microphone restart and language/scene checks, followed by signed/public release evidence, are recorded below when completed.

### Final live and production verification

The real Live mode-return test passed on the connected Pixel 7a. Two voice reconnections each reached Listening with AudioRecord source 7, an active audio lease, platform echo processing, English response language and the same conversation identity. This verifies actual microphone capture after leaving typing, rather than only checking the icon or a mocked session.

The additional noise prelude retained English and the same conversation. The spoken scene question was transcribed exactly. Three final end-to-end scene attempts did not complete a response: the last receipt records Google closing the Live WebSocket with code 1011 and an explicit exceeded-quota message. An intermediate resumption was rejected as expired. These attempts are retained as failures, not included among the six reviewed successful scene turns above. The auxiliary observer separately returned 503. No credentials were rotated and no billing or quota limits were changed. Provider capacity therefore remains an unresolved operational limit; the latest full spoken-scene answer could not be revalidated under that limit. The [official session guide](https://aistudio.google.com/docs/live-api/session-management) and [Live best practices](https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/live-api/best-practices) were rechecked while diagnosing the disconnects.

The signed workflow [41907843205](https://github.com/alishaban144/aliflix-android/actions/runs/41907843205) passed changed-path detection, playback device checks, Worker checks, backend deployment and release creation. The published tag points to **221594f738ca02bf1106ff0adc41ad9bff0b9338**. The tested backend update is deployed.

| Public artifact | Version / ABI | Size (bytes) | SHA-256 |
|---|---|---:|---|
| aliflix-mobile.apk | 3.1.191 / 281, arm64-v8a | 27,465,568 | `4b566dc60941428e27d9cbd7962200097e2ac7406e996d4a3e50b1fb4bb9a68a` |
| aliflix-mobile-compat.apk | Intentional compatibility bridge: 3.1.175 / 265, four ABIs | 69,151,718 | `1b13a22df5d0dd056beda9d13df7028660d438ca84e77fcd807f669b381e09a0` |

Both APKs verify with production signer SHA-256 **a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47**. ZIP integrity, embedded native/model hashes and APM provenance match the release parity report. The ARM64 updater advertises 281; the universal updater intentionally retains the compatible bridge and its ABI coverage. Both retain minimum SDK 29 and empty in-app release notes.

Before installation, the installed production certificate was compared with the incoming certificate. The ARM64 APK then installed in place successfully, launched normally, retained the original first-install timestamp and preserved both baseline recently-played entries across the horizontal list. The initial viewport-only data check was corrected to inspect that scrolling row; it was not a data-loss defect. Whole-screen startup and retained-data screenshots were reviewed. The physical phone remains awake and charging.

[v3.1.191](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.191) is published, not draft. Anonymous downloads through the canonical `releases/latest/download/` routes returned both manifests and both APKs byte-for-byte equal to the verified signed draft. TV sources/version and TV publication are unchanged.

### Product and engineering walkthrough

The four-page [Whimsical orchestration](https://whimsical.com/L7GWbKpBuqBbsjMTRm1Y3T) covers the system, [player retrieval](https://whimsical.com/DfrbBpLmVixzNcZapDyJ5F), [research/recommendations/native actions](https://whimsical.com/Pd5849r73Cbvh6dHhsBFiv), and [data, trust, evidence and limitations](https://whimsical.com/RCqKEzAwYmptgqBvNdHB2A). All three diagrams were rendered and visually reviewed. The repository copy is [talk-orchestration.md](talk-orchestration.md). It distinguishes direct Android-to-Live media from backend tools and distinguishes Embedding 2 recommendation ranking from source-bound scene recall.
