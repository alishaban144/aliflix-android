# Mobile player: reliability, design and size implementation plan

Prepared 12 October 2026. Baseline: 834f366 (mobile 3.1.157).
Release candidate: mobile 3.1.158 (248). This document distinguishes delivered changes from follow-up projects.
The Worker, subtitle acceptance rules, dependencies, native libraries and TV version remain unchanged.

## Delivered in 3.1.158

- One sync tap owns a 19-second maximum; verified answers finish immediately. Local matching gets the first chance, then local and online recovery overlap.
- Online recovery creates at most two active requests. A ten-second request timer begins only when the request starts; waiting clips cannot expire in the executor queue.
- Recovery re-reads already-played PCM, prioritizes fresh dialogue when a slot opens and keeps independently recognized clips separate.
- Identical snapshots and fragments with less than three seconds of new audio do not consume another request. The six-request / 120-second aggregate upload ceiling remains intact.
- Known silence is rejected before upload. Audio stays on the same decoded playback clock; no hidden seek or second decoder was added.
- Automatic captions use video metadata available after catalogue lookup, rather than freezing an unknown frame rate at startup. Complete modern film editions and explicit PAL hints help rank candidates; metadata never certifies a sync correction.
- When a stream omits frame-rate metadata, a small read-only estimator measures decoded video PTS cadence. It rejects unstable cadence and resets on source changes/gaps. This is an automatic-selection hint, not audio drift evidence.
- Old automatic caption caches refresh online under a versioned selection policy while remaining visible. Manual choices, offline downloads and unavailable-network caches remain usable; a failed lookup retains the cached captions.
- Cancellation, Reset, identity, manual-delay, ambiguity and held-out/earlier-clock validation remain intact. No confidence threshold was lowered.
- Player controls now use a warm-white Play/Pause control, charcoal action dock and lavender fine timeline. Narrow phones retain title and episode identity above the dock. Timeline accessibility can change playback position; the thumb remains inside the track at either edge.
- More, streaming sources, speed, audio/subtitles and quality use consistent charcoal sheets. Audio Boost moved to More. No descriptive paragraphs were added to the player.
- App-size decision: keep the runtime bundled in this release. Runtime reduction, ABI-specific distribution and optional model data remain separately validated follow-ups below.

Validation and release evidence: [3.1.158 player report](validation/mobile-v3.1.158-player.md).

The initial phone check with eight seconds of The Terminator (1984), English, selected a legacy caption edition and correctly rejected a short-scene rate correction at the 19-second deadline. A reference caption relationship showed a 25/23.976-style rate difference. This prompted the automatic-selection and late-metadata changes above. The 45-second separation requirement for proving an audio rate was retained; no short-scene guessing was introduced.

## Product decision

A viewer should choose subtitles, press Sync with Audio once, and continue watching.
They should never need to know about dialogue windows, speech models, frame rates or provider failures.
The promise is one bounded attempt that manages recovery and gives an honest result.
It cannot be "always correct": silence, an unrelated subtitle file, unsupported audio, or an unavailable network can make a verified answer impossible.
Keep rejection of ambiguous answers. A false "Synced" is worse than a clear explanation.

## What is implemented in this patch

- More uses a compact Material bottom sheet, a fixed header, scrolling content, safe insets, large touch targets and quieter surfaces.
- Sources are behind one Streaming source row, with the active provider/server summary.
- Navigation is More → Streaming source → provider's servers. Back returns one level; Close and outside dismissal end browsing.
- Source browsing does not change playback. A server choice invokes existing switching behavior; choosing the current server closes without restarting.
- Loading, unavailable/empty discovery, retry, current-source checkmarks and the single-source fallback are covered in the menu.
- Playback speed has a dedicated selection page and returns to More after choosing.
- Audio Boost moves from Settings to More, keeping the same saved preference and existing service-owned audio effect.
- Audio Boost reports Off, Preparing, On or Unavailable; casting is explained explicitly and the preference can still be turned off.
- The existing descriptive sync disclosure and added menu explanations are removed at the user's request. The player uses labels, values and concise statuses only; technical/privacy explanations belong in documentation.
- New instrumented regressions cover source browsing/switching, reopening, current-server no-op, and boost state.
- The wider controls changes are listed in the delivered section above; additional device/accessibility coverage remains in the validation plan.

