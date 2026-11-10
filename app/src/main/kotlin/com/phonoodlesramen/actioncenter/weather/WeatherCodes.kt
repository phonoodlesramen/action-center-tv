package com.phonoodlesramen.actioncenter.weather

/** The icon families the top bar draws. */
enum class Sky { CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

data class Condition(val sky: Sky, val label: String)

/**
 * WMO weather interpretation codes, as Open-Meteo's docs list them. The API only
 * returns the number; the client maps it.
 */
object WeatherCodes {
    fun condition(code: Int): Condition = when (code) {
        0 -> Condition(Sky.CLEAR, "Clear")
        1 -> Condition(Sky.PARTLY_CLOUDY, "Mainly clear")
        2 -> Condition(Sky.PARTLY_CLOUDY, "Partly cloudy")
        3 -> Condition(Sky.CLOUDY, "Overcast")
        45, 48 -> Condition(Sky.FOG, "Fog")
        51, 53, 55 -> Condition(Sky.DRIZZLE, "Drizzle")
        56, 57 -> Condition(Sky.DRIZZLE, "Freezing drizzle")
        61, 63 -> Condition(Sky.RAIN, "Rain")
        65 -> Condition(Sky.RAIN, "Heavy rain")
        66, 67 -> Condition(Sky.RAIN, "Freezing rain")
        71, 73 -> Condition(Sky.SNOW, "Snow")
        75 -> Condition(Sky.SNOW, "Heavy snow")
        77 -> Condition(Sky.SNOW, "Snow grains")
        80, 81, 82 -> Condition(Sky.RAIN, "Rain showers")
        85, 86 -> Condition(Sky.SNOW, "Snow showers")
        95, 97 -> Condition(Sky.THUNDER, "Thunderstorm")
        96, 99 -> Condition(Sky.THUNDER, "Thunderstorm with hail")
        else -> Condition(Sky.CLOUDY, "Unknown")
    }
}
