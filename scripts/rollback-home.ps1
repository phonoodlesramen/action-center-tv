#Requires -Version 7.0

<#
.SYNOPSIS
Restores the Home you had before set-default-home.ps1, without a reboot.

.DESCRIPTION
Uses what set-default-home.ps1 saved for this device, asking before each change:
  1. turns back on any other launcher's Home watcher it turned off;
  2. re-enables the stock launcher if it was enabled before (or if nothing was saved);
  3. makes the previous launcher (Projectivy, say) the saved Home again.
Then presses Home and verifies. Works over network ADB even if Action Center is
crash-looping.

.EXAMPLE
./scripts/rollback-home.ps1
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param([string]$Ip)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial

$recordPath = Get-HomeRecordPath $serial
$record = Read-HomeRecord $serial
$previous = if ($record) { $record.previousHome } else { $null }
if (-not $record) { Write-Host 'Nothing saved for this device: restoring the stock launcher.' }

foreach ($watcher in @(if ($record) { $record.removedAccessibility })) {
    if (-not $watcher) { continue }
    $current = Get-AccessibilityServices $serial
    if ($current -contains $watcher) { continue }
    Write-Host "Turning the other launcher's Home watcher back on:"
    Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
        -Command (Get-AccessibilityListCommand (@($current) + $watcher)) `
        -Undo (Get-AccessibilityListCommand $current) | Out-Null
}

$restoreStock = (-not $record) -or $record.stockLauncherWasEnabled -or (-not $previous)
if ($restoreStock) {
    if ((Get-DisabledPackages $serial) -notcontains $StockLauncher) {
        Write-Host "$StockLauncher is already enabled."
    } else {
        Write-Host 'Re-enabling the stock launcher:'
        $changed = Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
            -Command "pm enable $StockLauncher" -Undo "pm disable-user --user 0 $StockLauncher"
        if (-not $changed) { exit 0 }
    }
}

if ($previous -and $previous -notlike "$StockLauncher/*") {
    Write-Host "Making $previous the saved Home again:"
    $changed = Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
        -Command "cmd package set-home-activity --user 0 $previous" `
        -Undo "cmd package set-home-activity --user 0 $OurHome"
    if (-not $changed) { exit 0 }
}

Invoke-AdbShell -Serial $serial -Command 'input keyevent KEYCODE_HOME' | Out-Null
$homeActivity = Get-HomeActivity $serial
$expected = if ($previous) { $previous } else { "$StockLauncher/.MainActivity" }
if (($homeActivity -split '/')[0] -eq ($expected -split '/')[0]) {
    Write-Result $true "Home is $homeActivity again."
    if (Test-Path $recordPath) { Remove-Item -Path $recordPath }
    Write-Host 'Wait about 20 seconds before rebooting: Android saves package state lazily.' -ForegroundColor Yellow
    exit 0
}
Write-Result $false "Home resolves to '$homeActivity', expected $expected."
exit 1