## How the 3.1.157 baseline worked

1. Playback already gathers timestamped speech evidence from decoded media audio. WebRTC VAD and Silero/ONNX identify speech activity.
2. On supported Android 14+ devices, the on-device speech recognizer also collects timed words. Availability and downloaded language support vary.
3. At a tap, NativePlayerActivity checks heard words against the selected subtitles, translated reference subtitles when available, and speech-pattern evidence.
4. If it has no verified solution, it starts Groq recovery. A finite on-device replay can also start after about one second. Recovery overlaps continued local checking.
5. Groq receives WAV clips through the existing Aliflix Cloudflare Worker, which calls whisper-large-v3 for timed words. The phone makes the final subtitle alignment decision.
6. Samples contain 4–20 seconds of 16 kHz mono audio, with up to six scheduled per attempt: at most 120 seconds including overlapping material, roughly 3.84 MB plus headers. Two network threads service them.
7. Only already-played PCM is eligible. The memory ring retains roughly two minutes of recent audio, not a requirement to watch two minutes. Seek/discontinuity clears that PCM continuity.
8. The current attempt has a 9.5-second outer deadline and roughly 9 seconds of matching-loop time. The older 19-second guidance is not the current implementation.
9. Before applying, it checks request ownership, subtitle/source/audio identity, seek continuity, account, correction revision/Reset, manual delay, Cast and playhead change.
10. The Worker receives audio plus ordinary HTTP metadata. The client does not intentionally send media URLs, cookies, subtitle files or titles to this endpoint.
11. Worker code caches the filtered word/timing response under an audio hash with a 24-hour cache lifetime. It does not explicitly write the raw audio to R2 or a database. This code inspection does not establish Groq retention or deployed service configuration.

Relevant files:
- app/src/mobile/java/com/aliflix/app/player/NativePlayerActivity.kt
- app/src/mobile/java/com/aliflix/app/player/PlaybackSpeechBuffer.kt
- app/src/mobile/java/com/aliflix/app/player/RecentDialogueRecognition.kt
- app/src/mobile/java/com/aliflix/app/player/PlayedDialogueRecognition.kt
- app/src/mobile/java/com/aliflix/app/player/GroqDialogueRecognition.kt
- app/src/mobile/java/com/aliflix/app/player/DialogueWordAlignment.kt
- app/src/mobile/java/com/aliflix/app/player/TranslatedSubtitleSync.kt
- recommendation-worker/src/subtitleAudio.ts

## Why a later tap could work in the baseline

These are baseline mechanisms and candidate causes. The delivered scheduler addresses queue starvation and stale snapshots; it does not make silence or unrelated captions matchable.

- New speech and completed recognition segments arrive between taps; a fragment can become a distinctive exchange.
- A seek starts a fresh PCM buffer. Immediate taps may lack enough speech or independent verification.
- Groq uses a fixed PCM snapshot when recovery launches. New dialogue during that attempt does not replace a weak cloud snapshot; the next tap gets a new one.
- All clip coroutines start their seven-second timeout before their tasks obtain one of two executor threads. Later queued clips can time out before receiving useful processing time.
- Local matching runs before cloud launch, consuming part of the shared deadline.
- The Worker allows 25 seconds for Groq while the client reads for 6.5 seconds, bounds each clip at 7 seconds, and ends the whole attempt at 9.5 seconds. A valid slow result can arrive too late.
- A later identical upload may hit the Worker's transcript cache, although this must be observed rather than assumed.
- Translated captions, alternate edits, speech under music and uncertain word boundaries need more corroboration. Weakening those checks would increase wrong corrections.
- Android recognizer/model availability differs across phones and languages.

