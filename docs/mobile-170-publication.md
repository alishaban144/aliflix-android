# Mobile 3.1.170 publication

Release: https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.170

Commit: `22aa764`. GitHub workflow: https://github.com/alishaban144/aliflix-android/actions/runs/39057063485. Detailed implementation, prior failures, source evidence and screenshots: [validation report](mobile-170-validation.md).

## Final deployed configuration evidence

The final directorial-intent prompt was deployed by the existing workflow, and the physical ten-turn native Gemini Live acceptance was repeated successfully against that deployment: **10 continuous turns in 185.094 seconds**, with `gemini-3.8-live` and pinned `Aoede` throughout. Genuine native interruption, English/German/Arabic interaction, same-session reconnect, Shining follow-up context and Dark S1E1 boundaries passed. The German ambiguity answer no longer volunteered the previous unsourced director-intent assertion. This supports the measured cases, not a zero-hallucination or subjective naturalness guarantee.

Cold first rendered audio: **3805 ms**. Warm median: **1444 ms** (1072–5163 ms). Totals include actual spoken answers; PCM-input measurements also include the generated question and silence tail.

| Turn | First rendered audio ms | Total ms |
|---|---:|---:|
| 1 | 3805 | 24527 |
| 2 | 1077 | 20562 |
| 3 | 1072 | 15538 |
| 4 | 1531 | 18241 |
| 5 | 1280 | 17116 |
| 6 | 2160 | 18624 |
| 7 | 1170 | 17434 |
| 8 | 4532 | 16861 |
| 9 | 5163 | 17555 |
| 10 | 1444 | 15935 |

Final local mobile lint completed with zero errors; TV debug compilation passed, with no TV source/version changes or TV release. Portable suite: 668 tests, zero failures/errors, three native opt-in skips. Separate original-native gate: ten tests, no failures/errors/skips. Worker typecheck and 150 tests passed. The Pixel Shining German-to-verified-English case, unconventional title ratings and six source-picker/next-episode UI cases passed as described in the validation report.

Human acoustic echo/barge-in, subjective voice naturalness and long-duration service rotation remain unverified. Native PCM voice probes used owned generated input with the microphone muted. The stalled screenshot fixture is excluded from passing test counts; actual OS-level frame captures were reviewed separately.

## Published artifact verification

GitHub run **39057063485 succeeded**: 71 Android API 35 playback/UI tests, Worker typecheck/150 tests, backend deployment/live Watch Party and genre smoke checks, release unit/lint gates, signing, native parity and publication. No validation skip was requested. Final build logs show all four Gradle invocations succeeding.

Both public APKs were independently downloaded. ZIP integrity, package `com.aliflix.app`, minSdk 29, exact updater hash/size/code/URL, ABI set and original production certificate were verified. The actual `releases/latest/download` updater URLs match the new assets. All five original ARM64 native libraries and the Silero ONNX model retain exact SHA-256 parity with v3.1.164; the universal bridge retains all original native/model bytes across all four ABIs. The independent parity report equals the workflow-published `native-parity.json`.

| Artifact | Version / code | Exact bytes | ABIs |
|---|---|---:|---|
| aliflix-mobile.apk | 3.1.170 / 260 | 26,085,379 | arm64-v8a |
| aliflix-mobile-compat.apk | 3.1.169 / 259 | 66,464,701 | arm64-v8a, armeabi-v7a, x86, x86_64 |

Main SHA-256: `eaaf8d9d45b36dc326b862704e48a51e5cb9c5efece661d92d0b2dbef66fb9d7`.
Bridge SHA-256: `445df6744700763b5eb479af89d9d7bd93b0eab6bc2a1b15804ebd3ddc78b45f`.
Original production certificate SHA-256: `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`.

Reduction versus the original 66,266,641-byte v3.1.164 APK: **40,181,262 bytes (60.6357307291%)**. The same-version universal bridge isolates ABI packaging savings: **40,379,322 bytes (60.7530334034%)**. Main APK growth versus v3.1.168 is 15,100 bytes. Legacy clients receive the installable universal bridge; ABI-aware ARM64 clients receive code 260. No TV APK or TV update metadata was published.

Machine-readable results: [artifact verification](mobile-170/artifact-verification.json).

## Final Pixel installation and signing limit

The existing main installation was v3.1.166/code 256 signed with the test certificate `519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`. Its app data was preserved. A private copy of the downloaded release was signed with that existing test key; all **1,055 non-signature ZIP entries were byte-identical** to the public APK. That copy installed successfully with `adb install -r` and reports 3.1.170/code 260. No uninstall or data clear was performed; the original public APK was left unchanged.

The minified public payload launched, retained the existing library/history, and passed Home → Discover → tapping the orb itself → Ask Aliflix → Talk. The moving field, clear typography and native orb were inspected in the [release-payload screenshot](mobile-170/talk-release.png). No runtime crash was observed during this smoke check. Start was not pressed for an unattended ambient-microphone test.

This is payload/runtime evidence under the existing test signature, **not proof of a production-signer in-place upgrade on this Pixel**. Its test certificate still differs from GitHub's production certificate. Moving this particular installation to production-signed automatic updates requires a separate user-approved data migration; that destructive/signing change was not performed. Production-signed public clients retain their original signer and the ABI-safe update path.

## Deployed endpoints and remaining manual validation

Backend: `https://aliflix-recommendations.equable-equipment.workers.dev`.
Authenticated POST routes: `/v1/talk/session`, `/v1/talk/knowledge`, `/v1/talk/close`. The constrained Google Live WebSocket is opened directly by Android with only the ephemeral token. `GEMINI_API_KEY` and `TAVILY_API_KEY` are already configured server-side; no new manual secret setup was required.

Remaining manual validation is real-room human acoustic echo/barge-in, subjective voice naturalness, and long-duration session rotation. There is no claim of human-level fluency, zero hallucinations, universal future provider availability or solved short multilingual subtitle ambiguity. All raw voice fixtures, session tokens and private playback URLs remain outside Git.
