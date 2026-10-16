# R3 Integrated Player UX Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` and implement each task in order on the user-approved `main` branch. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Integrate DMC playback into the main window and deliver a responsive, efficient local music library with reliable artwork, lyrics, queue, and watched-folder behavior.

**Architecture:** Keep the existing Kotlin, Compose Desktop, Miuix, and libmpv architecture. Route Now Playing through `DesktopPage`, share one bounded local artwork repository between the library and player, keep playback state in `PlaybackCoordinator`, and update only affected local index records for watched-folder changes with a full-scan recovery path.

**Tech Stack:** Kotlin/JVM 17 target, Compose Desktop 1.10.3, Miuix 0.9.3, kotlinx.coroutines 1.11.0, JNA/libmpv, SQLite, jaudiotagger, existing JUnit 5 and Compose UI test setup.

**Spec:** `docs/superpowers/specs/2026-10-14-r3-integrated-player-ux-design.md`

## Global Constraints

- Keep playback on libmpv and Windows WASAPI shared output; never launch `mpv.exe` or fall back to JavaFX.
- Configure libmpv with `audio-display=no`; do not change TLS, network policy, or audio output as part of this player work.
- Keep local playback and its queue independent of NetEase IDs, sessions, cookies, and online audio URLs.
- Keep local audio files read-only; directory removal changes only DMC's SQLite index.
- Preserve opt-in recursive directory monitoring and use a full root scan as recovery after watcher overflow or failed recovery.
- Do not add a large runtime dependency for artwork, player layout, or directory watching.
- Implement on the current `main` branch as explicitly requested. Do not publish or release; ask before pushing implementation commits to the shared remote branch.

## Review Focus

- Audio files with embedded cover art must play through WASAPI without an extra mpv/native output window; missing libmpv must remain an in-app error.
- Very large, malformed, missing, or replaced artwork must not stall the UI or grow the thumbnail cache without bound.
- Rapid copy, partial writes, rename, deletion, overlapping roots, watcher overflow, and disconnected roots must not corrupt the SQLite index or modify source files.
- Manual lyric scrolling must not be immediately overridden by auto-follow; clicking a timed line must seek the current track.
- Resizing, high DPI, empty library, and text-input focus must keep controls readable and keyboard shortcuts predictable.

---

### Task 1: Disable libmpv audio cover output

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvPlaybackEngine.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvPlaybackEngineTest.kt`
- Modify: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvSharedOutputAcceptanceTest.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvEmbeddedArtworkWindowAcceptanceTest.kt`

**Interfaces:**
- Consumes: existing `MpvClient.setOption(name: String, value: String)` and native shared-output acceptance task.
- Produces: the initialized libmpv client always has `audio-display=no`; no new public playback API.

- [x] **Step 1: Add the failing configuration assertion** to `initializes with wasapi only and explicitly disables null fallback`; assert `client.options["audio-display"] == "no"`.
- [x] **Step 2: Run the targeted test and verify RED.** Run `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.libmpv.LibMpvPlaybackEngineTest"`; the new assertion must fail because the option is not configured.
- [x] **Step 3: Add the Windows-native acceptance test before changing production code.** Capture visible top-level window handles, play a generated audio fixture with embedded artwork, and assert no new visible top-level window appears while WASAPI playback and position advance. If the pinned runtime is unavailable, record this RED check as `BLOCKED` and continue from the unit test.
- [x] **Step 4: Configure `audio-display=no`** in `LibMpvPlaybackEngine.initializeClient` alongside the existing explicit mpv options.
- [x] **Step 5: Re-run the targeted test and verify GREEN.** Run the same command and confirm it passes.
- [x] **Step 6: Run the native shared-output task — PASS (2026-10-14 follow-up).** Added a hash-pinned local runtime setup and Gradle defaults, then passed both `nativeMpvShared` acceptance tests under JDK 25.0.2: four formats selected WASAPI and advanced; embedded cover created zero extra visible windows. See `docs/testing/libmpv-runtime.md`.
- [x] **Step 7: Run the full test suite** with `.\gradlew.bat --no-daemon test`.
- [ ] **Step 8: Commit the task** with message `fix(player): disable libmpv audio cover output`.

