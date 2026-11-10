#Requires -Version 7.0

<#
.SYNOPSIS
Dry-run tests for the Shield ADB scripts, against a fake adb (tests/stub/adb.ps1).

.DESCRIPTION
Each test gets a fresh simulated Shield. The tests check the exact adb command
sequence, that -WhatIf never changes the device, that rollback mirrors setup, and
that packages which must stay enabled are never touched.
Exit code = number of failed tests.

.EXAMPLE
pwsh scripts/tests/run-tests.ps1
#>
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3.0

$Scripts = Split-Path $PSScriptRoot -Parent
Import-Module (Join-Path $Scripts 'ShieldAdb.psm1') -Force

$Ip = '10.0.0.5'
$Serial = '10.0.0.5:5555'
$ShellPrefix = "-s $Serial shell "
$Plezy = 'com.edde746.plezy'

# Commands that only read. Anything else in the log is a change to the device.
$ReadOnly = '^(connect \S+|devices|-s \S+ shell (pm path |pm list packages|cmd package (resolve|query)-activit|getprop|wm (size|density)$|cat /proc/meminfo$|dumpsys (package|meminfo|gfxinfo) |settings get |logcat -d ))'

$Projectivy = 'com.spocky.projengmenu/.ui.home.MainActivity'
$ProjectivyWatcher = 'com.spocky.projengmenu/com.spocky.projengmenu.services.ProjectivyAccessibilityService'
$Dispatch = 'com.spauldhaliwal.dispatch/.MainActivity'
$OtherService = 'com.other.app/.TalkService'

# The operator's Shield: tvlauncher already disabled, Projectivy the saved Home with
# its watcher on, Dispatch also installed, and Action Center freshly installed (so
# Android shows its chooser).
$ProjectivyShield = @{
    tvlauncherEnabled = $false; otherHomes = @($Dispatch, $Projectivy)
    preferredHome = $Projectivy; preferredValid = $false
    secure = @{ enabled_accessibility_services = "${ProjectivyWatcher}:$OtherService"; accessibility_enabled = '1' }
}

function New-Device([hashtable]$Overrides = @{}) {
    $dir = Join-Path ([IO.Path]::GetTempPath()) "shield-test-$([guid]::NewGuid().ToString('N'))"
    New-Item -ItemType Directory -Path $dir | Out-Null
    $state = @{
        serial = $Serial; reachable = $true; connected = $true; authorized = $true
        tvlauncherEnabled = $true; ourAppInstalled = $true; hasHomeWatch = $false
        otherHomes = @(); preferredHome = $null; preferredValid = $false
        secure = @{}; appops = @{}; grants = @()
    }
    foreach ($key in $Overrides.Keys) { $state[$key] = $Overrides[$key] }
    $state | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $dir 'state.json')
    New-Item -ItemType File -Path (Join-Path $dir 'adb.log') | Out-Null
    $env:STUB_ADB_STATE = Join-Path $dir 'state.json'
    $env:STUB_ADB_LOG = Join-Path $dir 'adb.log'
    $env:SHIELD_IP_FILE = Join-Path $dir '.shield-ip'
    $env:SHIELD_STATE_DIR = $dir
    $script:DeviceDir = $dir
    $dir
}

function Get-AdbLog { @(Get-Content $env:STUB_ADB_LOG) }
function Clear-AdbLog { Set-Content -Path $env:STUB_ADB_LOG -Value $null }
function Get-ShellLog {
    @(Get-AdbLog | Where-Object { $_.StartsWith($ShellPrefix) } | ForEach-Object { $_.Substring($ShellPrefix.Length) })
}
function Get-StubState { Get-Content $env:STUB_ADB_STATE -Raw | ConvertFrom-Json -AsHashtable }

