package com.phonoodlesramen.actioncenter.ui.guide

import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.guide.GuideChannel
import com.phonoodlesramen.actioncenter.guide.GuideSlot
import com.phonoodlesramen.actioncenter.guide.StarterLineup

/**
 * A made-up lineup for previews, tests and screenshots of the Live row, the Guide card, the
 * full guide and the player. Programs start on the half hour; the gap to the next half hour
 * is a trailer or an "Up next" card, as the scheduler makes them (G3).
 */
internal object SampleGuide {
    private const val MINUTE = 60_000L
    private const val HALF_HOUR = 30 * MINUTE

    private class Program(
        val title: String,
        val minutes: Int,
        val rating: String,
        val episode: String? = null,
        val show: String? = null,
        val summary: String? = null,
    )

    private val movies = listOf(
        Program("Big Atlas", 118, "PG-13"),
        Program("Glowing Reef", 96, "PG"),
        Program("Quiet Field", 131, "R", summary = QUIET_FIELD),
        Program("The Long Signal", 104, "PG-13"),
        Program("Paper Harbor", 89, "PG"),
    )
    private val scifi = listOf(
        Program("Orbit of Glass", 142, "PG-13"),
        Program("Cold Star Relay", 109, "PG-13"),
        Program("The Ninth Moon", 121, "R"),
    )
    private val classics = listOf(
        Program("A Train at Midnight", 92, "NR"),
        Program("Harbor Lights", 101, "NR"),
        Program("The Silver Coast", 115, "PG"),
    )
    private val tvMix = listOf(
        Program("Night Shift", 44, "TV-14", "S2 E5 · Paper Moons", "show-night-shift"),
        Program("Kitchen Table", 22, "TV-PG", "S1 E8 · Second Helpings", "show-kitchen-table"),
        Program("Ridge Line", 52, "TV-MA", "S3 E1 · Thaw", "show-ridge-line"),
        Program("Kitchen Table", 22, "TV-PG", "S1 E9 · Leftovers", "show-kitchen-table", LEFTOVERS),
    )
    private val comedy = listOf(
        Program("Open House", 22, "TV-PG", "S4 E2 · The Showing", "show-open-house"),
        Program("Open House", 21, "TV-PG", "S4 E3 · Curb Appeal", "show-open-house"),
        Program("Desk Duty", 23, "TV-14", "S1 E1 · Pilot", "show-desk-duty"),
    )
    private val marathon = (1..10).map { Program("Ridge Line", 52, "TV-MA", "S1 E$it", "show-ridge-line") }
    private val family = listOf(
        Program("Paper Kites", 88, "G"),
        Program("The Lantern Fox", 94, "PG"),
        Program("Tide Pool Friends", 22, "TV-Y7", "S1 E3 · Low Tide", "show-tide-pool"),
        Program("Glowing Reef", 96, "PG"),
    )
    private val cartoons = listOf(
        Program("Tide Pool Friends", 22, "TV-Y7", "S1 E4 · The Big Wave", "show-tide-pool"),
        Program("Moss and Pebble", 11, "TV-Y", "S2 E1 · Rainy Day", "show-moss-pebble"),
        Program("Moss and Pebble", 11, "TV-Y", "S2 E2 · The Lost Acorn", "show-moss-pebble"),
    )
    private val teen = listOf(
        Program("Big Atlas", 118, "PG-13"),
        Program("Summer of the Comet", 102, "PG"),
    )

    /**
     * Six channels with slots from 2 hours before [now] to 25 hours after it. Classics has one
     * program whose file is gone, so it shows off air. [now] defaults to the sample weeknight.
     */
    fun ready(now: Long = WEEKNIGHT): GuideCardState.Ready = GuideCardState.Ready(
        channels = listOf(
            channel("movies", 1, "Movies", movies, now, trailers = true),
            channel("scifi", 2, "Sci-Fi", scifi, now, trailers = true),
            channel("tv-mix", 3, "TV Mix", tvMix, now, trailers = false),
            channel("classics", 4, "Classics", classics, now, trailers = true, offAirAt = 3),
            channel("comedy", 5, "Comedy", comedy, now, trailers = false),
            channel("marathon-ridge-line", 6, "Ridge Line Marathon", marathon, now, trailers = false),
        ),
        updatedAtMillis = now - 20 * MINUTE,
    )

    /**
     * [ready] and three channels that play only kids' and teens' titles (R2): Family (G to
     * TV-PG, as the starter lineup makes it), Cartoon Club (TV-Y, TV-Y7) and Teen Movies (PG,
     * PG-13). Each of [ready]'s plays something rated above, or NR, so a kid of 7 to 9 sees
     * Family and Cartoon Club alone.
     */
    fun withKidChannels(now: Long = WEEKNIGHT): GuideCardState.Ready {
        val adult = ready(now)
        return adult.copy(
            channels = adult.channels + listOf(
                channel("family", 7, "Family", family, now, trailers = true, ratings = StarterLineup.FAMILY_RATINGS.toSet()),
                channel("cartoons", 8, "Cartoon Club", cartoons, now, trailers = false),
                channel("teen", 9, "Teen Movies", teen, now, trailers = true),
            ),
        )
    }

    /** Monday 26 October 2026, 8:41 PM UTC, as SampleHomeState.weeknight. */
    const val WEEKNIGHT = 1_793_047_260_000L

    /** A summary long enough to wrap: the details screen shows all of it. */
    private const val QUIET_FIELD = "After a long drought, a retired surveyor goes back to the farm she grew up on to " +
        "map the one field nobody in town will cross after dark. Her brother wants the land sold before the harvest, " +
        "and the neighbours want her gone by the first frost. What she finds under the soil changes what the whole " +
        "valley remembers about the summer the river ran dry."

    private const val LEFTOVERS = "The family tries to make a week of dinners out of one enormous holiday ham, " +
        "and Dana keeps a spreadsheet nobody asked for."

    private fun channel(
        id: String,
        number: Int,
        name: String,
        programs: List<Program>,
        now: Long,
        trailers: Boolean,
        offAirAt: Int = -1,
        /** What its rule can play is rated (R2): its programs' ratings, unless its rule's pool holds more. */
        ratings: Set<String> = programs.mapTo(HashSet()) { it.rating },
    ): GuideChannel {
        val slots = mutableListOf<GuideSlot>()
        var start = (now - 2 * 60 * MINUTE) / HALF_HOUR * HALF_HOUR
        var i = 0
        while (start < now + 25 * 60 * MINUTE) {
            val p = programs[(i + number) % programs.size]
            val key = "sample-$id-$i"
            val end = start + p.minutes * MINUTE
            slots += GuideSlot(
                startMillis = start, endMillis = end, title = p.title, episode = p.episode, itemKey = key, showKey = p.show,
                art = "/library/metadata/$key/art", contentRating = p.rating, filler = false,
                offAir = if (i == offAirAt) "This title is no longer in your library" else null,
                summary = p.summary ?: "A made-up ${if (p.episode == null) "film" else "episode"} for the sample guide.",
            )
            val next = (end + HALF_HOUR - 1) / HALF_HOUR * HALF_HOUR
            if (next > end) {
                slots += GuideSlot(
                    startMillis = end, endMillis = next,
                    title = if (trailers) "Trailer: ${programs[(i + number + 1) % programs.size].title}" else "Up next",
                    episode = null, itemKey = if (trailers) "trailer-$key" else null, showKey = null, art = null,
                    contentRating = null, filler = true, offAir = null,
                )
            }
            start = next
            i++
        }
        return GuideChannel(id, number, name, logo = null, slots = slots, contentRatings = ratings)
    }
}
