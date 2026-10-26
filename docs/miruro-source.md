# Miruro native source

Based on the Miruro website client retrieved on 2026-09-25. Uses the same-origin secure GET catalogue endpoint and current environment key, including gzip/zlib/deflate response decoding. No hard-coded provider stream URLs or CDN keys. Native Media3 remains responsible for playback.

The compressed mobile asset contains TMDB-to-AniList edges from https://github.com/anibridge/anibridge-mappings/releases/download/v3/mappings.min.json (9,177 title/season records retrieved 2026-09-25). See https://github.com/anibridge/anibridge-mappings for dataset provenance. A unique exact title/type/year AniList match is used only for unmapped movies and first seasons. Ambiguous or split/merged episode mappings are rejected rather than playing the wrong episode. Unreleased episodes and titles missing from Miruro cannot produce a stream.

Miruro is attempted first for Japanese animation. Its provider list, parent relationships, variants and per-stream mirrors are resolved dynamically. Playlist origin overrides carry through native playback, resume, downloads and Cast. General sources remain fallback choices.

Tests and device/emulator playback were explicitly waived for this release. The signed release build and published artifact integrity are checked separately; those do not prove provider availability or runtime playback.
