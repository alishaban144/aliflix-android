# Aliflix mobile 3.1.164

Baseline: published `v3.1.163`, commit `2cbadc370aaf9f8c414cdab1dc45e31ec6c80087`.
Mobile version code 254. No runtime dependencies were added.

## Changes

- The shared `AliflixLiquidToggle` ports the actual Deepak Chandwani reference:
  two scaling circles, the checked droplet, reference colors, 500ms CSS easing,
  and the native GPU equivalent of its SVG goo filter on Android 12+.
  Android 10/11 retain the same geometry without the GPU filter.
  Future mobile settings use this control as documented in `mobile-controls.md`.
- Home composes and warms its display lists beneath the opaque launch screen
  before the brand clock starts. Previously it first composed during an 80ms
  final hold immediately before the crossfade, and zero opacity skipped drawing.
  Feed mapping and personal recommendation work now run off the UI thread.
  Rail sorting scores each distinct title once instead of inside each comparison.
- The brand reveal lasts 900ms with gentler easing; the wordmark takes 720ms.
  Every rotating word has a readable slot before the 300ms Home crossfade.
  Network loading cannot extend the branded sequence indefinitely.
- Sync with Audio keeps its existing local matching path. When more evidence is
  needed, a separate audio-only decoder reads up to 60 seconds near the playhead
  through the existing cache and source-specific networking. Missing nearby
  segments may be fetched. It uses the exact selected audio fingerprint, never
  the microphone, never seeks the visible player, and never takes audio focus.
  Its 11-second scan runs inside the existing 19-second overall sync deadline.
  Future words use the decoder's measured media timestamps; continuous
  recognition and the original played-only APIs retain their strict fences.
- Confidence, independent phrase verification, ambiguity rejection, manual delay,
  caption/source/account/seek identity and cancellation checks remain in place.
  Failure exposes a dedicated Retry action with no reason text beneath it.

## Validation

The mobile-only host gate passed: 630 unit tests, zero failures, three skipped;
mobile lint has zero errors (118 warnings and four hints); both mobile app and
instrumentation APKs assembled. One test initially compared two independently
rounded floating-point timestamps at the same boundary; it now uses the sequence
constant, and the complete suite passed on rerun.

Pixel 7a, Android 17: all 20 visual and decoder tests passed. Coverage includes
controlled/disabled toggles, the reference droplet in both layout directions,
intermediate animation frames, narrow screens and large text, launch completion
without waiting for the network, one Home composition rather than per-frame
recomposition, and Retry with no explanation underneath. Screenshots were reviewed.
The silent-decoder test retained a paused playhead at 0.049 seconds while decoding
through 51.32 seconds (2,566 PCM frames). A wrong audio identity produced no evidence.

The normal provider/automatic-subtitle route for The Terminator was then exercised
after only 0.5 seconds of playback and paused before tapping Sync. English captions
reached Synced on the first attempt in 3,207ms, using future decoded dialogue while
the continuous recognizer had no local words. The selected source was CineJoy;
the independently verified constant correction was +7.285926 seconds, rate 1.
The same early paused flow passed with Arabic captions in 2,978ms (verified offset +7.243093 seconds, rate 1). Both tests asserted
caption identity, rendering against the accepted correction, the deadline and
preservation of the paused state. This is device evidence for those scenes, not
a promise that silence or ambiguous/wrong captions can always yield a safe match.

The isolated debug package preserves the installed production app and its data;
its local Firebase client configuration is excluded from Git. Public signed APK
integrity and any subsequent minified-app smoke check are separate release gates.

The unchanged backend passed typecheck and all 116 tests. The first sandboxed
test run hit local runtime/database restrictions; the unsandboxed rerun passed.
