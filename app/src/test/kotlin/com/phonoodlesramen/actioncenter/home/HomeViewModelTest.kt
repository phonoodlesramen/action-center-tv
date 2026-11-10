package com.phonoodlesramen.actioncenter.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensMoveOn
import com.phonoodlesramen.actioncenter.content.LensPrefs
import com.phonoodlesramen.actioncenter.lastapp.AppVisit
import com.phonoodlesramen.actioncenter.lastapp.MemoryTvChannels
import com.phonoodlesramen.actioncenter.lastapp.RecentApps
import com.phonoodlesramen.actioncenter.lastapp.TunedChannel
import com.phonoodlesramen.actioncenter.player.MemorySpots
import com.phonoodlesramen.actioncenter.player.SavedSpot
import com.phonoodlesramen.actioncenter.plex.ConnectionKind
import com.phonoodlesramen.actioncenter.plex.LinkedServer
import com.phonoodlesramen.actioncenter.plex.PlexAccount
import com.phonoodlesramen.actioncenter.plex.PlexLink
import com.phonoodlesramen.actioncenter.plex.PlexSignIn
import com.phonoodlesramen.actioncenter.plex.PlexSyncStatus
import com.phonoodlesramen.actioncenter.plex.cache.ContinueEntity
import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.plex.cache.PlexSnapshot
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.profiles.ProfileStore
import com.phonoodlesramen.actioncenter.suggest.FeedbackKind
import com.phonoodlesramen.actioncenter.suggest.FeedbackUndo
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Profiles
import com.phonoodlesramen.actioncenter.suggest.SuggestionFeedback
import com.phonoodlesramen.actioncenter.suggest.Surfaces
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.settings.SettingsRepository
import com.phonoodlesramen.actioncenter.system.PackageState
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.weather.Place
import com.phonoodlesramen.actioncenter.weather.TemperatureUnit
import com.phonoodlesramen.actioncenter.weather.WeatherReading
import com.phonoodlesramen.actioncenter.weather.WeatherRepository
import com.phonoodlesramen.actioncenter.weather.WeatherState
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A clock the test moves by hand, alongside the coroutine test clock. */
    private class TestClock(var instant: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = instant
        fun advance(millis: Long) { instant = instant.plusMillis(millis) }
    }

    private class FakeSystem(
        var apps: List<LaunchableApp> = emptyList(),
        var stock: PackageState = PackageState.DISABLED,
    ) : HomeSystem {
        override suspend fun launchableApps() = apps
        override fun stockLauncherState() = stock
    }

    /** Runs what it's sent on [inner], counting it: the work the home sends off the main thread. */
    private class CountingDispatcher(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var sent = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            sent++
            inner.dispatch(context, block)
        }
    }

    // 2026-10-26 is a Monday.
    private val mondayEvening = Instant.parse("2026-10-26T20:41:00Z")

    private fun TestScope.settings(): SettingsRepository {
        val file = File(Files.createTempDirectory("settings").toFile(), "s.preferences_pb")
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })
    }

    private fun TestScope.viewModel(
        settings: SettingsRepository,
        clock: TestClock = TestClock(mondayEvening),
        system: FakeSystem = FakeSystem(),
    ) = HomeViewModel(settings, system, clock, io = dispatcher).also { settle() }

    private fun TestScope.settle() {
        advanceTimeBy(1)
        runCurrent()
    }

    private fun app(label: String) = LaunchableApp("pkg.${label.lowercase()}", "pkg.${label.lowercase()}.Main", label)

    @Test
    fun `before Plex there is nothing to pick, so it shows the first-run state`() = runTest(dispatcher) {
        val state = viewModel(settings()).state.value

        assertNull(state.content)
        assertNull(state.pick)
        assertEquals(PlexStatus.NOT_CONNECTED, state.plexStatus)
    }

    @Test
    fun `the sample switch shows sample content`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)

        val state = viewModel(settings).state.value

        assertEquals("Severance", state.pick?.title)
        assertEquals("Heat", state.lens?.pick?.title)
    }

    @Test
    fun `not tonight swaps the pick, offers undo, and the offer expires after 8 seconds`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        val clock = TestClock(mondayEvening)
        val vm = viewModel(settings, clock)

        vm.notTonight()
        settle()
        assertEquals("Ocean's Eleven", vm.state.value.pick?.title)
        assertEquals("Severance", vm.state.value.pickState.undo?.skippedTitle)

        clock.advance(PickState.UNDO_WINDOW_MILLIS)
        advanceTimeBy(PickState.UNDO_WINDOW_MILLIS)
        runCurrent()
        assertNull(vm.state.value.pickState.undo)
        assertEquals("Ocean's Eleven", vm.state.value.pick?.title)
    }

    @Test
    fun `undo brings the skipped pick back`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        val vm = viewModel(settings)

        vm.notTonight()
        settle()
        vm.undo()
        settle()

        assertEquals("Severance", vm.state.value.pick?.title)
        assertNull(vm.state.value.pickState.undo)
    }

    @Test
    fun `greeting and context mode follow the clock and the name`() = runTest(dispatcher) {
        val settings = settings()
        settings.setName("Alex")

        val state = viewModel(settings).state.value

        assertEquals("Good evening, Alex", state.greeting)
        assertEquals(ContextMode.WEEKNIGHT_EVENING, state.mode)
        assertEquals(Palette.DefaultAccent, state.accent)
    }

    @Test
    fun `late night dims the accent`() = runTest(dispatcher) {
        val state = viewModel(settings(), TestClock(Instant.parse("2026-10-26T23:48:00Z"))).state.value

        assertEquals(ContextMode.LATE_NIGHT, state.mode)
        assertEquals(Palette.lateNight(Palette.DefaultAccent), state.accent)
    }

    @Test
    fun `launching an app moves it up the launchpad`() = runTest(dispatcher) {
        val apps = listOf(app("Netflix"), app("Plezy"), app("YouTube"))
        val vm = viewModel(settings(), system = FakeSystem(apps = apps))
        assertEquals("Netflix", vm.state.value.launchpad.first().label)

        vm.launched(app("YouTube"))
        settle()

        assertEquals("YouTube", vm.state.value.launchpad.first().label)
        assertEquals(listOf("YouTube"), vm.state.value.mostUsed.map { it.label })
    }

    @Test
    fun `refresh notices the stock launcher coming back`() = runTest(dispatcher) {
        val system = FakeSystem()
        val vm = viewModel(settings(), system = system)
        assertTrue(!vm.state.value.stockLauncherBack)

        system.stock = PackageState.ENABLED
        vm.refresh()
        settle()

        assertTrue(vm.state.value.stockLauncherBack)
        assertNotNull(vm.state.value.now)
    }

    @Test
    fun `the home is worked out on its compute dispatcher, so a slow TV keeps drawing meanwhile`() = runTest(dispatcher) {
        val settings = settings()
        settings.setName("Alex")
        val compute = CountingDispatcher(dispatcher)
        val clock = TestClock(mondayEvening)

        val vm = HomeViewModel(settings, FakeSystem(), clock, io = dispatcher, compute = compute).also { settle() }

        assertEquals("Good evening, Alex", vm.state.value.greeting)
        val sent = compute.sent
        assertTrue(sent > 0)

        clock.advance(60_000)
        vm.tick()
        settle()

        assertTrue("a new minute is worked out there too", compute.sent > sent)
    }

    @Test
    fun `the top bar shows the cached weather, and hides it once it's over 3 hours old`() = runTest(dispatcher) {
        val clock = TestClock(mondayEvening)
        val reading = WeatherReading(celsius = 12.2, code = 3, isDay = false, fetchedAtMillis = mondayEvening.toEpochMilli())
        val place = Place("Portland", "Oregon", "United States", 45.52, -122.68)
        val weather = MutableStateFlow(WeatherState(place, TemperatureUnit.FAHRENHEIT, reading))
        val vm = HomeViewModel(settings(), FakeSystem(), clock, io = dispatcher, weather = weather).also { settle() }

        assertEquals("54°", vm.state.value.weatherNow?.temperature)
        assertEquals("Overcast", vm.state.value.weatherNow?.condition)

        clock.advance(WeatherRepository.SHOW_FOR_MILLIS + 60_000)
        vm.tick()
        settle()
        assertNull(vm.state.value.weatherNow)
        assertEquals(place, vm.state.value.weather.place)
    }

    @Test
    fun `the lens card follows the time of day, and Next runs on into the next lens`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        settings.setLensShuffle(false)
        val vm = viewModel(settings)
        assertEquals(Lens.THROWBACK, vm.state.value.lens?.lens)

        repeat(3) { vm.nextLensPick() }
        settle()

        assertEquals(Lens.CLASSIC, vm.state.value.lens?.lens)
        assertEquals("Jaws", vm.state.value.lens?.pick?.title)
    }

    @Test
    fun `Another lens jumps lenses, and a lens that's off is skipped`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        settings.setLensShuffle(false)
        settings.setLensEnabled(Lens.CLASSIC, false)
        val vm = viewModel(settings)

        vm.nextLens()
        settle()

        assertEquals(Lens.FAMILY, vm.state.value.lens?.lens)
    }

    @Test
    fun `Shuffle is on by default, so Next goes to another lens, and the lead's best still comes first`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        val vm = viewModel(settings)
        assertTrue(vm.state.value.lensPrefs.shuffle)
        assertEquals("Heat", vm.state.value.lens?.pick?.title)
        val seen = mutableListOf(vm.state.value.lens!!)

        repeat(6) {
            vm.nextLensPick()
            settle()
            seen += vm.state.value.lens!!
        }

        assertTrue(seen.map { it.lens }.zipWithNext().none { (a, b) -> a == b })
    }

    @Test
    fun `Back brings back what the card showed, then goes on back through the rotation`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        settings.setLensShuffle(false)
        val vm = viewModel(settings)
        vm.nextLens()
        settle()
        vm.nextLensPick()
        settle()
        assertEquals("Ocean's Eleven", vm.state.value.lens?.pick?.title)

        vm.previousLensPick()
        settle()
        assertEquals("Jaws", vm.state.value.lens?.pick?.title)
        vm.previousLensPick()
        settle()
        assertEquals("before Another lens", "Heat", vm.state.value.lens?.pick?.title)

        // Past what it remembers: the rotation's title before, round to the last lens's last.
        vm.previousLensPick()
        settle()
        assertEquals(Lens.COMFORT, vm.state.value.lens?.lens)
        assertEquals("The Office", vm.state.value.lens?.pick?.title)
    }

    @Test
    fun `Back skips a title hidden with Don't show this`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        settings.setLensShuffle(false)
        val vm = viewModel(settings)
        vm.nextLensPick()
        settle()
        assertEquals("Amélie", vm.state.value.lens?.pick?.title)

        vm.dontShowLensPick()
        settle()
        vm.previousLensPick()
        settle()

        assertEquals("Heat", vm.state.value.lens?.pick?.title)
    }

    @Test
    fun `a title on the card counts once a day, and only from Plex`() = runTest(dispatcher) {
        val feedback = FakeFeedback()
        val clock = TestClock(mondayEvening)
        val vm = HomeViewModel(
            settings(), FakeSystem(), clock, io = dispatcher,
            plexLink = flowOf(linked), plexSnapshots = { flowOf(engineRan) }, suggestions = feedback,
        ).also { settle() }
        val jaws = vm.state.value.lens!!.pick

        vm.lensShown(jaws)
        vm.lensShown(jaws)
        settle()
        assertEquals(listOf("jaws"), feedback.shown)

        clock.advance(24 * 3_600_000L)
        vm.lensShown(jaws)
        settle()
        assertEquals("the next day, again", listOf("jaws", "jaws"), feedback.shown)

        val samples = FakeFeedback()
        val sample = settings()
        sample.setShowSampleContent(true)
        val onSamples = HomeViewModel(sample, FakeSystem(), TestClock(mondayEvening), io = dispatcher, suggestions = samples).also { settle() }
        onSamples.lensShown(onSamples.state.value.lens!!.pick)
        settle()
        assertTrue(samples.shown.isEmpty())
    }

    @Test
    fun `Settings › Lenses' rotation, speed, Shuffle and Move on reach the home`() = runTest(dispatcher) {
        val settings = settings()
        val vm = viewModel(settings)
        assertEquals(LensPrefs(), vm.state.value.lensPrefs)

        vm.setLensRotate(false)
        vm.setLensSpeed(5)
        vm.setLensShuffle(false)
        vm.setLensMoveOn(LensMoveOn.WEEK)
        settle()

        assertEquals(LensPrefs(rotate = false, speed = 5, shuffle = false, moveOn = LensMoveOn.WEEK), vm.state.value.lensPrefs)
        assertNull(vm.state.value.lensPrefs.rotateSeconds)
    }

    @Test
    fun `the lens card never shows Tonight's Pick, even after Not tonight`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)
        settings.setLensShuffle(false)
        val vm = viewModel(settings, TestClock(Instant.parse("2026-10-26T14:00:00Z")))
        vm.nextLensPick()
        settle()
        assertEquals("Ocean's Eleven", vm.state.value.lens?.pick?.title)

        vm.notTonight()
        settle()

        assertEquals("Ocean's Eleven", vm.state.value.pick?.title)
        assertEquals(Lens.CLASSIC, vm.state.value.lens?.lens)
        assertEquals("Jaws", vm.state.value.lens?.pick?.title)
    }

    @Test
    fun `Seasonal shows the season's name on Friday and Saturday evenings`() = runTest(dispatcher) {
        val settings = settings()
        settings.setShowSampleContent(true)

        val state = viewModel(settings, TestClock(Instant.parse("2026-10-30T20:00:00Z"))).state.value

        assertEquals(Lens.SEASONAL, state.lens?.lens)
        assertEquals("Halloween", state.lens?.title)
    }

    @Test
    fun `the top bar says Plex once a server is linked, and the hero gets the sign-in code`() = runTest(dispatcher) {
        val link = MutableStateFlow(PlexLink())
        val signIn = MutableStateFlow<PlexSignIn?>(PlexSignIn.ShowingCode("K7QF", Long.MAX_VALUE))
        val vm = HomeViewModel(settings(), FakeSystem(), TestClock(mondayEvening), io = dispatcher, plexLink = link, plexSignIn = signIn)
            .also { settle() }
        assertEquals(PlexStatus.NOT_CONNECTED, vm.state.value.plexStatus)
        assertEquals(PlexSignIn.ShowingCode("K7QF", Long.MAX_VALUE), vm.state.value.plex.signIn)

        link.value = PlexLink(
            PlexAccount(1, "uuid", "example-user", "Alex"),
            LinkedServer("Basement", "aaaa", "https://x", ConnectionKind.LOCAL),
        )
        signIn.value = PlexSignIn.Linked
        settle()

        assertEquals(PlexStatus.CONNECTED, vm.state.value.plexStatus)
    }

    @Test
    fun `once linked, the cards come from the Plex cache, and offline adds the hero's note (U15)`() = runTest(dispatcher) {
        val linked = PlexLink(
            PlexAccount(1, "uuid", "example-user", "Alex"),
            LinkedServer("Basement", "aaaa", "https://x", ConnectionKind.LOCAL),
        )
        val arrival = ItemEntity(
            "7303", "1", "movie", "Arrival", 2016, 116 * 60_000L, 0, null, mondayEvening.epochSecond - 3 * 86_400, null,
            "PG-13", 8.2, null, null, "/art", "", 0,
        )
        val snapshot = PlexSnapshot(java.time.LocalDate.of(2026, 10, 26), emptyList(), listOf(arrival), emptyList(), emptyList(), 1)
        val sync = MutableStateFlow(PlexSyncStatus())
        val vm = HomeViewModel(
            settings(), FakeSystem(), TestClock(mondayEvening), io = dispatcher,
            plexLink = flowOf(linked), plexSync = sync, plexSnapshots = { flowOf(snapshot) },
        ).also { settle() }

        assertEquals("Arrival", vm.state.value.pick?.title)
        assertEquals("Added to Plex 3 days ago", vm.state.value.pick?.reason)
        assertEquals(PlexStatus.CONNECTED, vm.state.value.plexStatus)
        assertNull(vm.state.value.heroNote)

        sync.value = PlexSyncStatus(offline = true, lastSuccessMillis = mondayEvening.toEpochMilli() - 2 * 3_600_000)
        settle()

        assertEquals(PlexStatus.OFFLINE, vm.state.value.plexStatus)
        assertEquals("Saved pick · Plex is offline, updated 2 h ago", vm.state.value.heroNote)

        sync.value = PlexSyncStatus(failure = "/hubs/continueWatching answered 404", lastSuccessMillis = mondayEvening.toEpochMilli())
        settle()

        assertEquals("the server answered, so it isn't offline", PlexStatus.SYNC_FAILED, vm.state.value.plexStatus)
        assertNull(vm.state.value.heroNote)
    }

    @Test
    fun `while Plex syncs, the top bar says Updating half a second in, once linked`() = runTest(dispatcher) {
        val link = MutableStateFlow(PlexLink())
        val syncing = MutableStateFlow(false)
        val vm = HomeViewModel(settings(), FakeSystem(), TestClock(mondayEvening), io = dispatcher, plexLink = link, plexSyncing = syncing)
            .also { settle() }

        syncing.value = true
        advanceTimeBy(Updating.SHOW_AFTER_MILLIS + 1)
        assertFalse("nothing linked, nothing to update", vm.state.value.plex.updating)

        link.value = PlexLink(
            PlexAccount(1, "uuid", "example-user", "Alex"),
            LinkedServer("Basement", "aaaa", "https://x", ConnectionKind.LOCAL),
        )
        settle()
        assertTrue(vm.state.value.plex.updating)

        syncing.value = false
        advanceTimeBy(Updating.KEEP_MILLIS + 1)
        assertFalse(vm.state.value.plex.updating)
    }

    /** What the view model told the engine. */
    private class FakeFeedback : SuggestionFeedback {
        val recorded = mutableListOf<Triple<String, FeedbackKind, String>>()
        val undone = mutableListOf<FeedbackUndo>()
        var refreshed = 0

        override suspend fun record(titleKey: String, kind: FeedbackKind, scope: String): FeedbackUndo {
            recorded += Triple(titleKey, kind, scope)
            return FeedbackUndo(Profiles.OWNER, titleKey, kind, scope, previous = null)
        }

        override suspend fun undo(undo: FeedbackUndo) {
            undone += undo
        }

        override suspend fun refresh() {
            refreshed++
        }

        val shown = mutableListOf<String>()

        override suspend fun lensShown(titleKey: String) {
            shown += titleKey
        }
    }

    private val linked = PlexLink(PlexAccount(1, "uuid", "example-user", "Alex"), LinkedServer("Basement", "aaaa", "https://x", ConnectionKind.LOCAL))

    private fun title(key: String, name: String) = ItemEntity(
        key, "1", "movie", name, 2016, 116 * 60_000L, 0, null, null, null, "PG-13", 8.2, null, null, "/art/$key", "", 0,
    )

    /** The engine has run: two picks, two titles like the first, a Classic and Up Next. */
    private val engineRan = PlexSnapshot(
        java.time.LocalDate.of(2026, 10, 26),
        continueWatching = listOf(
            ContinueEntity(0, "x1", "episode", "Hello Darkness", "Slow Horses", 4, 2, null, 49 * 60_000L, 8 * 60_000L, "TV-14", null, null, null, showKey = "s2"),
        ),
        newest = emptyList(), classics = emptyList(), throwbacks = emptyList(), itemCount = 6,
        picks = listOf(
            PickEntity(Profiles.OWNER, Surfaces.HERO, 0, "m1", "Because you liked Heat", "", 0),
            PickEntity(Profiles.OWNER, Surfaces.HERO, 1, "m2", "Rated 8.2 · never played", "", 0),
            PickEntity(Profiles.OWNER, Surfaces.moreLike("m1"), 0, "like1", "Also Dena Varga", "", 0),
            PickEntity(Profiles.OWNER, Surfaces.moreLike("m1"), 1, "like2", "Science Fiction · 2010s, like Arrival", "", 0),
            PickEntity(Profiles.OWNER, Surfaces.lens(Lens.CLASSIC), 0, "jaws", "51 years old and you've never seen it", "", 0),
            PickEntity(Profiles.OWNER, Surfaces.UP_NEXT, 0, "x1", "Watched yesterday", "", 0),
        ),
        titles = listOf(title("m1", "Arrival"), title("m2", "Coherence"), title("like1", "Sicario"), title("like2", "Contact"), title("jaws", "Jaws"))
            .associateBy { it.ratingKey },
    )

    private fun TestScope.engineViewModel(feedback: FakeFeedback) = HomeViewModel(
        settings(), FakeSystem(), TestClock(mondayEvening), io = dispatcher,
        plexLink = flowOf(linked), plexSnapshots = { flowOf(engineRan) }, suggestions = feedback,
    ).also { settle() }

    @Test
    fun `Not tonight tells the engine, and Undo takes it back`() = runTest(dispatcher) {
        val feedback = FakeFeedback()
        val vm = engineViewModel(feedback)
        assertEquals("Arrival", vm.state.value.pick?.title)

        vm.notTonight()
        settle()
        assertEquals(listOf(Triple("m1", FeedbackKind.NOT_TONIGHT, "")), feedback.recorded)
        assertEquals("Coherence", vm.state.value.pick?.title)

        vm.undo()
        settle()
        assertEquals(listOf("m1"), feedback.undone.map { it.ratingKey })
        assertEquals("Arrival", vm.state.value.pick?.title)
    }

    @Test
    fun `sample content's Not tonight tells the engine nothing, and each return to the home asks for a new day's run`() = runTest(dispatcher) {
        val feedback = FakeFeedback()
        val settings = settings()
        settings.setShowSampleContent(true)
        val vm = HomeViewModel(settings, FakeSystem(), TestClock(mondayEvening), io = dispatcher, suggestions = feedback).also { settle() }

        vm.notTonight()
        settle()
        vm.refresh()
        settle()

        assertTrue(feedback.recorded.isEmpty())
        assertEquals("once at start, once on refresh", 2, feedback.refreshed)
    }

    @Test
    fun `the hides tell the engine by the show, and Don't show this by its lens`() = runTest(dispatcher) {
        val feedback = FakeFeedback()
        val vm = engineViewModel(feedback)
        val up = vm.state.value.content!!.upNext.single()

        vm.removeFromUpNext(up)
        vm.hideFromQuickWatch(com.phonoodlesramen.actioncenter.content.QuickItem("e7", "Abbott Elementary", "S3E4 · 22m", titleKey = "s3"))
        assertEquals("Jaws", vm.state.value.lens?.pick?.title)
        vm.dontShowLensPick()
        settle()

        assertEquals(
            listOf(
                Triple("s2", FeedbackKind.UP_NEXT_REMOVED, ""),
                Triple("s3", FeedbackKind.QUICK_WATCH_HIDDEN, ""),
                Triple("jaws", FeedbackKind.LENS_HIDDEN, "CLASSIC"),
            ),
            feedback.recorded,
        )
    }

    @Test
    fun `More like this puts titles like the pick on the lens card, and Next runs back to the lenses`() = runTest(dispatcher) {
        val vm = engineViewModel(FakeFeedback())

        assertTrue(vm.moreLikeThis())
        settle()
        assertEquals("More like Arrival", vm.state.value.lens?.title)
        assertEquals("Sicario", vm.state.value.lens?.pick?.title)

        vm.nextLensPick()
        settle()
        assertEquals("Contact", vm.state.value.lens?.pick?.title)

        vm.nextLensPick()
        settle()
        assertEquals("Classic", vm.state.value.lens?.title)
        assertNull(vm.state.value.lens?.moreLike)
    }

    /** Maya, a made-up kid of 9, is on. */
    private val maya = ActiveProfile("kid-maya", "Maya", kid = true, color = 0xFFFF8A65, age = 9, ceiling = setOf("G", "PG"), apps = emptySet())

    private class KidOn(private val kid: ActiveProfile) : ProfileStore by ProfileStore.None {
        override fun current(ownerName: Flow<String>, today: Flow<LocalDate>): Flow<ActiveProfile> = flowOf(kid)
        override fun profiles(ownerName: Flow<String>, today: Flow<LocalDate>) = flowOf(listOf(ActiveProfile.owner(""), kid))
    }

    /** Monday, 2 PM: daytime, when Classic leads yours. */
    private val mondayAfternoon = Instant.parse("2026-10-26T14:00:00Z")

    /** Maya's run: a pick, and a title on Throwback (unless [throwback] is false), Classic and For the family. */
    private fun kidRan(throwback: Boolean = true): PlexSnapshot {
        fun row(surface: String, key: String, reason: String) = PickEntity("kid-maya", surface, 0, key, reason, "", 0)
        return PlexSnapshot(
            java.time.LocalDate.of(2026, 10, 26), emptyList(), emptyList(), emptyList(), emptyList(), itemCount = 4,
            picks = listOfNotNull(
                row(Surfaces.HERO, "m1", "Because you loved Cloud Kingdom"),
                row(Surfaces.lens(Lens.THROWBACK), "tb", "One year ago today you watched").takeIf { throwback },
                row(Surfaces.lens(Lens.CLASSIC), "cl", "46 years old and you've never seen it"),
                row(Surfaces.lens(Lens.FAMILY), "fam", "Rated G · nobody's seen it yet"),
            ),
            titles = listOf(title("m1", "Paper Boats"), title("tb", "Puddle Jumpers"), title("cl", "Lighthouse Keeper"), title("fam", "Sky Racers"))
                .associateBy { it.ratingKey },
        )
    }

    private fun TestScope.kidViewModel(
        snapshot: PlexSnapshot = kidRan(),
        settings: SettingsRepository = settings(),
        clock: TestClock = TestClock(mondayAfternoon),
        feedback: SuggestionFeedback = SuggestionFeedback.None,
        profile: ActiveProfile = maya,
    ) = HomeViewModel(
        settings, FakeSystem(), clock, io = dispatcher, plexLink = flowOf(linked), plexSnapshots = { flowOf(snapshot) },
        suggestions = feedback, profileStore = KidOn(profile),
    ).also { settle() }

    @Test
    fun `a kid's Remember when leads whenever they have one, else the time's lens leads, as yours`() = runTest(dispatcher) {
        assertEquals("Classic leads your daytime", Lens.CLASSIC, kidViewModel(profile = ActiveProfile.owner("Alex")).state.value.lens?.lens)

        val theirs = kidViewModel().state.value.lens
        assertEquals(Lens.THROWBACK, theirs?.lens)
        assertEquals("Puddle Jumpers", theirs?.pick?.title)

        assertEquals("without a Throwback, the time's", Lens.CLASSIC, kidViewModel(kidRan(throwback = false)).state.value.lens?.lens)
    }

    @Test
    fun `Settings › Lenses applies to kids too`() = runTest(dispatcher) {
        val settings = settings()
        settings.setLensEnabled(Lens.THROWBACK, false)
        assertEquals(Lens.CLASSIC, kidViewModel(settings = settings).state.value.lens?.lens)

        settings.setLensEnabled(Lens.CLASSIC, false)
        settle()
        assertEquals("Throwback and Classic off: the next on, in Settings order", Lens.FAMILY, kidViewModel(settings = settings).state.value.lens?.lens)
    }

    @Test
    fun `a kid's Another lens and Don't show this work as yours, and Remember when leads again once the time moves on`() = runTest(dispatcher) {
        val feedback = FakeFeedback()
        val clock = TestClock(mondayAfternoon)
        val settings = settings()
        settings.setLensShuffle(false)
        val vm = kidViewModel(clock = clock, feedback = feedback, settings = settings)

        vm.nextLens()
        settle()
        assertEquals(Lens.CLASSIC, vm.state.value.lens?.lens)

        vm.dontShowLensPick()
        settle()
        assertEquals(listOf(Triple("cl", FeedbackKind.LENS_HIDDEN, "CLASSIC")), feedback.recorded)
        assertEquals("the card moves on", Lens.FAMILY, vm.state.value.lens?.lens)

        // An hour on it's still the afternoon: the lens picked by hand stays.
        clock.advance(3_600_000)
        vm.tick()
        settle()
        assertEquals(Lens.FAMILY, vm.state.value.lens?.lens)

        // 6:30 PM: a weeknight evening.
        clock.advance(3 * 3_600_000 + 30 * 60_000)
        vm.tick()
        settle()
        assertEquals(Lens.THROWBACK, vm.state.value.lens?.lens)
    }

    @Test
    fun `the guide player's saved spot reaches the home, and forgetting it clears it`() = runTest(dispatcher) {
        val spot = SavedSpot("tv-mix", 1_000L, "7303", 12 * 60_000L, 2_000L)
        val spots = MemorySpots(spot)
        val vm = HomeViewModel(settings(), FakeSystem(), TestClock(mondayEvening), io = dispatcher, spots = spots).also { settle() }
        assertEquals(spot, vm.spot.value)
        // Resume forgets only the spot it took: a newer one stays.
        vm.forgetSpot(only = spot.copy(positionMs = 1))
        settle()
        assertEquals(spot, vm.spot.value)
        vm.forgetSpot()
        settle()
        assertNull(vm.spot.value)
    }

    @Test
    fun `every refresh reads the last app you watched, against the apps on this TV`() = runTest(dispatcher) {
        val youTubeTv = LaunchableApp("com.google.android.youtube.tvunplugged", "com.google.android.apps.youtube.tvunplugged.activity.MainActivity", "YouTube TV")
        val system = FakeSystem(apps = listOf(youTubeTv))
        val asked = mutableListOf<List<String>>()
        var visit = AppVisit(youTubeTv.packageName, frontAt = 1_000L, leftAt = 2_000L)
        val recent = RecentApps { apps -> asked += apps.map { it.packageName }; visit }
        val vm = HomeViewModel(settings(), system, TestClock(mondayEvening), io = dispatcher, recentApps = recent).also { settle() }
        assertEquals(visit, vm.tv.value.visit)
        assertEquals(listOf(listOf(youTubeTv.packageName)), asked)
        visit = visit.copy(frontAt = 3_000L, leftAt = 4_000L)
        vm.refresh()
        settle()
        assertEquals(visit, vm.tv.value.visit)
    }

    @Test
    fun `channels opened and a forgotten visit reach the home`() = runTest(dispatcher) {
        val store = MemoryTvChannels()
        val vm = HomeViewModel(settings(), FakeSystem(), TestClock(mondayEvening), io = dispatcher, tvChannels = store).also { settle() }
        val nbc = TunedChannel("NBC", "https://tv.youtube.com/watch/nbc", 5_000L)
        vm.tunedTv(nbc)
        vm.forgetVisit(AppVisit("com.google.android.youtube.tvunplugged", 1_000L, 2_000L))
        settle()
        assertEquals(listOf(nbc), vm.tv.value.channels)
        assertEquals(1_000L, vm.tv.value.forgotten)
    }
}
