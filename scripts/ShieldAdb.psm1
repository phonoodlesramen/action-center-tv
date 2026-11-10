#Requires -Version 7.0
# Shared helpers for the Shield ADB scripts.
#
# Every command that changes the Shield goes through Invoke-DeviceChange, which
# prints it with its undo command, refuses packages that must stay enabled, and
# honours the calling script's -WhatIf / -Confirm.

Set-StrictMode -Version 3.0

$StockLauncher = 'com.google.android.tvlauncher'
# Google TV's launcher. It has to stay on: without it, Home can go to a recovery screen.
$GoogleTvLauncher = 'com.google.android.apps.tv.launcherx'
$OurPackage = 'com.phonoodlesramen.actioncenter'
$DoNotTouch = @(
    'com.google.android.tvrecommendations'    # the SHIELD TV phone remote breaks without it
    'com.google.android.katniss'              # the remote's mic button
    'com.google.android.tungsten.setupwraith'
    $GoogleTvLauncher                         # Google TV's Home: HomeWatch instead (docs/install.md)
)
$OurHome = "$OurPackage/.HomeActivity"
$HomeQuery = 'cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME'
$HomeCandidatesQuery = 'cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME'

function Get-AdbCommand {
    # SHIELD_ADB lets the tests swap in a fake adb.
    if ($env:SHIELD_ADB) { return $env:SHIELD_ADB }
    $onPath = Get-Command adb -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($onPath) { return $onPath.Source }
    $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
    if ($env:LOCALAPPDATA) { $sdkRoots += Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
    foreach ($sdk in $sdkRoots | Where-Object { $_ }) {
        foreach ($name in 'adb.exe', 'adb') {
            $candidate = Join-Path $sdk "platform-tools/$name"
            if (Test-Path $candidate) { return $candidate }
        }
    }
    throw 'adb not found. Install Android SDK Platform-Tools and put platform-tools on PATH, or set ANDROID_HOME.'
}

function Get-IpFile {
    if ($env:SHIELD_IP_FILE) { $env:SHIELD_IP_FILE } else { Join-Path $PSScriptRoot '.shield-ip' }
}

function Format-Serial([string]$Ip) {
    # An emulator (emulator-5554) is addressed by its adb serial as-is.
    if ($Ip -match ':\d+$' -or $Ip -match '^emulator-\d+$') { $Ip } else { "${Ip}:5555" }
}

function Test-NetworkSerial([string]$Serial) {
    # Only network devices (ip:port) need `adb connect`.
    $Serial -match ':\d+$'
}

function Get-ShieldSerial([string]$Ip) {
    if ($Ip) { return Format-Serial $Ip }
    $file = Get-IpFile
    if (Test-Path $file) { return (Get-Content $file -Raw).Trim() }
    throw 'No Shield address yet. Run scripts/connect.ps1 -Ip <shield-ip> first.'
}

function Invoke-Adb {
    # Runs adb and returns its output lines. Throws on a non-zero exit code
    # unless -AllowFailure is set.
    param(
        [Parameter(Mandatory)][string[]]$Arguments,
        [switch]$AllowFailure
    )
    $adb = Get-AdbCommand
    $output = @(& $adb @Arguments 2>&1 | ForEach-Object { "$_" })
    if ($LASTEXITCODE -ne 0 -and -not $AllowFailure) {
        throw "adb $($Arguments -join ' ') failed (exit $LASTEXITCODE): $($output -join ' ')"
    }
    $output
}

function Invoke-AdbShell {
    param(
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$Command,
        [switch]$AllowFailure
    )
    Invoke-Adb -Arguments @('-s', $Serial, 'shell', $Command) -AllowFailure:$AllowFailure
}

function ConvertTo-ShellQuoted([string]$Value) {
    # Single-quote for the Shield's shell, so ? & # in URIs stay literal.
    "'" + ($Value -replace "'", "'\''") + "'"
}

function Get-DeviceState([string]$Serial) {
    foreach ($line in Invoke-Adb -Arguments 'devices') {
        $parts = $line -split '\s+'
        if ($parts.Count -ge 2 -and $parts[0] -eq $Serial) { return $parts[1] }
    }
    $null
}

function Assert-ShieldReady([string]$Serial) {
    # Network debugging connections drop after a reboot: reconnect once.
    $state = Get-DeviceState $Serial
    if (-not $state -and (Test-NetworkSerial $Serial)) {
        Invoke-Adb -Arguments 'connect', $Serial | Out-Null
        $state = Get-DeviceState $Serial
    }
    if ($state -eq 'device') { return }
    if ($state -eq 'unauthorized') {
        throw "The Shield at $Serial hasn't authorised this PC. Accept the 'Allow network debugging' prompt on the TV (tick 'Always allow'), then try again."
    }
    if (-not $state -and -not (Test-NetworkSerial $Serial)) {
        throw "No device $Serial in 'adb devices'. Is the emulator running?"
    }
    if (-not $state) {
        throw "Can't reach the Shield at $Serial. Check that it's on, that Network debugging is on (Settings > Device Preferences > Developer options), and that the IP is right."
    }
    throw "The Shield at $Serial is '$state'. Run scripts/connect.ps1 again."
}

function Get-HomeActivity([string]$Serial) {
    # resolve-activity --brief prints a match line, then the component on its own line.
    $lines = Invoke-AdbShell -Serial $Serial -Command $HomeQuery -AllowFailure
    @($lines | Where-Object { $_ -match '^\S+/\S+$' }) | Select-Object -Last 1
}

function Test-RealHome([string]$Component) {
    # A specific launcher: not Android's "which Home app?" chooser or the system's FallbackHome.
    [bool]$Component -and $Component -notlike 'android/*' -and $Component -notlike '*FallbackHome'
}

function Get-HomeCandidates([string]$Serial) {
    @(Invoke-AdbShell -Serial $Serial -Command $HomeCandidatesQuery -AllowFailure |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ -match '^\S+/\S+$' })
}

