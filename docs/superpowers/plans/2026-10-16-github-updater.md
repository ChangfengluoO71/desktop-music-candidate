# GitHub Release Updater Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Detect and stage a verified GitHub Release installer in the background, show compact progress without interrupting playback, and install only after an explicit user action.

**Architecture:** Generate runtime build metadata from Gradle's version/channel/commit inputs; use a fixed-repository release client and a coroutine-backed update manager; stream only the exact expected Inno x64 asset into per-user staging and verify size plus GitHub SHA-256 digest; expose progress in the current shell and use an explicit, orderly Inno handoff.

**Tech Stack:** Kotlin, Gradle Kotlin DSL, OkHttp 5.3.2, kotlinx.coroutines, org.json, Compose Desktop, Inno Setup.

**Spec:** `docs/superpowers/specs/2026-10-16-github-updater-design.md`

## Global Constraints

- Do not embed credentials, accept arbitrary release URLs, or send session credentials to GitHub/CDN.
- Preview builds may select preview releases; stable builds may select stable releases only; never downgrade.
- Require exact `DesktopMusicCandidate-<version>-Setup.exe`, positive expected size, valid `sha256:` digest, HTTPS, and reviewed redirect-host validation before install readiness.
- Keep downloads in a fixed per-user staging directory, use `.part` plus verified atomic rename, and never execute incomplete/unverified bytes.
- Background checking/downloading must not navigate, pause playback, or show a modal; only the user can initiate installation through normal Inno/UAC UI.
- Keep current `AppId`, data identity, install directory behavior, and orderly shutdown protections.

## Review Focus

- Preview/stable channel mismatch or older/equal release must not be offered — SemVer/channel table in Task 1 and release fixtures in Task 2.
- GitHub 403/429 must not cause a rapid retry loop — rate-limit fixture in Task 2.
- Redirects to HTTP, unknown hosts, or non-installer assets must be rejected — redirect/asset fixtures in Task 3.
- A digest/size mismatch must never become installable — partial-file assertions in Task 3.
- Installer cancellation or a failed relaunch must not erase user data or falsely mark success — handoff state tests and Windows acceptance in Task 6.

---

### Task 1: Runtime BuildInfo and release version contract

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DesktopProductIdentity.kt`
- Generate: `build/generated/sources/buildInfo/kotlin/com/qing/hachimi/desktop/BuildInfo.kt` from Gradle (do not commit generated output)
- Modify: `src/test/kotlin/com/qing/hachimi/desktop/DesktopProductIdentityTest.kt`
- Modify: `.github/workflows/desktop-ci.yml`
- Modify: `installer/setup.iss`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/SemVer.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/SemVerTest.kt`

**Interfaces:**
- Produces: generated `object BuildInfo { const val VERSION: String; const val CHANNEL: String; const val COMMIT: String }` and `SemVer.parse(value: String): SemVer?`, `SemVer.compareTo(other: SemVer): Int`.

- [x] **Step 1: Write failing tests** for SemVer numeric/prerelease ordering, malformed values, equality/older/newer checks, channel values, and identity reading generated values rather than a source constant.
- [x] **Step 2: Run** `.\gradlew.bat -p . test --tests "com.qing.hachimi.desktop.SemVerTest" --tests "com.qing.hachimi.desktop.DesktopProductIdentityTest"` — expect missing type and mismatched source version failures.
- [x] **Step 3: Implement** Gradle generated source using `releaseVersion`, explicit `updateChannel` (`preview` or `stable`), and current commit SHA; make `DesktopProductIdentity.VERSION` delegate to it. Make CI assert tag, Gradle, BuildInfo, and Inno version agree and make Inno receive the same version input.
- [x] **Step 4: Run focused tests** and `.\gradlew.bat -p . tasks --all` — expect generated metadata to compile and task wiring to be visible.

### Task 2: Fixed-repository GitHub release discovery

