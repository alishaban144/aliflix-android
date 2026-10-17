# Mobile 3.1.172 validation

This release fixes the Talk exit failure reproduced on Pixel 7a with published 3.1.170. Android reported `NetworkOnMainThreadException` while TLS `close_notify` was written during connection-pool eviction in `TalkVoiceActivity.onDestroy`. Network disposal now runs on the existing HTTP executor, with idempotent audio/focus teardown and no premature executor shutdown.

Talk automatically requests microphone permission and connects when opened. It retains Gemini 3.8 Live / Aoede, native turn detection, interruption and resumption. Controls are transcript, microphone, end, privacy and close; failures expose Retry without Start/Resume. Moving diffuse orb lighting follows real input/output PCM energy; gaze and blink changes are Talk-specific. Accessible, continuous glyphs use a moving highlight and a brief particle reveal. Semantic display themes come from the same Live model through a local nonblocking tool, with filler rejection and stable follow-up context rather than streaming token prefixes.

User-owned recent progress is bounded to eight entries with exact season/episode, seconds, duration and strict completion. A cached on-demand viewing tool resolves independent TVMaze/Wikidata identities, retains gaps in viewed episode ranges, and does not request TMDB prose or audio from playback. No extra conversational model or TTS is introduced. Existing history privacy controls remain.

Playback preferences show Auto/Low inline. Download preferences show Auto/Low inline; the existing saved balanced tier remains effective until changed. Source download quality availability and episode pickers retain their behavior.

IMDb results merge partial host responses rather than returning on the first incomplete season. A verified parent plus exact S/E is authoritative across translated titles; explicit episode IMDb IDs still must match. No missing score is invented. TV retains its existing rating and orb behavior.

Design references: [Live Orb](https://23rd.dev/docs/components/live-orb), [Radix Toggle Group](https://www.radix-ui.com/primitives/docs/components/toggle-group), and the existing [Pixel Flow Field reference](https://21st.dev/community/components?preview=%2F%40educalvolpz%2Fcomponents%2Fpixel-flow-field). Native Compose adaptations reuse Aliflix surfaces, motion, accessibility and reduced-motion settings.

Validation results and public artifact evidence are appended after the focused device checks and GitHub workflow finish. Original native/model assets and subtitle synchronization algorithms are unchanged.

Focused evidence so far: local mobile debug/unit/test APK build succeeded; 670 unit tests, zero failures/errors, three opt-in host-native skips. Worker full suite 152 passed, followed by 26 focused Talk tests after identity/cache improvements. On Pixel 7a, all 26 Dark episodes across seasons 1-3 returned their own verified IMDb ID and score in a 1.911-second live repository test, including English season-2 titles matched against original German names.

Final focused Pixel checks passed: automatic listening, two real Gemini Live turns, meaningful `THE SHINING` topic retained after an acknowledgement, pinned Aoede voice, active-session exit returning to the existing MainActivity, and microphone/audio state reset. Test duration 27.934 seconds; measured rendered first-audio delays 1771ms and 1455ms. Separate Talk privacy/transcript/close and inline playback-quality UI checks passed (2 tests, 5.248 seconds). The first lifecycle fixture used ActivityScenario launch/idle synchronization and hung on the animated screen; it was replaced with direct lifecycle observation and rerun successfully. The stopped fixture is not counted as a pass.

The device installation uses the existing private test signer to retain user data. Production signing and original published APK proof remain separate GitHub release gates. Local downloaded subtitles/audio, binary/model bytes and Sync with Audio algorithms were not changed. These focused checks do not establish universal AI accuracy, source uptime or flawless behavior on every device.
