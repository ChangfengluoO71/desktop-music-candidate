# Building and testing

## Requirements

- JDK 25 is the verified build and package runtime for this candidate. The application source/bytecode target remains Java 17; Miuix desktop runtime and native packaging require a newer build JDK.
- Windows EXE/MSI packaging uses `jpackage` and requires WiX Toolset 3.0 or later on `PATH`.
- Gradle dependencies are resolved from Maven Central and Google Maven. Tests use local fixtures and do not require a NetEase account or live network credentials.

## Commands

From this directory in Windows PowerShell:

```powershell
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon run
.\gradlew.bat --no-daemon packageExe
```

The lines above are complete PowerShell commands. To build the MSI instead, use `.\gradlew.bat --no-daemon packageMsi`.

Build outputs are under `build/` and are intentionally ignored by Git. Windows installer output is under `build/compose/binaries/main/exe/` or `build/compose/binaries/main/msi/`.

## Wrapper integrity

The checked-in Wrapper targets Gradle 9.3.1 from `downloads.gradle.org`. The distribution SHA-256 is `b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06`; the Wrapper JAR SHA-256 is `b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13`. A wrapper-validation CI job is prepared.

## Packaging expectations

The Compose Desktop packaging task builds a Windows installer and an application runtime image from the selected JDK. Before a user-facing release, inspect the generated runtime image's `legal/` notices and the installer on a clean Windows account. A successful `packageExe` task alone does not prove installation, Start Menu launch, upgrade, uninstall or user-data preservation.