**Files:**
- Create: `src/main/kotlin/com/qing/hachimi/desktop/update/GitHubReleaseClient.kt`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/update/UpdateRelease.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/update/GitHubReleaseClientTest.kt`
- Reuse: existing OkHttp and org.json dependencies.

**Interfaces:**
- Produces: `data class UpdateRelease(version: SemVer, prerelease: Boolean, notes: String, asset: InstallerAsset)`; `data class InstallerAsset(name: String, url: HttpUrl, size: Long, sha256: String)`; `suspend fun check(etag: String?): ReleaseCheckResult`.

- [x] **Step 1: Add local-server fixtures** for newer/no update, preview/stable selection, draft, wrong/duplicate asset, missing size/digest, malformed version, 304/ETag, timeout, 403, and 429 with retry headers.
- [x] **Step 2: Run** `.\gradlew.bat -p . test --tests "com.qing.hachimi.desktop.update.GitHubReleaseClientTest"` — expect API/fixture tests to fail before implementation.
- [x] **Step 3: Implement** unauthenticated fixed-repository Releases collection parsing; select only allowed channel and exact asset name; use SemVer to reject equal/older versions; require valid HTTPS URL, positive size, and SHA-256 digest; retain ETag and bounded retry-after data without rapid automatic retry.
- [x] **Step 4: Run focused tests** — expect every release selection and error-category fixture to pass.

### Task 3: Background update state machine and verified installer staging

**Files:**
- Create: `src/main/kotlin/com/qing/hachimi/desktop/update/UpdateManager.kt`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/update/InstallerStager.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/update/InstallerStagerTest.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/update/UpdateManagerTest.kt`

**Interfaces:**
- Produces: `sealed interface UpdateState` with `Idle`, `Checking`, `UpToDate`, `Available`, `Downloading(bytesReceived,totalBytes)`, `Verifying`, `ReadyToInstall(path,release)`, and safe `Failed(category)` states; `StateFlow<UpdateState>`; `check(manual: Boolean)`, `download()`, `cancelDownload()`, `retry()`.

- [x] **Step 1: Add tests** using a local HTTP server for byte progress, cancellation, timeout, disk/write failure, redirect allowlist and HTTPS-only behavior, size mismatch, missing/malformed digest, digest mismatch, atomic final rename, and no credentials in requests/log output.
- [x] **Step 2: Run** the new staging and manager tests — expect missing implementation failures.
- [x] **Step 3: Implement** a cancellable streamed download into fixed per-user `.part` staging, validate every redirect, enforce declared byte count and SHA-256, then atomically rename. Drive explicit update states on a supervised scope so update errors do not cancel playback or the shell.
- [x] **Step 4: Run the focused staging/manager suite** — expect verified packages only to reach `ReadyToInstall`.

### Task 4: Compact background status, details, and settings integration

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/Main.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/Main.kt` (`HachimiDesktopApp` header and `SettingsScreen`)
- Create: `src/main/kotlin/com/qing/hachimi/desktop/update/UpdateScreens.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/update/UpdateScreensTest.kt`
- Create: `src/main/kotlin/com/qing/hachimi/desktop/update/UpdaterPreferencesStore.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/update/UpdaterPreferencesStoreTest.kt`

**Interfaces:**
- Consumes: `UpdateManager.state` and its check/download/cancel/retry actions.
- Produces: compact header status (`鏇存柊 42%` with a thin progress indicator), click-to-open update details, and `UpdaterPreferences(autoDownloadPreview: Boolean, lastCheckEpochMillis: Long, etag: String?)` persisted in a dedicated per-user app-data JSON file; preview auto-download defaults to enabled.

- [x] **Step 1: Add store and Compose tests** for preference/ETag/last-check round-trip, compact/ready/error labels, throttled progress rendering, details open/close, cancel/retry, and ensuring current route/mini-player visibility is unchanged when an update appears.
- [x] **Step 2: Run** `.\gradlew.bat -p . test --tests "com.qing.hachimi.desktop.update.UpdateScreensTest"` — expect update UI cases to fail.
- [x] **Step 3: Implement** a small toolbar/header status element, concise version/release-note/package detail surface, manual check in Settings/About, automatic once-per-24-hours startup check, persisted ETag/last-check state, and configurable preview auto-download preference in the dedicated updater preferences store. Throttle Compose progress updates to at most 5 per second while retaining byte-accurate final checks.
- [x] **Step 4: Run focused UI tests** — expect update status to remain compact and not change playback/page state.

### Task 5: Explicit Inno Setup handoff and pending-version confirmation

**Files:**
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/Main.kt`
- Modify: `src/main/kotlin/com/qing/hachimi/desktop/DesktopViewModel.kt` only if shutdown orchestration belongs there after code inspection
- Create: `src/main/kotlin/com/qing/hachimi/desktop/update/InstallerHandoff.kt`
- Create: `src/test/kotlin/com/qing/hachimi/desktop/update/InstallerHandoffTest.kt`
- Modify: `installer/setup.iss`

