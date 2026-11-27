# Mobile v3.1.157 storage slider

Settings → Downloads → Storage limit is again adjustable directly on the page. The native Material 3 Slider uses a 4 dp rounded Aliflix accent track and a 16 dp thumb that animates to 20 dp during interaction, while retaining a 48 dp minimum touch target. The value badge updates in whole gigabytes from 1 to 200, and tapping it opens the existing preset/custom editor with Save/Cancel.

The implementation follows [Android's Compose Slider customization guidance](https://developer.android.com/develop/ui/compose/components/slider). Native slider gestures, keyboard/accessibility progress actions and integer steps remain intact; the custom track mirrors in RTL. Changes persist to the existing `offline-downloads` / `limitGb` preference when an adjustment finishes. Preference changes refresh the displayed value. Actual storage usage and below-usage warnings remain visible; quota enforcement and saved downloads are unchanged. No instructional descriptions were added.

## Host validation

- `testMobileDebugUnitTest`, `lintMobileDebug`, `assembleMobileDebug` and `assembleMobileDebugAndroidTest` passed together in 5m 50s.
- 596 unit tests: 593 passed, three existing skips, zero failures/errors.
- Lint has zero errors, 111 warnings and four hints.
- Added a unit regression for whole-GB slider limits, retaining custom-value and below-usage coverage.
- Added compiled UI checks for a 48 dp touch target, accessibility quota adjustment, exact-editor access, external preference updates and RTL bounds/warnings. These UI checks were not executed.
- All nine release-workflow Bash blocks passed syntax checks. Both workflow conditions allow the existing device-test waiver.
- Compared against the published v3.1.156 baseline. TV, Worker, dependencies, native libraries, ABIs and APK cleanup configuration are unchanged. No TV build/release or Worker deployment is part of this change.

## Evidence limits

No emulator or physical-device testing was performed, as requested. Rendered UI and physical interaction are unverified.

## Published release

Released mobile v3.1.157 / versionCode 247 from `b29ba93389a84e58a18ddc9b7c8b9521d1db1553`.

- [Release](https://github.com/alishaban144/aliflix-android/releases/tag/v3.1.157)
- [Successful workflow 38083904134](https://github.com/alishaban144/aliflix-android/actions/runs/38083904134)
- [APK](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.157/aliflix-mobile.apk)
- [Updater manifest](https://github.com/alishaban144/aliflix-android/releases/download/v3.1.157/update-mobile.json)

CI passed the full mobile unit-test/lint/release build step, signing, integrity and publication. Device testing and Worker checks/deployment were skipped. The public tag points to the validated release commit.

The independently downloaded APK is 66,171,961 bytes, SHA-256 `85886c8d4d0a929ab2922ec56cb1f6f9e83e507c715e4dfacde52afdb217fbc7`. APK v2 signature and ZIP integrity pass; the production signer remains `a71ef174c31385c19f260027161ec255ad272ddbd7df1241a27029e8676f3a47`. Package `com.aliflix.app` reports versionCode 247 and versionName 3.1.157. GitHub asset size/digest agree with the downloaded file. Release and latest updater manifests are byte-identical, match its hash/size and point to v3.1.157.

All four supported ABIs, all 20 native libraries and every functional asset are byte-identical to the previous public v3.1.156 APK. The 74 verified-unused schema exclusions remain intact. Raw evidence is in ignored `.validation/release-v157/`.
