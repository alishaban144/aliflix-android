# v3.1.148 physical mobile acceptance

Date: 2026-10-05. Device: Pixel 7a, Android 17, authorized USB ADB. No emulator.

The baseline is published v3.1.145, followed by the published v3.1.147 source
(`2455a8187c8e66f14c13164d57492169a9dad033`). The old main branch is not used.
TV code, TV version and recommendation Worker remain unchanged.

| Check | Result |
| --- | --- |
| Actual Tears of Steel movie, published English captions +7.25 seconds | -7.28 seconds; 506 ms; no seek, rebuffer or interrupted playback |
| Actual movie's silent opening | Unable to Verify at 19,040 ms; no guessed correction |
| Fully minified v3.1.148, independent actual UI driver, same real movie | -7.32 seconds; 1,043 ms; Synced and Reset passed |
| Normal streaming human recording, +47-second offset | -46.92 seconds; 746 ms; Cancel never committed |
| Normal streaming human recording, 25/24 FPS clock | Rate 1.0416666667, -48.92 seconds; 509 ms |
| Completed offline human recording, offset / FPS / edited scenes | 6,239 / 6,250 / 6,623 ms; every corrected cue within 0.6 seconds |
| Completed offline recording, ambiguous edit | Rejected at 7,687 ms; originals preserved |
| Final mobile unit suite | 440 total, 0 failures/errors; 3 optional checks skipped |
| Separate actual host JNI + recorded-film checks | 3 passed; real Media3 extraction, WebRTC and ONNX |
| Mobile lint | 0 errors; existing warnings retained |
| Mobile debug, instrumentation APK and R8 benchmark builds | Passed |

Streaming timing above is measured from the tap. Evidence accumulates automatically
at normal speed before the tap: 85 seconds for the real movie, 130/200 seconds for
the human recording's offset/FPS tests. This is not an instant cold-start claim.
Silent or ambiguous audio cannot establish a reliable subtitle clock.

The offline recording concatenates 30 actual human recordings with independent
annotations; it is not a commercial film. The film tests use the real published
movie and subtitles, and introduce a known subtitle offset for an objective check.
The permanent film regression stores only measured fingerprints and numeric timings,
with no raw media or transcript text.

The optional CineJoy live-network unit check is unrelated and skipped. The two
optional JNI checks are executed separately with the actual native libraries.
The independent minified driver has no application-class references or shared
JUnit runtime. It uses actual Android accessibility controls. The local benchmark
is minified with normal release rules and uses the existing phone development
signer to preserve installed account/app data; the workflow checks production
signing separately.

Detailed reports: [real film](quick-sync-real-film-device.txt),
[minified film](quick-sync-minified-device.txt),
[offline models](quick-sync-offline-device.txt),
[streaming offset](quick-sync-streaming-offset-device.txt),
[streaming FPS](quick-sync-streaming-drift-device.txt).