function Invoke-Script([string]$Name, [hashtable]$Parameters = @{}) {
    $global:LASTEXITCODE = 0
    $output = & (Join-Path $Scripts $Name) @Parameters *>&1 | Out-String
    [pscustomobject]@{ Output = $output; ExitCode = $global:LASTEXITCODE }
}

function Assert-Equal($Expected, $Actual, [string]$What) {
    $e = ConvertTo-Json @($Expected) -Compress
    $a = ConvertTo-Json @($Actual) -Compress
    if ($e -ne $a) { throw "$What`n  expected: $e`n  actual:   $a" }
}

function Assert-True([bool]$Condition, [string]$What) {
    if (-not $Condition) { throw $What }
}

function Assert-Throws([scriptblock]$Block, [string]$Like) {
    try { & $Block } catch {
        if ($_.Exception.Message -notlike "*$Like*") { throw "Threw '$($_.Exception.Message)', expected '*$Like*'" }
        return
    }
    throw "Expected an error like '*$Like*', but nothing was thrown."
}

function Assert-NoDeviceChanges {
    $changes = @(Get-AdbLog | Where-Object { $_ -notmatch $ReadOnly })
    Assert-True ($changes.Count -eq 0) "Device-changing adb calls ran: $($changes -join ' | ')"
}

