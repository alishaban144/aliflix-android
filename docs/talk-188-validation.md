# Player Talk validation — 3 November 2026

Branch: `codex/talk-188-scene-recall`.

## Release and device audit

At the initial audit, authenticated GitHub Releases inspection across every page found no drafts. The latest public mobile release was `v3.1.187`, published 31 October 2026, version code 277. PR #12 was open at inspection.

The Pixel 7a was connected and authorized. Its production APK at that audit was version 3.1.187. The installed and downloaded release APKs have identical uncompressed ZIP entries, including code and resources; their outer signing-container fingerprints differ. The downloaded release matches the published arm64 update manifest. An older code/resource build was therefore not established by the outer fingerprint difference.

The upper scene/dialogue/replay shortcuts are already removed in this release's source. A physical-device palette test passed in portrait and landscape: only microphone/connection, typing, conversation expansion and close remain; the removed controls are absent and the remaining targets satisfy 48dp bounds. Saved screenshots were inspected locally.

## Delivered changes and deployment

- Independent whole-title transcript retrieval without enabling subtitles or replacing their displayed edition.
- Chronological earlier dialogue, selected windows, exact timestamp retrieval and actual earlier video frames.
- A separate read-only decoder with the same provider data-source adapters; retrieval does not seek the viewer's player.
- Timestamp-focused context and a focused recent window for immediate previous-scene questions. Full story retrieval remains available through `scene_recall`; opening/named-event/recap questions can receive the broader prefix.
- Explicit participant-identity uncertainty, current-question response language, and stronger causal/story instructions.
- A typed-turn delivery correction: native retrieval precedes the answer timer, and the current live connection is rechecked before sending the question.
- Gemini Embedding 2 in the shared embedding provider, production/test configuration and generated bindings. The model-2 task-prefix and batching contract replaces the old `taskType` request. Vectors must have 768 finite dimensions. Existing recommendation comparisons compute both sides afresh; no stored old-model vector is mixed with a new one.

The user explicitly approved the shared backend deployment. The initially deployed Worker version was `a435b366-275d-4969-93fd-fc9d682522c9`; its configuration uses `gemini-embedding-2`. The native client also negotiates the published v14 contract for older backends.

During initial validation, native changes were installed in the separate `com.aliflix.app.validation` package for testing. At that stage, the production phone package and GitHub APK release had not been replaced; the user subsequently authorized release publication. Local release-signing environment variables are not configured.

## Automated checks

| Check | Result |
|---|---|
| Complete Worker suite | 310 tests across 34 files passed after the final observer-routing change |
| Final Worker prompt/provider checks | Included in the final full suite |
| Worker TypeScript check | Passed |
| Worker deployment dry run | Passed before approved publication |
| Android Talk host suite | 152 tests passed |
| Mobile validation APK and instrumentation APK | Built successfully |
| Mobile debug lint | Passed; final refresh recorded in the local validation log |
| Phone decoder-isolation fixture | Passed: three earlier frames, future request filtered, viewer remained paused at the same clock position |
| Phone palette in both orientations | Passed |

Expected rejection tests print a `SESSION_EXPIRED` exception and a Worker event-handler warning while the suite still passes. These are test diagnostics, not evidence of a successful live request.

## Genuine English questions on the Pixel

First useful text is measured after more than 30 answer characters; completion is measured separately. Timings start when the typed question is submitted, not when title playback begins. The harness seeks to 36 seconds before the pause and plays that short current window; much earlier requested material was not played in that session.

| Title and pause | Question | Result | First useful text | Complete |
|---|---|---|---:|---:|
| Dark S1E1, 15:00; German audio | What happened immediately before this scene, and how does it connect to what is happening now? | Correctly identified Erik's father's confrontation with police and their response. The source dialogue and prior-scene selection were checked. | 5.325 s | 6.941 s |
| Dark S1E1, 25:00; German audio, subtitles off | What happened at 6:20? | Retrieved actual frames at approximately 6:12, 6:17, 6:20 and 6:23, plus the earlier German dialogue. The answer described the caress/kiss without the previously guessed woman's name. Subtitle display stayed off. | 4.970 s | 5.645 s |
| Inception, 75:00 | What happened immediately before this scene, and how does it connect to what is happening now? | Correctly recalled the preceding limbo conversation and connected it to the current alternate-will/safe discussion. Checked against the supplied timed dialogue. | 7.062 s | 8.952 s |
| Dark S1E1, 25:00; German audio, subtitles off | How did the opening voiceover describe time? | Correctly explained the opening narration's connected past/present/future and circular-time account in English. The actual opening German transcript was present even after the midpoint seek. | 9.752 s | 9.839 s |

