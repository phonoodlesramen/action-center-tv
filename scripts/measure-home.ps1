#Requires -Version 7.0

<#
.SYNOPSIS
Measures Action Center on the Shield: memory, the suggestion engine's run time, frames
drawn, and with -Start, how long it takes to open (Phase 4, M6).

.DESCRIPTION
Read-only by default: Action Center's memory (dumpsys meminfo), the Shield's available
memory, the frames drawn since it started (dumpsys gfxinfo) and the engine's run times
from the log.

-Start also measures a cold start (Action Center stopped, then opened), -Runs times: what
coming back to Home costs once Android has stopped the launcher, say during a long film.
A warm start isn't measured: Android only hands a running launcher the intent again. It
stops Action Center (am force-stop) and brings it to the front, so it asks first.

-Scroll presses Down and Up on the home and counts the frames drawn and the janky ones.
It presses keys on the TV, so it asks first.

.EXAMPLE
./scripts/measure-home.ps1
./scripts/measure-home.ps1 -Start -Scroll
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High')]
param(
    [string]$Ip,
    [switch]$Start,
    [switch]$Scroll,
    [ValidateRange(1, 10)][int]$Runs = 3
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial
if (-not (Test-PackageInstalled $serial $OurPackage)) {
    throw "Action Center isn't installed on the Shield. Run scripts/install.ps1 first."
}

function Get-Number([string[]]$Lines, [string]$Pattern) {
    foreach ($line in $Lines) { if ($line -match $Pattern) { return [double]($Matches[1] -replace ',', '') } }
    $null
}

function Format-Mb([double]$Kb) { '{0:N0} MB' -f ($Kb / 1024) }

function Get-Median([double[]]$Values) {
    $sorted = @($Values | Sort-Object)
    $sorted[[math]::Floor(($sorted.Count - 1) / 2)]
}

function Get-Frames {
    $lines = Invoke-AdbShell -Serial $serial -Command "dumpsys gfxinfo $OurPackage" -AllowFailure
    [pscustomobject]@{
        Total = Get-Number $lines 'Total frames rendered:\s+(\d+)'
        Janky = Get-Number $lines 'Janky frames:\s+(\d+)'
        P90 = Get-Number $lines '90th percentile:\s+(\d+)ms'
    }
}

function Write-Frames([string]$What, [double]$Total, [double]$Janky, $P90) {
    if (-not $Total) { return Write-Host "${What}: none drawn" }
    $share = '{0:P1}' -f ($Janky / $Total)
    $tail = if ($null -ne $P90) { ", 90th percentile $P90 ms" } else { '' }
    Write-Host ("${What}: {0:N0} drawn, {1:N0} janky ($share)$tail" -f $Total, $Janky)
}

# Memory: the app summary's rows, in kB.
$meminfo = Invoke-AdbShell -Serial $serial -Command "dumpsys meminfo $OurPackage" -AllowFailure
$pss = Get-Number $meminfo 'TOTAL PSS:\s+(\d+)'
if ($null -eq $pss) {
    Write-Host 'Memory: Action Center isn''t running'
} else {
    $parts = [ordered]@{ Java = 'Java Heap:\s+(\d+)'; native = 'Native Heap:\s+(\d+)'; graphics = 'Graphics:\s+(\d+)'; code = 'Code:\s+(\d+)' }
    $detail = ($parts.GetEnumerator() | ForEach-Object { "$($_.Key) $(Format-Mb (Get-Number $meminfo $_.Value))" }) -join ', '
    Write-Host "Memory: $(Format-Mb $pss) PSS ($detail)"
}
$available = Get-Number (Invoke-AdbShell -Serial $serial -Command 'cat /proc/meminfo') 'MemAvailable:\s+(\d+)'
if ($null -ne $available) { Write-Host "The Shield has $(Format-Mb $available) available" }

# The engine's runs still in the log.
$engineRuns = @(Invoke-AdbShell -Serial $serial -Command 'logcat -d -s ActionCenterSuggest:I' -AllowFailure |
    ForEach-Object { if ($_ -match 'suggest: ran in (\d+) ms') { [double]$Matches[1] } })
if ($engineRuns) {
    Write-Host ("Engine: last run {0:N0} ms; median {1:N0} ms of the {2} in the log" -f $engineRuns[-1], (Get-Median $engineRuns), $engineRuns.Count)
} else {
    Write-Host 'Engine: no runs in the log'
}

$frames = Get-Frames
Write-Frames 'Frames since it started' $frames.Total $frames.Janky $frames.P90

if ($Start) {
    Write-Host 'Start times: stops Action Center and opens it, so the TV shows it.'
    Write-Host "  Undo: adb -s $serial shell am start -n $OurHome (or press Home)"
    if ($PSCmdlet.ShouldProcess($serial, "stop and open Action Center $Runs times")) {
        $cold = @()
        foreach ($run in 1..$Runs) {
            Invoke-AdbShell -Serial $serial -Command "am force-stop $OurPackage" | Out-Null
            $cold += Get-Number (Invoke-AdbShell -Serial $serial -Command "am start -W -n $OurHome") 'TotalTime:\s+(\d+)'
            # Let it settle before the next stop.
            Start-Sleep -Milliseconds 1500
        }
        Write-Host ("Cold start: {0:N0} ms median ({1})" -f (Get-Median $cold), (($cold | ForEach-Object { '{0:N0}' -f $_ }) -join ' / '))
    }
}

if ($Scroll) {
    Write-Host 'Scrolling: opens Action Center and presses Down 6 times, then Up 6 times.'
    if ($PSCmdlet.ShouldProcess($serial, 'press keys on the home')) {
        Invoke-AdbShell -Serial $serial -Command "am start -W -n $OurHome" | Out-Null
        $before = Get-Frames
        foreach ($key in @(@('KEYCODE_DPAD_DOWN') * 6 + @('KEYCODE_DPAD_UP') * 6)) {
            Invoke-AdbShell -Serial $serial -Command "input keyevent $key" | Out-Null
            Start-Sleep -Milliseconds 600
        }
        $after = Get-Frames
        Write-Frames 'Scrolling' ($after.Total - $before.Total) ($after.Janky - $before.Janky) $null
    }
}