function Get-PreferredHome([string]$Serial) {
    # The saved Home choice, even when installing another launcher made Android ignore it.
    $item = $null
    foreach ($line in Invoke-AdbShell -Serial $Serial -Command 'dumpsys package preferred-xml' -AllowFailure) {
        if ($line -match '<item name="([^"]+)"') { $item = $Matches[1] }
        elseif ($line -match '</item>') { $item = $null }
        elseif ($item -and $line -match 'android\.intent\.category\.HOME') { return $item }
    }
    $null
}

function Get-AccessibilityServices([string]$Serial) {
    $value = (@(Invoke-AdbShell -Serial $Serial -Command 'settings get secure enabled_accessibility_services') -join '').Trim()
    if (-not $value -or $value -eq 'null') { return @() }
    @($value -split ':' | Where-Object { $_ })
}

function Get-LauncherWatchers([string]$Serial) {
    # Accessibility services of other launchers (Projectivy's, for one) that take Home back.
    $services = Get-AccessibilityServices $Serial
    if (-not $services) { return @() }
    $launchers = @(Get-HomeCandidates $Serial | ForEach-Object { ($_ -split '/')[0] } | Where-Object { $_ -ne $OurPackage })
    @($services | Where-Object { $launchers -contains ($_ -split '/')[0] })
}

function Get-AccessibilityListCommand([string[]]$Services) {
    if ($Services) { "settings put secure enabled_accessibility_services $(ConvertTo-ShellQuoted ($Services -join ':'))" }
    else { 'settings delete secure enabled_accessibility_services' }
}

function Get-HomeRecordPath([string]$Serial) {
    # What Home was before set-default-home, per device, for rollback-home (git-ignored).
    $dir = if ($env:SHIELD_STATE_DIR) { $env:SHIELD_STATE_DIR } else { $PSScriptRoot }
    Join-Path $dir (".previous-home.{0}.json" -f ($Serial -replace '[^A-Za-z0-9.-]', '_'))
}

function Read-HomeRecord([string]$Serial) {
    $path = Get-HomeRecordPath $Serial
    if (Test-Path $path) { Get-Content $path -Raw | ConvertFrom-Json -AsHashtable } else { $null }
}

function Get-DisabledPackages([string]$Serial) {
    @(Invoke-AdbShell -Serial $Serial -Command 'pm list packages -d' |
        ForEach-Object { $_ -replace '^package:', '' } |
        Where-Object { $_ })
}

function Test-PackageInstalled([string]$Serial, [string]$Package) {
    $output = Invoke-AdbShell -Serial $Serial -Command "pm path $Package" -AllowFailure
    [bool](@($output) -match '^package:')
}