### Task 2: Move Now Playing into the main DMC window

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DesktopViewModel.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/Main.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerScreens.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerViewModel.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/DesktopViewModelTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/PlayerScreensTest.kt`

**Interfaces:**
- Consumes: existing `DesktopViewModel.navigate(page: DesktopPage)` and `PlayerViewModel` playback actions.
- Produces: `DesktopPage.NOW_PLAYING`; a `NowPlayingScreen(viewModel: PlayerViewModel)` composable rendered in the main page switch; `PlayerMiniBar(viewModel, onOpenNowPlaying)` navigates to that page.

- [x] **Step 1: Add failing navigation coverage** in `DesktopViewModelTest` asserting `navigate(DesktopPage.NOW_PLAYING)` updates `DesktopUiState.page`.
- [x] **Step 2: Run the targeted test and verify RED.** Run `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.DesktopViewModelTest"`; it must fail to compile until the new page exists.
- [x] **Step 3: Add `NOW_PLAYING` to the page enum and the main title/content switch.** Add a visible sidebar entry; retain the mini-player at the bottom on every page.
- [x] **Step 4: Extract `NowPlayingScreen`** from `PlayerNowPlayingDialog`, remove the player `DialogWindow`, and route mini-player actions through `DesktopViewModel.navigate`. Remove `isNowPlayingOpen`, `openNowPlaying`, and `closeNowPlaying` if no other call sites remain.
- [x] **Step 5: Add Compose coverage** asserting the Now Playing route displays the lyrics/queue controls in the main content, and mini-player navigation calls the supplied route callback.
- [x] **Step 6: Run `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.PlayerScreensTest" --tests "com.qing.hachimi.desktop.DesktopViewModelTest"`** and confirm all tests pass.
- [x] **Step 7: Run the full test suite** with `.\gradlew.bat --no-daemon test`.
- [ ] **Step 8: Commit the task** with message `feat(player): integrate now playing into main window`.

### Task 3: Add a shared bounded artwork repository

**Files:**
- Create: `src/main/kotlin/com/qing/hachimi/desktop/LocalArtworkRepository.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/Main.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicScreen.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerScreens.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/LocalArtworkRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `LocalArtworkResolver.resolve(audioPath: Path): ArtworkPayload?`.
- Produces: `LocalArtworkRepository.thumbnail(track: LocalTrack, targetEdgePx: Int): ImageBitmap?`, `invalidate(path: Path)`, and `clear()`; one instance is shared by local-library cards and the player.

- [x] **Step 1: Write failing repository tests** for repeated requests sharing one extraction, cache key changes after file identity changes, explicit invalidation, missing/invalid artwork, unsafe image dimensions, and eviction at a fixed decoded-image budget of 64 MiB.
- [x] **Step 2: Run `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.LocalArtworkRepositoryTest"` and verify RED** on missing repository symbols/behavior.
- [x] **Step 3: Implement the repository** with background decoding, path + size + modified-time cache keys, deduplicated in-flight extraction, bounded source dimensions with image-reader subsampling, display-sized images, and a 64 MiB least-recently-used decoded-image budget.
- [x] **Step 4: Inject one repository instance** from `HachimiDesktopApp` into local album cards and player artwork; remove direct per-composition `LocalArtworkResolver()` construction from both screens.
- [x] **Step 5: Run repository and screen tests** with `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.LocalArtworkRepositoryTest" --tests "com.qing.hachimi.desktop.LocalMusicScreenTest" --tests "com.qing.hachimi.desktop.PlayerScreensTest"`; confirm repeated UI reads do not cause repeated extraction.
- [x] **Step 6: Run the full test suite** with `.\gradlew.bat --no-daemon test`.
- [ ] **Step 7: Commit the task** with message `feat(library): share bounded local artwork cache`.

### Task 4: Build responsive local-library browsing views

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicModels.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicViewModel.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicScreen.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/LocalMusicScreenTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/LocalMusicViewModelTest.kt`

**Interfaces:**
- Produces: `LocalMusicBrowseMode { ALBUMS, ARTISTS, SONGS, FOLDERS }`, `LocalMusicSortMode { TITLE, ARTIST, ALBUM, FORMAT, DURATION }`, `LocalMusicViewModel.setBrowseMode(mode)`, `setSortMode(mode)`, `setFolderFilter(path: String?)`, and adaptive album cards that consume the shared `LocalArtworkRepository` from Task 3.
- Album and artist selections update the existing `albumFilter` and `artistFilter`; folder selection filters tracks by canonical subtree; album actions reuse the existing play/enqueue list operations.

- [x] **Step 1: Add failing Compose tests** for four browse tabs, default album view, adaptive album cards, artist album counts, title/artist/album/format/duration sorting, and watched-root/subfolder navigation.
- [x] **Step 2: Run `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.LocalMusicScreenTest"` and verify RED.**
- [x] **Step 3: Add browse mode state and the four view bodies.** Use `LazyVerticalGrid(GridCells.Adaptive(...))` for albums/artists, a virtualized song list, and a folder tree derived from indexed file paths under registered roots.
- [x] **Step 4: Move scan/root controls to a secondary library toolbar** so the main browsing area is not displaced by technical scan details; retain add, scan, cancel, remove-index, and watch controls.
- [x] **Step 5: Run the local music screen and view-model tests** with `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.LocalMusicScreenTest" --tests "com.qing.hachimi.desktop.LocalMusicViewModelTest"`; confirm existing scan, duplicate, upgrade-preview, and read-only behavior remains covered.
- [x] **Step 6: Run the full test suite** with `.\gradlew.bat --no-daemon test`.
- [ ] **Step 7: Commit the task** with message `feat(library): add responsive local music views`.

