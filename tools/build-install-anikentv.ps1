#requires -Version 5.1
<#
    Build and install the original Aniken/Anilili Android TV snapshot.

    Examples:
      powershell -ExecutionPolicy Bypass -File .\build-install-anikentv.ps1
      powershell -ExecutionPolicy Bypass -File .\build-install-anikentv.ps1 -Tv 192.168.8.150:5555
      powershell -ExecutionPolicy Bypass -File .\build-install-anikentv.ps1 -RepoDirectory C:\Projects\anilibria-app

    Requires Git, JDK 17+, Android SDK command-line tools and ADB.
    Gradle 8.13 is downloaded and SHA-256 verified if not already installed.
#>

[CmdletBinding()]
param(
    [string]$RepoDirectory = (Join-Path $PSScriptRoot 'anilibria-app'),
    [string]$Tv = '192.168.8.133:5555',
    [string]$Branch = 'experiment/anikentv-original'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$RepositoryUrl = 'https://github.com/ivan-s-2001/anilibria-app.git'
$GradleVersion = '8.13'

function Run {
    param(
        [Parameter(Mandatory = $true)][string]$Command,
        [Parameter(Mandatory = $true)][string[]]$Arguments
    )

    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed (exit $LASTEXITCODE): $Command $($Arguments -join ' ')"
    }
}

function Find-SdkManager {
    param([string]$Sdk)

    $candidate = Join-Path $Sdk 'cmdline-tools\latest\bin\sdkmanager.bat'
    if (Test-Path $candidate) { return $candidate }

    $versions = @(Get-ChildItem (Join-Path $Sdk 'cmdline-tools') -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending)
    foreach ($version in $versions) {
        $candidate = Join-Path $version.FullName 'bin\sdkmanager.bat'
        if (Test-Path $candidate) { return $candidate }
    }

    $command = Get-Command sdkmanager -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    return $null
}

Write-Host "`n[1/5] Repository and branch" -ForegroundColor Cyan
if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
    throw 'Git was not found. Install Git for Windows and restart PowerShell.'
}

$RepoDirectory = [System.IO.Path]::GetFullPath($RepoDirectory)
if (Test-Path (Join-Path $RepoDirectory '.git')) {

    $origin = (& git -C $RepoDirectory remote get-url origin | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to read Git origin.' }
    if ($origin -notmatch '^(https://github\.com/|git@github\.com:)ivan-s-2001/anilibria-app(\.git)?$') {
        throw "Different repository found at the target path: $origin"
    }

    $changes = @(& git -C $RepoDirectory status --porcelain --untracked-files=normal)
    if ($LASTEXITCODE -ne 0) { throw 'Unable to check the worktree.' }
    if ($changes.Count -gt 0 -and $changes[0]) {
        throw 'Local changes found. Commit, stash or move them before updating; nothing was overwritten.'
    }

    Run -Command git -Arguments @('-C', $RepoDirectory, 'fetch', 'origin', $Branch)
    $current = (& git -C $RepoDirectory branch --show-current | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Unable to check current branch.' }
    if ($current -ne $Branch) {
        & git -C $RepoDirectory show-ref --verify --quiet "refs/heads/$Branch"
        if ($LASTEXITCODE -eq 0) {
            Run -Command git -Arguments @('-C', $RepoDirectory, 'switch', $Branch)
        } else {
            Run -Command git -Arguments @('-C', $RepoDirectory, 'switch', '--track', '-c', $Branch, "origin/$Branch")
        }
    }
    Run -Command git -Arguments @('-C', $RepoDirectory, 'pull', '--ff-only', 'origin', $Branch)
} else {
    if (Test-Path $RepoDirectory) {
        $existing = @(Get-ChildItem -Path $RepoDirectory -Force)
        if ($existing.Count -gt 0) {
            throw "Folder exists but contains another project or files: $RepoDirectory"
        }
    }
    $parent = Split-Path -Parent $RepoDirectory
    if (-not (Test-Path $parent)) {
        New-Item -ItemType Directory -Path $parent -Force | Out-Null
    }
    Run -Command git -Arguments @('clone', '--branch', $Branch, '--single-branch', $RepositoryUrl, $RepoDirectory)
}

$ProjectDirectory = Join-Path $RepoDirectory 'anikentv-release'
if (-not (Test-Path (Join-Path $ProjectDirectory 'app\build.gradle.kts'))) {
    throw "Aniken/Anilili source not found at: $ProjectDirectory"
}

Write-Host "`n[2/5] Java and Android SDK" -ForegroundColor Cyan
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    throw 'Java not found. Install JDK 17 (e.g. Temurin 17) and set JAVA_HOME/PATH.'
}
if (-not (Get-Command javac -ErrorAction SilentlyContinue)) {
    throw 'JDK compiler not found. Install JDK 17, not only a JRE.'
}
$javacVersion = (& javac -version 2>&1 | Out-String)
if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch 'javac\s+(\d+)') {
    throw 'Unable to determine JDK version.'
}
if ([int]$Matches[1] -lt 17) {
    throw "JDK 17+ required; detected: $javacVersion"
}

