# UI Spec: Home Screen

The design Phase 1 builds, from the product brief, the UI decisions (U1-U16) and the
prototype (`docs/design/home-prototype.html`, set to these picks). Where the prototype and
this spec disagree, this spec wins.

IDs such as U3, L1 or G15 name the design decisions each part rests on. The decision
records themselves aren't published: this spec says what was decided.

**Phase 2.5 (Look and layout, 10/26/2026)** revised U5, U7 and U16
(L1-L12):
- the home scrolls, with rows below the dashboard;
- the launchpad became an apps row, and its spot holds the Plex Guide card;
- wallpapers are a Settings choice.

**Phase 2.6 (Plex Guide, 10/27/2026)** built live channels from the Plex library inside
the launcher (G0-G10):
- the Row B Guide card shows the lineup and what's coming up, and OK opens the full
  guide;
- the Channels row became the Live row: your guide channels first, then YouTube TV's
  networks;
- a full guide screen, and a player for guide channels only (G5).

**Phase 2.7 (Plex Guide, more, 10/27/2026)** added player options, program details and
channel editing (G11-G20):
- in the player: start over, pause, a mini guide, audio and subtitle choice, and DTS
  decoded in the launcher;
- a program details screen, from the full guide and the player's options;
- Settings › Plex Guide edits the lineup on the TV, and Edit on your phone serves an
  editor to a phone on the home network.

**Phase 3 (Suggestions and lenses, 10/27/2026)** fills the media cards from a suggestion
engine (S1-S11):
- Tonight's Pick, the lens card, Quick Watch and Up Next show the engine's picks, each
  with a reason that names its evidence;
- Not tonight and the hides feed back;
- More like this is a temporary lens-card view;
- Quick Watch's cutoff is a setting.

**Phase 7 (Playback controls, 10/28/2026)** gave the guide player rewind, fast forward and
on-screen controls, and kept your place when you go Home (P1-P4):
- ⏪ and ⏩ skip back 10 s and forward 30 s; held, they scrub with a marker on the banner;
- OK shows the banner with a control row;
- Home pauses the player and saves the spot, and the Plex Guide card becomes "Back to
  {channel}" (a kid's Live row gets it as its first card).

**Phase 8 (The guide station, 10/30/2026)** moved the guide to a station on the NAS that
every paired TV mirrors (N1-N7):
- Settings › Plex Guide › Guide station pairs a TV, and shows the station, its Plex and the
  house's TVs;
- editing goes to the station, and dims while it can't be reached;
- the Back-to card names the room when the spot was saved on another TV.

**Phase 10 (Icons, 10/30/2026)** replaced the hand-drawn icons with Google's Material
Symbols Rounded, weight 400 (`ui/theme/Icons.kt`, `docs/third-party-notices.md`):
- filled for media and state glyphs (play, pause, the gear, the weather, warning, person,
  place);
- outlined for line icons (search, info, close, check);
- Back 10 and Forward 30 in the player's control row.

**Phase 11 (Keep watching, 10/31/2026)** lets a viewer carry on in Plezy or Plex from the
guide player (K0-K5):
- the player's options open with Watch in Plezy (or Plex), which carries on where the
  guide was, and its details' button does the same;
- a program watched to its credits counts as watched in Plex (G6 amended);
- at an episode's credits, a card offers the show's next episode;
- Settings › Plex › Play in and Count guide viewing in Plex.

**Phase 12 (Title logos, 10/31/2026)** shows each title's Plex logo in place of its name
(L1-L3):
- on Tonight's Pick (yours up to 60 dp tall, a kid's up to 72) and the lens card (up to
  30 dp; a kid's, the title's line);
- the title waits in the logo's box until it loads, and stays when there's no logo, it
  fails, or it's too dark for the card.

**Phase 13 (The NFL and favorite channels, 11/02/2026)** adds both
(F1-F11, C1-C5):
- **Favorite channels:** each profile's own, kept by the guide station. They come first in
  the Live row in an order you set (Move), first in the full and mini guides, and first in
  the Guide card's Coming up.
- **The NFL:**
  - a game-day card in Quick Watch's spot while a game is on, and an NFL row from the
    preseason to the Super Bowl;
  - an NFL screen with Games, Standings and Playoffs, plus game details and team pages;
  - Watch hands off to YouTube TV's channel, Sunday Ticket, Prime Video or Netflix;
  - Settings › NFL.
- The station fetches the NFL from ESPN for the house and pushes score changes (station API
  1.2.0).

**Phase 14 (The lens card rotates, goes back, shuffles and stays fresh, 11/02/2026)**
(L1-L6): the lens card moves on by itself, Back (an arrow at its top left) brings back
what it showed, Shuffle mixes the lenses, and titles you pass over rest (§4 Lens card,
Settings › Lenses). The kids' card too.

**Phase 15 (Universal search, leaning on Plex, 11/02/2026)**
(Q1-Q7): a magnifier in the top bar opens Search: one field, your library through
Plex's own search, a few words read as chips, what's on now, and Google for grown-ups
(§4 Search). Its second PR adds your streaming apps from Plex's catalog, once Diagnostics ›
Search check showed on a Google TV that "where to watch" answers (11/03/2026).

**Back to YouTube TV (11/03/2026)**: while
YouTube TV is the last thing you watched, the Guide card is "Back to YouTube TV", and OK brings
it back as its app icon does. The YouTube TV channels you open here come right after your
favorites in the Live row (§4 Plex Guide card, Live row).

The prototype shows the Phase 1 layout.

**Tags:** [REQ] brief requirement · [U*n*] UI decision · [PROP] proposal, not settled
· **V** verified in official docs or source.

---

## 1. Canvas and grid

| Item | Value | Source |
|---|---|---|
| Canvas | 960×540 dp (1920×1080 at xhdpi, density 2.0). Confirm with the Phase 0 device report | (V) |
| Safe margins | 48 dp left/right, 27 dp top/bottom | V |
| Content area | 864×486 dp | |
| Columns | 7/12 = 497.33 dp, 5/12 = 350.67 dp, 16 dp gap (12-column grid with 16 dp gutters) | U5 |
| Rows (adult) | Top bar 44, Row A 224, Row B 156, 16 dp gaps. Was 236 and 174 before Phase 2.5 | U5, L5 |
| Below the dashboard | The dashboard fills the first screen. Each row below it has 16 dp above, a label, 8 dp, then 160×90 dp cards with 16 dp gaps, scaled so five fill the content width at every layout scale | L5, L10 |
| The peek | The first non-empty row's label shows whole at the bottom, with about 17 dp of its art below it at 100% (21 at 90%, 13 at 115%). Only art meets the screen edge, never text | L5, TV-OV |
| Rows (kids) | Top bar 44, Row A 214, Row B 196 | U13 |

**Layout scale (U4).** Settings › Look offers 90-115%. It scales density
(`LocalDensity`), so text and icons grow while the grid stays proportional to the
content area. Every layout below is expressed as fractions of the content area, not
fixed dp, and screenshot tests run at 90, 100 and 115%.

```
┌ top bar 44 ─────────────────────────────────────────────────────────────┐
│ 8:41 PM  Good evening, Alex   (● Weeknight · Wind-down)  ☁ 54°  ● Plex  ⚙  (A) │
├ Row A 224 ─────────────────────────────┬───────────────────────────────┤
│ TONIGHT'S PICK (hero, full-bleed art)  │ LENS CARD                116  │
│ Title · meta · reason                  ├───────────────────────────────┤
│ [▶ Play] [Trailer] [Not tonight]       │ QUICK WATCH · before 10:30 92 │
├ Row B 156 ─────────────────────────────┼───────────────────────────────┤
│ UP NEXT  [still][still][still]         │ PLEX GUIDE · 18 CHANNELS      │
│          title · S4E2 · 41m left       │ Coming up at 9:30 · 2 titles  │
└────────────────────────────────────────┴───────────────────────────────┘
  LIVE  [1 Movies][2 Sci-Fi][3 TV Mix] … [ESPN][CNN] ▸   ← peeks; Down scrolls the home
  CONTINUE IN YOUR APPS  [Disney+][YouTube][Prime Video] ▸
  APPS  [YouTube TV][Disney+][Plezy][Plex][Netflix] … [All apps]
```

## 2. Tokens

### Colour [U1, U3]

| Token | Value | Use |
|---|---|---|
| `bg` | `#0E1318` | Screen background |
| `surface` | `#161D25` | Cards without art, sheets |
| `surface2` / `surface3` | `#1F2832` / `#2A3542` | Unfocused / focused rows, All apps tile |
| `text` / `textDim` / `textFaint` | `#E6EDF3` / `#9AA7B4` / `#6B7886` | Primary / secondary / hints |
| `accent` | `#4FC3F7` (cyan) default; Amber `#FFB74D`, Violet `#B39DDB`, Green `#81C784`, Coral `#FF8A65` in Settings | Focus ring, focused buttons, progress, reason icon |
| `accentInk` | `#06202B` | Text on the accent |
| `warm` | `#2B1F17` → `#3A2A1D`, text `#F2D6B8`, eyebrow `#E7B98A` | Throwback lens (other lens tints in §4) |
| `good` / `bad` | `#81C784` / `#E57373` | Plex status dot (always with a text label) |
| `safeMode` / `onSafeMode` | `#FFB74D` / `#1F1300` | Safe-mode banner (Phase 0 screen) |
| `bedtime` | `#1D2552` → `#141A33` | Kids bedtime card |

- **Late night** [REQ, brief §5]: accent = 62% accent + 38% `#4A5563`, artwork at
  75% brightness and 80% saturation.
- **Kids** [U13]: accent = the kid's own colour (sample: Maya coral, Leo `#AED581`).
- Never use colour alone for meaning: status dots always have a label. **V**
  (Color on TV).
- No large white areas, and no hard-edged gradients (TVs band them). **V**

### Type [U4]

tv-material's scale (**V**, `TypeScaleTokens.kt`), Roboto:

| Element | Style | Size / line |
|---|---|---|
| Hero title | headlineMedium, **Bold** in Art-forward | 28 / 36 (22 / 28 if longer than 24 characters) |
| Kid hero title | headlineLarge, Bold | 32 / 40 |
| Clock | titleLarge | 22 / 28 |
| Throwback title | titleLarge | 22 / 28 |
| Greeting, reason line | titleMedium | 16 / 24 |
| Card titles, Quick Watch titles | titleSmall | 14 / 20 |
| Meta lines | bodyMedium / bodySmall | 14 / 20, 12 / 16 |
| Buttons, chip | labelLarge | 14 / 20 |
| Eyebrows ("TONIGHT'S PICK") | labelMedium, uppercase, +1.2 letter spacing | 12 / 16 |

Nothing smaller than 12 sp.

### Shape and spacing

- **Radius:** cards 10 dp (Art-forward), inner tiles and stills 8 dp. Kids: 18 / 12 dp.
- **Buttons:** pill, 36 dp high (30 dp small).
- **Spacing:** 16 dp between panels, 12 dp between launchpad tiles, 8 dp between
  buttons and small items, card padding 12-20 dp.

## 3. Focus and navigation [U2, U10, U11; brief §4 REQ]

- **Cards and tiles:** 2.5 dp `accent` ring, 1.04 scale, shadow `0 10 26 #00000080`,
  150 ms.
- **Up Next:** the ring and lift apply to the still only. The caption stays put.
- **Buttons:** focused = `accent` fill with `accentInk` text. Lens card buttons use
  the lens's own colours (§4), so they stay in its tint.
- **List rows** (Quick Watch, sheets, Settings): focused = `surface3` fill. In
  sheets the focused row fills with the accent.
- **Focus groups:** each panel (top bar, hero, lens card, Quick Watch, Up Next,
  launchpad) is `Modifier.focusRestorer().focusGroup()`, so entering a panel returns
  to its last-focused child. Geometric search handles everything else. Add
  `focusProperties` neighbours only where a Phase 1 focus test shows it going wrong.
- **Home** [U10]: on launch and on `onNewIntent` (Home while already home), scroll
  to the top and focus **Play** in Tonight's Pick. In first run, focus "Get a new code".
- **Back:** on home, consumed (nowhere to go). In a sheet or overlay, it closes it
  and focus returns to the opener. In All apps, it goes back to the All apps tile.
- **Quick actions** [U11]: hold OK (`onLongClick`), or `KEYCODE_MENU` if the remote
  delivers it (unverified, Phase 0 step 8).

| Focused | Up | Down | Left | Right |
|---|---|---|---|---|
| Hero buttons | gear / avatar | Up Next (remembered) | previous button | next button → lens card |
| Top bar's magnifier (Phase 15) | none | the card below | previous item | gear |
| Lens card buttons (Watch, Next) | Back (Phase 14, L3) | Quick Watch | hero (remembered) | next button |
| Lens card Back | gear / avatar | Watch | hero (remembered) | none |
| Quick Watch rows | lens card / previous row | next row → launchpad | hero | none |
| Up Next | hero (remembered) | none | previous card | next card → launchpad |
| Guide card (Row B right) | Quick Watch (or the game-day card) | first row below | Up Next | none |
| Game-day card (Phase 13, in Quick Watch's spot) | lens card | Guide card | hero (remembered) | none |
| Rows below (NFL, Live, Continue in your apps, Apps) | the row above; from the first, the dashboard, scrolled to the top | next row | previous card | next card (the apps row ends at All apps) |

Phase 2.5: Down from Up Next or the Guide card goes to the first row below, and the
home scrolls so that row shows whole. Each row stops at the same place every time. A
`LazyRow` remembers its card with `Modifier.focusRestorer()` alone: a LazyRow is
already a focus group, and adding `focusGroup()` stops it restoring.

**Full guide and player** (Phase 2.6, 2.7, 7). The guide grid takes the D-pad itself
(`GuideGridFocus.kt`), so focus moves by time, never by geometric search. Over the
picture the player has nothing focusable: the activity reads the keys
(`player/GuidePlayerActivity.kt`). The banner's control row (P2) is the exception: while
it's up, the activity hands Left, Right and OK to Compose, and the row is one focus group
(`focusRestorer().focusGroup()`).

| Key | Full guide | Player |
|---|---|---|
| Up / Down | the channel above or below, on the program at the same time (else its nearest in the window); a channel with nothing in the window is skipped | Up: the next channel, Down: the previous, in lineup order, wrapping, like Channel up / down and Android's Live Channels (11/01/2026); at the live point, also with the control row up (it goes); in the mini guide, moves its pick up or down the list |
| Left / Right | previous / next program; past the window's edge, the window pages | opens the mini guide (G12); with the control row up, moves along it |
| Channel up / down | none | next / previous channel; in the mini guide, moves its pick |
| OK | on now: tune in; later: the program's details; over: nothing | the banner with its control row, Play/Pause focused (P2; Info shows the banner alone); with the row up, presses the focused control; in the mini guide, tunes in to its pick |
| Hold OK or Menu | the program's quick actions (§4) | the options sheet (§4) |
| Play/pause | none | pauses, then carries on behind live (G11) |
| ⏪ / ⏩ | none | a tap: back 10 s or forward 30 s; held: scrubs, and letting go jumps to the marker (P1). Over the picture only: under a sheet or the mini guide they do nothing |
| Back | the card that opened it (below) | closes what's open over the picture, the control row too; else back to where it was opened (the Live row, the full guide, a program's details or the Back-to card), saving nothing |
| Home | the dashboard top, Play focused (U10) | pauses the player and saves its spot (P3); the dashboard top, Play focused (U10) |

