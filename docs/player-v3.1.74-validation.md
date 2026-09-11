# Player and settings — v3.1.74

Doraby resolves its own advertised movie and exact episode servers through the same native handoff as Ramoflix and Moviepire. The shared catalogue adapter checks TMDB identity and respects the configured source domain. Player controls, progress, episode queue, server selection and fallback remain shared.

If a source cannot prepare the selected movie or episode, the player tries each of the other two configured sources automatically. Failed server names are scoped to the active source. The original resume position and exact episode identity survive the transition; progress remains keyed by content, independently of the streaming source. A successful fallback also survives Activity recreation without restarting the failed source.

Settings now groups account, playback, subtitles, discovery and app maintenance in compact Aliflix cards. Auto quality preserves adaptive selection; Low chooses the lowest supported video resolution, then bitrate, including manifests without bitrate metadata. The preference persists and applies again when loading a new source or server. Existing explicit subtitle preferences are retained; new mobile installations default to automatic captions.

Subtitle startup downloads candidates in ranking order rather than accepting the fastest download. Embedded captions and exact file-hash matches take precedence. Otherwise, two bounded audio-only probes decode the selected stream with its original media timestamps. No microphone recording occurs. The Worker sends mono PCM samples to speech transcription and returns timestamped words. Samples are limited to 20 seconds, rate limited, and never resolved from client-provided URLs; credentials remain server-side.

Timing correction requires at least four distinct dialogue anchors, with two anchors in each of two separated scenes. Offset and recognized frame-rate conversions must agree within 0.8 seconds. Ambiguous/repeated dialogue, mismatched language, and unsupported cuts do not trigger guessed corrections. If verification is unavailable, the highest-ranked readable caption remains available. Translated subtitles without matching spoken dialogue cannot be certified by this approach. Neither sampled dialogue nor metadata establishes perfect synchronization throughout every third-party cut.

## Validation

- Doraby live Android 17: Fight Club, Breaking Bad S1E1 and S2E3 passed decoded video, audio, seek beyond 185 seconds, continued playback, and resolver cleanup. `.validation/doraby-live-test.log`.
- Final Android gate: 261 unit tests, lint, mobile debug assembly, TV debug assembly and mobile instrumentation assembly passed. `.validation/v374-final-gates.log`. TV remains v3.1.69.
- Worker: 124 tests passed, typecheck and deployment dry run passed. The timing endpoint was deployed for live testing.
- An initial strict subtitle test was interrupted by an emulator System UI ANR and is not counted as a pass. Final live timing and release evidence will be recorded after verification.
