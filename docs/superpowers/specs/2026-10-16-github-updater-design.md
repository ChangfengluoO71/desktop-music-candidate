# GitHub Release Update Design

**Status: Implemented; automated suite and Compose EXE build verified; Inno installer and real upgrade acceptance blocked**
**Scope:** Public GitHub Release discovery, compact background download status, integrity verification, and handoff to the existing Inno Setup installer. This document defines product behavior and security boundaries; it is not an implementation plan.

## 1. Goal

Let DMC find and download a newer Windows installer without interrupting playback. The user can inspect progress in a compact status indicator, open update details when convenient, and explicitly confirm installation through the standard Windows/Inno Setup flow.

## 2. Implementation status and acceptance boundary

- The public update endpoint is fixed to `ChangfengluoO71/desktop-music-candidate`; checks are unauthenticated and use a fixed release asset contract.
- Gradle now generates runtime `BuildInfo` from the release version, update channel, and source commit inputs. The verified local build reports `0.1.2` / `preview` / `a4d8a15a638b`.
- Release discovery, compact background status, persistent updater preferences, verified staging, and explicit orderly Inno handoff are implemented with offline/local fixtures and Compose tests.
- The current Inno setup keeps its existing `AppId`, selectable install-directory page, and app-data identity. Installer behavior is covered by source-contract tests.
- The working tree is not code-signed. SHA-256 checks do not remove Windows UAC or SmartScreen warnings.
- The local `packageExe` task succeeded, but its output is a Compose EXE, not an Inno installer. No Inno Setup compiler is installed or discoverable on this machine. Consequently the actual installer build, UAC path, and old-to-new install/upgrade were not run and remain a separate acceptance blocker.

## 3. Version and channel contract (U0)
- Gradle release version is the single version source for runtime `BuildInfo`, installer `AppVersion`/filename, release tag, and the About/update UI.
- Generate `BuildInfo` at build time with at least semantic version, update channel (`preview` or `stable`), and commit SHA. Avoid a source-code constant that CI cannot override.
- CI must fail if `BuildInfo.version`, the requested release version, `v<version>` release tag, and Inno version differ.
- Preview builds can receive published preview releases. Stable builds only receive non-prerelease releases. Release selection is based on the build's channel; a stable build must not silently move to a preview channel.
- Compare parsed semantic versions, including prerelease identifiers. Never downgrade when a release version is equal to or lower than the installed version.
- The current public preview release is `v0.0.1`, while current source runtime defaults to `0.1.2`. It cannot be offered as an upgrade to a `0.1.2` build. The next release version must follow the current installed/source baseline rather than reusing an older tag.

## 4. Discovery and download behavior

### 4.1 Check policy

- On startup, check asynchronously at most once per 24 hours. Playback, search, and local-library operations must remain responsive if GitHub is slow or unavailable.
- Add a manual **检查更新** action in Settings/About.
- Cache the last result and GitHub ETag. A manual check may revalidate immediately. Respect rate-limit reset/retry headers and do not retry rapidly after `403`/`429`.
- Automatic checks use only unauthenticated public Release metadata. Do not embed GitHub credentials or request account access.

### 4.2 Non-interrupting status and compact progress

- A found update does not change the current page, pause audio, or open a modal.
- With **自动下载更新包** enabled (default on for the preview channel), download in the background. The setting can be disabled; the user can still start a manual download from update details.
- Show a compact status item in the existing main-window header/toolbar area: short text such as `更新 42%` plus a thin progress indicator. Do not add a permanent panel or reduce the player/library content area with a large card.
- Clicking the status opens a concise update detail panel/page with current/new version, release notes, exact package size, progress, cancel/retry, and later installation actions.
- On completion and successful verification, change the status to **更新已就绪**. Installation is never automatic. The user can choose **立即安装** or **稍后**; playback continues until installation begins.
- Throttle progress UI updates (for example, no more than 5 updates per second) while retaining byte-accurate final verification.

### 4.3 UI state model

`IDLE → CHECKING → UP_TO_DATE | UPDATE_AVAILABLE → DOWNLOADING → VERIFYING → READY_TO_INSTALL → INSTALL_HANDOFF`

Any network, parse, size, hash, or installation-launch error becomes `FAILED` with a safe retry path. Cancelling a download removes its partial file and returns to the available-update state; a subsequent attempt starts a new staged download. Update errors must not affect playback or ordinary application navigation.

## 5. Release selection and trust checks

- Read the fixed public repository's Releases collection, not an arbitrary URL supplied by release metadata. Filter out drafts and releases disallowed by the active channel.
- Select only the exact expected x64 Inno installer asset name `DesktopMusicCandidate-<version>-Setup.exe`; reject source archives, legacy names, wrong platform assets, and ambiguous duplicate candidates.
- Require an HTTPS `browser_download_url` associated with the fixed repository's release and validate every redirect against a reviewed GitHub asset-host allowlist. Reject HTTP downgrade and unapproved hosts.
- Require a positive expected asset size and a `sha256:` digest from GitHub Release asset metadata. If size or digest is missing, malformed, or mismatched, do not mark the package installable.
- Stream to a fixed-name per-user staging directory under the current product's local application data. Write to a partial file, verify exact byte length and SHA-256, then atomically rename to the installer filename. Do not use the server-provided filename as a path.
- Do not pass Cookie, MUSIC_U, GitHub tokens, or account credentials to the API or asset CDN. Never persist or log signed download URLs.
- Hash verification confirms bytes match the digest returned by GitHub over HTTPS; it is not independent publisher authentication. Keep code signing as a separate release-security gate.

