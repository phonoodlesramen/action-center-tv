#Requires -Version 7.0

<#
.SYNOPSIS
Connects to the Shield over network ADB and remembers its address for the other scripts.

.DESCRIPTION
Runs `adb connect <ip>:5555`, checks the Shield has authorised this PC, and saves the
address in scripts/.shield-ip (git-ignored). Changes nothing on the Shield.

.EXAMPLE
./scripts/connect.ps1 -Ip 192.168.1.50

.EXAMPLE
./scripts/connect.ps1 -Ip emulator-5554
Targets an Android TV emulator instead, for rehearsing (no adb connect needed).
#>
[CmdletBinding(SupportsShouldProcess)]
param([string]$Ip)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$serial = Get-ShieldSerial $Ip
if (Test-NetworkSerial $serial) {
    Invoke-Adb -Arguments 'connect', $serial | ForEach-Object { Write-Host $_ }
}
Assert-ShieldReady $serial
Set-Content -Path (Get-IpFile) -Value $serial
Write-Host "Connected to the Shield at $serial. The other scripts will use this address." -ForegroundColor Green
exit 0
