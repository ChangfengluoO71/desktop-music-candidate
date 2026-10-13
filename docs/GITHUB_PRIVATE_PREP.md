# Private GitHub repository and CI

## Repository metadata

- Repository: `ChangfengluoO71/desktop-music-candidate`
- Visibility: Private
- Default branch: `main`
- Description: `Independent Windows desktop music player and local library candidate`
- The slug and app identity remain placeholders until the product brand and owned package namespace are selected.

## Continuous integration

`.github/workflows/desktop-ci.yml` runs on pushes and pull requests. It validates the Gradle Wrapper and runs the full test suite on `windows-latest` with Temurin JDK 25. A manually dispatched workflow builds the Windows EXE and uploads it as a seven-day artifact. It does not publish a release, sign installers, or use account credentials.

The workflow requests only `contents: read`. Keep signing credentials and NetEase account credentials out of GitHub Actions. Do not upload local app data, credentials, logs, installers, or music files as repository content.

## Recommended repository controls

- Protect `main` against force-push and deletion.
- Require the Windows tests workflow before merging changes to `main`.
- Keep release publishing as a separate, explicitly approved workflow.
- Review dependency license snapshots and packaged JDK legal notices before distributing an installer.