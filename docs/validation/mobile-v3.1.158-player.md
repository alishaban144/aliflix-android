# Mobile 3.1.158 player release validation

Candidate versionCode 248. Baseline 834f366 / 3.1.157.

## Scope

Premium mobile player controls and consistent sheets; Streaming source drilling;
Audio Boost in More; bounded fresh-dialogue recovery with two active uploads.
Automatic caption ranking uses late video metadata and complete-edition hints;
old automatic caches can refresh without deleting manual/offline captions.
Missing video frame-rate metadata can fall back to a measured decoded-PTS cadence.
Local matching still gets the first opportunity. Online clips remain 4–20 seconds,
with no more than six / 120 aggregate seconds per tap. Each request has a
ten-second processing deadline within the 19-second overall deadline.

The correction matcher and its acceptance safeguards are unchanged.
TV version/source, Worker, dependencies, supported ABIs and native assets are unchanged.
Cloudflare executable downloads and a reduced native runtime were not introduced.

## Validation

The local gate and physical Pixel 7a results below are separate from the signed
public artifact verification. The earlier menu-only checkpoint is in
[player-premium-menu.md](player-premium-menu.md).

New scheduler regressions exercise active-request limits, full per-request
timeouts, fresh dialogue priority, duplicate/growing fragment suppression and
cancellation. Existing sync regressions cover offset, drift, edited scenes,
wrong captions, ambiguity, late results, Reset and manual delay.

The physical debug nine-test UI suite passed in 12.792 seconds.
It includes More/source navigation, no-op current-server selection, Audio Boost
state, timeline accessibility, RTL with 200% text, full episode identity,
unchanged transport press rendering, double-tap seek and pinch fit controls.

The first eight-second live test reached its 19-second deadline and rejected a
legacy automatic caption edition. The reference-caption relation had rate
1.042709376 (25/23.976). This is recorded as a failed pre-fix reproduction, not
a successful sync. It triggered late-metadata ranking and automatic-cache
migration improvements. Audio rate verification requirements remain intact.

An intermediate retest returned Synced in 14.472 seconds, but still used the
legacy cached file and reported unknown video frame rate. This was not accepted
as sufficient accuracy evidence. Inspection found that online requests use an
empty offlineDownloadId, not null; the cache-refresh guard was corrected to
check a nonblank ID. The decoded-video cadence fallback was added and tested
against film/PAL rates, duplicates, source resets, seeks and variable cadence.

The physical live-caption check uses normal provider resolution and The
Terminator (1984), English captions, a single scene, and eight seconds of played
audio before the first tap. It supplies no timing answer to the app. This is one
film/scene, not evidence that every title will sync successfully.

The final debug first-tap check passed: Synced in 4,155 ms after eight seconds
of normal playback at scene 3196.058, with no earlier sync tap. Measured video
cadence was 23.976215 fps. The app automatically selected
The Terminator [1984-BDRip-H.264]-NewArtRiot.srt; correction rate was 1 and
offset 7.244427 seconds. The selected source remained CineJoy and manual delay
remained zero. This establishes the bounded first-tap path on this case; it is
not an independently annotated accuracy check for every caption in the film.

A separate known-shift physical debug check passed. Its expected answer stayed
inside the test; the app received only the incorrectly timed captions.
For a +6.75-second injected shift, it applied -7.309231 seconds in 321 ms,
within the independently specified 0.6-second tolerance, with rate 1.
The existing +0.3-second manual delay remained separate. Reset and rendered
caption checks passed. The test used 34 seconds of dialogue and therefore does
not establish short-wait readiness; the eight-second case above supplies that
separate evidence.

The final broad debug QA gate passed unit tests, lint, app assembly and Android
test assembly. At that checkpoint it executed 608 tests: 605 passed, three
existing intentional skips, zero failures/errors. Three additional loader
warmup/identity tests are included in the final release gate. TV debug assembly
passed; TV version 3.1.69 (159), Worker and dependency/native-library diffs are
unchanged.

Private PCM, caption files, stream URLs and raw test receipts remain in ignored
local validation files and are excluded from the release sources.

## Final local and minified gate

The normal (no QA init script) final gate passed mobile unit tests, lint, debug
app/test assembly and TV debug assembly. Lint reported zero errors, 106 warnings
and four hints. The final unit XML totals are recorded alongside the release.

The optimized benchmark QA app was physically launched through ordinary
Search → The Terminator (1984) → Resume → Audio & subtitles. Reset returned the
control to Sync with Audio; one tap then returned Synced. Manual +0.3s remained
visible and separate. Live film playback and captions continued on CineJoy.
This was buffered playback, not a cold eight-second readiness measurement.

Minified instrumentation could not run reliably: R8 removed/inlined dependency
classes and changed model constructor signatures used by the separate test APK.
The final QA benchmark retained tracing and Kotlin support classes solely for
that harness attempt. Player logic and ONNX/JNI were still minified; the public
release contains no QA keep rules. The normal UI check is recorded separately
from the debug known-shift accuracy check and public artifact integrity.

No physical silent/ambiguous-film rejection or audible Audio Boost measurement
was performed in this release; silence/ambiguity guards have host regressions.
Public APK signing/hash/size/updater checks follow GitHub publication.
Final normal gate: 611 tests, 608 passed, three intentional skips, zero failures/errors.

## Published artifact verification

GitHub workflow [38186327447](https://github.com/alishaban144/aliflix-android/actions/runs/38186327447)
succeeded for source commit 86a32aa. Mobile unit tests and lint were enabled.
The emulator job was skipped after physical Pixel 7a validation; Worker jobs
were skipped because their sources did not change.

[Aliflix v3.1.158](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.158)
is a public, non-draft, non-prerelease release with aliflix-mobile.apk and
update-mobile.json. A fresh independent download verified:

- Package com.aliflix.app, versionCode 248, versionName 3.1.158, minimum SDK 29.
- APK size 66,182,121 bytes, matching release metadata and updater metadata.
- SHA-256 6e75e37cdd67329daeaac035351eda6491ef34aaba26e526de2d0bbdd57d6d0c.
- APK signature verification passed with the existing production signer:
  a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47.
- Every ZIP entry passed its integrity check. All four supported ABIs and 20
  native libraries are present; native library/model hashes match v3.1.157.
- The latest-release updater manifest exactly matches the tagged manifest.
- APK growth from v3.1.157 is 10,160 bytes. No size-reduction claim is made.

The public byte artifact was not installed over the user's production app.
QA app/test packages were removed and the original rotation settings restored;
production app data was preserved. Public-artifact integrity is separate from
physical debug accuracy and minified QA UI evidence.
