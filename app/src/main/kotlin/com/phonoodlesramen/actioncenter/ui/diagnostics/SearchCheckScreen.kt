package com.phonoodlesramen.actioncenter.ui.diagnostics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.search.SearchCheck
import com.phonoodlesramen.actioncenter.search.SearchCheckResult
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/**
 * Diagnostics › Search check (Phase 15, plan step 4): each line is one piece Search leans on
 * and what this TV got from it. The buttons below try, on this TV, what only the TV can show:
 * Google's search with words, and the catalog title's links in their apps (each opens an app).
 */
@Composable
fun SearchCheckScreen(
    state: SearchCheckResult,
    onRun: () -> Unit,
    onGoogle: (String) -> Unit,
    onOpenLink: (url: String, service: String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        onRun()
        runCatching { first.requestFocus() }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Palette.Background).padding(horizontal = 48.dp, vertical = 27.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Search check", style = MaterialTheme.typography.headlineSmall)
        Text(
            "What this TV gets from each piece Search leans on. Counts, names and answers only: your Plex token is never shown.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextDim,
        )
        PillButton(text = if (state.done) "Run again" else "Checking…", onClick = onRun, modifier = Modifier.focusRequester(first))
        state.lines.forEach { line ->
            Column {
                Text(line.label, style = MaterialTheme.typography.titleSmall)
                Text(line.result, style = MaterialTheme.typography.bodyMedium, color = if (line.ok) Palette.Text else Palette.Caution)
            }
        }
        Eyebrow("Try on this TV (each opens an app)", Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = "Google search with “${SearchCheck.CATALOG_WORDS}”",
                small = true,
                onClick = { onGoogle(SearchCheck.CATALOG_WORDS) },
                colors = pillButtonColors(container = Palette.Surface2),
            )
            state.offers.filter { it.url != null }.distinctBy { it.platform }.forEach { offer ->
                PillButton(
                    text = "${state.title?.title.orEmpty()} in ${offer.title}",
                    small = true,
                    onClick = { onOpenLink(offer.url!!, offer.title) },
                    colors = pillButtonColors(container = Palette.Surface2),
                )
            }
        }
    }
}
