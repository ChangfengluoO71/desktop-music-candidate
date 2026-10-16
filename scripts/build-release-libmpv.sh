#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cygpath -u "${GITHUB_WORKSPACE:?GITHUB_WORKSPACE is required}")"
runner_temp="$(cygpath -u "${RUNNER_TEMP:?RUNNER_TEMP is required}")"
release_root="$repo_root/build/release-libmpv"
work_root="$runner_temp/dmc-lgpl-libmpv"
source_root="$work_root/source"
mpv_root="$source_root/mpv"
build_root="$work_root/build"
prefix="$work_root/prefix"

if [[ -e "$release_root" ]]; then
    echo "Refusing to overwrite the release staging directory: $release_root" >&2
    exit 1
fi

mkdir -p "$release_root/runtime" "$release_root/licenses/libmpv" "$source_root" "$work_root/archive-cache"

fetch_source() {
    local name="$1" url="$2" sha256="$3" filename="$4" destination="$5"
    local archive="$work_root/archive-cache/$filename"
    mkdir -p "$destination"
    curl --fail --location --retry 3 --output "$archive" "$url"
    printf '%s  %s\n' "$sha256" "$archive" | sha256sum --check --status
    tar --extract --gzip --file "$archive" --directory "$destination" --strip-components=1
    cp "$archive" "$release_root/$filename"
    printf '%s  %s\n' "$sha256" "$filename" >> "$release_root/source-inputs-SHA256SUMS.txt"
}

fetch_source mpv \
    'https://github.com/mpv-player/mpv/archive/41f6a645068483470267271e1d09966ca3b9f413.tar.gz' \
    '068960E89211F2ADC80AF03F637FA393FB5E407D3DD23592C8FE8C5490BB7AE4' \
    'mpv-0.41.0.tar.gz' "$mpv_root"
fetch_source ffmpeg-meson-port \
    'https://gitlab.freedesktop.org/gstreamer/meson-ports/ffmpeg/-/archive/74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9/ffmpeg-74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9.tar.gz' \
    'C9F084FE612B27B1B24D514C86E89F76FC55E1C5629D497927DFA4BFD56CB84E' \
    'ffmpeg-meson-port-74d461d3.tar.gz' "$mpv_root/subprojects/ffmpeg"
fetch_source libass \
    'https://github.com/libass/libass/archive/4a05d8127f525943ebf45fdc6497c9e665947f0d.tar.gz' \
    '3546ECC8748C62E100A9DD18E52EA2D17D7FACEDF89CFCAD97000AD60B1BCE2B' \
    'libass-0.17.5.tar.gz' "$mpv_root/subprojects/libass"
fetch_source libplacebo \
    'https://code.videolan.org/videolan/libplacebo/-/archive/cee9b076f2c63104ccfd497fa79c39a867293ec4/libplacebo-cee9b076f2c63104ccfd497fa79c39a867293ec4.tar.gz' \
    '6F8FA218CBAFD8E5F50B8A82D918E1D8BBB92F9F980820BC0B34D92E9B79E484' \
    'libplacebo-7.360.1.tar.gz' "$mpv_root/subprojects/libplacebo"
fetch_source freetype \
    'https://github.com/freetype/freetype/archive/526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b.tar.gz' \
    'D24F53B60034DC5C48E90D122D432A8F04A05B9E0A98C8D8E49B4A759207CE2E' \
    'freetype-2.14.1.tar.gz' "$mpv_root/subprojects/freetype2"
fetch_source fribidi \
    'https://github.com/fribidi/fribidi/archive/b93119f5fdc7ea47672cc304c1455ffa6dfe7536.tar.gz' \
    'F309091695B3B0F2FD5F9CAA7941D906AB08DF9AB8F9931BF5EE674BC00B489B' \
    'fribidi-1.0.17.tar.gz' "$mpv_root/subprojects/fribidi"
fetch_source harfbuzz \
    'https://github.com/harfbuzz/harfbuzz/archive/0426759f2b03e479495e987608f10e0866e70180.tar.gz' \
    'A41AAE311E28701BFE954B15834FFB5C79E13D81E9B67F956FA78E557CB47A30' \
    'harfbuzz-12.2.0.tar.gz' "$mpv_root/subprojects/harfbuzz"

