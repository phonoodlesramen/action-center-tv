# Product Brief: Shield Action Center

The design concept and feature set, in one place. Read this before any plan or code.

IDs in brackets and parentheses, such as U1-U16, G15 or [DEC D12], name the design
decisions each part rests on. The decision records themselves aren't published: this brief
and `docs/design/ui-spec.md` say what was decided.

**UI/UX is decided** (U1-U16): the full design is in `docs/design/ui-spec.md`. Where this
brief says [PROP] about layout, look or interaction, the spec holds the answer.

**Phase 2.5 (Look and layout)** changed the layout below (L1-L12):
- Row B's right-hand spot is the Plex Guide card;
- apps moved to a row below the dashboard;
- Channels and Continue in your apps rows come first;
- wallpapers are a Settings choice.

**Phase 2.6 (Plex Guide)** added live TV from the Plex library (G0-G10):
- the Channels row became the Live row, with your guide channels first;
- the Guide card is real and opens a full guide screen;
- guide channels play in a player inside the launcher, the one exception to D11.

**Phase 2.7 (Plex Guide, more)** finished the guide (G11-G20):
- in the player: start over, pause, a mini guide, and audio and subtitle choice;
- program details, with Open in Plezy;
- channel editing on the TV (Settings › Plex Guide) and on a phone, from a page the
  launcher serves only while that screen is open.

**Phase 3 (Suggestions and lenses)** decided this brief's suggestion proposals (S1-S11):
- a suggestion engine scores the library on what you finish and what you abandon, and
  every pick names its evidence;
- Not tonight, Up Next's order, Quick Watch's cutoff, all eight lenses' rules, D15 and
  the quick actions are settled below;
- the Plex cache keeps each title's full tags from its details (S11).

**Phase 4 (Polish)** decided this brief's context-mode and usage proposals (M1-M12):
- the context modes have a schedule you can change and a Weekend evening mode, and each
  reorders Tonight's Pick; Quick Watch has a weekend cutoff;
- the Apps row goes by what you open at this time of day, from Android's usage stats;
- optional AI blurbs from Claude Haiku 5.5, off by default, with the key sent from a
  phone;
- a measured performance pass, and the 2.7 and Phase 3 leftovers.

**Phase 11 (Keep watching)** lets a viewer hooked while surfing carry on in Plezy or
Plex (K0-K5):
- the guide player's options open with Watch in Plezy (or Plex), which carries on where
  the guide was;
- guide viewing counts in Plex: a program watched to its credits, ¾ of it, is marked
  watched for the profile's Plex user (amends G6);
- Settings › Plex › Play in picks Plezy or Plex for every Play, per TV;
- at an episode's credits, a card offers the show's next episode.

**Phase 13 (The NFL, and favorite channels)** adds both (F1-F11, C1-C5):
- **Favorite guide channels:** each profile's own, kept by the guide station. They come
  first in the Live row in an order you set, first in the full and mini guides, and first
  in the Guide card's Coming up.
- **The NFL,** from the preseason to the Super Bowl:
  - a game-day card in Quick Watch's spot while a game is on, and an NFL row;
  - an NFL screen with games by week, standings by division, conference and league, the
    playoff picture, game details and team pages;
  - Watch hands off to whoever carries the game: YouTube TV's channel or Sunday Ticket,
    Prime Video, Netflix;
  - your teams, your area's teams and Hide scores in Settings › NFL.
- The station fetches ESPN's public scoreboard for the house.

**Phase 14 (The lens card rotates, goes back, shuffles and stays fresh)** (L1-L6):
- the card moves on by itself, every 30 s by default (five speeds), and waits while you're
  on it;