$Tests = [ordered]@{
    'measure-home reads memory, the engine and frames, and changes nothing' = {
        New-Device | Out-Null
        $result = Invoke-Script 'measure-home.ps1' @{ Ip = $Ip }
        Assert-True ($result.Output -match 'Memory: 172 MB PSS \(Java 25 MB, native 88 MB, graphics 10 MB, code 4 MB\)') "memory line: $($result.Output)"
        Assert-True ($result.Output -match 'The Shield has 1,172 MB available') 'available memory'
        Assert-True ($result.Output -match 'Engine: last run 3,312 ms; median 3,312 ms of the 2 in the log') "engine line: $($result.Output)"
        Assert-True ($result.Output -match 'Frames since it started: 1,000 drawn, 10 janky \(1.0%\), 90th percentile 18 ms') "frames line: $($result.Output)"
        Assert-NoDeviceChanges
    }

    'measure-home -Start -WhatIf neither stops nor opens Action Center' = {
        New-Device | Out-Null
        Invoke-Script 'measure-home.ps1' @{ Ip = $Ip; Start = $true; Scroll = $true; WhatIf = $true } | Out-Null
        Assert-NoDeviceChanges
    }

    'measure-home -Start times cold starts, and -Scroll counts the frames' = {
        New-Device | Out-Null
        $result = Invoke-Script 'measure-home.ps1' @{ Ip = $Ip; Start = $true; Scroll = $true; Runs = 1; Confirm = $false }
        Assert-True ($result.Output -match 'Cold start: 1,320 ms median \(1,320\)') "cold start: $($result.Output)"
        Assert-True ($result.Output -match 'Scrolling: 480 drawn, 12 janky \(2.5%\)') "scrolling: $($result.Output)"
        Assert-Equal @("am force-stop $OurPackage", "am start -W -n $OurHome") `
            (Get-ShellLog | Where-Object { $_ -match '^am ' } | Select-Object -First 2) 'start commands'
    }

    'connect remembers an authorised Shield' = {
        New-Device @{ connected = $false } | Out-Null
        Invoke-Script 'connect.ps1' @{ Ip = $Ip } | Out-Null
        Assert-Equal @("connect $Serial", 'devices') (Get-AdbLog) 'adb calls'
        Assert-Equal $Serial (Get-Content $env:SHIELD_IP_FILE -Raw).Trim() 'saved address'
    }

    'connect explains an unauthorised Shield and saves nothing' = {
        New-Device @{ authorized = $false } | Out-Null
        Assert-Throws { Invoke-Script 'connect.ps1' @{ Ip = $Ip } } 'Allow network debugging'
        Assert-True (-not (Test-Path $env:SHIELD_IP_FILE)) 'no address should be saved'
    }

    'scripts reconnect once when the Shield has dropped off' = {
        New-Device @{ connected = $false; tvlauncherEnabled = $false } | Out-Null
        $result = Invoke-Script 'verify-home.ps1' @{ Ip = $Ip }
        Assert-Equal 0 $result.ExitCode 'exit code'
        Assert-Equal @('devices', "connect $Serial", 'devices') (Get-AdbLog | Select-Object -First 3) 'first adb calls'
    }

    'connect saves an emulator serial without adb connect' = {
        New-Device @{ serial = 'emulator-5554' } | Out-Null
        Invoke-Script 'connect.ps1' @{ Ip = 'emulator-5554' } | Out-Null
        Assert-Equal @('devices') (Get-AdbLog) 'adb calls'
        Assert-Equal 'emulator-5554' (Get-Content $env:SHIELD_IP_FILE -Raw).Trim() 'saved serial'
    }

    'scripts address an emulator by its serial and never adb connect it' = {
        New-Device @{ serial = 'emulator-5554'; tvlauncherEnabled = $false } | Out-Null
        $result = Invoke-Script 'verify-home.ps1' @{ Ip = 'emulator-5554' }
        Assert-Equal 0 $result.ExitCode 'exit code'
        Assert-True (-not (Get-AdbLog | Where-Object { $_ -like 'connect*' })) 'no adb connect'
        Assert-True ((Get-AdbLog) -contains "-s emulator-5554 shell $HomeQuery") 'commands go to emulator-5554'
    }

    'a missing emulator gets an emulator-specific message' = {
        New-Device @{ serial = 'emulator-5554'; connected = $false } | Out-Null
        Assert-Throws { Invoke-Script 'verify-home.ps1' @{ Ip = 'emulator-5554' } } 'Is the emulator running?'
        Assert-Equal @('devices') (Get-AdbLog) 'adb calls'
    }

    'set-default-home disables only tvlauncher, presses Home and verifies' = {
        New-Device | Out-Null
        $result = Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; Confirm = $false }
        Assert-Equal 0 $result.ExitCode 'exit code'
        Assert-Equal @(
            "pm path $GoogleTvLauncher"
            "pm path $OurPackage"
            $HomeQuery
            'pm list packages -d'
            "pm disable-user --user 0 $StockLauncher"
            $HomeQuery
            'settings get secure enabled_accessibility_services'
            'input keyevent KEYCODE_HOME'
            $HomeQuery
        ) (Get-ShellLog) 'shell commands'
        Assert-True (-not (Get-StubState).tvlauncherEnabled) 'tvlauncher should be disabled'
        $record = Get-Content (Get-HomeRecordPath $Serial) -Raw | ConvertFrom-Json
        Assert-Equal "$StockLauncher/.MainActivity" $record.previousHome 'saved previous Home'
        Assert-True ($result.Output -like "*Undo:   adb -s $Serial shell pm enable $StockLauncher*") 'prints the undo command'
    }

    'set-default-home -WhatIf changes nothing' = {
        New-Device | Out-Null
        Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; WhatIf = $true } | Out-Null
        Assert-NoDeviceChanges
        Assert-True (Get-StubState).tvlauncherEnabled 'tvlauncher should still be enabled'
    }

    'install -FromRelease checks the release''s SHA-256, then installs' = {
        $dir = New-Device
        $release = Join-Path $dir 'release'
        New-Item -ItemType Directory -Path $release | Out-Null
        Set-Content -LiteralPath (Join-Path $release $ReleaseApk) -Value 'not really an APK' -NoNewline
        $hash = (Get-FileHash -LiteralPath (Join-Path $release $ReleaseApk) -Algorithm SHA256).Hash.ToLowerInvariant()
        Set-Content -LiteralPath (Join-Path $release $ReleaseSha) -Value "$hash  $ReleaseApk"
        $env:ACTION_CENTER_RELEASE_BASE = $release
        try { $result = Invoke-Script 'install.ps1' @{ Ip = $Ip; FromRelease = $true; Confirm = $false } }
        finally { Remove-Item Env:ACTION_CENTER_RELEASE_BASE }
        Assert-True ($result.Output -match "SHA-256 matches the release: $hash") "hash line: $($result.Output)"
        $install = @(Get-AdbLog | Where-Object { $_ -match '^-s \S+ install -r ' })
        Assert-Equal 1 $install.Count 'one install'
        Assert-True ($install[0] -like "*$ReleaseApk") "installs the downloaded APK: $($install[0])"
    }

    'install -FromRelease refuses an APK that doesn''t match its SHA-256, and installs nothing' = {
        $dir = New-Device
        $release = Join-Path $dir 'release'
        New-Item -ItemType Directory -Path $release | Out-Null
        Set-Content -LiteralPath (Join-Path $release $ReleaseApk) -Value 'tampered' -NoNewline
        Set-Content -LiteralPath (Join-Path $release $ReleaseSha) -Value "$('0' * 64)  $ReleaseApk"
        $env:ACTION_CENTER_RELEASE_BASE = $release
        try { Assert-Throws { Invoke-Script 'install.ps1' @{ Ip = $Ip; FromRelease = $true; Confirm = $false } } 'Not installing it' }
        finally { Remove-Item Env:ACTION_CENTER_RELEASE_BASE }
        Assert-True (-not (Get-AdbLog | Where-Object { $_ -match ' install ' })) 'nothing installed'
    }

    'releases come from this clone''s GitHub repository, else the public one' = {
        $dir = New-Device
        Assert-Equal $PublicRepo (Get-DefaultRepo $dir) 'a folder with no git remote'
        Assert-Equal 'https://github.com/o/r/releases/latest/download' (Get-ReleaseBase 'o/r' '') 'the latest release'
        Assert-Equal 'https://github.com/o/r/releases/download/v1.2.3' (Get-ReleaseBase 'o/r' 'v1.2.3') 'a tagged release'
    }

    'set-default-home refuses on a Google TV before changing anything, and points to HomeWatch' = {
        New-Device @{ googleTv = $true } | Out-Null
        Assert-Throws { Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; Confirm = $false } } 'Use HomeWatch instead'
        Assert-NoDeviceChanges
        Assert-True (Get-StubState).tvlauncherEnabled 'tvlauncher should still be enabled'
        Assert-True ((Get-ShellLog) -contains "pm path $GoogleTvLauncher") 'asks whether it is a Google TV'
    }

    'no change may ever name Google TV''s launcher' = {
        New-Device | Out-Null
        Assert-True ($DoNotTouch -contains $GoogleTvLauncher) 'launcherx is on the do-not-touch list'
    }

    'set-default-home refuses when Action Center is not installed' = {
        New-Device @{ ourAppInstalled = $false } | Out-Null
        Assert-Throws { Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; Confirm = $false } } "isn't installed"
        Assert-NoDeviceChanges
    }

    'rollback-home mirrors set-default-home' = {
        New-Device | Out-Null
        $setup = Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; Confirm = $false }
        Clear-AdbLog
        $rollback = Invoke-Script 'rollback-home.ps1' @{ Ip = $Ip; Confirm = $false }

        Assert-Equal 0 $rollback.ExitCode 'exit code'
        Assert-Equal @(
            'pm list packages -d'
            "pm enable $StockLauncher"
            'input keyevent KEYCODE_HOME'
            $HomeQuery
        ) (Get-ShellLog) 'shell commands'
        Assert-True (Get-StubState).tvlauncherEnabled 'tvlauncher should be enabled again'
        # Each script's undo is exactly the other script's change.
        Assert-True ($setup.Output -like "*Undo:   adb -s $Serial shell pm enable $StockLauncher*") 'setup undo = rollback change'
        Assert-True ($rollback.Output -like "*Undo:   adb -s $Serial shell pm disable-user --user 0 $StockLauncher*") 'rollback undo = setup change'
    }

    'over Projectivy: saves it, makes Action Center the saved Home, removes only its watcher' = {
        New-Device $ProjectivyShield | Out-Null
        $result = Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; Confirm = $false }

        Assert-Equal 0 $result.ExitCode 'exit code'
        $changes = @(Get-AdbLog | Where-Object { $_ -notmatch $ReadOnly } | ForEach-Object { $_.Substring($ShellPrefix.Length) })
        Assert-Equal @(
            "cmd package set-home-activity --user 0 $OurHome"
            "settings put secure enabled_accessibility_services '$OtherService'"
            'input keyevent KEYCODE_HOME'
        ) $changes 'changes'
        Assert-True (-not ((Get-ShellLog) -like 'pm disable-user*')) 'tvlauncher is already disabled: no pm disable-user'
        $state = Get-StubState
        Assert-Equal $OurHome $state.preferredHome 'saved Home on the device'
        Assert-Equal $OtherService $state.secure.enabled_accessibility_services 'other accessibility services kept'
        $record = Get-Content (Get-HomeRecordPath $Serial) -Raw | ConvertFrom-Json
        Assert-Equal $Projectivy $record.previousHome 'saved previous Home'
        Assert-Equal $false $record.stockLauncherWasEnabled 'stock launcher was already disabled'
        Assert-Equal @($ProjectivyWatcher) @($record.removedAccessibility) 'removed watcher recorded'
        Assert-True ($result.Output -like "*Undo:   adb -s $Serial shell cmd package set-home-activity --user 0 $Projectivy*") 'undo restores Projectivy'
    }

    'over Projectivy, -WhatIf changes nothing and saves nothing' = {
        New-Device $ProjectivyShield | Out-Null
        Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; WhatIf = $true } | Out-Null
        Assert-NoDeviceChanges
        Assert-True (-not (Test-Path (Get-HomeRecordPath $Serial))) 'no record written'
    }

    'rollback-home restores Projectivy and its watcher, and keeps tvlauncher disabled' = {
        New-Device $ProjectivyShield | Out-Null
        Invoke-Script 'set-default-home.ps1' @{ Ip = $Ip; Confirm = $false } | Out-Null
        Clear-AdbLog
        $result = Invoke-Script 'rollback-home.ps1' @{ Ip = $Ip; Confirm = $false }

        Assert-Equal 0 $result.ExitCode 'exit code'
        $changes = @(Get-AdbLog | Where-Object { $_ -notmatch $ReadOnly } | ForEach-Object { $_.Substring($ShellPrefix.Length) })
        Assert-Equal @(
            "settings put secure enabled_accessibility_services '${OtherService}:$ProjectivyWatcher'"
            "cmd package set-home-activity --user 0 $Projectivy"
            'input keyevent KEYCODE_HOME'
        ) $changes 'changes'
        $state = Get-StubState
        Assert-Equal $Projectivy $state.preferredHome 'Projectivy is the saved Home again'
        Assert-True (-not $state.tvlauncherEnabled) 'tvlauncher stays disabled'
        Assert-True (-not (Test-Path (Get-HomeRecordPath $Serial))) 'record removed after a successful rollback'
    }

    'verify-home fails on the chooser and warns about another launcher''s watcher' = {
        New-Device $ProjectivyShield | Out-Null
        $result = Invoke-Script 'verify-home.ps1' @{ Ip = $Ip }
        Assert-Equal 1 $result.ExitCode 'exit code'
        Assert-True ($result.Output -like '*ask which Home app*') 'explains the chooser'
        Assert-True ($result.Output -like "*$ProjectivyWatcher*") 'names the watcher'
        Assert-NoDeviceChanges
    }

    'rollback-home -WhatIf changes nothing' = {
        New-Device @{ tvlauncherEnabled = $false } | Out-Null
        Invoke-Script 'rollback-home.ps1' @{ Ip = $Ip; WhatIf = $true } | Out-Null
        Assert-NoDeviceChanges
        Assert-True (-not (Get-StubState).tvlauncherEnabled) 'tvlauncher should still be disabled'
    }

    'verify-home passes when Action Center is home and fails when tvlauncher is back' = {
        New-Device @{ tvlauncherEnabled = $false } | Out-Null
        Assert-Equal 0 (Invoke-Script 'verify-home.ps1' @{ Ip = $Ip }).ExitCode 'exit code with Action Center home'
        Assert-NoDeviceChanges

        New-Device @{ tvlauncherEnabled = $true } | Out-Null
        $result = Invoke-Script 'verify-home.ps1' @{ Ip = $Ip }
        Assert-Equal 1 $result.ExitCode 'exit code with tvlauncher back'
        Assert-True ($result.Output -like '*set-default-home.ps1*') 'points to the fix'
    }

    'install installs the given APK, and -WhatIf does not' = {
        $dir = New-Device @{ ourAppInstalled = $false }
        $apk = Join-Path $dir 'app.apk'
        Set-Content -Path $apk -Value 'not really an apk'

        Invoke-Script 'install.ps1' @{ Ip = $Ip; Apk = $apk; WhatIf = $true } | Out-Null
        Assert-NoDeviceChanges

        Invoke-Script 'install.ps1' @{ Ip = $Ip; Apk = $apk; Confirm = $false } | Out-Null
        Assert-True ((Get-AdbLog) -contains "-s $Serial install -r $((Resolve-Path $apk).Path)") 'adb install -r <apk>'
        Assert-True (Get-StubState).ourAppInstalled 'app should be installed'
    }

    'grant-permissions grants all three, and -Revoke undoes exactly that' = {
        New-Device | Out-Null
        Invoke-Script 'grant-permissions.ps1' @{ Ip = $Ip; Confirm = $false } | Out-Null
        Assert-Equal @(
            "pm path $OurPackage"
            "appops set $OurPackage GET_USAGE_STATS allow"
            "pm grant $OurPackage android.permission.READ_TV_LISTINGS"
            "pm grant $OurPackage android.permission.READ_EXTERNAL_STORAGE"
        ) (Get-ShellLog) 'grant commands'

        Clear-AdbLog
        Invoke-Script 'grant-permissions.ps1' @{ Ip = $Ip; Revoke = $true; Confirm = $false } | Out-Null
        Assert-Equal @(
            "pm path $OurPackage"
            "appops set $OurPackage GET_USAGE_STATS default"
            "pm revoke $OurPackage android.permission.READ_TV_LISTINGS"
            "pm revoke $OurPackage android.permission.READ_EXTERNAL_STORAGE"
        ) (Get-ShellLog) 'revoke commands'
        Assert-Equal @() @((Get-StubState).grants) 'no grants left'
    }

    'grant-permissions -WhatIf changes nothing' = {
        New-Device | Out-Null
        Invoke-Script 'grant-permissions.ps1' @{ Ip = $Ip; WhatIf = $true } | Out-Null
        Assert-NoDeviceChanges
    }

    'accessibility-fallback appends to the list and removes only its own entry' = {
        $other = 'com.other.app/.TalkService'
        $homeWatch = "$OurPackage/$OurPackage.home.HomeWatchService"
        New-Device @{ hasHomeWatch = $true; secure = @{ enabled_accessibility_services = $other; accessibility_enabled = '1' } } | Out-Null

        Invoke-Script 'accessibility-fallback.ps1' @{ Ip = $Ip; Enable = $true; Confirm = $false } | Out-Null
        Assert-Equal "${other}:$homeWatch" (Get-StubState).secure.enabled_accessibility_services 'after -Enable'

        Invoke-Script 'accessibility-fallback.ps1' @{ Ip = $Ip; Disable = $true; Confirm = $false } | Out-Null
        Assert-Equal $other (Get-StubState).secure.enabled_accessibility_services 'after -Disable'
    }

    'accessibility-fallback turns accessibility on, and -WhatIf changes nothing' = {
        $homeWatch = "$OurPackage/$OurPackage.home.HomeWatchService"
        New-Device @{ hasHomeWatch = $true } | Out-Null

        Invoke-Script 'accessibility-fallback.ps1' @{ Ip = $Ip; Enable = $true; WhatIf = $true } | Out-Null
        Assert-NoDeviceChanges

        Invoke-Script 'accessibility-fallback.ps1' @{ Ip = $Ip; Enable = $true; Confirm = $false } | Out-Null
        $secure = (Get-StubState).secure
        Assert-Equal $homeWatch $secure.enabled_accessibility_services 'service list'
        Assert-Equal '1' $secure.accessibility_enabled 'accessibility_enabled'
    }

    'accessibility-fallback refuses when this build has no HomeWatch service' = {
        New-Device @{ hasHomeWatch = $false } | Out-Null
        Assert-Throws { Invoke-Script 'accessibility-fallback.ps1' @{ Ip = $Ip; Enable = $true; Confirm = $false } } 'no HomeWatch service'
        Assert-NoDeviceChanges
    }

    'device-report writes the real values using read-only commands' = {
        $dir = New-Device @{ tvlauncherEnabled = $false }
        $report = Join-Path $dir 'report.md'
        Invoke-Script 'device-report.ps1' @{ Ip = $Ip; OutFile = $report } | Out-Null

        $md = Get-Content $report -Raw
        foreach ($expected in 'Physical size: 1920x1080', 'Physical density: 320', "$OurPackage/.HomeActivity",
            "| ``$Plezy`` | 2.24.1 |", 'MemTotal:', 'mode=NORMAL') {
            Assert-True ($md.Contains($expected)) "report should contain '$expected'"
        }
        Assert-True (-not $md.Contains('STUB-SERIAL-123')) 'report must not contain the serial number'
        Assert-NoDeviceChanges
    }

    'player-intent-probe tries Plezy first, then the Plex links' = {
        $dir = New-Device
        $results = Join-Path $dir 'probe.md'
        Invoke-Script 'player-intent-probe.ps1' @{ Ip = $Ip; ServerId = 'abc123'; RatingKey = '4567'; NoPrompt = $true; OutFile = $results } | Out-Null

        $starts = @(Get-ShellLog | Where-Object { $_ -like 'am start*' })
        Assert-Equal 5 $starts.Count 'number of handoffs tried'
        Assert-Equal "am start -W -a android.intent.action.VIEW -d 'plezy://play?server=abc123&id=4567' -p $Plezy" $starts[0] 'Plezy first'
        Assert-True ($starts[1] -like "*'plex://libraries/abc123/unmatched/%2Flibrary%2Fmetadata%2F4567'*") "the Plex app's title link second"
        Assert-True ($starts[2] -like "*'plex://preplay/?metadataKey=%2Flibrary%2Fmetadata%2F4567&server=abc123'*") 'Plex preplay third'
        Assert-True ((Get-Content $results -Raw).Contains('| Plezy deep link | `plezy://play?server=abc123&id=4567` | started |')) 'results file'
    }

    'player-intent-probe -WhatIf starts nothing' = {
        $dir = New-Device
        Invoke-Script 'player-intent-probe.ps1' @{
            Ip = $Ip; ServerId = 'abc123'; RatingKey = '4567'; NoPrompt = $true
            OutFile = (Join-Path $dir 'probe.md'); WhatIf = $true
        } | Out-Null
        Assert-NoDeviceChanges
    }

    'player-intent-probe rejects ids that would break a deep link' = {
        New-Device | Out-Null
        Assert-Throws { Invoke-Script 'player-intent-probe.ps1' @{ Ip = $Ip; ServerId = 'abc 123'; RatingKey = '4567'; NoPrompt = $true } } 'ServerId'
        Assert-Throws { Invoke-Script 'player-intent-probe.ps1' @{ Ip = $Ip; ServerId = 'abc123'; RatingKey = '4567&x=1'; NoPrompt = $true } } 'RatingKey'
    }

    'packages that must stay enabled are never touched' = {
        New-Device | Out-Null
        function Test-Guard {
            [CmdletBinding(SupportsShouldProcess)]
            param([string]$Package)
            Invoke-DeviceChange -Cmdlet $PSCmdlet -Serial $Serial -Command "pm disable-user --user 0 $Package" -Undo "pm enable $Package"
        }
        foreach ($package in $DoNotTouch) {
            Assert-Throws { Test-Guard -Package $package -Confirm:$false } 'Refusing to touch'
        }
        Assert-NoDeviceChanges
    }

    # AGENTS.md is the map agents read first: a renamed file mustn't leave it pointing nowhere.
    'every path AGENTS.md names exists' = {
        $root = Split-Path $Scripts -Parent
        $app = 'app/src/main/kotlin/com/phonoodlesramen/actioncenter'
        $core = 'guide-core/src/commonMain/kotlin/com/phonoodlesramen/actioncenter'
        $names = git -C $root ls-files | ForEach-Object { Split-Path $_ -Leaf } | Sort-Object -Unique
        $missing = [System.Collections.Generic.List[string]]::new()
        $checked = 0
        foreach ($line in Get-Content (Join-Path $root 'AGENTS.md')) {
            # A table row's second cell is its packages, under the app's source.
            $cells = @(if ($line -match '^\|') { $line.Trim().Trim('|').Split('|') })
            $packages = @(if ($cells.Count -ge 3) { [regex]::Matches($cells[1], '`([^`]+)`') | ForEach-Object { $_.Groups[1].Value } })
            foreach ($token in [regex]::Matches($line, '`([^`\s]+)`') | ForEach-Object { $_.Groups[1].Value }) {
                $path = $null
                $path = if ($token -match '^(app|guide-core|station|scripts|docs|gradle|\.github)/') { $token }
                elseif ($token -match '^core/(.+)$') { "$core/$($Matches[1])" }
                elseif ($token -in $packages) { "$app/$token" }
                if ($path) {
                    $checked++
                    if (-not (Test-Path -LiteralPath (Join-Path $root $path))) { $missing.Add($token) }
                } elseif ($token -match '^[\w-]+\.(kt|kts|ps1|psm1|md|yml|toml|html|py)$') {
                    $checked++
                    if ($token -notin $names) { $missing.Add($token) }
                }
            }
        }
        Assert-True ($checked -gt 50) "Only $checked paths found in AGENTS.md: is the check still reading it?"
        Assert-Equal '' ($missing -join ', ') 'Paths AGENTS.md names that are gone'
    }
}

$env:SHIELD_ADB = Join-Path $PSScriptRoot 'stub/adb.ps1'
$failed = 0
foreach ($name in $Tests.Keys) {
    $script:DeviceDir = $null
    try {
        & $Tests[$name]
        Write-Host "  PASS  $name" -ForegroundColor Green
    } catch {
        $failed++
        Write-Host "  FAIL  $name" -ForegroundColor Red
        Write-Host "        $($_.Exception.Message -replace "`n", "`n        ")"
    } finally {
        if ($script:DeviceDir) { Remove-Item $script:DeviceDir -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
foreach ($name in 'SHIELD_ADB', 'SHIELD_IP_FILE', 'SHIELD_STATE_DIR', 'STUB_ADB_STATE', 'STUB_ADB_LOG') {
    Remove-Item "env:$name" -ErrorAction SilentlyContinue
}
Write-Host "`n$($Tests.Count - $failed) passed, $failed failed."
exit $failed
