#Requires -Version 7.0

<#
.SYNOPSIS
Makes Action Center the Shield's home screen. Other launchers stay installed.

.DESCRIPTION
Saves what Home was first (per device, for rollback-home.ps1), then does what's
needed, asking before each change:
  1. disables com.google.android.tvlauncher if it's enabled: its HOME priority
     beats any sideloaded launcher (research §2);
  2. makes Action Center the saved Home when other launchers (Projectivy, say)
     are installed: `cmd package set-home-activity`;
  3. offers to turn off other launchers' accessibility "Home watchers", which
     would take Home back. Only their entries are removed from the list.
Then presses Home and verifies. Undo with rollback-home.ps1.

.EXAMPLE
./scripts/set-default-home.ps1
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param([string]$Ip)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial
# Before anything changes: on a Google TV, Home stays with its launcher (HomeWatch instead).
Assert-NotGoogleTv $serial

if (-not (Test-PackageInstalled $serial $OurPackage)) {
    throw "Action Center isn't installed on the Shield, so Home would have nowhere to go. Run scripts/install.ps1 first."
}

# Remember what Home was, unless a record already exists (re-runs keep the first one).
$recordPath = Get-HomeRecordPath $serial
$record = Read-HomeRecord $serial
$before = Get-HomeActivity $serial
$stockEnabled = (Get-DisabledPackages $serial) -notcontains $StockLauncher
if (-not $record) {
    $previous = if (Test-RealHome $before) { $before } else { Get-PreferredHome $serial }
    if ($previous -like "$OurPackage/*") { $previous = $null }
    $record = @{ previousHome = $previous; stockLauncherWasEnabled = $stockEnabled; removedAccessibility = @() }
    Write-Host "Home before: $(if ($previous) { $previous } else { 'unknown' }). Saved for rollback-home.ps1."
    $record | ConvertTo-Json | Set-Content -Path $recordPath
}

if ($stockEnabled) {
    Write-Host 'Disabling the stock launcher so Home opens Action Center:'
    $changed = Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
        -Command "pm disable-user --user 0 $StockLauncher" -Undo "pm enable $StockLauncher"
    if (-not $changed) { exit 0 }
}

if ((Get-HomeActivity $serial) -notlike "$OurPackage/*") {
    $previous = $record.previousHome
    $undo = if ($previous -and $previous -notlike "$StockLauncher/*") { "cmd package set-home-activity --user 0 $previous" } else { "pm enable $StockLauncher" }
    Write-Host 'Making Action Center the saved Home (other launchers stay installed):'
    $changed = Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
        -Command "cmd package set-home-activity --user 0 $OurHome" -Undo $undo
    if (-not $changed) { exit 0 }
}

foreach ($watcher in Get-LauncherWatchers $serial) {
    $current = Get-AccessibilityServices $serial
    $remaining = @($current | Where-Object { $_ -ne $watcher })
    Write-Host "Turning off another launcher's Home watcher, which would take Home back:"
    $changed = Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
        -Command (Get-AccessibilityListCommand $remaining) -Undo (Get-AccessibilityListCommand $current)
    if ($changed) {
        $record.removedAccessibility = @($record.removedAccessibility) + $watcher
        $record | ConvertTo-Json | Set-Content -Path $recordPath
    } else {
        Write-Warning "$watcher stays on, so it may take Home back from Action Center."
    }
}

Invoke-AdbShell -Serial $serial -Command 'input keyevent KEYCODE_HOME' | Out-Null
$homeActivity = Get-HomeActivity $serial
if ($homeActivity -like "$OurPackage/*") {
    Write-Result $true "Home opens $homeActivity. Undo any time with scripts/rollback-home.ps1."
    exit 0
}
Write-Result $false "Home resolves to '$homeActivity'. Run scripts/rollback-home.ps1 to restore the previous Home."
exit 1