- Back, an arrow in a circle at the card's top left, brings back what it showed;
- Shuffle mixes the lenses, never the same one twice in a row;
- a title on the card on 3 days without being played rests for 30 days (Settings › Lenses
  › Move on from a title: 1 day, 3 days, a week, or only once it's watched), and anything
  played in the last 2 weeks is off every lens;
- the kids' card does the same.

**Phase 15 (Universal search, leaning on Plex)** (Q1-Q7):
- a magnifier in the top bar, and the remote's Search key, open one search field;
- your library first, through Plex's own search, as whoever's watching;
- "90s", "kids", "movies", "shows" and "new" narrow it;
- what's on now, and Google's search for grown-ups;
- then titles on your streaming apps, from Plex's catalog and its "where to watch"
  (subscription and free, installed and allowed apps, no YouTube), in a second PR.

**Back to YouTube TV**, for a house whose TV provider is YouTube TV, without scrolling:
- while YouTube TV is the last thing you watched (left under 4 hours ago), the Guide card is
  "Back to YouTube TV": OK brings it back as its app icon does, and holding OK offers your
  recent YouTube TV channels;
- the YouTube TV channels you open here come right after your favorites in the Live row.

**How to read the source tags:**

| Tag | Meaning | How binding |
|---|---|---|
| **[REQ]** | The operator's own stated requirement | Requirement |
| **[DEC Dn]** | An answered design decision | Requirement |
| **[PROP]** | A proposal from the planning session | Not settled until a phase decides it |

---

## 1. The concept

**What it replaces.** Projectivy on the operator's NVIDIA SHIELD TV, with a custom,
**ad-free home screen** that the operator owns end to end. [REQ]

**What it should feel like.** A **card-based "media action center" dashboard**, not
rows of app icons. [REQ]

**What makes it different.**
- **Personal:** driven by the household's **real viewing habits**, not a catalogue.
- **Context-aware:** it changes with the time and day.
- **Every suggestion explains itself:** "Because you finished three heist films this
  month". [REQ]

**The four jobs.** On pressing Home, the screen should answer, at a glance:
1. **What should I watch tonight?** Tonight's Pick, with a reason.
2. **What am I in the middle of?** Up Next.
3. **What else might we enjoy?** The lens card: one title through one lens at a
   time (Throwback, Classic, For the family, …). [REQ, lens card U17]
4. **What app do I want right now?** App Launchpad, ordered by habit.

Kids get the same idea, scaled to them: their own picks, their own Up Next, their own
"Remember when", and a tone that grows up with them. [REQ]

## 2. Design principles

1. **10-foot UI first.** [REQ]
   - Readable from the couch, a calm dark theme, **one accent colour**, adjustable
     in Settings.
2. **Fast and light.** [REQ]
   - The Tegra X1+ (3 GB SHIELD TV Pro 2019) shares memory with the playback app.
3. **Never wait on the network.** [PROP]
   - Every card renders instantly from local cache. Fresh data swaps in when
     background sync finishes.
   - **AI is never on the UI's critical path.** [REQ]
4. **Every suggestion carries a human-readable reason.** No unexplained picks. [REQ]
5. **Predictable D-pad.** [REQ]
   - A clear focus ring on the focused card.
   - Movement between cards always goes where the eye expects.
   - Quick actions on cards.
6. **Hand off, don't re-implement.** [DEC D11]
   - The launcher picks and launches. Playback happens in Plezy or Plex, by Settings ›
     Plex › Play in. [DEC D11, K4]
   - The one exception is the Plex Guide's channels, which play in the launcher's own
     player so they can join mid-program. [DEC D11 amended, G5]
   - From the guide player, a viewer can branch into Plezy or Plex where the guide was:
     the launcher writes the resume point to Plex, then hands off. [Phase 11, K0b]
7. **Honest about limits.** [DEC D12]
   - Kid profiles shape what's *shown*. They don't lock the device.

## 3. Platform and constraints

| Item | Value | Source |
|---|---|---|
| Device | NVIDIA SHIELD TV Pro (2019), 3 GB RAM | DEC D1 |
| OS | SHIELD Experience 9.2.4 = Android 11 / API 30. No Android 12 coming | research §1 |
| Install | Sideloaded APK over ADB; never on Play Store | REQ |
| App identity | `com.phonoodlesramen.actioncenter`, "Action Center". Permanent | DEC D5 |
| Default home | Stock launcher disabled over ADB, plus a safety net | DEC D2 |
| Display | 1920×1080 → design canvas **960×540 dp**; safe margins 48 dp sides / 27 dp top-bottom | research §5 |
| Audio | Surround sound on **Auto**, so DD+ and TrueHD pass through to the TV. The TV path takes no DTS, so the guide's player decodes DTS (and anything else it can't pass through) to PCM with NextLib's FFmpeg; release builds ship arm64 only for its size | Phase 2.6 Playback check, G15 |
| Stack | Kotlin, Compose for TV (`tv-material` 1.1.0), Room 2.8 + DataStore, WorkManager, Coil 3, OkHttp 5 + kotlinx.serialization, manual DI | DEC D8-D10, research §5 |

