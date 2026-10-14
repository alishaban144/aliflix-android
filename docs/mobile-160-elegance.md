# Mobile v3.1.160 elegance and launch performance

Base: GitHub release v3.1.159, eb94abca3a5677bc73bf29f663b08634f493c626.

Inspected the actual Component.tsx in 21st's Animated Dock by Preet Suthar, using one free source view through the normal UI:
https://21st.dev/@preetsuthar17/components/animated-dock
- Container tracks mouseX. Each item computes pointer distance to its measured center.
- Distance [-150, 0, 150] maps width [40, 80, 40]; spring mass 0.1, stiffness 150, damping 12.
- A second spring scales the icon from 1 to 1.5.
- Native adaptation keeps 48dp hit targets fixed, uses the same distance falloff and normalized spring, restrained 12% tile/8% glyph magnification and 3dp lift, responds to press/focus/mouse, observes the initial pointer pass without consuming events. Narrow layouts wrap actions. No shared pill container.

Inspected Aceternity's editable source, including both path definitions and the spring/SVG rendering code:
https://ui.aceternity.com/labs/svg-path-morphing
- Both halves have matching M L L L Z structures; two vertices collapse onto the play apex.
- Source pause bars span x5..9 and x15..19, y5..19. Play split spans x7..19, midpoint x13.
- Spring stiffness 260, damping 26, mass 0.9; source also adds 1.5 stroke to already filled paths.
- Native adaptation uses two interpolated four-corner paths, narrower 2-unit pause bars and a 10-unit play triangle, no added stroke, normalized stiffness 289 and damping ratio .85. Playback state owns the glyph and accessibility label.

Additional primary design/performance sources reviewed:
https://m3.material.io/styles/motion
https://developer.android.com/develop/ui/compose/performance/phases
https://developer.android.com/topic/performance/issues/launch-time
https://developer.apple.com/documentation/technologyoverviews/liquid-glass
https://developer-rno.apple.com/videos/play/wwdc2026/102/

Use motion for state continuity and restrained hierarchy; keep text readable, backgrounds coherent, targets stable, and Android reduced-motion preference respected. Launch clock reads occur inside draw/layer blocks or snapshotFlow milestone observers. The expensive animated text blur and software toggle alpha-mask rasterization were removed.


## Final behavior

- The launch clock remains Compose State until graphics-layer/draw consumption. Snapshot observers react only to milestones; the wordmark is measured once and the animated blur is removed.
- The moving sequence keeps its 3.05-second timing. Home composes during the final static hold, and the 300ms fade begins after two frame boundaries with usable Home. Cached content is usable even while its background refresh runs. Reduced motion, background/resume, late readiness and one-shot completion remain supported.
- Describe and Similar share a compact horizontal result card. Bookmark and detail actions have independent 48dp targets; long titles/genres truncate within bounded text regions, and scores keep their own row.
- My Space collection navigation uses content-sized text/count tabs, a restrained underline, and horizontal scrolling at large fonts. Genre navigation shares the page background, supports long labels and retains section scrolling.
- Ask Aliflix reuses the page-wide passive touch observer and native Live Orb. It observes scrolling and child actions without consuming their gestures.
- Switches use one native animated thumb; the white droplet and software blur/readback mask are removed.
- Player actions use individual translucent asymmetric tiles, the inspected dock falloff/spring, stable 48dp targets, keyboard focus/press/mouse feedback, and wrapping based on actual available width. The play/pause icon morphs two compatible paths with narrower pause bars and no added stroke. Center-control spacing also adapts to 240dp widths.

## Verification

Local full gate passed: 622 mobile unit tests, zero failures/errors and three existing skipped tests; mobile lint, mobile debug APK, instrumentation APK and TV debug assembly succeeded. Existing lint warnings remain; no new runtime dependencies, TV source/resources/version changes, or Worker changes were made.

A physical Pixel 7a passed all 21 focused UI regressions against the final app source: card bounds at 240/280/320/400dp and fonts 1/1.5/2; long genres; score layout; bookmarks/detail callbacks; LTR/RTL toggle pixels, hoisted and disabled state; passive drag; Home pinning and scroll preservation; launch readiness/draw/fade timing and recomposition; download tier persistence; sync badge layout; player identity, dock callbacks and 240dp playback geometry. The launch composition fixture also accounts for CI's reduced-motion setting.

Actual app screens were inspected on that phone: My Space, Ask Aliflix's touch-aware header, and a live Describe request returning 23+ matches (including Interstellar, Tenet and Looper). Screenshots include real posters and the compact card layout. This checks UI/service connectivity, not recommendation-score accuracy. Both Describe and Similar use the same result-card implementation.

The validation application ID keeps the phone's installed Aliflix data intact. The installed main app has a different signing key from the official release; physical test-build evidence and production signed-APK evidence are separate.

Moving-intro frame samples on this Pixel (three fresh-process runs per build, equal ahead-of-time compilation):

| Build | Frames sampled | Frames over 16.7ms | Per-run p95 | Worst frame |
| --- | ---: | ---: | --- | ---: |
| v3.1.159 test build | 457 | 118 | 46.87 / 55.37 / 53.82ms | 418.35ms |
| v3.1.160 test build | 747 | 5 | 6.04 / 6.07 / 6.09ms | 29.39ms |

See [machine-readable samples and measurement boundaries](mobile-160-launch-frames.json). The sample window is 0.2-3.0 seconds from the first window frame; it measures the moving intro, excluding first activity draw and Home preparation in the static hold. It is one-device test-build evidence, not a zero-jank guarantee on all phones.

Release workflow gates remain enabled for mobile device regressions, full unit tests/lint, minification/build, production signer, APK integrity, updater size/hash and GitHub publication. Public artifacts are downloaded and independently checked after publication.
