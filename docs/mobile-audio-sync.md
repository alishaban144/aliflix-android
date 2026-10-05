# Mobile adaptive subtitle synchronisation

Baseline: published v3.1.145, `04dfd3b8c0211231d0a6b50c1b266c99d3115f6c`.
This change affects mobile playback, its tests and its release packaging only.

## Failure diagnosis

| Component | Published behaviour and failure | Replacement |
| --- | --- | --- |
| `SpeechCaptureAudioSink` | Already forced a decoder when supported and captured a duplicate of PCM before downstream writes. Flush/reset erased all evidence. Output-stream changes had no explicit capture boundary. | Preserve the existing decoder/passthrough decision and partial-write deduplication; distinguish identity reset from decoder continuity; reset partial/recurrent state on flush, discontinuity and stream-offset changes. Recover when raw PCM becomes available after passthrough. |
| `PlaybackSpeechBuffer` | A 30-second ring retained only a 6–12-second current exchange, rejected a tap without speech in the last second, and lost useful scenes on seek. Native failures had no actionable diagnostics. | Sparse timestamped speech fingerprints retain up to six hours. Unknown bins remain unknown. Sample-count continuity handles codec PTS rounding. Seek retains the soundtrack's observed scenes; source/audio identity changes erase them. Diagnostics include frames, boundaries, duplicates, unavailable reason, detector readiness, queue loss and inference/capture time. |
| `AudioSubtitleAlignment` | One short sample, a ±120-second search, offset only, manual delay inside the fitting signal, no independent confirmation. A previously cached rate could persist without new drift evidence. | Keep the cancellable FFT primitive; replace the old matcher with normalized observed-window correlations, ±600-second offsets, discrete FPS hypotheses, measured slope hypotheses and penalised edit regions. Fit and confirmation scenes are separate. |
| `NativePlaybackService` | PCM clock conversion already used renderer PTS minus output-stream offset; it was not an arbitrary wall clock. There was no durable multi-scene identity/observed-position contract or immutable service-owned subtitle source. | Preserve the clock conversion, bound evidence to actually played media, reset on selected soundtrack changes, own original cue JSON, and feed the same once-corrected VTT to local playback and Cast relay. |
| `NativePlayerActivity` | Two-second failure deadline, dialogue-dependent tap, original cue initialization restricted to offline requests, and transient URLs/credentials in cache identities. | One cancellable job automatically collects evidence for at most three minutes. Each CPU attempt has a 25-second cooperative deadline. Originals survive recreation through service ownership; signed credentials are excluded from identity. Late results cannot commit after identity, generation, account revision or Cast changes. |
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
- Fit on alternating independent observed scenes and confirm on withheld scenes.
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
inference was 0.371 ms, p95 0.418 ms per 32 ms chunk, compared with WebRTC mean
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

The Audio & Subtitles panel keeps one Sync with Audio action. It shows Analysing,
Collecting Evidence, Synced or Unable to Verify, with Cancel on the active action
and a separate automatic Reset. It does not require a dialogue-time tap or show
intrusive error messages. Evidence collection ends after three minutes.

## Validation boundaries

Portable regressions exercise real recorded speech fingerprints, offsets to
±310 seconds, PAL/inverse-PAL and measured drift, edits combined with FPS changes,
independent rejection, cancellation, immutable originals, manual-delay separation,
stable nested credentials, seeking/backward PCM and Arabic paragraph direction.
Optional host tests use the actual Media3 WAV extractor, native WebRTC JNI and
production ONNX session. The downstream hardware AudioSink is a test double;
the host uses the actual AOSP Pair utility rather than Gradle's empty Android stub.

No emulator is run, as requested. The connected Pixel 7a on Android 17 passed
actual Media3/AudioTrack playback, native VAD measurement, Android Arabic layout,
and downloaded-media analysis with its fixture server shut down. The thirty public
recordings are concatenated with two-second silent boundaries by
[`phone_fixture.py`](../tools/audio-sync/phone_fixture.py); raw audio is not shipped.
The 322-second fixture independently verifies a 47-second offset, 25/24 drift and
a 60-second edit after 120 seconds. Every accepted corrected cue must be within
0.6 seconds of its annotation. An ambiguous 18-second edit is rejected. Tests also
assert zero buffering/discontinuities during correction, unchanged originals,
separate manual delay, cache storage, and Reset preserving manual delay.

Streaming acceptance starts without a speech-time tap, observes Collecting
Evidence, tests Cancel without a stale commit, then verifies later independent
scenes from a single action. Four-times playback shortens this test; timestamps
still use media time. See [streaming report](validation/streaming-sync-device.txt)
and [downloaded-media report](validation/adaptive-sync-device.txt).
Real caption attachment and forward/backward seeks passed without re-preparing
the stream. Cast subtitle conversion/round-trip identity passed on Android; an
actual receiver and sustained battery measurement remain unverified.

The fully R8-minified mobile benchmark APK also passed through an independent
Android framework driver: the actual Audio & Subtitles controls collect evidence,
verify the offset (-46.92 seconds against -47), show Synced and Reset. No app
classes, shared JUnit dependencies, substitutes or reduced optimisation are used
by this driver. Its normal-speed streaming collection took 121 seconds; prior
observed speech avoids starting collection from zero. See
[minified report](validation/minified-sync-device.txt).
This gate found and fixed a release-only ONNX JNI abort: R8 renamed/deleted
TensorInfo/OnnxJavaType looked up from native code. The mobile flavor now includes
the [official ONNX keep rule](https://onnxruntime.ai/docs/build/android.html).
TV's shrinker configuration is unchanged. The benchmark uses the local debug
certificate; GitHub's release gate verifies the production signer separately.

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

Actual fresh starts on the Pixel decoded video and audio through Miruro for One
Piece S1E1 in 3,248 ms and Attack on Titan S1E1 in 1,413 ms. Both passed a forward
seek to 180 seconds with audio retained; playback progress identity remains
provider independent. See [startup measurements](validation/anime-startup-device.txt).
AniKuro's primary CDN timed out during investigation; the race lets the verified
Miruro result start promptly. These measurements are examples, not a guarantee
of zero latency for every title or third-party CDN state.
