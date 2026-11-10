package com.phonoodlesramen.actioncenter.weather

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeatherDisplayTest {
    private val portland = Place("Portland", "Oregon", "United States", 45.52, -122.68)
    private val now = 1_793_000_000_000L
    private fun minutesAgo(minutes: Long) = now - minutes * 60_000

    private fun state(celsius: Double = 12.2, code: Int = 3, minutesOld: Long = 12, unit: TemperatureUnit = TemperatureUnit.FAHRENHEIT) =
        WeatherState(portland, unit, WeatherReading(celsius, code, isDay = false, fetchedAtMillis = minutesAgo(minutesOld)))

    @Test
    fun `shows the temperature in the chosen unit, rounded`() {
        assertEquals("54°", state(celsius = 12.2).display(now)?.temperature)
        assertEquals("12°", state(celsius = 12.2, unit = TemperatureUnit.CELSIUS).display(now)?.temperature)
        assertEquals("-4°", state(celsius = -20.0).display(now)?.temperature)
    }

    @Test
    fun `says how old the reading is`() {
        assertEquals("updated just now", state(minutesOld = 0).display(now)?.updated)
        assertEquals("updated 12 min ago", state(minutesOld = 12).display(now)?.updated)
        assertEquals("updated 2 h ago", state(minutesOld = 150).display(now)?.updated)
    }

    @Test
    fun `hides a reading older than 3 hours rather than show it as current`() {
        assertEquals("54°", state(minutesOld = 180).display(now)?.temperature)
        assertNull(state(minutesOld = 181).display(now))
    }

    @Test
    fun `nothing to show without a place or a reading`() {
        assertNull(WeatherState().display(now))
        assertNull(WeatherState(place = portland).display(now))
    }

    @Test
    fun `WMO codes map to an icon family and a label`() {
        assertEquals(Condition(Sky.CLEAR, "Clear"), WeatherCodes.condition(0))
        assertEquals(Sky.PARTLY_CLOUDY, WeatherCodes.condition(2).sky)
        assertEquals(Condition(Sky.CLOUDY, "Overcast"), WeatherCodes.condition(3))
        assertEquals(Sky.FOG, WeatherCodes.condition(48).sky)
        assertEquals(Condition(Sky.DRIZZLE, "Freezing drizzle"), WeatherCodes.condition(57))
        assertEquals(Condition(Sky.RAIN, "Rain showers"), WeatherCodes.condition(81))
        assertEquals(Condition(Sky.SNOW, "Snow showers"), WeatherCodes.condition(86))
        assertEquals(Condition(Sky.THUNDER, "Thunderstorm"), WeatherCodes.condition(97))
        assertEquals(Condition(Sky.THUNDER, "Thunderstorm with hail"), WeatherCodes.condition(99))
        assertEquals("Unknown", WeatherCodes.condition(42).label)
    }

    @Test
    fun `Fahrenheit for the US, Celsius elsewhere`() {
        assertEquals(TemperatureUnit.FAHRENHEIT, TemperatureUnit.forLocale(Locale.US))
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.forLocale(Locale.UK))
        assertEquals(TemperatureUnit.CELSIUS, TemperatureUnit.forLocale(Locale.GERMANY))
    }

    @Test
    fun `a place describes itself without repeating parts`() {
        assertEquals("Portland, Oregon, United States", portland.description)
        assertEquals("Singapore", Place("Singapore", "Singapore", "Singapore", 1.29, 103.85).description)
        assertEquals("Reykjavík, Iceland", Place("Reykjavík", null, "Iceland", 64.1, -21.9).description)
    }
}
