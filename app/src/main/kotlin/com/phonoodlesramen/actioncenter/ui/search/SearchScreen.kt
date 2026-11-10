package com.phonoodlesramen.actioncenter.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.guide.GuideCardState
import com.phonoodlesramen.actioncenter.home.ChannelButton
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.search.LibraryAnswer
import com.phonoodlesramen.actioncenter.search.LibrarySearch
import com.phonoodlesramen.actioncenter.search.OnNowItem
import com.phonoodlesramen.actioncenter.search.SearchChip
import com.phonoodlesramen.actioncenter.search.SearchItem
import com.phonoodlesramen.actioncenter.search.SearchWords
import com.phonoodlesramen.actioncenter.search.StreamingItem
import com.phonoodlesramen.actioncenter.search.StreamingSearch
import com.phonoodlesramen.actioncenter.search.onNow
import com.phonoodlesramen.actioncenter.ui.art.CatalogArtUrls
import com.phonoodlesramen.actioncenter.ui.art.LocalArtUrls
import com.phonoodlesramen.actioncenter.ui.art.TitleArt
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.HomeActions
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.settings.okOpensKeyboard
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay

/** The results' list, for tests that scroll it. */
internal const val SEARCH_RESULTS = "search results"

/** The words settle this long before Plex is asked (Phase 15): a newer search cancels an older one. */
internal const val SEARCH_SETTLE_MILLIS = 250L

/**
 * Search (Phase 15): one field, and as you type, your library first (Plex's own search, Q1),
 * then what's on now, then your streaming apps (Plex's catalog, Q3), then Google for grown-ups
 * (Q4). Words such as "90s", "kids", "movies" or "new" become chips that narrow it (Q2).
 * Opened from the top bar's magnifier or the remote's Search key (Q5); Back closes the
 * keyboard, then the screen.
 *
 * @param initial what to search for on opening (the Search key may bring words).
 * @param streaming Plex's catalog, or [StreamingSearch.None] with Settings › Search › Include streaming apps off.
 * @param apps the apps on this TV the profile on now may open, best first: where the streaming row's titles can play.
 * @param guide the guide as the profile on now sees it (a kid's only what's inside their ceiling).
 * @param networks YouTube TV's networks, when the profile may open YouTube TV.
 */
