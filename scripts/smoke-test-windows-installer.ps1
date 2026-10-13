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
    if (-not (Test-Path -LiteralPath $uninstaller -PathType Leaf)) {
        throw 'Inno Setup uninstaller was not registered in the install directory.'
    }

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

    Write-Output 'Installer smoke test passed: custom directory, bundled runtime, Start Menu and desktop shortcuts, uninstall, and user-data preservation.'
}
finally {
    $env:APPDATA = $originalAppData
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
