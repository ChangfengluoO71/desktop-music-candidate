param(
    [Parameter(Mandatory = $true)]
    [string]$InstallRoot,
    [string]$ExpectedSourceCommit = $env:GITHUB_SHA
)

$ErrorActionPreference = 'Stop'

$root = (Resolve-Path -LiteralPath $InstallRoot -ErrorAction Stop).Path

function Get-RequiredFile([string]$RelativePath) {
    $path = Join-Path $root $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Release payload is missing required file: $RelativePath"
    }
    return $path
}

$launcher = Get-RequiredFile 'DesktopMusicCandidate.exe'
$appConfig = Get-RequiredFile 'app\DesktopMusicCandidate.cfg'
$runtimeManifestPath = Get-RequiredFile 'libmpv\runtime-manifest.json'
$runtimeNotice = Get-RequiredFile 'licenses\libmpv\THIRD_PARTY.md'
$mpvLicense = Get-RequiredFile 'licenses\libmpv\LICENSE.LGPL'
$jaudiotaggerLicense = Get-RequiredFile 'licenses\LGPL-2.1.txt'
$jaudiotaggerSource = Get-RequiredFile 'licenses\jaudiotagger-3.0.1-sources.jar'

$jaudiotaggerJar = Get-ChildItem -LiteralPath (Join-Path $root 'app') -Filter 'jaudiotagger-3.0.1-*.jar' -File | Select-Object -First 1
if (-not $jaudiotaggerJar) {
    throw 'Release payload is missing the replaceable jaudiotagger 3.0.1 runtime JAR.'
}
$classpath = Get-Content -LiteralPath $appConfig -Raw
if (-not $classpath.Contains($jaudiotaggerJar.Name)) {
    throw 'The installed application classpath does not reference its separate jaudiotagger JAR.'
}

$sourceHash = (Get-FileHash -LiteralPath $jaudiotaggerSource -Algorithm SHA256).Hash.ToUpperInvariant()
if ($sourceHash -ne 'D9C79A145944E6BC37579403843C9DA84CD81459E42C9877351802EE284F5858') {
    throw 'The bundled jaudiotagger source JAR does not match Maven Central coordinate net.jthink:jaudiotagger:3.0.1.'
}

$manifest = Get-Content -LiteralPath $runtimeManifestPath -Raw | ConvertFrom-Json
if ($manifest.schemaVersion -ne 1) {
    throw 'The libmpv runtime manifest has an unsupported schema version.'
}
if ($manifest.architecture -ne 'x86_64-windows-gnu') {
    throw 'The libmpv runtime manifest does not identify the supported x64 MinGW build.'
}
if ($manifest.mpv.version -ne '0.41.0' -or $manifest.mpv.commit -ne '41f6a645068483470267271e1d09966ca3b9f413' -or $manifest.mpv.clientApiVersion -ne '2.5' -or $manifest.mpv.gpl -ne $false) {
    throw 'The packaged libmpv is not the pinned stable LGPL mpv 0.41.0 build.'
}
if ($manifest.ffmpeg.version -ne '8.0.3' -or $manifest.ffmpeg.commit -ne '74d461d3ed8a9fdd956336fd2a6a77ebc1bb91a9' -or $manifest.ffmpeg.upstreamCommit -ne '8ae0b34901ba60a802f183ee75a250a9fc3e09a5' -or $manifest.ffmpeg.mesonPortCommit -ne $manifest.ffmpeg.commit -or $manifest.ffmpeg.gpl -ne $false -or $manifest.ffmpeg.nonfree -ne $false -or $manifest.ffmpeg.version3 -ne $false -or $manifest.ffmpeg.postproc -ne $false) {
    throw 'The packaged FFmpeg is not the pinned LGPL-only 8.0.3 build.'
}