@Composable
fun SearchScreen(
    profile: ActiveProfile,
    recent: List<String>,
    search: LibrarySearch,
    streaming: StreamingSearch = StreamingSearch.None,
    apps: List<LaunchableApp> = emptyList(),
    guide: GuideCardState,
    networks: List<ChannelButton>,
    nowMillis: Long,
    lateNight: Boolean,
    actions: HomeActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initial: String = "",
) {
    BackHandler(onBack = onBack)
    var text by remember { mutableStateOf(initial) }
    val words = remember(text) { SearchWords.read(text) }
    var answer by remember { mutableStateOf<LibraryAnswer?>(null) }
    /** The words [answer] is for, so the keyboard's Search knows when the results are these words'. */
    var answered by remember { mutableStateOf<SearchWords?>(null) }
    var failed by remember { mutableStateOf(false) }
    var onApps by remember { mutableStateOf<List<StreamingItem>>(emptyList()) }
    val latestSearch by rememberUpdatedState(search)
    val latestStreaming by rememberUpdatedState(streaming)
    val latestApps by rememberUpdatedState(apps)
    LaunchedEffect(words, profile.id) {
        failed = false
        if (words.text.isBlank()) {
            answer = null
            answered = null
            onApps = emptyList()
            return@LaunchedEffect
        }
        delay(SEARCH_SETTLE_MILLIS)
        answer = try {
            latestSearch.search(words, profile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            null
        }
        answered = words
        // Q3: your library first, then the same words in Plex's catalog, less what your library has.
        // The last row stays until this one comes, so it doesn't blink out on every key.
        onApps = try {
            latestStreaming.search(words, profile, latestApps, answer?.items.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    val field = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    // The keyboard's Search goes down to the results, but only once they're these words' and laid out. At
    // once, it could land on a last search the new words take away, and the field, taking focus back before
    // its new words are laid out, crashes (voice typing "heat" on the Shield, 11/03/2026).
    var toResults by remember { mutableStateOf(false) }
    LaunchedEffect(toResults, answered, words) {
        if (!toResults) return@LaunchedEffect
        if (words.text.isBlank()) {
            toResults = false
            return@LaunchedEffect
        }
        if (answered != words) return@LaunchedEffect
        withFrameNanos {}
        if (fieldFocused) focusManager.moveFocus(FocusDirection.Down)
        toResults = false
    }
    /** New words from a last search or a chip: the field takes focus first, while its words still match what's laid out. */
    fun replaceText(new: String) {
        runCatching { field.requestFocus() }
        text = new
    }
    // Opening something keeps the words among the last searches.
    fun opened() = actions.rememberSearch(words.text)
    val live = remember(words, guide, networks, nowMillis) { onNow(words, guide, networks, nowMillis) }
    Column(modifier.fillMaxSize().background(Palette.Background).padding(horizontal = 48.dp, vertical = 27.dp)) {
        SearchField(
            text,
            onChange = { text = it },
            focus = field,
            onSearch = { toResults = true },
            modifier = Modifier.onFocusChanged { fieldFocused = it.isFocused },
        )
        if (words.chips.isNotEmpty()) {
            Chips(words.chips, onRemove = { chip -> replaceText(without(text, chip)) })
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp).testTag(SEARCH_RESULTS),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (words.text.isBlank()) {
                item {
                    Start(recent, onPick = { picked ->
                        replaceText(picked)
                        toResults = true
                    })
                }
                return@LazyColumn
            }
            val found = answer
            if (found?.offline == true) item { Note("Plex can't be reached: searching what's on this TV.") }
            if (failed) item { Note("Search didn't work just now. Try again in a moment.") }
            val items = found?.items.orEmpty()
            items.firstOrNull()?.let { top ->
                item { TopMatch(top, lateNight) { opened(); actions.play(top.ratingKey, top.title) } }
            }
            val rest = items.drop(1)
            val movies = rest.filter { it.type == "movie" }
            val shows = rest.filter { it.type != "movie" }
            if (movies.isNotEmpty()) item { ItemRow("In your library · Movies", movies, lateNight) { opened(); actions.play(it.ratingKey, it.title) } }
            if (shows.isNotEmpty()) item { ItemRow("In your library · Shows", shows, lateNight) { opened(); actions.play(it.ratingKey, it.title) } }
            if (found != null && items.isEmpty()) item { Note("Nothing in your library matches “${words.text}”.") }
            if (live.isNotEmpty()) {
                item {
                    OnNowRow(live, lateNight) { item ->
                        opened()
                        item.channelId?.let(actions::tuneIn) ?: item.intentUri?.let { actions.openIntentUri(it, item.name) }
                    }
                }
            }
            if (onApps.isNotEmpty()) {
                item {
                    // The catalog's art is web addresses, resized by Plex's image service: never your server, never a token.
                    CompositionLocalProvider(LocalArtUrls provides CatalogArtUrls) {
                        StreamingRow(onApps, lateNight) { found -> opened(); actions.watchOn(found.apps.first(), found.title) }
                    }
                }
            }
            // Q4: grown-ups only; Google knows nothing of a kid's limits.
            if (!profile.kid) {
                item {
                    PillButton(
                        text = "Search everywhere with Google: “${words.text}”",
                        icon = AppIcons.Search,
                        onClick = { opened(); actions.searchWithGoogle(words.text) },
                        colors = pillButtonColors(container = Palette.Surface),
                    )
                }
            }
        }
    }
}

/** [text] without [chip]'s words, so the chip comes off. */
internal fun without(text: String, chip: SearchChip): String =
    text.split(' ').filterNot { word -> SearchWords.read(word).chips.singleOrNull() == chip }.joinToString(" ").trim()

/** The field: the TV keyboard opens with the screen; its Search key closes it, and [onSearch] goes to the results. */
@Composable
private fun SearchField(text: String, onChange: (String) -> Unit, focus: FocusRequester, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(22.dp)
    BasicTextField(
        value = text,
        onValueChange = { onChange(it.take(MAX_TEXT)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Palette.Text),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onSearch = {
            keyboard?.hide()
            onSearch()
        }),
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .background(Palette.Surface2, shape)
            .border(2.5.dp, if (focused) accent else Color.Transparent, shape)
            .okOpensKeyboard { keyboard?.show() },
        decorationBox = { inner ->
            Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp), tint = Palette.TextDim)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) Text("Titles, people, genres", style = MaterialTheme.typography.titleMedium, color = Palette.TextFaint)
                    inner()
                }
            }
        },
    )
}

