# The Guide Station's API (Phase 8)

The contract between the station (`:station`) and the TVs (`:app`). The JSON types live in
`:guide-core` (`…guide.station.api`, kotlinx.serialization), so both sides compile against
the same classes.

## Basics
- **Transport:** HTTPS on one port (default 8443), TLS 1.3 only, the JDK's
  `HttpsServer`. JSON bodies, UTF-8. Times are epoch millis.
- **Versions:** paths start with `/v1`. Every response carries
  `X-Station-Version: <semver>`; a TV refuses a station whose major version it doesn't
  know.
  - 1.1.0 (Phase 9): `ChannelRule.studios` and `.labels`, and `RuleChoices.studios` and
    `.labels`. Each defaults to empty, so a TV and a station a version apart still read
    each other; a TV hides the two rows while a station offers neither.
  - 1.2.0 (Phase 13): each profile's favorite channels and the NFL (below). Every older call
    answers as it did. A 1.1 station answers `404` to the new ones, and a TV then keeps
    favorites on itself and says the station needs an update.
  - 1.3.0: `show` in the NFL's settings, the house's Show the NFL (below). It's
    additive: a 1.2 TV or station ignores it.
- **Who's calling:**
  - Everything but pairing needs a client certificate signed by the station's CA (mutual
    TLS).
  - The TV is identified by its certificate's serial. A revoked serial gets
    `403 {"error":"unpaired"}`; no certificate fails the handshake.
- **Errors:** `{"error": "<plain sentence>"}` with a 4xx or 5xx status. A TV shows the
  sentence as is.

## Pairing (open, rate-limited)
The TV doesn't know the station's CA yet, so it can't verify the station's certificate
during pairing.
- **The code:** the code shown on the TV is a short authentication string over both
  keys: the first 6 characters (Crockford base32) of
  `SHA-256(stationCaDer ‖ tvPublicKeySpki)`. The TV works it out from the CA certificate
  it saw; the station, from its own CA and the key it received. A machine in the middle
  would give the two ends different codes.
- **The TV's key:** EC P-256 in AndroidKeyStore, `PURPOSE_SIGN`, digests NONE and
  SHA-256 (U2). Only its SubjectPublicKeyInfo (base64) is sent.

| Call | Body → answer |
|---|---|
| `POST /v1/pair` | `{name, model, publicKey}` → `{requestId, expiresAt}` (5 minutes). The station logs `Pairing: "<name>" code ABC123` |
| `GET /v1/pair/{requestId}` | → `{state: "pending"·"approved"·"refused"·"expired", certificate?, caCertificate?}`. PEM; `certificate` is the TV's, valid 10 years |

- **Approving:**
  - The first TV: the code on the TV must match the log. Then, in the station's
    `data/approve` folder, make a folder (or a file) named the code with DSM's File Station.
    Or, over SSH, run the image's `approve ABC123` command with `docker exec`
    (`station/README.md`). The image has no shell, so Container Manager's terminal can't.
  - Later TVs: approved from a paired TV, through `devices` below.
- **Keeping it safe:**
  - `requestId` is 128 random bits.
  - At most 5 pairing requests a minute.
  - Pending requests are kept in memory only.
- **After approval:** the TV keeps `caCertificate` as its only trust anchor for the
  station, and sends its certificate on every call.

## The guide (mutual TLS)
| Call | Body → answer |
|---|---|
| `GET /v1/guide` | → `Guide` (below): the lineup and every channel's slots from an hour ago to 48 hours ahead. `ETag: <version>`; `If-None-Match` gets `304` |
| `GET /v1/changes?after=<version>` | Held open up to 60 s → `{version}` as soon as the guide, the spot or a profile's favorites change, `204` on timeout |

