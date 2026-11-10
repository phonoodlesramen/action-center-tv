package com.phonoodlesramen.actioncenter.plex.cache

import com.phonoodlesramen.actioncenter.content.HomeContent
import com.phonoodlesramen.actioncenter.content.Lens
import com.phonoodlesramen.actioncenter.content.LensPick
import com.phonoodlesramen.actioncenter.content.Pick
import com.phonoodlesramen.actioncenter.content.QuickItem
import com.phonoodlesramen.actioncenter.content.UpNextItem
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import com.phonoodlesramen.actioncenter.context.HeroPick
import com.phonoodlesramen.actioncenter.context.ModeRules
import com.phonoodlesramen.actioncenter.home.PickState
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.KidPlayEntity
import com.phonoodlesramen.actioncenter.suggest.BlurbEntity
import com.phonoodlesramen.actioncenter.suggest.LensRules
import com.phonoodlesramen.actioncenter.suggest.PickEntity
import com.phonoodlesramen.actioncenter.suggest.Picked
import com.phonoodlesramen.actioncenter.suggest.QuickWatch
import com.phonoodlesramen.actioncenter.suggest.Surfaces
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

/** What the cards are built from: the cache's answers for one day, and the engine's last picks. */
data class PlexSnapshot(
    val day: LocalDate,
    val continueWatching: List<ContinueEntity>,
    val newest: List<ItemEntity>,
    val classics: List<ItemEntity>,
    val throwbacks: List<ItemEntity>,
    val itemCount: Int,
    /** The suggestion engine's last run (Phase 3): empty until it has run. */
    val picks: List<PickEntity> = emptyList(),
    /** The titles [picks] name, and their episodes' shows, by ratingKey. */
    val titles: Map<String, ItemEntity> = emptyMap(),
    /** AI blurbs (M7-M9), by ratingKey: each shows only while its pick has the reason it was written for. */
    val blurbs: Map<String, BlurbEntity> = emptyMap(),
)

/**
 * A kid's own part of the cards (Phase 5, K2-K4): the ratings they may see today, their
 * Plex Continue Watching, and (Phase 6, R1) how often and when they last watched each title,
 * so "seen" on their cards is theirs. All empty while they aren't linked to a Plex Home user.
 */
data class KidCards(
    val ceiling: Set<String>,
    val continueWatching: List<ContinueEntity>,
    /** Their finishes by ratingKey: a movie or an episode. */
    val views: Map<String, KidViews> = emptyMap(),
    /** Their finishes of a show's episodes, by the show's ratingKey. */
    val showViews: Map<String, KidViews> = emptyMap(),
) {
    private val positions = continueWatching.associateBy { it.ratingKey }
    private val showPositions = continueWatching.filter { it.showKey != null }.groupBy { it.showKey!! }

    /**
     * [item] with their watch state in place of yours: their finishes and last view (a
     * finish, or a position in their Continue Watching), their position, and none of your
     * stars. A show counts its episodes' finishes. Your history never shows on their cards.
     */
    fun theirs(item: ItemEntity): ItemEntity {
        if (item.type == "show") {
            val views = showViews[item.ratingKey]
            return item.copy(
                viewCount = views?.count ?: 0,
                lastViewedAt = listOfNotNull(views?.lastViewedAt, showPositions[item.ratingKey]?.mapNotNull { it.lastViewedAt }?.maxOrNull()).maxOrNull(),
                viewOffsetMs = null,
                watchedEpisodeCount = views?.episodes ?: 0,
                userRating = null,
                lastRatedAt = null,
            )
        }
        val views = views[item.ratingKey]
        val position = positions[item.ratingKey]
        return item.copy(
            viewCount = views?.count ?: 0,
            lastViewedAt = listOfNotNull(views?.lastViewedAt, position?.lastViewedAt).maxOrNull(),
            viewOffsetMs = position?.viewOffsetMs?.takeIf { it > 0 },
            userRating = null,
            lastRatedAt = null,
        )
    }

    companion object {
        /** [plays] (theirs, from their Plex history) counted by title and by show. */
        fun of(ceiling: Set<String>, continueWatching: List<ContinueEntity>, plays: List<KidPlayEntity>): KidCards = KidCards(
            ceiling,
            continueWatching,
            views = plays.groupBy { it.ratingKey }.mapValues { (_, rows) -> KidViews(rows.size, rows.maxOf { it.viewedAt }) },
            showViews = plays.filter { it.showKey != null }.groupBy { it.showKey!! }.mapValues { (_, rows) ->
                KidViews(rows.size, rows.maxOf { it.viewedAt }, episodes = rows.distinctBy { it.ratingKey }.size)
            },
        )
    }
}