## 6. Installation handoff (U2)

- **立即安装** opens the standard Inno Setup installer with its normal UI and Windows UAC. Do not use silent switches, bypass UAC/SmartScreen, force-kill DMC, or replace the installer system.
- Before handoff, reuse the orderly application shutdown path: pause and persist downloads, save the local playback queue, stop playback and release libmpv, close other app services, and release the single-instance lock.
- Use an updater-specific shutdown reason so the normal exit-confirmation dialog cannot deadlock the installer. Handle an installer/UAC cancellation distinctly; keep the application data and verified package available and let the user reopen or retry.
- Preserve the current Inno `AppId` and app-data identity. The same `AppId` allows Inno Setup's default previous-directory lookup to present the installed directory, but real upgrade tests remain mandatory.
- The app must not claim an update succeeded from the installer process exit code alone. On the next normal launch, compare the runtime `BuildInfo.version` with the pending target and mark the update complete only when they match.
- Do not automatically delete local databases, download history, credentials, user audio, or prior update diagnostics. Do not claim transactional rollback until it has a separately tested implementation.
- The current setup has `RestartApplications=no`; define and test the exact post-install launch behavior rather than assuming that Inno will relaunch DMC automatically.

## 7. Failure handling

Expose distinct user-safe categories for offline/timeout, GitHub rate-limited, no matching release asset, invalid metadata, insufficient disk space, download cancelled, size mismatch, digest mismatch, installer launch denied, UAC cancelled, installer failed, and post-install version mismatch.

No failure text or logs may include tokens, signed asset URLs, local credential values, or full HTTP response bodies. A failed or unverified download is never executed.

## 8. Acceptance gates

### U0 — version/release contract

- Generated runtime version/channel/commit match Gradle, Inno version, and tag in preview and stable workflow runs.
- SemVer/prerelease comparison covers equal, older, newer, and malformed versions without downgrades.
- Preview releases are found by channel-aware collection filtering; stable builds ignore previews.

### U1 — discovery and compact progress

- Offline mock Releases API covers no-update, preview, stable, draft, missing asset, duplicate asset, bad metadata, and rate-limit responses.
- A new release triggers background download while the current page and playback remain unchanged.
- Compact progress is accurate, throttled, cancelable, retryable, and does not resize or obscure the mini-player/content area.

### U2 — integrity and staging

- Local HTTP test server covers streamed download, redirect validation, cancellation, timeout, disk/write failure, wrong size, malformed/missing digest, and digest mismatch.
- Valid packages are atomically staged and only become runnable after hash verification.
- Staging path remains per-user and fixed; repository metadata cannot select an arbitrary executable path.

### U3 — Windows installer handoff

- On a test installation, verify an upgrade from an older actual Inno Setup package into a newer package in the same custom install directory.
- Verify download history, local database, preferences, playback queue, credential-store target, and user music remain intact.
- Exercise UAC approval/cancellation, installer cancellation/failure, application shutdown, single-instance lock release, and explicit post-install launch behavior.
- After install/relaunch, verify runtime version equals the release version and the pending update is cleared only after that check.

### Release security gate

- Review the actual packaged files and dependency notices, verify signing status and publisher identity, and document remaining SmartScreen/UAC prompts. Signing can improve publisher reputation over successive releases but is not a guarantee that all warnings disappear.

## 9. Out of scope

- A self-updating service, silent installation, privilege escalation, arbitrary URL downloads, GitHub authentication, automatic downgrade, alternate package manager, MSI migration, auto-rollback, and changing the current app identity.

## 10. Suggested delivery slices

1. U0: generated BuildInfo, version/channel contract, and release workflow validation.
2. U1: GitHub release client and version selection with offline fixtures.
3. U2: compact non-blocking status, background download, cancellation, and digest verification.
4. U3: controlled Inno handoff and real Windows in-place upgrade acceptance.


## 11. Execution results and remaining acceptance

- JDK 25 full suite: **356 tests passed, 0 failures, 0 errors, 0 skipped** (`test --rerun-tasks`).
- Compose EXE build: passed at `build/compose/binaries/main/exe/DesktopMusicCandidate-0.1.2.exe`; 88,340,992 bytes; SHA-256 `E4A82D3DF72D54DBD740A2757DBD26BEF95125690D7DEB093DED80E128F54B8A`.
- Release selection, staging integrity, cancellation/redirect behavior, preferences, compact UI state, and installer handoff contracts are covered by local automated tests; these results are not a live release or installer upgrade test.
- `ISCC.exe` was not found in PATH or the standard Inno Setup 6 paths. Inno compilation, installation, UAC approval/cancellation, real upgrade, and restart verification are **BLOCKED**.
- No commit, push, merge, or release was performed as part of implementation.