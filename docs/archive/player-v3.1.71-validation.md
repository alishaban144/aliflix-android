# Phone player v3.1.71

The phone player uses open white transport icons, an Aliflix violet seek track, compact text actions and edge gradients. Button press indication is explicitly disabled. The preparation message is “Sit back. Playback will begin shortly.”

Holding a rendered caption starts dragging with haptic feedback. Movement accumulates in pixels and resolves to single-dp placement, bounded to the viewport and saved on release. Ordinary taps, seeking and brightness/volume gestures retain their behavior outside this hold gesture. Both the top Back button and Android Back pause the service before leaving; Home and background casting retain playback.

Subtitle files use strict Unicode decoding, language-aware legacy encodings and conservative lossless mojibake repair. The phone rejects damaged text and obvious script mismatches during automatic selection, skips explicit wrong-episode filenames, ranks release hints and tries up to four candidates. A previous file's manual timing offset is cleared when changing titles or subtitle releases. Embedded tracks matching the preferred language take priority automatically because their timestamps belong to the video stream. Explicit manual selection still overrides that choice.

External subtitle timing cannot be guaranteed when a provider exposes a different cut with no embedded timing reference. This change does not fabricate dialogue offsets or infer frame-rate conversion from an unrelated file. Physical Nothing Phone/Android TV lock-screen mirroring remains dependent on the TV route and phone system policy and was not hardware-verified in this release.

The phone home hero keeps a fixed frame with separate artwork parallax, slow zoom, a violet light pass and staggered title fade. Auto-advance yields to touch, scrolling away and background lifecycle state. The current page owns accessibility and Play actions. Subtitle Auto display uses the app's existing pill Switch design.

## Verification

- Five new encoding, language and release-matching unit tests.
- API37 real-video tests passed for first-frame captions, rotation, disable/late attachment, preserved pause/position, recreation, 7-dp hold/drag persistence and system Back pausing the media service.
- A muxed English subtitle fixture proves automatic embedded-track selection wins over a sidecar file, survives a late automatic download and allows a subsequent explicit manual choice.
- UI instrumentation covers a stable home viewport and correct Play target after auto-advance, the subtitle settings switch, and pixel equality while pressing Play (no rectangle or circle indication).
- The existing background decoding, notification, display handoff, IntroDB, details, progress and Cast subtitle regressions remain in the release gate.
- TV version stays 3.1.69/code159; the release reuses its existing APK and manifest.

Encoding reference: [Microsoft code page identifiers](https://learn.microsoft.com/en-us/windows/win32/intl/code-page-identifiers). Track preference reference: [Android Media3 track selection](https://developer.android.com/media/media3/exoplayer/track-selection).