/** How many times a kid finished a title (or a show's episodes), when they last did (epoch seconds), and for a show how many episodes. */
data class KidViews(val count: Int, val lastViewedAt: Long?, val episodes: Int = 0)

/**
 * Reads a [PlexSnapshot] whenever the cache, the engine's [picks] or the [blurbs] change, or
 * the day turns. With a [kid] (null for you) it's theirs: see [kidSnapshot].
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun PlexDatabase.snapshots(
    days: Flow<LocalDate>,
    picks: Flow<List<PickEntity>> = flowOf(emptyList()),
    zone: ZoneId = ZoneId.systemDefault(),
    blurbs: Flow<List<BlurbEntity>> = flowOf(emptyList()),
    kid: Flow<KidCards?> = flowOf(null),
): Flow<PlexSnapshot> {
    val dao = dao()
    val changes = invalidationTracker.createFlow(*PlexDatabase.TABLES, emitInitialState = true)
    return combine(changes, days.distinctUntilChanged(), picks, blurbs, kid) { _, day, picked, written, theirs ->
        SnapshotInputs(day, picked, written, theirs)
    }.mapLatest { (day, picked, written, theirs) ->
        val todaySeconds = day.atStartOfDay(zone).toEpochSecond()
        if (theirs != null) return@mapLatest dao.kidSnapshot(day, todaySeconds, picked, theirs)
        val yearAgo = day.minusYears(1).atStartOfDay(zone).toEpochSecond()
        PlexSnapshot(
            day = day,
            continueWatching = dao.continueWatching(),
            newest = dao.newestUnwatchedMovies(PlexCards.PICKS),
            classics = dao.classics(
                maxYear = day.year - PlexCards.CLASSIC_YEARS,
                minRating = PlexCards.CLASSIC_RATING,
                notSince = todaySeconds - PlexCards.CLASSIC_REWATCH_YEARS * 365 * 86_400L,
                limit = PlexCards.LENS_PICKS,
            ),
            throwbacks = dao.watchedBetween(yearAgo - 7 * 86_400L, yearAgo + 7 * 86_400L, PlexCards.LENS_PICKS),
            itemCount = dao.itemCount(),
            picks = picked,
            titles = dao.titlesFor(picked),
            blurbs = written.associateBy { it.ratingKey },
        )
    }
}

private data class SnapshotInputs(val day: LocalDate, val picks: List<PickEntity>, val blurbs: List<BlurbEntity>, val kid: KidCards?)

/**
 * A kid's snapshot (Phase 5, K2-K4): their Continue Watching, so Up Next and Quick Watch are
 * theirs, and nothing their ceiling doesn't allow (an episode by its own rating, else its
 * show's; unrated hidden). Your Continue Watching stays out of it, and so do blurbs (AI
 * blurbs never run for kids). Every title it reads has their watch state on it, not yours
 * ([KidCards.theirs], Phase 6), so a lens card's Watch again, a pick's meta and Quick Watch
 * say what they've seen. Their picks were made within their ceiling; any a changed ceiling
 * no longer allows drop out here. Signed out, the cache is empty, and so is their Up Next.
 */
