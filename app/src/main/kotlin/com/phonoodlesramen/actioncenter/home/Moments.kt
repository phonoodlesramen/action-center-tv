package com.phonoodlesramen.actioncenter.home

import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.home.TonightStrip.Slot
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * A moment (Settings › Look › Change with the time of day, H3): what leads the home for the time
 * of day. It changes what's in the home's lead slots, never their shape, and only when you come
 * back Home ([com.phonoodlesramen.actioncenter.ui.home.HomeScreen]).
 */
enum class Moment {
    /** The finish-it pick, and a bigger Quick Watch. */
    WEEKNIGHT,

    /** The family lens, and Who's watching with the kids one press away (K11). */
    WEEKEND_MORNING,

    /** The live NFL game, and around the league. */
    GAME_DAY,

    /** A comfort rewatch, and what fits in the next 45 minutes, dimmed (M3). */
    LATE_NIGHT,
}

/** The moments' rules. Pure, so they're unit-tested. */
object Moments {
    /**
     * The moment at a context [mode] (M1-M3), or game day while the game-day card is up ([gameOn],
     * F1), which wins over the time of day. Weekend evenings and daytime keep the usual home.
     */
    fun at(mode: ContextMode, gameOn: Boolean): Moment? = when {
        gameOn -> Moment.GAME_DAY
        mode == ContextMode.LATE_NIGHT -> Moment.LATE_NIGHT
        mode == ContextMode.WEEKEND_MORNING -> Moment.WEEKEND_MORNING
        mode == ContextMode.WEEKNIGHT_EVENING -> Moment.WEEKNIGHT
        else -> null
    }

    /**
     * Marquee's strip in a [moment]: what leads it, then the rest. The lens card's title is the
     * family lens on weekend mornings and the comfort rewatch late at night (the lens lead follows
     * the mode, [com.phonoodlesramen.actioncenter.content.LensSchedule]).
     */
    fun stripOrder(moment: Moment?): List<Slot> = when (moment) {
        null -> TonightStrip.DEFAULT_ORDER
        Moment.WEEKNIGHT -> listOf(Slot.PICK, Slot.QUICK_WATCH, Slot.UP_NEXT, Slot.LENS, Slot.LIVE, Slot.MORE_LENS)
        Moment.WEEKEND_MORNING -> listOf(Slot.LENS, Slot.PICK, Slot.UP_NEXT, Slot.LIVE, Slot.QUICK_WATCH, Slot.MORE_LENS)
        Moment.GAME_DAY -> listOf(Slot.GAME, Slot.PICK, Slot.LIVE, Slot.UP_NEXT, Slot.QUICK_WATCH, Slot.LENS)
        Moment.LATE_NIGHT -> listOf(Slot.LENS, Slot.QUICK_WATCH, Slot.PICK, Slot.UP_NEXT, Slot.LIVE, Slot.MORE_LENS)
    }

    /**
     * Which evening [time] belongs to, for Keep the usual layout: a night past midnight is the
     * evening before's, so keeping it at 11:30 PM still holds at 12:30 AM.
     */
    fun evening(time: LocalDateTime): LocalDate = time.minusHours(EVENING_ENDS_HOUR).toLocalDate()

    /** What Keep the usual layout stores: the mode and its evening. */
    fun held(mode: ContextMode, time: LocalDateTime): String = "${mode.name}@${evening(time)}"

    private const val EVENING_ENDS_HOUR = 5L
}