fetch_build_input() {
    local url="$1" sha256="$2" filename="$3"
    curl --fail --location --retry 3 --output "$release_root/$filename" "$url"
    printf '%s  %s\n' "$sha256" "$release_root/$filename" | sha256sum --check --status
    printf '%s  %s\n' "$sha256" "$filename" >> "$release_root/source-inputs-SHA256SUMS.txt"
}

mkdir -p "$mpv_root/subprojects/packagecache"
fetch_build_input \
    'https://github.com/pnggroup/libpng/archive/v1.6.50.tar.gz' \
    '71158E53CFDF2877BC99BCAB33641D78DF3F48E6E0DAAD030AFE9CB8C031AA46' \
    'libpng-1.6.50.tar.gz'
fetch_build_input \
    'https://wrapdb.mesonbuild.com/v2/libpng_1.6.50-2/get_patch' \
    'A9C9362BBC8BB422C864807A174977DEC3868CDFF8CA380E27BAB6EE1CAA22EA' \
    'libpng_1.6.50-2_patch.zip'
fetch_build_input \
    'https://zlib.net/fossils/zlib-1.3.1.tar.gz' \
    '9A93B2B7DFDAC77CEBA5A558A580E74667DD6FEDE4585B91EEFB60F03B72DF23' \
    'zlib-1.3.1.tar.gz'
fetch_build_input \
    'https://wrapdb.mesonbuild.com/v2/zlib_1.3.1-2/get_patch' \
    '9CACEA02E1119964BC51E92DD2359B14DF723A36CFE0DF1C78D55D9C9F2763AE' \
    'zlib_1.3.1-2_patch.zip'
cp "$release_root/libpng-1.6.50.tar.gz" "$mpv_root/subprojects/packagecache/libpng-1.6.50.tar.gz"
cp "$release_root/libpng_1.6.50-2_patch.zip" "$mpv_root/subprojects/packagecache/libpng_1.6.50-2_patch.zip"
cp "$release_root/zlib-1.3.1.tar.gz" "$mpv_root/subprojects/packagecache/zlib-1.3.1.tar.gz"
cp "$release_root/zlib_1.3.1-2_patch.zip" "$mpv_root/subprojects/packagecache/zlib_1.3.1-2_patch.zip"

cat > "$mpv_root/subprojects/ffmpeg.wrap" <<'EOF'
[wrap-git]
url = https://gitlab.freedesktop.org/gstreamer/meson-ports/ffmpeg.git
revision = 74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9
depth = 1

[provide]
libavcodec = libavcodec_dep
libavdevice = libavdevice_dep
libavfilter = libavfilter_dep
libavformat = libavformat_dep
libavutil = libavutil_dep
libswresample = libswresample_dep
libswscale = libswscale_dep
EOF

cat > "$mpv_root/subprojects/libass.wrap" <<'EOF'
[wrap-git]
url = https://github.com/libass/libass.git
revision = 4a05d8127f525943ebf45fdc6497c9e665947f0d
depth = 1
EOF
cat > "$mpv_root/subprojects/libplacebo.wrap" <<'EOF'
[wrap-git]
url = https://code.videolan.org/videolan/libplacebo.git
revision = cee9b076f2c63104ccfd497fa79c39a867293ec4
depth = 1
EOF
cat > "$mpv_root/subprojects/freetype2.wrap" <<'EOF'
[wrap-git]
url = https://github.com/freetype/freetype.git
revision = 526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b
depth = 1
EOF
cat > "$mpv_root/subprojects/fribidi.wrap" <<'EOF'
[wrap-git]
url = https://github.com/fribidi/fribidi.git
revision = b93119f5fdc7ea47672cc304c1455ffa6dfe7536
depth = 1
EOF
cat > "$mpv_root/subprojects/harfbuzz.wrap" <<'EOF'
[wrap-git]
url = https://github.com/harfbuzz/harfbuzz.git
revision = 0426759f2b03e479495e987608f10e0866e70180
depth = 1
EOF

