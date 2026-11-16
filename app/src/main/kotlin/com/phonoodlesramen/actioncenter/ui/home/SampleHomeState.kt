package com.phonoodlesramen.actioncenter.ui.home

import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.content.HomeContent
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensCursor
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.home.HomeLayout
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.home.PlexHome
import com.phonoodlesramen.actioncenter.home.PickState
import com.phonoodlesramen.actioncenter.home.homeUiState
import com.phonoodlesramen.actioncenter.home.lensCard
import com.phonoodlesramen.actioncenter.plex.ConnectionKind
import com.phonoodlesramen.actioncenter.plex.LinkedServer
import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.PlexLink
import com.phonoodlesramen.actioncenter.plex.PlexSignIn
import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexSnapshot
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.settings.Settings
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Profiles
import com.phonoodlesramen.actioncenter.suggest.Surfaces
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.weather.Place
import com.phonoodlesramen.actioncenter.weather.TemperatureUnit
import com.phonoodlesramen.actioncenter.weather.WeatherReading
import com.phonoodlesramen.actioncenter.weather.WeatherState
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Made-up states for previews and screenshot tests. */
internal object SampleHomeState {
    /** Monday 26 October 2026, 8:41 PM: a weeknight evening. */
    val weeknight: LocalDateTime = LocalDateTime.of(2026, 10, 26, 20, 41)
    val lateNight: LocalDateTime = LocalDateTime.of(2026, 10, 26, 23, 48)
    val saturdayMorning: LocalDateTime = LocalDateTime.of(2026, 10, 31, 9, 15)
    val fridayEvening: LocalDateTime = LocalDateTime.of(2026, 10, 30, 20, 5)

    val apps = listOf(
        app("com.disney.disneyplus", "Disney+"),
        app("org.xbmc.kodi", "Kodi"),
        app("com.netflix.ninja", "Netflix"),
        app("com.plexapp.android", "Plex"),
        app("com.edde746.plezy", "Plezy"),
        app("com.amazon.amazonvideo.livingroom", "Prime Video"),
        app("com.spotify.tv.android", "Spotify"),
        app("tv.twitch.android.app", "Twitch"),
        app("com.google.android.youtube.tv", "YouTube"),
    )

    /** Plezy, YouTube, Netflix, Spotify and Plex are the most launched. */
    private val launchCounts = mapOf(
        "com.edde746.plezy/com.edde746.plezy.MainActivity" to 40,
        "com.google.android.youtube.tv/com.google.android.youtube.tv.MainActivity" to 22,
        "com.netflix.ninja/com.netflix.ninja.MainActivity" to 15,
        "com.spotify.tv.android/com.spotify.tv.android.MainActivity" to 9,
        "com.plexapp.android/com.plexapp.android.MainActivity" to 4,
    )

    fun state(
        sample: Boolean = true,
        time: LocalDateTime = weeknight,
        stockLauncherBack: Boolean = false,
        layoutScale: Float = 1f,
        pickState: PickState = PickState(),
        weather: WeatherState = this.weather(time),
        disabledLenses: Set<Lens> = emptySet(),
        plex: PlexHome = PlexHome(),
        plexSnapshot: PlexSnapshot? = null,
        cursor: LensCursor = LensCursor(),
        lensPrefs: LensPrefs = LensPrefs(),
        homeLayout: HomeLayout = HomeLayout.DEFAULT,
        moments: Boolean = false,
        sideRail: Boolean = false,
        /** Everyone on this TV, you first; just you by default. */
        profiles: List<ActiveProfile>? = null,
    ): HomeUiState = homeUiState(
        settings = Settings(
            name = "Alex",
            showSampleContent = sample,
            launchCounts = launchCounts,
            layoutScale = layoutScale,
            disabledLenses = disabledLenses,
            lensPrefs = lensPrefs,
            homeLayout = homeLayout,
            moments = moments,
            sideRail = sideRail,
        ),
        time = time,
        installed = apps,
        stock = if (stockLauncherBack) PackageState.ENABLED else PackageState.DISABLED,
        picks = pickState,
        cursor = cursor,
        weather = weather,
        nowMillis = time.millis(),
        plex = plex,
        plexSnapshot = plexSnapshot,
        profile = profiles?.first() ?: ActiveProfile.owner("Alex"),
        profiles = profiles ?: listOf(ActiveProfile.owner("Alex")),
    )