/** The words read as chips (Q2): OK on one takes its word out. */
@Composable
private fun Chips(chips: List<SearchChip>, onRemove: (SearchChip) -> Unit) {
    Row(Modifier.padding(top = 10.dp).focusRestorer(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { chip ->
            PillButton(text = "${chip.label}  ×", small = true, onClick = { onRemove(chip) }, colors = pillButtonColors(container = Palette.Surface3))
        }
    }
}

/** The empty field: the last searches, and what Search reads. */
@Composable
private fun Start(recent: List<String>, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recent.isNotEmpty()) {
            Eyebrow("Recent searches")
            Row(Modifier.focusRestorer(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { words -> PillButton(text = words, small = true, onClick = { onPick(words) }, colors = pillButtonColors(container = Palette.Surface2)) }
            }
        }
        Note("Search your library by title, actor, director or genre. “90s”, “kids”, “movies”, “shows” and “new” narrow it.")
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = Palette.TextDim)
}

/** The best match, large: Plex's first. */
@Composable
private fun TopMatch(item: SearchItem, lateNight: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("Top match")
        ResultCard(item, lateNight, width = TOP_WIDTH, onClick = onClick, large = true)
    }
}

@Composable
private fun ItemRow(label: String, items: List<SearchItem>, lateNight: Boolean, onClick: (SearchItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(label)
        // A LazyRow is a focus group already: focusRestorer() alone (AGENTS.md).
        LazyRow(Modifier.fillMaxWidth().focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.ratingKey }) { item -> ResultCard(item, lateNight, width = CARD_WIDTH) { onClick(item) } }
        }
    }
}

/**
 * On your apps (Q3): titles from Plex's catalog, each badged with the apps on this TV that
 * stream it, best first. OK opens it in the first.
 */
@Composable
private fun StreamingRow(items: List<StreamingItem>, lateNight: Boolean, onClick: (StreamingItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("On your apps")
        LazyRow(Modifier.fillMaxWidth().focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.ratingKey }) { found ->
                ResultCard(
                    SearchItem(ratingKey = found.ratingKey, type = found.type, title = found.title, meta = found.meta, image = found.image),
                    lateNight,
                    width = CARD_WIDTH,
                    badge = found.apps.joinToString(" · ") { it.label },
                ) { onClick(found) }
            }
        }
    }
}

/** A title: its art, with the title, what it is, and why Plex found it, over a scrim; [badge] names where it streams. */
@Composable
private fun ResultCard(item: SearchItem, lateNight: Boolean, width: Dp, large: Boolean = false, badge: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        modifier = Modifier.width(width).height(width * 9 / 16),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Surface2, focusedContainerColor = Palette.Surface2),
        scale = cardLift,
        border = focusRing(shape),
    ) {
        Box(Modifier.fillMaxSize()) {
            TitleArt(item.ratingKey, item.image, lateNight, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Palette.Scrim.copy(alpha = 0.9f))))
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Palette.Scrim.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    item.title,
                    style = if (large) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val line = listOfNotNull(item.meta.takeIf { it.isNotBlank() }, item.why).joinToString(" · ")
                if (line.isNotEmpty()) {
                    Text(line, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun OnNowRow(items: List<OnNowItem>, lateNight: Boolean, onClick: (OnNowItem) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow("On now")
        LazyRow(Modifier.fillMaxWidth().focusRestorer(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.channelId ?: it.intentUri ?: it.name }) { item ->
                ResultCard(
                    SearchItem(ratingKey = item.channelId ?: item.name, type = "channel", title = item.name, meta = item.now.orEmpty(), image = item.image),
                    lateNight,
                    width = CARD_WIDTH,
                ) { onClick(item) }
            }
        }
    }
}

private val TOP_WIDTH = 320.dp
private val CARD_WIDTH = 176.dp

/** The longest search the field takes. */
private const val MAX_TEXT = 60
