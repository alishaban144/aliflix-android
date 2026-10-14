# Mobile v3.1.161 UI correction

Base: latest published mobile release v3.1.160, b847064b0d9e6491835a8a553b4c5c8fcea55ff5.

The v3.1.160 redesign changed the wrong My Space navigation and gave the player toolbar filled tiles with magnification and lift. This correction restores the original collection navigation and removes those toolbar treatments.

- My List, Favorites, History and Downloads use the original segmented surface, selected treatment, stacked label/count, spacing and typography. Only My List's genre navigation keeps its redesign.
- The player back button returns to the preceding release's 48dp circular control and standard back arrow, matching the preparing player.
- Episodes, captions, quality, rotation and More use custom line geometry on a shared 24-unit optical grid. There are no action backgrounds, borders, tile silhouettes or enclosing card. Hit areas remain 48dp.
- Portrait puts More beside the title and aligns the secondary actions beneath the title. Landscape keeps the actions on the identity row. Narrow widths wrap the secondary actions. Short viewports limit title lines to leave the transport clear.
- Upper actions have no dock observer, magnification, lift, animated press scale or entry slide. The overlay retains its visibility fade. The slim play/pause state morph remains.
- Launch, Ask Aliflix, toggle and quality migration fixes from v3.1.160 are retained. TV behavior, resources and version are unchanged; no dependencies were added.

## Validation

`PlayerHeaderVisualTest` renders the production `NativePlayerScreen` over a real Media3 `PlayerView` decoding the bundled local MP4. It captures full portrait and landscape windows for movie and long series identities at 100% and 200% font size. Assertions check 48dp targets, pairwise action separation, title/transport separation and visible episode identity. A separate pixel test rejects card pixels around every action and verifies fixed bounds during a held press.

The existing mobile tests cover 240/320/700dp header constraints, long genres, restored collection selection, toggle states, Home pinning, quality mapping/migration and launch timing. The release device suite includes the new full-screen and pixel regressions.

On the connected physical Pixel 7a, all 23 focused instrumentation tests passed, including the eight full-player renders and held-press pixel checks. All eight full-window captures and the actual My Space composable were visually inspected. The decoded local video is a diagnostic test pattern; this validates the production overlay layout, not a remote provider or a movie stream.

Local validation: 622 unit tests, zero failures, three skipped; mobile debug APK and instrumentation APK assembly; mobile lint; TV debug assembly. The final cached gate also checks the final portrait alignment. The GitHub release runs its full device suite, unit tests, lint and signed release build with no test waiver.

Test APKs use the separate `com.aliflix.app.validation` package to preserve the installed app and its data. Physical test-build rendering and production signed-artifact verification are distinct checks.

Representative physical-device captures:

- [Portrait movie player](images/mobile-161/full-player-portrait-movie-font1.0.jpg)
- [Landscape series player](images/mobile-161/full-player-landscape-series-font1.0.jpg)
- [Landscape series at 200% font size](images/mobile-161/full-player-landscape-series-font2.0.jpg)
- [Restored My Space collection tabs](images/mobile-161/my-space-restored-tabs.jpg)
