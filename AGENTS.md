# AGENTS.md

How to work on Action Center as an AI coding agent, kept short to save your context.
People: start with [README.md](README.md) and [CONTRIBUTING.md](CONTRIBUTING.md).

Action Center is an ad-free home screen for Android TV 11 and later: the NVIDIA SHIELD TV
first, and Google TV through the HomeWatch accessibility service. It's sideloaded, written
in Kotlin with Compose for TV, and has three Gradle modules:

| Module | What | Tests |
|---|---|---|
| `:app` | The Android app | JUnit4, Robolectric and Roborazzi screenshots, in `app/src/test/` |
| `:guide-core` | Kotlin Multiplatform (Android and JVM), no Android APIs. The guide engine, the Plex client and cache, the NFL, and the station's API types | `guide-core/src/jvmTest/` |
| `:station` | The optional guide station: a JVM server in Docker (`station/README.md`) | `station/src/test/` |

## Where things are

Packages are under `app/src/main/kotlin/com/phonoodlesramen/actioncenter/`, and `core/`
means `guide-core/src/commonMain/kotlin/com/phonoodlesramen/actioncenter/`.
- **App wiring:**
  - `ActionCenterApp.kt` makes `AppContainer.kt`, the one manual-DI container;
  - the Home activity is `HomeActivity.kt`;
  - a crash at startup opens safe mode through `safety/CrashGuard.kt`.