$runtimeDirectory = Join-Path $root 'libmpv'
$runtimeFiles = @($manifest.runtimeFiles)
if ($runtimeFiles.Count -lt 2 -or -not ($runtimeFiles | Where-Object { $_.file -ieq 'libmpv-2.dll' })) {
    throw 'The runtime manifest must list libmpv-2.dll and its adjacent native dependencies.'
}
$requiredRuntimeComponents = @{
    'libiconv-2.dll' = 'libiconv'
    'liblzma-5.dll' = 'liblzma'
}
foreach ($requiredRuntimeName in $requiredRuntimeComponents.Keys) {
    $requiredRuntime = @($runtimeFiles | Where-Object { $_.file -ieq $requiredRuntimeName -and $_.component -ceq $requiredRuntimeComponents[$requiredRuntimeName] }) | Select-Object -First 1
    if (-not $requiredRuntime) {
        throw "The runtime manifest is missing the audited dependency $requiredRuntimeName ($($requiredRuntimeComponents[$requiredRuntimeName]))."
    }
    if ($manifest.nativeImports -notcontains $requiredRuntimeName) {
        throw "The runtime import inventory does not confirm the required dependency $requiredRuntimeName."
    }
}
foreach ($runtimeFile in $runtimeFiles) {
    if ([System.IO.Path]::GetFileName($runtimeFile.file) -ne $runtimeFile.file) {
        throw "Runtime manifest contains an invalid path: $($runtimeFile.file)"
    }
    $runtimePath = Join-Path $runtimeDirectory $runtimeFile.file
    if (-not (Test-Path -LiteralPath $runtimePath -PathType Leaf)) {
        throw "The installer is missing native runtime dependency $($runtimeFile.file)."
    }
    $actualHash = (Get-FileHash -LiteralPath $runtimePath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualHash -ne $runtimeFile.sha256.ToUpperInvariant()) {
        throw "Native runtime hash does not match the manifest: $($runtimeFile.file)"
    }
}

$packagedNativeNames = @($runtimeFiles | ForEach-Object { $_.file.ToLowerInvariant() })
$windowsSystemDllDirectory = Join-Path $env:WINDIR 'System32'
$unresolvedImports = @($manifest.nativeImports | Where-Object {
    $importName = $_.ToLowerInvariant()
    $safeImportName = $importName -match '^[a-z0-9_.-]+$'
    $packagedNativeNames -notcontains $importName -and
        (-not $safeImportName -or -not (Test-Path -LiteralPath (Join-Path $windowsSystemDllDirectory $importName) -PathType Leaf)) -and
        -not $importName.StartsWith('api-ms-win-') -and
        -not $importName.StartsWith('ext-ms-win-')
})
if ($unresolvedImports.Count -gt 0) {
    throw "Native DLL import closure is incomplete: $($unresolvedImports -join ', ')"
}

