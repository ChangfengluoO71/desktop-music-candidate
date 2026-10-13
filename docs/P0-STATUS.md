# Independent product foundation P0 status

Date: 2026-10-12

## Original worktree

- Original repository: `work/Hachimi`, branch `main`, HEAD `fafbc5e12ef4ce123a7e36a6a9e9d4553109ef91`.
- Initial and final original status: `M gradle/wrapper/gradle-wrapper.properties`, `?? desktop/`, `?? graphify-out/`; no other changes were made in that worktree.
- `origin` was left unchanged. No original source, test fixture, build output or `graphify-out/` file was modified or removed.

## Candidate

- Path: sibling directory `work/desktop-music-candidate`.
- It has its own Gradle build, wrapper and package identity. No `../app/` build dependency exists.
- All 71 original Desktop source/test/fixture files are present; none are missing. The candidate adds three byte-identical JVM API files, `DesktopProductIdentity.kt` and its test (76 total files under `src/`).
- The root MIT license is byte-identical to the source project's license. No Android UI, restricted icon or local account/database/log/download data was copied.
- The candidate has not been `git init`-initialized and has no remote or commit.

## Verification

| Check | Result | Evidence |
|---|---|---|
| Independent Kotlin/JVM source compilation | PASS | Full test run reached and passed `compileKotlin`; test compilation also passed. |
| Candidate identity test | PASS | Test-first cycle: it failed on the missing identity type, then passed after the implementation. |
| Full Desktop automated tests | PASS | JDK Temurin 25.0.2, Gradle 9.3.1; 206 tests completed, 206 passed, 0 failed, 0 skipped. |
| Windows EXE package | PASS | `.\gradlew.bat --no-daemon packageExe`; Gradle 9.3.1 completed successfully. |
| Runtime image | PASS at build stage | jpackage created a Java 25 runtime image with `lib/modules`, `java.dll`, and 51 runtime legal-notice files. |
| Windows install | PARTIAL | User completed per-user installation of v0.1.0. Uninstall registry entry and `C:\Users\cfl\AppData\Local\DesktopMusicCandidate\` were found, including a bundled Java runtime. No Start Menu entry or shortcut was present. |
| Installed app GUI | BLOCKED | `list_apps` exposed a window after launch, but Computer Use window capture failed twice with `foreground window did not report a process id`; no controls were operated. |
| Start Menu packaging | FIXED IN 0.1.1, NOT REINSTALLED | Missing Windows `menuGroup` caused the absent Start Menu entry. Candidate packaging now sets `menuGroup = "Desktop Music Candidate"`; rebuilt installer is ready for upgrade verification. |
| Uninstall and upgrade | NOT RUN | Uninstall was not performed. The v0.1.1 installer has not yet been installed over v0.1.0. |
| Live login/download | NOT RUN | No credentials or real network account were used. |
| GitHub CI | PREPARED, NOT RUN | A workflow exists, but no remote repository was created. |

### Test behavior alignment

The candidate retains every test. Three assertions/steps were aligned with already implemented product behavior and retry timing:

- Playlist selection is empty by default; the test now expects an empty selection.
- Collection preview is not implicitly selected; the test explicitly selects all resolved items before confirming enqueue.
- The unsafe URL diagnosis test allows the bounded 1s + 2s + 4s automatic retry backoff and asserts all three retries occurred before the final failure.

No production behavior was changed to obtain this test result.

## Installer artifact

- `build/compose/binaries/main/exe/DesktopMusicCandidate-0.1.1.exe`
- Size: 79,448,064 bytes
- SHA-256: `D0BE64EFD10641806D0B08CD325AD3E9C5B6987A3E125EC68CFF646BCAA9E8C2`
- Candidate `upgradeUuid`: `e1b24d8d-f6f8-429b-9f56-7d0c37c8f2b5`.
- The v0.1.1 package build succeeded. The installed v0.1.0 package remains in place pending upgrade acceptance.

## Persistent-data and account boundary

P0 changes no download JSON schema, collection JSON schema or local-library SQLite schema. The candidate points to its own `DesktopMusicCandidate` app-data directory and Credential Manager target, so it neither reads nor rewrites the old profile. No migration is performed. Existing local files remain outside the app-data root.

## License and repository preparation

- Runtime license snapshot: 119 resolved components; build/plugin snapshot: 27 components. Both inventories report no unverified license metadata after recording marker artifacts against their implementations and resolving ZXing from its upstream license statement.
- Apache-2.0, LGPL-2.1, MIT, GPL-2.0 and Classpath Exception reference texts are in `LICENSES/`; the generated runtime image also contains its 51 per-module legal notices.
- Jaudiotagger's LGPL replace/relink and source-distribution obligations still require a release-specific packaging review.
- Private GitHub repository proposal, file exclusions, CI workflow and local Git-init checklist are prepared. No repository, commit, remote, push or release was created.

## 0.1.2 installed JVM startup repair

- Root cause: the 0.1.1 minimized runtime omitted `java.sql`. `jdeps` reports that the application JAR requires `java.sql`, and a launch constrained to the shipped module list reproduced `NoClassDefFoundError: java/sql/SQLException` while initializing the local music store.
- Fix: `nativeDistributions.modules("java.sql")` now adds the JDBC module to the jlink runtime. The upgrade UUID remains `e1b24d8d-f6f8-429b-9f56-7d0c37c8f2b5`; package and product version advanced to 0.1.2.
- JDK 25.0.2 / Gradle 9.3.1 verification: 206 tests passed, 0 failed, 0 skipped. `packageExe`, `packageMsi`, and `createDistributable` succeeded. The freshly generated runtime `release` lists `java.sql` and the installed runtime is Java 25.0.2.
- Installed v0.1.2 via MSI to `%LOCALAPPDATA%\\DesktopMusicCandidate`; Windows reports installation status 0 and version 0.1.2. The Start Menu shortcut targets the installed executable. Launching that executable produced a responding `Desktop Music Candidate` main window process, confirming the installed JVM starts.
- The Computer Use screenshot bridge still fails with `foreground window did not report a process id`, so visual screenshot inspection is unavailable; Windows process metadata confirms the window title and nonzero HWND. The installed app remains open for user inspection.
- Uninstall/upgrade behavior was not retested in this startup-fix pass.
