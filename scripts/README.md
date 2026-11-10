# Shield ADB scripts

PowerShell 7 scripts for making Action Center the Shield's home screen, checking it,
and undoing it. Run them from the repo root on the PC that can reach the Shield.

**Safety:**
- Every script that changes the Shield prints each command and its undo, then asks
  before running it.
- `-WhatIf` shows what would happen and changes nothing.
- `Get-Help ./scripts/<name>.ps1 -Full` explains each script.

## Before the first run

1. Reserve the Shield's IP in your router.
2. On the Shield: Settings › Device Preferences › About › press *Build* 7 times, then
   Developer options › **Network debugging** on.
3. Have `adb` on PATH (Android SDK Platform-Tools).

## Order of use

| Step | Command | Changes the Shield? |
|---|---|---|
| Connect | `./scripts/connect.ps1 -Ip 192.168.1.50` (or `-Ip emulator-5554` to rehearse on an Android TV emulator) | No (saves the address in `scripts/.shield-ip`) |
| Report | `./scripts/device-report.ps1` | No |
| Install | `./scripts/install.ps1 -FromRelease` (checks the release's SHA-256; `-Tag v1.0.0` for one release), `-Build`, `-FromCi` or `-Apk <path>`. `-Repo owner/name` for a fork | Yes: installs Action Center |
| Make home | `./scripts/set-default-home.ps1` (refuses on a Google TV, whose launcher must stay on: HomeWatch there) | Yes: saves the current Home, then as needed disables `com.google.android.tvlauncher`, makes Action Center the saved Home, and turns off other launchers' Home watchers (Projectivy's). Other launchers stay installed |
| Check | `./scripts/verify-home.ps1` | No. Run it after every SHIELD OTA |
| Permissions | `./scripts/grant-permissions.ps1` (`-Revoke` to undo) | Yes: usage stats + TV listings |
| Playback probe | `./scripts/player-intent-probe.ps1 -ServerId <id> -RatingKey <key>` | Starts Plezy/Plex only |
| Measure | `./scripts/measure-home.ps1` (`-Start` for start times, `-Scroll` for frames while scrolling) | No by default. `-Start` stops and opens Action Center and `-Scroll` presses keys on the TV, so both ask first |
| HomeWatch | `./scripts/accessibility-fallback.ps1 -Enable` / `-Disable` | Yes. Off by default. Home on a Google TV, whose launcher must stay on; the Shield's fallback |
| **Undo** | `./scripts/rollback-home.ps1` | Yes: restores the saved Home (the stock launcher, or Projectivy) and any watcher it turned off |

The previous Home is saved per device in `scripts/.previous-home.<device>.json`
(git-ignored) and removed after a successful rollback. Without it, rollback
restores the stock launcher.

If Action Center ever crash-loops, `rollback-home.ps1` works over network ADB
without touching the TV. Wait about 20 s after it before rebooting.

## Tests

```powershell
pwsh scripts/tests/run-tests.ps1
```

The tests run every script against a fake adb (`tests/stub/adb.ps1`) that simulates
a Shield, so no device is needed. They check the exact command sequence, that
`-WhatIf` never changes anything, that rollback mirrors setup, and that
`tvrecommendations`, `katniss` and `setupwraith` are never touched.