    /** You, then Maya and Leo: a house with kids, for "Who's watching?" and the weekend morning's avatars (K11). */
    val family: List<ActiveProfile> get() = listOf(ActiveProfile.owner("Alex"), maya, leo)

    /** A small made-up Plex cache: what the cards look like built from real data (P6). */
    fun plexLibrary(time: LocalDateTime = weeknight): PlexSnapshot {
        val now = time.toEpochSecond(ZoneOffset.UTC)
        fun movie(key: String, title: String, year: Int, minutes: Long, rating: String, addedDaysAgo: Long, seen: Int = 0, lastSeen: Long? = null) =
            ItemEntity(key, "1", "movie", title, year, minutes * 60_000, seen, lastSeen, now - addedDaysAgo * 86_400, null, rating, 8.5, null, null, "/art/$key", "", 0)
        fun episode(position: Int, key: String, show: String, season: Int, episode: Int, minutes: Long, watched: Long) =
            ContinueEntity(position, key, "episode", "Episode $episode", show, season, episode, null, minutes * 60_000, watched * 60_000, "TV-14", "/thumb/$key", null, "/art/$show")
        return PlexSnapshot(
            day = time.toLocalDate(),
            continueWatching = listOf(
                episode(0, "slow", "Slow Horses", 4, 2, 49, 8),
                episode(1, "shogun", "Shōgun", 1, 7, 58, 46),
                episode(2, "bake", "Great British Bake Off", 12, 3, 58, 6),
                episode(3, "bear", "The Bear", 2, 3, 26, 0),
                episode(4, "abbott", "Abbott Elementary", 3, 4, 22, 2),
            ),
            newest = listOf(movie("arrival", "Arrival", 2016, 116, "PG-13", 3), movie("dune2", "Dune: Part Two", 2024, 166, "PG-13", 9)),
            classics = listOf(movie("jaws", "Jaws", 1975, 124, "PG", 400)),
            throwbacks = listOf(movie("heat", "Heat", 1995, 170, "R", 900, seen = 2)),
            itemCount = 5,
        )
    }

    /**
     * The same cache once the suggestion engine has run (Phase 3): a finish pick first, a
     * film, the lenses, Up Next in its order, Quick Watch's candidates, and More like Arrival.
     */
    fun engineLibrary(time: LocalDateTime = weeknight): PlexSnapshot {
        val library = plexLibrary(time)
        val now = time.toEpochSecond(ZoneOffset.UTC)
        fun title(key: String, name: String, year: Int, minutes: Long, rating: String, seen: Int = 0) =
            ItemEntity(key, "1", "movie", name, year, minutes * 60_000, seen, null, now - 90 * 86_400, null, rating, 8.4, null, null, "/art/$key", "", 0)
        fun episode(key: String, show: String, showTitle: String, season: Int, number: Int, minutes: Long) =
            title(key, "Episode $number", 2025, minutes, "TV-MA").copy(
                type = "episode", showKey = show, showTitle = showTitle, season = season, episode = number, thumb = "/thumb/$key", art = null,
            )
        val titles = listOf(
            episode("sev9", "sev", "Severance", 2, 9, 51),
            title("sev", "Severance", 2022, 0, "TV-MA").copy(type = "show", durationMs = null),
            title("arrival", "Arrival", 2016, 116, "PG-13"),
            title("sicario", "Sicario", 2015, 121, "R"),
            title("contact", "Contact", 1997, 150, "PG"),
            title("jaws", "Jaws", 1975, 124, "PG"),
            title("heat", "Heat", 1995, 170, "R", seen = 2),
            episode("abbott4", "abbott-show", "Abbott Elementary", 3, 4, 22),
        )
        fun row(surface: String, rank: Int, key: String, reason: String, evidence: String = "") =
            PickEntity(Profiles.OWNER, surface, rank, key, reason, evidence, 0)
        val picks = listOf(
            row(Surfaces.HERO, 0, "sev9", "You're 2 episodes from the end of Severance's season 2", "Last watched yesterday"),
            row(Surfaces.HERO, 1, "arrival", "Because you watched Denis Villeneuve's Sicario", "Rated 8.4"),
            row(Surfaces.lens(Lens.THROWBACK), 0, "heat", "One year ago this week you watched"),
            row(Surfaces.lens(Lens.CLASSIC), 0, "jaws", "51 years old and you've never seen it"),
            row(Surfaces.UP_NEXT, 0, "shogun", "4 episodes this week"),
            row(Surfaces.UP_NEXT, 1, "slow", "You usually watch this in the evening"),
            row(Surfaces.UP_NEXT, 2, "bake", "Watched 3 days ago"),
            row(Surfaces.QUICK_WATCH, 0, "bear", "Next episode"),
            row(Surfaces.QUICK_WATCH, 1, "abbott4", "Next episode"),
            row(Surfaces.moreLike("arrival"), 0, "sicario", "Also Denis Villeneuve"),
            row(Surfaces.moreLike("arrival"), 1, "contact", "Science Fiction · like Arrival"),
        )
        return library.copy(picks = picks, titles = titles.associateBy { it.ratingKey })
    }

