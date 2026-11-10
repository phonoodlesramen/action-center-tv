# Security and privacy

What Action Center keeps, what leaves your TV, and what you're trusting when you install it.
To report a problem, see [`SECURITY.md`](../SECURITY.md).

## What you're trusting

- **A sideloaded app as your Home.** It isn't reviewed by the Play Store.
  - Install APKs only from this repository's releases, or build them yourself.
  - A release APK is signed with the maintainer's key. Android refuses an update signed with
    a different key, so a tampered APK can't replace yours silently.
- **ADB over the network.** The helper scripts, and recovery if the app ever misbehaves,
  use Android's Network debugging.
  - Anyone who can reach the TV's port 5555 and whose computer you've allowed can control
    the TV completely.
  - Android asks you to allow each new computer on the TV.
  - Keep your home network private.
  - Untick "Always allow from this computer" on a computer you don't own.
  - Turn Network debugging off when you don't need it. Leaving it on is a recovery choice:
    it's how you'd undo a broken Home without a USB port.
- **Plex.** Action Center signs in to plex.tv with the PIN flow (plex.tv/link) as a Plex
  app named "Action Center". It uses Plex's own APIs, including some Plex's apps use but
  don't document, which may change without notice.

## What's stored on the TV

| What | Where | How |
|---|---|---|
| Your plex.tv token, your server's token, and each kid profile's Plex Home token | The app's private DataStore | Sealed with AES-256-GCM. The key is made in the Android Keystore and never leaves it. Lose the key and you sign in again |
| The Claude API key (only if you turn AI blurbs on) | The app's no-backup folder | Sealed the same way |
| The station's client key (only once paired) | Android Keystore, EC P-256 | Generated on the TV. Only its public key is ever sent |
| Your library cache, the guide, profiles, suggestions, app usage counts | The app's private databases | Plain SQLite in app-private storage, which other apps can't read |

App backup is off (`allowBackup="false"`), so none of this goes to a Google backup.
Uninstalling the app deletes all of it.

## What leaves the TV

There's no analytics, telemetry, crash reporting or advertising. The app talks only to:

| Service | When | What it sends |
|---|---|---|
| Your Plex Media Server | Always, once linked | Library sync, artwork, playback. A guide program watched to its credits is marked watched (Settings › Plex › Count guide viewing in Plex, on by default) |
| plex.tv | Sign-in, server discovery, Plex Home users | Your Plex token |
| Plex's catalog (`discover.provider.plex.tv`, `metadata.provider.plex.tv`) | When you search | The words you search for, with your token. "Where to watch" lookups use the catalog's title IDs |
| Open-Meteo | Every 30 minutes, for the weather | The name you type when choosing a city, then that city's coordinates |
| ESPN (`site.api.espn.com`, `a.espncdn.com`) | NFL data, only once Settings › NFL › Show the NFL is on, and only if the TV isn't paired with a station (else the station asks) | Nothing about you: public scoreboard requests |
| The guide station | Only once paired | The guide, your edits, the saved spot, favorites. Over mutual TLS, on your network |
| Anthropic (Claude API) | Only with AI blurbs on, at most once a night | About 30 picks: title, year, genres, rating, length and the pick's reason. No Plex keys, server or account names, or watch dates |
| Other apps on the TV | When you open them | An Android intent: a package name, sometimes a title link |

## Things that listen on your network

### The phone editor (on the TV)

Settings › Plex Guide › Edit on your phone, and Settings › AI blurbs' key page, start a
small web server on the TV.
- **When it runs:** only while that screen is open. It stops when you leave the screen, when
  the home stops, or after 15 minutes without a request.
- **Where it listens:** plain HTTP, on the TV's home-network address only.
- **Signing in** takes one of two things:
  - the QR code's one-time key (128 random bits), which proves you can see the TV;
  - a 6-digit PIN that's new on every run, with 5 wrong guesses locking PIN entry until the
    next run.
- **Sessions** are random cookies, `HttpOnly` and `SameSite=Strict`.
- **Requests:**
  - each is capped in size and time (16 KB of headers, a 256 KB body, 16 connections at
    once);
  - one whose `Host` isn't an IP address (or `localhost`) is refused, which guards against
    DNS rebinding;
  - pages carry a strict Content-Security-Policy.