The timestamp case spent 3.417 s in native recall and 0.067 s refreshing scene metadata. The opening-narration case spent 0.033 s in native recall; most remaining latency was model processing. The final Inception case supplied 165 recent-context cues rather than an unrelated full-prefix payload.

These checks used genuine English questions without an instruction to answer in English. They verify typed turns with microphone capture muted, not microphone/echo performance.

## Rejected experiments and resulting corrections

- The first previous-scene selection treated the latest subtitle window as the current cinematic scene, although the paused image had already transitioned. It selected the wrong preceding exchange and took 27.6 s. The latest exchange is now retained as a candidate, and caption windows are explicitly not declared cinematic boundaries.
- A cold earlier-timestamp check initially guessed an unsupported woman's name and exceeded the 12 s target. Identity handling was tightened, frames were centered on the requested timestamp, and unrelated current-scene images were removed from timestamp answers.
- A later cold timestamp experiment took 43.2 s. Timestamp-specific story prefixes and relevant-image delivery reduced the final check to 5.0 s for useful text.
- Initial Inception attempts timed out following live-socket failures, including close code 1011. Typed delivery now rechecks the socket after native preparation and starts the answer deadline at delivery. Focused previous-scene context then produced the accepted film result above. A single accepted run does not prove network outages are eliminated.
- A localhost backend attempt could not mint a Live session with the locally available Gemini credential. It was rejected as validation evidence. Successful live checks used the authenticated production backend; no credential value is included in this report.

Raw frames, audio, transcripts and device traces remain local under ignored validation storage and are excluded from repository/release assets. A private validation APK is retained locally.

## Additional random film and episode checks

The additional selection used a recorded random seed (`18058847393301836713`) and a random interior pause point. The title/episode and timestamp were retained across failed and accepted attempts; they were not replaced with easier cases. Before asking Talk, the actual frames and timed transcript were inspected to establish the expected causes and destination independently.

| Title and pause | Genuine English question | Independently reviewed result | First useful text | Complete |
|---|---|---|---:|---:|
| The Truman Show, 64:42 | Why did they make the father drown, and why are they bringing him back now? | Correctly connected the staged drowning to Truman's fear of water and containment on the island; identified the father's return as the creator's way to end the crisis. Both parts match the earlier/current dialogue. | 9.510 s | 10.572 s |
| Severance S1E3, 29:29 | Why did Mark warn Helly that swallowing a note would not work, and where are they taking her now? | Correctly explained detection of a message hidden inside the body and identified the Perpetuity Wing as the destination, rather than confusing the current O&D encounter with the trip's destination. | 8.582 s | 9.575 s |

Both accepted runs supplied actual temporal video evidence, independent title-blind observations and timestamped earlier story context. Both answered in English without an English-language instruction, kept playback paused, used no external plot lookup and revealed no future events. The transcript prefix was available despite seeking directly to the later scene. Movie recall took 88 ms and remaining observation wait 7.404 s; episode recall took 59 ms and observation wait 5.641 s. These are measured cases, not a universal latency guarantee.

The initial movie answer was rejected: it invented a dramatic motive for bringing the father back. Later initial attempts hit a Google Live quota error without answering. A minimal text-only capacity probe then succeeded in 4.181 s. The final corrections restore native evidence on reconnect, avoid decoding an already sufficient captured sequence, remove duplicate transcript passages, prefer structured independent observation with a delayed Live hedge, and send two corroborating current frames after a successful sequence observation. The final full Worker suite has 310 passing tests. The final pre-release backend used in these two accepted runs is `576d1f33-919c-4d4e-ae4d-ba38d88a7467`, configured with `gemini-embedding-2`.

The mobile release candidate is 3.1.188, version code 278. The CI workflow runs the complete Worker suite, all Talk host tests, mobile lint and the offline scene/palette/decoder device fixtures before signing. Original signing identity, native binary/model parity and empty app updater descriptions remain release requirements. The live scene tests are opt-in and were run on the connected physical Pixel; CI fixtures do not substitute for these plot checks.

## Limits and next implementation

This is a tested improvement to transcript and footage retrieval, not a guarantee of correct answers to every question about every title. Sparse frames can miss brief actions; external captions can be absent, incomplete or imperfectly aligned; actor/speaker identity needs explicit evidence; network/model failures remain possible. These runs do not establish universal sub-10-second latency or universal current-action grounding.

The prepared per-edition semantic scene/story index, audio-description ingestion, dense question-adaptive clip verification and a larger human-scored catalog benchmark remain the next engineering layer. The research plan specifies Gemini Embedding 2 and playhead-filtered retrieval for that index. It is not yet a deployed catalog-wide scene database.

See [research and implementation plan](talk-188-scene-research-and-plan.md) and [research field definitions](talk-188-research/fields.yaml).
