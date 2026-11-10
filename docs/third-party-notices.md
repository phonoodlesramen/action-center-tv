# Third-party notices

Material that the app ships but didn't write. Action Center itself is GPL-3.0-or-later
(`LICENSE`). On the TV, Settings › About › Open-source licences lists the same libraries,
with each licence's full text (`app/src/main/assets/licences/`).

## Libraries

Pulled in by Gradle, each under its own licence. All are compatible with GPL-3.0:

| Library | Used by | Licence |
|---|---|---|
| NextLib (`io.github.anilbeesetti:nextlib-media3ext`) | The guide player's audio decoders (DTS and others) | GPL-3.0 |
| FFmpeg, inside NextLib | The same | LGPL-3.0-or-later. NextLib builds it with `--enable-version3`, without `--enable-gpl` or `--enable-nonfree` (its `ffmpeg/setup.sh`, read 11/06/2026) |
| Mbed TLS and dav1d, inside NextLib's FFmpeg | The same | Apache-2.0; BSD-2-Clause |
| AndroidX, Jetpack Compose, Compose for TV, Media3, Room, WorkManager, DataStore | The app | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Everything | Apache-2.0 |
| OkHttp | The app, `:guide-core`, the station | Apache-2.0 |
| Coil | The app's images | Apache-2.0 |
| SQLite (bundled through `androidx.sqlite`) | `:guide-core` on the JVM, the station | Public domain |
| QR Code generator (`io.nayuki:qrcodegen`) | The phone editor's QR code | MIT |
| Octicons' `mark-github-24` (github.com/primer/octicons) | GitHub's mark, beside the maintainer's GitHub name in Settings › About | MIT. The mark is GitHub's trademark, and GitHub's logo guidelines allow it to point to a GitHub profile |
| Bouncy Castle (`bcpkix-jdk18on`) | The station's certificate authority | Bouncy Castle Licence (MIT-style) |

## Material Symbols

The app's icons (`AppIcons` in `app/src/main/kotlin/com/phonoodlesramen/actioncenter/ui/theme/Icons.kt`
and `PlayerIcons` in `.../ui/player/PlayerIcons.kt`) are Google's **Material Symbols Rounded**,
weight 400, grade 0, at the 24 px optical size.

- **Source:** https://github.com/google/material-design-icons, the SVGs under
  `symbols/web/<name>/materialsymbolsrounded/` (`<name>_24px.svg` outlined,
  `<name>_fill1_24px.svg` filled). Each icon's path is copied into the Kotlin unchanged.
- **Licence:** Apache License, Version 2.0: the repository's `LICENSE`, under which its README
  makes the icons available.

```
Material Symbols, by Google.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

## NFL data and team logos (Phase 13)

The app doesn't ship them: the guide station (or an unpaired TV) fetches them at run time
from ESPN's public site API (`site.api.espn.com`) and logo CDN (`a.espncdn.com`). The scores,
schedules, standings and team logos belong to ESPN and the NFL and its teams. The API is
undocumented. Disney's terms of use (disneytermsofuse.com) allow personal, non-commercial use
and forbid accessing content by automated means. So the launcher is for one household, polls
only while a game is on and someone is looking, caches everything, and shows no betting odds.
The NFL is off until someone turns it on (Settings › NFL › Show the NFL). While it's off,
neither a TV nor the station asks ESPN anything.
