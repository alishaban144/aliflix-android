# Aliflix

[![Latest release](https://img.shields.io/github/v/release/alishaban144/aliflix-android?display_name=release&sort=semver)](https://github.com/alishaban144/aliflix-android/releases/latest)
[![Android release](https://github.com/alishaban144/aliflix-android/actions/workflows/mobile-release.yml/badge.svg)](https://github.com/alishaban144/aliflix-android/actions/workflows/mobile-release.yml)
[![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.3.21-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)

Aliflix is a native Android movie and TV discovery app built with Kotlin and Jetpack Compose. It combines a TMDB-backed catalogue, personal library features, native title details, configurable playback providers, and Ask Aliflix: a semantic recommendation experience powered by Gemini interpretation and authoritative TMDB metadata.

The current mobile release is **Aliflix 3.0.9** (`versionCode 89`) for Android 10 and newer.

[Download the latest mobile APK](https://github.com/alishaban144/aliflix-android/releases/latest/download/aliflix-mobile.apk) | [View release notes](https://github.com/alishaban144/aliflix-android/releases/latest)

> Aliflix does not host, upload, relay, or sell video content. Playback availability and content are controlled by the provider selected by the user.

## Highlights

- **Native mobile UI** with Home, Discover, title details, genres, and My Space.
- **No account required** for the published app; personal lists and playback preferences stay on the device.
- **Ask Aliflix v3** with Describe, Similar, and Filters modes for movies or series.
- **Grounded recommendations**: Gemini interprets intent while TMDB remains the authority for titles, types, posters, genres, years, runtime, countries, languages, and ratings.
- **Deterministic filtering** after metadata enrichment, including genre inclusion/exclusion, year, runtime, language, country, rating, title exclusions, and TMDB ID exclusions.
- **Canonical Similar mode** that uses the selected TMDB identity directly, excludes the anchor, and preserves the requested output type.
- **Useful result cards** with poster, title, year, genres, rating, and match tier.
- **Real pagination** backed by signed recommendation-session cursors.
- **Personal library** with favorites, My List, recently played titles, and playback settings.
- **Native details and navigation**; a WebView is created only after Play is selected.
- **In-app updates** with APK size and SHA-256 verification before installation.
- **Android TV source flavor** with a separate application ID and D-pad-oriented UI.

## Ask Aliflix architecture

```mermaid
flowchart LR
    UI[Jetpack Compose editor] --> VM[AliflixViewModel]
    VM --> Client[RecommendationAiClient]
    Client --> Worker[Cloudflare recommendation worker]
    Worker --> Gemini[Gemini intent and embeddings]
    Worker --> TMDB[TMDB titles and metadata]
    Worker --> Session[Durable Object session]
    Session --> Client
```

The active request path is:

```text
AskAliflixScreen
  -> AliflixViewModel.submitAskAliflix
  -> RecommendationAiClient
  -> POST /v3/recommendations
  -> recommendation-worker
```

The worker uses Gemini for semantic interpretation and relevance signals, then retrieves and validates real TMDB titles. Explicit UI filters override interpreted filters. Candidates are deduplicated and pruned before bounded metadata enrichment, then hard filters are applied deterministically. Missing metadata cannot satisfy a filter that requires it.

Recommendation sessions are stored in a Cloudflare Durable Object. Subsequent pages use signed cursors tied to the request and session instead of fabricated offsets or client-side slicing.

## App sections

### Home

- Cinematic hero and native content rails.
- For You, Movies, TV, and New filters.
- Taste-based picks and recently played titles.
- Loading, empty, retry, and offline-friendly fallback states.

### Discover

- Predictive movie and TV search.
- Genre and catalogue discovery.
- Ask Aliflix entry point with native Compose animations.
- Search ranking that handles partial titles, punctuation, years, and type qualifiers.

### Ask Aliflix

- **Describe**: enter the kind of story, theme, tone, or viewing mood you want.
- **Similar**: select a canonical TMDB title and request movie or series recommendations.
- **Filters**: combine supported TMDB genres, year, runtime, language, country, and rating constraints.
- Edit filter and Similar context directly from the result bar, or start a New search while preserving the current mode and Movies/Series selection.

### Details and playback

- Native poster/backdrop, metadata, genres, ratings, cast, and related titles.
- My List and favorite actions.
- Configurable playback provider.
- Fullscreen WebView isolated to the selected playback flow.
- Exact, boundary-safe top-level host checks and blocked pop-ups/new windows.

### My Space

- Favorites and My List.
- Recently played titles.
- Playback-provider settings.
- Ask Aliflix visibility setting.
- In-app update checks.

## Requirements

### Mobile app

- Android Studio with JDK 17.
- Android SDK Platform 37.
- Android SDK Build-Tools 36.0.0 or newer.
- Android SDK Platform-Tools for `adb` installation.
- Android 10 / API 29 or newer on the device.

The project currently uses Android Gradle Plugin 9.3.0, Kotlin 2.3.21, and Gradle 9.5.0 through the checked-in wrapper.

### Recommendation worker

- Node.js 24.
- npm.
- A Cloudflare account for deployment.
- TMDB and Gemini credentials for a separately deployed worker.

Users of the published APK do not need to provide TMDB, Gemini, or Cloudflare credentials.

## Local Android setup

Clone the repository:

```powershell
git clone https://github.com/alishaban144/aliflix-android.git
cd aliflix-android
```

Copy the SDK template:

```powershell
Copy-Item local.properties.example local.properties
```

Set the Android SDK path in `local.properties`:

```properties
sdk.dir=C\:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

`local.properties`, keystores, passwords, and API credentials must not be committed.

## Build, test, and install the mobile app

Windows PowerShell:

```powershell
.\gradlew.bat testMobileDebugUnitTest
.\gradlew.bat lintMobileDebug
.\gradlew.bat assembleMobileDebug
adb install -r .\app\build\outputs\apk\mobile\debug\app-mobile-debug.apk
```

macOS or Linux:

```bash
./gradlew testMobileDebugUnitTest
./gradlew lintMobileDebug
./gradlew assembleMobileDebug
adb install -r app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
```

The debug APK is written to:

```text
app/build/outputs/apk/mobile/debug/app-mobile-debug.apk
```

## Android TV flavor

The TV flavor uses application ID `com.aliflix.app.tv`, requires Android TV 11 / API 30 or newer, and can coexist with the mobile app.

```powershell
.\gradlew.bat testTvDebugUnitTest
.\gradlew.bat lintTvDebug
.\gradlew.bat assembleTvDebug
adb install -r .\app\build\outputs\apk\tv\debug\app-tv-debug.apk
```

The current GitHub release workflow publishes the **mobile APK only**. The TV flavor remains buildable from source.

## Recommendation worker development

Install dependencies and run the checks:

```powershell
cd recommendation-worker
npm ci
npm run typecheck
npm test
```

Useful commands:

```powershell
npm run types
npm run dry-run
npm run deploy
```

The worker expects these Cloudflare secrets:

- `GEMINI_API_KEY`
- `TMDB_API_KEY` or `TMDB_READ_ACCESS_TOKEN`
- `CURSOR_SIGNING_SECRET`

Set them without writing secrets into the repository:

```powershell
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put TMDB_READ_ACCESS_TOKEN
npx wrangler secret put CURSOR_SIGNING_SECRET
```

The worker includes:

- Zod request validation.
- TMDB request authentication, retry, timeout, and bounded-call handling.
- Gemini intent interpretation and semantic embeddings.
- Deterministic hard-filter enforcement.
- Deduplication and relevance ranking.
- Durable Object recommendation sessions.
- HMAC-signed pagination cursors.
- Rate limiting and structured service errors.
- Vitest coverage for ranking, filters, failures, pagination, canonical anchors, and recommendation regressions.

## Repository structure

```text
.
|-- app/                         Android mobile and TV application
|   `-- src/main/java/com/aliflix/app/
|       |-- data/                Catalogue, metadata, search, cache, and update data
|       |-- player/              Playback navigation and WebView policy
|       |-- recommendation/      Android v3 recommendation client and mapping
|       |-- ui/                  Mobile and TV Compose interfaces
|       `-- update/              Update manifest and installer flow
|-- benchmark/                   Android benchmark module
|-- recommendation-worker/      Cloudflare Worker, Durable Object, and tests
|-- .github/workflows/           CI, signed release, and worker deployment
|-- gradle/                      Gradle wrapper configuration
`-- scripts/                     Repository maintenance utilities
```

## Security and release integrity

- Global Android cleartext networking is disabled.
- Production release tasks fail when release-keystore configuration is missing; they never fall back to debug signing.
- CI validates the release keystore certificate SHA-256 before building.
- CI verifies the final APK signer certificate SHA-256 with `apksigner`.
- The update manifest records the APK URL, version, byte size, and SHA-256.
- Aliflix verifies downloaded update metadata before launching Android's installer.
- Generated dependency and Gradle-agent caches are excluded from Git.

## Publishing a mobile release

The workflow is `.github/workflows/mobile-release.yml`. It type-checks and tests the recommendation worker, deploys it when Cloudflare credentials are configured, runs Android mobile unit tests, creates a production-signed APK, verifies the APK signer, generates `update-mobile.json`, and publishes both files to a GitHub Release.

Required GitHub Actions secrets:

- `ALIFLIX_KEYSTORE_BASE64`
- `ALIFLIX_KEYSTORE_PASSWORD`
- `ALIFLIX_KEY_ALIAS`
- `ALIFLIX_KEY_PASSWORD`
- `CLOUDFLARE_API_TOKEN` for worker deployment

For a new release:

1. Increase `mobileVersionCode` and `mobileVersionName` in `app/build.gradle.kts`.
2. Update the matching release version values and release branch in `.github/workflows/mobile-release.yml`.
3. Run the Android and worker checks locally.
4. Commit and push the release source.
5. Create and push the matching version tag, such as `v3.0.7`.
6. Wait for every workflow job to pass before treating the release as published.

Published assets:

- `aliflix-mobile.apk`
- `update-mobile.json`

The app reads the latest mobile update manifest from:

```text
https://github.com/alishaban144/aliflix-android/releases/latest/download/update-mobile.json
```

## Playback boundary and content responsibility

- Aliflix provides a native catalogue and navigation experience but does not host media.
- The selected provider controls the playback page and content availability.
- Aliflix does not extract, proxy, relay, or store third-party media URLs.
- New windows and pop-ups are rejected by the app's playback policy.
- Top-level WebView navigation is restricted to the configured provider and approved playback hosts.
- Provider domains can change or become unavailable independently of this project.
- Users are responsible for complying with the laws and service terms that apply in their location.

## Current release

- Version: **3.0.7**
- Version code: **87**
- Minimum Android version: **Android 10 / API 29**
- Release page: [Aliflix 3.0.7](https://github.com/alishaban144/aliflix-android/releases/tag/v3.0.7)
- Direct APK: [aliflix-mobile.apk](https://github.com/alishaban144/aliflix-android/releases/download/v3.0.7/aliflix-mobile.apk)
