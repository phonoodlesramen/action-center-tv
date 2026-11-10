package com.phonoodlesramen.actioncenter.content

import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import kotlin.random.Random

/**
 * The lens card's lenses (U17), in Settings › Lenses order. Each shows one kind of
 * title from the library; Phase 3 writes the rules that find them.
 */
enum class Lens(val title: String, val rule: String) {
    THROWBACK("Throwback", "One year ago today, release anniversaries, and titles you loved but haven't gone back to"),
    CLASSIC("Classic", "Released 25 or more years ago, well rated, and never watched or not in 5 years"),
    FAMILY("For the family", "Rated PG or lower, and over by 8:30 PM"),
    ACTION("Action & adventure", "Action and adventure you haven't seen in 2 years, by your taste"),
    HIDDEN_GEM("Hidden gem", "Your library's top-rated 15%, there for 2 months, and never played"),
    FINISH_SERIES("Finish the series", "The next film in a collection you've started, and shows you stopped"),
    SEASONAL("Seasonal", "Halloween from mid-October, holidays in December: your collections and labels first"),
    COMFORT("Comfort rewatch", "The films you've finished most, and episodes of the shows you watch most"),
}

/** When the Seasonal lens has something to say. Out of season it's skipped. */
enum class Season(val title: String) {
    HALLOWEEN("Halloween"),
    HOLIDAYS("Holidays");

    companion object {
        fun at(date: LocalDate): Season? = when {
            date.month == Month.OCTOBER && date.dayOfMonth >= 15 -> HALLOWEEN
            date.month == Month.DECEMBER -> HOLIDAYS
            else -> null
        }
    }
}

/**
 * One title on the lens card. [line] is its reason [REQ]; [seen] makes the button "Watch
 * again". [titleKey] is the movie, or an episode's show: what Don't show this hides.
 */
data class LensPick(
    val id: String,
    val title: String,
    val meta: String,
    val line: String,
    val seen: Boolean,
    val image: String? = null,
    val titleKey: String = id,
    /** Plex logo path (Phase 12): the lens card shows it in the title's place. Null shows the title. */
    val logo: String? = null,
)

data class LensSlot(val lens: Lens, val pick: LensPick)

/** Which lens leads (spec §4) [PROP]: it follows the context mode on [ContextSchedule]. */
object LensSchedule {
    /** When [lens] leads, for Settings › Lenses. Null: it only comes up through Next. */
    fun leads(lens: Lens): String? = when (lens) {
        Lens.THROWBACK -> "Leads on weeknight evenings"
        Lens.CLASSIC -> "Leads in the daytime"
        Lens.FAMILY -> "Leads on weekend mornings"
        Lens.ACTION -> "Leads on weekend evenings"
        Lens.SEASONAL -> "Leads on weekend evenings in season"
        Lens.COMFORT -> "Leads late at night"
        Lens.HIDDEN_GEM, Lens.FINISH_SERIES -> null
    }

    fun lead(time: LocalDateTime, schedule: ContextSchedule = ContextSchedule.DEFAULT): Lens = when (schedule.modeAt(time)) {
        ContextMode.LATE_NIGHT -> Lens.COMFORT
        ContextMode.WEEKEND_MORNING -> Lens.FAMILY
        ContextMode.WEEKNIGHT_EVENING -> Lens.THROWBACK
        ContextMode.WEEKEND_EVENING -> if (Season.at(time.toLocalDate()) != null) Lens.SEASONAL else Lens.ACTION
        ContextMode.DAYTIME -> Lens.CLASSIC
    }
}

/** Settings › Lenses' card settings (Phase 14, L1-L5). Per TV, as every setting. */
data class LensPrefs(
    /** L1: the card moves on by itself every [seconds], while you're not on it. */
    val rotate: Boolean = true,
    /** L2: the notch, 1 (slowest) to 5 (fastest). */
    val speed: Int = DEFAULT_SPEED,
    /** L4: the lenses mixed, a new order each time round. */
    val shuffle: Boolean = true,
    /** L5: when a title you pass over rests. */
    val moveOn: LensMoveOn = LensMoveOn.THREE_DAYS,
) {
    val seconds: Int get() = SPEEDS[speed.coerceIn(1, SPEEDS.size) - 1]

    /** How often it moves on; null while it doesn't. */
    val rotateSeconds: Int? get() = seconds.takeIf { rotate }

    companion object {
        /** The five notches, slow to fast, in seconds. */
        val SPEEDS = listOf(60, 45, 30, 20, 10)
        const val DEFAULT_SPEED = 3
    }
}

/**
 * L5: how many days a title can be on the lens card without being played before it rests
 * for 30 days ([days]); [WATCHED] never rests one, so only watching moves it on.
 */
enum class LensMoveOn(val days: Int?, val label: String) {
    DAY(1, "After 1 day"),
    THREE_DAYS(3, "After 3 days"),
    WEEK(7, "After a week"),
    WATCHED(null, "Once it's watched"),
}

object LensRotation {
    /**
     * Everything the lens card can show: the leading lens first, then round the rest
     * in Settings order, or with [shuffle] (L4) the lenses mixed by [mix] with that seed.
     * Skips lenses that are off, Seasonal out of season, and whatever Tonight's Pick is
     * showing (U20).
     */
    fun slots(
        picks: Map<Lens, List<LensPick>>,
        enabled: Set<Lens>,
        lead: Lens,
        season: Season?,
        tonightsPick: String?,
        shuffle: Long? = null,
    ): List<LensSlot> {
        val start = Lens.entries.indexOf(lead)
        val inOrder = (Lens.entries.drop(start) + Lens.entries.take(start))
            .filter { it in enabled && (it != Lens.SEASONAL || season != null) }
            .flatMap { lens -> picks[lens].orEmpty().filter { it.id != tonightsPick }.map { LensSlot(lens, it) } }
        return if (shuffle == null) inOrder else mix(inOrder, shuffle)
    }