export CC=gcc
export CXX=g++
export PKG_CONFIG=pkgconf
# Keep MinGW's thread runtime dynamically linked and separately replaceable.
# GCC's own runtime helpers remain static under the GCC Runtime Library Exception.
export CFLAGS="${CFLAGS:-} -static-libgcc"
export CXXFLAGS="${CXXFLAGS:-} -static-libgcc -static-libstdc++"

meson setup "$build_root" "$mpv_root" \
    --prefix="$prefix" \
    --wrap-mode=forcefallback \
    --force-fallback-for=ffmpeg,libass,libplacebo,freetype2,fribidi,harfbuzz \
    -Ddefault_library=shared \
    -Dlibmpv=true -Dcplayer=false -Dgpl=false -Dtests=false \
    -Dbuild-date=false -Dlua=disabled -Djavascript=disabled \
    -Ddvdnav=disabled -Dlibarchive=disabled -Dlibbluray=disabled \
    -Drubberband=disabled -Duchardet=disabled -Dvapoursynth=disabled \
    -Diconv=disabled -Dzlib=disabled -Dwasapi=enabled -Dwin32-threads=enabled \
    -Dgl=disabled -Dvulkan=disabled -Dd3d11=disabled \
    -Dffmpeg:default_library=shared -Dffmpeg:gpl=disabled \
    -Dffmpeg:version3=disabled -Dffmpeg:nonfree=disabled \
    -Dffmpeg:tests=disabled -Dffmpeg:programs=disabled -Dffmpeg:postproc=disabled \
    -Dffmpeg:iconv=disabled -Dffmpeg:bzlib=disabled -Dffmpeg:zlib=disabled \
    -Dffmpeg:libass=disabled -Dffmpeg:libplacebo=disabled \
    -Dlibass:default_library=shared -Dlibass:fontconfig=disabled \
    -Dlibass:directwrite=enabled -Dlibass:libunibreak=disabled \
    -Dlibass:test=disabled -Dlibass:compare=disabled -Dlibass:profile=disabled \
    -Dfreetype2:default_library=shared -Dfreetype2:brotli=disabled \
    -Dfreetype2:bzip2=disabled -Dfreetype2:harfbuzz=disabled \
    -Dfreetype2:png=disabled -Dfreetype2:zlib=none -Dfreetype2:tests=disabled \
    -Dfribidi:default_library=shared -Dfribidi:docs=false -Dfribidi:tests=false \
    -Dharfbuzz:default_library=shared -Dharfbuzz:tests=disabled \
    -Dharfbuzz:utilities=disabled -Dharfbuzz:docs=disabled \
    -Dharfbuzz:glib=disabled -Dharfbuzz:gobject=disabled -Dharfbuzz:cairo=disabled \
    -Dharfbuzz:chafa=disabled -Dharfbuzz:icu=disabled -Dharfbuzz:freetype=disabled \
    -Dlibplacebo:default_library=shared -Dlibplacebo:demos=false \
    -Dlibplacebo:tests=false -Dlibplacebo:vulkan=disabled \
    -Dlibplacebo:opengl=disabled -Dlibplacebo:d3d11=disabled \
    -Dlibplacebo:shaderc=disabled -Dlibplacebo:glslang=disabled \
    -Dlibplacebo:lcms=disabled -Dlibplacebo:dovi=disabled \
    -Dlibplacebo:libdovi=disabled -Dlibplacebo:xxhash=disabled \
    -Dlibplacebo:unwind=disabled

meson compile -C "$build_root"
meson install -C "$build_root"

cp "$prefix/bin/"*.dll "$release_root/runtime/"
test -s "$release_root/runtime/libmpv-2.dll"
compiler_runtime_dlls="$(find "$release_root/runtime" -maxdepth 1 -type f -printf '%f\n' | grep -Ei '^(libgcc|libstdc\+\+).*\.dll$' || true)"
if [[ -n "$compiler_runtime_dlls" ]]; then
    echo 'A GCC compiler runtime DLL was linked dynamically; release build must keep compiler runtimes static.' >&2
    exit 1
