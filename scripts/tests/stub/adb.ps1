#Requires -Version 7.0
# Fake adb for scripts/tests. Records every call in $env:STUB_ADB_LOG and simulates
# a Shield whose state lives in the JSON file $env:STUB_ADB_STATE, so a change
# (say, disabling tvlauncher) shows up in later reads (say, the HOME resolution).

$ErrorActionPreference = 'Stop'
$statePath = $env:STUB_ADB_STATE
$state = Get-Content $statePath -Raw | ConvertFrom-Json -AsHashtable
$argv = @($args | ForEach-Object { "$_" })
Add-Content -Path $env:STUB_ADB_LOG -Value ($argv -join ' ')

$ours = 'com.phonoodlesramen.actioncenter'
$tvlauncher = 'com.google.android.tvlauncher'
$versions = @{
    $ours = '0.1.0'
    'com.edde746.plezy' = '2.24.1'
    'com.plexapp.android' = '2026.18.0'
    'com.google.android.youtube.tv' = '5.10'
    $tvlauncher = '1.0'
}

function Save { $state | ConvertTo-Json -Depth 5 | Set-Content $statePath }

function Reply([string[]]$Lines = @(), [int]$Code = 0) {
    $Lines | ForEach-Object { Write-Output $_ }
    exit $Code
}

function Get-Installed {
    # googleTv: Google TV's launcher is installed too (a Google TV set).
    @($versions.Keys | Where-Object { $_ -ne $ours -or $state.ourAppInstalled }) +
        @(if ($state.ContainsKey('googleTv') -and $state.googleTv) { 'com.google.android.apps.tv.launcherx' })
}

function Get-HomeCandidates {
    # Launchers that can be Home, besides tvlauncher (which outranks them all).
    @(@(if ($state.ourAppInstalled) { "$ours/.HomeActivity" }) + @($state.otherHomes))
}

function Get-Home {
    if ($state.tvlauncherEnabled) { return "$tvlauncher/.MainActivity" }
    $candidates = @(Get-HomeCandidates)
    if ($state.preferredValid -and $candidates -contains $state.preferredHome) { return $state.preferredHome }
    if ($candidates.Count -eq 1) { return $candidates[0] }
    if ($candidates.Count -eq 0) { return 'com.android.tv.settings/.system.FallbackHome' }
    'android/com.android.internal.app.ResolverActivity'
}

function Split-Words([string]$Text) {
    # Enough of sh quoting for these scripts: single-quoted words.
    $words = [System.Collections.Generic.List[string]]::new()
    $word = [System.Text.StringBuilder]::new()
    $quoted = $false
    $started = $false
    foreach ($ch in $Text.ToCharArray()) {
        if ($ch -eq "'") { $quoted = -not $quoted; $started = $true; continue }
        if (-not $quoted -and [char]::IsWhiteSpace($ch)) {
            if ($started) { $words.Add($word.ToString()); [void]$word.Clear(); $started = $false }
            continue
        }
        [void]$word.Append($ch)
        $started = $true
    }
    if ($started) { $words.Add($word.ToString()) }
    $words
}

