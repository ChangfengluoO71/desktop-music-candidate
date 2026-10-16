param(
    [Parameter(Mandatory = $true)]
    [string]$InstallerPath
)

$ErrorActionPreference = 'Stop'

$resolvedInstaller = (Resolve-Path -LiteralPath $InstallerPath -ErrorAction Stop).Path
$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) "DmcInnoSmoke-$([guid]::NewGuid().ToString('N'))"
$installDir = Join-Path $testRoot 'Custom Install Folder'
$testAppData = Join-Path $testRoot 'UserData'
$originalAppData = $env:APPDATA
$uninstaller = Join-Path $installDir 'unins000.exe'

try {
    New-Item -ItemType Directory -Path $testRoot | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $testAppData 'DesktopMusicCandidate') -Force | Out-Null
    $dataMarker = Join-Path $testAppData 'DesktopMusicCandidate\keep-after-uninstall.txt'
    Set-Content -LiteralPath $dataMarker -Value 'user data must survive app removal' -NoNewline
    $env:APPDATA = $testAppData

    $installerArgs = @(
        '/VERYSILENT',
        '/SUPPRESSMSGBOXES',
        '/NORESTART',
        ('/DIR="{0}"' -f $installDir)
    )
    $installResult = Start-Process -FilePath $resolvedInstaller -ArgumentList $installerArgs -Wait -PassThru
    if ($installResult.ExitCode -ne 0) {
        throw "Installer exited with code $($installResult.ExitCode)."
    }

    $application = Join-Path $installDir 'DesktopMusicCandidate.exe'
    $runtimeModules = Join-Path $installDir 'runtime\lib\modules'
    $runtimeJvm = Join-Path $installDir 'runtime\bin\server\jvm.dll'
    if (-not (Test-Path -LiteralPath $application -PathType Leaf)) {
        throw 'Installed application launcher is missing from the selected directory.'
    }
    if (-not (Test-Path -LiteralPath $runtimeModules -PathType Leaf) -or -not (Test-Path -LiteralPath $runtimeJvm -PathType Leaf)) {
        throw 'Bundled Java runtime is missing from the installation.'
    }
    $appDirectory = Join-Path $installDir 'app'
    $appConfiguration = Join-Path $appDirectory 'DesktopMusicCandidate.cfg'
    if (-not (Test-Path -LiteralPath $appConfiguration -PathType Leaf)) {
        throw 'Application classpath configuration is missing from the installation.'
    }
    $classpath = Get-Content -LiteralPath $appConfiguration -Raw
    $javaFxBase = Get-ChildItem -LiteralPath $appDirectory -Filter 'javafx-base-25.0.2-win-*.jar' -File | Select-Object -First 1
    $javaFxGraphics = Get-ChildItem -LiteralPath $appDirectory -Filter 'javafx-graphics-25.0.2-win-*.jar' -File | Select-Object -First 1
    $javaFxMedia = Get-ChildItem -LiteralPath $appDirectory -Filter 'javafx-media-25.0.2-win-*.jar' -File | Select-Object -First 1
    if (-not $javaFxBase -or -not $javaFxGraphics -or -not $javaFxMedia) {
        throw 'OpenJFX Windows base, graphics, or media runtime is missing from the installation.'
    }
    foreach ($javaFxJar in @($javaFxBase, $javaFxGraphics, $javaFxMedia)) {
        if (-not $classpath.Contains($javaFxJar.Name)) {
            throw "The application classpath does not reference $($javaFxJar.Name)."
        }
    }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $mediaArchive = [System.IO.Compression.ZipFile]::OpenRead($javaFxMedia.FullName)
    try {
        if (-not ($mediaArchive.Entries | Where-Object FullName -EQ 'jfxmedia.dll')) {
            throw 'OpenJFX media archive does not contain its Windows native decoder.'
        }
    }
    finally {
        $mediaArchive.Dispose()
    }
    foreach ($licensePath in @(
        (Join-Path $installDir 'licenses\LICENSE'),
        (Join-Path $installDir 'licenses\THIRD_PARTY.md'),
        (Join-Path $installDir 'licenses\GPL-2.0.txt'),
        (Join-Path $installDir 'licenses\Classpath-Exception-2.0.txt'),
        (Join-Path $installDir 'licenses\LGPL-2.1.txt'),
        (Join-Path $installDir 'licenses\libmpv\winpthreads-COPYING.txt'),
        (Join-Path $installDir 'licenses\libmpv\GCC-COPYING.RUNTIME.txt'),
        (Join-Path $installDir 'licenses\jaudiotagger-3.0.1-sources.jar')
    )) {
        if (-not (Test-Path -LiteralPath $licensePath -PathType Leaf)) {
            throw "Required bundled license notice is missing: $licensePath"
        }
    }
    $jaudiotaggerRuntime = Get-ChildItem -LiteralPath $appDirectory -Filter 'jaudiotagger-3.0.1-*.jar' -File | Select-Object -First 1
    if (-not $jaudiotaggerRuntime -or -not $classpath.Contains($jaudiotaggerRuntime.Name)) {
        throw 'The replaceable jaudiotagger 3.0.1 runtime JAR is missing from the installed classpath.'
    }
    $jaudiotaggerSource = Join-Path $installDir 'licenses\jaudiotagger-3.0.1-sources.jar'
    $jaudiotaggerSourceHash = (Get-FileHash -LiteralPath $jaudiotaggerSource -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($jaudiotaggerSourceHash -ne 'D9C79A145944E6BC37579403843C9DA84CD81459E42C9877351802EE284F5858') {
        throw 'The installed jaudiotagger 3.0.1 source JAR does not match the audited Maven Central artifact.'
    }
    if (-not (Test-Path -LiteralPath $uninstaller -PathType Leaf)) {
        throw 'Inno Setup uninstaller was not registered in the install directory.'
    }

    & (Join-Path $PSScriptRoot 'assert-release-payload.ps1') -InstallRoot $installDir

    $startMenu = Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs'
    $startMenuLink = Get-ChildItem -LiteralPath $startMenu -Filter 'Desktop Music Candidate.lnk' -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $startMenuLink) {
        throw 'Start Menu shortcut was not created.'
    }
    $shell = New-Object -ComObject WScript.Shell
    $startMenuTarget = $shell.CreateShortcut($startMenuLink.FullName).TargetPath
    if (-not [string]::Equals($startMenuTarget, $application, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw 'Start Menu shortcut does not target the installed application.'
    }
    $commonDesktop = [System.Environment]::GetFolderPath([System.Environment+SpecialFolder]::CommonDesktopDirectory)
    if (-not (Test-Path -LiteralPath (Join-Path $commonDesktop 'Desktop Music Candidate.lnk') -PathType Leaf)) {
        throw 'Optional desktop shortcut was not created with the default task selection.'
    }

    $applicationProcess = Start-Process -FilePath $application -WorkingDirectory $installDir -PassThru -WindowStyle Hidden
    try {
        Start-Sleep -Seconds 10
        $applicationProcess.Refresh()
        if ($applicationProcess.HasExited) {
            throw "Installed application exited during the startup smoke test (exit code $($applicationProcess.ExitCode))."
        }
    }
    finally {
        $applicationProcess.Refresh()
        if (-not $applicationProcess.HasExited) {
            Stop-Process -Id $applicationProcess.Id -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $applicationProcess.Id -Timeout 10 -ErrorAction SilentlyContinue
        }
    }

    $uninstallResult = Start-Process -FilePath $uninstaller -ArgumentList @('/VERYSILENT', '/SUPPRESSMSGBOXES', '/NORESTART') -Wait -PassThru
    if ($uninstallResult.ExitCode -ne 0) {
        throw "Uninstaller exited with code $($uninstallResult.ExitCode)."
    }
    if (Test-Path -LiteralPath $application) {
        throw 'Application files remain after uninstall.'
    }
    if (-not (Test-Path -LiteralPath $dataMarker -PathType Leaf)) {
        throw 'Uninstall removed user data from APPDATA.'
    }

    Write-Output 'Installer smoke test passed: custom directory, Java runtime version and app startup, OpenJFX native media decoder, LGPL runtime/source/import hashes, license notices, Start Menu and desktop shortcuts, uninstall, and user-data preservation.'
}
finally {
    $env:APPDATA = $originalAppData
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
