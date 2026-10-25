# Repository / CI / release status

This path is retained for old links; it is **not** a private-repository preparation checklist.

Verified 2026-10-25 from the public repository:

- Repository: [ChangfengluoO71/desktop-music-candidate](https://github.com/ChangfengluoO71/desktop-music-candidate), default branch main, baseline commit 6220796220cb10323b248e54945e2a2855b3b177.
- Latest verified published release: [v0.0.3](https://github.com/ChangfengluoO71/desktop-music-candidate/releases/tag/v0.0.3), created 2026-10-17, stable GitHub release (not marked prerelease).
- Desktop CI runs Windows JDK 25 tests on push / PR. A manually dispatched pipeline can build the pinned LGPL libmpv + corresponding source, build the version-stamped app image and Inno Setup installer, smoke-test install/uninstall, and publish release assets after integrity checks. The reported main-head CI runs on 2026-10-17 succeeded; this review did not rerun them.
- The v0.0.3 public installer includes the audited release libmpv runtime and notices. It is distinct from locally provisioned, third-party development DLLs and ordinary Gradle packageExe tasks.
- Release assets include installer EXE, libmpv-corresponding-source.tar.gz, libmpv-runtime-manifest.json and SHA256SUMS.txt. Signing is not asserted.
- Do not store credential manager data, Cloud/WebDAV account keys, cookies, user tracks, downloads, or logs in source control or Actions artifacts.

Recommended controls: protect main and require tests; keep publishing manual and restrict release permissions; verify bundled DLL/FFmpeg license obligations at each native upgrade; separate release smoke tests from actual GUI/upgrade acceptance; add WebDAV mock integration tests before introducing remote playback. See [current status](PROJECT-STATUS.md) and [roadmap](ROADMAP.md).