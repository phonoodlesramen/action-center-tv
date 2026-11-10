package com.phonoodlesramen.actioncenter.weather

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WeatherRepositoryTest {
    private val server = MockWebServer()
    private var now = 1_793_000_000_000L
    private val portland = Place("Portland", "Oregon", "United States", 45.52, -122.68)

    @Before
    fun setUp() = server.start()

    @After
    fun tearDown() = server.close()

    private lateinit var store: DataStore<Preferences>

    private fun TestScope.repository(): WeatherRepository {
        val file = File(Files.createTempDirectory("weather").toFile(), "weather.preferences_pb")
        store = PreferenceDataStoreFactory.create(scope = backgroundScope) { file }
        val client = OpenMeteoClient(OkHttpClient(), server.url("/v1/forecast"), server.url("/v1/search"), clock = { now })
        return WeatherRepository(store, client, defaultUnit = TemperatureUnit.FAHRENHEIT)
    }

    private fun respondForecast() = server.enqueue(
        MockResponse.Builder().body(javaClass.getResource("/openmeteo/forecast.json")!!.readText()).build(),
    )

    @Test
    fun `nothing is set at first, in the locale's unit`() = runTest {
        assertEquals(WeatherState(unit = TemperatureUnit.FAHRENHEIT), repository().state.first())
    }

    @Test
    fun `without a place, refresh sends nothing`() = runTest {
        val repo = repository()

        assertEquals(Refresh.NO_PLACE, repo.refresh())
        assertFalse(repo.needsRefresh(now))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `refresh stores a reading for the place, sun times included, which goes stale after 30 minutes`() = runTest {
        val repo = repository()
        repo.setPlace(portland)
        assertTrue(repo.needsRefresh(now))
        respondForecast()

        assertEquals(Refresh.UPDATED, repo.refresh())

        val state = repo.state.first()
        assertEquals(portland, state.place)
        assertEquals(
            WeatherReading(15.1, 0, isDay = false, fetchedAtMillis = now, sunrise = LocalDateTime.of(2026, 10, 26, 7, 19), sunset = LocalDateTime.of(2026, 10, 26, 17, 59)),
            state.reading,
        )
        assertEquals("45.52", server.takeRequest().url.queryParameter("latitude"))
        assertFalse(repo.needsRefresh(now + 29 * 60_000))
        assertTrue(repo.needsRefresh(now + 30 * 60_000))
    }

    @Test
    fun `a failed refresh keeps the last reading`() = runTest {
        val repo = repository()
        repo.setPlace(portland)
        respondForecast()
        repo.refresh()
        server.enqueue(MockResponse.Builder().code(503).build())

        assertEquals(Refresh.FAILED, repo.refresh())

        assertEquals(15.1, repo.state.first().reading!!.celsius, 0.0)
    }

    @Test
    fun `a new place drops the old place's reading`() = runTest {
        val repo = repository()
        repo.setPlace(portland)
        respondForecast()
        repo.refresh()

        repo.setPlace(Place("Bend", "Oregon", "United States", 44.06, -121.31))

        assertNull(repo.state.first().reading)
    }

    @Test
    fun `the unit is saved`() = runTest {
        val repo = repository()

        repo.setUnit(TemperatureUnit.CELSIUS)

        assertEquals(TemperatureUnit.CELSIUS, repo.state.first().unit)
    }

    @Test
    fun `a reading cached before sunrise and sunset were asked for still loads, without them`() = runTest {
        val repo = repository()
        store.edit {
            it[stringPreferencesKey("place")] = Json.encodeToString(portland)
            it[stringPreferencesKey("reading")] = """{"celsius":12.2,"code":3,"isDay":false,"fetchedAtMillis":$now}"""
        }

        assertEquals(WeatherReading(12.2, 3, isDay = false, fetchedAtMillis = now), repo.state.first().reading)
    }

    @Test
    fun `an unreadable saved value is ignored, not a crash`() = runTest {
        val repo = repository()
        store.edit {
            it[stringPreferencesKey("place")] = "{not json"
            it[stringPreferencesKey("unit")] = "KELVIN"
        }

        assertEquals(WeatherState(unit = TemperatureUnit.FAHRENHEIT), repo.state.first())
        assertEquals(Refresh.NO_PLACE, repo.refresh())
    }
}
