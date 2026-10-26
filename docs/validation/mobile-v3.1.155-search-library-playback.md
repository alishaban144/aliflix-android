# Mobile implementation and validation

Base: `efdca0e`, mobile v3.1.155 / versionCode 245. Work branch: `codex/mobile-search-library-player`. The reviewed mobile release is prepared as v3.1.156 / versionCode 246. TV source sets, recommendation Worker, dependencies, supported ABIs, signing and update compatibility remain unchanged.

## Implementation

- Discover and Similar share exact-first title retrieval, a 15-minute bounded cache, at most three fallback queries within a 4.5-second recovery budget, full Damerau–Levenshtein distance and ambiguity rejection. Existing catalog/library metadata supplies correction queries for titles with errors in multiple words. Corrected results carry only the requested compact indication. Discovery type filters have separate result caches and share raw retrieval caches. Each type is filtered before correction and ambiguity decisions, so a closer TV title cannot suppress a movie correction.
- My List has one grid with stable title keys, fixed-order nonempty genre sections and horizontal shortcuts targeting the grid's section headers. Genres depend on each title's TMDB/OMDb genres. Missing metadata stays visible without an invented category while existing library enrichment and account synchronization fetch/persist metadata. The existing saved grid state handles restoration. Metadata arrival may move an initially unclassified title into its actual category.
- Portrait Skip Intro uses the measured playing-video bottom; landscape positioning is retained.
- Automatic subtitles read current preferences, prefer exact-language embedded/source tracks and fall back to the existing SubDL API. Candidate validation covers canonical language codes, conflicting language labels, text scripts/language markers, episode filenames, archive entries and complete movie coverage. Downloads are cancellable and bounded; failed candidates advance to alternatives. The source phase is limited to 12 seconds, preserving the remaining 16-second SubDL budget even when source downloads stall. Generation, stream, episode, preference and service-level activation checks reject stale results. Manual Off follows the title/series; a selected file belongs to its exact episode. Preference/cloud changes preserve explicit manual choices. External captions use the existing clock and keep unrelated Media3 text tracks disabled. Searches run after playback readiness.
- Storage limit uses a compact row with actual cache usage, a thin usage indicator and a themed preset/custom bottom sheet. Save persists the existing 1–200 GB quota setting; Cancel discards edits. A below-usage warning does not delete downloads or bypass the existing quota policy.
- Boost Audio defaults off and persists in the existing settings store. A single service-owned Android `LoudnessEnhancer` targets +954 mB on the local Media3 audio session. Session/routing changes release the previous effect; casting releases local boosting. Unsupported effects publish Unavailable and leave normal audio. The playback pipeline, speech buffer, system volume and download implementation are not changed by amplification.
- Mobile personalization shares one enriched metadata cache and multi-interest affinity model across Home, Details and Picked for You. Explicit Likes dominate; history contributes at reduced weight. Narrative concepts normalize synonymous descriptions alongside specific TMDB keywords, directors and cast. Genre, popularity, cast or generic keywords alone cannot produce a match. Missing evidence contributes no affinity. Candidate discovery includes recommendations and creator filmographies outside Home rails, with bounded enrichment. Disk metadata expires after seven days and is limited to 256 entries. Mobile labels are Liked, Strong match, Good match and Related; internal ordering values are not displayed as probabilities. Ask Aliflix's recommendation algorithm is retained.

## Recommendation evaluation

The fixtures use Interstellar, The Martian, Gravity, The Shining, The Others, Paddington and Cast Away story descriptions, with Catch Me If You Can / La La Land guarding the ambiguous con-artist/creative-artist false positive. Held-out candidates span space/survival and isolated-house supernatural interests. The old algorithm favors a genre-only, high-popularity fixture with missing story metadata over an intended story match; the new model rejects that false positive and retains the held-out intended stories. Tests also cover synonymous descriptions, unrelated interests, missing metadata, weak history evidence, cross-screen cache consistency and sparse refreshes preserving enriched evidence.

This is a deterministic narrative-concept model, not learned embeddings or calibrated enjoyment prediction. No new model, inference dependency or paid embedding call was introduced. Fixture accuracy does not establish population-wide recommendation quality.

## APK cleanup audit

The inspected v3.1.155 minified universal validation APK is 66,304,522 bytes. Its four ABI directories carry 20 native libraries; the native payload dominates size. ONNX Runtime, Silero VAD, WebRTC VAD, all native libraries and all supported ABIs are preserved.

