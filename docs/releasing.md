# Releasing

How CI builds, signs and publishes Action Center: for the maintainer, and for forks that
sign their own builds.

## What CI does

| Event | Checks | Signed APK | Station image |
|---|---|---|---|
| A pull request, from a fork or not | Lint, unit tests, a release build, the script tests, and the station's tests when it changed | No. A pull request's code never reaches the signing key | No |
| A push to `main` | The same | Yes, uploaded as the `action-center-release` artifact (`./scripts/install.ps1 -FromCi`) | The short SHA and `latest`, when the station changed |
| A `v*` tag on `main` | The same | Yes, published as a GitHub Release | The short SHA and the version |

The workflows are `.github/workflows/ci.yml` and `.github/workflows/station.yml`. Their
actions are pinned to commit SHAs, with the version in a comment.

**Where the key is used:** only in `ci.yml`'s sign job, which never runs for a pull
request.
- The job runs no code from the repository. It aligns, signs and verifies the APK that the
  build job made, with the Android SDK's `zipalign` and `apksigner`.
- The build, its Gradle plugins and its dependencies never run beside the key.
- A pull request from a fork or from Dependabot gets no secrets at all: GitHub withholds
  them.

## Versions

- **The version name** is what Settings › About shows:
  - a tag's build is named after the tag: `v1.2.0` is 1.2.0;
  - between tags, it's `git describe`'s: 1.2.0-3-gabc1234 is three commits after v1.2.0;
  - before the first tag, it's dev-abc1234;
  - debug builds are always "dev".
- **The version code** is the minutes from 2026-01-01 UTC to the commit. A later commit
  always has a higher one, so a newer build installs over an older one signed with the same
  key.

## The signing key

Android refuses an update signed with a different key. Keep the same key for good: a new
one means everyone uninstalls first.

1. **Make a keystore** once, with the JDK's `keytool`:
   ```bash
   keytool -genkeypair -v -keystore release.jks -alias action-center \
     -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Action Center, O=<your name>"
   ```
2. **Keep it safe,** outside the repository: the keystore and its passwords in a password
   manager, plus an offline backup.
3. **Add four repository secrets** (Settings › Secrets and variables › Actions), or with
   `gh`:
   ```bash
   base64 -w0 release.jks | gh secret set RELEASE_KEYSTORE_BASE64   # macOS: base64 -i release.jks
   gh secret set RELEASE_KEYSTORE_PASSWORD
   gh secret set RELEASE_KEY_ALIAS
   gh secret set RELEASE_KEY_PASSWORD
   ```
   In PowerShell, the first one is
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | gh secret set RELEASE_KEYSTORE_BASE64`.
4. **To sign on your computer,** set `RELEASE_KEYSTORE_PATH` to the keystore's path, and
   `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`. Without them,
   `assembleRelease` is unsigned.

## Cutting a release

1. **Check that `main` is green.**
2. **Pick the version:**
   - a patch (1.2.1) for fixes;
   - a minor (1.3.0) for features;
   - a major (2.0.0) when the TVs and the guide station have to update together.
3. **Tag `main` and push the tag:**
   ```bash
   git switch main && git pull
   git tag -a v1.3.0 -m "Action Center 1.3.0"
   git push origin v1.3.0
   ```
   A tag with a hyphen, such as `v1.3.0-rc.1`, makes a pre-release. `releases/latest` skips
   it; `./scripts/install.ps1 -FromRelease -Tag v1.3.0-rc.1` installs it.
4. **CI runs the checks again,** then publishes the release with:
   - `app-release.apk`, `app-release.apk.sha256` and `LICENSE.txt`;
   - notes with the APK's SHA-256 and the signing certificate's, then the pull requests
     merged since the last release.

   The station's workflow pushes `ghcr.io/<owner>/guide-station:1.3.0`.
5. **Try it:** `./scripts/install.ps1 -FromRelease -Tag v1.3.0`.

**If the release fails:**
- for a passing reason, such as a network error, re-run the failed jobs from the run's page;
- otherwise, fix it on `main` and tag the next patch version. Don't move a tag that's been
  pushed.

## Checking a download

Check the APK against the `.sha256` file from the same release:

```bash
sha256sum -c app-release.apk.sha256           # Linux
shasum -a 256 -c app-release.apk.sha256       # macOS
```

In PowerShell, `(Get-FileHash app-release.apk).Hash` must match the file's first word.

Then check who signed it, with `apksigner` from the Android SDK's build-tools:

```bash
apksigner verify --print-certs app-release.apk
```

This repository's releases are signed by `CN=Action Center, O=phonoodlesramen`, whose
certificate SHA-256 is
`3c7fc77c5e9240aed6f6e9b2172c5989ecc7b7cf36a3c3780707455f5a03577e`.

## Dependabot

Once a month, Dependabot opens pull requests for:
- the actions, all together;
- the Gradle dependencies' minor and patch updates, together;
- each major update, on its own;
- the station image's base.

CI checks each one. A Compose or tv-material update can move pixels, which CI doesn't
compare: run `./gradlew verifyRoborazziDebug` before merging one.