    /**
     * Maya, a made-up kid of 9 in coral (U13), whose ceiling is the 7-9 bracket's (K3). Her
     * My apps (K9) are Plezy, YouTube Kids, Disney+, PBS KIDS Video and Minecraft; Netflix,
     * YouTube and the rest aren't on it.
     */
    val maya = ActiveProfile(
        id = "kid-maya",
        name = "Maya",
        kid = true,
        color = 0xFFFF8A65,
        age = 9,
        ceiling = AgeBrackets.DEFAULT.first().ratings,
        apps = setOf(
            "com.edde746.plezy/com.edde746.plezy.MainActivity",
            "com.google.android.youtube.tvkids/com.google.android.youtube.tvkids.MainActivity",
            "com.disney.disneyplus/com.disney.disneyplus.MainActivity",
            "org.pbskids.video/org.pbskids.video.MainActivity",
            "com.mojang.minecraftpe/com.mojang.minecraftpe.MainActivity",
        ),
    )

    /** Leo, a made-up kid of 6 in green, with Maya's ceiling and apps. */
    val leo = maya.copy(id = "kid-leo", name = "Leo", color = 0xFFAED581, age = 6)

    /**
     * Your favorite channels in the sample guide (Phase 13), in your order: Comedy, then TV Mix.
     * They lead the Live row and the full guide, and Comedy's 9:00 leads the Guide card.
     */
    val favorites = listOf("comedy", "tv-mix")

    /**
     * Maya's (Phase 13): Cartoon Club and Family, and Comedy, which isn't kid-safe, so it
     * stays in her list but never shows ([com.phonoodlesramen.actioncenter.guide.KidGuide]).
     */
    val mayaFavorites = listOf("cartoons", "comedy", "family")

    /** Kids' apps a grown-up might have installed, beside [apps]. */
    val kidApps = listOf(
        app("com.mojang.minecraftpe", "Minecraft"),
        app("org.pbskids.video", "PBS KIDS Video"),
        app("com.google.android.youtube.tvkids", "YouTube Kids"),
    )

    /** Plezy and YouTube Kids are what Maya opens most. */
    private val kidLaunchCounts = launchCounts + ("com.google.android.youtube.tvkids/com.google.android.youtube.tvkids.MainActivity" to 30)

    /**
     * Maya's home (K7) at [time]: her pick, her lens card (R1: "Remember when" leading, or
     * the time's lens at the weekend), Up Next and My apps, from [kidContent]. Plex is
     * linked. [cursor] is the lens card's place, as Another lens leaves it.
     */
    fun kid(
        time: LocalDateTime = weeknight,
        layoutScale: Float = 1f,
        content: HomeContent? = kidContent(time),
        cursor: LensCursor = LensCursor(),
        disabledLenses: Set<Lens> = emptySet(),
        lensPrefs: LensPrefs = LensPrefs(),
    ): HomeUiState {
        val settings = Settings(
            name = "Alex", launchCounts = kidLaunchCounts, layoutScale = layoutScale, disabledLenses = disabledLenses, lensPrefs = lensPrefs,
        )
        val state = homeUiState(
            settings = settings,
            time = time,
            installed = (apps + kidApps).sortedBy { it.label.lowercase() },
            stock = PackageState.DISABLED,
            picks = PickState(),
            cursor = cursor,
            weather = weather(time),
            nowMillis = time.millis(),
            plex = plexLinked,
            profile = maya,
            profiles = listOf(ActiveProfile.owner("Alex"), maya),
        )
        val pick = content?.picks?.firstOrNull()
        return state.copy(
            content = content,
            pick = pick,
            lens = lensCard(content, settings.enabledLenses, time, pick, settings.contextSchedule, cursor, maya, lensPrefs.shuffle),
            sampleContent = true,
        )
    }

