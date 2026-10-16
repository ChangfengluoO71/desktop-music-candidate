# Windows libmpv runtime provenance and replacement

## Release gate

The Windows installer must ship a tested x64 `libmpv-2.dll` in its private `libmpv` directory. It must not use the local shinchiro development DLL or an untested upstream `git-release` artifact. The build script pins mpv stable 0.41.0 and all source archives by immutable commit and SHA-256. The build and installer smoke checks fail if the native import closure contains an unbundled non-Windows DLL or if an expected license/source file is missing.

The exact build output is recorded in `libmpv/runtime-manifest.json` inside the installed application. The installer also includes `licenses/libmpv/THIRD_PARTY.md`, component license texts, the matching source bundle, and the Jaudiotagger source JAR. The public GitHub Release attaches the same source bundle.

## Pinned source inputs

| Component | Version | Source revision | License noted by upstream |
|---|---|---|---|
| mpv/libmpv | 0.41.0 | `41f6a645068483470267271e1d09966ca3b9f413` | LGPL-2.1-or-later when built without GPL-only files |
| FFmpeg | 8.0.3 | upstream `n8.0.3`: `8ae0b34901ba60a802f183ee75a250a9fc3e09a5`; Meson port: `74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9` | LGPL-2.1-or-later; `gpl`, `version3`, and `nonfree` disabled |
| libass | 0.17.5 | `4a05d8127f525943ebf45fdc6497c9e665947f0d` | ISC |
| libpng | 1.6.50 | `v1.6.50`, archive SHA-256 recorded in the runtime manifest | PNG Reference Library License version 2 |
| zlib | 1.3.1 | `v1.3.1`, archive SHA-256 recorded in the runtime manifest | zlib License |
| libplacebo | 7.360.1 | `cee9b076f2c63104ccfd497fa79c39a867293ec4` | LGPL-2.1-or-later |
| FreeType | 2.14.1 | `526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b` | FreeType License (FTL) |
| FriBidi | 1.0.17 | `b93119f5fdc7ea47672cc304c1455ffa6dfe7536` | LGPL-2.1-or-later |
| HarfBuzz | 12.2.0 | `0426759f2b03e479495e987608f10e0866e70180` | MIT |
| Winpthreads | MSYS2 package version recorded in the runtime manifest | Matching MSYS2 source package recorded in the source bundle | MIT and BSD-3-Clause |
| Vulkan-Headers | MSYS2 package version recorded in `toolchain-packages.txt` | Build-time headers only; Vulkan support remains disabled and no Vulkan loader/runtime is shipped | Apache-2.0 |

`build-release-libmpv.sh` records the downloaded source archive checksums, Meson WrapDB patches, Windows import names, and MSYS2 package versions. The corresponding-source archive includes the exact input archives and build patches, the matching MSYS2 Winpthreads source package, and the build script. The runtime keeps Winpthreads as a separate DLL; the Windows smoke check requires it in the import closure and installer. GCC runtime helpers are static and built with the GCC Runtime Library Exception; the matching exception text is included in the installer. This is provenance for the actual candidate; it does not claim a bit-for-bit reproducible build on an unspecified host.

## Build configuration and limits

The release runtime is a shared Windows GNU build with `libmpv=true`, `cplayer=false`, WASAPI enabled, mpv's GPL option disabled, and FFmpeg's GPL/version3/nonfree options disabled. FFmpeg's optional `postproc` library and unrelated video backends are also disabled. All produced dependency DLLs, including `libwinpthread-1.dll`, are staged beside `libmpv-2.dll`; the build rejects dynamic GCC compiler runtime DLLs and emits per-DLL import lists and license-component mappings for review.

The `nativeMpvPoc` task validates decoding generated MP3, FLAC, M4A/AAC, and WAV fixtures, plus error cases, against the staged release DLL. `nativeMpvShared` uses the Windows machine's real WASAPI device and must be run on the user's PC for the final audio-output check. Installer smoke verifies custom installation, bundled Java, runtime/import closure, notices, source bundle hashes, replacement paths, uninstall, and user-data preservation.

The official mpv Windows build documentation says to review `-Dgpl=false` and similar options for subprojects. FFmpeg documents that enabling GPL or nonfree options changes the resulting build's license conditions. DMC's release checks assert the exact options and source archive hashes; they are not a substitute for legal advice.

## Replacing the installed libraries

The player locates `libmpv-2.dll` in the app-private `{app}\libmpv` directory. The installer keeps that folder separate from the Java application image. To replace the runtime, rebuild or obtain compatible x64 DLLs for the exact C client API, stop DMC, replace the native DLLs in `{app}\libmpv`, and restart. Preserve DLL names and include every required adjacent DLL. The in-app updater replaces the installer-owned app directory as a whole; user data under `%APPDATA%\DesktopMusicCandidate` remains separate.

Jaudiotagger is independently replaceable at `{app}\app\jaudiotagger-3.0.1.jar`; its source and LGPL license are under `{app}\licenses`. A compatible modified JAR can be placed under the same classpath filename.

## References

- [mpv 0.41.0 Windows build instructions](https://github.com/mpv-player/mpv/blob/41f6a645068483470267271e1d09966ca3b9f413/DOCS/compile-windows.md)
- [mpv 0.41.0 copyright and license conditions](https://github.com/mpv-player/mpv/blob/41f6a645068483470267271e1d09966ca3b9f413/Copyright)
- [FFmpeg licensing and legal considerations](https://ffmpeg.org/legal.html)
- [FFmpeg 8.0.3 release and source archives](https://ffmpeg.org/download.html)
- [MSYS2 Winpthreads package, license IDs, source package and installed COPYING path](https://packages.msys2.org/packages/mingw-w64-ucrt-x86_64-libwinpthread)
- [MSYS2 Vulkan-Headers package and Apache-2.0 license](https://packages.msys2.org/packages/mingw-w64-ucrt-x86_64-vulkan-headers)
- [libpng 1.6.50 license text](https://raw.githubusercontent.com/pnggroup/libpng/v1.6.50/LICENSE)
- [zlib 1.3.1 license text](https://raw.githubusercontent.com/madler/zlib/v1.3.1/LICENSE)
- [GCC libstdc++ license and Runtime Library Exception 3.1](https://gcc.gnu.org/onlinedocs/libstdc++/manual/license.html)
- [LGPL 2.1 text included by DMC](../../LICENSES/LGPL-2.1.txt)
