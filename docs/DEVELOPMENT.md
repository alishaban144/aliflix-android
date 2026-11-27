# Development

## Android

Install JDK 17, Android SDK Platform 37 (SDK Manager package `platforms;android-37.0`), Build Tools 36.0.0 or later, and Platform Tools. Use Android Studio or set the SDK path in `local.properties`. Keep the Gradle wrapper as the source of the Gradle version.

| Task | Gradle command |
| --- | --- |
| Compile and package | `:app:assembleMobileDebug` |
| JVM tests | `:app:testMobileDebugUnitTest` |
| Single JVM suite | `:app:testMobileDebugUnitTest --tests 'com.aliflix.app.player.NativePlaybackRequestTest'` |
| Android static analysis | `:app:lintMobileDebug` |
| Compile device tests | `:app:assembleMobileDebugAndroidTest` |
| Run on connected device | `:app:connectedMobileDebugAndroidTest` |

Use `bash gradlew` on Unix or `.\gradlew.bat` on Windows. The [benchmark](../benchmark/README.md) has separate setup and device requirements. Reports go under the module's `build/` directory.

For a debug install on a device that already has the signed production app, supply `-Paliflix.testApplicationIdSuffix=.validation` and put a Firebase client that matches `com.aliflix.app.validation` in the ignored `app/src/debug/google-services.json`. Use the same suffix when building the test APK. Remove or relocate that local Firebase override before building the default debug package if it lacks a client entry for `com.aliflix.app`. For local Worker fixtures, debug builds also accept `-Paliflix.testRecommendationBaseUrl=<url>`.

Phone CI uses [the emulator harness](../.github/scripts/test-phone-casting.sh). Many device tests need real streams, Cast, accounts, or voice input. Treat JVM tests and device compilation as separate evidence from end-to-end playback.

## Recommendation Worker

Use Node.js 24 and run `npm ci`, `npm run typecheck` and `npm test` from `recommendation-worker/`. The test configuration is `wrangler.test.jsonc`. Regenerate binding types with `npm run types` when changing the bindings. See `src/types.ts`, `src/talkProviders.ts`, and `wrangler.jsonc` for the current capability and credential requirements. Keep real credentials in local ignored files or deployment secrets.

## Repository checks

[Repository checks](../.github/workflows/checks.yml) runs mobile build, unit tests, Android lint, Worker typecheck and tests, and a whitespace check on changed lines. Editor settings live in [.editorconfig](../.editorconfig). Prefer formatting code as you edit it. Place experiments and temporary screenshots under `.local/` or the ignored output folder used by the test harness.