## Phase 1 — Reproduce and measure before changing acceptance

Deliverable: privacy-safe diagnostic timeline and reproducible failure fixture.

- Capture the reported title/episode, source/server, approximate position, selected audio and subtitle language/edition, phone/Android version, and network class.
- The user has identified The Terminator with English subtitles. Use it as the first regression; confirm the year, scene, selected file and server before attributing the exact failure. Existing AutomaticTerminatorSyncDeviceTest and TerminatorSyncDeviceTest provide starting drivers, but their long collection periods cannot prove the requested short-wait first-tap behavior.
- Reproduce a first tap and subsequent successful tap on a physical phone; keep the same media/subtitle bytes.
- Record phase durations, usable spoken duration, completed phrase count, sample hashes, queue wait, service status, deadline/cancellation reason and rejection reason.
- Keep dialogue text, raw audio, signed media URLs, cookies and account credentials out of telemetry.
- Distinguish no speech, insufficient independent evidence, unavailable recognizer, translated reference unavailable, network timeout/rate limit and genuine subtitle mismatch.
- Establish first-tap success, false correction count, latency and upload bytes. Count readiness from playback start or seek as well as time after tap; do not hide a long collection wait in the metric.

Acceptance: every fixture failure has an attributable reason and a bounded attempt timeline.

## Phase 2 — One tap owns the entire recovery

Deliverable: a small explicit sync coordinator with cancellable phases and deterministic scheduler tests.

- Keep capture/recognizer preparation off the playback/render thread. Prepare local resources when playback and subtitle selection make sync relevant.
- Reuse the best completed recent exchange; rank samples by speech quality, distinctiveness, boundary completeness and independence.
- Allow only two active cloud jobs. Acquire a slot before starting a per-request timeout; all queued work still obeys one overall deadline.
- Start with two useful, distinct clips. Schedule a further request only if it adds information or retries a transient transport error within the remaining time.
- Reassess newly played speech within the same attempt, with sample-hash deduplication. Do not upload overlapping copies indefinitely.
- Stop on a verified answer and cancel unused work immediately.
- Align client/server deadlines and propagation. Do not leave a 25-second upstream request running after a 9.5-second client session when avoidable.
- Begin recovery promptly when local evidence cannot resolve the match; preserve the phone-first privacy boundary and user choice.
- Keep 9.5 seconds as the initial benchmark ceiling. If evidence shows a deliberate recovery extension improves success, propose a visible, cancellable cap no greater than roughly 19 seconds; do not silently inflate the wait.
- Use current evidence to explain progress: Matching dialogue → Checking another phrase → Synced.
- If no speech arrives, finish with "No clear dialogue yet" and retain the existing correction. If offline, explain that online recovery is unavailable without blocking local work.
- Cancellation, Reset, seek, source/audio/subtitle/account change and Cast must invalidate every late result.
- Keep manual delay separate; do not silently switch providers, captions, audio renditions or subtitle editions.

Acceptance: no queued task consumes its service timeout while waiting for a slot; no repeated upload of the same sample inside one attempt; at most two concurrent requests; a single press manages bounded recovery.

## Phase 3 — Reduce time needed after a seek

Deliverable: measured readiness improvement, followed only if needed by an isolated audio-probe experiment.

First use the existing short speech buffer, early local readiness, stronger sample selection and prompt fallback.
Do not require the viewer to play an arbitrary minute.

If that still fails the readiness target, evaluate a separate, bounded audio-only probe around the current position:
- Preserve exact selected stream, audio rendition, headers, media timestamps and discontinuities.
- Never seek the visible player or substitute an alternative source.
- Keep probe evidence distinguishable from actually played evidence; current code deliberately excludes decode-ahead words.
- Enforce a total decode/network/CPU budget; stop on slow origin, DRM, unsupported codec or a mismatched track.
- Use cached bytes for downloaded titles and avoid covert network access in offline mode.
- Validate forward/backward sample clocks against container timestamps before any correction; never invent a time mapping.
- Require separate user-visible design for broader analysis if it cannot fit the one-tap budget.