## 4. Adult home layout [REQ, geometry PROP]

```
┌──────────────────────────────────────────────────────────────────────────┐
│ 9:41 PM   Good evening, Alex   [Weeknight · Wind-down mode]   ☁ 54°   ● Plex   (A) │  top bar
├─────────────────────────────────────────────┬────────────────────────────┤
│                                             │ LENS CARD (tinted by lens) │
│  TONIGHT'S PICK (hero, backdrop art)        │ THROWBACK · "One year ago  │
│  Title · year · runtime · rating            │  today you watched Heat"   │
│  "Because you finished three heist          │ [Watch again] [Next]       │
│   films this month"                         ├────────────────────────────┤
│  [▶ Play]  [Trailer]  [Not tonight]         │ QUICK WATCH (before 10:30) │
│                                             │ • Episode · 22m  • Short…  │
├─────────────────────────────────────────────┼────────────────────────────┤
│ UP NEXT                                     │ APP LAUNCHPAD              │
│ [still ▬▬▬░░] [still ▬░░░░] [still ▬▬▬▬░]  │ [app][app][app]            │
│  S2E5 · 18m left …                          │ [app][app][All apps]       │
└─────────────────────────────────────────────┴────────────────────────────┘
        Row A/B left = 7/12 width                 right = 5/12 width
```

### Top bar [REQ]
- time;
- greeting;
- **context chip** (for example "Weeknight · Wind-down mode");
- weather (Open-Meteo, attribution required; DEC D16);
- Plex server status;
- **profile switcher**.

### Row A, left, 7/12: Tonight's Pick hero [REQ]
- Backdrop art, title, metadata.
- A **one-line reason**.
- Actions **Play / Trailer / Not tonight**.
- **"Not tonight" feeds back into suggestions.** [REQ]
  - **Decided (S1):** the title is skipped for 2 weeks (the hero and the lens card), and
    its genres and collection get a small minus that fades over 60 days. A second Not
    tonight within 90 days skips it for 6 months. Undo takes it all back.
- **What it can pick** (S5): six picks. A "finish it" episode comes first when a show
  you've watched this month is within 3 episodes of the end of the season you're on. Then
  come films you haven't seen, with one series you haven't started third.

