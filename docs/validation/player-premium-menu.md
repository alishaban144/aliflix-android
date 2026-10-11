# Mobile player menu validation

12 October 2026. Baseline 834f366, mobile 3.1.157.
Branch: codex/player-premium-sync-plan.

Historical checkpoint for the first menu-only patch. Later sync, controls and
release evidence supersedes its remaining-work statements and is recorded in
[mobile-v3.1.158-player.md](mobile-v3.1.158-player.md).

## Changes

- A compact More bottom sheet with a single Streaming source entry.
- Source → server navigation, nested Back, current selection, empty/error/loading/retry states.
- Existing source switching callbacks preserved; choosing the current server is a no-op.
- Audio Boost moved from Settings to More with the existing persistent preference and audio effect.
- Labels, current values and short statuses only. Descriptive sync and menu copy removed.

## Host results

The normal package passed:
- :app:testMobileDebugUnitTest
- :app:lintMobileDebug
- :app:assembleMobileDebug
- :app:assembleMobileDebugAndroidTest

Final normal gate: BUILD SUCCESSFUL in 3m 40s.
596 unit tests: 593 passed, three existing skips, zero failures/errors.
Lint: zero errors, 110 warnings.
git diff --check passed.
TV source, Worker source and dependency configuration have no changes.

## Physical-device results

Pixel 7a, serial ending 02777, Android 17.
An isolated debug package com.aliflix.app.playerqa and matching test APK were installed.
The existing com.aliflix.app 3.1.155 / versionCode 245 app was retained.

PlayerMoreMenuTest passed all three tests in portrait and landscape:
1. Browsing does not switch playback; Android Back returns a level; reopening starts at More; a server choice switches once.
2. Choosing the current server does not restart playback.
3. Audio Boost follows its preference state and unavailable/active status.

Portrait: OK (3 tests), 6.55s.
Landscape: OK (3 tests), 6.527s.
The phone's original rotation setting was restored.

Full-display screenshots were inspected:
- .validation/portrait-player-more-home.png
- .validation/portrait-player-more-sources.png
- .validation/landscape-player-more-home.png

These menu tests use fixture providers/server callbacks and simulated Audio Boost availability.
They establish physical menu rendering, scrolling, Android Back, no-op selection and toggle semantics.
They do not establish live provider switching, audible gain on the current output, or subtitle-sync accuracy.

## Remaining reliability evidence

The user identified The Terminator with English subtitles.
No failing-first/successful-retry playback scene was reproduced in this change.
The scheduler queue/timeout issue is established by code inspection; its role in the user's specific attempt remains unconfirmed.
Sync acceptance, deadline, upload behavior and native libraries are unchanged.
The full next-step plan is in ../player-reliability-and-premium-plan.md.
No release or Worker deployment was performed.