$sdkCandidates = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
if ($env:LOCALAPPDATA) {
    $sdkCandidates += (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
}
$SdkDirectory = $sdkCandidates | Where-Object { $_ -and (Test-Path $_ -PathType Container) } |
    Select-Object -First 1
if (-not $SdkDirectory) {
    throw 'Android SDK not found. Install Android Studio + SDK, or set ANDROID_HOME.'
}
$env:ANDROID_HOME = $SdkDirectory
$env:ANDROID_SDK_ROOT = $SdkDirectory

$packages = @()
if (-not (Test-Path (Join-Path $SdkDirectory 'platforms\android-36\android.jar'))) {
    $packages += 'platforms;android-36'
}
if (-not (Test-Path (Join-Path $SdkDirectory 'build-tools\35.0.1\aapt2.exe'))) {
    $packages += 'build-tools;35.0.1'
}
if (-not (Test-Path (Join-Path $SdkDirectory 'platform-tools\adb.exe'))) {
    $packages += 'platform-tools'
}
if ($packages.Count -gt 0) {
    $sdkManager = Find-SdkManager $SdkDirectory
    if (-not $sdkManager) {
        throw "SDK packages missing ($($packages -join ', ')). Install Android SDK Command-line Tools in Android Studio."
    }
    Write-Host "Installing SDK packages: $($packages -join ', ')"
    Write-Host 'If Android requests license acceptance, follow the console prompt.'
    Run -Command $sdkManager -Arguments (@("--sdk_root=$SdkDirectory") + $packages)
}

Write-Host "`n[3/5] Gradle $GradleVersion" -ForegroundColor Cyan
$gradle = Get-Command gradle -ErrorAction SilentlyContinue
$GradleExecutable = $null
if ($gradle) {
    $versionOutput = (& $gradle.Source --version | Out-String)
    if ($LASTEXITCODE -eq 0 -and $versionOutput -match "(?m)^Gradle $([regex]::Escape($GradleVersion))\s*$") {
        $GradleExecutable = $gradle.Source
    }
}

if (-not $GradleExecutable) {
    $cacheBase = if ($env:LOCALAPPDATA) { $env:LOCALAPPDATA } else { $env:TEMP }
    $cache = Join-Path $cacheBase 'AnikenTVBuild\gradle'
    $GradleExecutable = Join-Path $cache "gradle-$GradleVersion\bin\gradle.bat"
    if (-not (Test-Path $GradleExecutable)) {
        New-Item -ItemType Directory -Path $cache -Force | Out-Null
        $archive = Join-Path $cache "gradle-$GradleVersion-bin.zip"
        $url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
        $expected = (Invoke-WebRequest -Uri "$url.sha256" -UseBasicParsing).Content.Trim().Split()[0]

        if ((-not (Test-Path $archive)) -or
            ((Get-FileHash -Path $archive -Algorithm SHA256).Hash -ne $expected)) {
            Write-Host "Downloading official Gradle $GradleVersion (one-time setup)..."
            Invoke-WebRequest -Uri $url -UseBasicParsing -OutFile $archive
        }
        $actual = (Get-FileHash -Path $archive -Algorithm SHA256).Hash
        if ($actual -ne $expected) {
            throw 'Gradle download failed SHA-256 verification.'
        }
        Expand-Archive -Path $archive -DestinationPath $cache -Force
        if (-not (Test-Path $GradleExecutable)) {
            throw 'Gradle installation is incomplete.'
        }
    }
}

Write-Host "`n[4/5] Build debug APK" -ForegroundColor Cyan
Run -Command $GradleExecutable -Arguments @('--project-dir', $ProjectDirectory, ':app:assembleDebug', '--no-daemon', '--console=plain')

$outputDirectory = Join-Path $ProjectDirectory 'app\build\outputs\apk\debug'
$apk = Get-ChildItem -Path $outputDirectory -Filter '*.apk' -File -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $apk) { throw "Gradle succeeded but no APK found in $outputDirectory" }
Write-Host "APK: $($apk.FullName)" -ForegroundColor Green

Write-Host "`n[5/5] Install to Android TV" -ForegroundColor Cyan
$adbPath = Join-Path $SdkDirectory 'platform-tools\adb.exe'
if (-not (Test-Path $adbPath)) {
    $adb = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adb) { throw 'ADB not found. Install Android SDK Platform-tools.' }
    $adbPath = $adb.Source
}

if ($Tv -match '^[^:]+:\d+$') {
    Write-Host "Connecting to TV $Tv..."
    & $adbPath connect $Tv
}
Run -Command $adbPath -Arguments @('-s', $Tv, 'get-state')
Run -Command $adbPath -Arguments @('-s', $Tv, 'install', '-r', $apk.FullName)

Write-Host "`nDone! Debug APK installed on $Tv" -ForegroundColor Green
Write-Host 'App ID: com.miruronative (original Anilili snapshot)'
Write-Host "APK saved at: $($apk.FullName)"
