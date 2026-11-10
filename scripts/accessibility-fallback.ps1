#Requires -Version 7.0

<#
.SYNOPSIS
Turns the optional HomeWatch accessibility service on or off (off by default).

.DESCRIPTION
Home through Action Center where the stock launcher has to stay on: a
Google TV, whose launcher can't be disabled safely
(docs/install.md). On the Shield, a fallback for when an OTA
keeps re-enabling the stock launcher (research §2, option 2). Where Action Center is
already the Home, it passes every key. It has costs: a brief flash of the stock
launcher when it comes up other than by Home, extra RAM, and some apps behave
differently while any accessibility service is on.

enabled_accessibility_services is a shared list: this script reads it first and
appends or removes only its own entry, never overwriting other services.

.EXAMPLE
./scripts/accessibility-fallback.ps1 -Enable
./scripts/accessibility-fallback.ps1 -Disable
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [string]$Ip,
    [Parameter(Mandatory, ParameterSetName = 'Enable')][switch]$Enable,
    [Parameter(Mandatory, ParameterSetName = 'Disable')][switch]$Disable
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$service = "$OurPackage/$OurPackage.home.HomeWatchService"
$shortService = "$OurPackage/.home.HomeWatchService"
$listKey = 'enabled_accessibility_services'

function Get-SecureSetting([string]$Key) {
    $value = (@(Invoke-AdbShell -Serial $serial -Command "settings get secure $Key") -join '').Trim()
    if ($value -eq 'null') { '' } else { $value }
}

function Get-RestoreCommand([string]$Key, [string]$Value) {
    if ($Value) { "settings put secure $Key $(ConvertTo-ShellQuoted $Value)" } else { "settings delete secure $Key" }
}

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial

$current = Get-SecureSetting $listKey
$entries = @($current -split ':' | Where-Object { $_ })
$ours = @($entries | Where-Object { $_ -eq $service -or $_ -eq $shortService })
$restoreList = Get-RestoreCommand $listKey $current

if ($Enable) {
    $dump = Invoke-AdbShell -Serial $serial -Command "dumpsys package $OurPackage" -AllowFailure
    if (-not (@($dump) -match 'HomeWatchService')) {
        throw "This build of Action Center has no HomeWatch service, so there is nothing to enable."
    }
    if ($ours) { Write-Host 'HomeWatch is already enabled.'; exit 0 }

    Write-Host 'Adding HomeWatch to the enabled accessibility services:'
    $newList = (@($entries) + $service) -join ':'
    $changed = Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
        -Command "settings put secure $listKey $(ConvertTo-ShellQuoted $newList)" -Undo $restoreList
    if (-not $changed) { exit 0 }

    $accessibilityOn = Get-SecureSetting 'accessibility_enabled'
    if ($accessibilityOn -ne '1') {
        Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
            -Command 'settings put secure accessibility_enabled 1' `
            -Undo (Get-RestoreCommand 'accessibility_enabled' $accessibilityOn) | Out-Null
    }
} else {
    if (-not $ours) { Write-Host 'HomeWatch is not enabled.'; exit 0 }

    Write-Host 'Removing HomeWatch from the enabled accessibility services:'
    $remaining = @($entries | Where-Object { $ours -notcontains $_ })
    Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $serial `
        -Command (Get-RestoreCommand $listKey ($remaining -join ':')) -Undo $restoreList | Out-Null
}
exit 0
