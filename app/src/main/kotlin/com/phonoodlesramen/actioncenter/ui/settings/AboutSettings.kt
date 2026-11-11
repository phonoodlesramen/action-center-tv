package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.BrandIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.update.UpdateInfo
import com.phonoodlesramen.actioncenter.update.UpdateLine
import com.phonoodlesramen.actioncenter.update.UpdateMode
import com.phonoodlesramen.actioncenter.update.UpdateStatus
import com.phonoodlesramen.actioncenter.update.UpdatesController
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A licence the app ships the text of, in `assets/licences/`. */
internal enum class Licence(val title: String, val asset: String) {
    GPL_3("GNU General Public License v3.0", "licences/GPL-3.0.txt"),
    LGPL_3("GNU Lesser General Public License v3.0", "licences/LGPL-3.0.txt"),
    APACHE_2("Apache License 2.0", "licences/Apache-2.0.txt"),
    BSD_2_DAV1D("BSD 2-Clause License (dav1d)", "licences/BSD-2-Clause-dav1d.txt"),
    MIT_QRCODEGEN("MIT License (QR Code generator)", "licences/MIT-qrcodegen.txt"),
    MIT_OCTICONS("MIT License (Octicons)", "licences/MIT-octicons.txt"),
}

/** A part of the app, or one it's built from, and its licence. */
internal data class OpenSourcePart(val name: String, val what: String, val licence: Licence)

/**
 * What the APK carries and under which licence (O1; `docs/third-party-notices.md` is the
 * same list for the whole repository). The station's own libraries aren't in the APK.
 */
internal object OpenSource {
    /** Where the source is, the app's and the station's: GPL-3.0 says to tell people. */
    const val SOURCE = "github.com/phonoodlesramen/action-center-tv"

    /** The maintainer, by their GitHub name, and their profile (the copyright line's button). */
    const val AUTHOR = "phonoodlesramen"
    const val AUTHOR_URL = "https://github.com/phonoodlesramen"

    val PARTS = listOf(
        OpenSourcePart("Action Center", "This app, GPL-3.0-or-later", Licence.GPL_3),
        OpenSourcePart("NextLib", "The guide player's audio decoders (DTS and others), GPL-3.0", Licence.GPL_3),
        OpenSourcePart("FFmpeg", "Inside NextLib, LGPL-3.0-or-later as NextLib builds it", Licence.LGPL_3),
        OpenSourcePart("Mbed TLS", "Inside NextLib's FFmpeg", Licence.APACHE_2),
        OpenSourcePart("dav1d", "Inside NextLib's FFmpeg", Licence.BSD_2_DAV1D),
        OpenSourcePart(
            "AndroidX, Jetpack Compose, Compose for TV, Media3, Room, WorkManager, DataStore",
            "What the app is built with",
            Licence.APACHE_2,
        ),
        OpenSourcePart("Kotlin, kotlinx.coroutines, kotlinx.serialization", "The language and its libraries", Licence.APACHE_2),
        OpenSourcePart("OkHttp, Okio", "Talking to Plex, the guide station and the weather", Licence.APACHE_2),
        OpenSourcePart("Coil", "Loading pictures", Licence.APACHE_2),
        OpenSourcePart("QR Code generator", "The phone editor's QR code", Licence.MIT_QRCODEGEN),
        OpenSourcePart("Material Symbols", "The icons", Licence.APACHE_2),
        OpenSourcePart("Octicons", "GitHub's mark, by the maintainer's GitHub name", Licence.MIT_OCTICONS),
    )
}

/** Settings › About: what's open, the licences list, or one licence's text. */
private sealed interface AboutPage {
    data object Main : AboutPage
    data object Licences : AboutPage
    data class Text(val part: Int) : AboutPage

    /** The maintainer's GitHub profile, as a QR code: TVs mostly have no browser to open it in. */
    data object Author : AboutPage

    /** An update's release notes on GitHub, as a QR code. */
    data class WhatsNew(val info: UpdateInfo) : AboutPage
}

/** What Settings › About's Updates needs: the status, and what its pills do. */
internal class AboutUpdates(
    val status: UpdateStatus,
    val nowMillis: Long,
    /** Install unknown apps is on for Action Center (Settings › Setup reads it). */
    val canInstall: Boolean,
    val controller: UpdatesController,
    /** TV Settings' Install unknown apps. */
    val onAllow: () -> Unit,
)

