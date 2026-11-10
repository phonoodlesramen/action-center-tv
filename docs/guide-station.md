# The guide station (optional)

The guide station is a small service for an always-on computer on your home network, such
as a NAS, a mini PC or a home server. It gives every TV in the house the same Plex Guide.
**Action Center works fully without it:** a TV that isn't paired makes its own guide.

## Do you need it?

| | Without a station (each TV on its own) | With a station |
|---|---|---|
| Guide channels and schedule | Each TV makes its own from Plex, 24 hours ahead. Two TVs can show different programs on "the same" channel | One lineup and one 48-hour schedule for every TV |
| Editing channels | On that TV, or from your phone through that TV | On any TV, or from your phone; the change reaches every TV |
| "Back to {channel}" | On the TV where you were watching | From any room |
| Favorite channels | Kept on each TV | Each profile's own, on every TV |
| The NFL | Each TV asks ESPN | The station asks once and pushes score changes to the TVs |
| What you run | Nothing | A container (or a Java process), plus a firewall rule |

**One TV:** skip it. **Two or more TVs and an always-on box:** it's worth it. You can add it
later: a TV keeps its settings when it pairs, and goes back to making its own guide if you
unpair it.

## What it needs

- **An always-on machine** on your home network, with a reserved IP.
- **Docker** (with Compose), or Java 21 to run it without Docker.
- **The architecture:** the published image is `linux/amd64` only. That covers Intel and
  AMD NAS models (Synology "+" models, for example), mini PCs and most home servers.
  - ARM machines (Raspberry Pi, ARM NAS models) have no image yet.
  - Running it with Java there is untested.
- **Resources:** about 256 MB of memory (the Compose file caps it there), and disk for the
  guide and a cache of your Plex library's details.
- **Port 8443** reachable from your TVs, and from nothing else.
- **Your Plex server reachable from the machine.** The station signs in to Plex on its
  own.

## Run it with Docker Compose

This works on any host that takes a Compose file. On a Synology NAS, Container Manager has
its own steps: see [`station/README.md`](../station/README.md).

1. **Make a folder** for the station and its `data` folder. Let the user the container will
   run as own it:

   ```bash
   mkdir -p ~/guide-station/data && cd ~/guide-station
   id                      # note your uid and gid for "user:" below
   ```

2. **Save this as `compose.yaml`,** with your values for the three marked lines. It's
   [`station/compose.yaml`](../station/compose.yaml) with its Synology notes taken out.

   ```yaml
   services:
     guide-station:
       image: ghcr.io/phonoodlesramen/guide-station:latest
       pull_policy: always
       container_name: guide-station
       restart: unless-stopped
       user: "1000:1000"            # your uid:gid, who owns ./data
       read_only: true
       tmpfs:
         - /tmp
       cap_drop:
         - ALL
       security_opt:
         - no-new-privileges:true
       mem_limit: 256m
       ports:
         - "8443:8443"
       environment:
         TZ: America/New_York       # your time zone: the guide's days start at its midnight
         STATION_HOSTS: 192.168.1.10   # this machine's reserved IP (and any name the TVs use)
       volumes:
         - ./data:/data
   ```

3. **Start it and watch the log:**

   ```bash
   docker compose up -d
   docker compose logs -f
   ```

   The first line reads `Guide station <version> on port 8443, time zone …`.

## Run it without Docker

The station is a plain JVM application, and the image is built from the same files.

```bash
./gradlew :station:installDist
STATION_DATA=/srv/guide-station STATION_HOSTS=192.168.1.10 TZ=America/New_York \
  station/build/install/station/bin/station
```

To keep it running, use your system's service manager (a systemd unit, say). Run it as its
own user, who owns `STATION_DATA`.

## Settings

| Variable | Default | What |
|---|---|---|
| `STATION_HOSTS` | (empty) | This machine's LAN IPs or names, comma-separated. They go in the station's TLS certificate, so the TVs must use one of them. Changing it makes a new server certificate; the TVs stay paired |
| `TZ` | UTC | The house's time zone |
| `STATION_PORT` | `8443` | The HTTPS port |
| `STATION_DATA` | `/data` | Where it keeps everything (below) |

## Pair your TVs

1. **On the first TV:** Settings › Plex Guide › Guide station. Type the station's IP (add
   `:port` if it isn't 8443), name the room, and pair. The TV shows a 6-character code.
2. **Check the code:** the station's log shows `Pairing: "Living room" code ABC123`. The
   two codes must match. A different code means something else answered at that address,
   so don't approve it.
3. **Approve it within 5 minutes,** either way:
   - make a folder (or an empty file) named the code in `data/approve/`;
   - or, with Docker:
     `docker exec guide-station java -cp "/app/lib/*" com.phonoodlesramen.actioncenter.station.MainKt approve ABC123`.
4. **The TV finishes by itself.** Approve later TVs from a TV that's already paired:
   Settings › Plex Guide › Guide station › Devices. Check their codes the same way.

## Link the station's Plex

From a paired TV: Settings › Plex Guide › Guide station › Plex.
1. Enter the code it shows at plex.tv/link. plex.tv lists the station as "Action Center
   guide station".
2. The station picks your server, on a local connection when it can, and syncs the library
   then and every 30 minutes.
3. The first lineup is made once the first full sync is done.

## Keep it safe

- **Home network only.** Don't forward port 8443 on your router.
- **Firewall:** allow TCP 8443 from your TVs' IPs, then deny it from everything else.
- **The `data/station/` folder** holds the station's CA key and its Plex sign-in. Keep it,
  and every backup of it, private.

The trust model is in [`security-and-privacy.md`](security-and-privacy.md#the-guide-station-optional-on-your-server).

## Back up, update and remove

- **Back up** the whole `data` folder, with the station stopped for a consistent copy.
  Losing it loses:
  - your edited channels, each profile's favorites and the NFL settings;
  - the CA, so every TV has to pair again.
- **Update:** `docker compose pull && docker compose up -d`. With `pull_policy: always`,
  `up` alone pulls too. `data` stays, so the TVs stay paired.
  - The log's first line names the version.
  - To stay on one version, use a release's (`guide-station:1.2.0`) instead of `latest`.
  - The TVs and the station are compatible within a major version. A TV says when its
    station needs an update for a feature.
- **Unpair one TV:** Settings › Plex Guide › Guide station › Devices › the TV › Unpair.
  It goes back to making its own guide, from its copy of the lineup.
- **Remove the station:**
  1. Unpair every TV first. Otherwise they keep the last copy and say they can't reach it.
  2. Stop the container.
  3. Delete the `data` folder.

## What's in `data/`

| Path | What |
|---|---|
| `guide.db`, `plex-cache.db` | The channels and schedule, and the library cache |
| `station/ca.pem`, `ca-key.pem` | The station's CA. Made once: a new one means pairing every TV again |
| `station/server.pem`, `server-key.pem`, `server-hosts` | The server certificate, made again when `STATION_HOSTS` changes |
| `station/devices.json` | The paired TVs and the revoked certificates |
| `station/plex.json` | The station's Plex sign-in and server |
| `station/lineup.json`, `spot.json`, `favorites.json`, `version` | The lineup's server, the house's saved spot, favorites, and the guide's version |
| `station/nfl*.json`, `nfl-version`, `nfl-logos/` | The station's copy of the NFL data, the house's NFL settings, and team logos |
| `approve/` | Drop a code here to approve a TV |

The API the TVs use is in [`design/guide-station-api.md`](design/guide-station-api.md).
