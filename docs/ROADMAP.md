# Product roadmap draft

## P0 — Independent foundation (this candidate)

- Independent Gradle build, local wrapper and Windows packaging configuration.
- Neutral candidate identity and separate data/credential scope.
- Source attribution, dependency snapshot, license inventory and architecture boundaries.
- Existing Desktop test suite retained.

## P1 — Local playback foundation

- Choose and document a Windows/JVM playback backend after an audio format and licensing review.
- Add provider-free playback controls: play/pause, seek, queue, previous/next, volume and output-device behavior.
- Add playback-state and supported-codec tests using synthetic fixtures; unsupported formats must be explicit.

## P2 — Local library experience

- Reliable directory watching with debounce, removable roots, scan cancellation and recovery.
- Album/artist poster wall, cover cache and fast search/filtering over the local index.
- Fullscreen/now-playing surface with background art and keyboard/media-key support.

## P3 — Lyrics and metadata

- Local LRC/embedded lyric parsing, synchronized display and explicit translation/romanization tracks.
- Keep user tags and local files authoritative; online metadata is optional and attributable.
- Review write-back, backups and undo before enabling batch tag edits.

## P4 — Provider adapters

- Isolate NetEase behind a provider interface; keep login optional and separate from local playback/library services.
- Introduce provider references separate from local track IDs and migrate old task JSON without losing history.
- Add additional providers only through normal authorization and source-specific policy checks.

## P5 — Release engineering

- Final name, owned namespace, original icon, stable installer/upgrade identity and data migration policy.
- Windows clean-machine install, upgrade, uninstall and data-preservation tests.
- CI tests on every pull request and manual installer artifacts; later add signing, release notes, checksum and update policy after release authorization.
- Complete LGPL/JRE notices review before public distribution.