/**
 * Settings › About: the version and its **Updates** ([Updates]), GPL-3.0's notice (that it's
 * free software under the GPL, with no warranty, and where the source is), and **Open-source
 * licences**: the app and every library it carries, each opening its licence's full text. The
 * copyright line's name is a button with GitHub's mark: OK shows the maintainer's GitHub profile
 * as a QR code. Back goes back a page at a time, and lands on the button that left.
 */
@Composable
internal fun ColumnScope.AboutSettings(version: String, openMeteoCredit: String, updates: AboutUpdates? = null) {
    var page by remember { mutableStateOf<AboutPage>(AboutPage.Main) }
    val author = remember { FocusRequester() }
    val licences = remember { FocusRequester() }
    val whatsNew = remember { FocusRequester() }
    // The button a page was opened from: Back lands on it.
    var returnTo by remember { mutableStateOf<FocusRequester?>(null) }
    // Back from the list or a text goes back a page; on the main page Settings' own Back closes it.
    BackHandler(enabled = page != AboutPage.Main) {
        page = if (page is AboutPage.Text) AboutPage.Licences else AboutPage.Main
    }
    when (val current = page) {
        AboutPage.Main -> {
            LaunchedEffect(Unit) {
                returnTo?.let { runCatching { it.requestFocus() } }
                returnTo = null
            }
            Text(if (version.isEmpty()) "Action Center" else "Action Center $version", style = MaterialTheme.typography.titleSmall)
            Note("An ad-free home screen for Android TV. A hobby project, sideloaded over ADB.")
            if (updates != null) {
                Updates(
                    updates = updates,
                    whatsNew = whatsNew,
                    onWhatsNew = {
                        returnTo = whatsNew
                        page = AboutPage.WhatsNew(it)
                    },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Note("Copyright © 2026")
                PillButton(
                    text = OpenSource.AUTHOR,
                    icon = BrandIcons.GitHub,
                    small = true,
                    onClick = {
                        returnTo = author
                        page = AboutPage.Author
                    },
                    modifier = Modifier.focusRequester(author),
                    colors = pillButtonColors(container = Palette.Surface2),
                )
                Note("and contributors.")
            }
            Note(
                "Action Center is free software: you can share and change it under the GNU General Public License, " +
                    "version 3 or later. It comes with no warranty.",
            )
            Note("Its source, and the guide station's: ${OpenSource.SOURCE}")
            PillButton(
                text = "Open-source licences",
                onClick = {
                    returnTo = licences
                    page = AboutPage.Licences
                },
                modifier = Modifier.focusRequester(licences),
                colors = pillButtonColors(container = Palette.Surface2),
            )
            Note(openMeteoCredit)
        }
        AboutPage.Licences -> Licences(onOpen = { page = AboutPage.Text(it) })
        is AboutPage.Text -> LicenceText(OpenSource.PARTS[current.part])
        AboutPage.Author -> Author()
        is AboutPage.WhatsNew -> WhatsNew(current.info)
    }
}

/**
 * Settings › About's Updates: what the last check found and what's happening now, Check for
 * updates, Install (or Allow installs, until Install unknown apps is on), What's new, and how
 * often it checks by itself. A build that doesn't update says why, and nothing else. The pills
 * stay while the work runs, so focus never lands on one that went: a press then does nothing.
 */
@Composable
private fun ColumnScope.Updates(updates: AboutUpdates, whatsNew: FocusRequester, onWhatsNew: (UpdateInfo) -> Unit) {
    val status = updates.status
    val controller = updates.controller
    Eyebrow("Updates", Modifier.padding(top = 4.dp))
    val line = UpdateLine.of(status, updates.nowMillis, ZoneId.systemDefault())
    Text(line.text, style = MaterialTheme.typography.bodyMedium, color = if (line.caution) Palette.Caution else Palette.Text)
    if (!status.enabled) return
    val available = status.available
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(
            text = "Check for updates",
            icon = AppIcons.Sync,
            onClick = controller::checkNow,
            colors = pillButtonColors(container = Palette.Surface2),
        )
        if (available != null) {
            if (updates.canInstall) {
                PillButton(text = "Install ${available.versionName}", onClick = controller::install)
            } else {
                PillButton(text = "Allow installs", onClick = updates.onAllow)
            }
            if (available.notesUrl.isNotEmpty()) {
                PillButton(
                    text = "What's new",
                    onClick = { onWhatsNew(available) },
                    modifier = Modifier.focusRequester(whatsNew),
                    colors = pillButtonColors(container = Palette.Surface2),
                )
            }
        }
    }
    if (available != null && !updates.canInstall) {
        Note("Android needs Install unknown apps turned on for Action Center before it installs an update. Allow installs opens it.")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Check by itself", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 8.dp))
        UpdateMode.entries.forEach { mode ->
            PillButton(
                text = mode.label,
                icon = if (mode == status.mode) AppIcons.Check else null,
                small = true,
                onClick = { controller.setMode(mode) },
                colors = pillButtonColors(container = Palette.Surface2),
            )
        }
    }
    Note(
        when (status.mode) {
            UpdateMode.ASK -> "Once a day it looks for a new release and shows it here. Nothing downloads until you press Install."
            UpdateMode.OFF -> "It never looks by itself. Check for updates still does."
        } + " Updates come only from github.com/${status.repo}'s releases, and only if they're signed with the same key as " +
            "this copy. On Android 11, Android asks once more before it installs.",
    )
}