function Assert-NotGoogleTv([string]$Serial) {
    # Google TV's launcher has to stay on, so Home can't be taken from it: HomeWatch instead.
    if (Test-PackageInstalled $Serial $GoogleTvLauncher) {
        throw ("This is a Google TV: its launcher ($GoogleTvLauncher) has to stay on, so Home can't be " +
            "taken from it safely. Use HomeWatch instead: Settings › Setup on the TV, or " +
            "./scripts/accessibility-fallback.ps1 -Enable. See docs/install.md.")
    }
}

function Invoke-DeviceChange {
    # Prints the change and its undo, asks (ConfirmImpact) or skips (-WhatIf),
    # then runs it. Returns $true only if the change ran.
    param(
        [Parameter(Mandatory)][System.Management.Automation.PSCmdlet]$Cmdlet,
        [Parameter(Mandatory)][string]$Serial,
        [Parameter(Mandatory)][string]$Command,
        [Parameter(Mandatory)][string]$Undo
    )
    foreach ($package in $DoNotTouch) {
        if ($Command -match [regex]::Escape($package)) {
            throw "Refusing to touch ${package}: it must stay as it is."
        }
    }
    Write-Host "  Change: adb -s $Serial shell $Command"
    Write-Host "  Undo:   adb -s $Serial shell $Undo"
    if (-not $Cmdlet.ShouldProcess($Serial, "adb shell $Command")) { return $false }
    Invoke-AdbShell -Serial $Serial -Command $Command | ForEach-Object { Write-Host "  $_" }
    $true
}

# Releases (install.ps1 -FromRelease). Each GitHub release carries these two assets: the
# signed APK and its SHA-256, as sha256sum writes it ("<hex>  app-release.apk").
$ReleaseApk = 'app-release.apk'
$ReleaseSha = 'app-release.apk.sha256'
$PublicRepo = 'phonoodlesramen/action-center-tv'

function Get-DefaultRepo([string]$RepoRoot) {
    # The GitHub repository this clone came from, else the public one.
    $url = try { git -C $RepoRoot remote get-url origin 2>$null } catch { $null }
    if ($url -match 'github\.com[:/]([^/]+/[^/]+?)(\.git)?$') { return $Matches[1] }
    $PublicRepo
}

function Get-ReleaseBase([string]$Repo, [string]$Tag) {
    # Where a release's assets are: the latest one, or [Tag]'s. ACTION_CENTER_RELEASE_BASE
    # (a URL or a local folder) replaces it, for the tests.
    if ($env:ACTION_CENTER_RELEASE_BASE) { return $env:ACTION_CENTER_RELEASE_BASE }
    if ($Tag) { "https://github.com/$Repo/releases/download/$Tag" } else { "https://github.com/$Repo/releases/latest/download" }
}

function Save-ReleaseAsset([string]$Base, [string]$Name, [string]$OutFile) {
    if (Test-Path -LiteralPath $Base -PathType Container) {
        Copy-Item -LiteralPath (Join-Path $Base $Name) -Destination $OutFile
    } else {
        Invoke-WebRequest -Uri "$Base/$Name" -OutFile $OutFile -UseBasicParsing
    }
}

function Assert-Sha256([string]$File, [string]$ShaFile) {
    # The release's own SHA-256 must match the APK we have, or nothing is installed.
    $expected = ((Get-Content -LiteralPath $ShaFile -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
    if ($expected -notmatch '^[0-9a-f]{64}$') { throw "$(Split-Path $ShaFile -Leaf) doesn't hold a SHA-256." }
    $actual = (Get-FileHash -LiteralPath $File -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) { throw "The APK's SHA-256 is $actual, but the release says $expected. Not installing it." }
    $actual
}

function Write-Result([bool]$Pass, [string]$Message) {
    if ($Pass) { Write-Host "PASS: $Message" -ForegroundColor Green }
    else { Write-Host "FAIL: $Message" -ForegroundColor Red }
}

Export-ModuleMember -Function * -Variable StockLauncher, GoogleTvLauncher, OurPackage, OurHome, DoNotTouch, HomeQuery, HomeCandidatesQuery, ReleaseApk, ReleaseSha, PublicRepo
