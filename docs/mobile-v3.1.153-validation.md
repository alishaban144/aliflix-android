# Mobile v3.1.153 validation

Baseline: published mobile v3.1.152. Target: 3.1.153 / 243. TV sources,
version and release artifacts are unchanged. The authorized Pixel 7a runs
Android 17; installations preserve its data and existing local certificate.

## Reproduced failures

Normal catalogue playback of The Terminator (1984), with automatically selected
English captions, reproduced a permanent recognition failure after Android
`ERROR_SERVER_DISCONNECTED` (11). Seeking and waiting could not restart the
decoder. The earlier v3.1.152 acceptance fixture deliberately selected a matching
NTSC caption edition; it did not establish the automatic-caption route.

The real automatically selected edition needs a playback-rate correction as well
as an offset. Short, isolated exchanges cannot always distinguish nearby clocks.
The fully minified normal route also reproduced an unlabeled "Original audio"
track that prevented English recognition from starting at all.

## Changes and evidence boundaries

Transient recognition errors retry with bounded background backoff. Session
tokens fence disconnected listeners; queue overflow and seeks cannot join PCM
across a gap. Unsupported models and permissions do not enter endless retries.
Unlabeled audio can try an English decoder when English captions are selected;
the caption label is not evidence of the soundtrack's language or timing.
Explicit other-language audio retains native speech-presence alignment.

Only played word timestamps are retained, with a 1,500-word limit. Audio identity
changes clear this history. Earlier phrases can prove a supported subtitle rate,
and independently withheld later phrases must agree. A current matched phrase
is required; stale earlier scenes cannot apply after an unmatched seek. Competing
clocks, contradictory phrases and fabricated cross-gap phrases still reject.

A weak presence envelope cannot veto independently recognized dialogue without
providing a distinctive contradictory alternative. That alternative only vetoes
the lexical proposal; it never supplies or guesses a correction. Broad native
presence-only matching keeps its existing independent acceptance checks.

The interaction still has a 1,800 ms analysis budget plus UI allowance, preserves
manual delay, original cues, source/audio/subtitle identity, account isolation,
Reset and running playback. No subtitle download or whole-file scan begins on tap.

## Physical and independent checks

`AutomaticTerminatorSyncDeviceTest` launches normal provider resolution with
automatic English subtitles. It supplies no stream request, replacement caption
edition, expected offset or detector evidence. Played conversations at 1,275.619
and 3,196.058 seconds provide independently matched phrases. The test checks
persisted correction and every rendered VTT boundary, with manual delay unchanged.

The completed native run synchronized in 559 ms. A separate host Whisper decoder
analyzed contiguous decoded PCM; three phrase endings fit a clock and two later
phrase endings independently verified it. Maximum applied timing error across
the five checked endings was below 349 ms; held-out fit error was 300 ms. These are
measured phrase checks, not a claim that every authored caption boundary equals
a spoken-word boundary or that every title/language has adequate evidence.

The framework-only minified driver now has a normal automatic-caption route.
It supplies only a title selection, exercises played scenes through the Android
media session and taps the actual Sync/Reset controls. It has no application
class references, decoder substitution or supplied timing answer. Private media,
caption text, source URLs and authentication evidence remain outside Git.
The fully minified 3.1.153 normal route also passed with unlabeled audio:
410 ms to apply the correction, below 333 ms error against the independently
checked phrase endings, followed by successful Reset and preserved manual delay.
A repeat passed in 464 ms with error below 349 ms. A separate minified Tears of
Steel check corrected captions deliberately shifted by 7.25 seconds: 70 ms maximum
cue error, 277 ms to finish, and successful Reset. A minified English/French/English
audio-switch and forward/backward-seek check preserved playback/manual delay and
rejected unrelated captions without false success (82 ms interaction).

## IntroDB and Up Next

The application uses `https://api.introdb.app/segments`. Live Breaking Bad S1E1
metadata returned a credits start at 3,431,000 ms and an end at 3,500,000 ms.
A valid start must remain usable when a stream finishes before that database
end. The old duration check discarded it and fell back to the final window.
Missing/outro-less cached metadata now refreshes after five minutes; known
outros retain the existing 24-hour cache.

Three physical Up Next tests passed: measured portrait/landscape placement with
large captions, autoplay disabled, Watch credits dismissal, end-of-episode
availability without a marker, and a database end beyond the actual stream.
IntroDB does not have credits metadata for every episode: Undone S1E1 returned
`outro: null` during investigation, so that case still uses the fallback window.

## Build gate

481 mobile unit tests completed with zero failures/errors and three intentional
skips. Mobile lint, debug/test APK assembly and fully optimized benchmark
assembly passed. No TV build was run. GitHub production signing, public artifact
integrity and minified real-playback results are separate release gates.