- **Player overlays** (2.7):
  - the mini guide has nothing focusable either: OK on the channel playing just closes
    it, Info closes it and shows the banner, Menu opens the options, and Back or 6 s
    without a key closes it;
  - the options sheet, its Audio and Subtitles lists and the details take the D-pad by
    Compose focus, and nothing under them surfs. Each closes after 2 minutes without a
    key (G10);
  - the rest of the held OK that opened the options (its repeats and release) is
    swallowed, so it can't pick a row. Play/pause works under every overlay.
- **Program details:** the first button takes focus, and Left/Right move along them.
  With no button (filler, or an off-air program that isn't on), the screen holds focus
  itself, so Back and screen care still work.
- **Settings › Plex Guide:** OK on a channel turns it on or off. Hold OK or Menu picks it
  up: Up and Down move it, and OK, Back or Menu put it down. While it's carried, the row
  keeps focus and the D-pad.
- **Back, step by step** (`HomeActivity`, `HomeFocus.LIVE`, `LiveRowFocus`):
  - Guide card › full guide › Back: the Guide card.
  - Live card › hold OK › Full guide: the guide opens on that channel. Back lands on that
    card, scrolled into its row if need be, or on the Guide card if the channel has left
    the row meanwhile (G20).
  - Full guide › details › Back: the guide, on that program. Back again: the card that
    opened the guide.
  - Edit on your phone › Back: Settings › Plex Guide.
  - Back-to card › Resume › Back (P4, `backToCardFocus`): the Guide card, back to its
    lineup since the player took the spot; on a kid's home, the Live card of that channel,
    where the Back-to card was. Home from the player lands on Play as always (U10).

## 4. Components

### Top bar [REQ, U9, U15]
Left to right:
- clock (with AM/PM in titleSmall, dim);
- greeting ("Good morning/afternoon/evening, {name}");
- context chip (dot + mode name): Weeknight evening, Weekend evening, Weekend morning,
  Late night or Daytime, on the schedule in Settings › Time & modes (M1, M2);
- weather (icon + temperature; tapping does nothing);
- Plex status: dot + label, `Plex` / `Plex offline` / `Plex not connected`;
  - **Updating** (10/29/2026): while a Plex sync runs, the status is followed by a 12 dp
    spinner in the accent (a quarter arc on a faint ring, a turn a second) and "Updating"
    in the status's dim labelLarge. It shows once the sync has run 0.5 s and stays 1 s at
    least, so a quick run never blinks (`home/Updating.kt`). Once the sync is done, it's
    gone. It slides in and out over 200 ms, so the chips to its left move rather than
    jump. The run covers the work after the sync too (picks, the kids, the guide), since
    the cards change until then. Not while Plex isn't connected;
- **search** (Phase 15, Q5): a 36 dp circle with the magnifier, just before the gear; it
  opens Search, and Back from Search lands on it;
- **gear** (36 dp circle, focusable) [U9];
- profile avatar (36 dp circle, initial on the profile colour, focusable): whoever is on,
  and OK opens "Who's watching?" (K1). Back from there lands on it.

On weekend mornings the adult top bar also shows the kids' avatars small (28 dp), before
the gear, for a one-press switch [K11]. A kid's top bar greets them: "Hi Maya!".

**Stock launcher came back:** a red chip "Stock launcher is back" replaces the
context chip and opens Settings › Diagnostics [PROP]. The grid doesn't move.

**Setup isn't finished** (setup without a computer): for grown-ups, a chip "Finish
setup · 2 left" (gear icon, Surface2, the accent when focused) replaces the context chip
and opens Settings › Setup.
- **What it counts:** Home button, Continue in your apps, Your pictures, and Apps row by
  habit. Plex isn't counted, since the first card asks for it.
- **"Home button is off":** when Android has turned HomeWatch off, the chip says this, in
  the caution colours.
- **When it goes:** once every step is done, or after Settings › Setup › Show in the top bar
  is turned off on this TV.
- **The stock launcher's chip wins** over both.

### Tonight's Pick hero [REQ, U1, U12, U14, U15]
- **Art:** full-bleed backdrop, centre-cropped. Scrim: horizontal
  `#080B0F` at 92% → 70% at 40% → 10% at 80%, plus a bottom scrim at 60% → 0% over
  the lower half.
- **Text column** (356 dp wide, 20 dp from the left):
  - eyebrow;
  - title (bold), or its Plex logo (Phase 12, below);
  - meta (dim);
  - reason line (spark icon in the accent + titleMedium). With AI blurbs on, the pick's
    blurb takes its place while the pick still has the reason the blurb was written for
    (M9); Why this pick? always starts with the plain reason;
  - flexible space;
  - an optional note line (bodySmall, dim) for the Undo or offline message;
  - buttons: **▶ Play** (or "Play S2E9"), **Trailer**, **Not tonight**.
- **Title logo (Phase 12, L1-L3):** a title with a Plex logo (its `clearLogo`; an episode
  uses its show's) shows the logo in the title's place, the meta and reason still text:
  - **The box:** the text column's width and up to 60 dp tall; the logo is fitted inside,
    left-aligned, centred in the box's height (2 dp clear above and below) and never
    stretched. Its content description is the title.
  - **It shares the column with the reason [REQ]:** the box is what's left after the
    eyebrow, meta and the lines the reason needs, up to 60 dp; when that's under the
    title's line, the reason keeps one line (ellipsized); when even that won't fit (115%
    with the Undo or offline note), the title shows as before. At 100%, 50 dp with a
    two-line reason and 60 with a one-line reason; at 115%, about 45 with one line.
  - **Loading:** the box is reserved at once, so nothing under it moves; the title waits in
    it until the logo loads, then crossfades (250 ms) to it.
  - **The title stays** when the title has no logo, the logo fails to load, or it's too
    dark for its card (L2): the average relative luminance of its visible pixels (alpha at
    least 50%) must give WCAG's 3:1 for graphics against the card's own background,
    `(L logo + 0.05) / (L background + 0.05) ≥ 3`. The hero's background is its scrim,
    `#080B0F` (a kid's too), so a logo needs 0.11: a deep red (`#E50914`, 0.17) shows; navy
    and black don't.
  - **Art:** a PNG from the photo transcoder (`format=png`, `minSize=0`: fitted within the
    width and height, its transparency kept; **V**, official spec's `imageTranscode`) at the
    box's pixel size, never decoded larger. Late night dims it as it dims the art.
- **Not tonight** [U12]: the next pick crossfades in (250 ms) and the button becomes
  **Undo** for 8 s, with "Skipping {title} for 2 weeks" in the note line. Focus stays
  on the button.
  - **What it does (S1):** the title (an episode's show) is skipped for 2 weeks on the
    hero and the lens card, and counts a little against its genres and collection. A
    second time within 90 days skips it for 6 months. Undo takes it back.
  - **The hero remembers its pick by id,** so when the engine re-ranks it stays put.
- **The picks (S5):** six of them.
  - **First, a "finish it" episode** when a show you've watched this month is within 3
    episodes of the end of the season you're on (4 on weeknight evenings, M3): "You're 2
    episodes from the end of Severance's season 2", **Play S2E9**. The show's title and art
    stand for the episode.
  - **Then films you haven't seen,** with one series you haven't started third.
  - **The context mode reorders them** when the card is drawn (M3, §5): long films move
    down on weeknight evenings (over 2h 15m) and late at night (over 1h 45m), films come
    first at the weekend, family ratings first on weekend mornings. It never drops a pick.
- **Reasons** name the most specific evidence:
  - "Next in the Dune collection";
  - "Because you watched Denis Villeneuve's Sicario";
  - "Because you liked Heat";
  - "Starring …, from …";
  - "Because you finished three crime films this month";
  - fallbacks: "Added to Plex 3 days ago", "Rated 8.6 · never played".
- **Until the engine's first run,** Phase 2's rules (the newest unwatched films) fill it.
- **Offline** [U15]: note line reads "Saved pick · Plex is offline, updated 2 h ago".
- **First run** [U14]:
  - eyebrow "Set up", "Connect your Plex account", "go to **plex.tv/link** and enter:";
  - the 4-character code in 44 sp bold accent with 10 dp letter spacing;
  - "Waiting for Plex…";
  - a **Get a new code** button.

### Lens card [REQ, U1, U17-U20]
Was Throwback. One title at a time, seen through a lens.

- **Background:** the lens's gradient, plus the title's art over the right 55% and
  a left-to-right scrim in the lens colour. Throwback and Classic art gets the
  sepia matrix (0.55) at 80% brightness; the others keep their colour.
- **Content:** eyebrow (the lens name; Seasonal shows the season), line (bodySmall),
  then title (titleLarge) with meta. The line is the reason [REQ].
- **Title logo (Phase 12):** as the hero's, in the title's place with the year and runtime
  beside it: up to 30 dp tall and no taller than the card leaves (28 at 100%, 30 at 115%,
  where the line gives way), as wide as the logo, up to the card. The lens's sepia is for
  its art: the logo keeps its colours (late night dims it). A failed logo or none keeps the
  title, and so does one under 3:1 against the lightest stop of that lens's own gradient. A
  logo needs from 0.16 on More like this (`#1F2832`) and 0.17 on Finish the series
  (`#2D2449`) to 0.22 on For the family (`#1E3F3A`), so a deep red (`#E50914`, 0.17) shows
  on the hero, only just on those two, and keeps the title on the other lenses.
- **Buttons (30 dp):** **Watch again** for titles you've seen, **Watch** otherwise;
  **Next** shows the lens's next title, then runs on into the next lens.
- **Back (Phase 14, L3):** a 22 dp circle with Material Symbols Rounded `arrow_back` at the
  top left, the eyebrow beside it, in the buttons' lens colours. The top padding is 6 dp, so
  the eyebrow's row (22) takes the 12 + 16 it had and nothing below moves. Up from Watch or
  Next reaches it; coming into the card from anywhere else lands on Watch (the card's
  `focusRestorer` falls back to Watch). It goes back through the last 30 titles the card
  showed (Next, rotating, Another lens, More like this), skipping one that has left, then
  through the rotation's titles before. A kid's card has it too (24 dp, top padding 8).
- **Moving on by itself (Phase 14, L1, L2):** Settings › Lenses › Rotate on its own, on by
  default every 30 s (1 min, 45, 30, 20 or 10 s). It's Next, so More like this runs through
  its titles and back. It waits while focus is on the card, while a sheet, Now showing or
  the art picker is over the home, while the card is less than half on screen, and while
  the home isn't resumed (an app, Settings, the screensaver); each starts the wait over. A
  new title's art and words crossfade together, 400 ms (`LensClock`, `LensMotion`).
- **Shuffle (Phase 14, L4):** on by default. Each pass is a new mix, seeded by the day and
  the pass: every title once, the lead lens's first title first, each lens's titles in its
  own order, and never one lens twice in a row while another has a title left
  (`LensRotation.mix`). Next and Back walk across passes. While shuffling, Another lens
  leaves your card's quick actions (Next already changes lens); a kid's card keeps it, as
  it has no Next.
- **Staying fresh (Phase 14, L5):** a title on the card for 3 s with the home resumed counts
  as a day on the card, once a day per profile (`lens_shown` in `suggestions.db` v3; More
  like this and sample content don't count). On the engine's run (a new day, a sync,
  feedback), a title on the card on 3 days before today since its last play rests from
  every lens for 30 days (`FeedbackKind.LENS_RESTED`, `LensFreshness`), and the lens's next
  candidate takes its place. A lens with only resting titles shows them, the longest-rested
  first. Anything played or started in the last 14 days, or in Up Next, is off every lens.
- **Which lens leads** [PROP], the first enabled lens with a title from (the times are
  the context modes', so they move with Settings › Time & modes):

  | When | Lens |
  |---|---|
  | Weekend morning (Sat-Sun 7:00-12:00) | For the family |
  | Weeknight evening (Sun-Thu 18:00-22:30) | Throwback |
  | Weekend evening (Fri-Sat 18:00-22:30) | Seasonal in season, else Action & adventure |
  | Late night (22:30-5:00) | Comfort rewatch |
  | Any other time | Classic |

  A lens picked by hand (quick actions) stays until the time moves to another row.
- **Never repeats Tonight's Pick** [U20], including after Not tonight.
- **Lenses**, in Settings › Lenses order (rules decided in Phase 3, S7-S9):

  | Lens | Titles | Tint (from → to, eyebrow) |
  |---|---|---|
  | Throwback [REQ] | Finished a year ago today (then that week); titles you've watched released this week in a round year; rated 4+ stars or finished twice and not seen in 3+ years | `#3A2A1D` → `#2B1F17`, `#E7B98A` |
  | Classic [U18] | Released 25+ years ago (rolling), rated 7+, never watched or not in 5+ years | `#3D3219` → `#2A2313`, `#E5C77A` |
  | For the family [S7] | Rated G to TV-PG; over by 8:30 PM if started now (the shelved bedtime, K6, would replace it); Family and Animation first | `#1E3F3A` → `#142A27`, `#8FD3C3` |
  | Action & adventure [S7] | Action or Adventure, not seen in 2 years, by your taste | `#45201F` → `#2E1516`, `#F2A08A` |
  | Hidden gem [S7] | Never played, in the library 60+ days, rated in your library's top 15% | `#1C2D49` → `#132036`, `#9EC1F2` |
  | Finish the series [S9] | The next film by release in a collection you've started (collections of 15 films or fewer: a longer one is a list, not a series); shows you stopped, not in Up Next | `#2D2449` → `#1E1832`, `#C2B1F0` |
  | Seasonal [S8] | Halloween (15-31 Oct) and holiday (December) titles: your collections and labels named for them first, then keywords in the title or summary; off out of season | `#3F2410` → `#2A180B`, `#FFA45C` |
  | Comfort rewatch [S7] | Films finished 2+ times, and an episode you've seen of your most-watched shows; not in the last 14 days, 2 h or less | `#382337` → `#261825`, `#E3AFD9` |
  | More like this [S10] | Not a lens: titles like Tonight's Pick (shared collection, director, cast, then genres and decade), from the hero's quick actions. "MORE LIKE ARRIVAL" until the time picks another lead, or Next passes the last | `surface2` → `surface`, the accent |

  Buttons: unfocused 12% of the eyebrow colour; focused, the eyebrow colour with
  the gradient's dark end as ink.

### Quick Watch [REQ, U1]
- Eyebrow "Quick Watch · fits before {cutoff}".
- Two rows, 28 dp high. Each row: a 36×20 dp thumbnail, the title (titleSmall,
  ellipsized) and meta on the right.
- **What's in it (S6):**
  - first, resumes and next episodes that fit;
  - then short titles you haven't seen, 45 minutes or less, by your taste;
  - never the hero's show or what Up Next shows.
- **The cutoff** is Settings › Time & modes, per night (M4): weeknights default 10:30 PM,
  Friday and Saturday nights 11:30 PM (a night runs until 5 AM, so 1 AM Saturday is still
  Friday's). From half an hour before it, the card shows what fits in the next 45 minutes.
- OK plays.

### Up Next [REQ]
- Three 16:9 stills (about 155×87 dp) with a 4 dp progress bar in the accent.
- Caption: title (titleSmall), then "S4E2 · 41m left" (bodySmall, dim).
- **Order (S3):** likelihood to continue:
  - recency, momentum (episodes finished in the last two weeks), habit (this hour, this
    kind of day) and how far in;
  - untouched for 30 days sinks to the end;
  - it leaves out the hero's show (S5);
  - Plex only (S4).
- **Finished leaves it** (the Up Next credits fix, 10/27/2026): a title Plex still lists
  past its first credits marker, or past Plex's default played point (90%) without one,
  counts as finished. An episode makes way for its show's next one; a film or a finale
  leaves. Coming back to the home syncs Plex once the cache is a minute old, in the
  background, so what you just finished is gone without waiting for the 30-minute sync.
- OK resumes.
- Kids: two stills (about 240×135 dp), with time left as a chip on the still.

### Rows below the dashboard [L5, L10, G20]
`ui/home/rows/RowAnatomy.kt` (`RowMetrics`), `ContinueInAppsRow.kt`
(`OtherAppsRowFrame`). Live, Continue in your apps and Apps share one anatomy:
- 16 dp above, the label, 8 dp, then 160×90 dp cards 16 dp apart inside the 48 dp side
  margins. These are sizes on the 960 dp canvas, scaled by the screen's width, so the
  rows line up with the dashboard at 90, 100 and 115% while text grows (U4). Before 2.7,
  Live and Continue in your apps used fixed dp.
- In Live and Continue in your apps, focus asks the home to show the whole row, from its
  label down to the captions, so the row lands in the same place every time. The ask
  starts at the label, never above it: for the first row below the dashboard that space
  is the dashboard's, and asking for it scrolled the home back to the top (G20).

### Apps row [REQ, L5-L7] (was the App Launchpad, U7)
- A row of 160×90 dp app banners below the dashboard, hidden apps left out, then an
  **All apps** card. **Order (M5):**
  - what you open around this time: opens within an hour either side of now, on this
    kind of day (weekday or weekend), over 8 weeks, recent weeks counting more;
  - then apps opened from Action Center the most, then A–Z.
- The opens come from Android's usage stats, so they count however an app was opened (a
  handoff to Plezy, a trailer in YouTube). The launcher keeps its own counts, since
  Android keeps only a few days. Action Center and other launchers don't count.
- Without usage access, the launches from Action Center order it, as before; Settings ›
  Channels & apps says how to turn it on.
- **Custom banners** (L7) from `/sdcard/Pictures/Icons`:
  - matched by a normalised name (lower case, letters and digits only); an exact
    match wins, and one typo is forgiven for names of 6+ characters;
  - the main folder wins over `Flat icons`;
  - otherwise the app's own banner (`PackageManager.getApplicationBanner`), otherwise
    its name on a tinted tile.
- **Choose art** (quick action): a full-screen grid of Automatic, the app's own
  banner, and every file in the Icons folder. The pick is saved per app.
- Kids: no Apps row. My apps on their home instead (§4, Kids' home).

### Plex Guide card [L10 spot, Q3, G7]
`ui/guide/GuideCard.kt`, in Row B's right-hand spot. One focusable card with the card
surface (Solid or Glass). It reads the lineup from the cache (`guide/Guide.kt`:
`GuideCardState`) and follows the home's minute tick.
- **No channels** (`ui/guide/NoChannelsYet.kt`, G20): eyebrow "PLEX GUIDE", "Your
  library as TV channels", then one line for what they wait on, over a faint sketch of a
  guide with an accent now line. The full guide says the same as "No channels yet: …".
  The five wordings:
  - Plex not connected: "They appear here once Plex is connected";
  - Plex linked, the library hasn't synced: "They appear here after your library syncs";
  - **Building** (synced, and the lineup is being made: the first time, after a remake,
    or for a different server, G19): "They're being made from your library now";
  - **NothingToFill** (synced, but nothing fills a day of any channel): "Not enough in
    your library to fill a channel yet";
  - **NoneOn** (every channel turned off, or deleted by hand): "They're all off in
    Settings › Plex Guide".
- **Ready:**
  - eyebrow "PLEX GUIDE · 18 CHANNELS";
  - "Coming up at 9:30" (titleSmall): the first time two channels start a program;
  - those two programs at the card's foot, each a 16:9 thumbnail (up to 34 dp high),
    the title and the channel's name.
- No two programs start together: the soonest two, each with its own time, under
  "Coming up next". Nothing ahead: "Nothing scheduled yet". Filler and off-air
  programs never show here. What's on now is the Live row's job (G7).
- **Favorites first** (Phase 13, C5; `ComingUp.of(…, favorites)`):
  - within the next hour, the soonest time a favorite starts something leads, with
    favorites first at that time, in your order;
  - a favorite alone at its time is joined by the soonest other program, under "Coming up
    next" with each one's time;
  - with no favorite starting within the hour, the rule above;
  - a star goes before a favorite's channel name.
- **OK** opens the full guide in every state but the two Back-to ones. Holding OK has no
  sheet there.
- **Back to {channel}** (P4, `ui/guide/BackToCard.kt`): while the guide player's saved
  spot holds (Start over and pause, below), the card shows it instead of its lineup:
  - eyebrow "BACK TO SCI-FI" in the accent;
  - the program's art at its own size (16:9, the card's middle row high), with the paused
    place on a 4 dp accent bar along its foot;
  - beside it the title (titleSmall, up to two lines) and episode · rating (bodySmall, dim);
  - at the foot "Paused 1:12:30 in · 9 min ago" (bodySmall, dim), on the minute tick;
    a spot saved on another TV (Phase 8) reads "Paused in the living room · 9 min ago"
    ("just now" under a minute, "1 hr 5 min ago" past the hour);
  - **OK** resumes it: the player on that channel, paused on that frame;
  - **hold OK or Menu**: Resume, Back to live (the channel live, the spot forgotten) and
    Forget this (the spot forgotten; the card goes back to its lineup, focus still on it).
  - Home's focus is unchanged: Play (U10). It's one surface whatever it shows, so focus
    stays on it when it changes.
- **Back to YouTube TV** (`ui/guide/BackToTvCard.kt`, `lastapp/`):
  - **When:**
    - YouTube TV is the last app you watched. That's the last app with a TV launcher icon
      to come to the front, from Android's usage events over the last day. These don't count:
      Action Center's home, Home apps, Settings, Google search, and system screens (the
      keyboard, choosers, Recents, the screensaver).
    - The guide player counts, as watching something else.
    - You left YouTube TV under 4 hours ago: another screen came up, or the screen went off.
    - You haven't forgotten that visit.
    - When the guide player's spot is newer, Back to {channel} shows instead.
    - Without usage access there's no card.
    - Never on a kid's home: a kid with YouTube TV among their apps has it in My apps.
  - **What it shows:**
    - eyebrow "BACK TO YOUTUBE TV" in the accent;
    - the channel's Live-row banner at 16:9 (the card's middle row high), or YouTube TV's
      own banner;
    - beside it "Started on NBC" (titleSmall), when the visit began on a YouTube TV channel
      opened here (the Live row, the NFL, or this card; YouTube TV came up within 2 minutes),
      otherwise "Live TV";
    - under that "Hold OK for channels" (bodySmall, dim), or "Hold OK for more" with no
      recent channels;
    - at the foot "Left 9 min ago" (bodySmall, dim), on the minute tick.
  - It never says YouTube TV is still on that channel: a change made inside YouTube TV isn't
    seen.
  - **OK** brings YouTube TV back to the front, as its app icon does (its launch intent, a
    new task).
  - **Hold OK or Menu:** Resume, "Watch NBC" for each of the 3 most recent YouTube TV
    channels (the visit's own first), and Forget this. Forget this puts the card back to
    what it showed, with focus still on it.
- **Late night:** the thumbnails dim with the rest of the art (§2).

### Live row [L8, G7]
`ui/home/rows/LiveRow.kt` (was Channels). The label is LIVE in every state. Guide
channels come first, then YouTube TV's networks. With neither, the row isn't drawn.
- **A guide channel** (`LiveChannelCard.kt`), a 160×90 dp card:
  - the program's art at the card's size;
  - the channel's number and name on a dark pill, top left;
  - a 4 dp accent progress bar along the bottom while a program is on;
  - under the card, the title (titleSmall) and "41m left · S3 E1 · Thaw" (bodySmall,
    dim). The captions hide while the row peeks.
- **Filler on** (a trailer): the next program, "Up next at 9:05".
- **Off air:** "Off air" and "Back at 2:30", with the art under a 60% scrim.
- **OK** tunes in. Hold OK or Menu for Tune in, Full guide, **Add to favorites** or
  **Remove from favorites**, **Move** (on a favorite, when another favorite is in the row),
  and "Why it's off air" when it is. **Full guide** opens the guide on this channel, and
  Back from it lands on this card (G20).
- **Favorites first** (Phase 13, C3; `favorites/ChannelFavorites.kt`, `FavoriteOrder.kt`):
  your favorites in your order, then the other channels by number, then YouTube TV's
  networks. A 12 dp accent star sits before a favorite's number on its pill.
- **Move:**
  - the card keeps focus, the D-pad and its lift, and its caption's second line reads
    "Left or right, then OK" in the accent;
  - Left and Right swap it with its neighbour among the favorites, and stop at the ends;
  - the row keeps the carried card and the one before it in view;
  - OK, Back or Menu put it down and save, and so does focus leaving (Home).
- **Whose favorites** (C1, proposal A): the profile's own. Paired, the station keeps them
  by `owner` or `plex:<Plex Home user id>`, and this TV keeps a copy it draws from at once.
  A kid without a Plex user keeps them on this TV. Off channels stay favorites, and a kid's
  favorite that stops being kid-safe stays, hidden. A deleted channel drops out at the next
  save.
- **Toasts:** "Sci-Fi is a favorite", "Sci-Fi is no longer a favorite". While the station
  can't be reached, a change is refused with a caution toast (a warning icon on `surface3`,
  the caution colour): "Can't reach the guide station." Nothing changes.
- Channels turned off in Settings › Plex Guide leave the row.
- **A kid's Back-to card** (P4, `rows/BackToRowCard.kt`): on a kid's home, which has no
  Guide card, the saved spot is the row's first card, only on a channel that's kid-safe for
  them (K8). A Live card's anatomy: the program's art, a two-line pill ("Back to" in the
  accent with a play sign, then the channel's name), the paused place on the bar, and the
  title and "Paused 6:30 in · 4 min ago" under it. OK resumes; hold OK or Menu for the
  Guide card's Resume, Back to live and Forget this. Forget this takes the card away and
  focus moves on to the next card. Yours stays on the Guide card, not in the row.
- **YouTube TV's networks** follow, one button per network from YouTube TV's own
  home-screen row (the Shield has 10). An optional `channels.json` in the app's own
  folder replaces that list, or adds to it. A banner from the Icons folder when the
  network's name matches (ESPN2 gets ESPN.png), otherwise the name on a tinted tile.
  OK tunes YouTube TV through the row's own link. A `tv.youtube.com` link names YouTube
  TV's package, so it never opens Android's chooser.
- **Recent YouTube TV channels**: the last 3 YouTube TV channels you opened
  here (this row, the NFL, Back to YouTube TV), newest first, come right after your
  favorites, or first without any. They're matched by name, kept on this TV only (the
  newest 5, in `youtube_tv`), and the rest of the row is unchanged.
- The row follows the home's minute tick. Late night dims the art (§2).

### Full guide [Q3, G5]
`ui/guide/GuideScreen.kt`, `GuideGrid.kt`, `GuideGridFocus.kt`, `GuideInfo.kt`,
`GuideTime.kt`, `GuideScreenCare.kt`. Full screen, at now: on the first channel from the
Guide card, on that card's channel from a Live card's Full guide (G20). It reads only the
cache, so it opens at once, offline too.
- **Info panel** (about 152 dp high) for the focused program: its art (16:9), eyebrow
  "1 · MOVIES" with the clock, the title (titleLarge, bold), episode · times · length ·
  rating, a status line, and Plex's summary (up to three whole lines, fewer at 115%).
  - Status: on now ("On now · 2h left", "OK to watch"), later ("Starts in 2h 19m", "OK
    for details"), "Ended at 8:30", or "Off air · {reason}" in the caution colour. The
    faint hint says what OK does; "OK for details" replaces 2.6's "starts at" notice.
  - Filler says what it is and what's next, with the next program's art.
- **Quick actions** (2.7, hold OK or Menu on any program, `programSheet`): the home's
  side sheet, titled with the program. Watch and Start over while it's on (Start over
  needs a file), Details, and Open in Plezy (Open in Plex by Settings › Plex › Play in,
  Phase 11) for a program with a file (not filler or off air), as `ProgramActions` sets.
  Nothing's playing, so it's a plain open: nothing is written to Plex. The sheet sits under screen care's dim; Back closes it
  and focus returns to the program.
- **Favorites first** (Phase 13, C4): your favorites in your order, a star before each
  one's name, and a 2 dp accent line at 60% across the grid in the gap under the last; then
  the rest by number. The program sheet's fifth row is "Add Sci-Fi to favorites" or "Remove
  Sci-Fi from favorites".
- **Grid:** six channels at a time (number, logo or monogram, name), a 90-minute window
  across. A time ruler over it: the day ("Today", "Tomorrow") and a mark each half
  hour. A 2 dp red now line (`bad`) with a dot on the ruler. Only the cells in view are
  composed (a custom `Layout`, not lazy lists).
- **Cells:** as wide as their minutes in the window. On now `surface2`, later
  `surface`, past fainter. Filler is an outline ("Up next", "Trailer: …"); off-air
  programs say "Off air". "‹" or "›" marks a program the window cuts off. Focused:
  `surface3` and the 2.5 dp accent ring, no lift.
- **Focus by time:** Up and Down keep a time, so a long film on the way doesn't drag
  the column sideways (§3). Past the edge, Right pages so the next program starts at
  the left; Left so the previous one ends at the right. The range runs from 2 hours
  before now to the half hour after now + 24 hours.
- **Screen care** (G10): after Now showing's idle minutes it dims (a 60% scrim) and
  drifts a few dp each minute. The first key only wakes it.
- **No channels:** eyebrow "PLEX GUIDE" and "No channels yet: …", in the Guide card's
  wording for what they wait on (`NoChannelsYet`).

### Program details [2.7]
`ui/guide/ProgramDetailsScreen.kt`. A full screen inside the home (`Screen.DETAILS`),
opened from the full guide by OK on a later program, or Details in a program's quick
actions. It reads only the cache. The player shows the same `ProgramDetails` over the
picture (Player options and tracks, below).
- **Layout,** inside the safe area:
  - left: eyebrow "1 · MOVIES", the title (headlineMedium, bold, up to two lines), the
    episode (titleMedium), times · length · rating (bodyLarge, dim), and the status line
    (on now with its bar and time left, "Starts in 2h 19m", "Ended at 8:30", or "Off air
    · {reason}" in the caution colour);
  - right: the art at its own size, 16:9, 42% of the width (filler shows the next
    program's);
  - below: Plex's whole summary, in bodyLarge when it fits, else bodyMedium; only a
    summary too long even then ends in an ellipsis;
  - at the foot: the buttons, the first focused, and the clock on the right.
- **Buttons** (`ProgramActions`, shared with the guide's quick actions):
  - **Watch** while it's on;
  - **Start over** while it's on and has a file (G11);
  - **Open in Plezy** (or **Open in Plex**, by Settings › Plex › Play in) for a program
    with a file, not filler or off air (D11). In the player it's **Watch in Plezy** (or
    Plex), which carries on where the picture is (Watch in, below);
  - a later program shows "Starts at 9:30" where Watch would be: a 36 dp outline with
    an accent info icon, not a button.
- **Gone:** a program that has left the lineup (its channel turned off, the lineup
  remade) shows "This program is no longer in the guide". Back still works.
- **Back** returns to the full guide, on this program.
- **Screen care** (G10): as the full guide, after Now showing's idle minutes it dims (a
  60% scrim) and drifts a few dp each minute. The first key only wakes it.

### Guide player [G5, G6, G10, Phase 11]
`player/GuidePlayerActivity.kt`, `player/GuideTuner.kt`, `player/GuideVideo.kt`,
`ui/player/GuidePlayerScreen.kt`, `ui/player/PlayerText.kt`. Its own activity in the
home's task (`singleTop`), Media3 on a `SurfaceView` sized to the file's shape, black
around it. Guide channels only: library titles still hand off to Plezy or Plex (D11,
Settings › Plex › Play in).
- **Tune in** joins the program on now at the live point (from the top within 2 s of
  its start). "Tuning in…" shows on black until frames move.
- **Banner** on tune-in, every channel change, OK and Info, for 5 s: the channel number
  (displaySmall, accent) and name; "ON NOW", the title, episode · rating, the times, a
  progress bar and time left; the clock, "NEXT · 9:30 PM" and the next title. Behind
  live it says so (Start over and pause, below). An overlay hides it.
- **Control row** (P2, `ui/player/PlayerControls.kt`): OK shows the banner with a row of
  the home's 36 dp pill buttons under its bar: Back 10, Pause (Play while paused) and Forward 30 (Phase 10: Material Symbols `replay_10` and `forward_30`; when four pills don't fit, at 115%, they read "10" and "30"),
  then Back to live while behind live. Play/Pause takes focus each time it comes up.
  - Left/Right move along it and OK presses; nothing else is focusable over the picture.
  - The banner stays while you use it and hides 5 s after the last press; paused, it stays.
  - Back to live hands focus to Play/Pause before it goes.
  - Back takes the row away (the banner too unless paused). Up/Down still surf, and the
    new channel's banner has no row. The options, the mini guide and the details close it.
  - Only over a program with a file (playing, paused, or opening after Back to live). On
    filler, an off-air card, a gap or the Up next card, OK shows the banner alone.
  - The scrim reaches 300 dp up with the row (240 without), so the eyebrow above it stays
    on the dark part.
- **Seeking** (P1, `player/Seeking.kt`), in the program on screen:
  - a tap of ⏪ goes back 10 s, a tap of ⏩ forward 30 s; it seeks on the release;
  - holding scrubs: the system repeats the key, and the marker moves ×2 for the first 2 s
    held (30 s of the program a second), ×4 for the next 2 s (a minute a second), then ×8
    (two minutes a second);
  - while held, the eyebrow reads "REWIND ×4 · 2 MIN BEHIND LIVE" (or "FAST FORWARD ×8 ·
    LIVE" once the marker's there), a white 3×14 dp tick on the bar marks where it'll land,
    and "9:00 in" replaces the time left. The picture carries on underneath; the only seek
    is on letting go;
  - back no further than the program's start, forward no further than live. Back from
    live is behind live (below), so the banner's wording follows; forward to live is Back
    to live, the program tuned in where it is now;
  - live, ⏩ only shows the banner. Not on filler, an off-air card, a gap or the Up next
    card, nor before frames move.
- **Surf:** Up/Down (and Channel up/down) change channel and banner at once. The file
  opens once the presses stop for 400 ms.
- **Schedule:** the next slot's file opens 30 s ahead and plays at the boundary.
  Trailers play their own file.
- **Between programs (G21):** an episode starts as the one before it ends. A film starts
  on the first 5-minute mark at least 2 minutes later, and the gap is its own trailer,
  plus another film's from the channel when the gap is 5 minutes or more; with no
  trailer to show, it starts straight away. There are no Up next slots any more.
- **Up next card:** only the short rest of a trailer's slot when the trailer ends early,
  one that's already over when you tune in (its length comes from its details, G20), or
  a file that ends early. The next program's art, title and start, with a countdown
  ("in 0:59").
- **Off-air card**, until the next slot: the slot's own reason ("This title is no
  longer in your library", also when Plex has no file for it at tune-in), "Plex isn't
  linked", "Nothing on right now" or "No channels yet", then what's next. Every error
  ends here, never in a crash. Both cards drift a few dp each minute.
- **The player's errors** (`VideoError.reasonFor`) are sorted by what a viewer can
  follow, in plain words: never "file", "HTTP" or an error code on screen. The Media3
  code goes to the log (`player: ERROR_CODE_…`), for the operator.
  - **Unavailable:** Plex answered but couldn't give the file right now, say a NAS drive
    that's off or restarting (the IO codes: bad HTTP status, not found, no permission
    and the rest). "This movie isn't available right now" ("This episode" for an
    episode), then "It may be on a drive that's off or restarting. Trying again…"
  - **Offline:** Plex can't be reached (connection failed or timed out, or the lookup
    couldn't reach the server). "Can't reach Plex", then "Check that your Plex server is
    on. Trying again…"
  - **Can't play:** a file the Shield can't decode (parsing, decoder, audio, DRM, and
    anything else). "This one won't play on the Shield", then "The channel carries on
    with the next program." Not tried again.
  - **Trying again:** Unavailable and Offline tune the program in again every 30 s,
    where it is by then (as tuning in mid-program does), one try at a time and logged by
    count ("player: channel comedy, retry 3"). The card stays up while a try opens, and
    the picture replaces it once frames move. Retrying stops when the slot ends (a try
    that would come after the next program opens ahead isn't made, so that program tunes
    in as usual), on a channel change, or when the player stops. Filler that fails shows
    the Up next card, and behind live a failure rejoins live, as before.
- **Screen on** only while video plays (G10); the cards and a paused picture let the
  screensaver come on.
- **Back** closes what's open over the picture, else returns to where it was opened, and
  saves nothing. That holds when Android has made the home again behind the player (a
  configuration change the player rides out, such as an HDMI mode switch): the full guide
  and a program's details keep their place, the guide on the program played. **Home** (or anything else that stops the player: Plezy, the screensaver)
  pauses it where it is and saves that spot for the home's Back-to card (Start over and
  pause, below). No playback is reported to Plex: no timeline, no session (G6). Since
  Phase 11, a program watched to its credits is marked watched, and Watch in writes its
  resume point (below). Coil's memory cache is cleared as it opens.
- **Sound** (G15, `player/PlexExo.kt`): passed through where the TV takes it (AC3, E-AC3,
  E-AC3 JOC and TrueHD, on a TV that takes them), else decoded by the Shield. NextLib's
  FFmpeg audio renderer comes after Media3's own as a fallback, and decodes to PCM what
  nothing else can, such as DTS. No FFmpeg video. Release builds ship arm64-v8a only.
- **Still not covered:** audio that none of these can play would still play with no
  sound, and the player doesn't say so (the Playback check does). Whether decoded DTS
  reaches the TV as surround or stereo is still to verify (§9).

### Watch in Plezy or Plex [Phase 11, K0, K3, K4]
`player/GuidePlayerActivity.kt` (`branch`), `player/GuideViewing.kt`, `player/GuidePlexWrites.kt`,
`plex/PlexWatchState.kt` (`:guide-core`). For when a viewer gets hooked while surfing.
- **Where:** the options sheet's first row, "Watch in Plezy" or "Watch in Plex" by
  Settings › Plex › Play in, for a program with a file once the server's known; the
  details' button does the same. Its icon is Material Symbols Rounded `open_in_new`, as
  is the full guide's Open in Plezy (or Plex).
- **Where it starts,** by the frame on screen (`ResumeWrite`):
  - under a minute in: nothing is written; the app starts where Plex had it;
  - before the watched point: Plex's resume point (`GET /:/progress … state=stopped`), and
    the hand-off waits for its answer, 2 s at most;
  - past the watched point (credits, or 90%): watched (`PUT /:/scrobble`), so the title
    opens from the start. It isn't counted again.
  - The details of the next program (filler on screen) write nothing.
- **If Plex doesn't take it** (offline, refused, or slower than 2 s): the hand-off goes
  ahead, with a system toast that shows over the app: "Plezy starts where Plex last had
  it" ("Plex starts …").
- **Whose account** (K3, `profiles/PlexViewers.kt`): the profile's Plex user. You are the
  owner's token; a kid linked to a Plex Home user is their own token; a kid without one
  writes nothing. Plezy's link names the user (`profile=`): a kid's UUID (or, linked
  before Phase 11, their Plex name), and yours once a kid is linked. Plezy switches to
  them first, asking a protected user's PIN, and drops a link whose profile it can't find.
- **The apps:** Plezy plays the title from Plex's `viewOffset`. The Plex app's link plays
  it too, from its `viewOffset`, after Plex's own "Who's watching?" when Plex asks
  (checked on the Shield, 10/31/2026); on Plex's very first launch it showed "Review Your
  Favorite Libraries" first.
- The player stays underneath, as before: Back from the app returns to it, paused on its
  frame.

### Guide viewing in Plex [Phase 11, K1, K2; G6 amended]
`player/GuideTuner.kt`, `player/GuideViewing.kt`.
- **What counts:** per program per sitting, the player adds up the time its frames
  actually played. Pausing and skipping past add nothing; watching behind live counts.
- **When:** once playback reaches the watched point (Plex's first credits marker, else 90%)
  with ¾ of the program played, one `PUT /:/scrobble` goes out in the background for the
  profile's Plex user (as above). Once per program per sitting; Home or Plezy at that
  point still counts it.
- **Every channel** (K1), shuffled ones too. Filler, cards and programs without a file
  never count. Nothing else is sent: partial viewing writes no position, so surfing stays
  out of Continue Watching.
- Settings › Plex › Count guide viewing in Plex (on by default) turns it off for this TV.

### Credits card [Phase 11, K5]
`ui/player/GuidePlayerScreen.kt` (`CreditsCardView`), `player/GuideViewing.kt` (`NextEpisodes`).
- **When:** in an episode's credits (from its marker; without one, its last minute), once
  per program, when the cache has the show's next episode (the next in the season, else
  the next season's first; specials left out). None after the last episode, none over an
  open sheet, and on a kid's profile none above their ceiling, by the kid filters' own
  check (an episode without a rating takes its show's; with neither, none).
- **Look:** 340 dp at the top right inside the safe area, clear of the banner: the next
  episode's art (96 dp, 16:9), "NEXT: S2 E5" in the accent, its title, and an accent "OK"
  chip with "Watch in Plezy" (or Plex). It fades in and out over 200 ms.
- **Keys:** nothing in it is focusable; the activity reads them, as for the mini guide.
  - **OK** (a tap) picks it: the episode handed off with nothing written, so the app
    starts it where Plex has it (the start, for one not begun). What played of the
    program on screen counts first.
  - **Holding OK** or Menu still opens the options, which take the card away.
  - **Back** takes it away; the channel carries on. Back again leaves the player.
  - With the control row up, OK is the row's; Up/Down surf and the card goes with the
    channel; Left/Right open the mini guide, which takes it away.
- **Left alone** it goes after 15 s, and the channel carries on.

### Start over and pause [G11]
`player/BehindLive.kt`. The player keeps its place in the program as a position in the
file at a wall-clock time, so it knows how far behind live it is.
- **Start over** (the options, or Start over in the guide's quick actions and details):
  the program on now from its start. Only a program with a file: not filler, off air or
  a gap.
- **Pause:** Play/pause (or the remote's Play and Pause) while video plays. The picture
  holds; Play/pause again carries on, behind live.
- **Seeking** (P1): ⏪ from live, or the control row's Back 10, is behind live too, and so is a
  scrub let go short of live. After a seek the eyebrow says how far behind ("Paused ·" while
  paused), not "Started over".
- **The banner** behind live: the eyebrow, in the accent, reads "Started over · 12 min
  behind live", "Paused · 12 min behind live", or "12 min behind live" after a pause or a
  seek. The bar shows the picture's place in the accent over the schedule's, fainter, and
  time left counts from the picture. While paused the banner stays.
- **While paused** the schedule waits: nothing is opened ahead, and the screen isn't held
  on.
- **Stopped** (P3): anything that stops the player but Back holds the program on screen
  where it is, paused behind live: Home, Plezy, the screensaver, live or behind. Coming
  back to the player (Back from Plezy, the screensaver ending) opens that frame, paused.
  Filler, a card or a gap hold nothing, and tune in afresh.
- **The saved spot** (P3, `player/SavedSpot.kt`): what a stop that isn't Back leaves,
  saved in its own DataStore file (`player_spot`), so it outlives the process: the
  channel, the program's slot (its start) and item, the position in its file, and the wall
  time. One at a time; a newer stop replaces it.
  - **It holds** (`SpotRules`) for 4 hours, while its channel is in the lineup and its
    program is still in that channel's schedule window (about 3 hours back) with its file.
    Any tune-in to another channel forgets it; its own channel live doesn't.
  - **Back** saves nothing, and takes back a spot this player saved when it was stopped
    and came back (Plezy, the screensaver), so Back always leaves none.
  - **Resuming** (the Back-to card, §4 Plex Guide card) opens the player on that channel,
    behind live and paused on that frame: "Paused · 9 min behind live", the banner held,
    and Play carries on. The player takes the spot over: Home saves it again, Back leaves
    none. A program gone from the schedule tunes in live; a file Plex can't give rejoins
    live, with the usual cards.
- **Rejoining live:** when the program ends at this pace, or its file does, the channel
  jumps to what's on now. Back to live (options, the control row, ⏩ to live) does it any
  time; changing channel (Up, Down, the mini guide) leaves behind-live too. A file that
  fails behind live rejoins live rather than showing a card.
- **Known gap:** after a long pause, the banner's Next can name a program that's already
  over (a 2.7 follow-up).

### Mini guide [G12]
`ui/player/MiniGuide.kt`. Left or Right opens a strip over the bottom third of the
picture; the video plays on above it.
- A 240 dp scrim to the screen's edges. Inside the safe area: the picked channel's
  program art (128 dp wide, 16:9), then the "CHANNELS" eyebrow with "OK to watch · Back to
  close" on the right, over five 28 dp rows.
- The rows: the pick in the middle with two channels above and two below, wrapping (the
  whole lineup when it's five or fewer).
- Each row: a play sign on the channel playing, the number (bold), the name, what's on
  now with its time left as the Live row says it (off air in the caution colour), and
  what's next ("9:30 Title"). The pick fills with the accent, as a sheet's row does.
- **Favorites first** (Phase 13, C4), as the full guide orders them, with the star and the
  line; its Up and Down follow that list. Channel up and down over the picture still go by
  number.
- Nothing in it is focusable: the activity moves the pick (§3). It closes after 6 s
  without a key.

### Player options and tracks [G13, G14]
`ui/player/PlayerOptions.kt`, `player/TrackMatch.kt`, `player/GuideVideo.kt`.
- **Options sheet** (hold OK or Menu): the home's quick actions sheet (U6), titled with
  the program, with what applies (§4, Quick actions). A choice closes it. Its first row
  is Watch in Plezy (or Plex) for a program with a file (Phase 11); its last is Add
  {channel} to favorites or Remove (Phase 13). Six rows at most, which fit at 115%: a file
  with both audio and subtitle tracks has one row, **Audio and subtitles** (Phase 13).
- **Audio and Subtitles** (`TrackSheet`): the same 336 dp sheet, the program as eyebrow,
  "Audio", "Subtitles" or "Audio and subtitles" as title, then 44 dp rows that scroll (a
  film can carry a dozen subtitle tracks). With both, the audio tracks come first, then
  the subtitles under their own eyebrow; the audio track on now takes focus.
  - Subtitles list Off first. The track that's on has a tick and takes focus.
  - Rows use Plex's names: `displayTitle`, or `extendedDisplayTitle` where two would
    read the same, else the language.
  - Tracks the Shield can't play are left out. Back returns to the picture.
- **Which tracks play** (G13): the streams Plex marks selected for the file, read from
  `/library/metadata/{id}` once per program (1.5 s at most, else the file plays without
  them). Otherwise the file's default audio and its forced subtitles only: a subtitle
  track the file marks default doesn't count.
- **Matching:** Plex's streams pair with the file's tracks by kind and order, and their
  languages must agree. When the counts differ or a language doesn't, Media3 keeps its
  own choice and the options leave that list out.
- **A change** is for this program only (G14): the next program starts on its own
  defaults, and nothing is saved or sent to Plex.
- **Subtitles** are drawn by media3-ui's `SubtitleView` (through `AndroidView`: Compose
  has none), in the picture's frame, in Android's caption style and size.
- **Sidecar files:** subtitle files Plex keeps beside the title (SRT, SSA/ASS, WebVTT,
  TTML) attach to the file and load only once chosen, through the same data source, so
  the token stays in a header. Closed captions inside the video aren't listed.
- **Details:** `ProgramDetails` over the picture at 94%, the video playing on behind:
  the program on screen, or the next one while filler is on. No Watch; Start over for
  the program on screen; Watch in Plezy (or Plex) carries on where the picture is
  (Phase 11), and Back from the app returns to the player. Back closes it.

### Continue in your apps [L9]
- Other apps' Watch Next entries (Disney+, YouTube, Prime Video, Spotify), Plex and
  Plezy left out because Up Next has them, newest first.
- Each card shows the art, an app badge, the title (or "S2E26 · episode"), and a
  progress bar in the accent colour.
- OK opens the exact title. Settings › Channels & apps turns apps on and off.

### All apps [U8]
- Full screen: title + count, a **Most used** row (5), then **A–Z** (5 columns of
  160×90 dp banners, 16 dp gaps).
- Scrolls to keep focus 60 dp from the edges.
- Quick actions: Open, Move, Hide from launchpad, App info.
- Kids see "My apps": only their allowlist A–Z, with "Only the apps a grown-up picked for
  you are shown here.", no Most used and no quick actions (K9).

### The NFL [Phase 13, F1-F11]
`nfl/`, `ui/nfl/`. From the first preseason game in August to the day after the Super Bowl
(F8); off-season there's nothing on the home. Never on a kid's home (F9). The TV draws only
its copy of `NflState`. Paired, the guide station keeps that copy fresh; unpaired, the TV
fetches it from ESPN itself (F11). Team logos are the station's (`/v1/nfl/logos`) or ESPN's
resizer, cached on the TV at the size drawn, never larger. Times are the house's.

**The game-day card** (F1, F2; `ui/nfl/GameDayCard.kt`):
- **When:** it takes Quick Watch's spot (351×92 dp, below the lens card) from 30 minutes
  before a kickoff until the day's last game ends. Then Quick Watch comes back.
- **Which game:** your team's while it's on; else the closest live game (the smallest
  margin, the later quarter first); else the next kickoff (`NflRules.featured`).
- **Anatomy** (12 dp padding), on the card surface with the away team's colour at 33% on
  the left and the home team's on the right, fading to the surface in the middle:
  - **Eyebrow:** "NFL", then "· YOUR TEAM" for your team's game, then "· ● LIVE · 1:45 3RD"
    (the dot and LIVE in `live` red) or "· 8:15 PM". The network badge is on the right.
  - **The score line**, 36 dp, one grid:
    - the away logo (28 dp) and abbreviation (18 sp, bold);
    - the possession marker (a small amber triangle by the team with the ball);
    - the two scores (28/36, bold, tabular) with an en dash between;
    - the home abbreviation and logo.
    - Before kickoff, the records ("2-5") stand where the scores go.
  - **The foot** (bodySmall, dim): "Scores from 2:41 PM" first when the copy is old, then
    "2nd & Goal at PHI 7 · Red zone · 3 more live" or "Kickoff in 25 min". The card never
    features a final.
  - **At 115%** the eyebrow sheds "· YOUR TEAM" first, then the clock; the badge stays.
- **OK** opens the NFL screen on this game (F3). **Hold OK or Menu:** each way to watch it
  (two at most), All games, Standings, and **Not today**: Quick Watch until tomorrow on this
  TV, with the toast "Quick Watch is back until tomorrow. The NFL row stays." With scores
  hidden, Show score too.

**The NFL row** (F1, F3; `ui/home/rows/NflRow.kt`):
- **Where:** first below the dashboard on a game day from an hour before the first kickoff,
  else after Live.
- **The label:** "NFL · WEEK 8 · 1 LIVE", "NFL · PRESEASON WEEK 2" or "NFL · WILD CARD".
- **Cards** (160×90 dp, the row anatomy):
  - **All games:** a navy card with the football icon, "NFL" and "Week 8 · 1 live";
    caption "All games", "Scores, standings, playoffs".
  - **The games**, in `NflRules.rowOrder`: live games (yours first), the next kickoffs,
    then the week's finals, newest first. Your team's division card goes between the
    kickoffs and the finals.
  - **A game card:**
    - two team lines, each a 22 dp logo, the abbreviation (bold), a star for your team, the
      possession marker, and the score on the right;
    - a status column on the right: LIVE in red (no dot) with the clock and quarter, the day
      and time, or Final;
    - the network badge at the bottom right, and the away colour washing in from the left;
    - a final's loser in `textFaint`;
    - captions "Eagles at Commanders" and "2nd & Goal at PHI 7" (live), "ESPN · Mon 8:15 PM"
      (to come) or "Sun · CBS" (final).
  - **Your division:** your first team's division, four rows of a 14 dp logo, the
    abbreviation and the record; caption "NFC East", "Standings". Left out with no team
    set.
- **OK** on a game opens the NFL screen on it (F3); on All games, the screen at this
  week; on the division, Standings › Division. **Hold OK or Menu** on a game: the ways to
  watch it, Details, both teams, and Show score when hidden.

**The NFL screen** (F4; `ui/nfl/NflScreen.kt`, `Screen.NFL` inside the home, like the full
guide). It opens at once from the copy.
- **Header** (44 dp at the top of the safe area): the football icon, "NFL" (titleLarge,
  bold), "Week 8 · Oct 29 – Nov 2" (dim), then the tabs **Games**, **Standings** and
  **Playoffs** as pill buttons; the open tab is tinted with an accent underline. On Games,
  **‹ Week 7** and **Week 9 ›** (small pills) step through the season, the preseason and
  playoffs included. The clock is on the right. Up from the top row reaches the header,
  landing on the open tab; on Standings, Down from a tab lands on the open view's pill.
- **Games:**
  - **The info panel** (864×128 dp) for the focused game:
    - both teams (52 dp logos, the name 20 sp bold, the record dim), the scores (40 sp
      bold), the status between them, and the situation or the venue under it;
    - on the right (300 dp, a hairline rule before it): "WHERE TO WATCH", the first way
      (16 sp, medium), "Also: …" (bodySmall), and the hint "OK to watch · hold OK for
      more" or "OK for details · hold OK for more".
  - **The games** below, scrolling: `NflRules.slots` headers as eyebrows ("SUNDAY · NOV 1
    · 1:00 · 8 GAMES · LIVE"), and 280×62 dp tiles three across (12 dp apart, 10 dp
    between rows). A tile has two team lines (logo, name, star, record faint, possession,
    score) and, past a hairline, the status and the network badge. Where a name and record
    don't fit, the record goes, then the name drops a size; it's never cut. Live tiles wash
    red from the left. Byes are listed at the end ("On a bye: …"). The list leaves room at
    its foot so any time slot can scroll to the top.
  - **OK** watches a game that's on or starting within 30 minutes (its first way), else
    opens its details. **Hold OK or Menu:** the ways to watch, Details, both teams, Show
    score.
- **Standings:** **Division**, **Conference** and **League** (small pills under the
  header), with a legend on the right ("★ your team · clinch marks (x, y, z) appear as teams
  clinch").
  - **Division:** the eight divisions at once, AFC above NFC, each a 207×190 dp box: the
    name as an eyebrow (AFC in `#EF9A9A`, NFC in `#90CAF9`), then four 36 dp rows (logo,
    name, record, games behind). The leader's row is tinted.
  - **Conference:** AFC and NFC side by side, 22 dp rows: seed, logo and full name, "·
    div" for seeds 1-4, W-L, PCT, DIV, STRK. A dashed accent line after the 7th is the
    playoff line.
  - **League:** all 32 by win percentage (then point difference), in two columns of 16:
    W-L, PCT, PF, PA, DIFF (`good` / `bad`), STRK.
  - OK on a team opens its page.
- **Playoffs:**
  - **In the season:** "If the season ended today · seeds after Week 8", a bracket in seven
    columns (Wild card, Divisional, AFC, Super Bowl, NFC, Divisional, Wild card) with
    hairline connectors. The top seed's box says "First-round bye", the later rounds are
    dashed boxes ("1 BUF hosts the lowest seed left"), and "In the hunt" (seeds 8-10) and
    a line on re-seeding sit at the foot.
  - **In January:** the real games, with scores, and the network under each game. OK on a
    game that's on watches it.
  - **In the preseason:** last season's bracket and final standings, said so in the
    header line (D).
  - OK on a team opens its page.
- **Game details** (a page on the NFL screen's own stack, `NflPage`):
  - the scoreboard (84 dp logos, the city and name, "2-5 · 3rd in NFC East", 56 sp scores,
    the status and situation between them, on the two teams' colours);
  - the score by quarter on the left, with the venue, the networks and the last play;
  - team stats on the right, as paired bars;
  - buttons: each way to watch (two at most; a missing app dimmed), both teams, Standings.
  - A game to come shows its time, network and both records; team stats wait for kickoff.
    With the score hidden, "Team stats are hidden with the score".
- **A team's page** (an `NflPage` too):
  - a header on the team's colour: the 72 dp logo, the division, the full name, then "2-5 ·
    3rd in the NFC East · 15th in the NFC · L5";
  - **★ Your team** / **☆ Make it your team** (F5) and its division's standings;
  - the 18 weeks in two columns of 36 dp rows: the week, the day and date, "vs" or "at"
    with the opponent's logo and name, then "W 24–22" (`good`) or "L 7–27" (`bad`), LIVE
    with the score, or the time and network. The bye week is a faint row.
  - OK on a row opens that game's details, or watches it while it's on.
- **Back** closes a sheet, then returns to where the screen was opened, a details or team
  page to the screen it came from. **Screen care** (G10) as the full guide.

**Where Watch goes** (F6, F7; `nfl/WatchLinks.kt`). Each way is an app, a link and whether
the app is on this TV. Every intent names its app's package: on a Google TV (Android
12) a bare link opens Android's app chooser (checked 11/02/2026).

| Carrier | First | Then |
|---|---|---|
| CBS, FOX, NBC, ABC, ESPN, NFL Network | YouTube TV on that channel: the Live row's link for it, else `channels.json`'s, else the national id (ESPN, ESPN2, NFL Network), else YouTube TV's home | Paramount+ (CBS), Disney+ (ESPN), Peacock (NBC), Fox One (FOX), when installed |
| A Sunday afternoon CBS or FOX game neither of whose teams is one of your area's, with Sunday Ticket on | "Sunday Ticket on YouTube TV": YouTube TV's home, where you pick the game (no link opens one) | The network on YouTube TV |
| Prime Video | Prime Video's home (no feed gives a game's id) | |
| Netflix | Netflix's home, which leads with a live game. Netflix's Tudum page gives a title id only for September's Melbourne game (checked 11/02/2026), so there's no link to a game still to come | |
| Peacock, YouTube, Paramount+, NFL+ | The app | |

An app that isn't installed is a dimmed row, "Peacock isn't on this TV", listed after the
ways that work. Until your area's teams are set, a Sunday afternoon game offers only its
network. A Live-row `intent:` link without a package gets YouTube TV's. While a score is
hidden, a game's sheet lists one way to watch, so Show score fits in five rows.

**Logos** while one is missing: a circle in the team's colour, with the abbreviation inside
from about 38 dp up (12 sp at least); smaller circles are plain, the abbreviation always
beside them.

**Hide scores** (F10, proposal C): today's games say "Score hidden" on the card, the row,
the Games tab, details and team pages. While any of them is hidden, the standings are this
morning's. Hold OK › **Show score** shows a game's until tomorrow.

### Search [Phase 15, Q1-Q7]
Opened by the top bar's magnifier, and by the remote's Search key when it sends one
(`onSearchRequested`, not in safe mode). A full screen like All apps (`ui/search/SearchScreen.kt`).
- **The field:** 44 dp, the magnifier and "Titles, people, genres", focused with the TV
  keyboard up. The keyboard's Search closes it and goes Down to the results once they're for
  these words and laid out (voice typing brings the words and Search together); OK on the
  field opens it again. Picking a last search, or taking a chip off, puts focus on the field
  before the words change: a focused pill the new words take away would otherwise hand focus
  back to the field before its words are laid out, which crashes Compose's text field
 . Back closes the keyboard, then the screen.
- **As you type:** Plex is asked 250 ms after the last key; a newer search cancels an older
  one (`SEARCH_SETTLE_MILLIS`).
- **Chips (Q2, `search/SearchWords.kt`):** decades ("90s", "'90s", "1990s"; the 00s to 20s
  are this century's), "kids" or "family" (G to PG, TV-Y to TV-PG), "movie(s)" or "film(s)",
  "show(s)", "series" or "tv", and "new" (added in the last 30 days) become pills under the
  field; OK on one takes its word out. The other words go to Plex. With chips, Plex is asked
  as typed too, and a title close to the words leads ("the truman show" stays the film).
  Chips alone list the titles on this TV that pass them, newest first.
- **Your library (Q1, `search/LibrarySearch.kt`):** Plex's `/hubs/search` as the profile on
  now: a kid's own Plex user when they have one, else yours, with their ceiling applied
  either way (unrated hidden). Movies, shows and episodes; collections and tags left out.
  Plex's `reason` shows on the card ("With Dana Example", "Directed by …").
- **Results:** the top match (320 dp wide), then rows of 176 dp cards (16:9, art, title,
  what it is and why): "In your library · Movies", "· Shows" (shows and episodes); "On now"
  (guide channels whose name or program on now holds the words, then YouTube TV's networks
  by name; a kid's only within their ceiling, and YouTube TV only when it's theirs); and,
  for grown-ups (Q4), "Search everywhere with Google: “…”" (`GLOBAL_SEARCH` to
  `com.google.android.katniss`, the words as `SearchManager.QUERY`).
- **On your apps (Q3, `search/StreamingSearch.kt`):** after your library and On now, before
  Google. Settings › Search › Include streaming apps (on by default) turns it off.
  - The words left after the chips go to Plex's catalog (20 titles). A decade, "movies" and
    "shows" narrow it, "kids" by each title's rating; "new" doesn't apply.
  - Titles already in your library are left out (same title and year). The rest get their
    details and "where to watch", held for the day, 6 at a time from the best match down,
    until 6 are on your apps or the 20 run out (with a big library, the first few are
    often on services this TV doesn't have). Your plex.tv token, in a header, for the offers.
  - Only subscription and free offers, on apps installed on this TV that whoever's watching
    may open (a kid's My apps), in the Apps row's order. YouTube is left out; YouTube TV stays.
    Netflix, Prime Video and YouTube TV go by the services the TV's check named; others by the
    app's name ("Disney Plus" is "Disney+").
  - A kid sees only titles rated inside their ceiling (unrated hidden). Nobody sees titles
    rated X or NC-17, or unrated with an Adult or Erotic genre.
  - The card: the catalog's art, resized by Plex's image service (`images.plex.tv/photo`,
    `CatalogArtUrls`: no token, never an original), with a badge naming its apps ("Netflix ·
    Prime Video").
  - OK opens the title in the first app, by its link with the app's package named, when the
    app takes it; else the app's home, with Android's toast "Search for “Heat” in Netflix".
- **OK:** a title plays as anywhere (Play in); a channel tunes in; a network opens YouTube TV.
  Opening something keeps the words among the profile's last five searches, on this TV.
- **Empty field:** the last searches as pills, and what the chips are.
- **Offline or no server:** titles on this TV whose name holds the words, with "Plex can't
  be reached: searching what's on this TV."

### Diagnostics › Search check [Phase 15]
From Diagnostics' buttons. Lines for Plex's search on your server ("the"), the catalog's
search ("heat") and that title's details, "where to watch" for it with your plex.tv token,
and the search providers other apps declare: counts, names and answers, never a token, each
also logged under `ActionCenterSearch`. Below, buttons try Google with "heat" and each offer's
link on this TV.

### Quick actions side sheet [U6]
- 336 dp wide from the right, full height, `#121920`, slides in 40 dp + fades
  (200 ms).
- Eyebrow, title, then up to five rows (six in the player's options) of icon + label, at
  least 44 dp high; a long label ("Remove Documentaries from favorites") takes a second
  line (Phase 13).
- "Back to close" hint at the bottom.
- Holding OK opens a sheet on the press's first repeat. The rest of that hold, and its
  release, never reach the sheet's actions, so letting go doesn't pick the first one
  (`SheetPresses`, 2.7). Only a press made inside the sheet picks one.

| On | Actions |
|---|---|
| Hero | Play, Why this pick? (the reason and its evidence), Start from the beginning, More like this (the lens card shows them, and focus goes there), Not tonight |
| Up Next | Resume, Start this episode over, Go to show in Plezy, Mark as watched, Remove from Up Next (S10: until you play it again; focus goes to Play) |
| Quick Watch | Play, Details, Hide from Quick Watch (S10: for 30 days; focus goes to Play) |
| Lens card | Watch (again), Details, Another lens (not while shuffling, Phase 14; a kid's keeps it), Don't show this (S10: off that lens for 6 months; the card moves on, and Back skips it) |

The Phase 3 actions stay on the launcher: none of them writes to Plex (S10). Start from
the beginning, Details, Start this episode over, Go to show in Plezy and Mark as watched
still say "Not yet".
| App | Open, Hide from launchpad, Choose art, App info |
| Live: guide channel | Tune in, Full guide (on this channel), Add to favorites or Remove from favorites, Move (a favorite, with another in the row; Phase 13), Why it's off air (only when it is) |
| Live: network | Watch, Where this button comes from |
| Continue in your apps | Open, Hide this app from the row |
| Full guide: a program | Watch (while it's on), Start over (while it's on, with a file), Details, Open in Plezy or Open in Plex (a program with a file; Play in), Add {channel} to favorites or Remove (Phase 13) |
| Guide player: options | Watch in Plezy or Watch in Plex (a program with a file, the server known; Phase 11), Start over (a program with a file), Back to live (behind live), Audio, Subtitles, or Audio and subtitles (once matched), Details, Add {channel} to favorites or Remove (Phase 13) |
| Back-to card (P4: the Guide card, or a kid's Live row) | Resume, Back to live (the channel live; the spot forgotten), Forget this (focus stays on the Guide card; on a kid's, moves to the next card) |
| Back to YouTube TV (the Guide card) | Resume (YouTube TV back to the front), Watch {channel} for the 3 most recent YouTube TV channels, Forget this (focus stays on the Guide card) |
| Game-day card (Phase 13) | Watch: each way (two at most; a missing app dimmed), All games, Standings, Not today; Show score while hidden |
| An NFL game (the row, Games, a team page) | Watch: each way (two at most), Details, the away team, the home team; Show score while hidden. Five rows at most |

### Settings [U9, brief §9]
- Opened by the gear, from any profile: the parent PIN that would guard it is shelved (K5).
- **Layout:** a 260 dp section list on the left, the section's content on the right.
- **Sections:** Setup, Look, Wallpaper, Lenses, Profiles, Plex, Plex Guide, NFL
  (Phase 13; not for a kid), Channels & apps, Search (Phase 15), Time & modes, Weather, AI
  blurbs, Diagnostics, About. Rows are 36 dp, and the list scrolls: at 100% AI blurbs,
  Diagnostics and About sit below the fold.
- **Setup** (setup without a computer): a
  checklist you can come back to. A note says every step is optional and nothing here
  leaves the TV. Then one row per step: a dot (Good when done, Caution when Android turned
  it off), the title, its state, a sentence on what it's for, and while it isn't done, one
  small pill:
  - **Home button:** Done (the stock launcher is off), Done with HomeWatch, Off, or Turned
    off by Android. "Turn on HomeWatch" opens TV Settings › Accessibility where the TV
    links there (a Google TV), else TV Settings with the path written under the row
    (the SHIELD). A note points to `docs/install.md` for the computer route.
  - **Continue in your apps** (`READ_TV_LISTINGS`) and **Your pictures** (storage, or
    `READ_MEDIA_IMAGES` and `_VIDEO` on Android 13+): "Allow" shows Android's own question.
    Once Android won't ask again, it opens App info, and a toast says why.
  - **Apps row by habit:** "Turn on" opens TV Settings' Usage access.
  - **Plex:** "Open Plex settings" moves to Settings › Plex.
  - **Show in the top bar:** a toggle row, on by default, per TV.

  States are read again on every resume. HomeWatch's Configure, in TV Settings ›
  Accessibility, opens this section (`setup/OpenSetupActivity`).
- **Plex Guide** (2.7): its own subsection below, with Edit on your phone.
- **Plex** (Phase 11): after Account, Server and Library, before Sync now and Sign out:
  - **Play in:** Plezy and Plex pills, the one in use ticked. Unset, it's Plezy when Plezy
    is installed (its launcher entry), else Plex. A note says where every Play goes, the
    fallback, why (when nothing's chosen), and that Plezy switches to whoever's watching
    or that Plex may ask who's watching first. Per TV (K4).
  - **Count guide viewing in Plex:** a toggle row, on by default: "A program watched to
    its credits on a guide channel, three quarters of it, is marked watched for whoever's
    watching. Nothing else is sent, and nothing for a kid without a Plex user."
- **Wallpaper** [L1-L4]: styles grouped Classic / Creative / Frontier, then only the
  chosen style's controls (folder, image or slideshow, blur, speed, dim), then the card
  style and Now showing. Remote-friendly steps only, no sliders. No live preview: Back
  shows it.
- **Channels & apps** [L8, L9, M5]: where the channels come from (and `channels.json`),
  which apps feed Continue in your apps, and one line on how the Apps row is ordered (or
  that usage access is off and `scripts/grant-permissions.ps1` turns it on).
- **Search** (Phase 15, Q3): **Include streaming apps**, a toggle row, on by default: Search's
  On your apps row, for every profile on this TV.
- **Lenses** [U19]: first how the card moves (Phase 14):
  - **Rotate on its own** (toggle, on), and while it's on **Speed**: five notches on a line,
    "Slow" to "Fast", the time beside it ("Every 30 s"). Each notch takes focus and focusing
    it chooses it, so Left and Right change the speed with no OK; coming into the row lands
    on the chosen notch (L2);
  - **Shuffle** (toggle, on);
  - **Move on from a title:** After 1 day, After 3 days (default), After a week, Once it's
    watched, as pills with a tick, and a note on what it does.
  Then one row per lens with its rule, when it leads, and an on/off toggle. The last lens
  that's on can't be turned off.
- **Time & modes** (S6, M1-M4):
  - Quick Watch's cutoff for weeknights and for Friday and Saturday nights: −/+ in
    15-minute steps, defaults 10:30 PM and 11:30 PM;
  - one row per context mode with its fixed days and −/+ for its start and end (15-minute
    steps); Daytime is "any other time". Hours that would overlap another mode on a day
    they share are refused with a sentence ("Weekend evening would overlap Late night");
  - a line on what the modes do (§5).
- **AI blurbs** (M7-M10): off by default.
  - On/off; the key ("Add a key on your phone", or "Key ending …abcd" with Forget key);
    the last run ("Last night at 4:02 AM: 28 blurbs, 7,530 tokens, about $0.002", or when
    and why it failed, in the caution colour);
    Write now.
  - A note on what's sent: each pick's title, details and why it was picked; never your
    name, server, account or watch dates.
- **Diagnostics** is the Phase 0 probe screen: device panel, handoff probes, Watch
  Next, stock-launcher state, safe mode.
- **About** (`ui/settings/AboutSettings.kt`):
  - **What it shows:**
    - the version;
    - GPL-3.0's notice: "Copyright © 2026 phonoodlesramen and contributors", free software
      under the GPL v3 or later, with no warranty. "phonoodlesramen" is a small pill with
      GitHub's mark. OK on it shows the maintainer's GitHub profile as a QR code, since TVs
      mostly have no browser, and Back lands on the pill again;
    - where the source is;
    - the Open-Meteo credit.
  - **Open-source licences** is a pill that opens a list of the app and every library the
    APK carries, each with what it's for and its licence.
  - **A licence's full text:** OK on any entry opens it, from `assets/licences/`, in a 360 dp
    box that Up and Down scroll.
  - **Back** goes back a page at a time.
- **Diagnostics › Playback check** (Phase 2.6, `ui/diagnostics/PlaybackCheckScreen.kt`):
  plays the largest MKV and HEVC 10-bit, HDR10, Dolby Vision, DD+, TrueHD and DTS titles
  from their middle in a box on the left, and lists each result (a title with no sound
  fails). It says how many titles have DTS as their main audio ("12 of your titles have
  DTS as their main audio.", 2.7, G15), then checks Plex recorded nothing. Back stops it.

### Settings › Plex Guide [G1, G16, R2]
`ui/settings/PlexGuideSettings.kt`, a Settings section (2.7). Everything works with the
D-pad (§3).
- **Status line** (titleSmall): "18 channels, 2 off · scheduled until 9:30 PM tomorrow"
  (how far ahead every channel that's on is scheduled), "Reading the lineup…" or "No
  channels yet". Under it, a hint on what OK and hold OK do, and one on Ratings: "Right,
  then OK, sets a channel's ratings for everyone, after the program on now. Kids see the
  channels whose titles are all within their ratings."
- **Buttons:** **Edit on your phone**, then **Remake the starter lineup**.
  - Remake asks first: "Remake the starter lineup?", with a line saying every channel is
    replaced, ones made or changed on the phone too. Then **Remake** and **Cancel**,
    Cancel focused. Back cancels.
  - While it works: "Making a new lineup from your library…". A remake the editor
    refuses (the library hasn't synced, nothing fills a day) says why under the buttons.
- **Channels:** one row each, 4 dp apart (`surface`, `surface3` focused, 10 dp corners):
  the number in a 44 dp column, the name, what's on now, and an on/off switch (44×24 dp,
  the accent when on).
  - **OK** turns it on or off. An off channel's name dims; it leaves the Live row, the
    Guide card and the full guide, and its schedule keeps extending, so it's back at once.
  - **Hold OK or Menu** picks it up: a 2 dp accent border, a move icon for the switch,
    and "Up or down to move it, then OK" in the accent. Put down, the lineup is numbered
    1 to n in its new order.
  - A change the editor turns down shows its sentence on that row in the caution colour,
    in place of what's on now.
- **Ratings** (Phase 6, R2): to the right of each row, 8 dp from it and as tall, a 196 dp
  chip (the row's colours): "Ratings: Any" over "No limit", "Ratings: Ages 7–9" over "Up to
  PG and TV-PG", or "Ratings: Custom" over the list.
  - **Right** from a row reaches its Ratings (a carried row keeps Left and Right); Left
    goes back. **OK** steps through Any, then each age bracket's ratings from Settings ›
    Profiles, youngest first (a bracket with nothing ticked, or another's ratings, is left
    out), then Any again.
  - Each step saves the rule's content ratings through the editor, for everyone, from the
    end of the program on now (G16). Quick presses save in order; the chip shows the last
    one until the lineup does. One turned down shows the saved ratings again and the
    sentence on the row.
  - A list set on the phone that's no bracket's is Custom until it's changed; OK on it
    goes to the youngest bracket's.
- **No lineup yet:** "A starter lineup is made from your library once Plex has synced it.
  Remake the starter lineup makes one now."
- Changes from the phone show here, on the Live row and in the guide as they happen.

### Settings › Plex Guide › Guide station [N1-N7]
`ui/settings/GuideStationSettings.kt` (Phase 8). A **Guide station** row tops Settings ›
Plex Guide with the station's state; OK opens this page, and Back returns to Plex Guide.
- **Unpaired:** a paragraph on what a station does, then:
  - **Station address:** a text field for the NAS's address, "Port 8443, unless you add
    another after a colon";
  - **This TV's room:** chips (Living room, Kitchen, Bedroom, Another room), "Other rooms'
    Back to cards name it";
  - **Pair.**
- **Pairing:** "Check that the station shows the same code", then the code in the accent
  (headline, letter-spaced).
  - Under it: if the station's code is different, something else answered at that
    address, so don't approve this TV.
  - **To approve this TV:** the first TV on the NAS: a folder named the code in the
    station's `approve` folder (File Station), or its approve command over SSH; later TVs
    on a paired TV, in its Devices.
  - "Waiting for approval · the code works until 8:45 PM", and **Cancel**. Refused or
    expired says so, with **Try again**.
- **Paired:**
  - the status: a dot and "Connected · updated 3 min ago" (good), "Can't reach the guide
    station · updated 2 hr ago" (caution) or "Unpaired by the station" (bad), then the
    room and address;
  - **The station's Plex:** "Signed in to Plex · Den PC" and when its library synced, or
    its plex.tv/link code while it signs in;
  - **Devices:** one row per TV (its room, model, when it paired and was last seen; "this
    TV" on its own). A TV waiting for approval shows its code in the accent: OK approves it
    once the code matches the one on that TV. OK on another TV unpairs it;
  - **Leave the station:** unpairs this TV, which goes back to making its own guide from
    its copy of the lineup.
- **A 1.1 station** (not updated for Phase 13) shows, in the caution colour: "The station
  needs an update for favorites. Until then, this TV keeps its own." Once it's updated, a
  profile's list kept here goes up to it if the station has none.
- **Editing while paired:** Settings › Plex Guide and the phone editor change the station's
  channels. While the station can't be reached, the editing controls dim (still focusable)
  under "Can't reach the guide station. Channels can't be changed until it's back, here or
  on your phone." in the caution colour, and a press repeats it.

### Settings › NFL [Phase 13, F5, F6, F10]
`ui/settings/NflSettings.kt`, a Settings section after Plex Guide. Hidden on a kid's
profile, as the NFL is.
- **Show the NFL** (O6), a toggle row, first.
  - **Its note:** "Game-day cards, scores, standings and team logos from ESPN's public site,
    for personal use. ESPN, the NFL and its teams own them. While this is off, nothing is
    fetched."
  - **Default:** off for a new house. A house that set the NFL up before the switch (teams,
    Sunday Ticket or Hide scores) reads as on.
  - **While it's off:** it's all the section shows, and the home has no game-day card and no
    NFL row, as on a kid's home.
- **Status line:** "Week 8 · from the guide station · updated 10:36 PM", "Week 8 · from ESPN
  · updated …" (unpaired), "Waiting for the guide station" (or ESPN) before the first copy,
  "The guide station needs an update for the NFL" (a 1.1 station), or the station's
  `problem` in the caution colour.
- **Your teams** and **Your area's teams:** OK on either opens a grid of the 32 teams (eight
  columns, AFC above NFC, 56 dp logos with the abbreviation), where OK stars a team and Back
  saves. The row says who's picked ("Eagles, Steelers" or "None").
  - Your teams lead the card and the row and wear a star (F5).
  - Your area's teams are the ones on your CBS and FOX (E).
- **Sunday Ticket** (toggle): "Sunday afternoon games outside your area's teams open Sunday
  Ticket in YouTube TV."
- **Hide scores** (toggle): "Today's scores stay hidden until you hold OK on a game › Show
  score. Standings stay as they were this morning."
- **Whose they are:** while paired, the four are the house's, kept by the station, and the
  section says "These are the house's: every TV shares them". Unpaired: "These are this
  TV's own, until it's paired with a guide station." While the station can't be reached,
  they dim under "Can't reach the guide station. The NFL's settings can't be changed until
  it's back." (N5).

### Settings › Profiles [K10]
`ui/settings/ProfilesSettings.kt`. Nothing here leaves the Shield or is logged.
- **The list:** you first (read-only, your name from Settings), then each kid ("Kid · 9 ·
  Their age's: PG and TV-PG · Plex: Maya R · 2 apps"), then Add a kid and Ratings by age.
- **A kid's page:** name (the TV keyboard), colour (six swatches), birthday (−/+ for day,
  month and year), Plex Home user (managed users only, or Not linked), ceiling ("Their
  age's" or "Their own", rating toggles from their bracket), My apps (a toggle per app; a
  new kid starts with the kid apps installed), Remove (with a confirm). Each change saves.
- **Linking a Plex Home user** (K2): plex.tv's switch to that user, then `resources`, gives
  their token for your server, sealed with the Keystore. A user with a PIN gets a 4-digit
  field, used for the switch only and never stored. A failure is one sentence and changes
  nothing. Linking, unlinking or removing syncs that kid's Plex and picks straight away.
- **Ratings by age:** each bracket's ages, its ratings as toggles (films G, PG, PG-13; TV
  TV-Y to TV-14). A ceiling moves up on the birthday.

### Add a key on your phone [M10]
`ui/settings/AiKeyScreen.kt`, opened from Settings › AI blurbs. The phone editor's
server and sign-in (QR code or PIN, home network only, stops on Back, on ON_STOP and
after 15 minutes idle) serve a page of one field, `assets/editor/key.html`
(`GET`/`POST /api/key`).
- A key must look like one (`sk-ant-`, 24-512 visible characters); a refusal never repeats
  what was pasted.
- On receipt: "Key saved" on the phone and the TV, then the screen closes and a toast
  says so. The key is sealed with Android Keystore (`KeystoreSecretBox`, alias `ai-key`)
  and only its last 4 characters are ever shown.

### Edit on your phone [G1, G17]
`ui/settings/PhoneEditorScreen.kt`, `guide/editor/`. A full screen from Settings › Plex
Guide. While it's open, the launcher serves the phone editor on the home network.
- **Layout,** in the safe area: "Edit on your phone" (headlineMedium), then the QR code
  (200 dp) on the left, 40 dp from a column with:
  - "Scan the code with your phone's camera" (titleLarge);
  - "Or open http://192.168.1.20:8137 on your phone and enter PIN 042 817" (titleMedium,
    the address and PIN in the accent);
  - the status line (a 10 dp dot and titleMedium text), and a button when there's
    something to do;
  - a faint note: the phone needs the Shield's network, and when the editor stops.
- **QR code:** black modules on white, a 4-module quiet zone, low error correction so the
  modules stay large enough to scan from the couch, each module whole pixels.
- **Sign-in** (G17): the QR code carries a one-time key and signs that phone straight in.
  The 6-digit PIN is for a typed address; five wrong PINs lock PIN entry for that run,
  and the code still works.
- **Status lines:**
  - "Starting…";
  - "Waiting for your phone…", then "Your phone is connected" in `good`;
  - in the caution colour, with **Try again**: "The Shield isn't on a network. Connect
    it, then try again." or "Another app is using the editor's port. Try again in a
    moment.";
  - in the caution colour, with **Start again**: "Stopped after 15 minutes without use"
    or "Stopped while you were away".
- **When it stops:** Back; the home stopping (Home, another app, or a screensaver that
  stops it); 15 minutes without a request. Each start is a new code and PIN, so the phone
  signs in again.
- **Where:** port 8137, or the next free one up to 8141, on the Shield's home-network
  address only (`HomeNetwork`, from the active network's link properties, Ethernet too).
- **Screen care:** nothing holds the screen on, so the Shield's own screensaver covers
  the QR code, as on the home's cards. It relies on that, not on the guide's dimming
  (G10).

### The phone editor's page [G18]
`app/src/main/assets/editor/index.html`: one file with its CSS and JS inline, phone
first, light and dark (dark uses the app's own colours). Nothing loads from elsewhere.
- **Sign in:** "Enter the PIN from your TV", shown when the page isn't signed in.
- **Channels:** each with its number, name, what's on now (or "Off: not in the guide"),
  an on/off switch, Edit, Rename, Number and ↑ ↓; **New channel**; and **Remake the
  starter lineup**, after a confirm.
- **A channel:** name; plays movies, episodes or both; libraries; movie and TV genres;
  shows, collections and studios (each a search: the picked ones, then matches as you
  type); labels (chips, or a search past 30); decades; years since release; content
  ratings; shortest and longest; unwatched only;
  specials; order. A note says films start on the next 5 minutes after a trailer or two
  and episodes run straight on (G21); the start times and filler choices are gone.
- **Studios and labels** (Phase 9): any of the picked ones, an episode by its show's; both
  offered the most titles first. Each row hides while the choices have none (a station
  before API 1.1.0).
- **Then** Preview the next 24 hours (by day, filler tagged), Save, Duplicate and Delete.
  A saved rule takes effect after the program on now (G16), and the page says so. No
  logo upload.
- Libraries show by what they hold ("Movies · 1,204 titles", "TV · 85 shows"): the cache
  keeps section keys, not names (a 2.7 follow-up).

### Kids' home [U13, K7-K9]
`ui/home/KidHome.kt`, while a kid is on. The same card system, their colour as the accent,
bigger tiles; rows 44 / 214 / 196 dp, corners 18 / 12 dp.
- **Row A:** their pick (eyebrow "Pick for you", Play, Trailer and Not now; the title
  drops from 32/40 to 24/32 when it's long, and a Plex logo takes its place as on yours,
  up to 72 dp tall: 64 dp at 100% with a one-line reason, 40 with two, 36 at 115%) and,
  on the right, their lens card (R1), never the pick:
  - every lens, filled from their library within their ceiling, rotating through the
    lenses Settings › Lenses has on, as yours;
  - each lens's tint and eyebrow; Throwback reads "Remember when";
  - a Plex logo in the title's place (Phase 12), in a box the title's line tall (32 dp),
    so nothing moves;
  - **Watch**, or **Watch again** when they've seen it (their plays, never yours);
  - your lens card's quick actions: Watch, Details, Another lens, Don't show this, recorded
    for the kid;
  - "Remember when" leads whenever they have a Throwback title (K7); otherwise the time's
    lead, as yours. A lens picked with Another lens stays until the time moves on.
- **Row B:** their Up Next as two stills (about 240×135 dp) with time left as a chip, and
  My apps: three of their allowlist in Apps-row order, then All apps, two by two.
- **Below:** the Live row with kid-safe channels only (K8). No YouTube TV networks, no
  Continue in your apps, no Apps row and no Guide card. While the guide player's saved
  spot holds on one of their kid-safe channels, its Back-to card is the row's first card
  (P4, §4 Live row); Back from the player it resumed lands on that channel's Live card.
- Their picks, Up Next and lens cards come from their own Plex Home user's plays and
  Continue Watching (K2), within their ceiling; an unrated title is hidden (K4). AI blurbs
  never run for a kid.
- **Kid-safe channels** (K8, R2): judged by what a channel can play, not its rule's
  wording. Every title its rule can play (an episode without a rating has its show's) and
  every program still to finish in its schedule must be rated inside the ceiling, and none
  unrated (K4); a channel that can play nothing isn't. So a marathon of a TV-Y show counts
  on its own, and a channel whose Ratings just changed shows once the program on now is
  over. The rule's ratings are read once per library sync. The same filter applies to the
  Live row, the full guide and the player's channel up/down and mini guide. The starter
  lineup has a Family channel (G to TV-PG); Settings › Plex Guide sets any channel's
  Ratings.

### Who's watching? [REQ, U13, K1]
`ui/profiles/WhosWatchingScreen.kt`.
- Avatars 96 dp in a row, with the name and "Kid · 9" or "You"; whoever is on is marked
  "(now)" and starts focused.
- Focus: 1.08 scale plus a double ring.
- Opens from the avatar, and when the home comes back after the screen was off, once
  there's a kid. Not on every Home press.
- OK switches, says "Now watching as Maya", and the home opens at the top. Back closes it.
- **Parent PIN: shelved** (K5), kept for an open-source release. As specified: from a kid
  profile, picking an adult opens a PIN pad (title, reason line, four dots, a 3×4 keypad
  of 64×52 dp keys, 1-9, ⌫, 0, ✓) that accepts on the fourth digit.

### Toasts
- Pill at the bottom centre, light on dark (`#E6EDF3` on `#0B0F13`), 2.6 s.
- Never focusable, so nothing that needs an action goes in a toast [U12].
- **Caution** (Phase 13, `Toast.kt`): a refused change says why on a `surface3` pill with a
  warning icon, in the caution colour ("Can't reach the guide station.").

## 5. States

| State | What changes |
|---|---|
| Normal | As above |
| First run [U14] | Hero = Plex code. The lens card, Quick Watch and Up Next show dashed placeholders ("appears once Plex is connected"). The Guide card says its channels appear once Plex is connected, and the Live row has YouTube TV's networks alone. Apps, All apps, clock and weather work |
| Plex offline [U15] | Status "Plex offline" in red. Everything renders from cache, and the hero's note line names the cache age. The Guide card, Live row and full guide still work from the cache; tuning in shows the "Can't reach Plex" card, which tries again every 30 s |
| Suggestions, before the engine's first run | The media cards use Phase 2's rules (the newest unwatched films, Throwback and Classic only), so they're never empty. The engine's picks take over once it has run |
| Guide, no channels yet | The Guide card and the full guide say what they wait on (§4, Plex Guide card): Plex, or the library's sync. The Live row has YouTube TV's networks alone, and the player's off-air card says "No channels yet" in this state and the three below |
| Guide, building [G20] | The library has synced and the lineup is being made: the first time, after a remake, or for a different server (G19). "They're being made from your library now" |
| Guide, nothing to fill [G20] | Synced, but nothing fills a day of any channel: "Not enough in your library to fill a channel yet". A remake by hand is refused, with a sentence that says so |
| Guide, none on | Every channel turned off, or deleted by hand: "They're all off in Settings › Plex Guide". That section still lists the off channels |
| Player, behind live [G11, P1] | Started over, paused, or sought back with ⏪: the banner says how far behind live, and while paused it stays and the screen isn't held on. The program's end rejoins live, and so does ⏩ to live |
| Player, control row [P2] | OK over a program with a file: Back 10, Play/Pause (focused), Forward 30, and Back to live while behind. Left/Right move along it, OK presses, Back takes it away; it hides with the banner 5 s after the last press, and stays while paused. Over filler or a card, OK shows the banner alone |
| Player, scrubbing [P1] | ⏪ or ⏩ held: the eyebrow gives the speed (×2, ×4, ×8) and where letting go lands, a white tick on the bar marks it, and "9:00 in" replaces the time left. Nothing seeks until it's let go |
| Spot saved [P3, P4] | The player was left by Home (or Plezy, the screensaver) on a program with a file, under 4 hours ago, and nothing else has played: the Guide card is "Back to {channel}" with the program's art, its title and "Paused 1:12:30 in · 9 min ago"; on a kid's home, only on a kid-safe channel, it's the Live row's first card. Resume opens it paused behind live; Back to live and Forget this forget it |
| YouTube TV last | YouTube TV was the last app watched, left under 4 hours ago, and is newer than any saved spot: the Guide card is "Back to YouTube TV" with the channel it started on (when opened here) and "Left 9 min ago". OK brings YouTube TV back; Forget this forgets that visit |
| Player, trying again | Plex can't be reached, or the program's file can't be had right now (a NAS drive restarting): the off-air card says so in plain words and "Trying again…". Every 30 s while the program's on, it's tuned in again where it is by then; the card stays until frames move (§4, Guide player) |
| Program details, gone | The program has left the lineup: "This program is no longer in the guide", and Back returns to the guide |
| Weeknight evening [M3] | A "finish it" pick can lead from 4 episodes before the season's end (other modes: 3); films over 2h 15m move down Tonight's Pick |
| Weekend evening [M2, M3] | No length limit; films before series on Tonight's Pick; Quick Watch's weekend cutoff |
| Late night [M3] | Accent and art dimmed (§2); films over 1h 45m move down Tonight's Pick and an episode of a season you're in comes first; Quick Watch shows what fits in the next 45 minutes |
| Weekend morning [M3] | Titles rated G to TV-PG come first on Tonight's Pick; For the family leads the lens card |
| Daytime | The engine's order |
| Kids, school night before bedtime | Shelved (K6). Would be: a bedtime card replaces the right-hand card: "19 minutes until bedtime at 9:00", "Calm picks only from now on", **Wind down** |
| Kids, "Remember when" | Their Throwback leads their lens card (warm, art-backed): "A year ago you watched it three times in one week!", **Watch again**. Without one, the time's lens, as yours (R1) |
| Kids, past bedtime | Shelved (K6). Would be: bedtime card "Past bedtime", **Ask a grown-up** |
| Kids, no kid-safe channels | The Live row is left out; the full guide says "None of them are for your age yet" |
| Kids, not linked to Plex | No Up Next (never yours); picks from the shared library within their ceiling |
| Safe mode | The Phase 0 screen (amber banner, All apps, Settings, Leave safe mode). Never the art-forward home |
| Stock launcher back | Red chip in the top bar (§4) |
| Guide station, pairing [N3] | The code on the TV and in the station's log must match; the first TV is approved on the NAS, later ones on a paired TV (§4, Guide station) |
| Guide station, paired [N4-N6] | The guide is the station's copy: every TV shows the same programs, an edit on one reaches all within seconds, and a spot paused in another room shows its room on the Back-to card |
| Guide station, can't reach it [N5] | The TV keeps its copy (the lineup and 48 hours) and plays from it; editing dims with its sentence; the Guide station row says when it last updated |
| NFL, off-season [F8] | From the day after the Super Bowl to the first preseason game: no card, no row; Settings › NFL keeps the teams |
| NFL, preseason [F8, D] | Preseason games in the row and Games; Standings and Playoffs show last season's final, said so |
| NFL, game day [F1] | The card takes Quick Watch's spot from 30 minutes before a kickoff to the day's last final; the row moves first below the dashboard from an hour before the first kickoff |
| NFL, an old copy [F11] | The station or ESPN can't be reached: everything draws from the copy, and while a game is on a copy older than 2 minutes says "Scores from 2:41 PM" under the score |
| NFL, nothing yet | No copy yet (a new TV, the first fetch): the row is left out and the NFL screen says "The NFL appears once the guide station (or ESPN) answers" |
| NFL, scores hidden [F10, C] | "Score hidden" for today's games; standings as they were this morning; Show score in a game's quick actions |
| NFL, a 1.1 station | The TV's NFL and Settings › NFL say "The guide station needs an update for the NFL"; favorites are kept on the TV until it's updated |
| Plex updating | A sync has run 0.5 s: a spinner and "Updating" after the Plex status (§4, Top bar), offline too. Typically when the home opens, when the TV wakes, and back from an app a minute or more later. In the fullest top bar (offline, a weekend morning's kids' avatars, 115%) the greeting gives way to an ellipsis while it shows |

## 6. Art-forward rules [U1]

- **Scrims:** every piece of text over artwork sits on a scrim. Text contrast must
  stay at or above 4.5:1 against the darkest point the scrim leaves. **V** (cards
  guide: use a scrim).
- **Sizes:** request every image at its tile's pixel size (dp × 2), centre-cropped.
  From Plex, use the photo transcoder (`/photo/:/transcode?width=&height=&minSize=1`).
  A title logo (Phase 12) is `format=png&minSize=0` instead: fitted inside
  its box, its transparency kept.
- **Memory budget** for one screen of art (ARGB_8888):

  | Image | Pixels | Approx. MB |
  |---|---|---|
  | Hero backdrop | 995×472 | 1.9 |
  | Lens card art | 701×248 | 0.7 |
  | Up Next stills ×3 | 310×174 | 0.65 |
  | Quick Watch thumbs ×2 | 72×40 | 0.02 |
  | App banners ×5 | 320×180 | 1.15 |
  | Hero logo, at most (Phase 12) | 712×120 | 0.33 |
  | Lens card logo, at most | 645×56 | 0.14 |
  | **Total** | | **≈ 4.9** |

  Logos are software bitmaps (the dark check reads their pixels), the rest hardware.

- **Coil 3:** hardware bitmaps, a memory cache capped at 24 MB, and the memory cache
  cleared at `TRIM_MEMORY_UI_HIDDEN`.
- **Placeholders:**
  - Until real art arrives, show a gradient generated from the title (two colours +
    a motif, as in the prototype).
  - Phase 1's sample content uses only these.
  - There is never a blank or shimmering card.

## 6b. Wallpaper [L1-L4]

| Family | Style | What it is |
|---|---|---|
| Classic | **Midnight** (default) | The `bg` colour, unchanged |
| Classic | Cover art | Tonight's Pick's backdrop, blurred once from 480×270 px, crossfading when the pick changes |
| Classic | Your images | One image or a slideshow from a Shield folder (default `Pictures/Wallpapers`), decoded at most 1920×1080, fill or fit, optional slow pan; sharp by default |
| Classic | Accent gradient | A dithered glow in the accent colour |
| Creative | Glow spirits | Six soft orbs drifting, at most 30 fps |
| Creative | Video loop | A muted loop from the folder (Media3), on a `SurfaceView`, released whenever Home isn't showing |
| Creative | Ambient light | Three colours from the focused card's art |
| Creative | Poster wall | 30 posters from the Plex cache, drifting slowly |
| Frontier | Living sky | Sunrise/sunset and the weather from Open-Meteo: dawn, day, golden hour, dusk, night with stars, rain and snow |

- **Frosted glass** (L4): a card style. Cards without art draw the still wallpaper's
  blurred copy behind themselves. Over Midnight and the moving styles it's a tint.
- **Now showing**: after the chosen idle minutes, Tonight's Pick fills the screen with
  the clock and weather. The first key only wakes it. The text drifts a few dp each
  minute for OLED care, the screen is never held on, and the wallpaper underneath
  stops once it's covered.
- **Dim**: every style except Midnight has a −/+ dim (0-80%).
- **Legibility** (the operator's choice, 10/27/2026):
  - still wallpapers may be as bright as primary text allows at 4.5:1 (luminance
    0.147);
  - dimmer labels on them (top bar, eyebrows, captions) get a soft dark halo,
    `onWallpaper()`;
  - frosted glass is tinted enough to keep dim text inside it at 4.5:1;
  - Midnight needs neither and is unchanged.
- **Rules**: animated styles freeze when Android's animations are off, slow at late
  night, and run only while the home is started.

## 7. Motion [U16]

| What | Duration | Curve |
|---|---|---|
| Focus ring and lift | 150 ms | ease-out |
| Sheets, overlays | 200 ms | slide 40 dp + fade, ease-out |
| Hero swap (Not tonight / Undo) | 250 ms | crossfade |
| All apps scroll | 200 ms | ease-out |

No parallax. Ambient and animated backgrounds are a wallpaper choice (L1, §6b),
never the default. Honour the system animation scale.

## 8. Compose mapping

| Spec | Compose for TV |
|---|---|
| Cards, tiles, rows | `androidx.tv.material3.Surface(onClick, onLongClick, scale, border, colors)` |
| Buttons | `androidx.tv.material3.Button` with `ButtonDefaults.colors(focusedContainerColor = accent)` |
| Grid | `Row`/`Column` with weights for the home. All apps uses `LazyVerticalGrid` (Foundation; TvLazy* is gone) |
| Focus groups | `Modifier.focusRestorer().focusGroup()` per panel (restorer first, or it doesn't restore) |
| Initial / Home focus | `FocusRequester` on Play, requested in `LaunchedEffect(homePresses)` |
| Layout scale | `CompositionLocalProvider(LocalDensity provides Density(density * scale, fontScale))` |
| Back | `BackHandler` |
| Quick actions | `onLongClick` + `onPreviewKeyEvent` for `Key.Menu` |
| Sepia | `ColorFilter.colorMatrix(...)` |