private suspend fun PlexDao.kidSnapshot(day: LocalDate, todaySeconds: Long, picks: List<PickEntity>, kid: KidCards): PlexSnapshot {
    val ceiling = kid.ceiling
    val itemCount = itemCount()
    val titles = titlesFor(picks).mapValues { kid.theirs(it.value) }
    val shows = (titles.values.mapNotNull { it.showKey } + kid.continueWatching.mapNotNull { it.showKey }).toSet() - titles.keys
    val ratings = titles.mapValues { it.value.contentRating } + itemsByKey(shows).mapValues { it.value.contentRating }
    fun allowedTitle(item: ItemEntity) = AgeBrackets.allows(ceiling, item.contentRating, item.showKey?.let(ratings::get))
    fun allowedRow(row: ContinueEntity) = AgeBrackets.allows(ceiling, row.contentRating, row.showKey?.let(ratings::get))
    val notSince = todaySeconds - PlexCards.CLASSIC_REWATCH_YEARS * 365 * 86_400L
    return PlexSnapshot(
        day = day,
        continueWatching = if (itemCount == 0) emptyList() else kid.continueWatching.filter(::allowedRow),
        // Until their first run: the interim cards, within their ceiling, with their history rather than yours.
        newest = newestUnwatchedMovies(PlexCards.PICKS * KID_WIDER).filter(::allowedTitle).map(kid::theirs)
            .filter { it.viewCount == 0 }.take(PlexCards.PICKS),
        classics = classics(
            maxYear = day.year - PlexCards.CLASSIC_YEARS,
            minRating = PlexCards.CLASSIC_RATING,
            notSince = notSince,
            limit = PlexCards.LENS_PICKS * KID_WIDER,
        ).filter(::allowedTitle).map(kid::theirs).filter { it.viewCount == 0 || (it.lastViewedAt ?: 0) < notSince }.take(PlexCards.LENS_PICKS),
        throwbacks = emptyList(),
        itemCount = itemCount,
        picks = picks.filter { pick -> titles[pick.ratingKey]?.let(::allowedTitle) == true },
        titles = titles,
    )
}

/** A kid's interim cards read this many times the titles, to fill them after their ceiling. */
private const val KID_WIDER = 10

private suspend fun PlexDao.itemsByKey(keys: Collection<String>): Map<String, ItemEntity> =
    keys.distinct().chunked(MAX_KEYS).flatMap { items(it) }.associateBy { it.ratingKey }

/** The titles [picks] name, plus the shows of those that are episodes. */
private suspend fun PlexDao.titlesFor(picks: List<PickEntity>): Map<String, ItemEntity> {
    val named = picks.map { it.ratingKey }.distinct().chunked(MAX_KEYS).flatMap { items(it) }
    val shows = (named.mapNotNull { it.showKey }.toSet() - named.map { it.ratingKey }.toSet()).toList().chunked(MAX_KEYS).flatMap { items(it) }
    return (named + shows).associateBy { it.ratingKey }
}

/** SQLite on Android 11 allows 999 arguments in a query. */
private const val MAX_KEYS = 500

/**
 * The home screen's media cards from the Plex cache. Once the suggestion engine has run
 * (Phase 3), they're its picks, with what depends on the minute applied here: the context
 * mode reorders Tonight's Pick (M3, [ModeRules]), For the family keeps what's over by
 * 8:30 PM, Up Next leaves out the hero's show (S5), and Quick Watch leaves out the hero's
 * show and Up Next and keeps what fits before the night's cutoff (S6, M4). Until then, P6's
 * simple, honest interim rules.
 */
object PlexCards {
    const val PICKS = 5
    const val LENS_PICKS = 8
    const val UP_NEXT = 3
    const val CLASSIC_YEARS = Classics.YEARS
    const val CLASSIC_RATING = 7.0
    const val CLASSIC_REWATCH_YEARS = 5L
    private const val QUICK_MAX_MINUTES = 40L
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm")

    /**
     * Null while the cache is empty: the hero keeps saying it's loading. [shown] is the
     * hero's [PickState], so Up Next can leave out the pick on screen. [schedule] says which
     * context mode orders Tonight's Pick (M3). [cutoff] and [weekendCutoff] are Settings ›
     * Time & modes' Quick Watch cutoffs: Friday and Saturday nights use [weekendCutoff],
     * every other night [cutoff] (M4).
     */
    fun build(
        snapshot: PlexSnapshot,
        now: LocalDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
        shown: PickState = PickState(),
        schedule: ContextSchedule = ContextSchedule.DEFAULT,
        cutoff: LocalTime = QuickWatch.DEFAULT_CUTOFF,
        weekendCutoff: LocalTime = QuickWatch.WEEKEND_CUTOFF,
    ): HomeContent? {
        if (snapshot.itemCount == 0 && snapshot.continueWatching.isEmpty()) return null
        val fits = QuickWatch.cutoff(QuickWatch.settingFor(now, cutoff, weekendCutoff), now.toLocalTime())
        return if (snapshot.picks.isEmpty()) interim(snapshot, now, zone, fits) else fromEngine(snapshot, now, shown, schedule, fits)
    }

