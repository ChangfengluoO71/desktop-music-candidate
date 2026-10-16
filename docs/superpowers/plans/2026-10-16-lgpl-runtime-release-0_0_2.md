# DMC LGPL Runtime and 0.0.2 Stable Release Plan

> **For agentic workers:** Implement this plan task by task in the current session. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prepare a verifiable, replaceable Windows libmpv runtime with its license/source materials, then publish DMC `0.0.2` as a stable release that the installed `0.0.1` preview can upgrade to.

**Architecture:** Keep libmpv as an external native library under the installer's private `libmpv` folder, which the existing locator already searches. Build from the stable mpv 0.41.0 source and a pinned LGPL-only FFmpeg 8.0.3 source tree, pin every other dependency, document the complete linked component set and source route, and make the installer smoke test reject packages missing runtime or notices. Publish only after the exact artifact, license texts, corresponding sources, and library replacement path have been verified.

**Tech Stack:** GitHub Actions, PowerShell, Inno Setup 6, Gradle/JDK 25, JNA/libmpv, GitHub Releases.

**Spec:** `docs/superpowers/specs/2026-10-13-libmpv-playback-design.md` (R3 distribution and licensing gate).

## Global Constraints

- Do not recreate or overwrite the deleted `v0.0.0` or `v0.0.1` preview tags.
- Build the first stable upgrade as `0.0.2`; the existing updater rejects versions less than or equal to installed `0.0.1`.
- Do not redistribute the local `shinchiro` development DLL.
- Do not publish until the exact native binary's linked components, licenses, source, and replacement method are accounted for.
- Keep app data and the installed native library outside the uninstall-owned user data; uninstall must preserve `%APPDATA%\DesktopMusicCandidate`.
- Preserve the existing updater asset name and SHA-256 checks.

## Review Focus

- Windows x64 package starts with no system mpv and finds only `{app}\\libmpv\\libmpv-2.dll`.
- Every native dependency needed by that DLL is shipped or provided by Windows, and every shipped component has a license notice.
- The build record resolves native source inputs to immutable commits and SHA-256 values, and records the GitHub runner image and exact MSYS2 package versions.
- The released library is separately replaceable without changing the DMC application image.
- An installed preview build at `0.0.1` discovers stable `0.0.2`, downloads the correctly named installer, and hands it to Inno Setup.

---

### Task 1: Complete the native runtime provenance and license audit

**Files:**
- Create or update: `docs/testing/libmpv-runtime.md`
- Generate in the release payload: `{app}/libmpv/runtime-manifest.json`
- Create/update: `THIRD_PARTY.md`, `LICENSES/`
- Evidence: pinned upstream source trees, build workflow logs, archive contents, binary dependency inspection

**Interfaces:**
- Consumes: the candidate runtime archive and its published digest.
- Produces: an immutable release manifest naming the source commit, build workflow/run, hosted-runner image, FFmpeg commit/configuration, source/DLL hashes, every non-Windows linked dependency, license identifier/version, source URL, and redistribution notice.

- [ ] Record the exact archive and DLL hashes, archive contents, machine architecture, API version, and native import/dependency list.
- [ ] Resolve all source/build inputs to immutable revisions; do not use a moving `latest`/`master` reference as the provenance record. FFmpeg 8.0.3's Meson port is pinned at `74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9`, based on upstream FFmpeg `n8.0.3` commit `8ae0b34901ba60a802f183ee75a250a9fc3e09a5`. The build matches the Winpthreads source package to the installed MSYS2 package version and includes it in the release source bundle.
- [ ] Reconcile the archive's included license text with upstream release notes and the exact FFmpeg configuration.
- [ ] Add the corresponding source archives, notices, and library-replacement instructions only when the complete component list is established. Keep Winpthreads separate and replaceable; include its source package and MIT/BSD notices, plus the GCC runtime exception notice for static compiler helpers.
- [ ] Mark the runtime gate BLOCKED if any linked component lacks a verifiable source or license record; do not advance to release packaging in that case.

### Task 2: Make the installer carry the audited runtime and license materials

