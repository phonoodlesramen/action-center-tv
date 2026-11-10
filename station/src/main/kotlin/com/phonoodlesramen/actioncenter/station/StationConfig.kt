package com.phonoodlesramen.actioncenter.station

import com.phonoodlesramen.actioncenter.guide.station.StationApi
import java.nio.file.Path
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId

/**
 * The station's settings, from its container's environment (`station/compose.yaml`):
 * - `STATION_PORT`: the HTTPS port, 8443 by default;
 * - `STATION_DATA`: the volume, `/data` by default;
 * - `STATION_HOSTS`: the NAS's LAN IPs or names, comma-separated, which the server
 *   certificate names, so a TV checking it by address accepts it;
 * - `TZ`: the house's zone, which sets the schedule's day boundaries.
 */
data class StationConfig(
    val port: Int = StationApi.DEFAULT_PORT,
    val dataDir: Path = Path.of(DEFAULT_DATA),
    val hosts: List<String> = emptyList(),
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** The station's own state: its CA, the TVs, the Plex sign-in, the spot. */
    val stateDir: Path get() = dataDir.resolve("station")

    /** A file named after a pairing code dropped here approves that TV (File Station, or `approve`). */
    val approveDir: Path get() = dataDir.resolve("approve")

    companion object {
        const val DEFAULT_DATA = "/data"

        /** A host the certificate can name: an IPv4 or IPv6 address, or a DNS name. */
        private val HOST = Regex("[A-Za-z0-9.:-]{1,253}")

        fun fromEnv(env: Map<String, String> = System.getenv(), log: (String) -> Unit = {}): StationConfig {
            val port = env["STATION_PORT"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
                it.toIntOrNull()?.takeIf { port -> port in 1..65535 } ?: throw IllegalArgumentException("STATION_PORT must be a port number, not \"$it\"")
            } ?: StationApi.DEFAULT_PORT
            val data = env["STATION_DATA"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_DATA
            val hosts = env["STATION_HOSTS"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            hosts.filterNot(HOST::matches).forEach { log("STATION_HOSTS: ignored \"${it.take(40)}\": not an address or a name") }
            val zone = env["TZ"]?.trim()?.takeIf { it.isNotEmpty() }?.let { tz ->
                try {
                    ZoneId.of(tz)
                } catch (e: DateTimeException) {
                    log("TZ \"${tz.take(40)}\" isn't a time zone; using ${ZoneId.systemDefault().id}")
                    null
                }
            } ?: ZoneId.systemDefault()
            return StationConfig(port, Path.of(data), hosts.filter(HOST::matches), zone)
        }
    }
}

/** The station's log: one line each, on stdout, which Container Manager › Logs shows. Never a token, key or certificate. */
object StationLog {
    fun line(message: String) {
        println("${Instant.now()} $message")
    }
}
