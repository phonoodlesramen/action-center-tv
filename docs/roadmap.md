# Roadmap

How Action Center was built, phase by phase. IDs such as D11 or G15 name the design
decisions a phase rests on; the decision records themselves aren't published.

| Phase | Delivers | Depends on |
|---|---|---|
| **0. Setup** | Repo and workflow scaffold; buildable app; probe/safe-mode home; ADB scripts; CI with a signed APK; default-home verified on a SHIELD; device report; Plezy/Plex handoff probe | D1-D7 |
| **1. Launcher shell** (design in `docs/design/ui-spec.md`) | 960×540 dp card grid (Row A 7/12 + 5/12, Row B 7/12 + 5/12) with placeholder data; focus ring and D-pad map; App Launchpad + All Apps; settings screen (accent colour, layout scale); top bar (clock, greeting, context chip, weather) | D8-D10, D16 |
| **2. Plex** | PIN sign-in; server discovery; Up Next from Continue Watching; library and history cache in Room; artwork through the Plex photo transcoder; Play hands off to **Plezy** (`plezy://play`), then the Plex app, then the app's home | D11, D13, D14 |
| **2.5 Look and layout** | Wallpapers (9 styles, frosted glass, Now showing); a scrolling home with Channels (YouTube TV's networks), Continue in your apps (Watch Next) and an apps row of full-size custom banners; the Plex Guide card as a stub | L1-L12 |
| **2.6 Plex Guide** | Live TV from your own Plex library, all inside the launcher (no Guide Server): episodes and media details in the cache; a Playback check in Diagnostics; a starter lineup of up to 20 channels scheduled a rolling 24 hours ahead (`guide.db`), with trailers or an Up next card as filler; the Live row (your channels, then YouTube TV); the Guide card; the full guide screen; a player for guide channels only that joins mid-program and sends nothing to Plex (D11 amended) | G0-G10 |
| **2.7 Plex Guide, more** | In the player: start over and pause behind live, a mini guide, audio and subtitle choice (Plex's own choice by default, forced subtitles shown), DTS decoded by NextLib's FFmpeg; program details with Open in Plezy; channel editing: Settings › Plex Guide on the TV (on/off, order, remake) and a phone editor the launcher serves while its screen is open (QR code or PIN, rules with a 24-hour preview), edits taking effect after the program on now; the 2.6 follow-ups. Collections in rules use Phase 3's cache (full tags from each title's details) | G11-G20 |
| **3. Suggestions + lenses** | Full tags in the Plex cache from each title's details (S11); a suggestion engine (`suggestions.db`) that scores the library on what you finish and abandon, after each sync, after feedback and on a new day; a reason naming its evidence on every pick; Tonight's Pick with a "finish it" episode first (S5); Not tonight that skips and teaches (S1); Up Next by likelihood to continue, Plex only (S3, S4, closes D15); rules for all eight lenses (S7-S9); Quick Watch with a cutoff setting (S6); quick actions that feed back, and More like this (S10) | D15, S1-S11 |
| **4. Polish** | Context modes with a schedule you can change and a Weekend evening mode, each reordering Tonight's Pick, and a weekend Quick Watch cutoff (M1-M4); the Apps row by what you open at this time of day, from usage stats (M5); a measured performance pass with `scripts/measure-home.ps1`, no Baseline Profile (M6); optional AI blurbs with Claude Haiku 5.5, off by default, key from a phone (M7-M11); the 2.7 and Phase 3 leftovers (M12) | D17, M1-M12 |
| **5. Profiles + Kids** | "Who's watching?" on wake and from the avatar (K1); kids with birthdays and an editable age-bracket table that moves up on birthdays, unrated titles hidden (K3, K4); each kid's own Plex Home user for their Up Next, history and picks (K2); the kids' home with "Remember when" and My apps (K7, K9); kid-safe guide channels and a Family channel (K8); Settings › Profiles on the TV (K10). Interface only (D12). The parent PIN and bedtime are shelved (K5, K6) | D12, K0-K11 |
| **6. Kids' lenses and guide** | The kids' right-hand card is a real lens card: every lens within their ratings, rotating through Settings › Lenses, "Remember when" first, "seen" from their own plays (R1). Guide channels are kid-safe by what they can play, so a kids' show marathon counts on its own; a Ratings choice per channel in Settings › Plex Guide on the TV (R2) | R1, R2 |
| **7. Playback controls** | In the guide player: ⏪ and ⏩ (a tap skips 10 s back or 30 s on; held, they scrub with a marker), an on-screen control row, and Home keeping your place, with the Plex Guide card becoming "Back to {channel}" (P1-P4). Also: "Updating" in the top bar during a Plex sync, and HomeWatch, the Home button for a Google TV whose launcher must stay on | P1-P4 |
| **8. The guide station** | One guide for every TV: a station in Docker on the NAS owns the channels, rules, a 48-hour schedule and the house's saved spot. TVs pair by mutual TLS (each TV's key in its Keystore) and mirror it, offline first; an edit on any TV reaches every TV; Back to {channel} from any room. `:guide-core` shares the engine and the Plex cache with `:station` | N1-N7 |
| **9. Studio and label filters** | Guide channels filtered by Plex studio and label in the phone editor: any of the chosen, with the rest of the rule; an episode by its show's. The Plex cache gains a studio column (v6, migrated, one full sync fills it); the station API is 1.1.0 | F1-F3 |
| **11. Keep watching** | From the guide player, Watch in Plezy (or Plex) carries on where the guide was: the resume point written to Plex first (`/:/progress`), or watched past the credits. Guide viewing counts in Plex: a program watched to its credits, ¾ of it in one sitting, is marked watched (`/:/scrobble`) for the profile's Plex user, amending G6. Settings › Plex › Play in (Plezy or Plex, per TV) for every Play, and Count guide viewing in Plex. At an episode's credits, a card offers the show's next episode | K0-K5 |
| **12. Title logos** | Each title's Plex logo (its `clearLogo`; an episode's show's) in place of its name on Tonight's Pick, yours and a kid's, and on the lens card: a PNG at the box's own size, dimmed late at night; the title while it loads, and when there's none, it fails or it's too dark for the card. The Plex cache gains a logo column (v7, migrated, one full sync fills it) | L1-L3 |
| **13. The NFL, and favorite channels** | Favorite guide channels, each profile's own and kept by the station: first in the Live row in an order you set, first in the full and mini guides and in the Guide card's Coming up. The NFL from the preseason to the Super Bowl: a game-day card in Quick Watch's spot while a game is on, an NFL row, and an NFL screen (Games by week, standings by division, conference and league, the playoff picture, game details, team pages). Watch hands off to YouTube TV's channel, Sunday Ticket, Prime Video or Netflix. Settings › NFL (your teams, your area's teams, Sunday Ticket, Hide scores). The station fetches ESPN's public scoreboard for the house and pushes score changes; the station API is 1.2.0 | F1-F11, C1-C5 (Phase 13's F, not Phase 9's) |
| **14. The lens card rotates, goes back, shuffles and stays fresh** | The card moves on by itself (every 30 s by default, five speeds; it waits while you're on it, a sheet is open or the home is hidden). Back, an arrow at its top left, Up from Watch. Shuffle mixes the lenses, a new order each pass. A title on the card on 3 days without a play rests for 30 (`lens_shown` in `suggestions.db` v3); anything played in the last 2 weeks is off every lens. The kids' card too | L1-L6 |
| **15. Universal search** | A magnifier in the top bar (and the remote's Search key) opens one field. Your library through Plex's own search (`/hubs/search`) as the profile on now, with a title match on the TV's copy offline; "90s", "kids", "movies", "shows" and "new" as chips; what's on now; Google's TV search for grown-ups; Diagnostics › Search check. Then, in a second PR, On your apps: titles from Plex's catalog where its "where to watch" names a subscription or free app on this TV the profile may open (no YouTube), within a kid's ceiling, with Settings › Search › Include streaming apps | Q1-Q7 |

Polish came before Profiles + Kids, so the earliest notes number them the other way round.

## Architecture sketch

```
HomeActivity (Compose for TV)
  └─ DashboardScreen ── per-card ViewModels (StateFlow) ── read-only from Room/DataStore caches
                                         ▲
WorkManager jobs ── PlexSync (every 30 min + on resume) ── OkHttp/kotlinx.serialization ── PMS / plex.tv
                ── SuggestionEngine (after each sync, after feedback, on a new day) ── picks + reasons in suggestions.db
                ── Weather (30 min) ── Open-Meteo
                ── BlurbWorker (nightly, optional, off by default) ── Claude API, claude-haiku-5-5 ── blurbs in suggestions.db (never on the UI path)
                ── AppUsage (after each sync + on resume) ── UsageStatsManager ── the launcher's own counts, by hour and kind of day
                ── GuideWorker (every 3 h + after each sync) ── GuideEngine ── guide.db (rolling 24 h)
Play / Trailer ── Plezy or the Plex app (Settings › Plex › Play in) → the other → app home; YouTube TV for trailers
Tune in (guide channels only) ── GuidePlayerActivity ── ExoPlayer (+ NextLib FFmpeg audio), HTTPS plex.direct, no timeline calls
  └─ Phase 11: watched at the credits (/:/scrobble), a branch's resume point (/:/progress), then Watch in Plezy or Plex
Edit on your phone ── EditorServer (home network, only while its screen is open) ── GuideEditor ── guide.db

Phase 8, once a TV pairs:
Guide station (Docker on the NAS) ── PlexSync + GuideEngine (48 h) ── its own guide.db ── HTTPS, mutual TLS, one port
  └─ each paired TV: GuideStation copies it into guide.db (changes held open, every 5 min, on wake)
                     edits (Settings › Plex Guide, the phone editor) and the saved spot go to the station

Phase 13:
Guide station ── NflService ── EspnClient ── ESPN's public scoreboard, standings, logos (30 s while a game is on and a TV is looking, else 15 min / hourly)
  ├─ /v1/nfl (+ nfl/changes held open) ── each TV's NFL copy ── the game-day card, the NFL row, the NFL screen
  └─ /v1/favorites/{owner | plex:<id>} ── each profile's favorite channels ── the Live row, the guides, Coming up
Watch (NFL) ── WatchLinks ── YouTube TV's channel / Sunday Ticket, Prime Video, Netflix, … (the app's package named)
An unpaired TV fetches ESPN itself with the same :guide-core client
```

The UI never waits on the network: every card renders from cache first. If the cache
is empty, it falls back to the rule-based reason and a placeholder.
