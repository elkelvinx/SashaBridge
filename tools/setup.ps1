<#
.SYNOPSIS
    Prepares a fresh copy of SashaBridge: downloads every dependency and builds both projects.

.DESCRIPTION
    This repository has no single package manager, so this script plays the role that
    "npm install" plays in a Node project:

      1. Checks the prerequisites: git, cargo, a JDK and the Android SDK. The JDK and the SDK
         are looked up in JAVA_HOME / ANDROID_HOME first and then in the default locations of
         Android Studio.
      2. Downloads scrcpy with fetch-scrcpy.ps1, the only dependency without a package manager.
      3. Builds the PC client. cargo downloads the crates pinned in Cargo.lock.
      4. Writes sashabridge-android\local.properties if it is missing and builds the Android
         app. The Gradle wrapper downloads Gradle itself, the Kotlin compiler and the libraries.
      5. With -InstallApp, installs the app on the connected phone and grants it notification
         access.

    Every step can run again safely: what is already present is not downloaded again.

.PARAMETER InstallApp
    Also installs the debug APK on the phone connected by USB and grants notification access.
    Without it the phone is not needed.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\setup.ps1

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File .\tools\setup.ps1 -InstallApp
#>
[CmdletBinding()]
param(
    [switch]$InstallApp
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$pcProject = Join-Path $repositoryRoot "sashabridge-pc"
$androidProject = Join-Path $repositoryRoot "sashabridge-android"
$listenerComponent = "io.github.elkelvinx.sashabridge/io.github.elkelvinx.sashabridge.MusicListenerService"

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message"
}

function Invoke-Native([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory) {
    # $ErrorActionPreference does not apply to the exit code of native programs, so it is
    # checked here to stop at the first failure.
    Push-Location $WorkingDirectory
    try {
        & $FilePath @Arguments
        if ($LASTEXITCODE -ne 0) {
            throw "'$FilePath $($Arguments -join ' ')' failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }
}

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    $androidStudioJdk = Join-Path $env:ProgramFiles "Android\Android Studio\jbr"
    if (Test-Path (Join-Path $androidStudioJdk "bin\java.exe")) {
        return $androidStudioJdk
    }
    throw "No JDK found. Install Android Studio, or set JAVA_HOME to a JDK 21."
}

function Resolve-AndroidSdk {
    $candidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk"))
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "platform-tools"))) {
            return $candidate
        }
    }
    throw "No Android SDK found. Open Android Studio once so that it installs the SDK, or set ANDROID_HOME."
}

Write-Step "Checking prerequisites"
foreach ($tool in "git", "cargo") {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "$tool is not installed or not in PATH. See INSTALACION.md."
    }
}
$env:JAVA_HOME = Resolve-JavaHome
$androidSdk = Resolve-AndroidSdk
Write-Host "JDK:         $env:JAVA_HOME"
Write-Host "Android SDK: $androidSdk"

Write-Step "Downloading scrcpy"
& (Join-Path $PSScriptRoot "fetch-scrcpy.ps1")

Write-Step "Building the PC client (cargo downloads the crates)"
Invoke-Native "cargo" @("build") $pcProject

Write-Step "Building the Android app (Gradle downloads its dependencies)"
$localProperties = Join-Path $androidProject "local.properties"
if (-not (Test-Path $localProperties)) {
    # Forward slashes avoid the escaping that backslashes need in a .properties file.
    Set-Content -Path $localProperties -Value ("sdk.dir=" + $androidSdk.Replace("\", "/")) -Encoding ASCII
    Write-Host "Created local.properties pointing to $androidSdk"
}
Invoke-Native (Join-Path $androidProject "gradlew.bat") @(":app:assembleDebug") $androidProject

if ($InstallApp) {
    Write-Step "Installing the app on the phone"
    $adb = Join-Path $pcProject "vendor\scrcpy\adb.exe"
    $apk = Join-Path $androidProject "app\build\outputs\apk\debug\app-debug.apk"
    Invoke-Native $adb @("install", "-r", $apk) $repositoryRoot
    Invoke-Native $adb @("shell", "cmd", "notification", "allow_listener", $listenerComponent) $repositoryRoot
}

Write-Step "Done"
Write-Host "Start the PC client with:  cd sashabridge-pc; cargo run"
if (-not $InstallApp) {
    Write-Host "Install the app on a connected phone with:  powershell -ExecutionPolicy Bypass -File .\tools\setup.ps1 -InstallApp"
}
