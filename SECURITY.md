# Security policy

Action Center runs as your TV's home screen, holds your Plex sign-in, and can run a small
server on your home network. Please report security problems privately, so a fix can ship
before the details are public.

## Reporting a vulnerability

1. **Open a private report:** on this repository's **Security** tab, choose **Report a
   vulnerability**. This uses GitHub's private vulnerability reporting.
2. **Don't** open a public issue, discussion or pull request about it.
3. **Include:**
   - what's affected: the app, the guide station, a script or CI;
   - the version, from Settings › About or the station's first log line;
   - how to reproduce it, and what an attacker would need (the same network? ADB access?
     a paired TV?).
4. **Never include real secrets:**
   - Plex tokens;
   - API keys;
   - the station's `data/station/` files, especially `ca-key.pem` and `plex.json`;
   - logs that contain any of them.

   If you've posted one by mistake, sign out of Plex everywhere, revoke the key, or make the
   station's CA again (which pairs every TV again).

This is a one-person hobby project. The aim is to reply within a week and fix serious
problems as soon as is practical. Say whether you'd like to be credited when the fix ships.

## Supported versions

Only the latest release and `main` get fixes. The guide station's API is versioned: a fix
may need the TVs and the station updated together.

## Scope

**In scope:**
- **The app** (`app/`): the Plex sign-in and how tokens are kept, the phone editor's server
  and its sign-in, the guide player, the HomeWatch accessibility service, and the data
  stored on the TV.
- **The guide station** (`station/`, `guide-core/`): pairing, mutual TLS, the CA, the
  container image and its defaults.
- **The ADB scripts** (`scripts/`): commands that could leave a TV without a home screen or
  touch packages they shouldn't.
- **CI** (`.github/workflows/`): anything that could leak the signing key or let a pull
  request publish an image or APK.

**Out of scope:**
- Problems in Plex, Android, the TV's firmware, or the streaming apps Action Center opens.
  Report those to their makers.
- Attacks that need ADB access to the TV already. ADB is full control of the device by
  design: protect it as [`docs/install.md`](docs/install.md) describes.
- Exposing the station or the phone editor to the internet. Both are for a home network
  only, and the docs say so.

## How it's protected

The full model is in [`docs/security-and-privacy.md`](docs/security-and-privacy.md). In
short:
- **Secrets on the TV:**
  - Plex tokens and the Claude API key are sealed with AES-256-GCM, using a key that never
    leaves the Android Keystore.
  - App backup is off.
- **The phone editor:**
  - It runs only while its screen is open, on the home network, and stops when idle.
  - Signing in takes a one-time QR key or a new 6-digit PIN, which locks after wrong
    guesses. Sessions are `HttpOnly`, `SameSite=Strict` cookies.
- **The guide station:**
  - Calls use TLS 1.3 with mutual authentication. Each TV's key lives in its Keystore.
  - You approve pairing by comparing a code shown on both the TV and the station.
  - The container runs read-only, as non-root, with no capabilities.
- **No telemetry:** no analytics, crash reporting or ads.
