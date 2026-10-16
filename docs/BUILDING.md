# Building and testing

## Requirements

- JDK 25 is the verified build and package runtime for DMC. The application source/bytecode target remains Java 17; Miuix desktop runtime and native packaging require a newer build JDK.
- The public Windows setup executable uses Inno Setup 6 and includes the app image and its bundled Java runtime. Building that installer locally requires the Inno Setup compiler (`ISCC.exe`).
- Gradle's `packageExe` and `packageMsi` tasks remain available for packaging experiments; GitHub preview releases use the Inno Setup installer instead.
- Gradle dependencies are resolved from Maven Central and Google Maven. Tests use local fixtures and do not require a NetEase account or live network credentials.

## Commands

From this directory in Windows PowerShell:

```powershell
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon run
.\gradlew.bat --no-daemon createDistributable
choco install innosetup --yes --no-progress
& "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" installer\setup.iss "/DMyAppVersion=0.0.1"
.\scripts\smoke-test-windows-installer.ps1 -InstallerPath .\build\installer\DesktopMusicCandidate-0.0.1-Setup.exe
```

The lines above are complete PowerShell commands. `packageExe` and `packageMsi` are optional Gradle packaging experiments; neither is used for the public preview installer.

## Local libmpv playback runtime

The preview installer does not bundle libmpv. To enable local playback in the source run or installed app, prepare the pinned Windows x64 development runtime for the current user:

```powershell
.\scripts\prepare-libmpv.ps1
.\gradlew.bat --no-daemon nativeMpvShared
.\gradlew.bat --no-daemon run
```

The script verifies the upstream archive SHA-256 before extraction and puts `libmpv-2.dll` in `%APPDATA%\DesktopMusicCandidate\libmpv`. The native acceptance task automatically uses this runtime and its cached archive; explicit `-PmpvLibrary` and `-PmpvArchive` overrides remain available. Install 7-Zip if it is not already installed. See [the pinned runtime record](testing/libmpv-runtime.md) for source, hashes, and distribution boundaries.

The selected Windows build is an unofficial third-party binary listed by mpv's Windows installation page. It is provisioned for local testing only; the project does not bundle or redistribute it. Do not enable release packaging for this binary until its exact build configuration and all linked component licenses have been audited.

Build outputs are under `build/` and are intentionally ignored by Git. The app image is under `build/compose/binaries/main/app/DesktopMusicCandidate/`. The Inno Setup executable is written to `build/installer/`.

## Wrapper integrity

The checked-in Wrapper targets Gradle 9.3.1 from `downloads.gradle.org`. The distribution SHA-256 is `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`; the Wrapper JAR SHA-256 is `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`. GitHub Actions runs wrapper validation and the Windows test suite on pushes and pull requests.

## Packaging expectations

The Compose Desktop `createDistributable` task builds the application image with the selected JDK's bundled runtime. Inno Setup packages that image into a single Windows setup executable. The Windows CI installer smoke test installs into a custom path, checks the launcher and runtime, verifies the Start Menu shortcut, uninstalls, and checks that the test user's AppData marker remains. This does not replace interactive UAC, application startup, or real-user upgrade testing.
