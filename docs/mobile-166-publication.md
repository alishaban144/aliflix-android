# Aliflix mobile 3.1.166 publication evidence

[Release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.166) · [APK](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.166/aliflix-mobile.apk) · [Release workflow](https://github.com/alishaban144/aliflix-android/actions/runs/38803180111) · [Implementation and validation](mobile-166-validation.md)

Published Android source: `ba4c38ed54823b8a8bdca6a5ece2d49542965acb`. Main APK: package `com.aliflix.app`, versionCode 256, versionName 3.1.166, minimum SDK 29, ARM64 only. TV source, version and packaging remain unchanged; no TV APK was published.

## Independently downloaded artifacts

| Artifact | Exact bytes | SHA-256 |
| --- | ---: | --- |
| Published v3.1.164 baseline | 66,266,641 | `9a6a97bc69d9f6f12dd8df13a5d02e1a04e62a3aea3672bb5c925eaf53703873` |
| ARM64 v3.1.166 | 25,974,551 | `0a15267983c86d2ae71664c5b35516e09995512a5baa1b6532c22496834d036d` |
| Universal compatibility bridge v3.1.165 | 66,354,101 | `fcf370f4d8a4f6e82eb6845a3ad877533557073de7156641f4a88b8cbdac8e9a` |

The signed main APK is **40,292,090 bytes smaller**, a **60.80297626674634% reduction** from the published baseline, including the new feature code.

Both published APK signatures verify against the existing certificate SHA-256 `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`. ZIP integrity, package/version/minimum SDK and ABIs were independently checked after downloading. The main APK contains only `arm64-v8a`. The bridge retains `arm64-v8a`, `armeabi-v7a`, `x86`, and `x86_64`.

Every original ARM64 library and the original Silero ONNX model are byte-identical to v3.1.164. Every original native library for all four ABIs is also byte-identical in the bridge. The independently generated parity report equals the [published native-parity.json](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.166/native-parity.json). No model quantization, native recompilation, precision change, audio synchronization algorithm change or remote audio processing was introduced for packaging.

Both tagged manifests match the downloaded APK bytes and hashes. Independently fetched `releases/latest/download/update-mobile.json` and `update-mobile-arm64.json` also match those published assets. Legacy clients receive the installable universal bridge (255); architecture-aware clients accept ARM64 version 256 only on supported devices. ABI rejection has portable regression coverage; no physical ARMv7-only/x86-only phone was available.

## Executed gates and physical evidence

| Gate | Result |
| --- | --- |
| Android portable unit suite | 636 tests, zero failures/errors, three optional tests skipped |
| Separate opt-in JNI/model/clock/update gate | 11 passed, zero skipped, including real WebRTC JNI and Silero/ONNX sessions |
| Mobile lint/build and TV compile protection | Passed |
| Final backend security/protocol suite | 128 passed |
| Final release API-35 emulator suite | 60 passed; no test/device waivers |
| General physical Pixel UI/player suite | 40 passed |
| Live native room UI | Passed in portrait, landscape and 200% system font |
| Two physical phones | Pixel Serienstream / Redmi CineJoy; independent decoded audio/video, authoritative controls, permission denial/grant, reconnect, host transfer, S1E2 readiness and completed player UI passed |
| Public host-disconnect check | Grace-period transfer, secure readmission, former-host rights and ending passed |
| Public host-leave with every guest offline | Retained owner, secure reconnect and host ending passed |

The two-phone buffering recovery step deliberately published a participant buffering status. It is protocol recovery evidence, not a claim to reproduce every real network interruption. The broad streaming-provider audit used host-side adapter/request/Media3 wiring regressions; live playback of every title on every provider is not asserted.

The Pixel's real cold launch was recorded (Android activity launch 977ms) and animation/Home handoff frames were inspected. Actual room and completed player screenshots were reviewed. Local evidence is under `.validation/`, including `party-pixel-visible-proof`, `party-redmi-visible-proof`, `party-ui-landscape-final-proof`, `party-ui-font2-proof`, `launch-frames`, and `ci-38803180111`.

## Published payload and device signatures

The Redmi's existing official installation upgraded in place through bridge 255 to the downloaded ARM64 256 APK. Its data was not cleared. Android independently reports the invitation domain as **verified** against the public release certificate. Valid HTTPS invitations passed direct app resolution, title/episode matching, cold start, replacement of a restored room by a new invitation, and already-running navigation.

The Pixel's pre-existing v3.1.164 installation used the machine's local development certificate (`519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`), rather than the published certificate. Android correctly rejected the official APK as an incompatible signature. To preserve its existing data, the downloaded APK was privately re-signed with that matching local key and installed in place. All non-signature ZIP payload bytes, including DEX, manifest, resources, models and native libraries, were compared and are identical. This private device copy was not published.

On that unchanged published payload, the Pixel opened a real HTTPS room invitation, entered the native player through Ready, decoded Dark S1E1, followed host seek/play/pause commands, and displayed `Serienstream · German · VOE` in More. Actual playing/provider screenshots were reviewed in the local `.validation/signed-links-*` evidence directories. Cold and warm invitation navigation also passed. The Pixel's link handling is explicitly user-selected for its local-key installation; **certificate verification is claimed only for the official APK on Redmi**.

Both phones now run version 3.1.166; temporary validation packages were removed. No current manual step is required to use this release. Moving the Pixel from its existing local certificate to the official automatic-update channel will require a deliberate data/certificate migration; Android cannot replace a differently signed installation in place. Its data was preserved rather than erased for that migration.

## Deployed backend

The existing GitHub deployment workflow published the final recovery hardening from `46d552e113cd9d140e4e959be576156a89f8e7fc`: [backend run 38806131730](https://github.com/alishaban144/aliflix-android/actions/runs/38806131730). Worker version: `374f58ca-4c10-40c0-b921-6579095f7159`. All 128 backend tests, deployment, the live two-client Watch Party smoke test and existing browse check passed. This backend-only deployment did not build or release a TV/mobile APK.

Base: `https://aliflix-recommendations.equable-equipment.workers.dev`

- `POST /v1/party/identity`
- `POST /v1/party/rooms`
- WebSocket `/v1/party/rooms/{room}/socket`
- HTTPS invitation landing `/party/{room}` with private invite fragment
- [Verified App Links declaration](https://aliflix-recommendations.equable-equipment.workers.dev/.well-known/assetlinks.json)

Existing deployment credentials and infrastructure were sufficient. No new paid service or required manual backend action remains. Third-party websites, title availability, challenges, editions, CDNs and connectivity can change; future uninterrupted provider availability cannot be guaranteed.
