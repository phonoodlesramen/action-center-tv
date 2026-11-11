#Requires -Version 7.0

<#
.SYNOPSIS
Grants Action Center the permissions a sideloaded app can't ask for itself.

.DESCRIPTION
  GET_USAGE_STATS (app op)   lets the launchpad order apps by use (Phase 4)
  READ_TV_LISTINGS           lets the Watch Next probe read other apps' rows
  READ_EXTERNAL_STORAGE      lets wallpapers and app banners read your folders
  REQUEST_INSTALL_PACKAGES   (app op, "Install unknown apps") lets Settings › About install
                             Action Center's own updates, and nothing else
Use -Revoke to undo exactly these changes.

.EXAMPLE
./scripts/grant-permissions.ps1
./scripts/grant-permissions.ps1 -Revoke
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [string]$Ip,
    [switch]$Revoke
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$changes = @(
    @{ Do = "appops set $OurPackage GET_USAGE_STATS allow"; Undo = "appops set $OurPackage GET_USAGE_STATS default" }
    @{ Do = "pm grant $OurPackage android.permission.READ_TV_LISTINGS"; Undo = "pm revoke $OurPackage android.permission.READ_TV_LISTINGS" }
    @{ Do = "pm grant $OurPackage android.permission.READ_EXTERNAL_STORAGE"; Undo = "pm revoke $OurPackage android.permission.READ_EXTERNAL_STORAGE" }
    @{ Do = "appops set $OurPackage REQUEST_INSTALL_PACKAGES allow"; Undo = "appops set $OurPackage REQUEST_INSTALL_PACKAGES default" }
)

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial
if (-not (Test-PackageInstalled $serial $OurPackage)) {
    throw "Action Center isn't installed on the Shield. Run scripts/install.ps1 first."
}

Write-Host ($Revoke ? 'Revoking:' : 'Granting:')
foreach ($change in $changes) {
    $command, $undo = $Revoke ? ($change.Undo, $change.Do) : ($change.Do, $change.Undo)
    Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial -Command $command -Undo $undo | Out-Null
}
Write-Host 'Press Home on the Shield to refresh the probe screen.'
exit 0
