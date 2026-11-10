package com.phonoodlesramen.actioncenter.ai

import com.phonoodlesramen.actioncenter.plex.cache.ItemEntity
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Profiles
import com.phonoodlesramen.actioncenter.suggest.Surfaces

/**
 * Made-up titles for the AI blurb tests, with everything that must never reach Anthropic
 * (M8) set to something easy to find: Plex keys, paths, a server address, watch dates.
 */
object BlurbFixtures {
    /** A made-up key in the right shape. Never a real one. */
    const val KEY = "sk-ant-api03-TESTONLY-not-a-real-key-0000-wxYZ"

    const val VIEWED_AT = 1_761_400_000L
    const val RELEASED = "1995-12-15"

    fun item(
        key: String,
        title: String,
        type: String = "movie",
        year: Int? = 1995,
        minutes: Long? = 170,
        rating: String? = "R",
        genres: String = "Crime|Thriller",
        showKey: String? = null,
        showTitle: String? = null,
    ) = ItemEntity(
        ratingKey = key,
        sectionKey = "section-7",
        type = type,
        title = title,
        year = year,
        durationMs = minutes?.let { it * 60_000 },
        viewCount = 1,
        lastViewedAt = VIEWED_AT,
        addedAt = VIEWED_AT - 86_400,
        updatedAt = VIEWED_AT,
        contentRating = rating,
        audienceRating = 8.3,
        rating = 8.0,
        thumb = "/library/metadata/$key/thumb/1700000000",
        art = "/library/metadata/$key/art/1700000000",
        genres = genres,
        syncedAt = VIEWED_AT * 1_000,
        showKey = showKey,
        showTitle = showTitle,
        season = if (type == "episode") 2 else null,
        episode = if (type == "episode") 9 else null,
        summary = "A secret twist the viewer shouldn't read about.",
        partKey = "/library/parts/$key/1/file.mkv",
        releaseDate = RELEASED,
        viewOffsetMs = 1_234_000,
    )

    fun pick(surface: String, rank: Int, key: String, reason: String, evidence: String = "") =
        PickEntity(Profiles.OWNER, surface, rank, key, reason, evidence, computedAt = VIEWED_AT * 1_000)

    /** Heat and Ronin (films), an episode of Harbour Lights (shown as the show), with Plex keys no one would type. */
    val titles: Map<String, ItemEntity> = listOf(
        item("rk-48151", "Heat"),
        item("rk-62342", "Ronin", year = 1998, minutes = 122),
        item("rk-show-9", "Harbour Lights", type = "show", year = 2019, minutes = null, rating = "TV-14", genres = "Drama|Mystery"),
        item(
            "rk-ep-77", "The Lighthouse Keeper", type = "episode", year = 2021, minutes = 47, rating = null, genres = "",
            showKey = "rk-show-9", showTitle = "Harbour Lights",
        ),
    ).associateBy { it.ratingKey }

    val picks = listOf(
        pick(Surfaces.HERO, 0, "rk-48151", "Because you liked Collateral", "Rated 8.3|New this month"),
        pick(Surfaces.HERO, 1, "rk-ep-77", "You're 2 episodes from the end of Harbour Lights's season 2", "Last watched 3 days ago|Rated 8.3"),
        pick(Surfaces.HERO, 2, "rk-62342", "From the Heist collection"),
    )
}