function Invoke-Shell([string]$Command) {
    $line = (Split-Words $Command) -join ' '

    if ($line -match '^pm path (\S+)$') {
        if ((Get-Installed) -contains $Matches[1]) { Reply "package:/data/app/$($Matches[1])/base.apk" }
        Reply -Code 1
    }
    if ($line -eq 'pm list packages -d') {
        Reply @(if (-not $state.tvlauncherEnabled) { "package:$tvlauncher" })
    }
    if ($line -eq 'pm list packages -3') {
        Reply @(Get-Installed | Where-Object { $_ -notin $tvlauncher } | ForEach-Object { "package:$_" })
    }
    if ($line -match '^pm disable-user --user 0 (\S+)$') {
        if ($Matches[1] -eq $tvlauncher) { $state.tvlauncherEnabled = $false; Save }
        Reply "Package $($Matches[1]) new state: disabled-user"
    }
    if ($line -match '^pm enable (\S+)$') {
        if ($Matches[1] -eq $tvlauncher) { $state.tvlauncherEnabled = $true; Save }
        Reply "Package $($Matches[1]) new state: enabled"
    }
    if ($line -match '^pm (grant|revoke) (\S+) (\S+)$') {
        $state.grants = @(@($state.grants) | Where-Object { $_ -ne $Matches[3] })
        if ($Matches[1] -eq 'grant') { $state.grants += $Matches[3] }
        Save
        Reply
    }
    if ($line -match '^appops set (\S+) (\S+) (\S+)$') { $state.appops[$Matches[2]] = $Matches[3]; Save; Reply }
    if ($line -eq 'input keyevent KEYCODE_HOME') { Reply }
    if ($line -eq 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME') {
        Reply @('priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true', (Get-Home))
    }
    if ($line -eq 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME') {
        $all = @(@(if ($state.tvlauncherEnabled) { "$tvlauncher/.MainActivity" }) + (Get-HomeCandidates) + 'com.android.tv.settings/.system.FallbackHome')
        $lines = @("$($all.Count) activities found:")
        for ($i = 0; $i -lt $all.Count; $i++) {
            $lines += "  Activity #${i}:", '    priority=0 preferredOrder=0 match=0x108000 specificIndex=-1 isDefault=true', "    $($all[$i])"
        }
        Reply $lines
    }
    if ($line -eq 'dumpsys package preferred-xml') {
        $lines = @("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>", '<preferred-activities>')
        $lines += '<item name="com.android.gallery3d/.app.MovieActivity" match="600000" always="true" set="1">', '<filter>', '<action name="android.intent.action.VIEW" />', '</filter>', '</item>'
        if ($state.preferredHome) {
            $lines += "<item name=`"$($state.preferredHome)`" match=`"100000`" always=`"true`" set=`"2`">", '<filter>',
                '<action name="android.intent.action.MAIN" />', '<cat name="android.intent.category.HOME" />',
                '<cat name="android.intent.category.DEFAULT" />', '</filter>', '</item>'
        }
        Reply ($lines + '</preferred-activities>')
    }
    if ($line -match '^cmd package set-home-activity --user 0 (\S+)$') {
        $state.preferredHome = $Matches[1]; $state.preferredValid = $true; Save
        Reply 'Success'
    }
    if ($line -eq 'getprop') {
        Reply @(
            '[ro.build.display.id]: [RQ1A.210105.003]'
            '[ro.build.version.release]: [11]'
            '[ro.build.version.sdk]: [30]'
            '[ro.product.manufacturer]: [NVIDIA]'
            '[ro.product.model]: [SHIELD Android TV]'
            '[ro.serialno]: [STUB-SERIAL-123]'
            '[persist.sys.timezone]: [America/New_York]'
        )
    }
    if ($line -eq 'getprop ro.config.low_ram') { Reply 'false' }
    if ($line -eq 'wm size') { Reply 'Physical size: 1920x1080' }
    if ($line -eq 'wm density') { Reply 'Physical density: 320' }
    if ($line -eq 'cat /proc/meminfo') { Reply @('MemTotal:        2965000 kB', 'MemFree:          400000 kB', 'MemAvailable:    1200000 kB') }
    if ($line -match '^dumpsys package (\S+)$') {
        $package = $Matches[1]
        if ((Get-Installed) -notcontains $package) { Reply "Unable to find package: $package" }
        $lines = @("Packages:", "  Package [$package] (stub):", "    versionName=$($versions[$package])")
        if ($package -eq $ours -and $state.hasHomeWatch) { $lines += "        $ours/.home.HomeWatchService" }
        if ($package -eq 'com.plexapp.android') { $lines += @('        Scheme: "plex"', '        Authority: "app.plex.tv": -1') }
        Reply $lines
    }
    if ($line -match '^settings get secure (\S+)$') {
        Reply ($state.secure.ContainsKey($Matches[1]) ? $state.secure[$Matches[1]] : 'null')
    }
    if ($line -match '^settings put secure (\S+) (\S+)$') { $state.secure[$Matches[1]] = $Matches[2]; Save; Reply }
    if ($line -match '^settings delete secure (\S+)$') { $state.secure.Remove($Matches[1]); Save; Reply 'Deleted 1 rows' }
    if ($line -match '^am start -W -a android\.intent\.action\.VIEW -d (\S+) -p (\S+)$') {
        if ((Get-Installed) -notcontains $Matches[2]) {
            Reply @("Starting: Intent { act=android.intent.action.VIEW dat=$($Matches[1]) pkg=$($Matches[2]) }",
                'Error: Activity not started, unable to resolve Intent')
        }
        Reply @("Starting: Intent { act=android.intent.action.VIEW dat=$($Matches[1]) pkg=$($Matches[2]) }",
            'Status: ok', 'LaunchState: COLD', "Activity: $($Matches[2])/.Main", 'WaitTime: 640', 'Complete')
    }
    # measure-home.ps1: memory, the engine's runs, frames, and starts.
    if ($line -eq "dumpsys meminfo $ours") {
        Reply @("** MEMINFO in pid 2896 [$ours] **", ' App Summary', '           Java Heap:    25384', '         Native Heap:    90368',
            '                Code:     3676', '            Graphics:    10496', '           TOTAL PSS:   176325       TOTAL RSS:   244628')
    }
    if ($line -eq 'logcat -d -s ActionCenterSuggest:I') {
        Reply @('10-27 16:39:01.000  29450  29470 I ActionCenterSuggest: suggest: ran in 5512 ms: 6 picks, 64 on lenses',
            '10-27 16:56:40.000  29450  29470 I ActionCenterSuggest: suggest: ran in 3312 ms: 6 picks, 64 on lenses')
    }
    if ($line -eq "dumpsys gfxinfo $ours") {
        $frames = $state.ContainsKey('frames') ? $state.frames : 1000
        $janky = $state.ContainsKey('janky') ? $state.janky : 10
        Reply @("** Graphics info for pid 2896 [$ours] **", "Total frames rendered: $frames", "Janky frames: $janky (1.00%)", '90th percentile: 18ms')
    }
    if ($line -match '^am force-stop (\S+)$') { $state.stopped = $true; Save; Reply }
    if ($line -eq "am start -W -n $ours/.HomeActivity") {
        $cold = $state.ContainsKey('stopped') -and $state.stopped
        $state.stopped = $false; Save
        Reply @('Starting: Intent { cmp=' + "$ours/.HomeActivity" + ' }', 'Status: ok', "LaunchState: $($cold ? 'COLD' : 'WARM')",
            "Activity: $ours/.HomeActivity", "TotalTime: $($cold ? 1320 : 240)", 'Complete')
    }
    if ($line -match '^input keyevent (\S+)$') {
        $state.frames = ($state.ContainsKey('frames') ? $state.frames : 1000) + 40
        $state.janky = ($state.ContainsKey('janky') ? $state.janky : 10) + 1
        Save; Reply
    }
    if ($line -eq 'logcat -d -s ActionCenterProbe:I') {
        Reply '10-26 20:30:01.000  1234  1234 I ActionCenterProbe: mode=NORMAL stockLauncher=DISABLED apps=9'
    }
    Reply "stub adb: unhandled shell command: $line" 1
}

if ($argv.Count -ge 2 -and $argv[0] -eq '-s') {
    $argv = @($argv | Select-Object -Skip 2)
}

switch ($argv[0]) {
    'connect' {
        if ($state.reachable) { $state.connected = $true; Save; Reply "connected to $($argv[1])" }
        Reply "failed to connect to $($argv[1])"   # real adb exits 0 here too
    }
    'devices' {
        $lines = @('List of devices attached')
        if ($state.connected) { $lines += "$($state.serial)`t$($state.authorized ? 'device' : 'unauthorized')" }
        Reply $lines
    }
    'install' {
        # A new Home app makes Android ignore the saved Home choice (it asks again).
        if (-not $state.ourAppInstalled) { $state.preferredValid = $false }
        $state.ourAppInstalled = $true; Save
        Reply @('Performing Streamed Install', 'Success')
    }
    'shell' { Invoke-Shell (@($argv | Select-Object -Skip 1) -join ' ') }
    default { Reply "stub adb: unhandled command: $($argv -join ' ')" 1 }
}
