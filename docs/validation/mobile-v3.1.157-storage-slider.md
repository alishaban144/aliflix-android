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

No emulator or physical-device testing was performed, as requested. Rendered UI and physical interaction are unverified. Public release, updater and signature verification will be recorded after publication.
