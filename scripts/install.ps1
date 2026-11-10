#Requires -Version 7.0

<#
.SYNOPSIS
Installs Action Center on the Shield (adb install -r).

.DESCRIPTION
Pick the APK with one of:
  -Apk <path>    an APK you already have
  -Build         build the debug APK locally (gradlew assembleDebug)
  -FromRelease   download the signed APK from the latest GitHub release (or -Tag's),
                 and check it against the release's SHA-256 before installing
  -FromCi        download the signed release APK from the latest green CI run (needs gh)
-Repo picks the GitHub repository (owner/name) for -FromRelease and -FromCi: by
default the one this clone came from, else phonoodlesramen/action-center-tv.

Debug and release builds are signed with different keys. Switching between them
needs an uninstall first (run rollback-home.ps1 before that if Action Center is home).

.EXAMPLE
./scripts/install.ps1 -FromRelease
.EXAMPLE
./scripts/install.ps1 -FromRelease -Tag v1.0.0
.EXAMPLE
./scripts/install.ps1 -FromCi
#>
[CmdletBinding(SupportsShouldProcess, ConfirmImpact = 'High', DefaultParameterSetName = 'Apk')]
param(
    [string]$Ip,
    [Parameter(Mandatory, ParameterSetName = 'Apk')][string]$Apk,
    [Parameter(Mandatory, ParameterSetName = 'Build')][switch]$Build,
    [Parameter(Mandatory, ParameterSetName = 'FromRelease')][switch]$FromRelease,
    [Parameter(ParameterSetName = 'FromRelease')][string]$Tag,
    [Parameter(Mandatory, ParameterSetName = 'FromCi')][switch]$FromCi,
    [Parameter(ParameterSetName = 'FromCi')][string]$Branch = 'main',
    [string]$Repo
)

$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ShieldAdb.psm1') -Force
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $Repo) { $Repo = Get-DefaultRepo $repoRoot }

switch ($PSCmdlet.ParameterSetName) {
    'Build' {
        $gradlew = Join-Path $repoRoot ($IsWindows ? 'gradlew.bat' : 'gradlew')
        & $gradlew -p $repoRoot assembleDebug
        if ($LASTEXITCODE -ne 0) { throw 'The debug build failed.' }
        $Apk = Join-Path $repoRoot 'app/build/outputs/apk/debug/app-debug.apk'
    }
    'FromRelease' {
        $base = Get-ReleaseBase $Repo $Tag
        $dir = Join-Path ([IO.Path]::GetTempPath()) "action-center-release-$([guid]::NewGuid().ToString('N').Substring(0, 8))"
        New-Item -ItemType Directory -Path $dir | Out-Null
        $Apk = Join-Path $dir $ReleaseApk
        $sha = Join-Path $dir $ReleaseSha
        Write-Host "Downloading $(if ($Tag) { $Tag } else { 'the latest release' }) from $Repo..."
        try {
            Save-ReleaseAsset $base $ReleaseApk $Apk
            Save-ReleaseAsset $base $ReleaseSha $sha
        } catch {
            throw "Couldn't download $(if ($Tag) { $Tag } else { 'the latest release' }) from $Repo ($($_.Exception.Message))."
        }
        $hash = Assert-Sha256 $Apk $sha
        Write-Host "  SHA-256 matches the release: $hash"
    }
    'FromCi' {
        # A pull request's run has no APK: it's built without the signing key.
        $runId = gh run list --repo $Repo --workflow ci.yml --branch $Branch --status success --limit 20 --json databaseId,event |
            ConvertFrom-Json | Where-Object event -ne 'pull_request' | Select-Object -First 1 -ExpandProperty databaseId
        if (-not $runId) { throw "No successful CI run on $Branch yet." }
        $dir = Join-Path ([IO.Path]::GetTempPath()) "action-center-ci-$runId"
        # Already downloaded (a second TV, say): gh refuses to download into it again.
        $Apk = if (Test-Path $dir) { Get-ChildItem $dir -Filter *.apk -Recurse | Select-Object -First 1 -ExpandProperty FullName }
        if (-not $Apk) {
            gh run download $runId --repo $Repo --name action-center-release --dir $dir
            if ($LASTEXITCODE -ne 0) { throw "Couldn't download the APK from CI run $runId." }
            $Apk = Get-ChildItem $dir -Filter *.apk -Recurse | Select-Object -First 1 -ExpandProperty FullName
        }
    }
}
if (-not $Apk -or -not (Test-Path $Apk)) { throw "APK not found: $Apk" }
$Apk = (Resolve-Path $Apk).Path

$serial = Get-ShieldSerial $Ip
Assert-ShieldReady $serial
Write-Host "  Change: adb -s $serial install -r $Apk"
Write-Host "  Undo:   adb -s $serial uninstall $OurPackage  (run rollback-home.ps1 first if Action Center is your home screen)"
if (-not $PSCmdlet.ShouldProcess($serial, "adb install -r $Apk")) { exit 0 }

$output = Invoke-Adb -Arguments '-s', $serial, 'install', '-r', $Apk -AllowFailure
$output | ForEach-Object { Write-Host "  $_" }
if (-not (@($output) -match '^Success')) {
    if (@($output) -match 'INSTALL_FAILED_UPDATE_INCOMPATIBLE') {
        Write-Host 'The installed copy is signed with a different key (debug vs release). Run rollback-home.ps1, uninstall it, then install again.' -ForegroundColor Yellow
    }
    throw 'Install failed.'
}
Write-Host 'Installed.' -ForegroundColor Green
exit 0
