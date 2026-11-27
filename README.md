# Aliflix

Aliflix is an Android phone app for discovering movies and series, playing and downloading supported streams, and keeping a personal library. Ask Aliflix handles recommendations. Talk supports voice conversations in Discover and with scene context in the player.

[Download the mobile APK](https://github.com/alishaban144/aliflix-android/releases/latest/download/aliflix-mobile.apk) · [Latest release notes](https://github.com/alishaban144/aliflix-android/releases/latest)

The published app requires Android 10 or later on an ARM64 phone. Build from source with JDK 17, Android SDK Platform 37, Build Tools 36.0.0 or later, and the checked-in Gradle wrapper. Android Studio can set the SDK location, or you can copy `local.properties.example` to `local.properties` and set `sdk.dir`.

```powershell
.\scripts\verify-build.ps1
```

```bash
bash gradlew :app:testMobileDebugUnitTest :app:lintMobileDebug :app:assembleMobileDebug
```

The APK is written to `app/build/outputs/apk/mobile/debug/app-mobile-debug.apk`. The installed package is `com.aliflix.app`. See the development guide for a side-by-side debug install on a phone that already has the production app.

For development, start with the [code map](docs/ARCHITECTURE.md), then use the [development guide](docs/DEVELOPMENT.md) or the [release guide](docs/RELEASING.md). The [documentation index](docs/README.md) points to feature references and historical validation.

Use playback sources you have permission to access. Source availability and playback capabilities vary by provider.
