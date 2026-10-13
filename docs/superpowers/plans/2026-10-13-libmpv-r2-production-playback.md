# libmpv R2 Production Playback Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` inline, task by task. Follow TDD for every production behavior change.

**Goal:** Switch DMC's production local player to libmpv with strict WASAPI shared output, reliable asynchronous loading, and session-safe queue events.

**Architecture:** Keep `PlaybackCoordinator` and the Compose player UI. Add a production libmpv engine whose native handle and event processing are confined to a dedicated worker, and carry a monotonically increasing playback session ID through engine commands and events. Keep JavaFX as a migration comparison only; if libmpv cannot be found or WASAPI cannot initialize, report an actionable playback error rather than silently using JavaFX or null output.

**Tech Stack:** Kotlin/JVM 17 bytecode, JDK 25 build, kotlinx.coroutines, JNA 5.19.1, Compose Desktop, existing JUnit 5 tests.

**Spec:** `docs/superpowers/specs/2026-10-13-libmpv-playback-design.md` — R2 section.

## Global Constraints

- Explicitly set `ao=wasapi` and `audio-fallback-to-null=no`.
- Do not remove the JavaFX backend or its dependency/tests in R2; removal belongs to R3 after Windows validation.
- Do not bundle a libmpv DLL, change the installer, or claim release readiness in R2.
- Resolve native libraries from an explicit absolute path or an application-private location; never search arbitrary `PATH` entries.
- Keep playback local-file-only and do not change queue, lyrics, or local-library persistence formats.
- Preserve existing `DesktopSettings` values and default old/missing audio-output settings to `SHARED`.
- Do not claim audible playback from decoder-only or mocked tests; record backend, transport, and listening evidence separately.

## Review Focus

- Late property updates from the previous file must not change current progress; cover stale `Position` in Task 1.
- A late end event from the previous file must not advance the queue; cover stale `Ended` in Task 1 and native playlist IDs in Task 2.
- A late error from the previous file must not mark the new file failed; cover stale `Failed` in Task 1.
- A cancelled load or shutdown must not leave an event thread, callback, or native handle alive; cover cancellation and close ordering in Task 2.
- Missing/corrupt old settings or missing libmpv must preserve data and surface a playback error without switching backends; cover settings migration in Task 3 and unavailable-library behavior in Task 3.

---

### Task 1: Session-scoped playback events

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlaybackCoordinator.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/JavaFxPlaybackEngine.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/PlaybackCoordinatorTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/PlaybackCoordinatorSessionTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/JavaFxPlaybackEngineTest.kt`

**Interfaces:**
- Produce `PlaybackSessionId(value: Long)`, required by `PlaybackEvent.Position`, `PlaybackEvent.Ended`, and `PlaybackEvent.Failed`.
- Change per-track `PlaybackEngine` operations to carry `PlaybackSessionId`; `load(track, sessionId)` assigns it and later operations for a superseded ID are ignored. `stop(sessionId = null)` accepts null only for an unconditional stop/close.
- `PlaybackCoordinator` assigns a new ID for every load/reload attempt, exposes the active ID and `isLoading` in `PlaybackState`, ignores events whose ID is not current, and does not hold its state mutex while waiting for native file-loaded events.
- JavaFX callbacks capture the ID passed to their specific `load` call.

- [x] Write failing tests for stale `Ended`, `Position`, and `Failed` events after switching tracks; assert the active track, queue index, position, duration, playing state, and error stay unchanged. Add controlled-load tests proving B can supersede a pending A and clear/stop can proceed while A is loading.
- [x] Run `PlaybackCoordinatorTest` and verify those tests fail because the session-aware API/guard does not exist.
- [x] Add session IDs to the event/engine contract and route JavaFX callbacks with their captured ID.
- [x] Add coordinator session allocation/invalidation and stale-event filtering, including restored/reloaded tracks. Move asynchronous load/play work outside the state mutex so queue changes and stop remain responsive during file loading; guard all engine controls with the session ID.
- [x] Run coordinator and JavaFX playback tests; verify existing queue, pause, seek, and end behavior remains green.
- [x] Commit as `fix(player): scope playback events to track sessions`.

### Task 2: Production libmpv engine and native event loop

**Files:**
- Create: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvNativeApi.kt`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvPlaybackEngine.kt`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/MpvEventDecoder.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvPlaybackEngineTest.kt`

