# Installing Action Center

This guide covers getting the APK, installing it over the network, making it your Home,
undoing all of it, and fixing the usual problems. It takes about 15 minutes.

## Will it work on my TV?

| Device | Status | How it becomes Home |
|---|---|---|
| NVIDIA SHIELD TV (2015-2019), SHIELD Experience 9.2.x, Android 11 | **Main target**, used daily | `set-default-home.ps1` turns off the stock launcher (`com.google.android.tvlauncher`) |
| Other Android TV 11+ devices with the stock Android TV launcher | Should work, untested | The same as the SHIELD |
| Google TV devices (Android 12+), such as Chromecast with Google TV or TVs with Google TV | Works, with HomeWatch | Google TV's launcher has to stay on, so the optional HomeWatch accessibility service sends the Home button to Action Center. **Don't** run `set-default-home.ps1` there: it refuses on a Google TV and points to HomeWatch |
| Fire TV, and Android TV 10 or older | Not supported | — |

The release APK is for ARM (`arm64-v8a` and `armeabi-v7a`). Debug builds add `x86` for
the Android TV emulator.

## The easy way: no computer

Everything happens on the TV, with the remote.

1. **Install Downloader** (free) from the TV's Play Store.
2. **Let Downloader install apps.** Allow it when it asks, or use TV Settings › Apps ›
   Security & restrictions › Unknown sources. The menu's name varies by TV.
3. **Download Action Center.** In Downloader, type the latest release's link and install
   it:
   `https://github.com/phonoodlesramen/action-center-tv/releases/latest/download/app-release.apk`
4. **Open Action Center** from the TV's apps, then go to **Settings › Setup**.
   - It has one row per step: Home button, Continue in your apps, Your pictures, Apps row by
     habit, and Plex.
   - Each step is one button: Android's own Allow, or the right TV Settings screen.
   - A "Finish setup" chip in the top bar takes you back there until it's done.
5. **Press Home:** Action Center opens.

**How it differs from the computer route:**
- **Home goes through HomeWatch,** an accessibility service, so you may see the TV's own
  home for a moment first.
- **The stock launcher stays on.**
- **On a SHIELD,** the computer route below turns the stock launcher off, for the cleanest
  Home.

**Android 13 and later:** some TVs grey out accessibility for apps installed this way
("restricted settings").
- Where TV Settings › Apps › Action Center has **⋮ › Allow restricted settings**, that
  unlocks it.
- Otherwise, install with a computer instead: an `adb install` isn't restricted.

## The full way: with a computer

The rest of this guide.

## Pick how you get the APK

