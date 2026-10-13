# DMC product roadmap

Status snapshot: 2026-10-13. See [current project status](PROJECT-STATUS.md) for repository and preview-build facts. The local player has not been implemented; P1 is a proposed, testable plan documented in [PLAYER-P1.md](PLAYER-P1.md).

## P0 — Independent product foundation

- Independent Gradle build, local wrapper, Windows packaging and public GitHub repository.
- Local music scanning/indexing and optional NetEase discovery/download kept in separate storage and service areas.
- Source attribution, dependency snapshot, license inventory and a DMC identity decision.
- The installed preview still uses the earlier `Desktop Music Candidate` runtime identity; see [PRODUCT-IDENTITY.md](PRODUCT-IDENTITY.md).

## P1 — Offline local player and music manager

- Add an embeddable playback backend behind a provider-free Kotlin interface.
- Deliver local-file playback, queue, transport controls, Windows package integration, local timed lyrics and DMC's cover-led library/now-playing experience.
- Add debounced watching for registered library folders, with full-scan recovery when watcher events overflow or a drive is disconnected.
- Validate codec/container pairs using real, offline test fixtures; the current scanner's extension recognition is not evidence of decoder support.
- Complete a short backend and license spike before selecting a native engine. Detailed phases and gates: [Player P1 plan](PLAYER-P1.md).

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
