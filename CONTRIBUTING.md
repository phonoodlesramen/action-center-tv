# Contributing

Thanks for your interest. Action Center is a hobby project with one maintainer, so please
keep changes small and focused, and expect replies within days rather than hours.

## Before you start

- **Bugs:** open an issue using the bug template. Include your device, its Android version,
  and Action Center's version from Settings › About.
- **Features:** open an issue first. The design is written down in
  [`docs/product-brief.md`](docs/product-brief.md) and
  [`docs/design/ui-spec.md`](docs/design/ui-spec.md):
  - items marked [REQ] or [DEC] are settled;
  - changing one needs the maintainer's OK before any code.
- **Security problems:** don't open an issue. Follow [`SECURITY.md`](SECURITY.md).

## Set up

- **JDK 17.** Android Studio's bundled JBR works.
- **The Android SDK** with platform 37 and recent build tools. Compose 1.12 needs
  compileSdk 37, though the app runs on API 30 and up.
- **An Android TV emulator** (API 30, x86) or a real Android TV device with Network
  debugging on. Debug builds include x86 for the emulator.
- **PowerShell 7,** only if you touch `scripts/`.

```bash
./gradlew assembleDebug                         # build the debug APK
./scripts/connect.ps1 -Ip emulator-5554         # or your TV's IP
./scripts/install.ps1 -Build                    # build and install it
```

On Windows, use `.\gradlew.bat`.

## Checks before a pull request

Run these locally first. CI runs the first and the last on every pull request, without
the signing key; the screenshots are yours to compare:

```bash
./gradlew lint testDebugUnitTest :guide-core:jvmTest :station:test assembleRelease
./gradlew verifyRoborazziDebug                  # UI screenshots against app/src/test/screenshots
pwsh scripts/tests/run-tests.ps1                # the ADB scripts, against a fake adb
```

**After a UI change:**
1. Re-record the screenshots: `./gradlew recordRoborazziDebug`.
2. Look at the changed PNGs.
3. Commit them with the change.

The screenshots are drawn at the SHIELD's 1080p TV configuration
(`w960dp-h540dp-land-television-xhdpi`) from sample data.

## Rules of thumb

**TV:**
- **Layout:** the design canvas is 960×540 dp. Keep 48 dp safe margins left and right, and
  27 dp top and bottom.
- **The D-pad:** everything must work with it alone. Each panel is
  `Modifier.focusRestorer().focusGroup()`, in that order. A `LazyRow` is already a focus
  group, so it gets `focusRestorer()` alone. Override neighbours with `focusProperties` only
  where geometric search picks wrong.
- **Memory:** budget for 2 GB of RAM. Never decode an image larger than its tile.
- **The network:** keep it off the UI path. Render from the cache first.
- **Never leave a TV without a home screen.** A crash at startup must reach safe mode, and
  All Apps and Settings must always be reachable.

**Stack:**
- **Libraries:** don't use Leanback or `androidx.tv:tv-foundation`. Use Foundation's
  `LazyRow` and `LazyVerticalGrid`, and `androidx.tv:tv-material` for components.
- **Kotlin:** AGP 9 compiles Kotlin itself, so never apply `org.jetbrains.kotlin.android`.
- **Structure:**
  - dependency injection is manual, through one `AppContainer`;
  - HTTP is OkHttp with kotlinx.serialization (no Retrofit);
  - versions are pinned in `gradle/libs.versions.toml`.
- **`:guide-core`:** it's shared with the station, so it has no Android APIs.

**Privacy:**
- **Logs:** never log or commit a Plex token, an API key, a PIN, or a station key or
  certificate.
- **Tests and sample data:** use made-up names, titles and documentation IP addresses
  (`192.168.1.x`, `2001:db8::`). Never use real ones from your home.

**Device scripts:**
- **Changes:** every change to a device goes through `Invoke-DeviceChange` in
  `scripts/ShieldAdb.psm1`. It prints the undo command and honours `-WhatIf` and `-Confirm`.
- **Hands off:** the scripts must never touch these packages:
  - `com.google.android.tvrecommendations`, the phone remote;
  - `com.google.android.katniss`, the mic button;
  - `com.google.android.tungsten.setupwraith`;
  - `com.google.android.apps.tv.launcherx`, Google TV's launcher.
- **Tests:** `scripts/tests/` checks the exact commands against a fake adb. Add a test
  for any new command.

## Branches, commits and pull requests

- **One branch per change, from `main`:** for example `fix/search-keyboard-61` or
  `feat/nfl-switch-62`.
- **Commits:** reference the issue, as in `Search: open the keyboard on OK (#61)`.
- **Pull requests:**
  - fill in the template;
  - keep each one to a single concern;
  - the maintainer squash-merges them.
- **Signing:** release builds are signed only when these four environment variables are
  set:
  - `RELEASE_KEYSTORE_PATH`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
    `RELEASE_KEY_PASSWORD`;
  - in CI, `RELEASE_KEYSTORE_BASE64` and the three others are repository secrets.

  Pull requests and forks build unsigned release APKs, which is fine for testing.
  [`docs/releasing.md`](docs/releasing.md) covers the key, versions and releases.

## Using AI tools

Much of Action Center was written with the help of AI coding tools. [`AGENTS.md`](AGENTS.md)
is the guide for AI coding agents: a map of the code, the narrowest test commands, the files
not to read whole, and the rules. Codex, Cursor, Copilot and Gemini CLI read it, and
`CLAUDE.md` points Claude Code to it. You're welcome to use AI tools too, on two conditions:
- you've read and tested everything you submit;
- the pull request says which parts an AI wrote.

## License of contributions

Action Center is licensed under the GPL-3.0-or-later ([`LICENSE`](LICENSE)). By
contributing, you agree that your contribution is licensed the same way.
