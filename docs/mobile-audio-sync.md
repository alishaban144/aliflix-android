# Mobile adaptive subtitle synchronisation

Baseline for this update: published v3.1.151, `e2cf3e2fe4dcd3d40c4b3fe522d5f8ffa54548a2`.
Current target: v3.1.152. This change affects mobile playback, its tests and its release packaging only.

## Failure diagnosis

| Component | Published behaviour and failure | Replacement |
| --- | --- | --- |
| `SpeechCaptureAudioSink` | Already forced a decoder when supported and captured a duplicate of PCM before downstream writes. Flush/reset erased all evidence. Output-stream changes had no explicit capture boundary. | Preserve the existing decoder/passthrough decision and partial-write deduplication; distinguish identity reset from decoder continuity; reset partial/recurrent state on flush, discontinuity and stream-offset changes. Recover when raw PCM becomes available after passthrough. |
| `PlaybackSpeechBuffer` | A 30-second ring retained only a 6–12-second current exchange, rejected a tap without speech in the last second, and lost useful scenes on seek. Native failures had no actionable diagnostics. | Sparse timestamped speech fingerprints retain up to six hours. Unknown bins remain unknown. Sample-count continuity handles codec PTS rounding. Quantize the first decision once per continuity run, then increment integer bins; repeated floating-point rounding at half-bin positions must not create duplicate/missing evidence. Partial blocks are available before a 20-second block fills. Seek retains the soundtrack's observed scenes; source/audio identity changes erase them. Diagnostics include frames, boundaries, duplicates, unavailable reason, detector readiness, queue loss and inference/capture time. |
| `AudioSubtitleAlignment` | One short sample, a ±120-second search, offset only, manual delay inside the fitting signal, no independent confirmation. A previously cached rate could persist without new drift evidence. | Keep the cancellable FFT primitive; replace the old matcher with normalized observed-window correlations, ±600-second offsets, discrete FPS hypotheses, measured slope hypotheses and penalised edit regions. Fit and confirmation scenes are separate. |
| `NativePlaybackService` | PCM clock conversion already used renderer PTS minus output-stream offset; it was not an arbitrary wall clock. There was no durable multi-scene identity/observed-position contract or immutable service-owned subtitle source. | Preserve the clock conversion, bound evidence to actually played media, reset on selected soundtrack changes, own original cue JSON, and feed the same once-corrected VTT to local playback and Cast relay. |
| `NativePlayerActivity` | Two-second failure deadline, dialogue-dependent tap, original cue initialization restricted to offline requests, and transient URLs/credentials in cache identities. | The mobile action uses a 1.8-second analysis deadline with presentation headroom within two seconds. It first fits a constant offset using up to 30 seconds of contiguous already-played dialogue and independently confirms later phrases and known history. It never waits for future PCM, subtitle downloads, or whole-file scanning. Existing observed history still supports verified drift and a complete already-captured, verified container reference permits edit correction. Reuse automatically accumulated evidence from normal playback; an independent UI watchdog handles temporarily cancellation-resistant I/O. Originals survive recreation through service ownership; signed credentials are excluded from identity. Late results cannot commit after identity, generation, account revision or Cast changes. |
| Arabic text | Neutral punctuation and mixed Latin text depended on inferred paragraph direction. | Set Unicode paragraph direction independently for each Arabic/RTL line; retain glyph order and embedded spans. Local captions and exported VTT share the direction policy. |

## Upstream implementation review

The implementation is native Kotlin/Media3, rather than a bundled desktop engine.
Actual source reviewed:

