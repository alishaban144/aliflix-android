# Aliflix mobile v3.1.156 release verification

Released mobile v3.1.156 / versionCode 246 from `e913a9d99bf71ba552b5201628310a127c11dfbd`.

- [Release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.156)
- [Successful release workflow](https://github.com/alishaban144/aliflix-android/actions/runs/38080951570)
- [APK](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.156/aliflix-mobile.apk)
- [Updater manifest](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.156/update-mobile.json)

## Review and host checks

Before publication, two additional regressions were fixed: type filtering now precedes fuzzy correction decisions, preventing a closer TV title from suppressing a movie correction; the source-subtitle phase now reserves time for SubDL even when multiple downloads stall.

Local mobile tests, lint, debug assembly and minified benchmark assembly passed. There are 595 tests: 592 passed, zero failures/errors and three existing environment-dependent skips. Lint reports zero errors, 110 warnings and four hints. All nine release-workflow Bash blocks passed syntax checks.

The GitHub release workflow passed unit tests, lint, signed mobile release packaging and APK integrity checks. Playback-device testing and Worker checks/deployment were skipped in the successful release run. The released tree is identical to the locally validated tree; its parent is the published v3.1.155 commit.

## Independently downloaded public artifacts

- APK size: **66,156,137 bytes**.
- APK SHA-256: `ea0c47ac2e8e9aca25083261efe5a4ea02deadc2fa41b2cc36847b1c39d81a37`.
- Production signer SHA-256: `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`.
- Package: `com.aliflix.app`; versionCode 246 / versionName 3.1.156.
- APK v2 signature and ZIP integrity passed.
- Release and latest updater manifests are byte-for-byte identical and match the downloaded APK's hash and size. The APK URL names v3.1.156.
- All four supported ABIs and all 20 native libraries are preserved. Native libraries and functional assets match the previous public v3.1.155 APK byte-for-byte, including Silero VAD.
- The exact 74 verified unused protobuf source schemas are absent. Dependencies, ONNX Runtime, native libraries and ABI configuration are unchanged.

The public APK is **147,653 bytes smaller** than the prior public v3.1.155 APK. The controlled identical-feature cleanup pair saved 162,013 bytes, with all 1,068 retained ZIP entries identical; those measurements isolate packaging cleanup from feature changes. The initial Windows validation APK's license file used Windows line endings, so the final cross-release asset comparison uses the prior public APK instead.

## Recovery audit

The first release run used the local checkout's alternate commit history and compared against an older ancestor tag. It was cancelled and replaced by the identical validated tree parented directly to published v3.1.155. The cancelled run briefly redeployed unchanged Worker code before cancellation completed. After explicit user approval, [isolated recovery workflow 38081283584](https://github.com/alishaban144/aliflix-android/actions/runs/38081283584) restored the exact previous Worker version `7ac45b8f-3be3-4cbf-a61c-6c3fbb81653e` at 100% traffic. Deployment-list output confirmed the restoration. The temporary recovery branch was removed. Worker and TV source trees are unchanged.

## Limits of evidence

No emulator or physical-device testing was performed, as requested. Public artifact/signing/update compatibility and host regressions are verified; rendered UI, actual audio amplification/routing/distortion and device/provider playback remain unverified. Personal match tiers are deterministic affinity evidence, not calibrated enjoyment probabilities. No TV release was produced.

Raw local evidence is retained in ignored `.validation/release-v156/`, with the implementation and controlled cleanup audit in `mobile-v3.1.155-search-library-playback.md`.