This is a higher-risk change. Ship only after the simpler scheduling changes are measured.

## Phase 4 — Privacy and service resilience

Deliverable: accurate disclosure and explicit online-recovery control, with resilient service behavior.

- Offer a concise "Online recovery" control and link to the privacy policy; preserve existing user choices through migration. Do not add explanatory paragraphs to the player.
- Document that clips come from the film, the maximum per-attempt transfer, Aliflix/Groq processing and transcript cache behavior outside the player UI.
- Verify actual Groq account retention settings and privacy documentation before making a no-storage claim.
- Use a clearly bounded cache TTL and decide explicitly whether private caching is preferable to the current public response cache header.
- Gate uploads on useful speech, cap attempts/concurrency/bytes, and add rate-limit/backoff and abuse protection without breaking guest use.
- Distinguish rate limiting from a mismatched subtitle. Do not automatically upload continuously in the background.
- Keep Groq secrets on the Worker. Fetching client-supplied video URLs on the Worker is not part of this proposal.
- Add a kill switch for online recovery that leaves local matching and manual offset operational.

## Phase 5 — App size without making first-use sync worse

Measured from a fresh GitHub download of the public v3.1.157 universal APK, verified against its updater hash and size:
- APK: 66,171,961 bytes (66.17 MB decimal).
- ONNX native runtime and JNI across four ABIs: 52,695,764 compressed bytes; 135,303,800 uncompressed.
- Silero model: 1,946,042 compressed bytes; 2,327,524 uncompressed.
- WebRTC VAD JNI across four ABIs: 139,500 compressed bytes.
- Therefore moving only the model to Cloudflare saves about 1.95 MB of APK payload, not the roughly 52.7 MB runtime cost.

Recommended order:
1. Build a reduced ONNX Runtime for the exact Silero model/operators and CPU needs. Keep versioned toolchain, model hash, licenses, JNI compatibility and all currently supported ABIs.
2. Verify decision parity and supported devices; compare compressed APK bytes, installed storage, cold start, memory, CPU and battery using identical app functionality.
3. Consider separate ABI downloads while retaining the universal APK. This requires reliable installer/updater ABI selection, matching manifests/hashes/signers and a universal fallback; never remove support silently.
4. Only then consider an optional model-data pack on Cloudflare R2 served via a Worker/CDN. Require a signed manifest, pinned digest, compatibility version, download size cap, resumable download, atomic activation, last-known-good rollback and low-storage handling.
5. Prefetch only under an explicit data policy. A cold installation must still sync locally with available capabilities or explain the missing optional pack. Never block a tap on a large download.

Do not recommend downloading .so, .dex or .jar libraries from R2 at sync time as the primary architecture. It moves cost into first use, adds code-loading/update risk, and can conflict with Play distribution rules.
Google Play feature delivery is a separate option for a Play-distributed app, not a drop-in replacement for the current direct APK updater.
A Worker can serve model data and coordinate recognition, but it cannot simply execute the Android native AAR. Cloud compute would require a separate compatible service/runtime and introduces ongoing latency, availability and cost.
Keep the compact local matcher and WebRTC path even if a future neural component becomes optional.

## Phase 6 — Premium player design

Direction: a quiet cinema interface with strong hierarchy, precise spacing and restrained accent use.
The scene remains the focus; premium should mean effortless and legible.