| Project | Source pin | Relevant implementation |
| --- | --- | --- |
| [AutoSubSync v6.5](https://github.com/denizsafak/AutoSubSync/tree/v6.5) | `db7875b8ab89832c0223ca4253f050ecb724860f` | `main/sync_core.py`, `constants.py`, `call_ffsubsync.py`, `call_autosubsync.py`: engine selection, reference inputs, process budgets/cancellation and validation. AutoSubSync orchestrates engines; it does not supply one universal timing algorithm. |
| [FFsubsync 0.5.1](https://github.com/smacke/ffsubsync/tree/0.5.1) | `de310ac6944b8260431a48ee741e7063cec49b0f` | Speech/subtitle occupancy, FFT correlation and framerate hypotheses in `speech_transformers.py` and `aligners.py`. |
| [LAPSE v2.0.1](https://github.com/Schwponaco-org/lapse/tree/v2.0.1) | `3bf5f0c9b541d175d70a63207a75231f9e222245` | `engine/align.cpp`, `correlate.cpp`, `main.cpp`, `silero.cpp`: local normalized correlation, peak uniqueness, regression and penalised edit segmentation; Silero context and recurrent state. |
| [ALASS](https://github.com/kaegi/alass) | `874f02d9577182752a0f969b6d6b98fd65bdf1fc` | Interval overlap scoring and complexity-penalised splits in `alass-core/src/alass.rs`. GPL code was studied, not copied into Aliflix. |
| [autosubsync](https://github.com/oseiskar/autosubsync/tree/b392da2713aafe6de83e08e2571198988612febc) | `b392da2713aafe6de83e08e2571198988612febc` | Discrete rate candidates, learned VAD features and fit quality/peak ambiguity in `find_transform.py` and `quality_of_fit.py`. Its Python runtime and ML model are not bundled. |

Aliflix uses the shared principles while preserving the Android playback contract.
It is not bit-for-bit engine parity. In particular, an edit boundary inside an
unobserved streaming scene cannot be verified without additional reference evidence.

## Evidence and acceptance

- Use selected decoded audio only. No microphone, paid API, streaming-video scan,
  playback seek, source switch or persisted raw PCM is part of synchronisation.
- A provider subtitle is only a candidate, including hash/catalogue matches. Check
  its timing against actual selected audio first. Cross-language matching uses
  timing occupancy, never translated words or text similarity.
- An embedded reference contains parsed container text with extractor media PTS.
  Partial references use only complete observed windows. A seek invalidates the
  reference's continuity; only an uninterrupted end-of-container pass establishes
  a complete timeline. External SRT/VTT is excluded from embedded references.
- Split contiguous known audio into 6-, 12- or 20-second independent scenes, depending on available history. Fit on two scenes per group of three and confirm on the withheld scene; legacy full-reference fitting uses alternating scenes. Close only short internal syllable pauses (up to 320 ms) in both speech and subtitle envelopes.
- Joint fitting establishes whole-timeline peak uniqueness. Held-out scenes independently verify local correlation and displacement. Scenes with no captions or nearly constant caption occupancy cannot provide timing evidence; informative contradictory scenes still veto a result.
  Reject silence, nearly constant occupancy, periodic/repeated matches, weak or
  non-unique peaks, inconsistent scene offsets, search-edge peaks and competing
  timing models. Scores are correlations, not claimed probabilities.
- Prefer a constant offset when timing slope is not distinguishable. Rate models
  need enough elapsed media. A fitted arbitrary slope also bounds extrapolation
  over the whole target; a good local fit is insufficient.
- Every edit region requires at least two fitting scenes and a withheld scene.
  Infer cue boundaries from the complete verified reference and reject uncertain
  boundaries or reversed cue order. A completed, contiguous cache-only audio scan
  can supply a full speech reference. Sparse played streaming samples cannot.
- Limits: six-hour target/evidence horizon, 20,000 cues, eighteen selected windows,
  eight edit regions and ±600 seconds per regional offset. Reject unsupported or
  uncertain material rather than applying a guessed correction. Bitmap subtitles
  are not a text timing reference.

## Native detector selection and overhead

See [measured results](validation/audio-sync-vad.json) and
[`compare_vad.py`](../tools/audio-sync/compare_vad.py).
The benchmark uses thirty manually labelled human-speech recordings (262.08 s)
from [TEN VAD's testset](https://github.com/TEN-framework/ten-vad/tree/22a3bcd4509d0faaa8eef4881e8af5f39c178950/testset).

| Detector | Precision | Recall | F1 | Host mean inference |
| --- | ---: | ---: | ---: | ---: |
| WebRTC, aggressive, 8 kHz/20 ms | 85.05% | 94.20% | 89.39% | 0.00293 ms |
| Silero, 8 kHz/32 ms | 93.16% | 94.28% | 93.72% | 0.19715 ms |
| Silero, 16 kHz/32 ms | 93.83% | 93.79% | 93.81% | 0.23525 ms |

Silero at 8 kHz removes 58% of the observed false positives with almost the same
accuracy as 16 kHz and lower compute. The packaged model is 2,327,524 bytes,
SHA-256 `1a153a22f4509e292a94e67d6f9b85e8deb25b4988682b7e174c65279d8788e3`,
from [Silero source](https://github.com/snakers4/silero-vad/tree/1e261b036686cd0017d500ee96acd1c4ba572a9d).
The 8 kHz contract includes 256 new samples, the previous 32 samples and recurrent
state `[2,1,128]`. Context is retained between chunks and reset at discontinuities.
Physical continuous-playback evaluation exposed recurrent bias after silent scene
boundaries: isolated-clip accuracy was not sufficient. After 32 consecutive chunks
below 0.1 speech probability (1.024 seconds), the session starts fresh. Ordinary
pauses and uncertain speech retain history. In the continuous phone fixture this
improved Silero F1 from 0.8735 to 0.9321; WebRTC measured 0.8858. Android Silero mean
inference was 0.367 ms, p95 0.416 ms per 32 ms chunk, compared with WebRTC mean
0.0051 ms per 20 ms frame. See [device measurements](validation/native-vad-device.json).

One background CPU worker uses ONNX Runtime Android 1.30.0 with one inference
thread, a 128-frame queue (~40 KiB transient PCM), and a bounded pair of compact
fingerprint maps (at most ~2.2 MiB for six hours). Queue overflow leaves neural
evidence unknown; complete WebRTC evidence remains the fallback. Session or JNI
failure cannot escape into the audible renderer. A JNI capture failure can still
use the neural worker when it is available.

The universal Android runtime has ~135 MiB of native libraries across four ABIs,
before ZIP compression. Mobile-only compressed native packaging reduces download
bandwidth; Android extracts libraries once during installation. This adds APK and
installed storage compared with WebRTC alone. Windows inference timings and JNI
parity are not Android performance or battery measurements.

Completed downloads use an independent, silent, audio-only Media3 decoder with
the exact selected audio identity. The data source has no network upstream.
Backpressure applies only to this scanner, never the audible player. A two-minute
scan budget returns available partial evidence if the cache/codec cannot finish;
partial scans do not qualify for complete-audio edit boundaries.

## Correction, cache and UI ownership

Original cue timestamps and text remain immutable. The automatic transform is
`original * rate + regional offset`; manual delay is added once afterwards.
Original JSON remains service-owned while rendered VTT can change. Resetting the
automatic correction keeps manual delay; the Time Offset row has its own Reset.
The Cast relay receives the same corrected/directed VTT. Cast cancels local
analysis because the phone no longer has the receiver's selected decoded PCM.

Keys digest content, provider/source, stable asset URL, meaningful URL rules,
video preference/rendition, exact audio identity and original cue JSON. Authentication
headers, cookies, expiry fields and signed-URL parameters are excluded recursively.
No credentials, URLs, cue text or raw PCM enter diagnostic messages or Firebase
correction payloads. The existing account-scoped correction collection retains
compare-and-set revisions and reset tombstones. Activity stop, Cancel and source,
audio or subtitle changes cancel pending analysis and prevent stale commits.

The Audio & Subtitles panel keeps one Sync with Audio action. It shows Syncing…,
then Synced, Not enough dialogue yet, or Couldn't match this dialogue. There is no
numeric countdown. Analysis has a 1.8-second budget, leaving 200 ms for applying
and presenting the correction. It reads existing evidence and never waits for
future dialogue, downloads, or whole-file scanning. Cancel and automatic Reset
retain their existing semantics; a late or stale result cannot commit.
Sync compensates the current manual delay in the stored automatic correction,
leaving the manual slider unchanged. Rendering combines both transformations
before clipping cues at zero, so early captions survive. Reset restores the
original captions with the existing manual delay; later manual adjustments remain
relative to the synchronized presentation.

For English audio on Android 14+ with an already installed on-device recognition
service, a bounded worker also feeds selected 16 kHz decoded PCM to the platform
recognizer through `EXTRA_AUDIO_SOURCE`. This does not open the microphone,
contact a network recognizer, download a model, or store PCM/transcripts on disk.
Completed word timings remain in a short in-memory cache. The tap considers only
words ending at or before its playback position within the latest 30 seconds and
current decoder continuity. Unsupported services leave native FFT analysis
available. Recognition follows audio/subtitle identity, closes on service
release, and invalidates its words across seeks, track changes and lost frames.
Recognition sessions rotate after 45 seconds and restart after a silence timeout;
the playback thread never waits for the recognizer.

`DialogueWordAlignment` requires distinctive matching earlier phrases and a
separate later phrase, a consistent offset, and no similarly supported competing
clock or progressive drift. Silence-padded leading words use a complete matching
phrase ending; a missing prefix can verify a clock but cannot train it alone.
Unambiguous joined words and plural transcription differences are normalized
without using caption timing. Earlier informative native audio still vetoes a
contradictory local clock. Coverage is measured from matched words; absent
platform confidence is never invented. Both analysis paths share cancellation,
account/track/cue revision fences and persistence of the actual applied correction.
The Android API contract is documented in the official
[RecognizerIntent audio-source reference](https://developer.android.com/reference/android/speech/RecognizerIntent#EXTRA_AUDIO_SOURCE)
and [word timing reference](https://developer.android.com/reference/android/speech/RecognitionPart).

The following v3.1.148 results are historical evidence; their older interaction
budgets do not describe the v3.1.152 button.

## Validation boundaries for v3.1.148

No emulator is used. A connected Pixel 7a on Android 17 runs the actual Media3
renderer, AudioTrack, WebRTC JNI and production ONNX detector.

The real-film test streams the official
[Tears of Steel movie](https://download.blender.org/demo/movies/ToS/tears_of_steel_720p.mov)
and attaches its [published English captions](https://download.blender.org/demo/movies/ToS/subtitles/TOS-en.srt),
shifted by +7.25 seconds. No fingerprints are generated from subtitle times.
At normal playback speed, its silent opening could not establish a subtitle clock:
the action ended as Unable to Verify in 19,040 ms, without changing any timing.
After normal playback naturally accumulated 85 seconds of soundtrack, one tap
verified the correction in 506 ms: offset -7.28 seconds, rate 1.0, confidence
0.8014, zero position discontinuities and zero rebuffer events. Playback remained
running. See [actual-film phone report](validation/quick-sync-real-film-device.txt).
The preparation time is ordinary playback before the tap; it is not claimed as
cold-start synchronization in 506 ms.

Measured fingerprints from another actual phone playback are committed as a
small permanent [regression input](../app/src/testMobile/resources/audio-sync-tears-of-steel.json).
It contains binary speech decisions and numeric published caption times; it
contains neither PCM nor movie transcript text. This tests film offset correction,
direct-clock reference verification and FPS correction against a verified full
subtitle reference, without a network download in CI.

Normal streaming playback separately verified a -46.92-second offset in 746 ms
and a 25/24 FPS correction in 509 ms, after 130 and 200 seconds of automatically
accumulated normal-speed history respectively. Cancel prevented a stale commit.
See [streaming offset](validation/quick-sync-streaming-offset-device.txt) and
[streaming FPS](validation/quick-sync-streaming-drift-device.txt) reports.

The independent downloaded-media test uses thirty public manually labelled human
recordings, concatenated with silent boundaries by
[`phone_fixture.py`](../tools/audio-sync/phone_fixture.py). The complete 322-second
cache is analysed with its fixture server shut down. Physical results:

| Case | Verified result | Tap to result |
| --- | --- | ---: |
| Constant offset | -46.96 seconds (expected -47) | 6,239 ms |
| Framerate mismatch | 25/24; -48.92 seconds | 6,250 ms |
| Edited scenes | Two regions; -46.93 initial offset | 6,623 ms |
| Ambiguous edits | Rejected; original times preserved | 7,687 ms |

See [downloaded-media phone report](validation/quick-sync-offline-device.txt).
Every accepted corrected cue must be within 0.6 seconds of its separate human
annotation. Tests also check unchanged originals, manual delay applied separately,
zero playback discontinuities/rebuffer events, cache storage and Reset preserving
manual delay. A partial offline scan must not commit before later cached scenes
have been checked: an initial physical regression caught competing 25/24 and
25/23.976 hypotheses, and complete-scan verification resolved it without weakening
the expected-rate assertion.

Deadline regressions include silent input, a 25-second cancellation-resistant
worker, successful early completion and explicit user cancellation. The watchdog
expires at 19 seconds and never returns a late correction. PCM regressions include
half-bin timestamps, partial runs, seeks, unknown gaps and clipping unheard audio.
Alignment tests retain large offsets, progressive drift, piecewise timing,
periodic/wrong-film rejection and known contradictions outside the bounded fit.

Optional host tests use the real Media3 WAV extractor, native WebRTC JNI and ONNX
session. Only the downstream host hardware AudioSink is a test double; physical
checks cover Android audio output. The mobile Gradle task filter explicitly checks the task name. JVM argument
providers supply host JNI paths after Android's default test configuration.

Full-film fitting remains bounded to eighteen scenes, then verifies every other
informative observed scene near the proposed clock. Sparse streaming evidence
cannot establish an edit boundary in an unseen scene. Full verified subtitle
references, including other languages, can supply that missing timeline; their
clock must first agree with the selected decoded audio. Missing language metadata
uses the title's original language only to find candidates, never as proof.

The fully R8-minified benchmark APK is checked by a separate Android framework
driver through actual Audio & Subtitles accessibility controls. The driver has no
application/library references, shared JUnit runtime, detector substitute or
reduced optimisation. GitHub independently checks mobile tests, lint, production
signing and published artifact hashes. ONNX's official keep rule remains scoped
to mobile. Cast conversion has host/device coverage from v3.1.147, but no actual
Cast receiver or sustained battery test is claimed.

The final v3.1.148 minified APK passed the real-film driver: one tap verified
-7.32 seconds against the known -7.25-second offset in 1,043 ms, then Reset
restored original timestamps. This used normal-speed, automatically accumulated
playback evidence. See [minified phone report](validation/quick-sync-minified-device.txt).

Original cue ownership, independent manual delay, stable signed-URL-free identity
and Firebase confidence schema 4 remain intact. Arabic paragraph direction and
the dedicated Japanese anime source routing below are retained from v3.1.147.

## v3.1.153 normal automatic-caption recovery

The normal automatically selected English Terminator edition differs from the
matching NTSC edition used by earlier offset fixtures. Played phrase history now
permits independently verified rate correction across scenes. Transient Android
recognition disconnections retry in the background; unlabeled audio can try the
English decoder when English captions are selected. This selects a candidate
decoder, and never proves the soundtrack language or accepts a correction on its
own. Independent word matches, held dialogue and contradictory-scene rejection
still determine acceptance. See [normal-route evidence](mobile-v3.1.153-validation.md).

## Japanese anime startup

Japanese animated series race the two dedicated native sources, Miruro and
AniKuro, before general movie/series sources. Old general-provider history and
detail-page preloads cannot hold up a fresh anime start. Explicit server selection
is retained; non-Japanese animation, live action and TV-app routing keep their
existing behavior. AniList episode mapping rejects ambiguous title/season matches.

Miruro's current v1.15 catalogue protocol replaces the obsolete browser/secure-pipe
adapter: `/api/v1/anime?anilist_id_in=...&limit=100`, `/api/config`, and the compact-ID
episode play endpoint. Its publicly specified XOR/gzip envelope is decoded locally;
soft/Japanese subtitles and native HLS/MP4 servers preserve their provider headers.
AniKuro races current provider endpoints internally rather than waiting through
three sequential attempts at its primary server. Catalogue requests disconnect on
cancellation, and only validated native media candidates can win either race.
An intermittent live startup failure recovered on an isolated rerun. Catalogue
fetches now automatically retry once after 200 ms for a connection failure or
HTTP 408/429/5xx, within the existing source deadline. Permanent HTTP failures,
invalid payloads and cancellation do not retry; source failures log their type
without signed URLs or credentials. An actual Android HTTP test serves 503 then
200 and requires recovery from a single caller action. Fresh-start tests invalidate
their previous direct stream before fetching each catalogue again.

Actual fresh starts on the Pixel decoded video and audio through Miruro for One
Piece S1E1 in 2,689 ms and Attack on Titan S1E1 in 1,826 ms. Both passed a forward
seek to 180 seconds with audio retained; playback progress identity remains
provider independent. See [startup measurements](validation/anime-startup-device.txt).
AniKuro's primary CDN timed out during investigation; the race lets the verified
Miruro result start promptly. These measurements are examples, not a guarantee
of zero latency for every title or third-party CDN state.
