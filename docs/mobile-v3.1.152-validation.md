# Mobile v3.1.152 validation

Baseline: published v3.1.151, `e2cf3e2fe4dcd3d40c4b3fe522d5f8ffa54548a2`.
Target: mobile 3.1.152 / 242. Physical device: authorized Pixel 7a, Android 17.
The existing installation's data is retained; the fully optimized, non-debuggable
benchmark build uses its existing local certificate. TV is unchanged.

## Download ownership and discovery

Four concurrent provider slots refill until three positive, individually verified
video heights are found, providers finish, or the 30-second overall deadline ends.
Cancellation closes inspection data sources and resolver/WebView resources.
An option retains its original preparation: provider/server, native request,
URL authentication/rules, manifest, stream keys, audio groups and subtitles.
Duplicate heights retain alternate preparations internally. Manifest indexes are
never transferred between providers.

Unselected tiers use highest, lowest and nearest available midpoint heights.
A user-selected height remains selected if later results add new extremes.
Skeleton cards transition into actual resolutions and estimates. Download stays
disabled until discovery and all selected episode validation finish.

Season preparation pins the earliest selected eligible anchor's provider/server
and target height. Four episodes prepare concurrently; ties choose the lower
actual height. An individual fallback or episode Retry cannot replace or
rediscover the season anchor. Completed
preparations remain reusable within the activity session across dismissal.

Unit coverage includes dynamic 320p/560p/930p heights, duplicate/unknown heights,
partial results, bounded deadlines, cancellation, source ownership, required
audio groups, nearest-height ties, pinning/fallback and stale callbacks. Physical
UI checks cover progressive selection, disabled actions, a shared season tier,
episode selection, audio selection and large text.

Physical native anime probes prepared two episodes per title:

| Title | Discovery | Actual heights | Pinned batch |
| --- | ---: | --- | --- |
| One Piece | 30,071 ms, bounded partial result | 1080p | Both episodes ready |
| Attack on Titan | 2,943 ms | 1080p / 720p / 360p | Both episodes ready |

A separate live Breaking Bad three-episode probe verified distinct episode URLs
while retaining its anchor route. Actual Media3 transfers check MP4 pause/resume,
HLS resolution ownership, alternate audio, original subtitles, persistence through
recreation and offline playback/seeking after the source server shuts down.

## Sync with Audio

The action analyses existing played evidence, with a 1.8-second worker budget and
200 ms presentation headroom. It does not wait for future dialogue, subtitle
downloads or a whole-file scan. An optional installed Android on-device English
recognizer supplies actual PCM word timestamps; native VAD/FFT remains available.
Word fitting requires earlier phrases and independent later verification. The
matched duration includes the completed held-out phrase, rather than only phrase
onsets; unrelated trailing audio cannot provide duration. Seek
gaps, decode-ahead frames, stale identity/account revisions and competing clocks
cannot commit. The automatic correction compensates the current manual delay;
the slider stays unchanged and Reset preserves it.

The Terminator (1984) uses actual decoded CineJoy/Nebula English audio, and an
independently checked matching NTSC caption edition. The app receives only shifted
captions; expected offsets remain exclusively in the independent test. Three
different conversations begin at 1,275.37, 3,192.58 and 3,424.39 seconds. Checks
compare every rendered VTT cue with the verified transformation and retain
original cues, running playback, persisted correction and Reset behavior.

| Physical check | Injected offsets | Manual delay | Largest error | Tap to result |
| --- | --- | --- | ---: | --- |
| Native app, three scenes | +6.75 / -9.25 / +14.5 s | +9 s | 229 ms | 230-300 ms |
| Native app, three scenes | -6.75 / +9.25 / -14.5 s | -9 s | 229 ms | 222-334 ms |
| Final fully minified UI, three scenes repeated twice | -9.25 / +6.75 / -14.5 s | -9 s | 259 ms | 240-276 ms |

The separate fully minified Tears of Steel UI test corrected deliberately shifted
official captions with 70 ms error in 229 ms. Native real-film checks cover Tears
of Steel and Elephants Dream, with no correction-induced seeks or rebuffer events.
Silence, wrong-title captions and repeated patterns return actionable rejection;
unit coverage separately enforces cancellation and the two-second deadline.
The fully minified annotated normal-speed speech fixture also verified a 25/24
framerate mismatch through a fitted rate of 1.041216351 in 562 ms; every corrected
cue must meet the 600 ms timestamp tolerance. A separate minified lifecycle check
selected English / French / English audio, sought forward and backward, retained
playing state and manual delay, and rejected unavailable dialogue in 123 ms.

