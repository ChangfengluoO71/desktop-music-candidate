# DMC project status

Snapshot: 2026-10-13. Player P1 is in progress; this report distinguishes code and automated-test status from installed Windows and audio-device acceptance.

## Repository and build

- Repository: [ChangfengluoO71/desktop-music-candidate](https://github.com/ChangfengluoO71/desktop-music-candidate), public.
- Work is on `main`; the previous P1 foundation was published as `6af7c9d`. This follow-up adds the Windows media adapter, player screens, stronger installer smoke checks and matching status documentation.
- Existing preview: [v0.0.1](https://github.com/ChangfengluoO71/desktop-music-candidate/releases/tag/v0.0.1), built as a Windows Inno Setup installer with a bundled Java runtime. This P1 work does not create a new release.
- Existing installer identity, user data directory, Git remote, Android source and release/tag are unchanged.
- Build runtime: JDK 25. The Gradle runtime dependency report includes OpenJFX 25.0.2 Windows artifacts; see `docs/dependency-licenses.csv` and `docs/dependency-tree-runtimeClasspath.txt`.

## Player P1

- Implemented: local LRC parsing and sidecar loading; durable queue and playback state coordinator; OpenJFX Media adapter; app lifecycle creation/shutdown; queue restoration without auto-play; play/queue actions in the library; mini-player; Now Playing window with artwork, synchronized lyric highlight, original/translation/romanization switching and queue controls; local cover wall/search; opt-in directory monitoring.
- Media integration tests load synthetic MP3, AAC-LC/M4A and PCM WAV fixtures and read their duration. That verifies the local OpenJFX decoder in this test environment, not audible output.
- FLAC remains unsupported by OpenJFX Media and is explicitly excluded from this backend's playable formats. The library may still index FLAC files and will report unsupported playback.
- `createDistributable` succeeded under JDK 25. The app image contains OpenJFX 25.0.2 Windows base/graphics/media JARs; `javafx-media` contains `jfxmedia.dll`; the bundled JDK image includes its `runtime/legal` notices. The Inno script now copies product/dependency license files, and the installer smoke script checks the JavaFX JARs, native decoder, classpath and notices.
- Not yet accepted: actual sound on a Windows audio device; playback/pause/seek/track-end behavior through a real app window; Inno compile/install smoke (Inno Setup is not installed in this local environment); install/upgrade/uninstall preservation; high-DPI/resize usability; FLAC backend selection.
- No new release was created. See [PLAYER-P1.md](PLAYER-P1.md) for scope, architecture, format boundaries, and remaining acceptance gates.

## Validation record

- JDK 25 command: `./gradlew.bat --no-daemon test --rerun-tasks` — 233 tests in 41 reports, 0 failures, 0 errors, 0 skipped.
- JDK 25 command: `./gradlew.bat --no-daemon packageExe` — succeeded and produced the Gradle packaging experiment `DesktopMusicCandidate-0.1.2.exe`.
- JDK 25 command: `./gradlew.bat --no-daemon createDistributable` — succeeded; inspected app image includes JavaFX Windows runtime JARs and the native media DLL inside `javafx-media`.
- The local Inno Setup compiler is unavailable, so the installer script and smoke additions have not run locally; the next Windows CI run must confirm compilation and the extended install/uninstall smoke test.
- Earlier Windows workflow: [CI run 38342677736](https://github.com/ChangfengluoO71/desktop-music-candidate/actions/runs/38342677736), run #12 on `ecaa65b`; this predates the current player integration.

## Product identity and remaining work

- Product documents use the name **DMC**. Runtime executable, window title, installer identity, data directory and Credential Manager target still use `Desktop Music Candidate`; a brand migration is deferred until upgrade and data-preservation tests are prepared.
- Keep the local player independent from NetEase Song IDs, sessions, cookies and remote URLs. Queue state stores local track paths only.
- Next: rerun Windows CI with an audio-output preflight on the JavaFX integration test; if the hosted runner exposes no output line, only that hardware-dependent test is skipped. Then have CI compile the Inno installer and run the extended install/uninstall smoke; complete real Windows audio-device acceptance; decide whether FLAC support is a P1 requirement; then close remaining UI behavior such as keyboard shortcuts, queue reorder and high-DPI layout.