### Row A, right, 5/12, stacked
- **Lens card** (was Throwback; U17-U20)
  - One title at a time, seen through a **lens** named in its eyebrow: Throwback,
    Classic, For the family, Action & adventure, Hidden gem, Finish the series,
    Seasonal, Comfort rewatch.
  - **Throwback stays** as one lens: one year ago today, release anniversaries,
    loved but not revisited. [REQ]
  - **Settings › Lenses** turns lenses on and off. The time of day picks which
    lens leads. [DEC U19]
  - **Moves on by itself**, every 30 s by default, waiting while you're on it; **Back**
    brings back what it showed; **Shuffle** mixes the lenses. [DEC L1-L4]
  - **Stays fresh:** a title passed over on 3 days rests for 30; anything played in the
    last 2 weeks is off every lens. [DEC L5]
  - **Never repeats Tonight's Pick.** [DEC U20]
  - Actions **Watch (again) / Next**; Next runs on into the next lens.
  - **More like this** (S10, from the hero's quick actions) puts titles like Tonight's
    Pick on the card ("MORE LIKE ARRIVAL") until the time picks another lead.
- **Quick Watch** [REQ]
  - Short items that **fit before a set time** (for example "before 10:30").
  - **Decided (S6):** one setting in Settings › Time & modes, default 10:30 PM. From
    half an hour before it, the card shows what fits in the next 45 minutes.
  - **Decided (M4):** Friday and Saturday nights have their own cutoff, default 11:30 PM.
  - It never repeats the hero's show or Up Next.

### Row B, left, 7/12: Up Next [REQ]
- **3 cards** with stills, episode label and time left, and **progress bars**.
- **Ranked by likelihood to continue**, not just by recency.
  - **Decided (S3):** recency, momentum (episodes finished in the last two weeks),
    habit (what you watch at this hour, on this kind of day) and how far in you are.
    Anything untouched for 30 days sinks to the end. It leaves out the hero's show.
  - **Sources (S4, closes D15):** Plex only. Other apps' Watch Next stays in Continue
    in your apps.

### Row B, right, 5/12: Plex Guide card [L10 spot, Phase 2.6]
- Your lineup and what's coming up next ("Coming up at 9:30" with two programs).
- OK opens the **full guide**: channels down the side, 90 minutes across, the next
  24 hours, with a now line and an info panel for the focused program. OK on something
  on now tunes in.
- It becomes **Back to {channel}** after the guide player (P4), or **Back to YouTube TV**
  after YouTube TV, whichever you left last.
- Decisions G0-G10; the design is in `docs/design/ui-spec.md`.

### Below the dashboard [L5-L10]
- **Live** (was Channels; G7): your Plex Guide channels first, each with what's on,
  time left and progress, then YouTube TV's networks. One press tunes in. Your favorites
  lead, then the YouTube TV channels you opened last.
- **Continue in your apps:** other apps' Watch Next (Disney+, YouTube…), not Plex or
  Plezy.
- **Apps:** the App Launchpad as a row of full-size banners, **ordered by my usage at
  this time of day** [REQ], then **All apps**.
  - **Decided (M5):** by how often you open each app within an hour of now, on this kind
    of day, over 8 weeks with recent weeks counting more; then launches from Action
    Center, then A-Z. The launcher keeps its own counts from Android's usage stats.
  Custom banners come from the Icons folder.
- The first row peeks at the bottom of the screen; Down scrolls the home to it.

### Geometry [DEC U5: accepted as shown; see `docs/design/ui-spec.md` §1]
- **Content area:** 864×486 dp inside the safe margins.
- **Columns:** a 12-column grid with 16 dp gutters gives a 7/12 column of about
  497 dp and a 5/12 column of about 351 dp.
- **Heights:** top bar about 44 dp, Row A about 236 dp, Row B about 174 dp, with
  16 dp gaps.
- **Up Next cards:** about 158 dp wide each (16:9 stills).
- **Launchpad tiles:** app banners are 320×180 px = 160×90 dp, which suggests a
  2-column banner grid or a 3×2 grid of smaller tiles. **Decided (U7):** 3×2 app
  banners, five apps plus All apps.

### Focus and navigation [REQ]
Proposed details [PROP]:
- Each panel is a focus group that remembers its last-focused child.
- Explicit neighbours exist only where geometric search would be wrong.
- Pressing Home while already home resets focus to the hero.
- The quick-actions gesture: **decided (U11):** hold OK, plus the Menu button if
  the remote delivers it to apps.

## 5. Context modes [REQ: "layout adapts by context"]

The modes are weeknight evening, weekend morning and late night, and the context chip
names the active mode. [REQ]

**Decided (M1-M4, Phase 4).** The modes change what the cards pick, never where the
cards are. Settings › Time & modes changes each mode's hours; the days are fixed.

| Mode | When (default) | What it changes |
|---|---|---|
| Weeknight evening | Sun-Thu 18:00-22:30 | A "finish it" pick can lead from 4 episodes before a season's end; films over 2h 15m move down |
| Weekend evening (M2) | Fri-Sat 18:00-22:30 | No length limit; films before series; Quick Watch's weekend cutoff (11:30 PM) |
| Late night | Every day 22:30-5:00 | Accent and art dimmed; films over 1h 45m move down; an episode of a season you're in first; Quick Watch shows the next 45 minutes |
| Weekend morning | Sat-Sun 7:00-12:00 | Titles rated G to TV-PG first; For the family leads the lens card. The kid profiles come with Phase 5 |
| Daytime | Any other time | The engine's order |

## 6. Suggestion engine (layered) [REQ]

| Layer | What it does | Reason it produces |
|---|---|---|
| **1. Rules** | In-progress items, recency, time-of-day patterns, genre and cast affinity from Plex history | "You're 2 episodes from the end of Severance" |
| **2. Scoring** | Ranks unwatched library items by similarity to what you **finish** vs **abandon** | "Because you finished three heist films this month" |
| **3. AI (optional, off by default)** | Nightly Claude call writes a one-line "why we picked this" blurb for the hero's picks and each lens's first three. Never on the UI path; a blurb shows only while its pick has the reason it was written for, else the rule reason | Model: Claude Haiku 5.5, `claude-haiku-5-5` (DEC D17; M7-M11) |

- **Every suggestion must carry a human-readable reason.** [REQ]
- **Data:** Plex metadata only (genres, cast, directors, ratings, year, duration,
  watch history with view offsets). No TMDB for now (DEC D14).
  - [PROP] Keep any TMDB data out of AI prompts if TMDB is added later; its terms
    reportedly restrict AI use.
- **Feedback:** "Not tonight" and abandons count as negative signals; finishes and
  rewatches count as positive. [REQ in spirit]
  - **Decided (S2):**
    - a finish is a play in Plex's history (Plex logs only plays past its played
      point);
    - an abandon is a title you got 10+ minutes into and haven't touched for 3 weeks,
      or removed from Up Next;
    - a show you stopped 2 months ago with episodes left counts as half an abandon;
    - your star ratings count too (4+ stars for, 2 or fewer against);
    - everything fades to half after 6 months.
  - The engine runs after each sync, after feedback and on a new day, never on the UI
    path. The cards read its last picks from `suggestions.db`.
