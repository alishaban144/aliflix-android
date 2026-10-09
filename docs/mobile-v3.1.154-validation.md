# Mobile v3.1.154 validation

Baseline: published mobile v3.1.153, following the isolated v3.1.151 baseline.
Target: 3.1.154 / 244. No TV sources, version, builds or releases change.
Physical checks use the authorized Pixel 7a on Android 17. Installations preserve
app data and its development certificate; the public production signer is checked separately.

## Resume and preparation

Unfinished progress belongs to the movie/episode independently of provider/server
and survives a library watched flag. Save the actually active selection before a
new intent and fence first frames before applying the resume position. Audio-only
media and Cast use their ready media clock. Five physical resume/Up Next checks
passed, including a server change after 80 percent, audio-only resume, large text,
portrait/landscape bounds, credits dismissal and single episode advancement.
No Cast-receiver hardware result is claimed.

Details preparation fetches playable media bytes for every resolved route, muted.
Up Next warms the pinned working route 15 seconds before the measured outro and
buffers three seconds through the existing media cache, preserving headers and
audio choice. Stale warmup cannot replace the current selection. Downloads,
subtitles, alternate audio, background progress and pause/resume keep the existing
v3.1.152/v3.1.153 implementations and their validation records.

## Sync with Audio

The interaction has a 9.5-second watchdog and shows Syncing without a countdown.
Timestamped decoded PCM is retained independently of optional Android recognition;
a missing on-device recognizer no longer silently disables audio recovery.
Only contiguous already-played audio is eligible. Seeks, decode-ahead, gaps,
source/audio/caption changes, Reset, cancellation and stale account/revision state
cannot apply a late result. Manual delay remains separate and original cues stay intact.

A finite on-device decoder supplies progressive results. When necessary, the
user-requested recovery sends audio through the existing Aliflix backend and Groq
Whisper: two network slots, at most six clips, at most 20 seconds each and 120 seconds
total including overlap. No model, API key or speech SDK is added to the APK. Clips
retain their own media clocks; captions, stream URLs, cookies and account credentials
are not sent. The audio sheet explains online recovery. Nothing downloads subtitles
or scans the whole movie during the Sync interaction.

Clip selection favors complete speech exchanges over music and uses shifted
windows to recover cropped words. Transcripts remain independent: extract phrases
inside each response rather than concatenating inconsistent recognition. Preserve
measured word endings and mark regressed/padded onsets unknown. Fit earlier phrases
and verify held-out phrases; silence, repeated text, wrong captions and conflicting
clocks do not produce success. Missing function words may corroborate two rich
independent phrases only when the content words and measured ending survive;
that weaker phrase cannot train the offset or invent an onset. A four-second
compact exchange needs two independent measurements of both complete phrases;
one response still requires six seconds. Translated recovery needs two responses
to agree on two distinct phrases. Return the median of independently measured
boundaries, giving each response one vote.

Caption references prepare separately in the background. Full-file exact text
establishes edition correspondence using at least nine unique phrases spanning
180 seconds. This relationship alone cannot identify the stream's frame rate.
When edition rates differ, require measured phrases separated by at least 45
seconds to choose among the text-proven rate candidates. Native correlation still
checks other earlier evidence. A local match must not silently transfer an
incorrect movie-wide PAL clock. Translations keep their own cue/region indexes and
verify their own uniquely corresponding measured boundaries, including clause
pauses. Original English or Arabic text is never replaced.