    /**
     * Maya's cards, made up from family films and shows, through every lens (R1). On a
     * weeknight her Throwback leads as "Remember when"; on a Saturday morning she has none, so
     * the time's lens, For the family, leads. Classic, Action & adventure, Hidden gem, Finish
     * the series, Halloween and Comfort rewatch have a title each.
     */
    fun kidContent(time: LocalDateTime = weeknight): HomeContent {
        val weekend = time.dayOfWeek == DayOfWeek.SATURDAY || time.dayOfWeek == DayOfWeek.SUNDAY
        val picks = if (weekend) {
            listOf(
                Pick("spiderverse", "Spider-Man: Into the Spider-Verse", "2018 · 1h 57m · PG", "Because you loved Puss in Boots: The Last Wish"),
                Pick("wallace", "Wallace & Gromit: Vengeance Most Fowl", "2024 · 1h 19m · PG", "New, and very silly"),
            )
        } else {
            listOf(
                Pick("wildrobot", "The Wild Robot", "2024 · 1h 42m · PG", "Because you loved How to Train Your Dragon"),
                Pick("shaun", "Shaun the Sheep Movie", "2015 · 1h 25m · PG", "Short and silly, just right for a school night"),
            )
        }
        val family = LensPick("totoro", "My Neighbor Totoro", "1988 · 1h 26m · G", "A family favourite you haven't seen yet", seen = false)
        // Her other lenses, from her plays and within her ratings (R1).
        val others = mapOf(
            Lens.CLASSIC to listOf(LensPick("irongiant", "The Iron Giant", "1999 · 1h 26m · PG", "Rated 8.1 · never played", seen = false)),
            Lens.FAMILY to listOf(family),
            Lens.ACTION to listOf(
                LensPick("kungfupanda", "Kung Fu Panda", "2008 · 1h 32m · PG", "Last watched in 2023", seen = true),
            ),
            Lens.HIDDEN_GEM to listOf(
                LensPick("songofthesea", "Song of the Sea", "2014 · 1h 33m · PG", "Rated 8.0 · in your library since 2022, never played", seen = false),
            ),
            Lens.FINISH_SERIES to listOf(
                LensPick("toystory3", "Toy Story 3", "2010 · 1h 43m · G", "You watched Toy Story 2 · Toy Story 3 is next", seen = false),
            ),
            Lens.SEASONAL to listOf(
                LensPick("hoteltransylvania", "Hotel Transylvania", "2012 · 1h 31m · PG", "A Halloween film you haven't seen", seen = false),
            ),
            Lens.COMFORT to listOf(LensPick("moana", "Moana", "2016 · 1h 47m · PG", "Watched four times", seen = true)),
        )
        val lenses = if (weekend) {
            others
        } else {
            mapOf(
                Lens.THROWBACK to listOf(
                    LensPick("paddington2", "Paddington 2", "2017 · 1h 44m · PG", "A year ago you watched it three times in one week!", seen = true),
                ),
            ) + others
        }
        return HomeContent(
            picks = picks,
            lenses = lenses,
            quickWatch = emptyList(),
            quickWatchCutoff = "",
            upNext = listOf(
                UpNextItem("avatar", "Avatar: The Last Airbender", "S1E8", "14m left", 0.45f),
                UpNextItem("hilda", "Hilda", "S2E3", "20m left", 0.12f),
            ),
        )
    }

    /** Signing in: the code to enter at plex.tv/link. */
    val plexCode = PlexHome(signIn = PlexSignIn.ShowingCode("K7QF", Long.MAX_VALUE))

    /** Signed in and linked to a local server. */
    val plexLinked = PlexHome(
        link = PlexLink(
            account = PlexAccount(1, "uuid", "example-user", "Alex"),
            server = LinkedServer("Basement", "aaaa1111", "https://192-168-1-20.aaaa1111.plex.direct:32400", ConnectionKind.LOCAL),
        ),
        signIn = PlexSignIn.Linked,
    )

    /** Portland, 54°F and overcast, read 12 minutes before [time]. */
    fun weather(time: LocalDateTime = weeknight) = WeatherState(
        place = Place("Portland", "Oregon", "United States", 45.52, -122.68),
        unit = TemperatureUnit.FAHRENHEIT,
        reading = WeatherReading(celsius = 12.2, code = 3, isDay = false, fetchedAtMillis = time.millis() - 12 * 60_000),
    )

    private fun LocalDateTime.millis() = toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun app(packageName: String, label: String) =
        LaunchableApp(packageName, "$packageName.MainActivity", label)
}
