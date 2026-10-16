# Pinned local libmpv runtime

## Purpose

The DMC preview does not bundle libmpv. The player locates `libmpv-2.dll` in `%APPDATA%\DesktopMusicCandidate\libmpv` or accepts an explicit absolute DLL path. This setup is for local development and playback acceptance; it is not a release dependency.

## Pinned Windows x64 development build

| Item | Value |
|---|---|
| Provider | [shinchiro/mpv-winbuild-cmake release 20261013](https://github.com/shinchiro/mpv-winbuild-cmake/releases/tag/20261013), linked from the [mpv Windows installation page](https://mpv.io/installation/) |
| Asset | `mpv-dev-x86_64-20261013-git-9d144e57b6.7z` |
| Archive SHA-256 | `10860837BC04491862C1E66A95DC1AF1D7886D69757CE447A09E1E49AE993B52` |
| `libmpv-2.dll` SHA-256 | `5080E00CD114F3DFC0EB87F35068FEC70AFC8BFD6FC29DCA4B79C874A42485EE` |
| Client API | 2.5 in the 2026-10-14 native acceptance run |

The source page describes Windows builds as unofficial third-party builds (with first-party CI builds as an exception) and links to this provider. The script pins a specific release asset and verifies its published archive hash before installing. It does not use PATH lookup or turn off TLS verification.

## Prepare and use

From the repository root in PowerShell:

```powershell
.\scripts\prepare-libmpv.ps1
.\gradlew.bat --no-daemon nativeMpvShared
.\gradlew.bat --no-daemon run
```

7-Zip is required for the `.7z` archive. The archive is cached under `%LOCALAPPDATA%\DMC\libmpv`; extracted DLLs are installed under `%APPDATA%\DesktopMusicCandidate\libmpv`, which the app already checks. Native acceptance tasks use these paths automatically. Existing explicit Gradle properties can still override them.

The script replaces only the dedicated app-private `libmpv` directory. If that directory already exists, it is retained as a uniquely named sibling backup before the new runtime is put in place. The source archive and extracted staging files are kept for repeatable acceptance and troubleshooting.

## Local verification record (2026-10-14)

`nativeMpvShared` passed on Windows 11 with JDK 25.0.2, using the default paths installed by the script:

- libmpv client API: 2.5; archive SHA-256 `10860837BC04491862C1E66A95DC1AF1D7886D69757CE447A09E1E49AE993B52`; DLL SHA-256 `5080E00CD114F3DFC0EB87F35068FEC70AFC8BFD6FC29DCA4B79C874A42485EE`.
- MP3, FLAC, M4A/AAC, and WAV each selected `wasapi` and reported a positive playback position.
- Embedded-cover MP3 selected `wasapi`; playback position advanced; zero additional visible top-level windows were created.
- The source app was launched with plain `run` and Windows process-module inspection confirmed it loaded `%APPDATA%\DesktopMusicCandidate\libmpv\libmpv-2.dll` without an explicit `dmc.libmpv.path` override.
- Result: 2 native tests passed, 0 failed. This validates the local runtime and these playback paths; it does not count as manual UI acceptance or verify every player control.

The first attempt ran Gradle under the machine's Java 17 default and failed test discovery because a Miuix class requires Java 21 bytecode. Native test tasks now fail early with a JDK 21+ message; use the project's verified JDK 25.

## Distribution boundary

This is a local development runtime. The script writes it outside the Git worktree, and no build task copies it into the application image, EXE, Inno Setup installer, or release. mpv documents GPLv2+ as the default project license and describes an LGPL mode with build-specific exclusions; a switch or DLL name alone is not a license audit. Before distributing any libmpv binary, record the exact build configuration and review mpv, FFmpeg, and every linked component's obligations against the binary's actual configuration.

See the [mpv copyright and license notes](https://github.com/mpv-player/mpv/blob/master/Copyright) and the [mpv Windows installation page](https://mpv.io/installation/).
