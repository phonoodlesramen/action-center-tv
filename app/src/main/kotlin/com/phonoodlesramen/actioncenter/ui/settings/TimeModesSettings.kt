package com.phonoodlesramen.actioncenter.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.context.ContextMode
import com.phonoodlesramen.actioncenter.context.ContextSchedule
import com.phonoodlesramen.actioncenter.context.Hours
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Settings › Time & modes: Quick Watch's cutoff for weeknights and for Friday and Saturday
 * nights (M4), and when each context mode runs (M1, M2), in 15-minute steps. A change that
 * would leave a mode without hours or two modes at once is refused, and the sentence why
 * shows under the modes until the next change goes through.
 */
@Composable
fun ColumnScope.TimeAndModesSettings(state: HomeUiState, actions: SettingsActions) {
    var refused by remember { mutableStateOf<String?>(null) }
    Eyebrow("Quick Watch", Modifier.padding(top = 4.dp))
    Column(verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        CutoffRow("Weeknights", state.quickWatchCutoff, actions::setQuickWatchCutoff)
        CutoffRow("Friday and Saturday", state.weekendCutoff, actions::setWeekendCutoff)
    }
    Note("From half an hour before it, Quick Watch shows what fits in the next 45 minutes instead.")

    // The modes come into view whole when focus reaches them, so Daytime and a refusal below the last buttons show too.
    val modes = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(refused) { if (refused != null) modes.bringIntoView() }
    Column(
        modifier = Modifier
            .padding(top = 8.dp)
            .bringIntoViewRequester(modes)
            .onFocusChanged { if (it.hasFocus) scope.launch { modes.bringIntoView() } },
        verticalArrangement = Arrangement.spacedBy(ROW_GAP),
    ) {
        Eyebrow("Context modes")
        Note(
            "Each mode reorders Tonight's Pick: finish what you started on weeknights, longer films at the weekend, " +
                "shorter late at night, family-friendly on weekend mornings.",
        )
        MODES.forEach { (mode, days) ->
            ModeRow(mode, days, state.schedule.hours(mode), now = mode == state.mode) { hours ->
                val problem = state.schedule.with(mode, hours).problem()
                refused = problem
                if (problem == null) actions.setModeHours(mode, hours)
            }
        }
        refused?.let {
            Text("$it.", style = MaterialTheme.typography.bodyMedium, color = Palette.Caution)
        }
    }
}

/** The modes in Settings' order, with their fixed days (M1). */
private val MODES = listOf(
    ContextMode.WEEKNIGHT_EVENING to "Sunday to Thursday",
    ContextMode.WEEKEND_EVENING to "Friday and Saturday",
    ContextMode.WEEKEND_MORNING to "Saturday and Sunday",
    ContextMode.LATE_NIGHT to "Every day",
    ContextMode.DAYTIME to "Any other time",
)

/** Quick Watch's cutoff on some nights: − and + move it a quarter of an hour. */
@Composable
private fun CutoffRow(nights: String, cutoff: LocalTime, onChange: (LocalTime) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(nights, style = MaterialTheme.typography.titleSmall, maxLines = 1, modifier = Modifier.width(LABEL_WIDTH))
        TimeStepper(cutoff, "$nights cutoff", onChange)
    }
}

/** A mode, its days and, unless it's Daytime, its start and end. The mode now is in the accent. */
@Composable
private fun ModeRow(mode: ContextMode, days: String, hours: Hours?, now: Boolean, onChange: (Hours) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(LABEL_WIDTH)) {
            Text(
                text = mode.label + if (now) " (now)" else "",
                style = MaterialTheme.typography.titleSmall,
                color = if (now) LocalAccent.current else Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(days, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (hours != null) {
            TimeStepper(hours.start, "${mode.label} start") { onChange(hours.copy(start = it)) }
            Text("to", style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim, modifier = Modifier.padding(horizontal = 6.dp))
            TimeStepper(hours.end, "${mode.label} end") { onChange(hours.copy(end = it)) }
        }
    }
}

/** A time with − and + either side, a quarter of an hour a step. [what] names the buttons for TalkBack and tests. */
@Composable
private fun TimeStepper(time: LocalTime, what: String, onChange: (LocalTime) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        StepButton("−", "$what earlier") { onChange(time.minusMinutes(ContextSchedule.STEP_MINUTES)) }
        Text(
            text = time.format(CLOCK),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.width(TIME_WIDTH),
        )
        StepButton("+", "$what later") { onChange(time.plusMinutes(ContextSchedule.STEP_MINUTES)) }
    }
}

/** A narrow pill, so a mode's start and end fit on its row at 115%. */
@Composable
private fun StepButton(text: String, description: String, onClick: () -> Unit) {
    PillButton(text = text, onClick = onClick, small = true, modifier = Modifier.width(STEP_WIDTH).semantics { contentDescription = description })
}

// A mode's row is 490 dp: it fits the detail column at 115% (572 / 1.15).
private val LABEL_WIDTH = 168.dp
private val TIME_WIDTH = 68.dp
private val STEP_WIDTH = 38.dp
private val ROW_GAP = 8.dp
private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
