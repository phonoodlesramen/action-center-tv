#Requires -Version 7.0

<#
.SYNOPSIS
Tests the Plex playback handoff (D11) for one title you pick.

.DESCRIPTION
1. Plezy: plezy://play?server=<serverId>&id=<ratingKey>, the link Action Center
   sends (Phase 11; Plezy's own source).
2. The Plex app: prints its link schemes (dumpsys), then tries the link Action
   Center sends for Play in Plex, plex://libraries/<serverId>/unmatched/<key>
   (it plays the title on a SHIELD), then plex://preplay,
   plex://play, an app.plex.tv web link and, if given, a watch.plex.tv link.

Each link is started with `am start -W`; press Home between tries. After each one
you're asked what the TV did (right title? resumed or restarted?). Results go to
reports/player-intent-probe.md, which git ignores.

ServerId is the Plex server's machine identifier (Plex Web > Settings > General,
or the clientIdentifier from plex.tv/api/v2/resources). RatingKey is the number in
the title's Plex Web URL: ...key=%2Flibrary%2Fmetadata%2F<ratingKey>.

.EXAMPLE
./scripts/player-intent-probe.ps1 -ServerId 0a1b2c3d... -RatingKey 4567
#>
[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Ip,
    [Parameter(Mandatory)][ValidatePattern('^[A-Za-z0-9-]+$')][string]$ServerId,
    [Parameter(Mandatory)][ValidatePattern('^[0-9]+$')][string]$RatingKey,
    [string]$WatchPlexUrl,
    [switch]$SkipPlex,
    [switch]$NoPrompt,
    [string]$OutFile = (Join-Path (Split-Path $PSScriptRoot -Parent) 'reports/player-intent-probe.md')
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force

$plezy = 'com.edde746.plezy'
$plex = 'com.plexapp.android'
$metadataKey = [uri]::EscapeDataString("/library/metadata/$RatingKey")

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial

$candidates = [System.Collections.Generic.List[object]]::new()
$candidates.Add(@{ Name = 'Plezy deep link'; Uri = "plezy://play?server=$ServerId&id=$RatingKey"; Package = $plezy })
if (-not $SkipPlex) {
    Write-Host 'Plex app link schemes and hosts (dumpsys package):'
    Invoke-AdbShell -Serial $serial -Command "dumpsys package $plex" -AllowFailure |
        Where-Object { $_ -match '(Scheme|Authority): ' } |
        ForEach-Object { $_.Trim() } | Sort-Object -Unique |
        ForEach-Object { Write-Host "  $_" }
    $candidates.Add(@{ Name = 'Plex title link'; Uri = "plex://libraries/$ServerId/unmatched/$metadataKey"; Package = $plex })
    $candidates.Add(@{ Name = 'Plex preplay'; Uri = "plex://preplay/?metadataKey=$metadataKey&server=$ServerId"; Package = $plex })
    $candidates.Add(@{ Name = 'Plex play'; Uri = "plex://play/?metadataKey=$metadataKey&server=$ServerId"; Package = $plex })
    $candidates.Add(@{ Name = 'Plex web link'; Uri = "https://app.plex.tv/desktop#!/server/$ServerId/details?key=$metadataKey"; Package = $plex })
    if ($WatchPlexUrl) { $candidates.Add(@{ Name = 'watch.plex.tv link'; Uri = $WatchPlexUrl; Package = $plex }) }
}

$results = foreach ($candidate in $candidates) {
    $command = "am start -W -a android.intent.action.VIEW -d $(ConvertTo-ShellQuoted $candidate.Uri) -p $($candidate.Package)"
    Write-Host "`n$($candidate.Name): adb -s $serial shell $command"
    if (-not $PSCmdlet.ShouldProcess($serial, "adb shell $command")) { continue }

    $output = @(Invoke-AdbShell -Serial $serial -Command $command -AllowFailure)
    $status = if ($output -match '^Status: ok') { 'started' }
        elseif ($output -match 'unable to resolve') { 'no app handles it' }
        else { ($output | Select-Object -Last 1) }
    Write-Host "  $status"

    $observed = 'not observed'
    if ($status -eq 'started' -and -not $NoPrompt) {
        $observed = Read-Host '  What did the TV do? (e.g. "right title, resumed at 12:34", "restarted from 0:00", "app home only", "nothing")'
    }
    Invoke-AdbShell -Serial $serial -Command 'input keyevent KEYCODE_HOME' -AllowFailure | Out-Null
    if (-not $NoPrompt) { Start-Sleep -Seconds 2 }

    [pscustomobject]@{ Name = $candidate.Name; Uri = $candidate.Uri; Status = $status; Observed = $observed }
}

$rows = $results | ForEach-Object { "| $($_.Name) | ``$($_.Uri)`` | $($_.Status) | $($_.Observed) |" }
$md = @"
# Player Intent Probe
*<small>(generated $(Get-Date -Format 'MM/dd/yyyy') by scripts/player-intent-probe.ps1)</small>*

Server ``$ServerId``, ratingKey ``$RatingKey``.

| Handoff | Link | adb result | What the TV did |
|---|---|---|---|
$($rows -join "`n")
"@
New-Item -ItemType Directory -Force -Path (Split-Path $OutFile -Parent) | Out-Null
Set-Content -Path $OutFile -Value $md
Write-Host "`nWrote $OutFile"
exit 0