fi
winpthread_package='mingw-w64-ucrt-x86_64-libwinpthread'
winpthread_version="$(pacman -Q "$winpthread_package" | awk '{print $2}')"
winpthread_source="mingw-w64-winpthreads-${winpthread_version}.src.tar.zst"
winpthread_source_url="https://mirror.msys2.org/mingw/sources/${winpthread_source}"
if ! curl --fail --location --retry 3 --output "$release_root/$winpthread_source" "$winpthread_source_url"; then
    winpthread_source_url="https://repo.msys2.org/mingw/sources/${winpthread_source}"
    curl --fail --location --retry 3 --output "$release_root/$winpthread_source" "$winpthread_source_url"
fi
winpthread_source_hash="$(sha256sum "$release_root/$winpthread_source" | cut -d ' ' -f1)"
printf '%s  %s\n' "$winpthread_source_hash" "$winpthread_source" >> "$release_root/source-inputs-SHA256SUMS.txt"
cp "$MINGW_PREFIX/bin/libwinpthread-1.dll" "$release_root/runtime/"
cp "$MINGW_PREFIX/share/licenses/libwinpthread/COPYING" "$release_root/licenses/libmpv/winpthreads-COPYING.txt"
printf 'Winpthreads\t%s\tMIT AND BSD-3-Clause\t%s\t%s\n' \
    "$winpthread_version" "$winpthread_version" "$winpthread_source_url" \
    > "$release_root/winpthreads-source-info.tsv"
gcc_runtime_license="$(find "$MINGW_PREFIX/share/licenses" -type f -name COPYING.RUNTIME -print -quit)"
if [[ -z "$gcc_runtime_license" ]]; then
    echo 'The MSYS2 toolchain does not provide GCC Runtime Library Exception text.' >&2
    exit 1
fi
cp "$gcc_runtime_license" "$release_root/licenses/libmpv/GCC-COPYING.RUNTIME.txt"
cp "$MINGW_PREFIX/share/licenses/vulkan-headers/LICENSE" "$release_root/licenses/libmpv/Vulkan-Headers-LICENSE.txt"
pacman -Q | sort > "$release_root/toolchain-packages.txt"

resolve_runtime_component() {
    local dll_name="${1,,}"
    case "$dll_name" in
        libmpv-2.dll) printf '%s' 'mpv' ;;
        avcodec*.dll|avdevice*.dll|avfilter*.dll|avformat*.dll|avutil*.dll|swresample*.dll|swscale*.dll|libavcodec*.dll|libavdevice*.dll|libavfilter*.dll|libavformat*.dll|libavutil*.dll|libswresample*.dll|libswscale*.dll) printf '%s' 'FFmpeg' ;;
        libass*.dll) printf '%s' 'libass' ;;
        libpng16*.dll) printf '%s' 'libpng' ;;
        libz-1.dll) printf '%s' 'zlib' ;;
        libplacebo*.dll) printf '%s' 'libplacebo' ;;
        libfreetype*.dll) printf '%s' 'FreeType' ;;
        libfribidi*.dll) printf '%s' 'FriBidi' ;;
        libharfbuzz*.dll) printf '%s' 'HarfBuzz' ;;
        libwinpthread-1.dll) printf '%s' 'Winpthreads' ;;
        *) return 1 ;;
    esac
}

: > "$release_root/runtime-components.tsv"
mkdir -p "$release_root/native-imports"
for dll in "$release_root/runtime/"*.dll; do
    dll_name="$(basename "$dll")"
    if ! objdump -f "$dll" | grep -q 'architecture: i386:x86-64'; then
        echo "Runtime DLL is not x86_64 PE: $dll_name" >&2
        exit 1
    fi
    component="$(resolve_runtime_component "$dll_name")" || {
        echo "No audited license/source component is mapped to runtime DLL $dll_name." >&2
        exit 1
    }
    printf '%s\t%s\n' "$dll_name" "$component" >> "$release_root/runtime-components.tsv"
    objdump -p "$dll" | awk '/DLL Name:/ {print tolower($3)}' | sort -u > "$release_root/native-imports/$dll_name.txt"