**Interfaces:**
- Produces: `InstallerHandoff.start(path: Path): HandoffResult` called only by the explicit install button, with shutdown reason `UPDATE_INSTALL` and pending target version persisted before launch.

- [x] **Step 1: Add tests** for invalid/unverified package rejection, explicit-only launch, app-shutdown callback order (persist/pause tasks, save queue, close player/services, release lock), launch failure, user cancellation, and pending target retained until next launch verifies matching `BuildInfo.VERSION`.
- [x] **Step 2: Run** the handoff tests — expect state and launch assertions to fail before implementation.
- [x] **Step 3: Implement** normal Inno UI/UAC launch, no silent switches and no process kill; use an update-specific close reason to bypass only the ordinary exit-confirmation dialog while running the normal orderly close path. Keep the verified installer and app data after canceled/failed launch. Clear pending state only after next startup confirms target version.
- [x] **Step 4: Run focused tests** and verify setup retains the stable `AppId`, custom install-directory behavior, and current app-data identity.

### Task 6: End-to-end updater and Windows upgrade acceptance

**Files:**
- Modify: `.github/workflows/desktop-ci.yml` (release-channel assertions if required)
- Modify: updater spec with actual results and unresolved limitations.

- [x] **Step 1: Run the full automated suite** with `.\gradlew.bat --no-daemon -p . test` and the package build with `.\gradlew.bat --no-daemon -p . packageExe`; record counts and exact artifacts.
- [x] **Step 2: Run** the local mock-release workflow in preview and stable configurations; verify no real GitHub credentials or music-service credentials are needed.
- [ ] **BLOCKED — Step 3:** Real older-to-newer Inno installation, UAC approval/cancellation, and pending-version clearing were not run because this machine has no Inno Setup compiler or newly built installer. Do not infer upgrade acceptance from `packageExe`.
- [x] **Step 4: Report** automated results separately from real Windows install/upgrade results, note signing/SmartScreen limits, and do not claim upgrade success without the old-to-new package test.


## Execution status (2026-10-16)

- Tasks 1–5 implemented on `main`; generated build identity is `0.1.2` / `preview` / source HEAD `a4d8a15a638b`.
- Full JDK 25 regression: **356 tests, 356 passed, 0 failures, 0 errors, 0 skipped** (`test --rerun-tasks`).
- `packageExe` succeeded: `build/compose/binaries/main/exe/DesktopMusicCandidate-0.1.2.exe`, 88,340,992 bytes, SHA-256 `E4A82D3DF72D54DBD740A2757DBD26BEF95125690D7DEB093DED80E128F54B8A`.
- This artifact is the Compose EXE output, not an Inno installer. `ISCC.exe` is absent from the standard Inno Setup locations and is not on PATH, so the new installer and real in-place upgrade remain blocked.
- No commit, push, merge, or release was performed. Existing R4 and other workspace changes remain untouched.