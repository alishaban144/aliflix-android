# Ramoflix native playback — v3.1.73

Ramoflix now resolves its advertised movie and episode servers into the existing phone native player. The adapter checks the selected TMDB identity before accepting a result and uses Ramoflix's exact season/episode endpoint instead of delayed clicks on the site's season menus. It uses the configured Ramoflix domain and never substitutes Moviepire streams.

The shared resolver races two servers at a time, cleans up temporary WebViews, supports server selection and retries, and passes the original selection/resume identity to the existing player. Resolved server names populate the same server menu. Player controls, subtitle preparation, playback progress, and background service remain shared. TV is preserved at v3.1.69.

Validation:

- 244 Android unit tests passed, including four new Ramoflix regressions for title identity, movie/TV separation, exact episode routing, configured mirrors, search-link filtering, and native handoff identity.
- Final v3.1.73 Android unit tests, lint, mobile debug assembly, TV debug assembly and instrumentation assembly all passed. Log: `.validation/ramo-final-gate-host.log`.
- Android 17 live Ramoflix test passed for Fight Club (1999), Breaking Bad S1E1, and Breaking Bad S2E3. All three produced decoded video and selected audio, continued beyond 185 seconds after a seek to 180 seconds, and destroyed all resolver WebViews after native handoff. No interaction with provider pages was needed. Log: `.validation/ramo-live37.txt`; screenshots: `.validation/ramoflix-live-screens/`.
- All three live Ramoflix cases passed again on the final build with automatic subtitles enabled, including subtitle readiness before playback and a caption cue rendered at its timestamp. Two concurrent resolvers were observed, with none remaining after handoff. Log: `.validation/ramo-final-subtitles-live.txt`.
- Seven shared-player instrumentation tests passed on Android 17, including background/notification playback, surface recovery, stream handoff, exact progress, Cast subtitle metadata and caption rendering. Five Compose/Espresso UI tests hit the emulator/framework incompatibility `InputManager.getInstance` and must pass on the API35 release CI before publication.
- The API35 local emulator stopped twice without producing a result; these attempts are not counted as passes. Live verification used the Android 17 emulator.

Live tests sample representative titles and episodes; they do not establish availability of every title or every third-party server, or physical-device behavior.
