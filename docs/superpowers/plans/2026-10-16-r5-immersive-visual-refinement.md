# R5 Immersive Visual Refinement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add selectable immersive-player backgrounds/materials, improve cover sharpness, and explain unavailable playback actions.

**Architecture:** Extend the existing persisted `DesktopSettings` with backward-compatible background preferences. Reuse the safe local-artwork pipeline for artwork and custom wallpaper sources, keep style rendering inside the existing Compose window, and expose player errors/disabled-seek reasons directly in the immersive surface.

**Tech Stack:** Kotlin, Compose Desktop 1.10.3, Miuix, Java AWT image processing already present in the Desktop module, existing JSON settings store and player coordinator.

**Spec:** `docs/superpowers/specs/2026-10-16-r5-immersive-visual-refinement-design.md`

## Global Constraints

- Preserve old settings files with default values; do not change the schema version.
- Do not change Android sources or add a new runtime UI/player dependency.
- Do not let artwork decode or blur block playback controls or run on progress ticks.
- Do not log or upload custom wallpaper paths.
- Do not commit, push, merge, or release.
- Do not add or run tests; run only compilation verification.

## Review Focus

- Invalid, missing, or oversized custom image safely falls back without interrupting playback.
- Low-resolution foreground cover is neither cropped nor enlarged past its source pixels.
- Missing libmpv/runtime error is visible in the immersive screen.
- Zero duration leaves seek disabled but explains why.
- Narrow window keeps queue/fullscreen/exit controls reachable.

---

### Task 1: Persist immersive background preferences

**Files:**
- Create `src/main/kotlin/com/qing/hachimi/desktop/ImmersiveBackdrop.kt` for source/material enums and pure palette/color helpers.
- Modify `src/main/kotlin/com/qing/hachimi/desktop/DownloadTask.kt` for `DesktopSettings` fields.
- Modify `src/main/kotlin/com/qing/hachimi/desktop/DownloadTaskStore.kt` for optional JSON read/write fields.

**Interfaces:**
- `enum class ImmersiveBackdropSource { ALBUM_ART, CUSTOM_IMAGE, SOLID_COLOR }`
- `enum class ImmersiveBackdropMaterial { SOFT_FOCUS, ACRYLIC, ALBUM_GRADIENT }`
- `DesktopSettings.immersiveBackdropSource`, `.immersiveBackdropMaterial`, `.immersiveBackdropImagePath`, `.immersiveBackdropSolidColor`.

- [x] Add defaults that preserve existing behavior.
- [x] Serialize fields and decode missing/invalid enum values to defaults without bumping schema.
- [x] Keep custom path local to settings only.

### Task 2: Add source-aware backdrop loading and image treatment

**Files:**
- Modify `src/main/kotlin/com/qing/hachimi/desktop/LocalArtworkRepository.kt`.
- Modify or create `src/main/kotlin/com/qing/hachimi/desktop/ImmersiveBackdrop.kt` for bounded custom-image decode and palette extraction. Keep blur as a Compose render effect over the repository-cached thumbnail; do not decode again on playback-position updates.

**Interfaces:**
- `suspend fun backdrop(track: LocalTrack, targetEdgePx: Int = 1_024): ImageBitmap?`
- `suspend fun loadCustomBackdrop(pathText: String, targetEdgePx: Int): ImageBitmap?`
- Missing or invalid images return null. Custom file reading/decoding and palette extraction run away from the UI dispatcher.

- [x] Preserve existing `thumbnail()` behavior and cache budget.
- [x] Reuse the existing thumbnail cache; render material blur without retaining a second bitmap copy.
- [x] Bound background decode size to at most 1,024 px per edge; keep the custom image path within the existing safe image validation rules.
- [x] Implement Compose soft-focus/acrylic treatment and a palette-derived gradient from the bounded decoded image.

### Task 3: Add settings preview and source selection

**Files:**
- Modify `src/main/kotlin/com/qing/hachimi/desktop/Main.kt` `SettingsScreen` and immersive screen wiring.
- Use the existing Swing file chooser pattern for local image selection, filtered to supported image formats.

**Interfaces:**
- Settings cards control the source and material enums from Task 1.
- Settings save includes all four new settings fields.
- The immersive screen receives the same persisted selection and current settings.

- [x] Add separate source selection and material preview cards for Album Fog, Acrylic, Album Gradient, Solid Stage, and Custom Wallpaper.
- [x] Add a custom-image picker and solid color swatches/hex entry.
- [x] Show missing/invalid custom wallpaper state and fallback guidance.
- [x] Preserve other settings and existing save behavior.

### Task 4: Render sharper artwork and responsive feedback

**Files:**
- Modify `src/main/kotlin/com/qing/hachimi/desktop/PlayerScreens.kt`.

**Interfaces:**
- `ImmersivePlayerScreen` and `ImmersivePlayerContent` consume background source/material/path/color from Task 1.

- [x] Render the selected background with per-style scrim and acrylic panels.
- [x] Render foreground cover with `ContentScale.Fit`, preserving aspect ratio and limiting display size using source pixel dimensions and density.
- [x] Display `PlaybackState.error` in the immersive screen.
- [x] When duration is zero, keep seeking disabled and display a short loading/unavailable explanation.
- [x] Make the top action layout adapt at compact widths so exit remains visible and other actions remain reachable by horizontal scrolling.

### Task 5: Compile and record status

**Files:**
- Update `docs/superpowers/specs/2026-10-16-r5-immersive-visual-refinement-design.md` with delivered behavior and verification status.

- [x] Run `.\gradlew.bat --no-daemon -p . compileKotlin` under the configured JDK.
- [x] Run `git diff --check` and inspect the changed-file list; preserve all pre-existing modifications.
- [x] Report compilation result and note that tests and GUI/manual acceptance were not run.

**Implementation status:** Source/settings/material behavior and responsive feedback are implemented. `compileKotlin` and `git diff --check` passed. Tests and manual GUI acceptance were not run in this task.
