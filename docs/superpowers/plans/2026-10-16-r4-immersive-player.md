# R4 Immersive Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Add an artwork-led immersive listening mode to the existing DMC window with readable synchronized lyrics and full playback controls.

**Architecture:** Keep `PlaybackCoordinator`, `PlayerViewModel`, libmpv, queue persistence, and the existing main window. Add a pure lyric-alignment model, bounded artwork backdrop retrieval through `LocalArtworkRepository`, and an immersive Compose screen; let `Main.kt` own shell visibility and window placement restoration.

**Tech Stack:** Kotlin, Compose Desktop, Miuix, kotlinx.coroutines, existing local artwork and playback modules.

**Spec:** `docs/superpowers/specs/2026-10-16-r4-immersive-player-design.md`

## Global Constraints

- No second player window, playback engine, or online lyric provider.
- Preserve the 64 MiB shared decoded-artwork cache ceiling and off-UI artwork work.
- Align translation and romanization chronologically, one-to-one, with a tolerance no greater than 500 ms; preserve unmatched lyrics.
- Keep active-line scrolling transition-driven, preserve click-to-seek and manual-scroll follow controls, and support reduced motion.
- Keep existing queue, playback, and normal-shell behavior unchanged outside immersive mode.

## Review Focus

- Missing or corrupt cover must leave lyrics and controls usable — cover failure fixture in Task 2 and screen fallback assertion in Task 3.
- Duplicate or equidistant lyric timestamps must not be paired arbitrarily — ambiguity fixtures in Task 1.
- Large progress update volume must not trigger repeated lyric scroll animation — transition-only UI test in Task 3.
- Escape while a text field owns focus must not steal normal editing behavior — focus test in Task 4.
- Fullscreen entry/exit must restore the prior window size and placement — window-controller test in Task 4.

---

### Task 1: Timestamp-based lyric grouping

**Files:**
- Create: `src/main/kotlin/com/qing/hachimi/desktop/ImmersiveLyrics.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/ImmersiveLyricsTest.kt`
- Reference: `src/main/kotlin/com/qing/hachimi/desktop/LocalLyrics.kt`

**Interfaces:**
- Consumes: `LocalLyricsBundle`, `TimedLyricLine`.
- Produces: `data class ImmersiveLyricLine(val original: TimedLyricLine, val translation: TimedLyricLine?, val romanization: TimedLyricLine?)` and `object ImmersiveLyrics { fun align(bundle: LocalLyricsBundle, toleranceMillis: Long = 500): List<ImmersiveLyricLine> }`.

- [ ] **Step 1: Write failing tests** for exact timestamp pairing, skew within 500 ms, out-of-tolerance lines, one-to-one matching, repeated text, ambiguous equal-distance candidates, empty original lyrics, and preserving every original line.
- [ ] **Step 2: Run** `.\gradlew.bat -p . test --tests "com.qing.hachimi.desktop.ImmersiveLyricsTest"` — expect failures because the model is absent.
- [ ] **Step 3: Implement** deterministic chronological matching. Attach a secondary line only when it has one unambiguous eligible original; do not reuse a secondary line. Leave uncertain or unmatched values null.
- [ ] **Step 4: Run the focused test command** — expect all grouping and ambiguity cases to pass.

### Task 2: Bounded artwork backdrop retrieval

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/LocalArtworkRepository.kt`
- Modify: `src/test/kotlin/com/qing/hachimi/desktop/LocalArtworkRepositoryTest.kt`

**Interfaces:**
- Consumes: existing `thumbnail(track, targetEdgePx)` resolver, decoder, invalidation and byte-accounting behavior.
- Produces: `suspend fun backdrop(track: LocalTrack, targetEdgePx: Int = 256): ImageBitmap?`; backdrop data uses the same path/size/mtime identity, invalidation, and bounded cache accounting.

- [ ] **Step 1: Add tests** that a backdrop is capped at 256 px by default, shares cache accounting, is reused for an unchanged file, invalidates after artwork replacement, and returns null for absent/corrupt artwork without throwing.
- [ ] **Step 2: Run** `.\gradlew.bat -p . test --tests "com.qing.hachimi.desktop.LocalArtworkRepositoryTest"` — expect the new API assertions to fail.
- [ ] **Step 3: Implement** the backdrop path through the existing safe decode pipeline; downsample to a 128–256 px long edge and apply any blur/treatment off the UI dispatcher. Use a distinct cache key if treatment differs from a thumbnail, and account every decoded pixel against `maxDecodedBytes`.
- [ ] **Step 4: Run the focused repository tests** — expect bounded-memory and invalidation cases to pass.

### Task 3: Immersive player surface and lyric hierarchy

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerScreens.kt`
- Modify: `src/test/kotlin/com/qing/hachimi/desktop/PlayerScreensTest.kt`
- Reuse: `ImmersiveLyrics`, `LocalArtworkRepository`, `PlayerViewModel`.