`Guide`:
```json
{
  "version": 412,
  "updatedAtMillis": 1793000000000,
  "timeZone": "America/Los_Angeles",
  "channels": [
    {
      "id": "c-comedy", "number": 3, "name": "Comedy", "logo": null,
      "enabled": true, "alignmentMinutes": 30, "filler": "trailers",
      "rule": { "...": "ChannelRule, as guide.db stores it" },
      "contentRatings": ["TV-PG", "TV-14"], "unrated": false,
      "slots": [
        { "startMillis": 1793000000000, "endMillis": 1793001800000,
          "title": "Frasier", "episode": "S3 · E7 · The Focus Group",
          "itemKey": "5101", "showKey": "4800", "art": "/library/metadata/5101/art/1",
          "contentRating": "TV-PG", "filler": false, "offAir": null, "summary": "…" }
      ]
    }
  ]
}
```
- **What the slots are:** `GuideSlot` and `GuideChannel` as they are today (`guide/Guide.kt`),
  so the TV writes them into `guide.db` and its `GuideSource` reads them unchanged.
- **Plex keys:** `itemKey`, `showKey` and `art` are the shared Plex server's own keys. Each
  TV plays and draws art with its own Plex sign-in (G3), and no Plex token or host is ever
  in the guide.
- **`logo`:** a Plex image path drawn over the channel's icon: a one-show channel's (a
  marathon's) show poster, else null. TVs have always drawn it, so filling it needed no new
  version. The icon's glyph isn't sent: each TV picks it from the channel's `name` and `rule`.
- **Disabled channels** come with their slots, so turning one on is instant (2.7).
- **Kids:** `contentRatings` and `unrated` are what each TV's `KidGuide` filters on.

## Editing (mutual TLS)
`GuideEditor`'s calls, one for one. Each answers with the new `version`, and the change
reaches every TV through `changes`. Edits take effect after the program on now (G16).

| Call | Body → answer |
|---|---|
| `GET /v1/editor/choices` | → `RuleChoices` |
| `POST /v1/channels` | `ChannelDraft` → `{id, version}` |
| `PUT /v1/channels/{id}` | `ChannelDraft` → `{version}` |
| `PATCH /v1/channels/{id}` | `{name?, number?, enabled?, index?}` → `{version}`: rename, renumber, on/off, move |
| `POST /v1/channels/{id}/duplicate` | → `{id, version}` |
| `DELETE /v1/channels/{id}` | → `{version}` |
| `POST /v1/preview` | `ChannelDraft` → `{slots}` (24 hours) |
| `POST /v1/lineup/remake` | → `{version}` |

No offline queue: a TV that can't reach the station greys editing out.

## The saved spot (mutual TLS)
One spot for the house: the latest pause on any TV, kept 4 hours (P3, N6).

| Call | Body → answer |
|---|---|
| `GET /v1/spot` | → `{spot: {channelId, slotStartMillis, itemKey, positionMs, savedAtMillis, device, room}·null}` |
| `PUT /v1/spot` | `{channelId, slotStartMillis, itemKey, positionMs, savedAtMillis}` → `{version}`. `device` and `room` come from the caller's certificate and name |
| `DELETE /v1/spot` | Optional `{only: {channelId, slotStartMillis, itemKey}}`, so it's forgotten only while it's still that one → `{version}` |
| `POST /v1/spot/played` | `{channelId}`: another channel played on this TV. It forgets the spot only if this TV saved it → `{version}` |

The station applies `SpotRules` (4 hours, the program still in the schedule) and returns
`null` for a spot that no longer holds.

## Favorite channels (mutual TLS, 1.2.0)
Each profile's own (C1), kept by the station so they follow the profile from TV to TV. The
station knows a profile by a key every TV agrees on (proposal A, `ProfileKeys`):
- `owner`: the adult profile, whoever signed the TVs in to Plex;
- `plex:<id>`: a kid linked to a Plex Home user (K2).

A kid with no Plex user keeps favorites on their TV only.

| Call | Body → answer |
|---|---|
| `GET /v1/favorites/{profileKey}` | → `{channels: [channelId…], version}`, in the profile's order; `{channels: [], version: 0}` for a profile never saved, so a TV that kept its own list while the station was 1.1 sends it up |
| `PUT /v1/favorites/{profileKey}` | `{channels}` → `{channels, version}`. A change moves the house's version, so every TV hears of it through `changes` |

- **A key that's neither** is `400` with a sentence.
- **The list** keeps the TV's order, without repeats. Channels that are off stay in it, and a
  TV leaves out the ones it no longer has.
- **Kept** in `station/favorites.json`.

## The NFL (mutual TLS, 1.2.0)
The station asks ESPN's public site API for the house (F11) and keeps a copy
(`station/nfl.json`), so the TVs get what's known at once and never call ESPN while paired.
The types are `nfl/Nfl.kt` in `:guide-core`.
- **Off** (1.3.0): while the house's NFL is off, the station doesn't ask ESPN at all.
  - **What counts as on:** `NflSettings.showing`, which is `show` once it's chosen.
    Before that, it's on when the house already uses the NFL (its teams, Sunday Ticket or
    Hide scores), so a new station starts off.
  - **Turning it on** (`PUT /v1/nfl/settings` with `show: true`) wakes the poller at once.
  - **What answers while it's off:** `GET /v1/nfl` gives the last copy, or `503` if there's
    never been one; `GET /v1/nfl/settings` always answers, so a TV can show the switch.
