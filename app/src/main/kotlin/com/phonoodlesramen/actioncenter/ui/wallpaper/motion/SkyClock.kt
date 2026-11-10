package com.phonoodlesramen.actioncenter.ui.wallpaper.motion

import com.phonoodlesramen.actioncenter.weather.WeatherState
import java.time.LocalTime
import kotlin.math.max

/** Living sky's times of day, in the order the day runs. */
enum class DayPhase { NIGHT, DAWN, DAY, GOLDEN_HOUR, DUSK }

/** When the sun rises and sets, on the home's clock. */
data class SunTimes(val sunrise: LocalTime, val sunset: LocalTime) {
    companion object {
        /** Without a place or a reading: a typical day (the brief). */
        val FALLBACK = SunTimes(LocalTime.of(6, 30), LocalTime.of(19, 0))

        /**
         * Today's sunrise and sunset from the weather reading, else [FALLBACK]. Open-Meteo
         * gives them on the place's clock, the same as the Shield's for a home TV. A reading
         * a few days old is still close: the times move a minute or two a day.
         */
        fun from(weather: WeatherState): SunTimes {
            val reading = weather.reading?.takeIf { weather.place != null } ?: return FALLBACK
            val sunrise = reading.sunrise?.toLocalTime() ?: return FALLBACK
            val sunset = reading.sunset?.toLocalTime() ?: return FALLBACK
            return if (sunrise < sunset) SunTimes(sunrise, sunset) else FALLBACK
        }
    }
}

/**
 * A sky to draw, as opaque ARGB: a vertical gradient from [top] (behind the top bar, so
 * always the darkest) through [middle] to the [horizon], a [glow] low in the sky, and how
 * bright the [stars] are (0 hides them).
 */
data class SkyColors(
    val top: Int,
    val middle: Int,
    val horizon: Int,
    val glow: Int,
    val glowAlpha: Float,
    val stars: Float,
)

/**
 * The time of day for Living sky, from the real sunrise and sunset. Pure, so the phases
 * are unit-tested; the wallpaper only draws what [colors] returns.
 */
object SkyClock {
    /**
     * - **Dawn:** from 45 minutes before sunrise to 30 minutes after.
     * - **Day:** until golden hour.
     * - **Golden hour:** the hour before sunset (shorter on a very short day).
     * - **Dusk:** sunset to 45 minutes after.
     * - **Night:** the rest.
     */
    fun phase(time: LocalTime, sun: SunTimes): DayPhase {
        val t = minutes(time)
        val rise = minutes(sun.sunrise)
        val set = minutes(sun.sunset)
        val dayStarts = rise + DAWN_AFTER
        val goldenStarts = max(dayStarts, set - GOLDEN_HOUR)
        return when {
            t < rise - DAWN_BEFORE -> DayPhase.NIGHT
            t < dayStarts -> DayPhase.DAWN
            t < goldenStarts -> DayPhase.DAY
            t < set -> DayPhase.GOLDEN_HOUR
            t < set + DUSK_AFTER -> DayPhase.DUSK
            else -> DayPhase.NIGHT
        }
    }

    /**
     * The sky at [time]: each phase's colours peak inside it and blend into the next, so
     * the sky changes a little every minute rather than jumping at a boundary.
     */
    fun colors(time: LocalTime, sun: SunTimes): SkyColors {
        val t = minutes(time)
        val keys = keyframes(minutes(sun.sunrise), minutes(sun.sunset))
        if (t <= keys.first().first) return keys.first().second
        for (i in 1 until keys.size) {
            val (end, to) = keys[i]
            if (t <= end) {
                val (start, from) = keys[i - 1]
                val span = end - start
                return if (span <= 0f) to else blend(from, to, (t - start) / span)
            }
        }
        return keys.last().second
    }

    /** The palette each phase peaks at. */
    fun palette(phase: DayPhase): SkyColors = when (phase) {
        // Even the brightest horizon stays dark enough for white text on the home (about 4.5:1).
        DayPhase.NIGHT -> SkyColors(0xFF060A14.toInt(), 0xFF0A1222.toInt(), 0xFF142039.toInt(), 0xFF1B2A4A.toInt(), 0f, 1f)
        DayPhase.DAWN -> SkyColors(0xFF141B36.toInt(), 0xFF33305A.toInt(), 0xFF7A4E58.toInt(), 0xFFF2A07B.toInt(), 0.16f, 0.25f)
        DayPhase.DAY -> SkyColors(0xFF133A63.toInt(), 0xFF1F4D7C.toInt(), 0xFF2F6491.toInt(), 0xFFBFD8EA.toInt(), 0.05f, 0f)
        DayPhase.GOLDEN_HOUR -> SkyColors(0xFF1B2A4D.toInt(), 0xFF4E3C58.toInt(), 0xFF82553A.toInt(), 0xFFFFC07A.toInt(), 0.18f, 0f)
        DayPhase.DUSK -> SkyColors(0xFF0F1630.toInt(), 0xFF2E2552.toInt(), 0xFF6A3A58.toInt(), 0xFFE07A6A.toInt(), 0.12f, 0.45f)
    }

    /** Minutes of the day where each palette peaks, never going backwards on a short day. */
    private fun keyframes(rise: Float, set: Float): List<Pair<Float, SkyColors>> {
        val raw = listOf(
            rise - 60f to DayPhase.NIGHT,
            rise - 10f to DayPhase.DAWN,
            rise + 50f to DayPhase.DAY,
            set - 80f to DayPhase.DAY,
            set - 15f to DayPhase.GOLDEN_HOUR,
            set + 20f to DayPhase.DUSK,
            set + 65f to DayPhase.NIGHT,
        )
        var floor = Float.NEGATIVE_INFINITY
        return raw.map { (minute, phase) ->
            floor = max(floor, minute)
            floor to palette(phase)
        }
    }

    private fun blend(from: SkyColors, to: SkyColors, fraction: Float): SkyColors {
        val f = fraction.coerceIn(0f, 1f)
        return SkyColors(
            top = lerpArgb(from.top, to.top, f),
            middle = lerpArgb(from.middle, to.middle, f),
            horizon = lerpArgb(from.horizon, to.horizon, f),
            glow = lerpArgb(from.glow, to.glow, f),
            glowAlpha = from.glowAlpha + (to.glowAlpha - from.glowAlpha) * f,
            stars = from.stars + (to.stars - from.stars) * f,
        )
    }

    /** Blends opaque colours channel by channel. */
    fun lerpArgb(from: Int, to: Int, fraction: Float): Int {
        fun channel(shift: Int): Int {
            val a = from shr shift and 0xFF
            val b = to shr shift and 0xFF
            return (a + (b - a) * fraction + 0.5f).toInt().coerceIn(0, 255) shl shift
        }
        return (0xFF shl 24) or channel(16) or channel(8) or channel(0)
    }

    private fun minutes(time: LocalTime): Float = time.toSecondOfDay() / 60f

    private const val DAWN_BEFORE = 45f
    private const val DAWN_AFTER = 30f
    private const val GOLDEN_HOUR = 60f
    private const val DUSK_AFTER = 45f
}
