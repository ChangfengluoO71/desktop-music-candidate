# DMC product roadmap

Status snapshot: 2026-10-13. See [current project status](PROJECT-STATUS.md) for repository and preview-build facts. Player P1 is in progress: local playback/UI, queue reorder, and focus-safe shortcuts are implemented and automated; real audio-device and installed-Inno acceptance remain open. Detailed phases and gates are in [PLAYER-P1.md](PLAYER-P1.md).

## P0 — Independent product foundation

- Independent Gradle build, local wrapper, Windows packaging and public GitHub repository.
- Local music scanning/indexing and optional NetEase discovery/download kept in separate storage and service areas.
- Source attribution, dependency snapshot, license inventory and a DMC identity decision.
- The installed preview still uses the earlier `Desktop Music Candidate` runtime identity; see [PRODUCT-IDENTITY.md](PRODUCT-IDENTITY.md).

## P1 — Offline local player and music manager

- Implemented: OpenJFX Media behind a provider-free Kotlin interface; local queue and transport UI; mini-player; Now Playing lyric/queue view; cover wall; opt-in folder watching; synthetic MP3, AAC-LC/M4A and PCM WAV load tests; queue drag/button reorder; focus-safe player shortcuts.
- Remaining: audible output and transport testing in a real Windows window; inspect the installed Inno package via CI; verify packaged license notices; determine whether to adopt a FLAC-capable backend; resize/high-DPI and source-file immutability acceptance.
- The current backend does not support FLAC. Scanner extension recognition is not evidence of decoder support.
- Detailed implementation status and gates: [Player P1 plan](PLAYER-P1.md).

## P2 — Library depth and personalization

- Faster indexing and search for large libraries; resilient handling of network/removable drives and duplicate roots.
- Saved views, favorites, playlists and richer album/artist organization.
- Advanced output-device selection, global media keys and configurable playback behavior after the P1 backend proves stable.
- Cache invalidation and cover-art quality/performance improvements based on real library measurements.

## P3 — Lyrics and library metadata

- Improve timed translation/romanization alignment, word-level YRC rendering and karaoke-style presentation.
- Add opt-in metadata edits with backups, diff preview and undo; local user tags remain authoritative.
- Keep any online lyric or metadata source optional, attributable and clearly separated from local playback.

## P4 — Online provider boundaries

- Isolate NetEase behind a provider interface; login remains optional and separate from local playback/library services.
- Introduce provider references distinct from local track identity; migrate existing task JSON without losing history.
- Add additional providers only through normal authorization and source-specific policy checks.

## P5 — Stable release engineering

- Complete the DMC runtime-brand migration with stable installer identity and tested data/credential continuity.
- Test clean install, in-place upgrade and uninstall on supported Windows versions.
- Keep CI tests on every pull request; use a separate explicitly authorized release workflow for installers.
- Complete LGPL source/relinking and bundled-runtime notice review for each release.
