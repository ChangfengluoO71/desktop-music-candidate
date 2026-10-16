param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\LICENSES\jaudiotagger-3.0.1-sources.jar')
)

$ErrorActionPreference = 'Stop'

$expectedSha256 = 'D9C79A145944E6BC37579403843C9DA84CD81459E42C9877351802EE284F5858'
$sourceUri = 'https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1-sources.jar'
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$parent = Split-Path -Parent $destinationPath
New-Item -ItemType Directory -Path $parent -Force | Out-Null

$temporaryPath = "$destinationPath.download-$([guid]::NewGuid().ToString('N'))"
try {
    Invoke-WebRequest -Uri $sourceUri -OutFile $temporaryPath
    $actualSha256 = (Get-FileHash -LiteralPath $temporaryPath -Algorithm SHA256).Hash.ToUpperInvariant()
    if ($actualSha256 -ne $expectedSha256) {
        throw "jaudiotagger source JAR SHA-256 mismatch: expected $expectedSha256, got $actualSha256."
    }

    Move-Item -LiteralPath $temporaryPath -Destination $destinationPath -Force
    Write-Output "Verified net.jthink:jaudiotagger:3.0.1 source JAR: $destinationPath ($actualSha256)"
}
finally {
    if (Test-Path -LiteralPath $temporaryPath) {
        Remove-Item -LiteralPath $temporaryPath -Force
    }
}