if (@($manifest.licenseComponents).Count -ne 12) {
    throw 'The runtime manifest does not enumerate every included native component and license.'
}
$expectedComponentLicenses = @{
    'mpv' = 'LGPL-2.1-or-later'
    'FFmpeg' = 'LGPL-2.1-or-later'
    'libass' = 'ISC'
    'libpng' = 'PNG Reference Library License version 2'
    'zlib' = 'zlib License'
    'libplacebo' = 'LGPL-2.1-or-later'
    'FreeType' = 'FTL'
    'FriBidi' = 'LGPL-2.1-or-later'
    'HarfBuzz' = 'MIT'
    'Winpthreads' = 'MIT AND BSD-3-Clause'
    'libiconv' = 'LGPL-2.1-or-later'
    'liblzma' = '0BSD'
}
foreach ($requiredComponent in $expectedComponentLicenses.Keys) {
    $component = @($manifest.licenseComponents | Where-Object { $_.name -ceq $requiredComponent }) | Select-Object -First 1
    if (-not $component -or $component.license -ne $expectedComponentLicenses[$requiredComponent] -or [string]::IsNullOrWhiteSpace($component.commit) -or $component.sourceUrl -notmatch '^https://') {
        throw "The runtime manifest is missing license metadata for $requiredComponent."
    }
}
if (@($manifest.sourceInputs).Count -ne 14) {
    throw 'The runtime manifest does not enumerate all runtime source archives, Meson build patches, and matching MSYS2 source packages.'
}
foreach ($sourceInput in $manifest.sourceInputs) {
    if ([string]::IsNullOrWhiteSpace($sourceInput.commit) -or [string]::IsNullOrWhiteSpace($sourceInput.sha256) -or $sourceInput.sourceUrl -notmatch '^https://') {
        throw "A source archive is missing its immutable revision, checksum, or HTTPS URL: $($sourceInput.archive)"
    }
}
foreach ($requiredSourceArchive in @('mingw-w64-libiconv-1.19-1.src.tar.zst', 'mingw-w64-xz-5.8.4-1.src.tar.zst')) {
    if (-not (@($manifest.sourceInputs | Where-Object { $_.archive -ceq $requiredSourceArchive }).Count)) {
        throw "The runtime manifest is missing the matching MSYS2 dependency source archive $requiredSourceArchive."
    }
}
foreach ($runtimeFile in $runtimeFiles) {
    if (-not (@($manifest.licenseComponents | Where-Object { $_.name -ceq $runtimeFile.component }).Count)) {
        throw "The runtime DLL has no matching license/source component: $($runtimeFile.file)"
    }
    if ($null -eq $runtimeFile.imports) {
        throw "The runtime manifest is missing the import list for $($runtimeFile.file)."
    }
}
if ([string]::IsNullOrWhiteSpace($manifest.build.sourceCommit) -or [string]::IsNullOrWhiteSpace($manifest.build.workflowRunId) -or [string]::IsNullOrWhiteSpace($manifest.build.runnerImageVersion)) {
    throw 'The runtime manifest is missing CI source/run/runner provenance.'
}
if ($manifest.nativeImports -notcontains 'libwinpthread-1.dll') {
    throw 'The runtime was not dynamically linked to its replaceable Winpthreads DLL.'
}
if (-not ($runtimeFiles | Where-Object { $_.file -ieq 'libwinpthread-1.dll' -and $_.component -ceq 'Winpthreads' })) {
    throw 'The runtime does not ship the separately licensed Winpthreads DLL.'
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'licenses\libmpv\winpthreads-COPYING.txt') -PathType Leaf) -or -not (Test-Path -LiteralPath (Join-Path $root 'licenses\libmpv\GCC-COPYING.RUNTIME.txt') -PathType Leaf)) {
    throw 'The release payload is missing the Winpthreads or GCC runtime license text.'
}
foreach ($requiredNativeLicense in @(
    'Vulkan-Headers-LICENSE.txt', 'libpng-LICENSE.txt', 'zlib-LICENSE.txt',
    'libiconv-COPYING.txt', 'libiconv-COPYING.LIB.txt', 'libcharset-COPYING.LIB.txt',
    'XZ-COPYING.txt', 'liblzma-COPYING.0BSD.txt', 'XZ-COPYING.GPLv2.txt',
    'XZ-COPYING.GPLv3.txt', 'XZ-COPYING.LGPLv2.1.txt'
)) {
    if (-not (Test-Path -LiteralPath (Join-Path $root "licenses\libmpv\$requiredNativeLicense") -PathType Leaf)) {
        throw "The release payload is missing a native runtime license notice: $requiredNativeLicense"
    }
}
if ($ExpectedSourceCommit -and $manifest.build.sourceCommit -ne $ExpectedSourceCommit) {
    throw 'The native runtime source commit differs from the expected runtime build commit.'
}
if ([string]::IsNullOrWhiteSpace($manifest.sourceBundle.file) -or [string]::IsNullOrWhiteSpace($manifest.sourceBundle.sha256)) {
    throw 'The runtime manifest does not identify its corresponding source bundle.'
}
$sourceBundlePath = Join-Path $root $manifest.sourceBundle.file
if (-not (Test-Path -LiteralPath $sourceBundlePath -PathType Leaf)) {
    throw "The installer is missing the corresponding libmpv source bundle: $($manifest.sourceBundle.file)"
}
$sourceBundleHash = (Get-FileHash -LiteralPath $sourceBundlePath -Algorithm SHA256).Hash.ToUpperInvariant()
if ($sourceBundleHash -ne $manifest.sourceBundle.sha256.ToUpperInvariant()) {
    throw 'The corresponding libmpv source bundle does not match the runtime manifest.'
}

Write-Output 'Release payload passed: stable LGPL libmpv, dynamically replaceable native dependencies, every adjacent DLL hash, corresponding source archives, license notices, and replaceable jaudiotagger source/JAR.'
