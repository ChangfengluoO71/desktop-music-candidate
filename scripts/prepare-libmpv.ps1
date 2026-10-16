#Requires -Version 5.1
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$releaseTag = '20261013'
$archiveName = 'mpv-dev-x86_64-20261013-git-9d144e57b6.7z'
$archiveSha256 = '10860837BC04491862C1E66A95DC1AF1D7886D69757CE447A09E1E49AE993B52'
$librarySha256 = '5080E00CD114F3DFC0EB87F35068FEC70AFC8BFD6FC29DCA4B79C874A42485EE'
$downloadUri = "https://github.com/shinchiro/mpv-winbuild-cmake/releases/download/$releaseTag/$archiveName"

if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA) -or [string]::IsNullOrWhiteSpace($env:APPDATA)) {
    throw 'LOCALAPPDATA and APPDATA must be set for the current Windows user.'
}

$cacheDirectory = Join-Path $env:LOCALAPPDATA 'DMC\libmpv'
$archivePath = Join-Path $cacheDirectory $archiveName
$extractDirectory = Join-Path $cacheDirectory "extracted-$releaseTag"
$appDataRoot = Join-Path $env:APPDATA 'DesktopMusicCandidate'
$runtimeDirectory = Join-Path $appDataRoot 'libmpv'
$runtimeLibrary = Join-Path $runtimeDirectory 'libmpv-2.dll'

New-Item -ItemType Directory -Path $cacheDirectory -Force | Out-Null

if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf)) {
    $downloadPath = Join-Path $cacheDirectory "$archiveName.$([guid]::NewGuid().ToString('N')).download"
    Write-Output "Downloading pinned libmpv development archive $releaseTag..."
    Invoke-WebRequest -Uri $downloadUri -OutFile $downloadPath
    $downloadHash = (Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($downloadHash -ne $archiveSha256) {
        throw "Downloaded archive SHA-256 did not match the pinned value. File retained at $downloadPath."
    }
    Move-Item -LiteralPath $downloadPath -Destination $archivePath
}

$actualArchiveHash = (Get-FileHash -LiteralPath $archivePath -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualArchiveHash -ne $archiveSha256) {
    throw "Cached archive SHA-256 did not match the pinned value. File retained at $archivePath."
}

$sevenZipCommand = Get-Command '7z.exe' -ErrorAction SilentlyContinue
$sevenZip = if ($sevenZipCommand) { $sevenZipCommand.Source } else { $null }
if (-not $sevenZip) {
    $sevenZipCandidates = @(
        (Join-Path ${env:ProgramFiles} '7-Zip\7z.exe'),
        (Join-Path ${env:ProgramFiles(x86)} '7-Zip\7z.exe')
    ) | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) }
    if ($sevenZipCandidates.Count -gt 0) { $sevenZip = $sevenZipCandidates[0] }
}
if (-not $sevenZip) {
    throw '7-Zip was not found. Install 7-Zip, then run this script again; the archive has been verified and retained.'
}

$extractedLibraries = @()
if (Test-Path -LiteralPath $extractDirectory -PathType Container) {
    $extractedLibraries = @(Get-ChildItem -LiteralPath $extractDirectory -Recurse -File -Filter 'libmpv-2.dll')
}
if ($extractedLibraries.Count -eq 0) {
    New-Item -ItemType Directory -Path $extractDirectory -Force | Out-Null
    & $sevenZip x $archivePath -y "-o$extractDirectory" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "7-Zip extraction failed with exit code $LASTEXITCODE. Extracted files were retained at $extractDirectory."
    }
    $extractedLibraries = @(Get-ChildItem -LiteralPath $extractDirectory -Recurse -File -Filter 'libmpv-2.dll')
}
if ($extractedLibraries.Count -ne 1) {
    throw "Expected exactly one libmpv-2.dll in the pinned archive; found $($extractedLibraries.Count)."
}

$sourceLibrary = $extractedLibraries[0]
$actualLibraryHash = (Get-FileHash -LiteralPath $sourceLibrary.FullName -Algorithm SHA256).Hash.ToUpperInvariant()
if ($actualLibraryHash -ne $librarySha256) {
    throw "Extracted libmpv-2.dll SHA-256 did not match the pinned value. Extracted files were retained at $extractDirectory."
}

$runtimeFiles = @(Get-ChildItem -LiteralPath $sourceLibrary.DirectoryName -File -Filter '*.dll')
if (-not ($runtimeFiles | Where-Object { $_.Name -ieq 'libmpv-2.dll' })) {
    throw 'The runtime folder does not contain libmpv-2.dll.'
}

New-Item -ItemType Directory -Path $appDataRoot -Force | Out-Null
$stagedRuntime = Join-Path $appDataRoot ".libmpv-stage-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $stagedRuntime | Out-Null
foreach ($runtimeFile in $runtimeFiles) {
    Copy-Item -LiteralPath $runtimeFile.FullName -Destination $stagedRuntime
}

$manifest = [ordered]@{
    source = $downloadUri
    release = $releaseTag
    archive = $archiveName
    archiveSha256 = $archiveSha256
    librarySha256 = $librarySha256
    purpose = 'Local development and playback testing only. Not bundled or redistributed by this script.'
}
$manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $stagedRuntime 'runtime-manifest.json') -Encoding UTF8

$backupRuntime = $null
if (Test-Path -LiteralPath $runtimeDirectory -PathType Container) {
    $backupRuntime = Join-Path $appDataRoot "libmpv.backup-$([DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss'))-$([guid]::NewGuid().ToString('N'))"
    Move-Item -LiteralPath $runtimeDirectory -Destination $backupRuntime
}

try {
    Move-Item -LiteralPath $stagedRuntime -Destination $runtimeDirectory
} catch {
    if ($backupRuntime -and (Test-Path -LiteralPath $backupRuntime -PathType Container) -and -not (Test-Path -LiteralPath $runtimeDirectory)) {
        Move-Item -LiteralPath $backupRuntime -Destination $runtimeDirectory
    }
    throw
}

Write-Output "libmpv installed for the current user: $runtimeLibrary"
Write-Output "Archive SHA-256: $actualArchiveHash"
Write-Output "DLL SHA-256: $actualLibraryHash"
Write-Output 'The app resolves this private runtime automatically. It is not included in the EXE, installer, or Git repository.'
