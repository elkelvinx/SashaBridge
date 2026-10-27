<#
.SYNOPSIS
    Downloads the pinned scrcpy release for Windows x64 into the PC client's vendor directory.

.DESCRIPTION
    The scrcpy binaries (scrcpy.exe, adb.exe and their DLLs) are third-party artifacts and are
    not tracked by version control. This script makes the development environment reproducible:

      1. Downloads the official release archive from the Genymobile/scrcpy GitHub releases.
      2. Verifies the archive against the pinned SHA-256 digest. A mismatch aborts the script
         and nothing is written to the destination.
      3. Extracts the archive into a fresh temporary directory, never into the destination.
      4. Replaces the destination contents with the extracted files and writes a VERSION marker.

    Running the script again is a no-op when the destination already holds the pinned version,
    unless -Force is given.

.PARAMETER Version
    scrcpy release tag without the leading "v". Changing it requires updating -ExpectedSha256.

.PARAMETER ExpectedSha256
    Lowercase hexadecimal SHA-256 digest of scrcpy-win64-v<Version>.zip, as published by GitHub
    for the release asset.

.PARAMETER Destination
    Directory that receives the binaries. Defaults to sashabridge-pc\vendor\scrcpy relative to
    the repository root.

.PARAMETER Force
    Re-downloads and replaces the destination even when the VERSION marker already matches.

.EXAMPLE
    .\tools\fetch-scrcpy.ps1

.EXAMPLE
    .\tools\fetch-scrcpy.ps1 -Destination "D:\tools\scrcpy" -Force
#>
[CmdletBinding()]
param(
    [string]$Version = "4.0",
    [string]$ExpectedSha256 = "75dbeb5b00e6f64292f26f70900ae55ca397786bdfb0b9bbeb481a0549047457",
    [string]$Destination = (Join-Path $PSScriptRoot "..\sashabridge-pc\vendor\scrcpy"),
    [switch]$Force
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$archiveName = "scrcpy-win64-v$Version"
$downloadUrl = "https://github.com/Genymobile/scrcpy/releases/download/v$Version/$archiveName.zip"
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$versionMarker = Join-Path $destinationPath "VERSION"

if (-not $Force -and (Test-Path $versionMarker) -and ((Get-Content $versionMarker -Raw).Trim() -eq $Version)) {
    Write-Host "scrcpy $Version is already present in $destinationPath"
    return
}

# Work in an isolated, empty directory so that the downloaded content never mixes with other files.
$workDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("sashabridge-scrcpy-" + [guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $workDirectory | Out-Null

try {
    $archivePath = Join-Path $workDirectory "$archiveName.zip"
    Write-Host "Downloading $downloadUrl"
    Invoke-WebRequest -Uri $downloadUrl -OutFile $archivePath -UseBasicParsing

    $actualSha256 = (Get-FileHash -Path $archivePath -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actualSha256 -ne $ExpectedSha256.ToLowerInvariant()) {
        throw "SHA-256 mismatch for $archiveName.zip. Expected $ExpectedSha256, got $actualSha256. The destination was not modified."
    }
    Write-Host "SHA-256 verified: $actualSha256"

    $extractDirectory = Join-Path $workDirectory "extracted"
    Expand-Archive -Path $archivePath -DestinationPath $extractDirectory

    # Official archives contain a single top-level folder named after the release.
    $payloadDirectory = Join-Path $extractDirectory $archiveName
    if (-not (Test-Path (Join-Path $payloadDirectory "scrcpy.exe"))) {
        throw "Unexpected archive layout: scrcpy.exe not found under $archiveName."
    }

    if (Test-Path $destinationPath) {
        Remove-Item -Path $destinationPath -Recurse -Force
    }
    New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null
    Copy-Item -Path (Join-Path $payloadDirectory "*") -Destination $destinationPath -Recurse
    Set-Content -Path $versionMarker -Value $Version -NoNewline

    Write-Host "scrcpy $Version installed in $destinationPath"
}
finally {
    Remove-Item -Path $workDirectory -Recurse -Force -ErrorAction SilentlyContinue
}