- **How often** (proposal F), while it's on:
  - every 30 s while a game is on (or past its kickoff and not final) and a TV called
    `/v1/nfl…` in the last 3 minutes;
  - every 15 minutes on game days otherwise, hourly on other days;
  - once a day, at 4 AM, in the off-season, to notice the preseason;
  - within 5 minutes after a failure.
- **Standings** after a game ends and at 4 AM house time. In the preseason, last season's final
  standings (D).
- **Its own version:** `GET /v1/nfl`'s `ETag` and what `nfl/changes` waits on. It moves when
  anything in `NflState` does, a score or the settings, but not for `updatedAtMillis` alone, so
  a TV that hears nothing new knows its copy is current. It doesn't move the guide's `changes`.
- **A failing ESPN:** the copy stays, and `problem` says why ("Can't reach ESPN.", "ESPN
  answered 503.") until it answers again.

| Call | Body → answer |
|---|---|
| `GET /v1/nfl` | → `NflState` (below). `ETag: <version>`; `If-None-Match` gets `304`. `503` with a sentence until ESPN has answered once |
| `GET /v1/nfl/changes?after=<version>` | Held open up to 60 s → `{version}` as soon as the NFL changes, `204` on timeout |
| `GET /v1/nfl/week/{type}/{week}?season=` | → `NflWeek`: `type` 1 preseason, 2 regular season, 3 postseason; `season` defaults to this one. A finished week is kept for good, one with games to come 15 minutes. `404` for a week with no games |
| `GET /v1/nfl/teams/{abbr}/schedule?season=&seasonType=` | → `{abbr, seasonYear, games}`: this season's regular season by default. Kept 15 minutes |
| `GET /v1/nfl/games/{id}` | → `{game, awayStats, homeStats}`: 1st Downs, Total Yards, Passing, Rushing, Turnovers and Possession for each side. Kept 30 s while it's on, for good once final |
| `GET /v1/nfl/logos/{abbr}?size=s\|l` | → PNG, 64 or 192 px, ESPN's art for dark backgrounds. `Cache-Control: max-age=604800`. Fetched once per team and size, again after a month |
| `GET /v1/nfl/settings` | → `{yourTeams, areaTeams, sundayTicket, hideScores, show?}`, the house's (B). `show` is missing until it's chosen (1.3.0) |
| `PUT /v1/nfl/settings` | The same → `{version}`. A team ESPN doesn't list is `400` |

Any call that reaches ESPN and fails answers its last copy, else `502` with a sentence.

`NflState`:
```json
{
  "version": 57,
  "updatedAtMillis": 1793589960000,
  "phase": "regular",
  "seasonYear": 2026,
  "teams": [{ "abbr": "PHI", "name": "Eagles", "location": "Philadelphia", "color": "#06424D",
              "altColor": "#000000", "conference": "NFC", "division": "East" }],
  "week": {
    "seasonYear": 2026, "seasonType": 2, "week": 8, "label": "Week 8", "byes": ["NO", "NYG", "SF", "HOU"],
    "games": [
      { "id": "401873035", "seasonType": 2, "week": 8, "kickoffMillis": 1793582400000, "kickoffKnown": true,
        "away": "PHI", "home": "WSH", "awayScore": 10, "homeScore": 13, "awayRecord": "2-5", "homeRecord": "1-4-1",
        "state": "in", "detail": "6:20 - 3rd", "period": 3, "clock": "6:20", "possession": "PHI",
        "downDistance": "1st & 10 at PHI 38", "redZone": false, "lastPlay": "…", "tv": ["NBC"], "streaming": [],
        "venue": "Northwest Stadium", "awayLineScores": [10, 0, 0], "homeLineScores": [3, 10, 0] }
    ]
  },
  "calendar": [{ "seasonType": 1, "week": 1, "label": "Hall of Fame Weekend", "startMillis": 1785999600000, "endMillis": 1786604340000 }],
  "standings": [{ "abbr": "BUF", "wins": 6, "losses": 1, "ties": 0, "winPercent": ".857", "seed": 1, "divisionRank": 1,
                  "streak": "W3", "gamesBehind": "-", "divisionRecord": "0-1", "pointsFor": 233, "pointsAgainst": 181,
                  "differential": 52, "clincher": null }],
  "standingsSeason": 2026,
  "morningStandings": ["… the same, as at 4 AM"],
  "settings": { "yourTeams": ["PHI"], "areaTeams": ["PHI"], "sundayTicket": true, "hideScores": false },
  "problem": null
}
```
- **`phase`:** `preseason`, `regular`, `postseason` or `off`, from ESPN's calendar. `off` runs
  from the day after the Super Bowl to the day of the first preseason game; `week` is then
  null.
- **`calendar`:** the season's weeks as ESPN lists them, for ‹ Week and Week ›. The
  postseason's numbers differ by season (2025: 4 the Pro Bowl, 5 the Super Bowl; 2026: 4 the
  Super Bowl), and the Pro Bowl isn't listed.