    /** One shuffle a day and time round ([LensCursor.pass]), so Next and Back agree all pass. */
    fun seed(day: LocalDate, pass: Int): Long = day.toEpochDay() * 10_000 + pass

    /**
     * L4: every title once, the lenses mixed. The first slot (the lead's best) stays first;
     * then each title is from a lens drawn at random, likelier the more it has left, never
     * the one just shown, and only from lenses that leave the rest able to alternate. So two
     * of a lens meet only when nothing else could have gone between. A lens keeps its own
     * order, best first.
     */
    internal fun mix(slots: List<LensSlot>, seed: Long): List<LensSlot> {
        if (slots.size < 3) return slots
        val random = Random(seed)
        val left = slots.groupByTo(LinkedHashMap()) { it.lens }.mapValues { ArrayDeque(it.value) }
        val mixed = ArrayList<LensSlot>(slots.size)
        var last = slots.first().lens
        mixed += left.getValue(last).removeFirst()
        while (mixed.size < slots.size) {
            val open = left.filterValues { it.isNotEmpty() }.keys.toList()
            val choices = open.filter { it != last }.ifEmpty { open }
            val lens = draw(choices.filter { canAlternate(left, after = it) }.ifEmpty { choices }, left, random)
            mixed += left.getValue(lens).removeFirst()
            last = lens
        }
        return mixed
    }

    /**
     * Whether what's [left], once one of [after] is shown, can still go with no lens twice in
     * a row: [after] no more than all the others, and any other lens no more than one over.
     */
    private fun canAlternate(left: Map<Lens, ArrayDeque<LensSlot>>, after: Lens): Boolean {
        val counts = left.mapValues { (lens, titles) -> titles.size - if (lens == after) 1 else 0 }
        val total = counts.values.sum()
        return counts.all { (lens, n) -> n <= total - n + if (lens == after) 0 else 1 }
    }

    /** One of [choices], each as likely as it has titles left. */
    private fun draw(choices: List<Lens>, left: Map<Lens, ArrayDeque<LensSlot>>, random: Random): Lens {
        var draw = random.nextInt(choices.sumOf { left.getValue(it).size })
        for (lens in choices) {
            draw -= left.getValue(lens).size
            if (draw < 0) return lens
        }
        return choices.last()
    }
}

/**
 * More like this (S10): the lens card shows titles like Tonight's Pick [sourceId] instead
 * of the rotation, [index] being the one shown.
 */
data class MoreLike(val sourceId: String, val sourceTitle: String, val index: Int = 0)

/**
 * The rotation as it runs each time round: [pass]'s order. In Settings order every pass is
 * the same; shuffled (L4) each is a new mix.
 */
fun interface LensDeck {
    fun slots(pass: Int): List<LensSlot>
}

/**
 * Where the lens card is in the rotation. It remembers the title rather than a
 * position, so a new Tonight's Pick or a settings change doesn't jump the card; and
 * the lead it was set under, so a lens picked by hand, or More like this, lasts until the
 * time moves on. [pass] is how many times round it's been, which shuffle it's in.
 */
data class LensCursor(
    val lead: Lens? = null,
    val lens: Lens? = null,
    val id: String? = null,
    val moreLike: MoreLike? = null,
    val pass: Int = 0,
) {
    fun current(deck: LensDeck, lead: Lens): LensSlot? = current(deck.slots(pass), lead)

    fun current(slots: List<LensSlot>, lead: Lens): LensSlot? {
        if (slots.isEmpty()) return null
        if (this.lead != lead) return slots.first()
        return slots.firstOrNull { it.lens == lens && it.pick.id == id }
            ?: slots.firstOrNull { it.lens == lens }
            ?: slots.first()
    }

    /** The next title, running on into the next lens, then round again in the next pass's order. */
    fun next(deck: LensDeck, lead: Lens): LensCursor {
        val slots = deck.slots(pass)
        val shown = current(slots, lead) ?: return this
        val at = slots.indexOf(shown) + 1
        if (at < slots.size) return at(slots[at], lead, pass)
        return deck.slots(pass + 1).firstOrNull()?.let { at(it, lead, pass + 1) } ?: this
    }

    /** Back past what the card remembers (L3): the title before, from the last pass's end before the first. */
    fun previous(deck: LensDeck, lead: Lens): LensCursor {
        val slots = deck.slots(pass)
        val shown = current(slots, lead) ?: return this
        val at = slots.indexOf(shown) - 1
        if (at >= 0) return at(slots[at], lead, pass)
        return deck.slots(pass - 1).lastOrNull()?.let { at(it, lead, pass - 1) } ?: this
    }

    /** The first title of the next lens that has one, in this pass. */
    fun nextLens(deck: LensDeck, lead: Lens): LensCursor {
        val slots = deck.slots(pass)
        val shown = current(slots, lead) ?: return this
        val from = slots.indexOf(shown)
        val other = (1 until slots.size).map { slots[(from + it) % slots.size] }.firstOrNull { it.lens != shown.lens }
        return at(other ?: shown, lead, pass)
    }

    private fun at(slot: LensSlot, lead: Lens, pass: Int) = LensCursor(lead, slot.lens, slot.pick.id, pass = pass)
}