    private fun fromEngine(snapshot: PlexSnapshot, now: LocalDateTime, shown: PickState, schedule: ContextSchedule, fits: LocalTime): HomeContent {
        val titles = snapshot.titles
        val bySurface = snapshot.picks.groupBy { it.surface }.mapValues { (_, rows) -> rows.sortedBy { it.rank } }
        fun rows(surface: String) = bySurface[surface].orEmpty()

        val ordered = ModeRules.order(rows(Surfaces.HERO), schedule.modeAt(now)) { row -> heroPick(row, titles) }
        val blurbs = snapshot.blurbs
        val picks = ordered.mapNotNull { row -> pick(row, titles, blurbs) }
        val hero = shown.current(picks)

        val continueWatching = snapshot.continueWatching.associateBy { it.ratingKey }
        val ranked = rows(Surfaces.UP_NEXT).mapNotNull { continueWatching[it.ratingKey] }.ifEmpty { snapshot.continueWatching }
        val upNext = ranked.map { it.toUpNext() }.filter { it.titleKey != hero?.titleKey }.take(UP_NEXT)
        // Quick Watch repeats neither the hero's show nor Up Next.
        val shown = upNext.flatMapTo(mutableSetOf()) { listOf(it.id, it.titleKey) } + listOfNotNull(hero?.id, hero?.titleKey)

        val quick = rows(Surfaces.QUICK_WATCH).mapNotNull { row -> quickItem(row.ratingKey, continueWatching, titles) }
            .filter { (item, _) -> item.id !in shown && item.titleKey !in shown }
            .associateBy { (item, _) -> item.id }
        val fitted = QuickWatch.fit(quick.values.map { (item, minutes) -> Picked(item.id, "") to minutes }, now.toLocalTime(), fits)

        return HomeContent(
            picks = picks,
            lenses = Lens.entries.associateWith { lens ->
                rows(Surfaces.lens(lens)).mapNotNull { row ->
                    if (lens == Lens.FAMILY && !LensRules.familyFits(titles[row.ratingKey]?.durationMs, now)) null else lensPick(row, titles, blurbs)
                }
            },
            quickWatch = fitted.mapNotNull { quick[it.ratingKey]?.first },
            quickWatchCutoff = fits.format(CLOCK),
            upNext = upNext,
            fromPlex = true,
            moreLike = bySurface.filterKeys { it.startsWith(MORE_LIKE) }
                .entries.associate { (surface, rows) -> surface.removePrefix(MORE_LIKE) to rows.mapNotNull { lensPick(it, titles, blurbs) } },
        )
    }

    private val MORE_LIKE = Surfaces.moreLike("")

    /** What the mode rules need to know about a hero pick (M3): an episode with episodes left is the finish pick, any other the new series. */
    private fun heroPick(row: PickEntity, titles: Map<String, ItemEntity>): HeroPick {
        val item = titles[row.ratingKey]
        val kind = when {
            item?.type == "movie" -> HeroPick.Kind.FILM
            row.episodesLeft != null -> HeroPick.Kind.FINISH
            else -> HeroPick.Kind.SERIES
        }
        val rating = item?.contentRating ?: item?.showKey?.let(titles::get)?.contentRating
        return HeroPick(kind, item?.durationMs, rating, row.episodesLeft)
    }

    /** The pick's AI blurb in place of its reason, while the blurb was written for that reason (M9). */
    private fun line(row: PickEntity, blurbs: Map<String, BlurbEntity>): String =
        blurbs[row.ratingKey]?.takeIf { it.reason == row.reason }?.text ?: row.reason

