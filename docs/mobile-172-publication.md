# Aliflix mobile v3.1.172 publication

[Release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.172) · [Successful workflow](https://github.com/alishaban144/aliflix-android/actions/runs/39073764353) · [Device and implementation evidence](mobile-172-validation.md) · [Independent artifact verification](mobile-172/artifact-verification.json).

Published commit: `e277d71f0f80a16734105e07399dc7631234789f`. Mobile only; TV remains 3.1.69/code159 and no TV APK was released.

| Artifact | Version/code | Bytes | ABI |
|---|---|---:|---|
| Main APK | 3.1.172 / 262 | 26,093,391 | arm64-v8a |
| Legacy compatibility bridge | 3.1.171 / 261 | 66,475,965 | arm64-v8a, armeabi-v7a, x86, x86_64 |

Main SHA-256: `5200587732fc86d0e7f52d82ff9415a220c77aa8d3af142af534cc8bfeaf9ef9`.

Both APKs verify with the existing production certificate SHA-256 `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`. Package, version, minimum SDK29, ZIP integrity, architecture sets and metadata hashes were independently checked after downloading the public assets. Both latest-download updater URLs matched the released manifests. Legacy clients receive the installable universal bridge; architecture-aware clients select the ARM64 update only on compatible devices.

All native/model bytes match the original v3.1.164 baseline. Main APK reduction: **40,173,250 bytes (60.6236401811886%)** from 66,266,641 bytes. Comparing the two current artifacts isolates **40,382,574 bytes (60.747631117502394%)** of ABI packaging reduction. Main APK grew by only **8,012 bytes** from v3.1.170.

The final workflow passed mobile unit tests, mobile lint, backend typecheck/157 tests, backend deployment/live smoke, APK signature/integrity and native/model parity. The Android gate reported `OK (71 tests)` with two motion-specific assumption skips; those skipped tests are not counted as executed visual proof. Physical Pixel checks separately covered automatic listening, four native voice turns, pinned Aoede, English retained through a German-title question, explicit German switching, stable/updateable semantic topics, safe exit, inline preferences and all 26 Dark episode ratings. Final first-audio measurements were 1450/1259/1536/1017ms. A synthetic-energy rendering fixture covered Latin/Arabic dots and the final shader with 1052 frames, no modern jank or missed vsync; legacy counter and percentiles are retained in the validation report.

Backend endpoint: `https://aliflix-recommendations.equable-equipment.workers.dev`. Authenticated `/v1/talk/session`, `/knowledge`, `/viewing` and `/close` use the existing identity flow. Gemini 3.8 Live/Aoede remains the sole voice engine. An actual authenticated synthetic-history request resolved Dark and The Shining with exact partial progress (236ms; cached65ms).

The Pixel's earlier tests used its existing private test certificate, retaining app data. A private re-signed copy of the public APK was prepared and compared: **1,055 non-signature entries are byte-identical**, and the original public APK was unchanged. However, the phone disconnected from USB during the GitHub build; the final install attempt reported no devices. The published APK's final on-device installation/runtime and a production-signer upgrade on this development phone are therefore **not verified**. No app reset or uninstallation was performed. Reconnecting the Pixel is needed only to repeat that final installation check; public release and independent artifact verification are complete.

These tests do not establish universal AI accuracy, arbitrary-provider uptime or flawless operation on every device. Subtitle synchronization algorithms and original Silero/ONNX/WebRTC assets were unchanged.