/** About's words for each [UpdateMode]. */
private val UpdateMode.label: String
    get() = when (this) {
        UpdateMode.ASK -> "Ask me"
        UpdateMode.OFF -> "Off"
    }

/** An update's release notes on GitHub, as a QR code to scan: TVs mostly have no browser. */
@Composable
private fun WhatsNew(info: UpdateInfo) {
    Text("What's new in ${info.versionName}", style = MaterialTheme.typography.titleSmall)
    QrImage(info.notesUrl, Modifier.size(QR_SIZE.dp), description = "QR code for ${info.versionName}'s release notes")
    Note("Scan it with your phone for the release's notes on GitHub. Back comes back to About.")
}

/** The maintainer's GitHub profile: a QR code to scan with a phone, and the address to type. */
@Composable
private fun Author() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(BrandIcons.GitHub, contentDescription = null, modifier = Modifier.size(22.dp), tint = Palette.Text)
        Text("${OpenSource.AUTHOR} on GitHub", style = MaterialTheme.typography.titleSmall)
    }
    QrImage(OpenSource.AUTHOR_URL, Modifier.size(QR_SIZE.dp), description = "QR code for ${OpenSource.AUTHOR}'s GitHub profile")
    Note("Scan it with your phone, or go to ${OpenSource.AUTHOR_URL.removePrefix("https://")}. Back comes back to About.")
}

@Composable
private fun Licences(onOpen: (Int) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Text("Open-source licences", style = MaterialTheme.typography.titleSmall)
    Note("Action Center and what it's built with. OK shows a licence; Back comes back here.")
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OpenSource.PARTS.forEachIndexed { index, part ->
            Surface(
                onClick = { onOpen(index) },
                modifier = Modifier.fillMaxWidth().let { if (index == 0) it.focusRequester(first) else it },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Palette.Surface,
                    contentColor = Palette.Text,
                    focusedContainerColor = Palette.Surface3,
                    focusedContentColor = Palette.Text,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text(part.name, style = MaterialTheme.typography.titleSmall)
                    Text("${part.what} · ${part.licence.title}", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
                }
            }
        }
    }
}

/**
 * One licence's full text, in a box of its own that Up and Down scroll (a D-pad has no other way
 * through 35 KB of GPL). Read from the APK's assets off the main thread.
 */
@Composable
private fun LicenceText(part: OpenSourcePart) {
    val context = LocalContext.current
    val text by produceState<String?>(initialValue = null, part.licence) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.assets.open(part.licence.asset).bufferedReader().use { it.readText() } }.getOrNull()
        }
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val box = remember { FocusRequester() }
    var focusedOnce by rememberSaveable(part.licence) { mutableStateOf(false) }
    LaunchedEffect(text) {
        if (text != null && !focusedOnce) {
            runCatching { box.requestFocus() }
            focusedOnce = true
        }
    }
    Text("${part.name} · ${part.licence.title}", style = MaterialTheme.typography.titleSmall)
    Note("Up and Down scroll; Back goes back to the list.")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(LICENCE_BOX)
            .background(Palette.Surface, RoundedCornerShape(10.dp))
            .focusRequester(box)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = when (event.key) {
                    Key.DirectionDown -> SCROLL_STEP
                    Key.DirectionUp -> -SCROLL_STEP
                    else -> return@onKeyEvent false
                }
                scope.launch { scroll.animateScrollBy(step) }
                true
            }
            .focusable()
            .semantics { contentDescription = "${part.licence.title}, the full text" }
            .verticalScroll(scroll)
            .padding(16.dp),
    ) {
        Text(text ?: "…", style = MaterialTheme.typography.bodySmall, color = Palette.Text)
    }
}

private val LICENCE_BOX = 360.dp
private const val SCROLL_STEP = 240f