    /** A hero pick: a movie, or an episode shown as its show ("Play S2E9"), with the show's logo. */
    private fun pick(row: PickEntity, titles: Map<String, ItemEntity>, blurbs: Map<String, BlurbEntity> = emptyMap()): Pick? {
        val item = titles[row.ratingKey] ?: return null
        val evidence = row.evidence.split('|').filter(String::isNotEmpty)
        val line = line(row, blurbs)
        // Why this pick? keeps the plain evidence when a blurb shows (M9).
        val because = row.reason.takeIf { it != line }
        if (item.type != "episode") {
            return Pick(
                item.ratingKey, item.title, meta(item.year, item.durationMs, item.contentRating), line,
                image = item.art, trailerKey = item.trailerKey, year = item.year, evidence = evidence, because = because,
                logo = item.logo,
            )
        }
        val show = item.showKey?.let(titles::get)
        val label = episodeLabel(item.season, item.episode)
        return Pick(
            id = item.ratingKey,
            title = item.showTitle ?: show?.title ?: item.title,
            meta = listOfNotNull(label, item.durationMs?.let(::runtime), item.contentRating ?: show?.contentRating).joinToString(" · "),
            reason = line,
            playLabel = label?.let { "Play $it" } ?: "Play",
            image = show?.art ?: item.art ?: item.thumb,
            year = show?.year,
            titleKey = item.showKey ?: item.ratingKey,
            evidence = evidence,
            because = because,
            logo = show?.logo ?: item.logo,
        )
    }

    /** A lens card or More like this title: a movie, a show, or an episode shown as its show, with the show's logo. */
    private fun lensPick(row: PickEntity, titles: Map<String, ItemEntity>, blurbs: Map<String, BlurbEntity> = emptyMap()): LensPick? {
        val item = titles[row.ratingKey] ?: return null
        if (item.type != "episode") {
            return LensPick(
                item.ratingKey, item.title, meta(item.year, item.durationMs, item.contentRating), line(row, blurbs), item.viewCount > 0, item.art,
                logo = item.logo,
            )
        }
        val show = item.showKey?.let(titles::get)
        return LensPick(
            id = item.ratingKey,
            title = item.showTitle ?: show?.title ?: item.title,
            meta = listOfNotNull(episodeLabel(item.season, item.episode), item.durationMs?.let(::runtime)).joinToString(" · "),
            line = line(row, blurbs),
            seen = item.viewCount > 0,
            image = show?.art ?: item.thumb,
            titleKey = item.showKey ?: item.ratingKey,
            logo = show?.logo ?: item.logo,
        )
    }

    /** A Quick Watch row and its minutes: a resume from Continue Watching, or a short title in full. */
    private fun quickItem(ratingKey: String, continueWatching: Map<String, ContinueEntity>, titles: Map<String, ItemEntity>): Pair<QuickItem, Long>? {
        continueWatching[ratingKey]?.let { item ->
            val left = remainingMinutes(item)
            return item.toQuick(left) to left
        }
        val item = titles[ratingKey] ?: return null
        val minutes = (item.durationMs ?: 0) / 60_000
        val show = item.showKey?.let(titles::get)
        val title = if (item.type == "episode") item.showTitle ?: show?.title ?: item.title else item.title
        val meta = listOfNotNull(episodeLabel(item.season, item.episode), "${minutes}m").joinToString(" · ")
        return QuickItem(item.ratingKey, title, meta, item.thumb ?: show?.art ?: item.art, titleKey = item.showKey ?: item.ratingKey) to minutes
    }

    private fun interim(snapshot: PlexSnapshot, now: LocalDateTime, zone: ZoneId, fits: LocalTime): HomeContent {
        val upNext = snapshot.continueWatching.take(UP_NEXT)
        return HomeContent(
            picks = snapshot.newest.map { it.toPick(now.toLocalDate(), zone) }
                .ifEmpty { snapshot.classics.take(PICKS).map { it.toClassicPick(now.year, zone) } },
            lenses = mapOf(
                Lens.THROWBACK to snapshot.throwbacks.map {
                    LensPick(
                        it.ratingKey, it.title, meta(it.year, it.durationMs, it.contentRating), "One year ago this week you watched", seen = true,
                        image = it.art, logo = it.logo,
                    )
                },
                Lens.CLASSIC to snapshot.classics.map { it.toClassic(now.year, zone) },
            ),
            quickWatch = interimQuickWatch(snapshot.continueWatching.drop(UP_NEXT), now, fits),
            quickWatchCutoff = fits.format(CLOCK),
            upNext = upNext.map { it.toUpNext() },
            fromPlex = true,
        )
    }

