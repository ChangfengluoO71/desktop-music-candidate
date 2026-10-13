# Repository and CI status

This file keeps its historical path to avoid breaking older links. It supersedes the earlier private-repository preparation checklist, which is no longer current.

## Current repository

- Repository: `ChangfengluoO71/desktop-music-candidate`
- Visibility: public
- Default branch: `main`
- Current product name in documentation: DMC. Runtime branding migration is pending; see [PRODUCT-IDENTITY.md](PRODUCT-IDENTITY.md).
- Current status snapshot and preview evidence: [PROJECT-STATUS.md](PROJECT-STATUS.md).

## CI and preview workflow

`.github/workflows/desktop-ci.yml` runs the Windows test suite on pushes and pull requests with Temurin JDK 25. A manually dispatched workflow can build the Inno Setup installer, run the Windows install/uninstall smoke script, and upload a seven-day artifact. The optional preview-publish step creates a GitHub prerelease and needs `contents: write` only for that job. The workflow does not use NetEase account credentials or sign the installer.

Keep signing and account credentials out of GitHub Actions. Never add local app data, Windows credentials, logs, installers, downloaded music, test audio output or user-specific databases to the repository.

## Remaining repository controls

- Protect `main` against force-push and deletion.
- Require the Windows tests workflow before merging changes to `main`.
- Keep preview publishing a deliberate manual action and stable releases separately reviewed.
- Regenerate dependency/license inventories after playback-engine or native-library changes. Review actual packaged DLLs and their notices, not only Gradle metadata.
