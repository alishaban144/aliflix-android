# Native casting in 3.1.60

The earlier casting service held wake/Wi-Fi locks and sent JavaScript commands to an Activity-owned WebView. It did not own a decoder or a TV playback session. Keeping browser timers running or overriding visibility could not establish independent playback ownership.

The Cast action now hands the current stream, playback position, required request headers, and selected subtitles to a Media3 player in `NativePlaybackService`. The service owns the decoder, media session, notification, and external-display Presentation. The Activity owns only controls and a disposable phone surface. Surface attachment is routed through the service so detaching a phone Activity cannot clear the TV's surface. Reopening the same stream reconnects controls to ongoing playback.

Google Cast transfers playback to the receiver. A session-scoped LAN relay preserves upstream headers, rewrites HLS master/variant/audio/key URLs, serves selected WebVTT subtitles, and supports range requests. Resource URLs use an unpredictable session token and registered resource IDs; clients cannot supply arbitrary upstream URLs. Browser cookies remain on the phone. The relay stops with the playback service.

Wireless-display receivers that expose an Android presentation display receive video on that display, with its own aspect-correct surface and subtitles. Plain phone-screen mirroring without a presentation display cannot show an independent video while the phone shows another app. For that hardware, select a Google Cast receiver using the video player's Cast button. The implementation does not use PiP, hidden-API visibility tricks, or automatic JavaScript replay loops.

## Verification

`NativeBackgroundPlaybackTest` uses a generated 90-second video with audio and changing pixels. On Android 15 it verifies actual decoded pixels on an ImageReader-backed presentation display, playback while the Activity is stopped and another app is open, persistent notification pause/play, Activity recreation, and playback after Activity destruction. It uses the app's own notification media-session token and normal app permissions. Adopting a privileged shell identity during the entire test interferes with Android audio-focus app-ops and is intentionally avoided.

`WebStreamHandoffTest` runs a real embedded HTML video in Android WebView and checks the playing frame's stream URL, frame origin, and duration. It also checks HLS manifest discovery for blob/MSE playback. `CastStreamRelayTest` exercises an HTTP upstream requiring headers, HLS master and variant rewriting, key retrieval, query preservation, byte ranges, CORS headers, and invalid session-token rejection. Request tests cover pause/position serialization and subtitle time offsets.

Run the device regression with:

```sh
./gradlew connectedMobileDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.aliflix.app.player.NativeBackgroundPlaybackTest,com.aliflix.app.player.WebStreamHandoffTest
```

The release workflow requires these emulator tests before publishing, together with Android unit tests, lint, a TV build, Worker checks, and signed-APK integrity checks.

The generated test asset was created with:

```sh
ffmpeg -f lavfi -i testsrc2=size=320x180:rate=15 -f lavfi -i sine=frequency=440:sample_rate=44100 -t 90 -c:v libx264 -preset ultrafast -crf 35 -pix_fmt yuv420p -c:a aac -b:a 32k -movflags +faststart cast-test.mp4
```

Physical phone/TV interoperability, Google Cast receiver execution, provider-specific expiring streams, and OEM wireless-display behavior require hardware validation. Emulator results are not a claim that every TV or provider has been tested.

Implementation references: [Android background playback](https://developer.android.com/media/media3/session/background-playback), [Media3 CastPlayer](https://developer.android.com/media/media3/cast/create-castplayer), and [Android Presentation](https://developer.android.com/reference/android/app/Presentation).