    private fun interimQuickWatch(candidates: List<ContinueEntity>, now: LocalDateTime, cutoff: LocalTime): List<QuickItem> {
        val minutesLeft = java.time.Duration.between(now.toLocalTime(), cutoff).toMinutes().let { if (it < 0) it + 24 * 60 else it }
        return candidates
            .map { it to remainingMinutes(it) }
            .filter { (_, left) -> left in 1..minOf(QUICK_MAX_MINUTES, minutesLeft) }
            .sortedBy { it.second }
            .take(2)
            .map { (item, left) -> item.toQuick(left) }
    }

    private fun ContinueEntity.toQuick(left: Long): QuickItem {
        val time = if ((viewOffsetMs ?: 0) > 0) "${left}m left" else "${left}m"
        return QuickItem(
            ratingKey, showTitle ?: title, listOfNotNull(episodeLabel(season, episode), time).joinToString(" · "), thumb ?: art,
            titleKey = showKey ?: ratingKey,
        )
    }

    private fun ItemEntity.toPick(today: LocalDate, zone: ZoneId) = Pick(
        id = ratingKey,
        title = title,
        meta = meta(year, durationMs, contentRating),
        reason = addedAt?.let { added(Instant.ofEpochSecond(it).atZone(zone).toLocalDate(), today) } ?: "New in your library",
        image = art,
        trailerKey = trailerKey,
        year = year,
        logo = logo,
    )

    private fun ItemEntity.toClassicPick(thisYear: Int, zone: ZoneId) = toClassic(thisYear, zone).let {
        Pick(it.id, it.title, it.meta, it.line, image = art, trailerKey = trailerKey, year = year, logo = logo)
    }

    private fun ItemEntity.toClassic(thisYear: Int, zone: ZoneId): LensPick {
        val age = year?.let { thisYear - it }
        val lastYear = lastViewedAt?.let { Instant.ofEpochSecond(it).atZone(zone).year }
        val line = when {
            viewCount == 0 || lastYear == null -> if (age != null) "$age years old and you've never seen it" else "You've never seen it"
            age != null -> "$age years old · last watched in $lastYear"
            else -> "Last watched in $lastYear"
        }
        return LensPick(ratingKey, title, meta(year, durationMs, contentRating), line, seen = viewCount > 0, image = art, logo = logo)
    }

    private fun ContinueEntity.toUpNext(): UpNextItem {
        val left = remainingMinutes(this)
        val label = episodeLabel(season, episode) ?: year?.toString() ?: ""
        val duration = durationMs
        val progress = if (duration != null && duration > 0) ((viewOffsetMs ?: 0).toFloat() / duration).coerceIn(0f, 1f) else 0f
        val time = if ((viewOffsetMs ?: 0) > 0) "${left}m left" else "${left}m"
        return UpNextItem(
            ratingKey, showTitle ?: title, label, time, progress, image = if (type == "episode") thumb ?: showArt else art ?: thumb,
            titleKey = showKey ?: ratingKey,
        )
    }

    private fun episodeLabel(season: Int?, episode: Int?) = if (season != null && episode != null) "S${season}E$episode" else null

    private fun remainingMinutes(item: ContinueEntity): Long =
        (((item.durationMs ?: 0) - (item.viewOffsetMs ?: 0)) / 60_000).coerceAtLeast(0)

    private fun added(day: LocalDate, today: LocalDate): String = when (val days = ChronoUnit.DAYS.between(day, today)) {
        in Long.MIN_VALUE..0 -> "Added to Plex today"
        1L -> "Added to Plex yesterday"
        in 2..13 -> "Added to Plex $days days ago"
        in 14..59 -> "Added to Plex ${days / 7} weeks ago"
        else -> "New to you since ${day.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}"
    }

    fun meta(year: Int?, durationMs: Long?, contentRating: String?): String =
        listOfNotNull(year?.toString(), durationMs?.let(::runtime), contentRating).joinToString(" · ")

    private fun runtime(ms: Long): String {
        val minutes = ms / 60_000
        return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
    }
}