### Task 5: Finish lyric, queue, and playback interactions

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlaybackCoordinator.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerViewModel.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerScreens.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerQueueContent.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicScreen.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/PlaybackCoordinatorTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/PlayerViewModelTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/PlayerScreensTest.kt`

**Interfaces:**
- Produces: `PlaybackCoordinator.enqueueNext(track: LocalTrack)` and `PlayerViewModel.playNext(track: LocalTrack)`; when a track is active, Play next inserts immediately after it without changing current playback; when idle, it starts the requested track. Clicking a timed lyric invokes the existing `seek(positionMillis: Long)` path.
- Lyric auto-follow pauses on user drag/wheel scroll and resumes through an explicit control; track or lyric-variant changes restore follow mode.

- [x] **Step 1: Add failing coordinator coverage** asserting `enqueueNext` inserts after the current item without changing the current track or losing the rest of queue order.
- [x] **Step 2: Run `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.PlaybackCoordinatorTest"` and verify RED.**
- [x] **Step 3: Implement `enqueueNext` and expose Play now / Play next / Add to queue from song and album actions.** Keep the existing queue reorder, remove, clear, shuffle, and repeat behavior; add regression assertions for queue order and current-track identity where current tests do not already cover them.
- [x] **Step 4: Add a failing Compose test** asserting a timed lyric click requests a seek and a manual scroll pauses auto-follow until the resume action is selected.
- [x] **Step 5: Implement lyric seek and follow-state behavior** without resetting lyric variants; track changes and variant changes restore auto-follow.
- [x] **Step 6: Run coordinator, view-model, screen, and keyboard tests** with `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.PlaybackCoordinatorTest" --tests "com.qing.hachimi.desktop.PlayerViewModelTest" --tests "com.qing.hachimi.desktop.PlayerScreensTest" --tests "com.qing.hachimi.desktop.PlayerKeyboardShortcutsTest"`.
- [x] **Step 7: Run the full test suite** with `.\gradlew.bat --no-daemon test`.
- [ ] **Step 8: Commit the task** with message `feat(player): improve lyric and queue controls`.

### Task 6: Update watched folders incrementally

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicDirectoryWatcher.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DesktopMusicScanner.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicStore.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalMusicViewModel.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalArtworkRepository.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/LocalMusicDirectoryWatcherTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/DesktopMusicScannerTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/LocalMusicStoreTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/LocalMusicViewModelTest.kt`

**Interfaces:**
- Produces: `enum class LocalMusicChangeKind { CREATED, MODIFIED, DELETED, RESCAN_ROOT }; data class LocalMusicChange(val root: Path, val path: Path, val kind: LocalMusicChangeKind)` and `DesktopMusicScanner.applyChanges(changes: List<LocalMusicChange>)`.
- `LocalMusicStore` removes a deleted path's root membership and deletes the track row only when no registered root references it.
- Overflow or lost-watch recovery requests a full scan for the affected root; artwork-sidecar changes invalidate thumbnails without reparsing every audio file.

- [x] **Step 1: Add failing watcher tests** asserting emitted changes are path-specific, burst events coalesce, new subdirectories are registered, lost watches reconnect, disconnected roots recover, and overflow emits a root-recovery event.
- [x] **Step 2: Run `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.LocalMusicDirectoryWatcherTest"` and verify RED.**
- [x] **Step 3: Add the `LocalMusicChange` event contract** and coalesce events by canonical root/path while preserving the final operation in each burst.
- [x] **Step 4: Add failing SQLite/scanner tests** for create, modify, stable-file wait, rename, delete, and a path referenced by overlapping roots.
- [x] **Step 5: Implement changed-path scanning and transactional index updates.** Treat a file as stable only after size and last-modified time match in two samples 500 ms apart; after 10 seconds of continued change, reschedule it and leave the previous index record intact. Never change source files.
- [x] **Step 6: Wire the view-model to apply only changed paths** and invalidate affected artwork; use existing full-root scan on overflow, failed registration, or reconnect recovery.
- [x] **Step 7: Run watcher, scanner, store, and view-model tests** with `.\gradlew.bat --no-daemon test --tests "com.qing.hachimi.desktop.LocalMusicDirectoryWatcherTest" --tests "com.qing.hachimi.desktop.DesktopMusicScannerTest" --tests "com.qing.hachimi.desktop.LocalMusicStoreTest" --tests "com.qing.hachimi.desktop.LocalMusicViewModelTest"`.
- [x] **Step 8: Run the full test suite** with `.\gradlew.bat --no-daemon test`.
- [ ] **Step 9: Commit the task** with message `feat(library): update watched folders incrementally`.

