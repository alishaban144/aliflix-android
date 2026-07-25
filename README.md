# Aliflix

Aliflix is a personal, phone-first Android catalogue with a native Kotlin and
Jetpack Compose interface. Home, search, title details, My List, recently
played, and recommendations are native. Only the dedicated playback surface
uses a WebView to load the matching Ramoflix item or watch page.

The project also includes a separate Android TV flavor for Android 11 and
newer. It uses the same catalogue and library code behind a landscape 10-foot
interface designed for D-pad, OK, Back, TV keyboard, and remote media keys.

No account, TMDB token, or other API credential is required. Aliflix reads the
public catalogue cards and presents them in its own native interface. A small built-in
catalogue keeps the app usable if catalogue pages are temporarily unavailable.

## Playback boundary

- The WebView has no address bar or browser controls and sits inside a
  fullscreen native black playback surface.
- A native Cast button opens Android's Cast screen picker for device mirroring
  without extracting or relaying third-party video URLs.
- Ramoflix is the only playback website. Its base URL can be edited in the app
  in case the domain changes.
- Movies and episodes first resolve the matching Ramoflix item, then TV playback
  selects the requested season and episode on that page.
- Top-level navigation is restricted to Ramoflix (or its edited replacement
  domain), known playback hosts, and their real subdomains.
- New windows and pop-ups are rejected.
- Third-party iframe resources required by the Ramoflix player may load inside
  the approved watch page, but cannot take over the top-level WebView.
- Aliflix does not extract, relay, or store third-party video URLs.
- Cookies persist normally. The active WebView is retained only for the current
  Aliflix process and is destroyed when the activity closes.
- Exact playback progress is not available across the cross-origin boundary,
  so Aliflix presents a Recently Played list without a progress bar.

## One-time Windows setup

1. Install Android Studio and use its SDK Manager to install:
   - Android SDK Platform 37
   - Android SDK Build-Tools 36.0.0 or newer
   - Android SDK Platform-Tools
   - Android SDK Command-line Tools
2. Enable Developer options and USB debugging on the Android phone.
3. Install the phone manufacturer's Windows USB driver if `adb devices` does
   not list the device.
4. Copy `local.properties.example` to `local.properties`, then set:

```properties
sdk.dir=C\:/Users/YOUR_NAME/AppData/Local/Android/Sdk
```

`local.properties` is ignored by Git and must never be committed.

## Build and install from Codex

From the `aliflix-android` directory:

```powershell
.\gradlew.bat testMobileDebugUnitTest
.\gradlew.bat lintMobileDebug
.\gradlew.bat assembleMobileDebug
adb devices
adb install -r .\app\build\outputs\apk\mobile\debug\app-mobile-debug.apk
```

The phone app requires Android 10 or newer. Internet access is required to
refresh the public TMDB catalogue pages, load poster artwork, and play a title.

## Android TV (Android 11+)

The TV flavor has its own application ID (`com.aliflix.app.tv`), launcher
banner, Leanback launcher entry, landscape interface, and visible focus states,
so it can coexist with the phone build.

```powershell
.\gradlew.bat testTvDebugUnitTest
.\gradlew.bat lintTvDebug
.\gradlew.bat assembleTvDebug
adb install -r .\app\build\outputs\apk\tv\debug\app-tv-debug.apk
```

The TV APK supports Android TV 11 / API 30 and newer. Search opens the TV's
system keyboard, including voice entry when the device keyboard provides it.
Playback accepts D-pad navigation, Back, and Play/Pause remote keys.

## Native app structure

- Home: cinematic hero, native category filters, content rails, and recently
  played.
- Search: native search across the refreshed TMDB catalogue in a
  native poster grid.
- Details: native metadata, My List, Play, cast, genres, and recommendations.
- My Space: My List plus recently played titles.
- Player: isolated fullscreen WebView with strict top-level navigation and
  pop-up blocking.