The only excluded files are the exact 74 Google protobuf **source schema resources** listed in `app/mobile-unused-protobuf-resources.txt`: 420,688 bytes uncompressed / 151,981 bytes compressed. Every file was matched byte-for-byte to an existing dependency archive: protobuf-javalite, Firebase protolite-well-known-types and Firebase Firestore. All retain their generated functional classes and runtime dependencies. No dependency was removed or changed.

All 74 full resource paths have no references in the inspected DEX or native binaries. DEX resource-stream call sites load ONNX native resources and `firebase-auth.properties`; those resources remain packaged. Functional assets, licenses, properties, service metadata and runtime configuration remain packaged. The protobuf compiler generates runtime code from schemas; these source files are not required by generated Android lite classes. See [protobuf generated Java code](https://protobuf.dev/reference/java/java-generated/).

Cleanup uses only the mobile variant's [resource packaging exclusions](https://developer.android.com/reference/tools/gradle-api/8.12/com/android/build/api/variant/ResourcesPackaging). `tools/check-mobile-apk-cleanup.py` compares two builds with identical feature code, requires exactly the allowlisted removals, checks ZIP integrity, checks every retained entry except signature metadata byte-for-byte, and checks all original native libraries and functional assets against the v3.1.155 baseline. APK signing certificates are checked separately with apksigner. No code, algorithm or runtime configuration is altered for the size cleanup.

## Initial paired cleanup result (v3.1.155 feature build)

The identical-feature APK pair is **66,317,846 → 66,155,833 bytes**, saving **162,013 bytes (158.2 KiB)**. Exactly 74 source schemas were excluded. All **1,068 retained ZIP entries** are byte-for-byte identical, including DEX, manifest, resources, functional assets, profiles and native libraries; no signature metadata entry changed. All 20 native libraries additionally match the original v3.1.155 baseline. All 398 source files were hash-checked unchanged across this cleanup pair. ZIP integrity checks passed.

The final APK is 148,689 bytes smaller than the original 66,304,522-byte baseline after accounting for the requested features. The native payload remains dominant; no further size saving is claimed from removing functional components.

Both paired APKs and the baseline pass APK v2 signature verification with the same existing benchmark/debug certificate SHA-256: `519b8d698e2eb16e41c3064db79a65f3fdd9f6df36b79bc4b9669c2fad19bda2`. The final manifest retains `com.aliflix.app`, versionCode 245 / versionName 3.1.155, target SDK 37 and all four ABIs. Production signing configuration and updater files are unchanged; these checks concern the validation artifact, not a production release.

- Before cleanup SHA-256: `e9a74b9c28c84ab00ba56948d2a88cc5656760bc06bb5e29b39b68aea5b08713`
- After cleanup SHA-256: `25a083bd96bfecedbce3f2a6b3193080db9ad205c59214c476a8d8fd963a5f64`

Reproduction evidence is stored locally in ignored `.validation/apk-audit/`: paired APKs, dependency-origin checks, source fingerprints and `cleanup-comparison.json`. The reusable tracked comparison script and exact exclusion allowlist accompany this change.

## Evidence boundaries

The initial host run completed `:app:testMobileDebugUnitTest`, `:app:lintMobileDebug` and `:app:assembleMobileBenchmark` successfully. There are 593 tests: 590 passed, zero failures/errors and three existing environment-dependent skips (`CineJoyLivePlaybackTest`, native PCM/VAD capture and native Silero ONNX). Mobile lint reports zero errors, 110 warnings and four hints. Warnings are not represented as a clean lint report.

Focused suites cover 14 title-search cases, six genre grouping cases, two storage-input/quota cases, 18 automatic-subtitle cases, two cancellable HTTP cases, two persisted/manual settings cases, five effect lifecycle cases and 13 narrative-affinity cases. These are host checks; they do not prove rendered UI, actual provider availability, production SubDL responses or device audio gain. No emulator or connected-device test is run, per the user's instruction. GitHub publication is authorized separately after review; its workflow must skip the playback-device job and retain unit/lint/signing gates. Physical amplification, audible distortion, Bluetooth/headphone/speaker behavior, rendered UI and device playback remain unverified. The validation APK uses the existing benchmark signing configuration and is not a published production update.

## Release review gate

The reviewed v3.1.156 / versionCode 246 run passed mobile unit tests, mobile lint, mobile debug assembly and minified mobile benchmark assembly. The suite contains 595 tests: 592 passed, zero failures/errors and three existing environment-dependent skips. The added regressions cover wrong-media-type correction suppression and stalled source files preserving the SubDL fallback budget. Workflow Bash blocks pass syntax checks; both device-waiver conditions include v3.1.156. No emulator/device test is run.
