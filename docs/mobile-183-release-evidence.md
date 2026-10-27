# Mobile 3.1.183 release evidence

Released **3.1.183 / 273** on 2026-10-27. [Public release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.183) · [Full change list](mobile-183-changelog.md) · [Research and physical validation](mobile-183-validation.md).

## Source and workflow

- Tested application source and release tag: `0a311a165dc6063c761de44a6727ebce499c2405`.
- [Signed workflow 40432883759](https://github.com/alishaban144/aliflix-android/actions/runs/40432883759): completed successfully. Unit tests and lint enabled. Emulator job explicitly skipped; physical Pixel validation was performed locally.
- Worker CI typecheck/tests and deployment passed. Deployed version: `c1956c84-0b78-45d8-939e-8f6f887f229e`.
- Ask publication-readiness gate returned 24 movie and 24 TV items and passed.
- Draft source target and actual Git tag both matched the tested application commit before publication. Later documentation commits only record evidence.

## Public artifacts

| Artifact | Version | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| aliflix-mobile.apk | 3.1.183 / 273 | 26702485 | `1d5e1e6b691e73472fda1ebdc7cffb533989b0db2481ccb7e6e2839d42d027a9` |
| aliflix-mobile-compat.apk | 3.1.175 / 265 compatibility bridge | 67081735 | `d7061d58751277e634a2b091285effb87c159bc783cc2e2f61727bc67ff93fa2` |

Both APKs passed apksigner verification with production certificate SHA-256:

`a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`

The current APK contains only arm64-v8a and requires Android API 29+. The compatibility bridge retains arm64-v8a, armeabi-v7a, x86, x86_64. ZIP integrity, declared versions, actual ABI sets and native/model hashes passed. CI additionally compared all native libraries and neural models against the pinned 3.1.164 baseline, including all bridge ABIs; no neural model or native library was replaced.

After publication, both APKs were independently downloaded from their public URLs and compared byte for byte with the verified draft. SHA-256 and lengths also matched the published manifests. The machine-readable [release verification](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.183/release-verification.json) and [native parity](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.183/native-parity.json) are release assets.

## Actual updater URLs

- [Canonical current mobile updater](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile-arm64.json): 3.1.183 / 273, arm64-v8a, API 29+, 26702485 bytes and the current APK hash above. This is the default compiled mobile UPDATE_MANIFEST_URL.
- [Legacy updater](https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile.json): compatibility bridge 3.1.175 / 265, 67081735 bytes and its hash above. Older clients never receive an incompatible ARM64-only package through the legacy URL.

Both canonical URLs were fetched after publication, rather than inferring updater availability from release assets alone.

## Phone and functional evidence

Pixel 7a, serial 41301JEHN02777, has candidate 3.1.183 / 273. Installed and incoming debug certificates matched before `adb install -r`. No uninstall, data wipe, emulator or production-certificate replacement was used.

The phone's debug certificate is `519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`. Its runtime evidence is separate from verification of the production-signed public artifact; a production-signer upgrade on this debug installation is not claimed.

- Android local gate: 762 tests (759 passed, 3 skipped, 0 failures/errors); lint 0 errors; mobile/test APKs and TV debug assembly passed.
- Worker final local gate: 272 tests / 29 files, typecheck and deploy dry-run passed; CI repeated its gates successfully.
- 29 distinct physical checks/journeys passed across turn ownership, wire ordering, microphone-independent playback, speaker stress, orb, discovery UI, actual morph rendering and live conversation/native-action journeys. One UI leaf-node assertion was corrected and its full 10-test suite rerun; original failed checks and their corrections are documented in validation.
- Owned near-end speech stopped actual playback in 279 ms; a short Stop after mute/unmute stopped it in 410 ms. Mute itself preserved the playing answer.
- 12 speech clips over 41.425 seconds with actual speaker/capture/AEC produced 0 microphone activities. The final spoken comedy/sci-fi/developed-discussion journey recorded 0 interruptions and 0 unsolicited provider interruptions, with no follow-up Hi needed to start audio.
- Fresh welcome, genuinely configured Charon acknowledgement and the real Color of Pomegranates card passed. The voice/card journey recorded 0 interruptions.
- Futurama S1E1 LOW/ar reached actual Media3 transfer while an unrelated question was answered: 720p lowest rendition on the selected source, 16768 caption characters, 128788503 bytes transferred, 64.912285 percent at capture. Only the test-created job was cancelled.
- The additional live 3-Iron / And / closed 90-minute comparison journey passed with 0 self-barges; a subsequent owned spoken interruption during generated speech produced 1 local barge and 1 service interruption.
- The Pixel was returned to Home and test voice sessions suspended. Viewer data/preferences were retained.

Owned PCM injection exercises the production processing path on physical hardware; it is not an assertion about every human, room, headset or network. Full scenario details, timing boundaries and actual failures resolved are in the linked validation document.

No TV package or TV version was released. TV remains 3.1.69 / 159; its local debug compilation passed.
