# Aliflix mobile 3.1.178

This release starts from v3.1.177 and keeps the TV version and TV UI unchanged.

## Changes and failure causes

- Talk previously treated an initial spoken acknowledgment as a finished answer and could leave asynchronous results waiting. Completed lookups now explicitly resume generation, retain their title cards and rearm a bounded answer deadline. A lookup that has not produced speech sends a streaming `willContinue` progress response for one contextual bridge. A short grace period avoids duplicating a bridge already being generated.
- The microphone still uses continuous native capture, server automatic VAD and native AudioTrack playback. New questions cancel abandoned tools; backgrounding still closes capture and the socket. A greeting is not required.
- Describe/Similar now use Tavily, one Gemini selection and direct TMDB identity matching. Talk supplies its own Gemini candidates, avoiding a second model selection. There is no Clef or narrative verification stage in this path. Explicit filters, exclusions and account boundaries remain.
- The Android stream parser previously rejected valid reordered whole-list snapshots, retained a few early titles and cancelled the rest. Revisions can now replace/reorder the list while request ownership, valid catalogue identities and duplicate checks remain enforced.
- Compact film/series poster rails sit below the voice topic and below the relevant chat reply. The orb and changing topic remain visible. Cards open the existing native details experience above Talk and return to the same conversation. Archives retain the cards.
- Surprise Me shows three choices without automatically opening one. Dislike sits inside the compact result card and removes it immediately. The My Space update indicator sits left of Settings.

## Checks

- Android local unit gate: 713 tests, no failures/errors, three skipped. Mobile lint, mobile app/test APK assembly and shared TV debug assembly pass. CI repeats its configured unit/lint, signing, native/model parity and phone regression gates.
- Worker: typecheck, 234 tests, deployment dry run and production deployment pass. Tests cover the simplified model/search path, streaming, shared cancellation, exclusions and card archive payloads.
- Physical Pixel 7a: authenticated USB ADB; matching debug signer; replacement installs preserve app data. Fourteen UI checks pass, including voice/chat card placement, retained orb/topic, three-choice Surprise Me and immediate in-card dislike. Native details opens from a card and Back returns to the same chat.
- Live mobile Describe and Similar finish with useful complete selections after the stream-parser correction. The test rejects incomplete results and requires at least 12 real identities per broad request.
- Live Talk acceptance streams owned speech saying “movies about gentlemen” through automatic VAD without an audio-stream-end signal or greeting. It then asks for less violent stylish films and recent science fiction about identity. Each turn must return at least two real identities, render native audio and name a returned title; a bridge alone cannot pass. The successful progress-response run produced first audio at 5.09 s including connection and speech input, then 1.07 s and 0.83 s for follow-ups, with zero stall recoveries. Spoken choices included Kingsman/The Gentlemen, The Remains of the Day/The King's Speech and The Creator/The Pod Generation/Spaceman.
- Public-service Describe/Similar probes return 24 titles each, with first results in 0.5–0.9 s when Tavily research/identities are cached and one new Gemini curation takes approximately 6–7 s overall. Surprise Me returns three choices in approximately 2.8 s. These are observed requests, not a latency guarantee; provider capacity and uncached search can take longer.
- The existing authenticated publication gate passes with 24 movie and 22 series daily suggestions. Daily editorial collections retain their existing refresh lifecycle.

## Evidence limits and release artifacts

The phone has the debug signer already installed, so physical runtime evidence is from a debug build of these sources. It does not prove installation of the production-signed APK over that debug app. No app-data wipe or account replacement is used. Owned speech exercises live ASR/VAD on the physical phone and native rendered audio; this is not a human acoustic microphone test.

The signed main APK remains ARM64. The universal compatibility bridge and original native/model assets retain their existing release checks. The release publishes SHA-256, byte size and ABI in `update-mobile-arm64.json`, native parity in `native-parity.json`, and the compatibility manifest in `update-mobile.json`. The current mobile app checks the ARM64 manifest; older clients keep the universal bridge route. Final public download and canonical updater verification occur after publication.

External title quality, speech phrasing and latency vary. Tavily findings are context, not proof of every generated recommendation premise; TMDB supplies identities/artwork and factual catalogue fields. No generated relevance probability is presented as verified confidence. Unrelated search findings are omitted from citations, and source outages are not reported as successful browsing.
