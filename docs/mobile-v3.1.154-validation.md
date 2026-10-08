# Mobile v3.1.154 validation

Baseline: published mobile v3.1.153, following the v3.1.151 worktree baseline.
Target: 3.1.154 / 244. TV sources/version and recommendation Worker are unchanged.
The authorized Pixel 7a runs Android 17. Installations retain its data and local
development certificate; the production signer is verified separately.

## Resume and preparation

An unfinished position survives the library's watched/completed flag. Progress
belongs to the movie/episode, independently of provider or server. The player
records the actually active selection and fences initial frames before restoring
a requested resume position. A physical test watched a fixture past 80%, left,
stopped the service, selected another saved server route and resumed within two
seconds of the saved position. It did not clear the application's data.

Details preload now fetches playable media bytes for every resolved route. Up Next
preparation starts ahead of the actual outro and buffers three seconds through
the existing media cache, muted, with the selected route's own headers and audio
settings. A stale preparation cannot replace the current selection.

## Sync with Audio

The interaction has a 9.5-second watchdog and displays Syncing without a numerical
countdown. It analyzes contiguous, already-played PCM; a finite on-device decoder
can recover when continuous recognition misses a phrase. It does not open the
microphone, seek the video, scan the entire media file or download captions on tap.

Complete caption references prepare in the background. Both original Flixer
catalogues get an early opportunity, alongside external caption releases. Slow
or duplicate files cannot monopolize the bounded two-slot search. Automatic
selection rejects obviously incomplete split movie files and retains manually
chosen original cues.

Unique same-language sentences or long pause boundaries establish a complete
caption-to-caption clock. Pause fitting balances cue onsets and endings, checks
withheld boundaries across the release, rejects competing clocks and checks
dialogue occupancy in independent timeline blocks. Spoken-language words still
have to verify the reference against actual played audio before a translation's
clock can be applied. Original captions, manual delay, Reset, account/audio
identity and target-owned region indexes are preserved.

Stronger testing caught and rejected an early implementation that displayed
Synced while the final phrase was over a second out. A later independently heard
phrase cannot be discarded as an outlier, and an old phrase group cannot stand
in for recent dialogue. After independent verification, the applied offset
centers all accepted boundaries to minimize its worst timing error.

Normal catalogue Terminator playback uses automatically selected captions and
provides no expected timing answer or replacement edition to the app. A separate
host Whisper decoder analyzes privately captured played PCM. The final debug
English two-scene check completed in 533 ms, with 356 ms maximum error at five
independently checked phrase endings; withheld phrases independently verified
the playback-rate difference. Arabic single-scene playback completed in 504 ms,
with 493 ms maximum error at three separately checked phrase endings.

The fully optimized mobile benchmark app passed normal English Terminator
playback twice (442 ms and 471 ms), and Arabic playback in 456 ms. Its final English
correction has 361 ms maximum independently checked error; Arabic has 493 ms.
These minified corrections were checked against separately captured, same-provider
played PCM and the exact same original caption editions (550 English / 780 Arabic
cues); the independent decoder and expected timings never enter the app.
Reset and the original manual delay passed in both languages.

A second real title, Tears of Steel, used official captions deliberately shifted
7.25 seconds. The minified app corrected every checked cue boundary to within
70 ms in 279 ms, without being told the offset. English/French/English audio
changes and forward/backward seeks preserved playing state and manual delay.
Unsuitable captions produced an actionable rejection after 9,201 ms without a
false success or playback discontinuity. The phone's automatic English preference
was restored. These are measured checks, not certification of every authored cue
or every possible media/subtitle combination.

## Timing metadata and Up Next

IntroDB, AniSkip and IntroHater use small HTTP/JSON adapters; no CLI or additional
media SDK ships in the APK. Exact mapped AniList/MAL episodes are used for AniSkip,
and exact IMDb/season/episode identities for IntroHater. Different cuts are never
averaged or joined. IntroDB has priority, while another service can fill a missing
kind. Requests and negative caches are bounded; unavailable services fail quietly.
Live AniList POST and Naruto AniSkip metadata returned successfully. IntroHater's
public metadata route returned HTTP 403 from the validation host, so live service
availability is not claimed; its documented response parser and fallback pass.

The compact floating card adapts the action hierarchy in the original designer's
[Hulu mobile end-card case study](https://www.oorjac.com/project/huluendcard).
It keeps the soft ring, 48 dp Play target, Watch credits, measured caption/video
separation, safe areas and landscape control clearance. Its transition uses a
short fade/scale/slide with spring positioning. Four physical resume/Up Next tests
pass, including large captions, portrait/landscape screenshots, missing markers,
an outro end beyond the stream duration and credits dismissal without advancement.

## Final gates

The final local gate passed 498 mobile unit tests (zero failures/errors; three
intentional live OMDb skips), mobile lint, debug assembly, Android-test assembly,
and fully minified benchmark assembly. No TV tasks were run. Four focused
physical resume/Up Next tests pass in addition to the live sync checks above.
Private media, captions, source URLs and authentication are excluded from Git.

Publication uses the connected mobile workflow with unit/lint/signing gates and
its focused API-35 instrumentation suite. CI/emulator results remain separate
from physical Pixel evidence. After publication, independently downloaded public
APK and manifest checks must verify 3.1.154 / 244, production signer, ZIP integrity,
size and SHA-256 against update-mobile.json.
