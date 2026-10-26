# Aliflix mobile 3.1.168 publication evidence

Mobile-only release from 8de0396. TV remains 3.1.69/code 159; no TV APK is published.

## Release and deployment

- Release: https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.168
- Workflow: https://github.com/alishaban144/aliflix-android/actions/runs/38982462909
- Authenticated Talk WebSocket: wss://aliflix-recommendations.equable-equipment.workers.dev/v1/talk
- Invitation fallback: https://aliflix-recommendations.equable-equipment.workers.dev/party/
- Android association: https://aliflix-recommendations.equable-equipment.workers.dev/.well-known/assetlinks.json
- Fish is configured as an encrypted Cloudflare secret. Wrangler vars contain only model settings. No additional key setup remains.

## Independent public artifacts

| APK | Bytes | SHA-256 |
|---|---:|---|
| aliflix-mobile.apk | 26,070,279 | `cb5afd1f8773b8ecf39fd87621b0abe66863e09f75d0ae51728973ff915f21a4` |
| aliflix-mobile-compat.apk | 66,449,641 | `541d6c191f3d6ed3fa7a922bc0d27de98e5a4fe9403ec3201406e8d2afe1275f` |

Native/model hashes match the v3.1.164 baseline byte for byte. The ARM64 APK includes only arm64-v8a libraries; the compatibility bridge preserves all four original ABIs. Both public APK signatures were independently verified against the existing production certificate. Legacy update metadata advertises the universal bridge (code 257); the architecture-aware manifest advertises ARM64 (code 258). Incompatible architectures do not receive the ARM64 package.

ARM64 is 40,196,362 bytes (60.658517%) smaller than the v3.1.164 baseline; ABI packaging alone saves 40,379,362 bytes (60.766863%) against this release’s universal bridge. New application code adds 95,728 bytes compared with already-ARM64 v3.1.166.

## Executed validation

- Worker: 147 tests and typecheck passed; live deployment passed existing Party/genre smoke checks.
- Portable mobile suite: 665 tests, zero failures/errors, three native-only skips; actual native-host suite: 10 tests, no skips/failures. Mobile lint: zero errors. Final TV debug APK and mobile compilation passed.
- Physical Pixel: final awake suite 59/59; separate next-episode portrait/landscape/completion geometry 3/3.
- Nine real-title priority-source starts passed: Filmo Interstellar / Green Book / Frozen (2013), Serienstream Dark / The Big Bang Theory / Narcos, AniWorld Black Clover / Death Note / Cowboy Bebop. Actual German download edition and request passed. Rapid paused DE/EN/DE switching preserved position/captions; independent AAC-tone PCM proved native audio changes.
- Talk English/German/Arabic generated-question end-to-end checks reached real ASR, reasoning and physical PCM rendering; interruption passed. Final English response began in 9290ms and reconnect retained context (12.49s complete test). This test muted ambient microphone upload and injected legally generated question PCM.
- The particle sample rendered 133 frames with one missed deadline. Native screenshots and animation frames were reviewed. This short sample does not establish all-device or sustained-load performance.

Final GitHub API35 emulator suite passed all 65 tests (156.965s), including actual native audio changes, independent scan identity/paused clock, and the new Talk privacy/entry UI. Final production build/unit/lint/signing results: all passed; workflow completed successfully..

The first publication attempt stopped before signing/release after catching a CRLF in the final instrumentation class name and a pause-acknowledgement race in the scanner fixture. The shell script now uses LF; the fixture waits for actual advancing playback and the acknowledged paused clock. No production playback behavior or assertion tolerance was changed for that retry.

## Scope and remaining limits

See [mobile-168-validation.md](mobile-168-validation.md) for implementation details, references and passing/failed synchronization cases. Original Silero/ONNX/WebRTC bytes, core alignment algorithms, precision and confidence gates are preserved; episode PCM stays local.

German audio / English captions passed the latest short-sample case in 1663ms without seeking. Earlier German/Arabic S1E2 cases passed, but later short-sample repetitions rejected matching. Arabic S1E1, German S1E1 and English Terminator cases also retain unresolved matching limits. Failed matching does not apply a speculative subtitle shift. Reliable synchronization for every language/subtitle edition is not claimed.

Only one Pixel was connected during the follow-up; two-phone Watch Party was not rerun. Prior v166 cross-provider two-device proof remains earlier evidence. Human acoustic echo/barge-in, full 30-minute voice sessions, ASR rotation under real acoustic use and arbitrary provider failures were not end-to-end verified. Provider embedding restrictions are not bypassed. Current-scene plot claims are denied without reliable timed evidence.

The production signer, ZIPs, package/version, ABIs, update metadata and public native/model bytes were independently verified. Phone evidence used an isolated test installation; a production-signer upgrade on the test-signed Pixel is not claimed. Its existing data was preserved.
