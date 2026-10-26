# HLS playback fixture

`playback-fixture.zip` contains 24 seconds of generated black video (160x90, H.264)
and a 440 Hz sine wave (48 kHz, AAC), split into two-second fMP4 HLS fragments.
No third-party programme media is included. Tests expose the same generated audio
under four distinct rendition URLs, like CineJoy's four unnamed audio tracks.

Generated with FFmpeg using lavfi `color=c=black:s=160x90:r=24` and
`sine=frequency=440:sample_rate=48000`, `-t 24`, `-c:v libx264 -preset ultrafast
-g 48 -sc_threshold 0`, `-c:a aac -b:a 24k`, and `-f hls -hls_time 2
-hls_playlist_type vod -hls_segment_type fmp4` for separate audio/video outputs.

CineJoyPlaybackIntegrationTest exercises Media3 loading, MP4 extraction, sample
consumption, seeks and track overrides on the JVM using fake renderers. It does
not validate Android hardware decoding or physical-device UI.

CineJoyLivePlaybackTest is opt-in via `ALIFLIX_CINEJOY_LIVE_MASTER`. It checks
live media loading and sample delivery. The ordinary unit gate skips that test;
its independent live result must be assessed before claiming provider playback.