The backend repair retains words overlapping confident speech segments; the old
containment filter discarded genuine boundary words. Confidence, no-speech,
timestamp and upload limits remain enforced, and the cache version invalidates
old filtered responses. All 116 Worker tests and typecheck pass. Backend-only
[run 37938281922](https://github.com/alishaban144/aliflix-android/actions/runs/37938281922)
validated and deployed this repair without publishing an APK.

## Physical timing acceptance

Normal Terminator checks use automatic original captions and ordinary provider
resolution. The app receives no expected offset, replacement edition or decoded
timing answer. A separate host small.en decoder checks privately captured played
PCM, with no caption prompt. The exact stream fingerprint must match before any
independent receipt is accepted. A correction fitted near 30 minutes is also
checked unchanged near 54 minutes to catch global rate errors.

The final R8 benchmark APK installed on the Pixel has SHA-256
`99e874b30997e95451e7c8445886407876afc093e1fc5e63232e9ef76b46ec3a`.
Its bytes match the local build; it uses the existing development signer and is
not debuggable. The following physical results use this exact build:

| Check | Sync time | Worst independently checked error |
| --- | ---: | ---: |
| Terminator, automatic original English, CineJoy, around 30 minutes | 2,501 ms | 521 ms |
| Same English correction unchanged around 54 minutes | — | 506 ms |
| Terminator, selected Flixer route, automatic original English | 2,486 ms | 541 ms |
| Same Flixer correction unchanged around 54 minutes | — | 493 ms |
| Terminator, automatic original Arabic over English speech, CineJoy | 1,831 ms | 365 ms |
| Same Arabic correction unchanged around 54 minutes | — | 599 ms |
| Tears of Steel, original captions deliberately delayed 7.25 seconds | 356 ms | 70 ms |
| Tears of Steel, original captions deliberately early 7.25 seconds | 352 ms | 10 ms |

Terminator checks provide no expected timing to the app. Tears of Steel supplies
deliberately shifted original captions, not a correction or matcher answer; its
independent receipt checks the applied correction against the original timing.
The fully minified lifecycle fixture separately passed English/French/English
audio changes, forward/backward seeks, continued playback, wrong-caption rejection
without false success, Reset and preservation of manual delay. This dual-audio
fixture is separate from the actual Tears of Steel film.

An earlier Arabic boundary measured 604.5 ms and held publication. Independent
response voting and median measured boundaries then passed the unchanged 600 ms
limit. Earlier intermediate local-only rate passes and recognition rejections do
not authorize publication. These are measured phrase checks, not certification
of every authored caption boundary or every possible media/subtitle combination.

The Flixer repeat selects that existing provider before normal resolution and
automatic caption selection. Its later PCM is captured separately from CineJoy;
neither route borrows the other's audio clock. The final minified build is restored
after diagnostic capture, retaining app data. An earlier route-pinning helper lacked
a subtitle track and failed setup; it is not a sync acceptance result.

## Timing metadata and Up Next

IntroDB, AniSkip and IntroHater use small HTTP/JSON adapters, with exact episode
identity. IntroDB retains priority; another service can fill a missing marker kind.
Different cuts are never averaged. Bounded requests and negative caches fail quietly.
Live AniList and Naruto AniSkip metadata succeeded. IntroHater returned HTTP 403
from the validation host; its live availability is unverified, while parser/fallback
checks pass. Valid outro starts remain usable when an end exceeds stream duration.

The compact floating card adapts the action hierarchy in the designer's
[Hulu end-card case study](https://www.oorjac.com/project/huluendcard): soft ring,
48 dp Play target, Watch credits, measured video/caption separation, safe insets,
landscape control clearance and a short fade/scale/slide with spring positioning.

## Build and release gates

528 mobile unit cases completed with zero failures/errors and three intentional
live OMDb skips. Debug and Android-test APK assembly pass. Final mobile lint and
full R8 benchmark assembly pass after the last production change. Private
recordings, captions, requests, tokens, diagnostic files and validation drivers
are excluded from Git. Only the explicitly approved small speech backend repair
and backend-only workflow input extend beyond mobile Android paths.

Publication retains unit/lint/signing gates and focused API-35 instrumentation.
CI/emulator results are separate from physical Pixel evidence. Independently
verify the downloaded public APK and update-mobile.json for version 3.1.154 / 244,
production signer, ZIP integrity, byte size and SHA-256. Production and development
certificates differ; do not erase phone data to install the public-signed artifact.
