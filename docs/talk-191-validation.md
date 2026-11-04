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
| Parasite / 30:00 | Driver versus rear passenger; stated station/exit/boyfriend reason versus corroboration. | 5.667 / 5.871 s |
| The Truman Show / 30:00 | Production instruction versus traffic report; Sylvia callback and limits of what one radio line proves. | 3.229 / 5.311 s |
| Severance / 20:00 | General versus personal memory, expected survey result, and the three-request release protocol. | 3.010 / 5.667 s |
| Better Call Saul / 20:00 | Alleged fracture versus established fact, video claimant, and attempted prevention of a police call. | 6.039 / 7.301 s |

All four retained English answers pass their final checks and manual claim review. This is evidence for these tested cases, not a guarantee for every title, cut or physical action. Captions can be imperfectly aligned, sparse frames can miss brief actions, and provider capacity remains external. No universal catalogue-wide index is claimed.

The final source passes the affected 161 host tests and mobile lint. The earlier broad gate passed 869 host tests and TV compatibility assembly. Physical final coverage passed 21 checks (20 combined plus the separately recorded native transition), followed by six larger-text/reduced-motion checks and two explicit static-gaze/long-answer checks. The speaker run rendered 898,200 samples over 41.721 seconds with zero activity events; two real capture-path interruptions measured 219 and 264 ms. A real English ASR/AudioTrack/Stop/resume probe retained the same conversation. Its deliberately interrupted recommendation is transport/continuity evidence, not a recommendation-quality result.

Whole-screen review includes the native entry/exit recording, the empty reopening, long streaming answers, full retained-chat scrolling, and both keyboard orientations. The bubble is opaque so movie subtitles do not show through its response text. Settled live-answer captures verify that the close/keyboard controls remain inside the bubble. Literal keyword checks initially rejected correct variants such as “the man drives while the woman sits in the back”; that oracle now accepts the observed semantic equivalent. Manual role/causality checks remain separate from keyword matching.