### Task 7: Reconcile documents and run Windows acceptance

**Files:**
- Modify: `README.md`
- Modify: `docs/PLAYER-P1.md`
- Modify: `docs/ROADMAP.md`
- Modify: `docs/PROJECT-STATUS.md`
- Modify: `docs/superpowers/specs/2026-10-14-r3-integrated-player-ux-design.md`

**Interfaces:**
- Consumes: completed behavior and actual verification output from Tasks 1-6.
- Produces: accurate libmpv-only product documentation, R3 status, and an evidence-based Windows acceptance record.

- [x] **Step 1: Correct P1 and status documents** to remove claims that JavaFX is the active player, describe integrated main-window playback, and distinguish automated tests from Windows GUI acceptance.
- [x] **Step 2: Run the complete test suite.** Run `.\gradlew.bat --no-daemon test --rerun-tasks`; record exact totals and any platform-gated tests.
- [x] **Step 3: Build the Windows installer.** Run `.\gradlew.bat --no-daemon packageExe`; record the resulting installer path and version.
- [x] **Step 4: Launch the updated application — PARTIAL / GUI_BLOCKED.** Current source started successfully. No desktop input-control tool was available to exercise navigation, playback, lyrics, queue, shortcuts, or 100%/high-DPI layouts.
- [x] **Step 5: Exercise a large synthetic local library — GUI_BLOCKED.** Automated virtualization and cache tests passed; visual scrolling and repeated-navigation behavior were not operated in a real window.
- [x] **Step 6: Verify the generated embedded-cover fixture — NATIVE_MPV_BLOCKED.** The native acceptance test is present, but the pinned runtime/archive was unavailable.
- [x] **Step 7: Record results without claiming unexecuted checks.** Separate `UNIT_PASS`, `NATIVE_AUDIO_PASS`, `LOCAL_GUI_PASS`, and `INSTALLER_BUILD_PASS`; installation and upgrade remain R4 unless explicitly requested.
- [ ] **Step 8: Commit documentation** with message `docs(player): record R3 implementation and acceptance`.

## Review follow-up and final verification

- [x] Serialize scanner, incremental watcher, duplicate-analysis, and root mutations; discard queued watcher events when their root is removed or auto-watch is disabled.
- [x] Add regression coverage for scan/watch races, compilation-album selection, double-click playback, overflow and invalid WatchKey recovery, disconnected-root reconnection, and scoped artwork invalidation.
- [x] Run focused screen, artwork, watcher, and view-model tests under JDK 25.
- [x] Run `./gradlew.bat --no-daemon test --rerun-tasks`: 298 tests, 49 reports, 0 failures, 0 errors, 0 skipped.
- [x] Run `./gradlew.bat --no-daemon packageExe`: `DesktopMusicCandidate-0.1.2.exe` generated.
- [x] Launch the updated source app with `./gradlew.bat --no-daemon run`; leave it open for review.
- [x] Create one consolidated local R3 commit. Do not push before user approval.

GUI actions were not performed. The Computer Use runtime was present, but the required `sky.documentation()` API was absent, so its guidance could not be loaded safely. The window launch is not counted as GUI acceptance.

## Final completion contract

- The full test suite is green, or every blocker and failed test is named.
- `packageExe` builds successfully, or its exact environmental blocker is recorded.
- The application starts from the current source, and no claim of real GUI or native audio acceptance is made without direct evidence.
- Artwork cache memory is bounded; watched-folder overflow has a tested full-scan recovery; local audio files remain unchanged.
- Player, README, roadmap, and status documents describe the same libmpv-backed implementation.
