# Mobile 3.1.182 release evidence

Published 2026-10-26 at 20:47:03 UTC. [Public release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.182), [full changelist](mobile-182-changelog.md), [research and validation](mobile-182-validation.md).

## Source and CI

- Tested/tagged source: `8efc6543e07b283c735af652bfef9e66ed9eccc2` on `codex/talk-intelligence-182`.
- [Signed workflow](https://github.com/alishaban144/aliflix-android/actions/runs/40396495508), attempt 2: success. Unit/lint, backend checks/deployment, publication readiness, APK integrity, production signer and native/model parity gates passed. Emulator execution was explicitly skipped; physical Pixel checks were used.
- The obsolete unpublished draft/tag at cb52ba63 was inspected and removed before the corrected job reran. It was never published. The final tag points to the reassessed source above.
- Final CI backend deployment: `9aee0f0c-717b-44f3-be00-267fcafac689` (same source). Local final Worker gate: 270 tests, typecheck and dry-run passed. Local Android gate: 763 tests, zero failures/errors, three skips; lint/mobile/test and TV compatibility assembly passed.

## Public signed artifacts

| Artifact | Version | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| ARM64 mobile | 3.1.182 / 272 | 26701073 | `de86e41150606bc36d1dff8dab2b5e4304c93dad79b9fa03272852f03dbac75c` |
| Universal compatibility bridge | 3.1.175 / 265 | 67080391 | `8943be903ca06d19ca1caa4fd82fe3e77fecc956a3935e9260c803f7575a3f3f` |

Both retain production certificate SHA-256 `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`. The mobile APK contains arm64-v8a; the legacy bridge contains arm64-v8a, armeabi-v7a, x86 and x86_64. Minimum SDK is 29. ZIP integrity and native/library/model asset hashes passed independently after download.

The exact BuildConfig canonical updater, [update-mobile-arm64.json](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile-arm64.json), advertises 272 / 3.1.182, the correct ARM64 ABI, minimum SDK, APK URL, size and digest. The [legacy updater](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile.json) advertises the compatibility bridge. Independently downloaded public APK bytes matched both verified draft artifacts. [Machine-readable verification](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.182/release-verification.json).

## Physical Pixel evidence

- Pixel 7a, mobile 3.1.182 / 272, target SDK 37. Installed using same-signer updates; existing data retained. No emulator or app-data clear was used.
- Final regression run: 20 tests passed in 184.563 seconds, including wire ordering, 80 ms speech with capture stopped, Ask single-loader/no-For-you states, native Media3 controls, durable task execution and three separate cold spoken requests plus developed discussion/continuation.
- Cold spoken comedy/science-fiction/discussion calls produced 345,600 / 243,600 / 974,160 rendered samples, without a wake-up utterance. First-audio elapsed 10.530 / 11.412 / 12.457 seconds includes speaking each input and opening the session. Actual route was built-in speaker, usage ASSISTANT, mode NORMAL, selected volume 2; the app did not raise volume.
- Physical speaker sound was checked independently of playback counters with an owned 997 Hz probe and unprocessed microphone. Only spectral magnitudes were retained. At selected volume 10, signal was over 1,000 times baseline; at selected volume 2 the final check passed at roughly 13 times baseline. No raw microphone recording was stored or sent to AI.
- Developed 3-Iron/And?/exact-90-minute decision journey passed with zero self-interruptions in the tested speaker/microphone turns and a genuine near-end interruption through the production duplex gate. Owned PCM injection exercises actual DSP/VAD paths; this is not a human-in-room acoustic conversation claim.
- A Live-created two-film task finished at the checked total of 173 minutes with Talk suspended. Its fixture was cancelled afterward. A separate real backend episode plan replanned ten remaining Scavengers Reign episodes to eight after progress changed, retained catalogue work and honored daily budgets.
- Contextual orb check passed in 14.538 seconds across eight states, with one face inside the viewport. Conversation focus moved left/right for the actual first/second visible titles, Thinking shrank/lifted, Listening recentered, and chat docked. [Actual phone recording](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.182/aliflix-talk-orb-182.mp4) preserves all 1,261 variable-rate source frames over 17.915 seconds; the share copy is 540x1200. This is visual evidence, not a formal frame-time benchmark.

The phone has a debug signer (`519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`); public artifacts retain the production signer above. Physical checks establish the candidate's behavior, not a production-signer upgrade installation. The phone was left at Home with microphone use stopped. TV source, version (3.1.69 / 159) and release remain unchanged.

Coverage is representative, not exhaustive across every acoustic environment, headset, recurrence interval or media provider. Catalogue release does not guarantee provider availability. Current AI paths remain in use, with no new verification model or external-app agent.
