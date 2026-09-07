# Phone native player in 3.1.61

The phone Play action now opens a native preparation screen and a Media3 player. A temporary muted WebView resolves the stream, request headers and cookies, then is destroyed. Only the foreground media service owns ongoing decoding, progress, notification controls and external video output. TV-flavor code and version are unchanged.

## Preparation and controls

Moviepire's public embeds are tried in order: Vid, Mist, Mistify, Flix, Peach, followed by other discovered servers. Vid/Mist use their current documented-by-page embed routes; Mistify nests nextgencloudfabric.com; Flix has an idle-cover play action; Peach has an icon-only overlay. The preparation script targets these known controls and video elements only, with bounded retries. Challenges and unavailable sources fall through to another source; no CAPTCHA or access-control bypass is implemented.

The Aliflix dark/lilac player includes branded preparation, landscape playback, tap-to-reveal controls, double-tap seeking, 10-second controls, scrubbing/buffer position, quality, native audio and subtitles, subtitle search, speed, fit/fill, rotation, control lock, episode selection and next episode at completion. Playback readiness requires a decoded frame and supported selected audio for local output. The app does not require interacting with the original provider screen.

The reference review used current [HBO Max playback controls](https://help.hbomax.com/gb/Answer/Detail/000002510) and [Netflix audio/subtitle controls](https://help.netflix.com/en/node/372): landscape controls, central playback, ten-second seeking, remaining time and in-player audio/subtitle access. This implementation keeps Aliflix's own colors, typography and loading identity; it does not claim to duplicate every version of either service's proprietary UI.

[IntroDB](https://introdb.app/docs/api) is queried asynchronously at `/segments` using the series IMDb ID, season and episode. Valid intro/outro windows show only a Skip intro or Skip outro button, including when transport controls are hidden. Seeking goes to the marker's end, preserving any following post-credit scene. Wrong identity, missing data, malformed markers and markers outside the stream duration show no button. Read results are cached for 24 hours. IntroDB availability never gates playback.

## Casting ownership and platform limits

Cast opens Android wireless-display settings directly. Player options also offers Google Cast, which transfers playback to a compatible receiver and avoids dependence on phone-screen mirroring. This requires a Google Cast receiver; an Android 11 TV alone does not confirm receiver support. Android 17 requests LAN permission when opening this picker. Stop casting selects the system default route and restores the phone video surface without restarting the stream. The same session supplies notification play/pause.

Where Android permits a dedicated Activity on the external display, a lock-aware fullscreen TV window renders service-owned video. A service-owned Presentation supplies fallback output. Native surfaces are attached/detached synchronously on the application's main thread, before a SurfaceHolder destruction callback returns; asynchronous controller commands could leave a destroyed surface attached to the decoder and cause a renderer error. Only Surface references and owner tokens cross this boundary, never phone Activity references. Fallback output is recreated after a system display power cycle.

Partial CPU and Wi-Fi locks protect playback while needed, and are released on pause/stop. A TV window's keep-screen-on/show-when-locked flags apply to the TV, never to the phone keyguard. An app cannot force a system wireless route to remain connected or powered if Android/OEM casting policy shuts it down on phone lock. A shared virtual display was observed to power off with the phone during investigation. Independent output requires a route providing an external display; plain mirrored phone pixels cannot independently show a movie while another phone app is visible.

## Episode and rating loading

Phone show details start season/episode API requests immediately, independently of title enrichment and ratings. `/v3/tv/{id}/seasons/{season}` calls TMDB's season API once and is cached at the edge. The phone keeps season documents for six hours, can show stale rows while refreshing, preloads the last watched seasons for recent shows, and selects the last watched season automatically. IMDb/RT/OMDb enrichment runs independently and does not gate episode rows. TMDB votes are never labeled IMDb ratings.

## Verification

The local phone unit suite has 221 passing tests; Worker typecheck, 116 tests and deployment dry-run pass. The release workflow additionally gates publication on phone instrumentation, lint, signing, APK integrity, version and manifest checks.

`NativeBackgroundPlaybackTest` verifies actual changing decoded pixels, Settings background playback, phone power events, notification pause/play, Activity recreation/destruction, display restoration and Stop casting. A shared-display fixture tests recovery when Android powers down the display. An independent-display fixture uses AOSP system display flags solely during fixture creation; Android also powered this fixture off on lock. Video-while-locked assertions apply only while Android keeps the display powered, and a powered-off route is explicitly logged as unsupported. Playback and restoration assertions remain mandatory; playback runs with the normal app UID. This distinction must not be interpreted as proof of an OEM wireless route's power policy. `WebStreamHandoffTest` verifies real WebView video/HLS extraction. `NativeSkipControlsTest` verifies timed native buttons, seeking, post-credit preservation and control lock. Unit tests cover malformed/stale IntroDB data and provider ordering/routes.

Physical Nothing Phone 4a Pro/Android 16 and TV interoperability have not been tested. Emulator checks and selected audio-track verification do not establish physical-TV sound, uninterrupted OEM screen-lock behavior, or universal provider availability. The historical Android 17 log in `test-results/android17-casting.txt` belongs to 3.1.60, not this version.