- **A game:** `state` is `pre`, `in` or `post`. `kickoffKnown` is false while the league hasn't
  set the time (flex weeks). `headline` is a playoff game's round ("AFC Wild Card Playoffs")
  or a neutral site's note ("NFL London Games").
- **`standings`** are `standingsSeason`'s, by division, leaders first. `morningStandings` are
  the 4 AM ones, which a TV draws while Hide scores hides today's games (C).
- **No odds:** ESPN's betting lines are never read.

## Devices and the station (mutual TLS)
| Call | Body → answer |
|---|---|
| `GET /v1/devices` | → `[{id, name, model, pairedAtMillis, lastSeenMillis, pending, code?}]` |
| `POST /v1/devices/{id}/approve` | Approves a pending TV → `{}` |
| `DELETE /v1/devices/{id}` | Unpairs a TV (its serial is revoked); a TV can unpair itself → `{}` |
| `GET /v1/station` | → `{version, plex: {linked, serverName?}, library: {lastSyncMillis?, error?}, timeZone}` |
| `POST /v1/plex/link` | Starts the station's own plex.tv sign-in → `{code, expiresAtMillis}`: the TV shows "go to plex.tv/link and enter <code>" |
| `GET /v1/plex/link` | → `{state: "waiting"·"linked"·"expired", serverName?}` |

- **The device name** is the room the TV is in ("Living room", "Kitchen"), set when it
  pairs and shown on the Back-to card.
- **Plex sign-in:** the station chooses the server the way the launcher does (P3: the
  owned server, a local connection).