- Palette: near-black charcoal surfaces, warm white content, muted secondary text, one lavender accent for selection/progress.
- Replace competing button treatments with one consistent family: 48 dp hit targets, 20–24 dp glyphs, aligned corners and a visible focus state.
- Use one low-contrast top action dock; retain full title/episode identity without squeezing it behind too many icons on narrow phones.
- Give Play/Pause the strongest visual weight, with simpler seek controls and a fine timeline that enlarges only while scrubbing.
- Use clear pressed feedback without springy over-animation; respect system animation scale/reduced-motion behavior.
- Keep portrait sheets and evaluate a compact landscape end panel; both use the same navigation model, typography and row components.
- Show current values on first-level rows; reveal choices one level at a time.
- Keep Audio & subtitles immediately reachable, with a single Sync action, visible Cancel while busy and a separate Reset.
- Use concise error statuses with a helpful action. Keep the player free of descriptive/instructional text; explanations live in documentation.
- Preserve subtitle placement and drag boundaries, gesture zones, lock behavior, brightness/volume, 15-second seeks, PiP, Cast, quality, next-episode and auto-hide behavior.

Design validation: small portrait, landscape, cutouts/navigation insets, 200% font scaling, Arabic RTL, TalkBack, high-contrast bright video, one-handed use and a low-end device.
A phone rendering review is required before calling the design visually finished.

## Phase 7 — Regression and release gates

| Area | Required cases |
| --- | --- |
| Sync accuracy | positive/negative offset, known 25/24 drift, edited scenes, held-out phrases, translated Arabic, alternate editions, wrong captions |
| Sync rejection | silence, music, ambiguous repeated words, no suitable dialogue, unsupported decoder/recognizer |
| Lifecycle | Cancel/Reset, seek, manual delay, source/subtitle/audio/account change, playback speed, pause/resume, Cast, background/foreground |
| Service | offline, slow connection, queue starvation, timeout, 429/5xx, malformed/late response, cache hit/miss, byte/concurrency caps |
| Navigation | nested Back, outside tap, swipe dismissal, reopening, provider discovery race/empty/error/retry, current-server no-op |
| Audio Boost | preference persistence, active output, unavailable effect, session/route changes, Bluetooth, Cast, toggle off |
| Packaging | all supported ABIs, JNI loading, model integrity, minification, size by ZIP entry, signer/update compatibility |
| Accessibility | RTL, TalkBack order/state, 48 dp targets, font scale, landscape scrolling/insets |

Run mobile unit tests, lint, debug app and instrumented-test compilation after implementation.
Run instrumented navigation tests and real sync fixtures on physical Android devices; compilation is not execution.
Run a minified build on the phone before release and compare first-attempt success and false-correction counts to baseline.
Suggested provisional product targets: >95% first-tap success on an agreed representative supported-dialogue set, zero wrong accepted corrections in the regression corpus, p95 within the approved deadline. These are targets, not measured results or an all-films guarantee.
Roll out menu changes, scheduling changes and runtime-size changes separately to isolate regressions.
Publish only when requested; verify workflow, public APK/hash/size/ZIP/signer/updater independently. Retain the previous release and a rollback switch.

## Order of delivery

1. Ship/review the bounded menu and Audio Boost change after phone QA.
2. Reproduce the failed-first/successful-retry case and add scheduler diagnostics.
3. Implement the bounded recovery coordinator with unchanged confidence checks.
4. Validate readiness and consider the audio probe only if still necessary.
5. Complete premium player controls with a visual prototype and physical-device review.
6. Optimize ONNX packaging as a separate measured change.
7. Add optional R2 model delivery only if its size benefit justifies the first-use/offline complexity.

## Sources checked

- [Cloudflare R2 Workers API](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/): model-data delivery.
- [Workers limits](https://developers.cloudflare.com/workers/platform/limits/): runtime and resource constraints.
- [Android dynamic code loading](https://developer.android.com/privacy-and-security/risks/dynamic-code-loading): executable download risks and distribution considerations.
- [ONNX Runtime custom builds](https://onnxruntime.ai/docs/build/custom.html): reduced runtime options.
- [Play Feature Delivery](https://developer.android.com/guide/playcore/feature-delivery): Play-managed on-demand modules.
