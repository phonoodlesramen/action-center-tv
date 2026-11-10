# Guide station

A house's Plex Guide, on its NAS (Phase 8): one lineup and schedule for every TV, and the
saved spot ("Back to …") across rooms. Since 1.2.0 (Phase 13), each profile's favorite
channels and the NFL, which it asks ESPN for (`site.api.espn.com`, logos from
`a.espncdn.com`) so the TVs don't. A linux/amd64 container, built by CI into
`ghcr.io/phonoodlesramen/guide-station`. Its API is `docs/design/guide-station-api.md`.

These steps are for a Synology NAS (x86_64) with Container Manager. For Docker Compose
anywhere else, see `docs/guide-station.md`.

**Safety:**
- Home network only: no port forwarding, and a DSM firewall rule lets only the TVs in.
- Every call but pairing needs a certificate the station issued (mutual TLS 1.3).
- `data/station/` holds the station's keys and its Plex token (mode 600). Keep backups of
  it private and never post its files.

## Install (Container Manager)

1. If the `guide-station` package is private (a fork's, say), the NAS logs in to pull it.
   Container Manager's Registry screen is reported not to log in to ghcr.io, so
   the login is over SSH:
   - On GitHub, as the package's owner: Settings › Developer settings › Personal access
     tokens › **Tokens (classic)**, with `read:packages` only. Fine-grained tokens don't
     work with the registry.
   - Over SSH on the NAS: `sudo docker login ghcr.io -u <owner>`, with the token as the
     password. `sudo` matters: Container Manager pulls as root.
   - Or make the package public (Package settings › Change visibility): it holds no
     secrets.
2. In File Station, make `docker/guide-station/data`, signed in as the DSM user the
   container will run as.
3. Container Manager › Project › Create: name `guide-station`, path
   `docker/guide-station`, and paste `station/compose.yaml`. Set its placeholders:
   - `TZ`: your time zone, such as `America/New_York`;
   - `STATION_HOSTS`: the NAS's reserved IP, which the TVs type in;
   - `user`: that DSM user's `uid:gid` (`id <user>` over SSH; `1026:100` is DSM's first user).
   - `image`: `latest` exists once a version is on `main`. A release's version (`1.2.0`)
     stays put. To try a branch first, use its short-sha tag (see "Develop" below).

   "Build project failed" hides the cause. Over SSH, `cd /volume1/docker/guide-station &&
   sudo docker compose up -d` prints it (`unauthorized` means the login above).
4. Start it. Container › guide-station › Log shows
   `Guide station 1.2.0 on port 8443, time zone …`.

The container is read-only, drops every capability and is capped at 256 MB.

## Pair the first TV

1. On the TV: Settings › Plex Guide › Guide station. Enter the NAS's IP and pair. The TV
   shows a code.
2. The log shows `Pairing: "Living room" code ABC123`. The two codes must match: a
   different code means something sits in between, so don't approve it.
3. Approve it within 5 minutes, either way:
   - File Station: in `data/approve`, create a folder named the code (`ABC123`).
   - Over SSH: `sudo docker exec guide-station java -cp "/app/lib/*" com.phonoodlesramen.actioncenter.station.MainKt approve ABC123`
     (the image has no shell, so it's this command, not a terminal).
4. The log says `Paired "Living room" …` and the TV finishes by itself.

Later TVs are approved from a paired one (Settings › Plex Guide › Guide station ›
Devices), after checking the code the same way.

## Link the station's Plex

From a paired TV (Settings › Plex Guide › Guide station › Plex), enter the code it shows
at plex.tv/link. The station has its own sign-in, listed by plex.tv as "Action Center
guide station". It links your own server, on a local connection when it can, and syncs the
library now and every 30 minutes. The first lineup is made once the first full sync is done.

## Update

`compose.yaml` has `pull_policy: always`, so Project › guide-station › Build pulls `latest`
and starts the project on it. `data/` stays, so the TVs stay paired.

A project made from an older `compose.yaml`, without `pull_policy`, keeps the image it has
until it's gone:
1. Project › guide-station › Stop.
2. **Clean**, which removes the project's container: an image a container still uses can't be
   deleted.
3. Image › `ghcr.io/phonoodlesramen/guide-station` › Delete.
4. Project › guide-station › Build, which pulls `latest` and starts it.

Then add `pull_policy: always` under `image:` in the project's YAML, as `compose.yaml` has it.
To stay on one version, use a release's version (`1.2.0`) or a short-sha tag instead of
`latest`. The log's first line
names the version: the TVs need 1.2.0 for favorites and the NFL, and until then keep
favorites on themselves.

## Back up

Back up `docker/guide-station/data` (Hyper Backup), with the project stopped for a
consistent copy. Restore it the same way. Losing it loses the channels you edited, each
profile's favorites and the NFL settings, and the CA, so every TV pairs again.

## Unpair a TV

On any paired TV: Devices › the TV › Unpair (a TV can unpair itself). Its certificate is
revoked for good; pairing again gets a new one. With no paired TV left, stop the project,
delete `data/station/devices.json` and pair again from step 1: every old certificate then
reads as unpaired.

## Firewall

DSM Control Panel › Security › Firewall › Edit rules:
1. Allow TCP 8443 from the TVs' reserved IPs (Source IP › Specific IP).
2. Below it, deny TCP 8443 from all.

## What's in data/

| Path | What |
|---|---|
| `guide.db`, `plex-cache.db` | The channels and schedule, and the library cache: the TVs' own schemas |
| `station/ca.pem`, `ca-key.pem` | The station's CA. Never made again: a new one means pairing every TV again |
| `station/server.pem`, `server-key.pem`, `server-hosts` | The server certificate, made again when `STATION_HOSTS` changes |
| `station/devices.json` | The paired TVs, and the revoked certificates |
| `station/plex.json` | The station's Plex sign-in and server |
| `station/lineup.json`, `spot.json`, `version` | The lineup's server, the house's spot, the guide's version |
| `station/favorites.json` | Each profile's favorite channels, by `owner` or `plex:<id>` |
| `station/nfl.json`, `nfl-settings.json`, `nfl-version` | The station's copy of ESPN's NFL data, the house's NFL settings, and the NFL's own version |
| `nfl-logos/` | The teams' logos, two sizes each, fetched from ESPN once and again monthly |
| `approve/` | Drop a code here to approve a TV |

## Develop

```powershell
.\gradlew.bat :station:test          # unit tests, and a real station on localhost with a fake Plex
.\gradlew.bat :station:installDist   # what the image is built from
docker build --platform linux/amd64 -t guide-station station
```

CI (`.github/workflows/station.yml`) tests it on every pull request that touches it. It
pushes the image:
- on `main`, tagged with the short sha and `latest`;
- on a release tag (`v1.2.0`), with the short sha and the version (`1.2.0`).

To try a branch on the NAS before it merges, run the workflow by hand on that branch
(Actions › Guide station › Run workflow): it pushes that commit's short-sha tag only, and
`latest` stays on `main`.
