# Mobile v3.1.155 Sync with Audio hotfix

The ordinary Pixel playback flow reproduced the reported failure in The
Terminator with automatically selected English captions. At about 47:05,
recognition returned complete dialogue, but discovering another caption edition
forced a wide frame-rate proof and vetoed the selected captions' current clock.

Alternate caption editions now remain candidates rather than a global veto.
Selected-caption words, independently held-out phrases and earlier audio decide
the correction. Non-unit reference clocks retain their wide verification, and
wrong captions, repeated dialogue, contradictory timing, cancellation, subtitle
identity, manual delay and Reset retain their existing guards.

Recovery also uses its first two network slots for separate conversations before
repeating overlapping clips. Six clips, twenty seconds per clip and two minutes
of uploaded audio including overlaps remain the upper limits.

## Ordinary physical phone check

The final fully minified development-signed APK was installed over the existing
Pixel 7a app, preserving app data. APK SHA-256:
`caeffeff555292a2b9fb95059061e34e4044f4f430b8969a59283b804245bb70`.

The movie was opened through Recently played and Resume. Using the ordinary
timeline, playback was moved to about 44:30; English dialogue then played normally.
At about 45:42, Audio & subtitles > Sync with Audio returned **Synced** within its
ten-second interaction budget. The actual UI result was read after the attempt;
the log records independent played-word verification with 17 recognized words.
No expected offset or matcher answer was supplied to the app.

To repeat this example: open The Terminator (1984), enable English subtitles,
play the conversation from about 44:30, then open Audio & subtitles and tap Sync
with Audio. After a seek, let several sentences play before tapping so the
already-played audio contains the dialogue to analyze.

## Checks and release

535 mobile unit cases completed with zero failures/errors and three intentional
live-service skips. Mobile lint and full minified benchmark assembly passed.
New regressions cover separate conversation scheduling and a verified selected
caption clock with alternate edition rates, including wrong-caption rejection.
Existing rate, translation, ambiguity and bounded-upload cases remain passing.

The release workflow retains its mobile test, lint, signer and integrity gates.
Private phone logs, recordings, captions, requests and test drivers are excluded
from publication. TV source and version remain unchanged.