**Interfaces:**
- `LibMpvPlaybackEngine(apiFactory, libraryPath, dispatcher/workerFactory, loadTimeout)` implements `PlaybackEngine` and an output-diagnostics query.
- All native-handle operations and event polling are serialized on one dedicated worker; suspend calls submit work and await results without blocking Compose/UI.
- `load(track, sessionId)` completes only after the matching file-loaded event, with timeout/cancellation surfaced as an error.
- End-file handling emits `Ended` only for EOF mapped to the matching playlist entry; STOP/QUIT/ERROR/REDIRECT/unknown never advance the queue.

- [x] Write fake-native tests for strict WASAPI/no-null options, load waiting for FILE_LOADED (not command submission), event/session mapping, EOF vs STOP/ERROR, stop/supersede during loading, repeated seek, cancellation, and close waiting for the event worker.
- [x] Run the focused test class and verify it fails because the production engine/API does not exist.
- [x] Implement the narrow JNA mapping, explicit UTF-8 native arguments, a serialized worker/event loop, command reply IDs, property observation IDs, and playlist-entry-to-session mapping.
- [x] Set `config=no`, `load-scripts=no`, `ao=wasapi`, `audio-exclusive=no`, and `audio-fallback-to-null=no`; wait for active `current-ao=wasapi` before returning from play and fail explicitly if shared WASAPI does not initialize.
- [x] Add bounded load/output timeouts and cancellation/close ordering; do not log full local paths or native command arguments.
- [x] Run focused fake-native tests and the existing native POC tests; the POC remains test-only and continues using `ao=null` only for decode verification.
- [x] Commit as `feat(player): add libmpv shared-output engine`.

### Task 3: Production wiring and settings compatibility

**Files:**
- Create: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvLibraryLocator.kt`
- Create or modify: `src/main/kotlin/com/qing/hachimi/desktop/UnavailablePlaybackEngine.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DownloadTask.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DownloadTaskStore.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/Main.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/DownloadTaskStoreTest.kt`
- Test: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvLibraryLocatorTest.kt`

**Interfaces:**
- Add `AudioOutputMode.SHARED` and `DesktopSettings.audioOutputMode`, defaulting to `SHARED`.
- Locator accepts only a configured absolute `dmc.libmpv.path` or a known application-private DLL path; no PATH lookup.
- Main constructs `PlaybackCoordinator` with `LibMpvPlaybackEngine`; unavailable native library yields an engine that reports an actionable error and never falls back to JavaFX.

- [x] Write failing tests for old JSON without `audioOutputMode`, round-trip settings, preserving unrelated settings, and rejecting relative/arbitrary PATH library lookup.
- [x] Run focused settings/locator tests and verify the missing behavior causes failures.
- [x] Implement backward-compatible JSON read/write, strict native path resolution, unavailable-player error reporting, and wire libmpv as the production engine.
- [x] Preserve the current settings screen's other values when settings are saved; expose the fixed shared-output choice/status without adding exclusive mode.
- [x] Run settings, locator, and player-view-model regression tests.
- [x] Commit as `feat(player): select libmpv for production playback`.

### Task 4: Windows shared-output verification and R2 handoff

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvNativeApi.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvPlaybackEngine.kt`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/libmpv/NativeWorkerCommandQueue.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvSharedOutputAcceptanceTest.kt`
- Modify: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/LibMpvPlaybackEngineTest.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/libmpv/NativeWorkerCommandQueueTest.kt`
- Create: `docs/testing/libmpv-r2-windows-acceptance.md`
- Modify: `README.md` only if the current local-playback description says JavaFX is the production engine.

**Interfaces:**
- Add a separate opt-in `nativeMpvShared` test task requiring absolute `-PmpvLibrary` and `-PmpvArchive`; do not mix it into offline `test`.
- Acceptance records API version, DLL/archive SHA-256, `current-ao`, playback-position movement, format coverage, and whether a human confirmed audible output.

- [x] Write the opt-in native test and acceptance checklist before running it; assert active output is `wasapi`, never `null`, and progress advances for MP3, FLAC, AAC/M4A, and WAV.
- [x] Run offline focused tests and the full suite under JDK 25.
- [x] Run `nativeMpvShared` against the pinned local test DLL and available fixtures; record exact result and any environment/audio-device block.
- [x] Launch the desktop app using the production engine and local fixtures when the environment permits; distinguish app launch, control interaction, active WASAPI, and audible confirmation.
- [x] Verify final diff and R2 requirements; commit as `test(player): verify WASAPI shared playback on Windows`.

## Execution

Execute inline in the current authorized `main` workspace. Run every task test before its commit. Do not remove JavaFX, package libmpv, create an installer, or begin R3.
