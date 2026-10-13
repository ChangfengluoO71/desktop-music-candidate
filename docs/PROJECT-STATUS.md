# DMC project status

Snapshot: 2026-10-13. Player P1 is implemented in part. This report separates code and automated test results from real-device audio acceptance.

## Repository and build

- Repository: [ChangfengluoO71/desktop-music-candidate](https://github.com/ChangfengluoO71/desktop-music-candidate), public.
- Current branch: `main`. Player work is published in `612dba3` and `c2cfdba`.
- Existing preview: [v0.0.1](https://github.com/ChangfengluoO71/desktop-music-candidate/releases/tag/v0.0.1). This P1 work did not create a release.
- Installer identity, user data directory, remote, Android source, and release tags are unchanged.
- Build runtime: JDK 25. Runtime dependency inventories include OpenJFX 25.0.2 Windows artifacts; see `docs/dependency-licenses.csv` and `docs/dependency-tree-runtimeClasspath.txt`.

## Player P1

- Implemented: local LRC parsing and sidecar loading; durable playback queue and coordinator; OpenJFX Media adapter; application lifecycle and shutdown; queue restoration without auto-play; play/queue actions in the local library; mini-player; Now Playing window with artwork, synchronized lyric highlight, original/translation/romanization switching and queue controls; cover wall/search; opt-in directory monitoring.
- Automated media integration loads synthetic MP3, AAC-LC/M4A and PCM WAV fixtures and reads duration. This verifies local media-stack loading, not audible output.
- FLAC is unsupported by OpenJFX Media and is excluded from this backend's playable formats. FLAC files can still be indexed; playback reports unsupported format.
- `createDistributable` succeeded under JDK 25. The image contains OpenJFX 25.0.2 Windows base/graphics/media JARs; `javafx-media` contains `jfxmedia.dll`; the bundled JDK image contains `runtime/legal` notices. The Inno script copies product/dependency notices.
- Still unverified: audible output and playback controls on a real Windows audio device; launching the installed app from Start Menu; upgrade behavior; high-DPI/resize usability; and whether to add a FLAC-capable backend.
- See [PLAYER-P1.md](PLAYER-P1.md) for the detailed scope, architecture, supported-format boundaries, and remaining acceptance gates.

## Validation record

- Local JDK 25 command: `./gradlew.bat --no-daemon test --rerun-tasks` — 233 tests in 41 reports, 0 failures, 0 errors, 0 skipped.
- Local JDK 25 command: `./gradlew.bat --no-daemon packageExe` — succeeded and produced the packaging experiment `DesktopMusicCandidate-0.1.2.exe`.
- Local JDK 25 command: `./gradlew.bat --no-daemon createDistributable` — succeeded; inspected app image includes JavaFX Windows runtime JARs and its native media DLL.
- GitHub Actions [run 38350307463](https://github.com/ChangfengluoO71/desktop-music-candidate/actions/runs/38350307463) passed the Windows full-test job and the Inno Setup installer job on JDK 25. The publish-preview job was skipped (`publish_preview=false`); no release was created.
- The installer smoke compiled the setup, installed to a custom directory, checked bundled JVM/OpenJFX media files and notices, checked Start Menu and desktop shortcuts, uninstalled, and confirmed test user data survived. It did not launch the installed application or test audible playback.
- GitHub Actions currently does not upload JUnit XML, so the hosted run's exact test/skip count is not captured. The 233-test count above is the local run. The JavaFX hardware test uses an output-endpoint preflight; a green CI job alone is not audible-playback acceptance.
- No Inno installer was installed or launched on this user's local machine during this task. The CI installer artifact is short-lived and is not a release.

## Product identity and next work

- Product documentation uses **DMC**. Runtime executable, window title, installer identity, data directory, and Credential Manager target still use `Desktop Music Candidate`; changing these identities is deferred until upgrade/data-preservation tests are ready.
- The player remains independent of NetEase Song IDs, sessions, cookies, and remote URLs. Its queue stores local track paths only.
- Next: manually launch the installed app from Start Menu and verify audible playback, pause/seek/track-end behavior, and supported formats on a real Windows audio device; validate upgrade/data preservation; decide whether FLAC support is a P1 requirement; then address keyboard shortcuts, queue reorder, and high-DPI layout.