done
cat "$release_root"/native-imports/*.txt | sort -u > "$release_root/native-imports.txt"

source_bundle_root="$work_root/corresponding-source"
mkdir -p "$source_bundle_root/source" "$source_bundle_root/build-inputs"
cp "$release_root/"*.tar.gz "$release_root/"*.src.tar.zst "$release_root/"*.zip "$source_bundle_root/source/"
cp "$release_root/source-inputs-SHA256SUMS.txt" "$source_bundle_root/source/SHA256SUMS.txt"
cp "$repo_root/scripts/build-release-libmpv.sh" "$source_bundle_root/build-inputs/"
cp "$release_root/toolchain-packages.txt" "$source_bundle_root/build-inputs/"
cp "$release_root/native-imports.txt" "$source_bundle_root/build-inputs/"
cp "$release_root/runtime-components.tsv" "$source_bundle_root/build-inputs/"
cp "$release_root/winpthreads-source-info.tsv" "$source_bundle_root/build-inputs/"
cat > "$source_bundle_root/BUILDING.md" <<'EOF'
# DMC libmpv corresponding source

This source bundle contains the exact source archives used for the DMC Windows libmpv runtime, the matching MSYS2 Winpthreads source package, their SHA-256 list, and the pinned build script. It targets x86_64 Windows GNU and builds shared LGPL components so the installed DLLs can be replaced independently. Winpthreads is shipped as a separate DLL to keep its own runtime replaceable.

Build prerequisites: MSYS2 UCRT64 with GCC/G++, Meson, Ninja, pkgconf, yasm, nasm, Python with Jinja2, Vulkan headers, Git, curl, and tar. Vulkan is disabled in the runtime; its headers are needed to compile libplacebo's no-Vulkan stubs and no Vulkan loader/runtime is shipped. The included source inputs and Meson WrapDB patches are checksum-verified; Meson uses the cached libpng/zlib source inputs rather than downloading them again. The included script fetches the immutable source revisions listed in `source/SHA256SUMS.txt`, configures the shared libraries, and builds them. The matching Winpthreads source package and license are taken from the same MSYS2 package version recorded in `build-inputs/winpthreads-source-info.tsv`. Run it from an MSYS2 UCRT64 shell with `GITHUB_WORKSPACE` and `RUNNER_TEMP` set. The tested CI job records the exact toolchain package versions in its artifact metadata.

The runtime is configured with mpv `-Dgpl=false`, FFmpeg `gpl=disabled`, `version3=disabled`, and `nonfree=disabled`. The source archives preserve each upstream project's license and copyright notices.
EOF

for spec in \
    'mpv:LGPL-2.1-or-later:0.41.0:41f6a645068483470267271e1d09966ca3b9f413:source/mpv-0.41.0.tar.gz' \
    'ffmpeg:LGPL-2.1-or-later:8.0.3:74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9:source/ffmpeg-meson-port-74d461d3.tar.gz' \
    'libass:ISC:0.17.5:4a05d8127f525943ebf45fdc6497c9e665947f0d:source/libass-0.17.5.tar.gz' \
    'libpng:PNG Reference Library License version 2:1.6.50:v1.6.50:source/libpng-1.6.50.tar.gz' \
    'zlib:zlib License:1.3.1:v1.3.1:source/zlib-1.3.1.tar.gz' \
    'libplacebo:LGPL-2.1-or-later:7.360.1:cee9b076f2c63104ccfd497fa79c39a867293ec4:source/libplacebo-7.360.1.tar.gz' \
    'freetype:FTL:2.14.1:526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b:source/freetype-2.14.1.tar.gz' \
    'fribidi:LGPL-2.1-or-later:1.0.17:b93119f5fdc7ea47672cc304c1455ffa6dfe7536:source/fribidi-1.0.17.tar.gz' \
    'harfbuzz:MIT:12.2.0:0426759f2b03e479495e987608f10e0866e70180:source/harfbuzz-12.2.0.tar.gz'; do
    name="${spec%%:*}"
    remainder="${spec#*:}"
    license="${remainder%%:*}"
    remainder="${remainder#*:}"
    version="${remainder%%:*}"
    remainder="${remainder#*:}"
    commit="${remainder%%:*}"
    archive="${remainder#*:}"
    archive_hash="$(sha256sum "$source_bundle_root/$archive" | cut -d ' ' -f1)"
    printf '%s\t%s\t%s\t%s\t%s\n' "$name" "$version" "$license" "$commit" "$archive_hash" >> "$source_bundle_root/source-components.tsv"
done
IFS=$'\t' read -r winpthread_name winpthread_version winpthread_license winpthread_revision winpthread_source_url < "$release_root/winpthreads-source-info.tsv"
winpthread_archive="source/$(basename "$winpthread_source")"
winpthread_archive_hash="$(sha256sum "$source_bundle_root/$winpthread_archive" | cut -d ' ' -f1)"
printf '%s\t%s\t%s\t%s\t%s\n' \
    "$winpthread_name" "$winpthread_version" "$winpthread_license" "$winpthread_revision" "$winpthread_archive_hash" \
    >> "$source_bundle_root/source-components.tsv"

tar -czf "$release_root/libmpv-corresponding-source.tar.gz" -C "$source_bundle_root" .
cp "$source_root/mpv/Copyright" "$release_root/licenses/libmpv/mpv-Copyright.txt"
cp "$source_root/mpv/subprojects/ffmpeg/COPYING.LGPLv2.1" "$release_root/licenses/libmpv/FFmpeg-LGPL-2.1.txt"
cp "$source_root/mpv/subprojects/libplacebo/LICENSE" "$release_root/licenses/libmpv/libplacebo-LICENSE.txt"
cp "$source_root/mpv/subprojects/libass/COPYING" "$release_root/licenses/libmpv/libass-COPYING.txt"
cp "$source_root/mpv/subprojects/libpng-1.6.50/LICENSE" "$release_root/licenses/libmpv/libpng-LICENSE.txt"
cp "$source_root/mpv/subprojects/zlib-1.3.1/LICENSE" "$release_root/licenses/libmpv/zlib-LICENSE.txt"
cp "$source_root/mpv/subprojects/freetype2/LICENSE.TXT" "$release_root/licenses/libmpv/FreeType-LICENSE.txt"
cp "$source_root/mpv/subprojects/fribidi/COPYING" "$release_root/licenses/libmpv/FriBidi-COPYING.txt"
cp "$source_root/mpv/subprojects/harfbuzz/COPYING" "$release_root/licenses/libmpv/HarfBuzz-COPYING.txt"
cp "$repo_root/LICENSES/LGPL-2.1.txt" "$release_root/licenses/libmpv/LICENSE.LGPL"

cat > "$release_root/licenses/libmpv/THIRD_PARTY.md" <<'EOF'
# Native playback runtime notices

This directory contains the native playback runtime's license texts. The exact corresponding source, source checksums, and build instructions are in `../libmpv-corresponding-source.tar.gz`.

| Component | Version | License |
|---|---:|---|
| mpv / libmpv | 0.41.0 | LGPL-2.1-or-later; built with GPL code disabled |
| FFmpeg | 8.0.3 | LGPL-2.1-or-later; GPL, version 3, and nonfree code disabled |
| libass | 0.17.5 | ISC |
| libpng | 1.6.50 | PNG Reference Library License version 2 |
| zlib | 1.3.1 | zlib License |
| libplacebo | 7.360.1 | LGPL-2.1-or-later |
| FreeType | 2.14.1 | FreeType License (FTL) |
| FriBidi | 1.0.17 | LGPL-2.1-or-later |
| HarfBuzz | 12.2.0 | MIT |
| Winpthreads | See `runtime-manifest.json` | MIT and BSD-3-Clause |
| GCC runtime helpers | See `toolchain-packages.txt` | GCC Runtime Library Exception 3.1 |
| Vulkan-Headers | See `toolchain-packages.txt` | Apache-2.0; build-time headers only, Vulkan support disabled and no Vulkan runtime shipped |

Use the included DLLs in the `libmpv` directory or replace those files with compatible rebuilt DLLs from the corresponding source. Keep the installed DLL names and architecture unchanged. DMC loads libmpv only from its private runtime directory.
EOF

python - "$release_root" <<'PY'
import hashlib
import json
import pathlib
import sys

root = pathlib.Path(sys.argv[1])
runtime = root / "runtime"
source = root / "libmpv-corresponding-source.tar.gz"
components = []
source_details = {
    "mpv-0.41.0.tar.gz": {"name": "mpv", "version": "0.41.0", "commit": "41f6a645068483470267271e1d09966ca3b9f413", "license": "LGPL-2.1-or-later", "sourceUrl": "https://github.com/mpv-player/mpv/archive/41f6a645068483470267271e1d09966ca3b9f413.tar.gz"},
    "ffmpeg-meson-port-74d461d3.tar.gz": {"name": "FFmpeg", "version": "8.0.3", "commit": "74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9", "upstreamCommit": "8ae0b34901ba60a802f183ee75a250a9fc3e09a5", "license": "LGPL-2.1-or-later", "sourceUrl": "https://gitlab.freedesktop.org/gstreamer/meson-ports/ffmpeg/-/archive/74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9/ffmpeg-74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9.tar.gz"},
    "libass-0.17.5.tar.gz": {"name": "libass", "version": "0.17.5", "commit": "4a05d8127f525943ebf45fdc6497c9e665947f0d", "license": "ISC", "sourceUrl": "https://github.com/libass/libass/archive/4a05d8127f525943ebf45fdc6497c9e665947f0d.tar.gz"},
    "libpng-1.6.50.tar.gz": {"name": "libpng", "version": "1.6.50", "commit": "v1.6.50", "license": "PNG Reference Library License version 2", "sourceUrl": "https://github.com/pnggroup/libpng/archive/v1.6.50.tar.gz"},
    "zlib-1.3.1.tar.gz": {"name": "zlib", "version": "1.3.1", "commit": "v1.3.1", "license": "zlib License", "sourceUrl": "https://zlib.net/fossils/zlib-1.3.1.tar.gz"},
    "libplacebo-7.360.1.tar.gz": {"name": "libplacebo", "version": "7.360.1", "commit": "cee9b076f2c63104ccfd497fa79c39a867293ec4", "license": "LGPL-2.1-or-later", "sourceUrl": "https://code.videolan.org/videolan/libplacebo/-/archive/cee9b076f2c63104ccfd497fa79c39a867293ec4/libplacebo-cee9b076f2c63104ccfd497fa79c39a867293ec4.tar.gz"},
    "freetype-2.14.1.tar.gz": {"name": "FreeType", "version": "2.14.1", "commit": "526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b", "license": "FTL", "sourceUrl": "https://github.com/freetype/freetype/archive/526ec5c47b9ebccc4754c85ac0c0cdf7c85a5e9b.tar.gz"},
    "fribidi-1.0.17.tar.gz": {"name": "FriBidi", "version": "1.0.17", "commit": "b93119f5fdc7ea47672cc304c1455ffa6dfe7536", "license": "LGPL-2.1-or-later", "sourceUrl": "https://github.com/fribidi/fribidi/archive/b93119f5fdc7ea47672cc304c1455ffa6dfe7536.tar.gz"},
    "harfbuzz-12.2.0.tar.gz": {"name": "HarfBuzz", "version": "12.2.0", "commit": "0426759f2b03e479495e987608f10e0866e70180", "license": "MIT", "sourceUrl": "https://github.com/harfbuzz/harfbuzz/archive/0426759f2b03e479495e987608f10e0866e70180.tar.gz"},
}
build_input_details = {
    "libpng_1.6.50-2_patch.zip": {"name": "libpng Meson WrapDB patch", "version": "1.6.50-2", "commit": "libpng_1.6.50-2", "sourceUrl": "https://wrapdb.mesonbuild.com/v2/libpng_1.6.50-2/get_patch"},
    "zlib_1.3.1-2_patch.zip": {"name": "zlib Meson WrapDB patch", "version": "1.3.1-2", "commit": "zlib_1.3.1-2", "sourceUrl": "https://wrapdb.mesonbuild.com/v2/zlib_1.3.1-2/get_patch"},
}
winpthread_name, winpthread_version, winpthread_license, winpthread_revision, winpthread_source_url = (root / "winpthreads-source-info.tsv").read_text(encoding="utf-8").strip().split("\t")
winpthread_archive = f"mingw-w64-winpthreads-{winpthread_version}.src.tar.zst"
source_details[winpthread_archive] = {
    "name": winpthread_name,
    "version": winpthread_version,
    "commit": winpthread_revision,
    "license": winpthread_license,
    "sourceUrl": winpthread_source_url,
}
for line in (root / "source-inputs-SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
    digest, name = line.split(maxsplit=1)
    detail = source_details.get(name, build_input_details.get(name))
    if detail is None:
        raise SystemExit(f"No provenance metadata exists for source input {name}.")
    components.append({**detail, "archive": name, "sha256": digest.upper()})
component_map = {}
for line in (root / "runtime-components.tsv").read_text(encoding="utf-8").splitlines():
    filename, component = line.split("\t", maxsplit=1)
    component_map[filename] = component
files = []
for path in sorted(runtime.glob("*.dll"), key=lambda p: p.name.lower()):
    imports = (root / "native-imports" / f"{path.name}.txt").read_text(encoding="utf-8").splitlines()
    files.append({
        "file": path.name,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest().upper(),
        "component": component_map[path.name],
        "imports": sorted(set(line.strip().lower() for line in imports if line.strip())),
    })
manifest = {
    "schemaVersion": 1,
    "architecture": "x86_64-windows-gnu",
    "mpv": {
        "version": "0.41.0",
        "commit": "41f6a645068483470267271e1d09966ca3b9f413",
        "clientApiVersion": "2.5",
        "gpl": False,
    },
    "ffmpeg": {
        "version": "8.0.3",
        "commit": "74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9",
        "upstreamCommit": "8ae0b34901ba60a802f183ee75a250a9fc3e09a5",
        "mesonPortCommit": "74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9",
        "gpl": False,
        "version3": False,
        "nonfree": False,
        "postproc": False,
    },
    "build": {
        "sourceCommit": __import__("os").environ.get("GITHUB_SHA", "unknown"),
        "workflowRunId": __import__("os").environ.get("GITHUB_RUN_ID", "unknown"),
        "workflowRef": __import__("os").environ.get("GITHUB_WORKFLOW_REF", "unknown"),
        "runnerImage": __import__("os").environ.get("ImageOS", "unknown"),
        "runnerImageVersion": __import__("os").environ.get("ImageVersion", "unknown"),
        "toolchain": "MSYS2 UCRT64 GCC",
        "meson": "recorded in CI toolchain package list",
        "compiler": "UCRT64 gcc/g++",
        "toolchainPackageList": "build-inputs/toolchain-packages.txt",
        "configureFlags": [
            "-Dgpl=false",
            "-Dffmpeg:gpl=disabled",
            "-Dffmpeg:version3=disabled",
            "-Dffmpeg:nonfree=disabled",
            "-Dffmpeg:postproc=disabled",
            "-Ddefault_library=shared",
            "-Dlibmpv=true",
            "-Dcplayer=false",
            "-Dwasapi=enabled",
        ],
    },
    "sourceInputs": components,
    "licenseComponents": [
        {"name": item["name"], "version": item["version"], "commit": item["commit"], "license": item["license"], "sourceUrl": item["sourceUrl"]}
        for item in source_details.values()
    ],
    "runtimeFiles": files,
    "nativeImports": [
        line.strip().lower()
        for line in (root / "native-imports.txt").read_text(encoding="utf-8").splitlines()
        if line.strip()
    ],
    "sourceBundle": {
        "file": "licenses/libmpv-corresponding-source.tar.gz",
        "sha256": hashlib.sha256(source.read_bytes()).hexdigest().upper(),
    },
}
(runtime / "runtime-manifest.json").write_text(
    json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
)
PY

if [[ "$(find "$release_root/runtime" -maxdepth 1 -type f -name '*.dll' | wc -l)" -lt 2 ]]; then
    echo 'Release staging must contain libmpv and at least one adjacent native DLL.' >&2
    exit 1
fi

echo "Staged pinned LGPL runtime and corresponding source under $release_root"