- **Lens sources** (all from the Plex library, so everything plays):
  - Throwback: this day in your history, release anniversaries of titles you
    watched, and loved but not revisited. [REQ]
  - Classic: released **25 or more years ago (rolling)**, well rated, never watched
    or not in years. [DEC U18]
  - **The other lenses (decided S7-S9):**
    - For the family: rated PG or lower, and over by 8:30 PM.
    - Action & adventure: by your taste, not seen in 2 years.
    - Hidden gem: your library's top-rated 15%, never played.
    - Finish the series: the next film in a collection you've started, and shows you
      stopped.
    - Seasonal: your collections and labels first, then keywords.
    - Comfort rewatch: what you've finished most.

    The ui-spec §4 has the details.

## 7. Profiles and Kids mode

### Profiles [REQ]
- A profile switcher with **Adult** (the operator) and **one profile per kid**.
- Kid profiles are for ages **7 to 12**.
- Each kid profile stores a **birthdate**.

### Rating ceilings [REQ]
- They come from an **editable age-bracket table** (for example 7-9 vs 10-12) using
  **TV and MPAA ratings**.
- They **advance automatically on birthdays**, so the experience grows up with the
  kids.
- **Per-kid overrides** are allowed.

### Plex mapping
- Each kid profile maps to a **Plex Home user**, so it shows *that kid's* Up Next and
  history. [REQ, adjusted by DEC D11]
- Playback: kids may use the family Plex account in Plezy/Plex and switch Plex users
  themselves. The launcher doesn't need per-kid Plex tokens to play. [DEC D11]
- From Phase 11, a kid linked to a Plex Home user is that user to Plex: Plezy's link
  names them, so Plezy switches to them (asking their PIN if they have one), and guide
  viewing and a branch's resume point are written with their token. A kid without a
  Plex user sends nothing. [K3]

### Filtering and gates
- A **local rating filter** on what the launcher shows. [REQ]
- A **per-profile app allowlist**: hide everything else. [REQ]
- **Entering a kid profile is free. Leaving it, or opening Settings, needs the parent
  PIN.** [REQ, shelved by K5: not built for this household; kept for an open-source
  release, so switching profiles and opening Settings are free]
