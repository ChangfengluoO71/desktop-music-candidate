# DMC project status

Snapshot checked: 2026-10-13; P1 implementation in progress.

## Repository and build

- Repository: [ChangfengluoO71/desktop-music-candidate](https://github.com/ChangfengluoO71/desktop-music-candidate), public.
- Branch: `main`; checked HEAD: `ecaa65b0133ad9220949a3dbdbeb2ffaaa9cb610` (`Use bundled Inno default language`).
- `origin` points to the repository above. The checked-out `main` still starts at the pre-P1 HEAD shown above; the P1 source and documentation changes in this worktree have not been committed or pushed yet.
- Latest reviewed successful Windows workflow: [CI run 38342677736](https://github.com/ChangfengluoO71/desktop-music-candidate/actions/runs/38342677736), run #12 on `ecaa65b`. It ran the test suite, built and smoke-tested the Inno Setup installer, and published the preview release.
- Preview release: [v0.0.1](https://github.com/ChangfengluoO71/desktop-music-candidate/releases/tag/v0.0.1), built as a Windows Inno Setup installer with the Java runtime bundled.
- A Windows installation was launched in the previous verification pass. This does not establish audio playback acceptance; no native playback engine is bundled yet.

## Follow-up items

- GitHub marked Gradle 9.3.1 as out of date in the successful run. This is a non-blocking maintenance notice; review the supported Gradle/Compose/Kotlin matrix before upgrading.
- Runtime product identity has not yet been migrated from `Desktop Music Candidate` to DMC.
- Player P1 is underway. Offline core, local cover-wall/search UI and opt-in folder monitoring are implemented. JDK 25 full suite passed (229 tests); `packageExe` built `DesktopMusicCandidate-0.1.2.exe`. There is no playable audio backend, player UI, or installed-package audio acceptance yet.

## Product status

- Product documents now use the selected name **DMC**.
- The current executable, window title, installer identity, data directory and credential target still use `Desktop Music Candidate`. The branding change in this update is documentation-only; runtime migration is deferred and must preserve the existing `upgradeUuid`/`AppId` and user data.
- Implemented: independent Kotlin/Compose Desktop build; local music scanning, SQLite indexing and duplicate analysis; optional NetEase search/discovery/download/session; Windows credential storage; Inno Setup packaging and CI; local LRC parsing/loading; playback coordinator and queue persistence behind a replaceable engine interface; local artwork resolver; album cover wall and title search; per-root opt-in folder watching with debounced rescans and reconnect checks. `Main.kt` now passes the local music ViewModel to the app, which fixes a previously missing library connection.
- Not implemented: a distributable audio decoder, app-wired playback ViewModel/mini-player/Now Playing lyric screen, track-to-queue UI actions, and real MP3/FLAC/WAV/M4A playback acceptance. See [PLAYER-P1.md](PLAYER-P1.md).

## Current worktree

Before this P1 work, the worktree was clean and `main` matched `origin/main`. Current changes include the previously prepared DMC product docs plus P1 player core, local library UI/store/watcher updates and automated tests. The installer identity, release/tag, remote and Android sources have not been changed. Current validation: JDK 25 full suite 229/229 passed; `packageExe` succeeded and produced the existing app version 0.1.2.

## Next gates

1. Select a reproducible Windows decoding backend and record exact version, license, transitive runtime files, source and hashes; the current mpv development artifact was not suitable to ship.
2. Implement the playback adapter and wire `PlayerViewModel` into app creation/shutdown.
3. Add track-to-queue actions, mini-player and Now Playing synced lyric UI; test queue restore without auto-play.
4. Run clean Windows Inno package acceptance against real MP3, FLAC, WAV and M4A fixtures, including source-file hashes and shutdown.
5. Keep DMC runtime-brand migration separate until installer upgrade, data directory, Credential Manager and shortcuts are verified.