**Files:**
- Modify: `installer/setup.iss`
- Modify: `scripts/smoke-test-windows-installer.ps1`
- Create/update: `scripts/build-release-libmpv.sh`
- Modify: `.github/workflows/desktop-ci.yml`

**Interfaces:**
- Consumes: Task 1's immutable manifest and verified source bundle.
- Produces: `build/release-libmpv/` with the runtime DLLs, runtime manifest, required notices, and source materials; Inno installs the runtime in `{app}\\libmpv` and legal/source materials in `{app}\\licenses`.

- [ ] Extend the installer smoke test first to assert the required native DLL, manifest, source bundle, license notices, and adjacent native dependencies.
- [ ] Run the smoke test against the already-built no-mpv candidate and confirm it fails on the missing runtime.
- [ ] Stage release-only binaries from the pinned source and verify the archive hash before extraction; keep the local-development provisioning script separate.
- [ ] Add the verified release files to Inno Setup with an explicit release-only define.
- [ ] Verify install, launcher presence, uninstaller, custom install directory, and user-data preservation in the updated smoke test.

### Task 3: Satisfy the Java LGPL source and replacement requirement

**Files:**
- Modify: `.github/workflows/desktop-ci.yml`
- Modify: `installer/setup.iss`
- Modify: `scripts/smoke-test-windows-installer.ps1`
- Update: `THIRD_PARTY.md`

**Interfaces:**
- Consumes: `net.jthink:jaudiotagger:3.0.1` from the locked runtime dependency graph.
- Produces: the exact jaudiotagger 3.0.1 source JAR, its LGPL notice, and evidence that the installed application keeps the library as a separate replaceable JAR on its classpath.

- [ ] Add smoke assertions for the source JAR, license text, installed dependency JAR, and classpath entry.
- [ ] Run the modified smoke test against the current candidate and confirm it reports the missing source/relink materials.
- [x] Stage the matching source JAR and documents from the immutable Maven coordinate/checksum. The Maven source JAR SHA-256 is `D9C79A145944E6BC37579403843C9DA84CD81459E42C9877351802EE284F5858`.
- [x] Keep the dependency JAR independently replaceable in the installed `app` directory and document the generated filename pattern.

### Task 4: Verify the release candidate end to end

**Files:**
- Modify: `.github/workflows/desktop-ci.yml`
- Modify: `scripts/smoke-test-windows-installer.ps1`
- Update: release and installation docs

**Interfaces:**
- Consumes: audited runtime bundle and updated installer.
- Produces: a stable-channel `0.0.2` installer artifact and CI evidence for tests, native decoding, clean installation, and launcher startup.

- [x] Run the full JDK 25 suite with `--rerun-tasks`.
- [ ] Run native `libmpv` tests against the staged release DLL for MP3, FLAC, M4A/AAC, WAV, and failure cases.
- [ ] Run the installer smoke test and installed application startup smoke test; require no PATH or system mpv installation.
- [x] Build the application image with `release_version=0.0.2`, `update_channel=stable`; native runtime and Inno packaging remain for the gated Windows workflow.
- [x] Confirm from updater tests that preview channel `0.0.1` accepts stable `0.0.2`, while stable selection excludes prereleases.

### Task 5: Commit and publish stable `v0.0.2`

**Files:**
- Commit all reviewed source, installer, workflow, and documentation changes.
- Create GitHub Release `v0.0.2` only after Task 4 passes.

**Interfaces:**
- Consumes: successful Task 4 artifact and verified source/license assets.
- Produces: public stable `v0.0.2`, Inno Setup installer, corresponding source bundle, and update instructions for the installed `0.0.1` preview.

- [ ] Recheck clean worktree and push the reviewed commit to `main`.
- [ ] Run the workflow with `release_version=0.0.2`, `update_channel=stable`, and publication enabled. The publish job depends on every native, test, and installer gate.
- [ ] Verify the Release is stable, has the expected installer/source assets and digests, and is recognized by the updater.
- [ ] Explain the in-app upgrade path and manual same-directory fallback, including that app data is preserved.
