# Mobile 3.1.168 validation

Baseline: published mobile v3.1.166/code 256. Main APK: 3.1.168/code 258; universal update bridge: 3.1.167/code 257. TV sources, version and release are unchanged.

## Playback and downloads

- Filmo is the default movie source, Serienstream the default series source, and AniWorld the default Japanese anime series source. Explicit player choices remain available. A priority source is not abandoned on a timeout, broken catalogue schema, parsing failure or challenge. Automatic fallback requires an explicit, successful catalogue response proving absence.
- Filmo uses its normal English catalogue, exact title/year verification, CSRF/session-bound link resolution and actual VOE multi-audio metadata. The mobile VOE redirect uses a different path from desktop; origin-based validation now accepts the observed HTTPS origin without guessing its changing route shape. Cookies remain origin-scoped, redirect counts and response sizes are bounded, and a stream must validate before winning.
- AniWorld verifies title/IMDb identity, maps TMDB seasons to the site's absolute episode numbering, excludes non-series search results, and preserves Japanese editions with English/German hardsubs. A missing mapping never falsely proves absence. Stale cached mappings are removed after a failed validation.
- Source audio edition switches resolve while the current edition continues, cancel superseded requests, preserve position/pause/captions/manual delay, and commit only after readiness. Actual Media3 track changes execute against the service's TrackGroup and confirm decoded PCM; applying feedback and rollback remain integrated.
- Download discovery uses the same priority policy. Audio and subtitle selectors are above the episode list. Actual container tracks and separate source editions are distinguished; separate videos are never advertised as a single combined all-audio download. Expanded episodes show loading and retain selections/season anchors through retries.
- Filmo's advertised Byse mirror displays a video-owner embedding restriction on the tested origin. It is not advertised as playable, and that restriction is not bypassed. Filmo's VOE route was verified separately.

## Mobile experience

- Logo, ALIFLIX and Movies / Series / Stories start together within the existing short launch sequence.
- Details restores the pre-party Play/Resume, download, like and My List actions. Watch Together remains in player More. Choosing another title/episode while in a room requires the custom Stay / Leave & play action before changing playback or history.
- The player center control is 76dp with a 50dp icon; its animation is unchanged. The mobile back arrow retains its 48dp target with the background removed.
- The next-episode card uses an episode still, compact title/season hierarchy, a full-width 48dp Watch credits action and restrained entrance/placement motion. The existing moving ring and playback-driven countdown remain unchanged.
- Episode browsing includes all seasons, current episode, fetched IMDb ratings, loading/retry and adaptive portrait/landscape layout.
- Home vertical scrolling can begin on its pinned filter strip. The selected bottom-navigation pill is removed. Settings ends with a collapsed, read-only Streaming sources catalogue, including all regular/anime providers. Quality and download choices use the shared animated selection mark.
- Small accessible violet update indicators appear at the My Space settings control and App updates row only when an installable newer update is known. Checks run outside rendering, are throttled while visible, preserve known availability through transient failures, and use the existing ABI-aware updater.

## Sync with Audio

The feature remains local. No episode PCM is uploaded to Gemini, Groq or Fish. The original Silero model, ONNX Runtime ARM64 binaries, WebRTC VAD, alignment algorithms, numerical precision, confidence thresholds and held-out validation remain intact. Source/audio/caption/seek/account identity fences, manual-delay separation, reset and cancellation are preserved.

A bounded independent silent decoder starts immediately, reads only the selected soundtrack through the byte cache and never seeks or accelerates the visible player. Actual discontinuous PCM runs remain separate. Measured phrase endings require observed silence; unknown timing and subtitle text cannot invent an ending. Android's optional on-device recognizer now has its required RECORD_AUDIO permission, always uses an explicit audio-file descriptor and never opens the microphone for subtitle synchronization. Local recognition failure retains the native speech-presence path.

Verified Pixel case: German audio / English captions, The Big Bang Theory S1E1, after eight seconds of played dialogue: 1663ms after tapping, offset +0.68s, rate 1, confidence 0.8714897443, zero seek/discontinuity/position change. Earlier German / Arabic S1E2 runs passed at +2.32s and 25/24, but later eight-second repetitions rejected the match. Arabic S1E1, German S1E1 and the English Terminator short-sample cases also have unresolved matching limits. They are not represented as reliable passes. Rejection leaves playback and captions unshifted; no confidence gate was weakened to turn these into successes.

## Talk to Aliflix

Native portrait experience within Ask Aliflix. Continuous turn-taking with a separate instance of the existing WebRTC VAD, microphone mute, audio focus, echo cancellation where Android supplies it, interruption/cancellation, incremental PCM playback, bounded reconnect and ASR rotation are integrated. Capture stops when the experience leaves the foreground. No additional bundled speech model or mobile dependency is introduced.

Server-side models: Gemini `gemini-3.5-transcribe-live` ASR and `gemini-3.8-flash` reasoning; Fish `s2.1-pro-free` WebSocket TTS with the explicit model header. Configured fallbacks are Groq `whisper-large-v3-turbo` / `openai/gpt-oss-120b`, Gemini `gemini-3.8-flash-lite-tts`, then an installed offline Android voice. Recent primary-reasoner failures are remembered briefly to avoid repeatedly paying the same latency. Streaming frames, audio, turns, history, retrieval, rate and daily budgets are bounded. Cancellation fences reject stale output. Empty retrieval cannot create an unbounded tool loop.

`FISH_AUDIO_API_KEY` is an encrypted Cloudflare secret. The supplied plaintext dashboard binding was removed before installing the encrypted secret. No key is stored in Wrangler vars, APKs, Git or this report. Existing recommendation model settings and secrets are preserved.

