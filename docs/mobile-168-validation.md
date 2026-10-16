# Mobile 3.1.168 validation

Baseline: published mobile v3.1.166/code 256, Android source `ba4c38ed54823b8a8bdca6a5ece2d49542965acb`. This release contains mobile fixes only. TV source, version, native packaging and release are unchanged. The deployed Watch Party backend is unchanged; its implementation and previous paired-device evidence are recorded in [v166 validation](mobile-166-validation.md).

## Changes

- Logo, ALIFLIX and Movies/Series/Stories start together. Words receive more readable time within the existing short launch duration. Reduced motion remains supported.
- Title actions form one compact row: a shorter Play/Resume action, Watch Together, download, like and My List. Charcoal/violet surfaces, existing theme/motion tokens, press springs, accessible labels and 48dp targets remain native Compose. The player center control grows from 64dp to 72dp; its animation is unchanged.
- All mobile playback entry points share a Watch Together consent boundary. Choosing another title or exact episode offers a custom Stay / Leave & play surface before changing history, preparation or playback. Rapid taps create one prompt; restored rooms and direct player intents are covered. Room episode broadcasts cannot hijack unrelated titles.
- Settings ends with a read-only Streaming Sources catalogue, including general and anime sources. Provider/server selection stays in the player; unavailable or stale server selections are rejected against the current title/episode.
- Native audio selection now executes against the service's actual Media3 TrackGroup, rather than a controller copy. A transaction tracks the latest request, shows Applying, confirms selected decoder/PCM evidence, commits preferences after readiness, and rolls back errors/timeouts. The confirmed checkbox stays accurate while controller metadata catches up. CineJoy recovery remains integrated.
- Serienstream audio-edition changes resolve while the current edition continues, preserve current position, pause state, caption edition and manual delay, cancel superseded requests, and confirm readiness before committing. Failures restore the previous request. Unlabelled VOE audio uses its resolved edition language before a show's original language.
- Sync with Audio starts its independent silent decoder immediately, uses the original Silero model and original WebRTC/native code, retains actual timestamped PCM runs across HLS segments, and retains valid delayed Silero results from earlier segments of the same fixed soundtrack. The visible player still rejects old continuity/generation results. Acquisition stops on success/cancellation or its eleven-second scan deadline, scans at most four minutes, bounds each retained PCM run and the number of independent clips, and stays within the existing nineteen-second overall sync deadline. It never seeks or accelerates the visible player.
- Mobile speech recovery is local only. The previous HTTP audio-upload fallback was removed and replaced with serialized Android on-device recognition. Unsupported local recognition falls back to native speech-presence evidence; no microphone, remote audio processing, timing guess or reduced confidence threshold is introduced. Buffered evidence now distinguishes an ambiguous match from insufficient dialogue.

The numerical alignment algorithms, acceptance thresholds, rate models, manual-delay separation, held-out validation, selected audio/subtitle identity, source/seek/account fences and reset/cancellation guards remain intact. The Silero asset and Android native binaries are not recompiled or modified.

## Design references

Native adaptations follow [Aceternity Stateful Button](https://ui.aceternity.com/components/stateful-button), [Animated Modal](https://ui.aceternity.com/components/animated-modal), [Motion press gestures](https://motion.dev/docs/react-gestures), and [Radix Data List](https://www.radix-ui.com/themes/docs/components/data-list). Existing AliflixMobileTheme, AliflixSurfaces and motion conventions supply the actual styling. No React or WebView feature UI was introduced. Track selection follows [Media3's override contract](https://developer.android.com/media/media3/exoplayer/track-selection).

## Executed evidence

- Portable unit suite: 645 tests, zero failures, three opt-in native skips. Covers update ABI gates, provider/native wiring, synchronization offsets/drift/edits/ambiguity, cancellation, audio mapping, decoded-clip continuity, recognizer serialization, launch timing and room protocol.
- Native-host gate: all 11 tests passed with zero skips, including actual WebRTC JNI capture, the original Silero/ONNX session, room protocol and updater parsing.
- Existing Worker: typecheck and all 128 tests passed. No backend mutation/deployment was needed for these client fixes.
- Pixel 7a, isolated validation package: 39 awake UI/player regressions passed. These cover launch/motion, controls, Arabic caption rendering, details, narrow screens, large fonts, settings, More and subtitle behavior.
- Pixel native audio test: two independently decoded AAC tones verify English/German switching and rapid latest-choice behavior. No seek, playback error or incorrect final checkbox was accepted.
- Pixel live Serienstream edition test: The Big Bang Theory S1E1 changed German→English after rapid competing requests, then back to German. Exact paused position 125000ms and original captions survived both changes. Passed in 7.575 seconds total.
- Pixel deployed-room consent test: exact content stays in-room; another title/episode prompts once despite repeated taps; Stay preserves the room and Leave & play clears it before proceeding. Passed.
- German audio / English captions: The Big Bang Theory S1E1 synchronized locally after eight seconds of played dialogue, 2157ms after tapping Sync with Audio, offset +0.68s/rate 1.0, without a seek or position change.
- German audio / Arabic captions: The Big Bang Theory S1E2 synchronized locally after eight seconds of played dialogue, 7798ms after tapping, offset +2.32s/rate 25/24, confidence 0.8946546586, without a seek, position change, playback resume or replacement caption edition. Reset restored the original clock.

### Limits and failed checks

The automatically selected Arabic S1E1 caption edition and English S1E2 edition did not satisfy independent timing verification in the tested eight-second scenarios. They were rejected within the bounded deadline with zero playback discontinuities or position changes. A trial change to alignment-window sampling was rejected and reverted after its regression failed; alignment thresholds and algorithms were preserved. These cases are not reported as successful synchronization or a guarantee that every caption edition matches every dub.

Initial English-labelled Terminator checks inherited the global Arabic preference and did not test the intended caption language. The fixture now sets/restores global and local preferences and asserts the actual language before synchronization. Those initial checks are not counted as English successes. After correcting the fixture, the actual English Terminator caption edition still rejected the eight-second sample within 18594ms; no correction was applied. This is an unresolved matching limitation, not a passing regression. The original physical lock-screen regression was interrupted after Android's secure lock activated and an AOD/OFF assertion failed. The user unlocked the Pixel; the completed 39-case awake suite excludes that secure-lock test. CI runs its separate emulator background/lifecycle gate.

Normal-font and large-font action layouts, read-only source settings, leave consent, actual audio checkmarks, launch reveal frames and intermediate player animation frames were captured and visually reviewed.

Only Pixel was visible to ADB during this follow-up. The two-phone cross-provider runs from v166 are prior evidence, not newly rerun here. No phone provider sweep was substituted for the off-phone routing tests.

Automatic approval review blocked an optional transcript diagnostic because it would upload episode audio to Groq. That diagnostic was removed, and the existing mobile upload fallback was removed as well. No rejected upload was executed.

## Release gates

The signed main APK is ARM64-only. Old update clients receive a universal 3.1.167/code 257 bridge; the bridge and current clients read the ABI-aware manifest for 3.1.168/code 258. Incompatible ARMv7/x86-only devices remain on an installable universal build. Both APKs retain the original package/signing identity. The workflow verifies every native/model SHA-256 against published v3.1.164, signed APK integrity, version, signer and both update manifests.

Final CI, independent published-artifact checks, exact sizes and installation evidence are recorded in the publication report after the release finishes. External provider availability and arbitrary future network/subtitle editions cannot be guaranteed indefinitely.
