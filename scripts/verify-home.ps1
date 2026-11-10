#Requires -Version 7.0

<#
.SYNOPSIS
Checks that Home still opens Action Center. Run it after every SHIELD OTA.

.DESCRIPTION
Read-only: resolves the HOME intent, lists disabled packages, and warns if a package
that must stay enabled is disabled, or if another launcher's Home watcher (an
accessibility service, like Projectivy's) is on. Exit code 0 = PASS, 1 = FAIL.

.EXAMPLE
./scripts/verify-home.ps1
#>
[CmdletBinding(SupportsShouldProcess)]
param([string]$Ip)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial

$homeActivity = Get-HomeActivity $serial
$disabled = Get-DisabledPackages $serial
Write-Host "Home resolves to: $homeActivity"
Write-Host "Disabled packages: $(if ($disabled) { $disabled -join ', ' } else { 'none' })"

$mustStayEnabled = @($disabled | Where-Object { $DoNotTouch -contains $_ })
if ($mustStayEnabled) {
    Write-Warning "These should stay enabled: $($mustStayEnabled -join ', '). Re-enable with: adb -s $serial shell pm enable <package>"
}

$watchers = Get-LauncherWatchers $serial
if ($watchers) {
    Write-Warning "Another launcher's Home watcher is on and can take Home back: $($watchers -join ', '). set-default-home.ps1 offers to turn it off."
}

if ($homeActivity -like "$OurPackage/*") {
    Write-Result $true 'Home opens Action Center.'
    exit 0
}
if ($homeActivity -like 'android/*') {
    Write-Result $false 'Android will ask which Home app to use (another launcher is installed). Run scripts/set-default-home.ps1.'
} elseif ($disabled -notcontains $StockLauncher) {
    Write-Result $false 'The stock launcher is enabled, so Home opens it (not set up yet, or an update or OTA re-enabled it). Run scripts/set-default-home.ps1.'
} else {
    Write-Result $false "Home resolves to '$homeActivity'. Is Action Center installed?"
}
exit 1