- **Enforcement level: interface only.** [DEC D12]
  - There is no watchdog, lockdown or device-owner mode. The PIN would be a UI gate.
  - The operator accepted that kids can reach other apps via the Assistant,
    long-press Home, Settings and similar routes.

### Kids layout [REQ]
- The same card system with **bigger tiles**.
- **Playful but not babyish** for ages 7-12.
- Their own Up Next, their own picks, and a **"Remember when"** throwback from their
  history.
- An optional **bedtime wind-down card** after a set time. [Shelved by K6, like the PIN]

## 8. Integrations

| Integration | How | Source |
|---|---|---|
| **Plex** (primary source) | plex.tv 4-character PIN at plex.tv/link (classic token, no JWT); server discovery via `/api/v2/resources` (local connection); Continue Watching hub; library; history (owner token filtered by `accountID`); Plex Home users; photo transcoder for sized art | DEC D13, research §4 |
| **Playback handoff** | **Settings › Plex › Play in**, per TV: Plezy by default when it's installed, else Plex. **Plezy:** `plezy://play?server=<serverClientIdentifier>&id=<ratingKey>`, with `profile=<Plex Home user's UUID>` (Plezy source), then Plezy's home, then the Plex app's home. **Plex:** `plex://libraries/<machineId>/unmatched/%2Flibrary%2Fmetadata%2F<ratingKey>`, which plays the title from its `viewOffset` after Plex's own "Who's watching?" (checked on the Shield, 10/31/2026), then Plex's home, then Plezy. Both start where Plex last had the title | DEC D11, K3, K4, research §4 |
| **Trailers** | Plex extras (`subtype="trailer"`); YouTube TV intent as fallback | research §4, §6 |
| **Weather** | Open-Meteo; location set once in Settings; attribution line | DEC D16 |
| **App usage** | UsageStatsManager (`appops set … GET_USAGE_STATS allow` over ADB) | REQ, research §3 |
| **Other apps' Play Next** | `READ_TV_LISTINGS` (ADB grant). Up Next stays Plex-only (D15, decided by S4); other apps' Watch Next is its own row, Continue in your apps (L9) | DEC D15, L9, S4 |
| **YouTube TV** | No API. Its own home-screen row (10 live networks with tuning links) feeds the Live row after your guide channels; an optional `channels.json` overrides it | L8 |
| **Plex Guide** | In the launcher until Phase 8. From Phase 8 (N1-N7, reopening G0), a **guide station** in Docker on the NAS owns the channels, rules, schedule (48 hours ahead) and the house's saved spot. Every paired TV mirrors it over mutual TLS and keeps working from its copy when the NAS is down; an unpaired TV makes its own guide. The engine (`:guide-core`) is the same in both. A starter lineup from the library cache (episodes and media details included), scheduled a rolling 24 hours ahead in `guide.db`. Channels play in the launcher's ExoPlayer straight from the server over HTTPS `plex.direct`, with no timeline calls, so Plex records no session or history. From Phase 11, a program watched to its credits (¾ of it in one sitting) is marked watched with `PUT /:/scrobble` (no history row), and a branch into Plezy or Plex writes its resume point with `/:/progress` first (amends G6). Audio and subtitle choices read Plex's streams (`/library/metadata/{id}`) and are never written back. The phone editor is a small HTTP server inside the launcher, on the home network only while its screen is open, signed in by QR code or PIN | G0-G20, N1-N7 |
| **The NFL** (Phase 13) | ESPN's public site API (undocumented, no key; Disney's terms allow personal use and frown on automated access, so it's polled gently): the scoreboard, standings, teams, schedules, game summaries and logos. The guide station fetches for the house, every 30 s only while a game is on and a TV is looking, and pushes changes over mutual TLS; an unpaired TV fetches itself. No betting odds. No source says which regional CBS/FOX game an area gets | F1-F11 |
| **Watching the NFL** (Phase 13) | Hands off to the carrier's app, its package named on every intent: YouTube TV's channel (`tv.youtube.com/watch/<id>`, the Live row's links), Sunday Ticket (YouTube TV's home; no link opens one game), Prime Video's home, Netflix's game by title id, Paramount+, Disney+ | F6, F7 |
| **Claude API** (optional) | Nightly WorkManager job, one request with OkHttp and structured output (`output_config.format`). It sends each pick's title, details and the reason's evidence, never a name, server, account or watch dates. The key is pasted on a phone (the phone editor's sign-in) and stored with Android Keystore | DEC D17, M7-M11 |

## 9. Settings (feature inventory) [PROP, derived from the requirements]

- **Look:** accent colour, layout scale.
- **Wallpaper:** style, its options, card style, Now showing (L1-L4).
- **Channels & apps:** the channel list and which apps feed Continue in your apps
  (L8, L9).
- **Profiles:** add/edit profiles and birthdates; the age-bracket table; per-kid
  overrides; per-profile app allowlist; each kid's Plex Home user (K10). The parent PIN is
  shelved (K5).
- **Plex:** account, server choice; Play in (Plezy or Plex, per TV) and Count guide
  viewing in Plex (Phase 11).
- **Plex Guide:** each channel on or off, the order, Remake the starter lineup, and Edit
  on your phone (a QR code to the phone editor) (G1, G18).
- **NFL** (Phase 13): your teams, your area's teams, Sunday Ticket, Hide scores; the
  house's while paired.
- **Time-based:** Quick Watch cutoffs (weeknights, Friday and Saturday); the
  context-mode schedule (M1). Bedtime is shelved (K6).
- **Weather:** location.
- **AI blurbs:** on/off, the API key from a phone, the last run, Write now (M7-M10).
- **Diagnostics:** stock-launcher status, safe mode, the last sync times, the Playback
  check (Phase 2.6; a DTS title and the DTS count from 2.7).

## 10. Non-goals

- **Ads and sponsored rows of any kind.** [REQ]
- **Play Store distribution.** [REQ]
- **A built-in video player for library titles.** Playback is handed off. Only the
  Plex Guide's channels play in the launcher. [DEC D11 amended, G5]
- **Device lockdown for kids.** [DEC D12]
- **TMDB, for now.** [DEC D14]
- **Re-creating the stock launcher's channel/recommendation rows.**
  - Other apps' Play Next rows don't feed Up Next (S4); they have their own row,
    Continue in your apps.

## 11. Phases (detail in `docs/roadmap.md`)

| Phase | Scope |
|---|---|
| 0 | Setup, default home verified on the Shield, ADB scripts, CI, Plezy/Plex handoff probe |
| 1 | Launcher shell: card grid, focus/navigation, App Launchpad, settings screen |
| 2 | Plex: auth, Up Next, library, watch history, Play/Trailer handoff |
| 2.5 | Look and layout: wallpapers, the scrolling home with Channels, Continue in your apps and Apps rows, custom banners, the Plex Guide card |
| 2.6 | Plex Guide: channels from your library, the Live row, the Guide card, the full guide, the guide player, the Playback check |
| 2.7 | Plex Guide, more: start over and pause, mini guide, tracks, DTS, program details, channel editing on the TV and the phone |
| 3 | Suggestions and lenses: full tags in the cache, the engine, reasons, Not tonight, Up Next's order, all eight lenses, Quick Watch's cutoff, More like this |
| 4 | Polish: context modes, usage stats, performance, optional AI blurbs |
| 5 | Profiles and Kids mode |
| 11 | Keep watching: Watch in Plezy or Plex from the guide player where it was, guide viewing counted in Plex, Play in, the credits card |
| 12 | Title logos on Tonight's Pick and the lens card |
| 13 | The NFL (the game-day card, the NFL row and screen, Watch, Settings › NFL) and favorite guide channels, both kept by the guide station |
| 14 | The lens card rotates on its own, goes back, shuffles its lenses, and rests titles you pass over |
| 15 | Universal search: your library through Plex's search, words that narrow it, what's on now, Google; then your streaming apps from Plex's catalog |

Polish came before Profiles and Kids, so the earliest notes number them the other way
round.
