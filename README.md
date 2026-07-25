# Aliflix

Aliflix is a personal, phone-first Android catalogue with a native Kotlin and
Jetpack Compose interface. Home, search, title details, My List, recently
played, and recommendations are native. A WebView is created only after Play
is selected.

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
- Movies and shows can use either Ramoflix or 67 Movies. The default can be
  changed in My Space on both phone and TV.
- Japanese anime is classified separately and always uses Miruro. Aliflix
  resolves the title to its AniList ID before opening Miruro.
- The 67 Movies option opens its Vidlove player with the exact TMDB movie ID or
  TV season and episode, avoiding the outer website around the player.
- Ramoflix's base URL can still be edited in the phone app if its domain changes.
- Top-level navigation is restricted to the selected provider and confirmed
  playback hosts using exact, boundary-safe host checks.
- New windows and pop-ups are rejected.
- Third-party iframe resources required by a selected provider may load inside
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

## GitHub updates for phone and TV

Both builds are preconfigured to read updates from this repository:

- Phone: `update-mobile.json`
- TV: `update-tv.json`

The workflow at `.github/workflows/tv-release.yml` builds and publishes both
signed APKs and both manifests. Aliflix verifies the APK SHA-256 before opening
Android's installer.

Set up GitHub once:

1. Create one release keystore and keep it backed up. Every future update must
   use this same keystore.

```powershell
keytool -genkeypair -v -keystore aliflix-release.jks -alias aliflix -keyalg RSA -keysize 2048 -validity 10000
```

2. In the GitHub repository, open **Settings → Secrets and variables → Actions**
   and add:

   - `ALIFLIX_KEYSTORE_BASE64`
   - `ALIFLIX_KEYSTORE_PASSWORD`
   - `ALIFLIX_KEY_ALIAS`
   - `ALIFLIX_KEY_PASSWORD`

   On Windows, copy the keystore value with:

```powershell
[Convert]::ToBase64String(
  [IO.File]::ReadAllBytes("C:\path\to\aliflix-release.jks")
) | Set-Clipboard
```

3. Push the source to `main`. In GitHub, open **Actions → Publish Aliflix
   Android and TV → Run workflow**, enter a new tag such as `v2.1`, and add
   release notes.
4. For every later release, increase `versionCode` (and normally
   `versionName`) in `app/build.gradle.kts`, push, and run the workflow again.

The workflow uploads:

- `aliflix-mobile-release.apk`
- `aliflix-tv-release.apk`
- `update-mobile.json`
- `update-tv.json`

Install the first release APK manually. After that, **My Space → Check for
updates** downloads later releases. Android may ask once for permission to
install updates from Aliflix. A debug APK cannot update a release APK signed
with a different key; uninstall the debug build before installing the first
release if Android reports a signature conflict.

## Native app structure

- Home: cinematic hero, native category filters, content rails, and recently
  played.
- Search: native search across the refreshed TMDB catalogue in a
  native poster grid.
- Details: native metadata, My List, Play, cast, genres, and recommendations.
- My Space: playback-source choice, GitHub update controls, My List, favorites,
  and recently played titles.
- Player: isolated fullscreen WebView with strict top-level navigation and
  pop-up blocking.
