#Requires -Version 7.0

<#
.SYNOPSIS
Writes reports/device-report.md with the TV's real values. reports/ is git-ignored:
the report describes your TV, so it stays out of the repository.

.DESCRIPTION
Read-only on the Shield. Collects the build, display size and density, RAM, HOME
resolution, disabled and third-party packages, the versions of Action Center, Plezy,
Plex and YouTube, and Action Center's latest probe lines from logcat.
Serial numbers are left out.

.EXAMPLE
./scripts/device-report.ps1
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Ip,
    [string]$OutFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'reports/device-report.md')
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial

function Read-Shell([string]$Command) { @(Invoke-AdbShell -Serial $serial -Command $Command -AllowFailure) }

function Get-VersionName([string]$Package) {
    $match = Read-Shell "dumpsys package $Package" | Select-String -Pattern 'versionName=(\S+)' | Select-Object -First 1
    if ($match) { $match.Matches[0].Groups[1].Value } else { 'not installed' }
}

$props = Read-Shell 'getprop' | Where-Object {
    $_ -match '^\[(ro\.(build|product|vendor\.build)\.[^\]]+|[^\]]*(nvidia|shield)[^\]]*)\]' -and $_ -notmatch '(?i)serial'
}
$memory = Read-Shell 'cat /proc/meminfo' | Where-Object { $_ -match '^(MemTotal|MemAvailable):' }
$versions = foreach ($package in $OurPackage, 'com.edde746.plezy', 'com.plexapp.android', 'com.google.android.youtube.tv', $StockLauncher) {
    "| ``$package`` | $(Get-VersionName $package) |"
}

$md = @"
# Shield Device Report
*<small>(generated $(Get-Date -Format 'MM/dd/yyyy') by scripts/device-report.ps1)</small>*

## Display and memory

| What | Value |
|---|---|
| Size | $((Read-Shell 'wm size') -join '; ') |
| Density | $((Read-Shell 'wm density') -join '; ') |
| Memory | $($memory -join '; ') |
| Low-RAM flag | $((Read-Shell 'getprop ro.config.low_ram') -join '') |

## Home

- HOME resolves to: ``$(Get-HomeActivity $serial)``
- Disabled packages: $((Get-DisabledPackages $serial | ForEach-Object { "``$_``" }) -join ', ')

## App versions

| Package | Version |
|---|---|
$($versions -join "`n")

## Build properties

``````
$($props -join "`n")
``````

## Third-party packages

``````
$((Read-Shell 'pm list packages -3' | ForEach-Object { $_ -replace '^package:', '' } | Sort-Object) -join "`n")
``````

## Action Center probe lines (latest)

``````
$((Read-Shell 'logcat -d -s ActionCenterProbe:I' | Select-Object -Last 40) -join "`n")
``````
"@

New-Item -ItemType Directory -Force -Path (Split-Path $OutFile -Parent) | Out-Null
Set-Content -Path $OutFile -Value $md
Write-Host "Wrote $OutFile"
exit 0
