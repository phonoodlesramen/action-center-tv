package com.phonoodlesramen.actioncenter.apps

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class LaunchpadOrderTest {
    private fun app(label: String) = LaunchableApp("pkg.${label.lowercase()}", "pkg.${label.lowercase()}.Main", label)

    private val apps = listOf("Netflix", "plezy", "YouTube", "Disney+", "Spotify", "Kodi", "Twitch").map(::app)

    @Test
    fun `most launched apps come first`() {
        val counts = mapOf(app("YouTube").key to 9, app("plezy").key to 20, app("Kodi").key to 1)

        val order = LaunchpadOrder.launchpad(apps, counts, hidden = emptySet()).map { it.label }

        assertEquals(listOf("plezy", "YouTube", "Kodi", "Disney+", "Netflix"), order)
    }

    @Test
    fun `with no history the launchpad is A to Z, ignoring case`() {
        val order = LaunchpadOrder.launchpad(apps, emptyMap(), hidden = emptySet()).map { it.label }

        assertEquals(listOf("Disney+", "Kodi", "Netflix", "plezy", "Spotify"), order)
    }

    @Test
    fun `hidden apps are left off the launchpad`() {
        val order = LaunchpadOrder.launchpad(apps, emptyMap(), hidden = setOf(app("Disney+").key)).map { it.label }

        assertEquals(listOf("Kodi", "Netflix", "plezy", "Spotify", "Twitch"), order)
    }

    @Test
    fun `most used lists only apps launched at least once, at most five`() {
        val counts = apps.mapIndexed { i, a -> a.key to i }.toMap()

        val most = LaunchpadOrder.mostUsed(apps, counts).map { it.label }

        assertEquals(listOf("Twitch", "Kodi", "Spotify", "Disney+", "YouTube"), most)
        assertEquals(emptyList<LaunchableApp>(), LaunchpadOrder.mostUsed(apps, emptyMap()))
    }

    @Test
    fun `what you open around this time leads, then launches from here, then A to Z (M5)`() {
        val counts = mapOf(app("plezy").key to 20)
        val usage = mapOf("pkg.twitch" to 3.0, "pkg.spotify" to 1.5)

        val order = LaunchpadOrder.launchpad(apps, counts, hidden = emptySet(), usage = usage).map { it.label }

        assertEquals(listOf("Twitch", "Spotify", "plezy", "Disney+", "Kodi"), order)
    }

    @Test
    fun `with UsageScore, all-day opens come after opens around now and before launches from here (M5)`() {
        // Monday 26 October 2026, 9:30 PM: Twitch was opened at 9 PM a week ago; Kodi and
        // Spotify only on Saturday mornings, Kodi more often.
        val time = LocalDateTime.of(2026, 10, 26, 21, 30)
        val week = UsageHours.weekStart(time.toLocalDate())
        val usage = UsageCounts(
            listOf(
                UsageRow("pkg.twitch", week - 7, hourOfWeek = 21, opens = 1),
                UsageRow("pkg.kodi", week - 7, hourOfWeek = 5 * 24 + 9, opens = 4),
                UsageRow("pkg.spotify", week - 7, hourOfWeek = 5 * 24 + 9, opens = 2),
            ),
        )
        val scores = UsageScore.at(usage, time)

        val order = LaunchpadOrder.launchpad(apps, mapOf(app("plezy").key to 20), hidden = emptySet(), usage = scores.now).map { it.label }
        val mostUsed = LaunchpadOrder.mostUsed(apps, counts = emptyMap(), usage = scores.allDay).map { it.label }

        assertEquals(listOf("Twitch", "Kodi", "Spotify", "plezy", "Disney+"), order)
        assertEquals(listOf("Kodi", "Spotify", "Twitch"), mostUsed)
    }

    @Test
    fun `most used counts apps opened anywhere`() {
        val mostUsed = LaunchpadOrder.mostUsed(apps, counts = emptyMap(), usage = mapOf("pkg.kodi" to 2.0)).map { it.label }

        assertEquals(listOf("Kodi"), mostUsed)
    }
}