**The catch:** it's plain HTTP. Someone already on your network with the means to sniff
traffic could see the session cookie, or a Claude API key as you send it. That's acceptable
on a home network and not on a shared one, so don't use the phone editor on public or
shared Wi-Fi.

### The guide station (optional, on your server)

See [`guide-station.md`](guide-station.md) for setup.
- **Encryption:** HTTPS on one port (8443), TLS 1.3 only.
- **Pairing:** the only call without a client certificate. It's rate-limited (5 a minute),
  and a request expires after 5 minutes. To approve a TV, you compare a 6-character code,
  worked out from both sides' keys, on the TV and in the station's log. A machine in the
  middle would produce different codes.
- **Every other call** needs a client certificate from the station's own CA. Revoked
  certificates are refused.
- **Its secrets** live in `data/station/` with mode 600: the CA key (`ca-key.pem`) and the
  station's own Plex sign-in (`plex.json`). Anyone with that folder can issue certificates
  and use that Plex sign-in. Keep backups of it private.
- **The container:**
  - a distroless Java image pinned by digest, with no shell;
  - runs as non-root, with a read-only root filesystem;
  - every Linux capability dropped, `no-new-privileges`, and a 256 MB memory cap.
- **Keep it on your home network.** Don't forward its port, and let only your TVs reach it
  (a firewall rule).

## The HomeWatch accessibility service

HomeWatch is optional and off until you turn it on in Android's Settings › Accessibility.
Action Center's Settings › Setup takes you there.
It's for TVs whose own launcher has to stay on, such as Google TV.
- Android warns that an accessibility service can observe what's on screen and the keys you
  press.
- HomeWatch asks only for window changes and key events, so it can catch the Home button.
  It reads which app is on screen once, when it connects.
- It doesn't log, store or send any of it.
- Leave it off if your TV can make Action Center its Home the normal way.

## Permissions

| Permission | Why | How it's granted |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE` | Plex, weather; the phone editor's address | At install |
| `PACKAGE_USAGE_STATS` | Orders the Apps row by what you open, and when | You, in TV Settings' Usage access (Settings › Setup opens it), or over ADB (`appops set`) |
| `READ_TV_LISTINGS` | Reads Android's Watch Next ("Continue in your apps") | Android's own Allow, from Settings › Setup, or over ADB (`pm grant`) |
| `READ_EXTERNAL_STORAGE` (Android 12 and older), `READ_MEDIA_IMAGES` and `READ_MEDIA_VIDEO` (13 and later) | Your own wallpapers and app banners from the TV's storage | Android's own Allow, from Settings › Setup, or over ADB (`pm grant`) |

Each one can be revoked: `./scripts/grant-permissions.ps1 -Revoke`.

## Building and releasing

- **Pinned code:**
  - dependencies are pinned in `gradle/libs.versions.toml`, and CI checks the Gradle
    wrapper's checksum;
  - the workflows' actions are pinned to commit SHAs;
  - Dependabot proposes updates once a month, as pull requests that CI checks.
- **Signing:** release APKs are signed in CI with a key kept in GitHub's encrypted secrets.
  - Only pushes to `main` and release tags are signed, by a job of their own. That job runs
    no code from the repository: it signs the APK the build made, with the Android SDK's
    `apksigner`. The build, its plugins and its dependencies never run beside the key.
  - Pull requests are never signed. One from a fork or from Dependabot gets no secrets at
    all.
  - A fork's CI runs in the fork, without the key, so its release APKs are unsigned.
- **Releases** are `v*` tags on `main`. Each one carries the APK, its SHA-256, and the
  signing certificate's SHA-256 in its notes. To check a download, see
  [Releasing](releasing.md#checking-a-download).
- **The station's image** is built from `:station:installDist` on a pinned distroless base.
  It's tagged with the commit's short SHA, and a release's with its version too. `latest`
  moves only with `main`.

## A checklist for your house

- [ ] Your TVs and your station host have reserved IPs, on a network only your household
      uses.
- [ ] No router port forwards to the TV (5555) or the station (8443).
- [ ] The station's firewall lets only your TVs reach port 8443.
- [ ] Backups of the station's `data/` folder are private.
- [ ] HomeWatch is on only where it's needed.
- [ ] If you turned AI blurbs on, your Anthropic key has a spending limit.
