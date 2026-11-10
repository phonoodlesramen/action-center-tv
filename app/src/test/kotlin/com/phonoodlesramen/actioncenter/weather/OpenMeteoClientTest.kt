package com.phonoodlesramen.actioncenter.weather

import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Against responses recorded from the live API (src/test/resources/openmeteo). */
class OpenMeteoClientTest {
    private val server = MockWebServer()
    private lateinit var client: OpenMeteoClient

    @Before
    fun setUp() {
        server.start()
        client = OpenMeteoClient(
            http = OkHttpClient(),
            forecastUrl = server.url("/v1/forecast"),
            geocodingUrl = server.url("/v1/search"),
            clock = { NOW },
        )
    }

    @After
    fun tearDown() = server.close()

    private fun respond(fixture: String, code: Int = 200) = server.enqueue(
        MockResponse.Builder().code(code).body(javaClass.getResource("/openmeteo/$fixture")!!.readText()).build(),
    )

    private suspend fun failure(block: suspend () -> Unit): OpenMeteoException = try {
        block()
        throw AssertionError("Expected an OpenMeteoException")
    } catch (e: OpenMeteoException) {
        e
    }

    @Test
    fun `current weather asks for temperature, code and day or night, and today's sunrise and sunset`() = runTest {
        respond("forecast.json")

        val reading = client.current(40.71, -74.01)

        assertEquals(
            WeatherReading(
                celsius = 15.1,
                code = 0,
                isDay = false,
                fetchedAtMillis = NOW,
                sunrise = LocalDateTime.of(2026, 10, 26, 7, 19),
                sunset = LocalDateTime.of(2026, 10, 26, 17, 59),
            ),
            reading,
        )
        val url = server.takeRequest().url
        assertEquals("/v1/forecast", url.encodedPath)
        assertEquals("40.71", url.queryParameter("latitude"))
        assertEquals("-74.01", url.queryParameter("longitude"))
        assertEquals("temperature_2m,weather_code,is_day", url.queryParameter("current"))
        assertEquals("sunrise,sunset", url.queryParameter("daily"))
        // Daily values need a time zone; auto gives the place's own clock.
        assertEquals("auto", url.queryParameter("timezone"))
        assertEquals("1", url.queryParameter("forecast_days"))
    }

    @Test
    fun `in the polar night sunrise and sunset are both midnight, which means no sun times`() = runTest {
        respond("forecast-polar-night.json")

        val reading = client.current(78.22, 15.65)

        assertEquals(3, reading.code)
        assertNull(reading.sunrise)
        assertNull(reading.sunset)
    }

    @Test
    fun `a response without daily values is still a reading, without sun times`() = runTest {
        respond("forecast-current-only.json")

        assertEquals(WeatherReading(celsius = 19.1, code = 0, isDay = true, fetchedAtMillis = NOW), client.current(40.71, -74.01))
    }

    @Test
    fun `an unreadable sun time costs only the sun times`() = runTest {
        val body = javaClass.getResource("/openmeteo/forecast.json")!!.readText().replace("2026-10-26T07:19", "soon")
        server.enqueue(MockResponse.Builder().body(body).build())

        val reading = client.current(40.71, -74.01)

        assertEquals(15.1, reading.celsius, 0.0)
        assertNull(reading.sunrise)
        assertNull(reading.sunset)
    }

    @Test
    fun `search returns places with region and country`() = runTest {
        respond("search.json")

        val places = client.search("  Portland ")

        assertEquals(
            listOf("Portland, Oregon, United States", "Portland, Maine, United States"),
            places.map { it.description },
        )
        assertEquals(45.52345, places.first().latitude, 0.0)
        val url = server.takeRequest().url
        assertEquals("Portland", url.queryParameter("name"))
        assertEquals("6", url.queryParameter("count"))
        assertEquals("json", url.queryParameter("format"))
    }

    @Test
    fun `no matches means no results key, which is an empty list`() = runTest {
        respond("search-empty.json")

        assertEquals(emptyList<Place>(), client.search("zzzqqqxx"))
    }

    @Test
    fun `a blank search sends nothing`() = runTest {
        assertEquals(emptyList<Place>(), client.search("   "))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an HTTP error carries Open-Meteo's reason`() = runTest {
        respond("error.json", code = 400)

        val error = failure { client.current(999.0, 0.0) }

        assertTrue(error.message, error.message!!.startsWith("Latitude must be in range"))
    }

    @Test
    fun `a body that isn't the expected JSON is an OpenMeteoException`() = runTest {
        server.enqueue(MockResponse.Builder().body("<html>maintenance</html>").build())

        failure { client.current(1.0, 2.0) }
    }

    @Test
    fun `no network is an OpenMeteoException`() = runTest {
        server.close()

        failure { client.search("Portland") }
    }

    private companion object {
        const val NOW = 1_793_000_000_000L
    }
}
