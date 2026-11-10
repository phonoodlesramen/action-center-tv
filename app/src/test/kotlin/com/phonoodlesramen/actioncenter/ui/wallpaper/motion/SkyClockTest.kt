package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import com.phonoodlesramen.actioncenter.weather.Place
import com.phonoodlesramen.actioncenter.weather.WeatherReading
import com.phonoodlesramen.actioncenter.weather.WeatherState
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyClockTest {
    /** New York on 26 October 2026, as Open-Meteo gave it (src/test/resources/openmeteo/forecast.json). */
    private val autumn = SunTimes(LocalTime.of(7, 19), LocalTime.of(17, 59))

    private fun phase(hour: Int, minute: Int, sun: SunTimes = autumn) = SkyClock.phase(LocalTime.of(hour, minute), sun)

    @Test
    fun `the day runs night, dawn, day, golden hour, dusk, night`() {
        assertEquals(DayPhase.NIGHT, phase(0, 0))
        assertEquals(DayPhase.NIGHT, phase(6, 33))
        assertEquals(DayPhase.DAWN, phase(6, 34)) // 45 min before sunrise
        assertEquals(DayPhase.DAWN, phase(7, 48))
        assertEquals(DayPhase.DAY, phase(7, 49)) // 30 min after sunrise
        assertEquals(DayPhase.DAY, phase(12, 0))
        assertEquals(DayPhase.DAY, phase(16, 58))
        assertEquals(DayPhase.GOLDEN_HOUR, phase(16, 59)) // the hour before sunset
        assertEquals(DayPhase.GOLDEN_HOUR, phase(17, 58))
        assertEquals(DayPhase.DUSK, phase(17, 59)) // sunset
        assertEquals(DayPhase.DUSK, phase(18, 43))
        assertEquals(DayPhase.NIGHT, phase(18, 44)) // 45 min after sunset
        assertEquals(DayPhase.NIGHT, phase(23, 59))
    }

    @Test
    fun `a very short day keeps its order, with golden hour starting no earlier than day`() {
        val winter = SunTimes(LocalTime.of(10, 0), LocalTime.of(11, 0))

        assertEquals(DayPhase.DAWN, phase(10, 15, winter))
        assertEquals(DayPhase.GOLDEN_HOUR, phase(10, 30, winter))
        assertEquals(DayPhase.GOLDEN_HOUR, phase(10, 59, winter))
        assertEquals(DayPhase.DUSK, phase(11, 0, winter))
    }

    @Test
    fun `each phase's colours peak inside it`() {
        val noon = SkyClock.colors(LocalTime.NOON, autumn)
        assertEquals(SkyClock.palette(DayPhase.DAY), noon)
        assertEquals(SkyClock.palette(DayPhase.NIGHT), SkyClock.colors(LocalTime.MIDNIGHT, autumn))
        assertEquals(SkyClock.palette(DayPhase.GOLDEN_HOUR), SkyClock.colors(LocalTime.of(17, 44), autumn))
        assertEquals(SkyClock.palette(DayPhase.DUSK), SkyClock.colors(LocalTime.of(18, 19), autumn))
        assertEquals(SkyClock.palette(DayPhase.DAWN), SkyClock.colors(LocalTime.of(7, 9), autumn))
    }

    @Test
    fun `between peaks the sky blends a little each minute instead of jumping`() {
        val before = SkyClock.colors(LocalTime.of(17, 30), autumn)
        val after = SkyClock.colors(LocalTime.of(17, 31), autumn)

        assertNotEquals(before, after)
        val step = channelDistance(before.horizon, after.horizon)
        assertTrue("A minute moved the horizon by $step", step in 1..6)
    }

    @Test
    fun `stars show at night and fade out by day`() {
        assertEquals(1f, SkyClock.colors(LocalTime.of(22, 0), autumn).stars, 0f)
        assertEquals(0f, SkyClock.colors(LocalTime.NOON, autumn).stars, 0f)
        val dusk = SkyClock.colors(LocalTime.of(18, 40), autumn).stars
        assertTrue("Dusk stars $dusk", dusk > 0.45f && dusk < 1f)
    }

    @Test
    fun `the top of the sky is always the darkest part, behind the top bar`() {
        for (minute in 0 until 24 * 60 step 10) {
            val sky = SkyClock.colors(LocalTime.of(minute / 60, minute % 60), autumn)
            assertTrue("At $minute min", luma(sky.top) <= luma(sky.middle) && luma(sky.middle) <= luma(sky.horizon))
        }
    }

    @Test
    fun `sun times come from the reading, else 6-30 and 19-00`() {
        val portland = Place("Portland", "Oregon", "United States", 45.52, -122.68)
        val reading = WeatherReading(
            celsius = 12.2,
            code = 3,
            isDay = false,
            fetchedAtMillis = 0,
            sunrise = LocalDateTime.of(2026, 10, 26, 7, 42),
            sunset = LocalDateTime.of(2026, 10, 26, 18, 6),
        )

        assertEquals(SunTimes(LocalTime.of(7, 42), LocalTime.of(18, 6)), SunTimes.from(WeatherState(portland, reading = reading)))
        assertEquals(SunTimes.FALLBACK, SunTimes.from(WeatherState()))
        assertEquals(SunTimes.FALLBACK, SunTimes.from(WeatherState(place = portland)))
        // Cached before Phase 2.5, or the polar night: no sun times in the reading.
        assertEquals(SunTimes.FALLBACK, SunTimes.from(WeatherState(portland, reading = reading.copy(sunrise = null, sunset = null))))
        assertEquals(LocalTime.of(6, 30), SunTimes.FALLBACK.sunrise)
        assertEquals(LocalTime.of(19, 0), SunTimes.FALLBACK.sunset)
    }

    private fun luma(argb: Int) = 0.2126 * (argb shr 16 and 0xFF) + 0.7152 * (argb shr 8 and 0xFF) + 0.0722 * (argb and 0xFF)

    private fun channelDistance(a: Int, b: Int) = listOf(16, 8, 0).maxOf { kotlin.math.abs((a shr it and 0xFF) - (b shr it and 0xFF)) }
}