These measurements prove the tested audio/caption pairs and windows. They do not
promise that every third-party subtitle edition or language can be matched. A
local constant offset does not establish an unseen edit or whole-film framerate;
broader drift/edit correction requires independently verified observed history.
Unavailable local recognition models are not downloaded during the interaction.
Private media, URLs, transcripts and raw device captures are excluded from Git.

## Up Next

The compact Next episode / Watch credits pattern adapts the selected
[Netflix UI reference](https://medium.com/@karinsuvaryan/ui-ux-case-study-netflix-81ae5a7d1563)
to Aliflix surfaces, without a thumbnail and with the existing soft ring. Measured
video and caption bounds plus safe insets separate the portrait card from the
video and screen bottom. Landscape places it above the timeline and hides the
central seek controls while the offer is visible, avoiding overlapping actions.

Physical portrait/landscape screenshots and large-caption tests verify its bounds.
IntroDB outro start timing remains supported. Without a valid marker, a final
window (up to 30 seconds, scaled for short episodes) offers the next episode.
The offer persists beyond marker expiration and in the ended state when autoplay
is disabled. Watch credits cancels it for the current episode; advancement remains
single-shot. Season metadata refresh supplies the following season, while the
final episode has no successor and no offer. Physical and emulator tests cover
missing markers, marker expiration, ended state, cancellation and the final episode.

The final landscape polish also passes all four targeted physical Up Next/silence
checks. The final fully minified candidate repeats all three Terminator scenes twice:
six accepted corrections, worst cue error 363 ms, analysis completion 233-339 ms.
Manual delay and Reset remain intact. The final minified second-title check has
70 ms maximum cue error and 262 ms analysis; its English/French/English track and
forward/backward seek test rejects unrelated captions in 132 ms without false success.

## Silent details preparation and playback startup

Details starts four bounded provider attempts, retaining the in-progress session
when Play claims the exact title/episode/source/resume position. Unclaimed sessions
cancel on lifecycle exit; transferred sessions cancel on foreground cancellation
or the bounded handoff timeout. A complete source preparation survives handoff.
The former eight-second Flixer head start no longer delays other providers.

CineJoy previously won on a catalogue playlist before its media could play.
Its server race now verifies playable media before winning speculative discovery,
using the same fragment/audio/manifest wrappers and source-owned credentials as
native playback. Pinned recovery retains its existing route retry behavior.

Resolver hosts are invisible and excluded from focus/accessibility. Native resolver
WebViews are muted before navigation through AndroidX MUTE_AUDIO where supported;
a document-start media/WebAudio guard supplies the older-WebView fallback. Provider
scripts cannot unmute preparation. Native startup remains paused until the matching
request renders a first frame; direct requests are also muted until video renders.
Audio-only requests release the gate at track readiness.

On the physical Pixel, Undone S1E1 now reuses its details preparation: first frame
at 2,369 ms and ready playing UI at 2,974 ms after Play, after about two seconds on
details. The test verifies no native playback during details, no playing audio
behind preparation, and continued playback after a distant seek. Provider silence
also passes a page that explicitly sets muted=false and volume=1. External provider
availability and latency remain variable.

## Release gates

Mobile unit tests, lint, debug/test assembly and full R8 benchmark assembly run
locally: 473 unit cases, zero failures/errors, three optional host cases skipped;
lint has zero errors, 109 warnings and four hints. All 18 focused physical checks
passed on the Pixel: 11 download UI cases, three offline cases, Skip/IntroDB and
Up Next (with and without metadata), and provider silence. This includes episode
Retry without anchor rediscovery. The same 18-case suite passed on the local API 35
emulator.
The rotation test invalidates the
Android accessibility cache before reading the current rendered Compose tree;
its geometry and interaction assertions remain enforced. On a fresh CI image,
Android's first-fullscreen education overlay initially owned the accessibility
window ("Viewing full screen" / "Got it"). The fixture now acknowledges that
system prompt before reading app controls; no app assertion is waived. Clearing
that setting on the temporary read-only emulator reproduces the CI condition, and
the complete 18-case suite then passes with normal tutorial acknowledgement.
Focused physical checks are separate from compilation evidence. GitHub
runs mobile unit/lint and focused emulator regressions without test waivers,
checks the production signer, ZIP integrity and mobile version consistency, and
publishes the mobile APK and update manifest. Public artifact verification must
independently compare version, signer, length and SHA-256 against that manifest.
