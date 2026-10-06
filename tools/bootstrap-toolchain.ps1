<#
.SYNOPSIS
    Fetch the self-contained Android toolchain into .\toolchain\.

.DESCRIPTION
    Downloads JDK 17, the Android SDK command-line tools, platform 34,
    build-tools 34.0.0 and Gradle 8.7, then unpacks them under .\toolchain\.

    This exists because the toolchain is ~2.2 GB and is deliberately NOT
    committed (see .gitignore). build.cmd prefers .\toolchain\ when present and
    falls back to the machine's JAVA_HOME/ANDROID_HOME otherwise, so this script
    is optional — it is for reproducing a release build exactly, or for working
    without a system-wide install.

    Downloads go through Node's TLS stack when Node is available, because some
    locked-down Windows setups have a broken Schannel that makes curl and the
    JVM fail certificate validation. If Node is missing, it falls back to
    Invoke-WebRequest.

.PARAMETER Force
    Re-download even when a component already looks present.

.EXAMPLE
    pwsh -File tools/bootstrap-toolchain.ps1
#>
[CmdletBinding()]
param(
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = Split-Path -Parent $PSScriptRoot
$tc = Join-Path $root 'toolchain'

# --- versions ---------------------------------------------------------------
# Pinned so a rebuild months from now produces the same toolchain.
$jdkUrl = 'https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse'
$cmdlineToolsUrl = 'https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip'
$gradleUrl = 'https://services.gradle.org/distributions/gradle-8.7-bin.zip'
$platformZip = 'https://dl.google.com/android/repository/platform-34-ext7_r03.zip'
$buildToolsZip = 'https://dl.google.com/android/repository/build-tools_r34-windows.zip'
$platformToolsZip = 'https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip'

function Write-Step($message) {
    Write-Host ""
    Write-Host "==> $message" -ForegroundColor Cyan
}

function Get-NodePath {
    $candidates = @(
        (Get-Command node -ErrorAction SilentlyContinue).Source,
        (Join-Path $env:USERPROFILE '.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\node\bin\node.exe')
    ) | Where-Object { $_ -and (Test-Path $_) }
    return $candidates | Select-Object -First 1
}

<#
    Download with the best available transport.

    Node first: its bundled OpenSSL works in environments where Windows
    Schannel is misconfigured (a real failure mode — curl returns
    SEC_E_NO_CREDENTIALS and the JVM throws PKIX path building failures).
#>
function Get-File($url, $destination) {
    if ((Test-Path $destination) -and -not $Force) {
        $size = (Get-Item $destination).Length
        if ($size -gt 1MB) {
            Write-Host "    cached: $(Split-Path -Leaf $destination) ($([math]::Round($size/1MB,1)) MB)"
            return
        }
    }

    $name = Split-Path -Leaf $destination
    $node = Get-NodePath

    if ($node) {
        $downloader = Join-Path $PSScriptRoot 'download.mjs'
        if (-not (Test-Path $downloader)) {
            throw "Missing $downloader; cannot use the Node download path."
        }
        Write-Host "    fetching $name (via Node)"
        & $node $downloader $url $destination
        if ($LASTEXITCODE -ne 0) { throw "Download failed: $url" }
    }
    else {
        Write-Host "    fetching $name (via Invoke-WebRequest)"
        $ProgressPreference = 'SilentlyContinue'
        Invoke-WebRequest -Uri $url -OutFile $destination -UseBasicParsing
    }
}

function Expand-Zip($zip, $destination) {
    # Expand-Archive only accepts .zip, so normalise the extension.
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("dsh-x-" + [guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $tops = @(Get-ChildItem $tmp -Directory)
        if ($tops.Count -ne 1) {
            throw "Expected exactly one top-level folder in $zip, found $($tops.Count)"
        }
        if (Test-Path $destination) { Remove-Item -Recurse -Force $destination }
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
        Move-Item $tops[0].FullName $destination
    }
    finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

New-Item -ItemType Directory -Force -Path $tc | Out-Null

# --- JDK --------------------------------------------------------------------
Write-Step 'JDK 17'
$jdkRoot = Join-Path $tc 'jdk'
$haveJdk = Get-ChildItem $jdkRoot -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin\java.exe') } |
    Select-Object -First 1
if ($haveJdk -and -not $Force) {
    Write-Host "    cached: $($haveJdk.Name)"
}
else {
    $jdkZip = Join-Path $tc 'jdk17.zip'
    Get-File $jdkUrl $jdkZip
    Expand-Zip $jdkZip $jdkRoot
    Write-Host "    unpacked to $jdkRoot"
}

# --- Gradle -----------------------------------------------------------------
Write-Step 'Gradle 8.7'
$gradleDir = Join-Path $tc 'gradle-8.7'
if ((Test-Path (Join-Path $gradleDir 'bin\gradle.bat')) -and -not $Force) {
    Write-Host '    cached'
}
else {
    $gradleZip = Join-Path $tc 'gradle-8.7-bin.zip'
    Get-File $gradleUrl $gradleZip
    Expand-Archive -Path $gradleZip -DestinationPath $tc -Force
    Write-Host "    unpacked to $gradleDir"
}

# --- Android SDK ------------------------------------------------------------
Write-Step 'Android SDK platform-tools'
$sdk = Join-Path $tc 'sdk'
if ((Test-Path (Join-Path $sdk 'platform-tools\adb.exe')) -and -not $Force) {
    Write-Host '    cached'
}
else {
    $zip = Join-Path $tc 'platform-tools.zip'
    Get-File $platformToolsZip $zip
    Expand-Zip $zip (Join-Path $sdk 'platform-tools')
}

Write-Step 'Android SDK platform 34'
if ((Test-Path (Join-Path $sdk 'platforms\android-34\android.jar')) -and -not $Force) {
    Write-Host '    cached'
}
else {
    $zip = Join-Path $tc 'platform-34.zip'
    Get-File $platformZip $zip
    Expand-Zip $zip (Join-Path $sdk 'platforms\android-34')
}

Write-Step 'Android build-tools 34.0.0'
if ((Test-Path (Join-Path $sdk 'build-tools\34.0.0\aapt2.exe')) -and -not $Force) {
    Write-Host '    cached'
}
else {
    $zip = Join-Path $tc 'build-tools-34.zip'
    Get-File $buildToolsZip $zip
    Expand-Zip $zip (Join-Path $sdk 'build-tools\34.0.0')
}

# --- done -------------------------------------------------------------------
Write-Step 'Toolchain ready'
Write-Host "    $tc"
Write-Host ''
Write-Host '    Next: build.cmd assembleRelease'
Write-Host ''
Write-Host '    These archives stay on disk so a rebuild needs no network:'
Get-ChildItem $tc -Filter '*.zip' -ErrorAction SilentlyContinue |
    ForEach-Object { Write-Host "      $($_.Name) ($([math]::Round($_.Length/1MB,1)) MB)" }
Write-Host ''