| Feature | Package | Start with |
|---|---|---|
| The home screen | `home`, `ui/home`, `ui/home/rows` | `HomeViewModel.kt`, `HomeStateLoader.kt`, `HomeScreen.kt` |
| HomeWatch (Home on Google TV) | `home` | `HomeWatchService.kt`, `HomeWatch.kt` |
| Setup checklist | `setup`, `ui/settings` | `SetupMonitor.kt`, `SetupChecks.kt`, `SetupSettings.kt` |
| Settings | `ui/settings`, `settings` | `SettingsScreen.kt` (one file per section), `SettingsRepository.kt` |
| Plex guide: live channels from your library | `core/guide`, `ui/guide`, `guide` | `GuideEngine.kt`, `GuideScheduler.kt`, `GuideScreen.kt`, `GuideWorker.kt` |
| The guide's player | `player`, `ui/player` | `GuidePlayerActivity.kt`, `GuideTuner.kt`, `GuidePlayerScreen.kt` |
| Phone editor (channels from a phone) | `guide/editor`, `ui/settings` | `EditorServer.kt`, `PhoneEditorScreen.kt` |
| Plex sign-in, client, cache | `plex`, `core/plex`, `core/plex/cache` | `PlexSession.kt`, `PlexTv.kt`, `PlexSync.kt`, `PlexDatabase.kt` |
| Suggestions, lenses, Tonight's Pick | `suggest`, `content`, `ui/home` | `SuggestionEngine.kt`, `TonightsPick.kt`, `LensRules.kt`, `LensCard.kt` |
| AI blurbs (the user's own Claude key) | `ai` | `ClaudeBlurbs.kt`, `AiKeyVault.kt` |
| Profiles and kids | `profiles`, `ui/profiles`, `ui/home` | `Profiles.kt`, `KidPlex.kt`, `WhosWatchingScreen.kt`, `KidHome.kt` |
| Search | `search`, `ui/search` | `LibrarySearch.kt`, `StreamingSearch.kt`, `SearchScreen.kt` |
| The NFL (off until turned on) | `core/nfl`, `nfl`, `ui/nfl` | `EspnClient.kt`, `NflRules.kt`, `NflSource.kt`, `NflScreen.kt` |
| Guide station: the TV's side | `station` | `GuideStation.kt`, `StationClient.kt` |
| Guide station: the server | `station/src/main/kotlin/com/phonoodlesramen/actioncenter/station/` | `StationServer.kt`, `Pairing.kt`, and the types in `core/guide/station/StationApi.kt` |
| Apps, banners, usage | `apps`, `apps/usage`, `ui/apps` | `LaunchableAppsRepository.kt`, `UsageScore.kt`, `AllAppsScreen.kt` |
| Channels and other apps' rows | `tvprovider` | `ChannelList.kt`, `TvProvider.kt` |
| Back to YouTube TV | `lastapp` | `LastApp.kt`, `YouTubeTv.kt` |
| Wallpapers | `ui/wallpaper`, `wallpaper` | `WallpaperLayer.kt`, `Wallpaper.kt` |
| Weather | `weather` | `OpenMeteoClient.kt` |
| Theme, tokens, icons | `ui/theme` | `Tokens.kt`, `Icons.kt`, `BrandIcons.kt` |
| Device scripts (PowerShell 7) | `scripts/` | `ShieldAdb.psm1`, `install.ps1`, `set-default-home.ps1` |
| CI and releases | `.github/workflows/` | `ci.yml`, `station.yml`, `docs/releasing.md` |
| The website (GitHub Pages) | `site/` | `site/README.md`, `site/public/`, `site.yml` |

The docs:
- `docs/design/ui-spec.md`: the decided UI;
- `docs/design/guide-station-api.md`: the station's API;
- `docs/product-brief.md`: the concept;
- `docs/design/brand/`: the TVAC art. Its `make_icons.py` makes the TV banner and the
  launcher icon from it, into `app/src/main/res`.

## Commands, narrowest first

From the repo root. On Windows, use `.\gradlew.bat`.

```bash
./gradlew :app:testDebugUnitTest --tests '*AboutSettingsTest'     # one :app test class
./gradlew :guide-core:jvmTest --tests '*GuideRulesTest'            # one :guide-core class
./gradlew :station:test --tests '*PairingTest'                     # one station class
./gradlew :app:testDebugUnitTest --tests '*AboutSettingsTest' -Proborazzi.test.record=true  # re-record one class's screenshots
pwsh scripts/tests/run-tests.ps1                                    # the device scripts, against a fake adb
python site/check_site.py                                           # the website's pages, links and sitemap
./gradlew testDebugUnitTest                                         # all of :app (several minutes)
./gradlew lint testDebugUnitTest :guide-core:jvmTest :station:test assembleRelease  # what CI runs
./gradlew verifyRoborazziDebug                                      # every screenshot (CI doesn't compare them)
```

Run the narrowest test that covers your change first, and the CI line before you finish.

## Don't read these whole

- `app/src/test/screenshots/`: about 270 PNGs, about 96 MB. Open only the ones your change
  re-recorded.
- `docs/design/ui-spec.md`: about 1,750 lines. Grep its `###` headings, then read one
  section.
- `app/schemas/` and `guide-core/schemas/`: Room writes them. Change the entity and let the
  build regenerate them.
- Test fixtures (`app/src/test/resources/`, `guide-core/src/jvmTest/resources/`): saved
  Plex and ESPN responses. Grep for the field you need.
- The largest Kotlin files: grep them first.
  - `GuideTuner.kt` (about 1,250 lines) and `HomeActivity.kt` (about 1,080);
  - `GuideStation.kt`, `SettingsScreen.kt` and `ProfilesSettings.kt` (about 880 each).
- `LICENSE` and `app/src/main/assets/licences/`: the licence texts.

## Patterns to copy

- **A Settings section and its screenshot test:** `AboutSettings.kt` with
  `AboutSettingsTest.kt`.
  - Add the section to `SettingsSection` in `SettingsScreen.kt`.
  - Tests click through semantics (`performSemanticsAction(SemanticsActions.OnClick)`):
    tv-material ignores touch.
  - Tests render at `TV_1080P` (`TvConfig.kt`), the SHIELD's 1080p, from sample states such
    as `SampleHomeState.kt`.
- **A station API change:**
  - the types go in `StationApi.kt`; raise `StationApi.VERSION`, by its minor for an
    additive change;
  - the server is `StationServer.kt`, the TV's side `StationClient.kt`;
  - the spec is `docs/design/guide-station-api.md`;
  - a small example: the NFL's "show" switch (1.3.0), in `Nfl.kt` and `NflService.kt`.
- **A device script:**
  - every change to a TV goes through `Invoke-DeviceChange` in `ShieldAdb.psm1`, which
    prints the undo and honours `-WhatIf` and `-Confirm`;
  - add a case to `scripts/tests/run-tests.ps1`, and teach the fake adb
    (`scripts/tests/stub/adb.ps1`) any new command.

## Rules

**TV and UI**
- The design canvas is 960×540 dp. Safe margins are 48 dp left and right, 27 dp top and
  bottom.
- Everything works with the D-pad alone.
  - Each panel is `Modifier.focusRestorer().focusGroup()`, in that order: the other way
    round doesn't restore focus.
  - A `LazyRow` is already a focus group, so give it `focusRestorer()` alone.
  - Use `focusProperties` only where the geometric search picks the wrong neighbour.
- Budget for 2 GB of RAM: never decode an image larger than its tile. Keep the network
  off the UI path: render from the cache first.
- The home's view model is `AppContainer.home`, alive as long as the process. Android can
  make a new Home activity while the app runs.
- After a UI change, re-record its screenshots and look at the PNGs before committing.

**Stack**
- AGP 9 compiles Kotlin itself: never apply `org.jetbrains.kotlin.android`. The Compose
  compiler plugin's version sets Kotlin's. Versions are pinned in
  `gradle/libs.versions.toml`.
- `minSdk` is 30, `compileSdk` and `targetSdk` 37. The package is
  `com.phonoodlesramen.actioncenter`, and its label is "Action Center".
- Libraries:
  - no Leanback and no `androidx.tv:tv-foundation`: use Foundation's `LazyRow` and
    `LazyVerticalGrid`;
  - OkHttp with kotlinx.serialization, no Retrofit;
  - Room and DataStore, WorkManager and Coil 3.
- `:guide-core` has no Android APIs: it's shared with the station.

**Devices**
- Never run a command that changes a TV unless the person you're working for asks. Show
  the command first; read-only commands (`dumpsys`, `getprop`) are fine.
- On the stock Android TV launcher, only `com.google.android.tvlauncher` is ever
  disabled. On Google TV, never disable its launcher (`com.google.android.apps.tv.launcherx`):
  HomeWatch takes Home there.
- Never touch these:
  - `com.google.android.tvrecommendations` (the phone remote);
  - `com.google.android.katniss` (the mic button);
  - `com.google.android.tungsten.setupwraith`.
- `settings put secure enabled_accessibility_services` overwrites the list: read it first
  and append.
- The app must never leave a TV without a home screen. A crash at startup opens safe mode,
  and All Apps and Settings stay reachable.
- An update to the stock launcher, or a SHIELD update, can turn the stock launcher back
  on. The app shows a banner, and `scripts/verify-home.ps1` checks.

**Privacy**
- Never commit or log tokens, keys, Plex sign-ins, real names, or real addresses and IPs.
  Tests use made-up names, `192.168.1.x` and `10.0.0.x`.

**Changes**
- One branch and one pull request per change, squash-merged.
- Say in the pull request which parts an AI wrote ([CONTRIBUTING.md](CONTRIBUTING.md)).
