package com.phonoodlesramen.actioncenter.context

import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The context chip's mode [REQ]. [ContextSchedule] says when each runs (M1, M2), and each
 * reorders what the cards pick (M3), never where the cards are.
 */
enum class ContextMode(val label: String) {
    WEEKNIGHT_EVENING("Weeknight evening"),
    WEEKEND_EVENING("Weekend evening"),
    WEEKEND_MORNING("Weekend morning"),
    LATE_NIGHT("Late night"),
    DAYTIME("Daytime");

    companion object {
        /** On the default schedule. */
        fun at(time: LocalDateTime): ContextMode = ContextSchedule.DEFAULT.modeAt(time)
    }
}

fun greetingAt(time: LocalTime): String = when {
    time >= LocalTime.of(5, 0) && time < LocalTime.NOON -> "Good morning"
    time >= LocalTime.NOON && time < LocalTime.of(18, 0) -> "Good afternoon"
    else -> "Good evening"
}