**Interfaces:**
- Consumes: `PlayerUiState`, existing view-model controls and artwork API.
- Produces: `@Composable internal fun ImmersivePlayerScreen(viewModel: PlayerViewModel, artworkRepository: LocalArtworkRepository?, layout: ImmersivePlayerLayout, reducedMotion: Boolean, onExit: () -> Unit)` and `enum class ImmersivePlayerLayout { COVER_AND_LYRICS, LYRICS_FIRST }`.

- [ ] **Step 1: Add Compose tests** for both layout choices, large/compact constraints, missing-cover fallback, active/original/translation/romanization hierarchy, click-to-seek, follow-resume, queue open/close, and previous/play-pause/next/seek controls. Verify progress-only updates do not restart the active-line scroll animation.
- [ ] **Step 2: Run** `.\gradlew.bat -p . test --tests "com.qing.hachimi.desktop.PlayerScreensTest"` — expect immersive tags/behavior tests to fail.
- [ ] **Step 3: Implement** the immersive surface with a contrast veil over the backdrop, foreground cover/metadata, stable lyric region with active line near the upper-middle third, bottom transport deck, secondary shuffle/repeat/volume controls, dismissible queue, layout selector, and visible exit/fullscreen affordances. Cross-fade only on track/art changes and skip it when `reducedMotion` is true.
- [ ] **Step 4: Run the focused Compose tests** — expect all interaction and layout assertions to pass.

### Task 4: Main-window entry, exit, and placement restoration

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/Main.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/PlayerScreens.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DownloadTask.kt` (persisted `DesktopSettings` model)
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DownloadTaskStore.kt` (settings JSON encoding/decoding)
- Modify: `src/test/kotlin/com/qing/hachimi/desktop/DownloadTaskStoreTest.kt`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/ImmersiveWindowState.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/ImmersiveWindowStateTest.kt`
- Modify: `src/test/kotlin/com/qing/hachimi/desktop/PlayerKeyboardShortcutsTest.kt`

**Interfaces:**
- Produces: `class ImmersiveWindowState { fun enter(currentPlacement: WindowPlacement, currentSize: DpSize); fun exit(): SavedWindowPlacement? }` with a saved placement consumed by the main window; adds a persisted `DesktopSettings.reducedMotion` preference, defaulting to `false`.

- [ ] **Step 1: Add tests** for reduced-motion JSON round-trip and default, entering/leaving from normal, maximized and fullscreen placement, repeated enter/exit, Esc with normal focus, and Esc while a text editor is focused.
- [ ] **Step 2: Run** the focused window-state and keyboard tests — expect new cases to fail.
- [ ] **Step 3: Implement** immersive visibility in the existing `Window`; hide sidebar/header/mini-player only while active, keep a regular-page and mini-player entry, expose a visible exit button, restore saved placement/size, route Esc through existing focus-safe shortcut behavior, and add a reduced-motion toggle to Settings backed by the existing JSON settings store.
- [ ] **Step 4: Run** `.\gradlew.bat -p . test --tests "com.qing.hachimi.desktop.ImmersiveWindowStateTest" --tests "com.qing.hachimi.desktop.PlayerKeyboardShortcutsTest" --tests "com.qing.hachimi.desktop.PlayerScreensTest"` — expect the targeted suite to pass.

### Task 5: R4 integrated verification and acceptance notes

**Files:**
- Modify: `docs/superpowers/specs/2026-10-16-r4-immersive-player-design.md` (record delivered behavior and remaining manual checks)
- Verify: all R4 source and test files above.

- [ ] **Step 1: Run the full automated suite** with `.\gradlew.bat --no-daemon -p . test` — record passed/failed/skipped counts and retain all prior tests.
- [ ] **Step 2: Run** `.\gradlew.bat --no-daemon -p . run` and inspect the actual Windows UI at 100%, 150%, and 200% scaling, wide/compact/short windows, with embedded/sidecar/missing/corrupt art and aligned/unmatched lyric variants.
- [ ] **Step 3: Verify** enter/exit while playing and paused does not change playback position or queue; verify controls, contrast, focus, reduced motion, and no progress-tick scroll jitter.
- [ ] **Step 4: Record** which checks were performed in a real window and which remain blocked; do not label a GUI check passed from compilation or Compose unit tests alone.