| Option | Best for | What you need |
|---|---|---|
| **A. A release APK** from this repository's Releases page | Most people | Nothing else. Download `app-release.apk` and check its SHA-256 against the one in the release notes ([how](releasing.md#checking-a-download)) |
| **B. Build it yourself** | Developers, or anyone who wants to read what they install | JDK 17 and the Android SDK (see the [README](../README.md#build-from-source)). `./scripts/install.ps1 -Build` builds and installs a debug APK in one go |
| **C. Your fork's CI** | Running your own signed builds | Fork the repo, add the four `RELEASE_*` secrets (see [Releasing](releasing.md#the-signing-key)), and download the `action-center-release` artifact from a green run |

APKs signed with different keys can't update each other: a debug build, a release build and
your fork's build are three different keys. To switch, undo Home and uninstall first (see
[Undo everything](#undo-everything)).

## 1. Prepare the TV

1. **Reserve the TV's IP address** in your router, so it doesn't change.
2. **Turn on developer options:**
   - SHIELD: Settings › Device Preferences › About, and press **Build** seven times.
   - Google TV: Settings › System › About, and press **Android TV OS build** seven times.
3. **Turn on network debugging** in Developer options:
   - SHIELD: **Network debugging**.
   - Other devices: the option may be called "USB debugging" plus "Wireless debugging", or
     ADB may already listen on port 5555 once USB debugging is on.

Keep network debugging on if you can. It's how you'd undo a broken Home from your computer,
and many TVs have no usable USB port. [`security-and-privacy.md`](security-and-privacy.md)
explains the trade-off.

## 2. Prepare your computer

- **`adb`:** install Android SDK Platform-Tools and put `adb` on your PATH.
- **The scripts (optional):** install PowerShell 7 (`pwsh`) on Windows, macOS or Linux, and
  clone this repository. Every step below also has the plain `adb` command.

## 3. Connect

```powershell
./scripts/connect.ps1 -Ip 192.168.1.50
./scripts/device-report.ps1        # read-only: model, Android version, launchers, Home
```

The TV asks whether to allow your computer: allow it. The address is saved in
`scripts/.shield-ip` (git-ignored), so later scripts don't need `-Ip`.

Plain adb: `adb connect 192.168.1.50:5555`.

## 4. Install

```powershell
./scripts/install.ps1 -FromRelease                        # option A: the latest release
./scripts/install.ps1 -FromRelease -Tag v1.0.0            # option A: a given release
./scripts/install.ps1 -Build                              # option B
./scripts/install.ps1 -Apk ~/Downloads/app-release.apk     # an APK you already have (option C)
```

`-FromRelease` downloads the release's `app-release.apk` and its `app-release.apk.sha256`.
It installs only if the two match. `-Repo owner/name` picks a fork's releases.

Plain adb: `adb install -r app-release.apk`. To undo it: `adb uninstall com.phonoodlesramen.actioncenter`
(undo Home first if Action Center is your Home).

## 5. Make it Home

### SHIELD and stock Android TV

```powershell
./scripts/set-default-home.ps1
```

It saves what Home is now, so it can be put back. Then it does whatever of these it needs
to, asking before each:
1. turns off the stock launcher, whose Home priority beats any sideloaded one;
2. makes Action Center the saved Home if other launchers are installed;
3. offers to turn off other launchers' accessibility "Home watchers" (Projectivy has one).
   They would take Home back.

Other launchers stay installed. Then it presses Home and checks.

| Plain adb | Undo |
|---|---|
| `adb shell pm disable-user --user 0 com.google.android.tvlauncher` | `adb shell pm enable com.google.android.tvlauncher` |
| `adb shell cmd package set-home-activity --user 0 com.phonoodlesramen.actioncenter/.HomeActivity` | `adb shell cmd package set-home-activity --user 0 <your previous Home>` |

**Only ever turn off `com.google.android.tvlauncher`.** Never turn off
`com.google.android.tvrecommendations` (the phone remote), `com.google.android.katniss`
(the mic button) or `com.google.android.tungsten.setupwraith`.

### Google TV

**Don't turn off Google TV's launcher (`com.google.android.apps.tv.launcherx`).** On some
models, Home then goes to a recovery screen, and people report black screens and restart
loops. Use HomeWatch instead:

```powershell
./scripts/accessibility-fallback.ps1 -Enable
```

Or on the TV: Settings › System › Accessibility › Action Center Home button › On. Android
warns what an accessibility service can see; [`security-and-privacy.md`](security-and-privacy.md#the-homewatch-accessibility-service)
says what HomeWatch does with it. To turn it off: `-Disable`, or the same switch.

## 6. Grant permissions

```powershell
./scripts/grant-permissions.ps1
```

| Plain adb | What it's for |
|---|---|
| `adb shell appops set com.phonoodlesramen.actioncenter GET_USAGE_STATS allow` | The Apps row, ordered by what you open |
| `adb shell pm grant com.phonoodlesramen.actioncenter android.permission.READ_TV_LISTINGS` | Continue in your apps (Android's Watch Next) |
| `adb shell pm grant com.phonoodlesramen.actioncenter android.permission.READ_EXTERNAL_STORAGE` | Your own wallpapers and app banners |

Each is optional; the app works without them, with less. `-Revoke` undoes all three.
Settings › Setup on the TV does the same with the remote. On Android 13 and later,
"Your pictures" is `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` instead.

## 7. First run

1. **Plex:** the home shows a code. On your phone or computer, go to plex.tv/link and
   enter it, then pick your server.
   - The first library sync can take a few minutes on a big library.
   - The Plex Guide makes its first channels once the sync is done.
2. **Weather:** Settings › Weather, then type your city.
3. **Profiles:** Settings › Profiles. Add kids with their birthdays; each can have their own
   Plex Home user.
4. **Optional:**
   - **The guide station:** for a house with more than one TV
     ([`guide-station.md`](guide-station.md)).
   - **AI blurbs:** Settings › AI blurbs. You send your Anthropic API key from your phone.
   - **The NFL:** Settings › NFL › Show the NFL. It's off until you turn it on; the data is
     ESPN's, for personal use.

## Keep it Home after updates

A SHIELD update, or an update to the stock launcher, can turn the stock launcher back on.
Action Center then shows a banner. Check it, and fix it if it has:

```powershell
./scripts/verify-home.ps1          # read-only
./scripts/set-default-home.ps1     # if it says Home moved
```

## Update Action Center

Install the new APK over the old one: `./scripts/install.ps1 -FromRelease` (or `-Apk <new apk>`), or
`adb install -r`. It must be signed with the same key. Your settings, sign-ins and guide
stay.

## Undo everything

```powershell
./scripts/rollback-home.ps1        # puts your previous Home back, and any watcher it turned off
adb uninstall com.phonoodlesramen.actioncenter
```

Rollback works over network ADB without touching the TV, even if Action Center is
crash-looping. Wait about 20 seconds after it before rebooting.

## Troubleshooting

| What you see | Why | What to do |
|---|---|---|
| "Home button is off" in the top bar | Android turned HomeWatch off, after an update or because of the TV's own "auto-start" settings | Settings › Setup › Turn on HomeWatch |
| Home goes to the stock launcher again | A system update turned it back on | `verify-home.ps1`, then `set-default-home.ps1` |
| Home goes to another launcher (Projectivy, say) | Its accessibility "Home watcher" took it back | Run `set-default-home.ps1` again and let it turn the watcher off |
| A "Safe mode" screen | Action Center crashed at startup, so it opened its safe mode | All Apps and Settings still work. Please [open an issue](https://github.com/phonoodlesramen/action-center-tv/issues/new/choose) with Settings › Diagnostics' details |
| "Use the keyboard on your mobile device" instead of the TV keyboard | Something is connected to the TV as a phone remote (the Google TV app, or another TV's remote feature), so Android's keyboard is a bridge to it | Disconnect that remote. The TV keyboard comes back |
| A guide channel won't play | Usually the Plex server's connection or a codec | Settings › Diagnostics › Playback check |
| `adb connect` times out | The TV's IP changed, or network debugging went off | Check the reserved IP and Developer options |
| Search finds nothing on your apps | "On your apps" needs a Plex sign-in, and only lists apps installed on this TV with a subscription or free offer | Settings › Search |