A separate SQLite-backed TalkSession Durable Object authenticates the existing signed account/temporary guest identity. Voice sockets remain warm for bounded upstream streaming (30 minutes maximum); existing Watch Party sockets retain their separate hibernating implementation. Daily limits are twelve connections per IP and forty globally, with twenty-five turns per session. History retention is off by default; optional retained history expires after seven days and can be deleted. Reconnect context is bounded to twelve turns. Microphone speech is sent only after Start; no playback source URL, cookie, credential or subtitle body is accepted by the Talk protocol.

Evidence is retrieved on demand from TVmaze (CC BY-SA) and Wikipedia/Wikidata (CC BY-SA/CC0), with attribution in the optional transcript. TMDB text/artwork is excluded from new AI prompts because its API terms restrict AI use; opaque identifiers and the viewer's own progress support independent lookup. Jumping ahead does not authorize earlier episodes: only actual completed-episode ranges are accepted, and skipped episodes remain gaps. Retained context is fenced by title, episode and spoiler permissions. Current-episode plot retrieval is denied unless actually completed or spoilers are explicitly allowed. Aliflix's ordinary 80% history-completion flag alone never licenses spoilers. Whole-movie plot sections require completion or permission. No unlicensed transcript/subtitle is uploaded, indexed or treated as timed evidence. When reliable timed evidence is absent, the companion declines current-scene assertions. Facts and interpretations require known source identifiers.

### Executed voice evidence

- Worker typecheck and 147 tests passed, including stream negotiation, MessagePack PCM, free Fish header, cancellation, quotas, authentication, bounded responses, privacy deletion, licence attribution and episode/spoiler boundaries.
- Existing GitHub backend-only workflows deployed the feature and ran the live Watch Party / genre smoke checks without publishing an APK.
- Pixel generated-question pipeline tests passed in English, German and Arabic: real authenticated WebSockets, ASR, reasoning, physical AudioTrack PCM rendering and mid-response interruption. German replied with Firefly; Arabic replied with an Arabic Firefly recommendation. The Arabic test reached rendered audio in 7735ms and used Gemini ASR / Groq reasoning / Fish voice.
- The final English check rendered a grounded recommendation in 9290ms using Gemini ASR / Gemini reasoning / Fish voice, interrupted playback, reconnected and retained the conversation (one passing physical test, 12.49s total).
- These tests inject legally generated questions while the microphone is muted. They verify the live pipeline and physical audio rendering, not an unperformed human acoustic barge-in/echo test. Full real-room multilingual acoustic performance and long-duration rotation are not claimed verified.
- Initial live probes found and fixed binary WebSocket format handling, a setup timer that closed an established ASR socket, and repeated retrieval-tool requests. Initial failed probes are excluded from successes.

## Physical provider evidence

All nine titles reached native video dimensions, playing state and advancing position while retaining their expected priority source: Filmo Interstellar / Green Book / Frozen (2013, distinguished from Frozen 2010); Serienstream Dark S1E1 / The Big Bang Theory S1E1 / Narcos S1E1; AniWorld Black Clover / Death Note / Cowboy Bebop S1E1. Filmo exposed English/German tracks; AniWorld exposed Japanese editions; Dark's actual German download edition and download request were verified. Narcos used actual German audio and is not Spanish-audio evidence.

Rapid German/English/German source switching preserved the exact paused 125000ms position and captions. Two independent AAC tones verified actual native English/German PCM switching. Source startup, subtitle availability and provider identity receipts are private ignored validation artifacts, not inferred from catalogue labels.

The final Pixel awake suite passed all 59 tests; the separate next-episode geometry suite passed 3 tests. Portable tests passed 665 cases with three opt-in native skips; the actual native-host gate passed 10 tests with zero skips. Mobile lint passed with zero errors. The final TV debug APK and mobile Kotlin compilation also passed; no TV release was made.

The optimized real particle sample rendered 133 frames with one missed deadline (0.75%) on the Pixel; its moving word and two actual frames were reviewed. This is a short physical sample, not an all-device or all-load performance guarantee.

The follow-up had one connected Pixel. Prior v166 two-phone cross-provider Watch Party results remain prior evidence; they were not rerun with two devices here. The secure-lock test that previously required a manual unlock is not counted among awake UI successes.

## Design and API references

- [Pixel Flow Field, Edu Calvo / SmoothUI](https://smoothui.dev/docs/components/pixel-flow-field): cached word rasterization, coarse flow, scatter/re-form and touch wake, adapted to native Canvas with Aliflix colours.
- [Motion Primitives morphing dialog](https://motion-primitives.com/docs/morphing-dialog), [Aceternity animated modal](https://ui.aceternity.com/components/animated-modal), [Stateful button](https://ui.aceternity.com/components/stateful-button).
- [Compose badge guidance](https://developer.android.com/develop/ui/compose/components/badges), [Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection).
- [Gemini live transcription](https://ai.google.dev/gemini-api/docs/live-api/live-transcribe), [Fish WebSocket protocol](https://docs.fish.audio/api-reference/endpoint/websocket/tts-live), [TVmaze API/licence](https://www.tvmaze.com/api), [TMDB API terms](https://www.themoviedb.org/api-terms-of-use).

## Release evidence

The release workflow checks every native/model SHA-256 against v3.1.164, both APK signers and ZIPs, version/ABI/update compatibility and emulator playback. Independent public artifact checks, exact byte sizes and final physical/CI counts are recorded in mobile-168-publication.md after publication. Third-party websites, networks, arbitrary subtitle editions and future provider changes cannot be guaranteed indefinitely.
