package com.phonoodlesramen.actioncenter.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.apps.LaunchableApp
import com.phonoodlesramen.actioncenter.apps.key
import com.phonoodlesramen.actioncenter.home.HomeUiState
import com.phonoodlesramen.actioncenter.plex.PlexHomeUser
import com.phonoodlesramen.actioncenter.profiles.AgeBracket
import com.phonoodlesramen.actioncenter.profiles.AgeBrackets
import com.phonoodlesramen.actioncenter.profiles.KidEntity
import com.phonoodlesramen.actioncenter.profiles.PlexLinkResult
import com.phonoodlesramen.actioncenter.profiles.ProfilesController
import com.phonoodlesramen.actioncenter.profiles.RoomProfileStore
import com.phonoodlesramen.actioncenter.ui.home.Eyebrow
import com.phonoodlesramen.actioncenter.ui.home.PillButton
import com.phonoodlesramen.actioncenter.ui.home.cardLift
import com.phonoodlesramen.actioncenter.ui.home.focusRing
import com.phonoodlesramen.actioncenter.ui.home.pillButtonColors
import com.phonoodlesramen.actioncenter.ui.profiles.AvatarDisc
import com.phonoodlesramen.actioncenter.ui.profiles.KidColorNames
import com.phonoodlesramen.actioncenter.ui.profiles.KidColors
import com.phonoodlesramen.actioncenter.ui.profiles.argb
import com.phonoodlesramen.actioncenter.ui.profiles.initialOf
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.LocalAccent
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Settings › Profiles (K10): you, then each kid with their age, ceiling and Plex user, then
 * Add a kid and the age-bracket table (K3). Everything works with the D-pad, and Back
 * steps out of a kid's page or the table to the list. Nothing here leaves the Shield
 * except the Plex Home calls that link a kid (K2), and names and birthdays are never logged.
 *
 * - **You:** your name from Settings, unchanged here, with no limits.
 * - **A kid's page:** name (the TV keyboard), colour, birthday (−/+ for the day, month and
 *   year), their Plex Home user, their ceiling (their age's, or their own as rating
 *   toggles), My apps (K9) and Remove, with a confirm. Every change is saved as it's made,
 *   once the kid has a name; a new kid who's never named is never saved.
 * - **The age-bracket table:** each bracket's ages and its ratings as toggles. A kid moves
 *   up a bracket on their birthday; titles with no rating never show for kids (K4).
 */
@Composable
fun ColumnScope.ProfilesSettings(state: HomeUiState, controller: ProfilesController) {
    val kids by remember(controller) { controller.kids }.collectAsState(initial = null)
    val brackets by remember(controller) { controller.brackets }.collectAsState(initial = AgeBrackets.DEFAULT)
    var page by remember { mutableStateOf<Page>(Page.List) }
    // Back on the list: focus returns to what opened the page.
    var returnTo by remember { mutableStateOf<ReturnTo?>(null) }
    val today = state.now.toLocalDate()

    fun close(to: ReturnTo) {
        returnTo = to
        page = Page.List
    }

    when (val current = page) {
        Page.List -> ProfileList(
            state = state,
            kids = kids,
            brackets = brackets,
            today = today,
            returnTo = returnTo,
            onReturned = { returnTo = null },
            onOpen = { page = Page.Kid(it, new = false) },
            onAdd = { page = Page.Kid(newKid(kids.orEmpty(), state.allApps, today), new = true) },
            onBrackets = { page = Page.Brackets },
        )
        is Page.Kid -> key(current.kid.id) {
            KidPage(
                initial = current.kid,
                new = current.new,
                state = state,
                brackets = brackets,
                today = today,
                controller = controller,
                onClose = { removed -> close(if (removed) ReturnTo.Add else ReturnTo.Kid(current.kid.id)) },
            )
        }
        Page.Brackets -> BracketTable(brackets, controller, onClose = { close(ReturnTo.Brackets) })
    }
}

private sealed interface Page {
    data object List : Page
    data class Kid(val kid: KidEntity, val new: Boolean) : Page
    data object Brackets : Page
}

private sealed interface ReturnTo {
    data class Kid(val id: String) : ReturnTo
    data object Add : ReturnTo
    data object Brackets : ReturnTo
}

/** The kid apps Action Center knows (K9): a new kid starts with whichever are installed. */
internal val KID_APPS = setOf(
    "com.edde746.plezy", // Plezy
    "com.google.android.youtube.tvkids", // YouTube Kids
    "com.disney.disneyplus", // Disney+
    "org.pbskids.video", // PBS KIDS
)

/** The ratings a ceiling is made of, films then TV, each from the youngest (K3). */
internal val FILM_RATINGS = listOf("G", "PG", "PG-13")
internal val TV_RATINGS = listOf("TV-Y", "TV-Y7", "TV-G", "TV-PG", "TV-14")

private const val MAX_NAME_LENGTH = 24

/** A kid's birthday can't be further back than this, nor after today. */
private const val OLDEST_YEARS = 18L

/**
 * A new kid (K9, K10): a random id; the first colour no other kid has; 8 years old today, to
 * be set; the kid apps that are installed; and last in the order. Their name is the first
 * thing to fill in.
 */
internal fun newKid(existing: List<KidEntity>, installed: List<LaunchableApp>, today: LocalDate, id: String = UUID.randomUUID().toString()) =
    KidEntity(
        id = id,
        name = "",
        color = KidColors.firstOrNull { colour -> existing.none { it.color == colour } } ?: KidColors[existing.size % KidColors.size],
        birthday = today.minusYears(8).toEpochDay(),
        apps = joined(installed.filter { it.packageName in KID_APPS }.map { it.key }),
        sort = (existing.maxOfOrNull { it.sort } ?: -1) + 1,
    )

/** "PG and TV-PG": the oldest film and TV ratings a ceiling allows. */
internal fun ceilingSummary(ratings: Set<String>): String =
    listOfNotNull(FILM_RATINGS.lastOrNull { it in ratings }, TV_RATINGS.lastOrNull { it in ratings })
        .joinToString(" and ")
        .ifEmpty { "nothing rated" }

/** A kid's row on the list: "Kid · 9 · Their age's: PG and TV-PG · Plex: Maya · 4 apps". */
internal fun kidSummary(kid: KidEntity, brackets: List<AgeBracket>, today: LocalDate): String {
    val born = LocalDate.ofEpochDay(kid.birthday)
    val own = kid.ceilingOverride?.let(RoomProfileStore::split)
    val apps = RoomProfileStore.split(kid.apps).size
    return listOf(
        "Kid · ${AgeBrackets.age(born, today)}",
        (if (own != null) "Their own: " else "Their age's: ") + ceilingSummary(own ?: AgeBrackets.ceiling(born, null, brackets, today)),
        kid.plexUserTitle?.let { "Plex: $it" } ?: "Plex not linked",
        "$apps ${if (apps == 1) "app" else "apps"}",
    ).joinToString(" · ")
}

/** "|"-joined and sorted, as `profiles.db` keeps a set. */
private fun joined(values: Collection<String>) = values.sorted().joinToString("|")

private fun Set<String>.toggled(value: String) = if (value in this) this - value else this + value

@Composable
private fun ColumnScope.ProfileList(
    state: HomeUiState,
    kids: List<KidEntity>?,
    brackets: List<AgeBracket>,
    today: LocalDate,
    returnTo: ReturnTo?,
    onReturned: () -> Unit,
    onOpen: (KidEntity) -> Unit,
    onAdd: () -> Unit,
    onBrackets: () -> Unit,
) {
    val rows = remember(kids?.map { it.id }) { kids.orEmpty().associate { it.id to FocusRequester() } }
    val add = remember { FocusRequester() }
    val table = remember { FocusRequester() }
    LaunchedEffect(returnTo, kids) {
        val to = returnTo ?: return@LaunchedEffect
        if (kids == null) return@LaunchedEffect
        // A kid saved just now may not be on the list yet: Add a kid is beside where they'll show.
        val target = when (to) {
            is ReturnTo.Kid -> rows[to.id] ?: add
            ReturnTo.Add -> add
            ReturnTo.Brackets -> table
        }
        runCatching { target.requestFocus() }
        onReturned()
    }

    Note("You, then a profile for each kid. A kid's home shows only what their age allows, and their own apps.")
    // You: not editable here. Your name comes from Settings, and nothing is limited.
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        AvatarDisc(initialOf(state.name), Palette.Accents.getOrElse(state.accentIndex) { Palette.DefaultAccent }, 36.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(state.name.ifBlank { "You" }, style = MaterialTheme.typography.titleSmall)
            Text("You · no limits", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
    }
    if (kids != null) {
        Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            kids.forEach { kid ->
                ProfileRow(
                    avatar = { AvatarDisc(initialOf(kid.name), argb(kid.color), 36.dp) },
                    title = kid.name,
                    detail = kidSummary(kid, brackets, today),
                    onClick = { onOpen(kid) },
                    modifier = Modifier.focusRequester(rows.getValue(kid.id)),
                )
            }
        }
    }
    PillButton(
        text = "Add a kid",
        icon = AppIcons.AddPerson,
        onClick = onAdd,
        colors = pillButtonColors(container = Palette.Surface2),
        modifier = Modifier.focusRequester(add),
    )
    Eyebrow("Age brackets", Modifier.padding(top = 8.dp))
    ProfileRow(
        avatar = null,
        title = "Ratings by age",
        detail = brackets.joinToString(" · ") { "${it.fromAge} to ${it.toAge}: ${ceilingSummary(it.ratings)}" },
        onClick = onBrackets,
        modifier = Modifier.focusRequester(table),
    )
    Note("Switch from the avatar in the top bar, or on Who's watching? when the TV wakes. Names and birthdays stay on this Shield.")
}

/** A row that opens a page: an optional avatar, a title and a line under it. */
@Composable
private fun ProfileRow(
    avatar: (@Composable () -> Unit)?,
    title: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (avatar != null) {
                avatar()
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Where linking a Plex Home user is up to, on a kid's page (K2). */
private sealed interface Linking {
    data object Idle : Linking
    data object Reading : Linking
    data class Choosing(val users: List<PlexHomeUser>) : Linking
    data class AskingPin(val user: PlexHomeUser) : Linking
    data class Working(val user: PlexHomeUser) : Linking
}

/**
 * A kid's page. [initial] is the kid as stored (or [new]), and the page keeps its own copy
 * while it's open: each change is saved whole, in order, once there's a name. [onClose]
 * hears whether the kid was removed.
 */
@Composable
private fun ColumnScope.KidPage(
    initial: KidEntity,
    new: Boolean,
    state: HomeUiState,
    brackets: List<AgeBracket>,
    today: LocalDate,
    controller: ProfilesController,
    onClose: (removed: Boolean) -> Unit,
) {
    var kid by remember { mutableStateOf(initial) }
    var name by remember { mutableStateOf(initial.name) }
    var confirming by remember { mutableStateOf(false) }
    val nameField = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler { onClose(false) }
    LaunchedEffect(Unit) {
        runCatching { nameField.requestFocus() }
        // A new kid: their name first, with the TV keyboard up.
        if (new) keyboard?.show()
    }

    fun update(change: (KidEntity) -> KidEntity) {
        kid = change(kid)
        if (kid.name.isNotBlank()) controller.saveKid(kid)
    }

    val born = LocalDate.ofEpochDay(kid.birthday)
    val age = AgeBrackets.age(born, today)
    val colour = argb(kid.color)
    val shownName = kid.name.ifBlank { "New kid" }

    Row(verticalAlignment = Alignment.CenterVertically) {
        AvatarDisc(initialOf(kid.name), colour, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(shownName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Kid · $age · Back returns to Profiles", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        }
    }

    Eyebrow("Name", Modifier.padding(top = 4.dp))
    NameField(
        name = name,
        onChange = { typed ->
            name = typed
            update { it.copy(name = typed.trim()) }
        },
        modifier = Modifier.focusRequester(nameField),
    )

    Eyebrow("Colour", Modifier.padding(top = 8.dp))
    Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KidColors.forEachIndexed { index, swatch ->
            Swatch(colour = argb(swatch), selected = swatch == kid.color, description = KidColorNames[index]) {
                update { it.copy(color = swatch) }
            }
        }
    }

    Eyebrow("Birthday", Modifier.padding(top = 8.dp))
    BirthdayRow(born) { date -> update { it.copy(birthday = date.coerceIn(today.minusYears(OLDEST_YEARS), today).toEpochDay()) } }
    val bracket = brackets.ifEmpty { AgeBrackets.DEFAULT }.sortedBy { it.fromAge }.let { table ->
        table.firstOrNull { age in it.fromAge..it.toAge }
    }
    val ageCeiling = AgeBrackets.ceiling(born, null, brackets, today)
    Note(
        "$shownName is $age" + (bracket?.let { ": ages ${it.fromAge} to ${it.toAge}" } ?: "") +
            ", up to ${ceilingSummary(ageCeiling)}. They move up on their birthday.",
    )

    Eyebrow("Plex Home user", Modifier.padding(top = 8.dp))
    PlexUser(
        kid = kid,
        controller = controller,
        onLinked = { linked -> kid = kid.copy(plexUserId = linked.plexUserId, plexUserTitle = linked.plexUserTitle) },
        onUnlink = {
            controller.unlink(kid)
            kid = kid.copy(plexUserId = null, plexUserTitle = null)
        },
    )

    Eyebrow("Ceiling", Modifier.padding(top = 8.dp))
    val own = kid.ceilingOverride?.let(RoomProfileStore::split)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PillButton(
            text = "Their age's: ${ceilingSummary(ageCeiling)}",
            icon = if (own == null) AppIcons.Check else null,
            onClick = { update { it.copy(ceilingOverride = null) } },
        )
        PillButton(
            text = "Their own",
            icon = if (own != null) AppIcons.Check else null,
            onClick = { if (own == null) update { it.copy(ceilingOverride = joined(ageCeiling)) } },
        )
    }
    if (own != null) {
        RatingToggles(selected = own, what = "$shownName's ceiling") { rating ->
            update { it.copy(ceilingOverride = joined(own.toggled(rating))) }
        }
        Note(if (own.isEmpty()) "Nothing rated: their home shows only their apps." else "Their own ceiling stays as it is on their birthday.")
    } else {
        Note("Titles with no rating never show for kids.")
    }

    Eyebrow("My apps", Modifier.padding(top = 8.dp))
    Note("Their home and All apps show only these.")
    val apps = RoomProfileStore.split(kid.apps)
    Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        state.allApps.forEach { app ->
            AppRow(label = app.label, on = app.key in apps) { update { it.copy(apps = joined(apps.toggled(app.key))) } }
        }
    }

    if (confirming) {
        val cancel = remember { FocusRequester() }
        BackHandler { confirming = false }
        LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
        Text("Remove $shownName?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Note("Their profile, apps and Plex link go from this Shield. Nothing changes in Plex.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(
                text = "Remove",
                onClick = {
                    // A new kid who was never named was never saved.
                    if (!new || kid.name.isNotBlank()) controller.removeKid(kid)
                    onClose(true)
                },
            )
            PillButton(
                text = "Cancel",
                onClick = { confirming = false },
                colors = pillButtonColors(container = Palette.Surface2),
                modifier = Modifier.focusRequester(cancel),
            )
        }
    } else {
        PillButton(
            text = "Remove $shownName",
            onClick = { confirming = true },
            colors = pillButtonColors(container = Palette.Surface2),
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/**
 * The name, typed with the TV keyboard. It doesn't open just because focus passes through:
 * OK on the field opens it.
 */
@Composable
private fun NameField(name: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val accent = LocalAccent.current
    BasicTextField(
        value = name,
        onValueChange = { onChange(it.take(MAX_NAME_LENGTH)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Palette.Text),
        cursorBrush = SolidColor(accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Words,
            imeAction = ImeAction.Done,
            showKeyboardOnFocus = false,
        ),
        keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
        modifier = modifier
            .fillMaxWidth()
            .fieldLook(if (focused) accent else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
            .okOpensKeyboard { keyboard?.show() },
        decorationBox = { inner ->
            Box {
                if (name.isEmpty()) Text("Their name", style = MaterialTheme.typography.titleMedium, color = Palette.TextFaint)
                inner()
            }
        },
    )
}

/** A text field's box, as Weather's search draws it: [ring] is the accent while it's focused. */
internal fun Modifier.fieldLook(ring: Color): Modifier = this
    .background(Palette.Surface2, RoundedCornerShape(10.dp))
    .border(2.dp, ring, RoundedCornerShape(10.dp))
    .padding(horizontal = 14.dp, vertical = 10.dp)

/** OK on a text field brings the TV keyboard back after it was dismissed. */
internal fun Modifier.okOpensKeyboard(show: () -> Unit) = onPreviewKeyEvent {
    if (it.type == KeyEventType.KeyUp && it.key == Key.DirectionCenter) show()
    false
}

@Composable
private fun Swatch(colour: Color, selected: Boolean, description: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(40.dp).semantics {
            contentDescription = description
            stateDescription = if (selected) "Selected" else ""
        },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colour,
            focusedContainerColor = colour,
            contentColor = Palette.AccentInk,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = cardLift,
        border = focusRing(CircleShape),
    ) {
        if (selected) Icon(AppIcons.Check, contentDescription = null, modifier = Modifier.size(20.dp).align(Alignment.Center))
    }
}

/** Day, month and year, each with − and + either side. A month or year that lacks the day keeps its last. */
@Composable
private fun BirthdayRow(born: LocalDate, onChange: (LocalDate) -> Unit) {
    Row(Modifier.focusRestorer().focusGroup(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Stepper("Day", born.dayOfMonth.toString(), DAY_WIDTH) { step ->
            onChange(born.withDayOfMonth((born.dayOfMonth - 1 + step).mod(born.lengthOfMonth()) + 1))
        }
        Stepper("Month", born.month.getDisplayName(TextStyle.FULL, LocalLocale.current.platformLocale), MONTH_WIDTH) { step ->
            onChange(born.withMonth((born.monthValue - 1 + step).mod(12) + 1))
        }
        Stepper("Year", born.year.toString(), YEAR_WIDTH) { step -> onChange(born.plusYears(step.toLong())) }
    }
}

/** A value with − and + either side, and what it is above. [what] names the buttons: "Day down", "Day up". */
@Composable
private fun Stepper(what: String, value: String, width: Dp, onStep: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(what, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            StepButton("−", "$what down") { onStep(-1) }
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.width(width),
            )
            StepButton("+", "$what up") { onStep(1) }
        }
    }
}

@Composable
private fun StepButton(text: String, description: String, onClick: () -> Unit) {
    PillButton(text = text, onClick = onClick, small = true, modifier = Modifier.width(STEP_WIDTH).semantics { contentDescription = description })
}

private val STEP_WIDTH = 38.dp
private val DAY_WIDTH = 32.dp
private val MONTH_WIDTH = 92.dp
private val YEAR_WIDTH = 52.dp

/**
 * The kid's Plex Home user (K2): who they're linked to, and choosing one from your Home's
 * managed users. A user with a PIN asks for it here, once: it goes to plex.tv with this one
 * call and is gone when the step closes (it's never saved, even across a restart). A link
 * that fails says why, and the kid stays as they were.
 */
@Composable
private fun PlexUser(kid: KidEntity, controller: ProfilesController, onLinked: (KidEntity) -> Unit, onUnlink: () -> Unit) {
    var step by remember { mutableStateOf<Linking>(Linking.Idle) }
    var problem by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val choose = remember { FocusRequester() }
    val firstUser = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(false) }
    val name = kid.name.ifBlank { "this kid" }

    fun back() {
        step = Linking.Idle
        refocus = true
    }

    fun read() {
        problem = null
        step = Linking.Reading
        scope.launch {
            val found = controller.linkableUsers()
            problem = found.problem
            step = if (found.problem == null) Linking.Choosing(found.users) else Linking.Idle
            if (found.problem != null) refocus = true
        }
    }

    fun link(user: PlexHomeUser, pin: String?) {
        problem = null
        step = Linking.Working(user)
        scope.launch {
            when (val result = controller.link(kid, user, pin)) {
                is PlexLinkResult.Linked -> onLinked(result.kid)
                is PlexLinkResult.Failed -> problem = result.why
            }
            back()
        }
    }

    when (val current = step) {
        Linking.Idle -> {
            LaunchedEffect(Unit) {
                if (refocus) runCatching { choose.requestFocus() }
                refocus = false
            }
            val linked = kid.plexUserTitle
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = linked ?: "Not linked",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (linked == null) Palette.TextDim else Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                )
                if (kid.name.isNotBlank()) {
                    PillButton(
                        text = if (linked == null) "Choose a Plex user" else "Change",
                        onClick = ::read,
                        colors = pillButtonColors(container = Palette.Surface2),
                        modifier = Modifier.focusRequester(choose),
                    )
                    if (linked != null) PillButton(text = "Unlink", onClick = onUnlink, colors = pillButtonColors(container = Palette.Surface2))
                }
            }
            Note(
                if (kid.name.isBlank()) {
                    "Name them first."
                } else {
                    "Their Up Next and history come from their own Plex user, and Plex's restrictions for them apply too."
                },
            )
        }
        Linking.Reading -> Note("Reading your Plex Home…")
        is Linking.Choosing -> {
            BackHandler(onBack = ::back)
            LaunchedEffect(Unit) { runCatching { firstUser.requestFocus() } }
            Note(if (current.users.isEmpty()) "Your Plex Home has no managed users yet. Add one for $name at plex.tv, then come back." else "Which Plex Home user is $name?")
            Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                current.users.forEachIndexed { index, user ->
                    UserRow(
                        title = user.title,
                        detail = listOfNotNull(restriction(user.restrictionProfile), "PIN".takeIf { user.protected }).joinToString(" · "),
                        onClick = { if (user.protected) step = Linking.AskingPin(user) else link(user, pin = null) },
                        modifier = if (index == 0) Modifier.focusRequester(firstUser) else Modifier,
                    )
                }
                UserRow(
                    title = "Not linked",
                    detail = "Their home filters by rating, without their own Up Next",
                    onClick = {
                        if (kid.plexUserId != null) onUnlink()
                        back()
                    },
                    modifier = if (current.users.isEmpty()) Modifier.focusRequester(firstUser) else Modifier,
                )
            }
        }
        is Linking.AskingPin -> PinStep(current.user, onLink = { pin -> link(current.user, pin) }, onCancel = ::back)
        is Linking.Working -> Note("Linking ${current.user.title}…")
    }
    problem?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.Caution) }
}

/** Plex's restriction profile, as Plex names it (research §4). */
private fun restriction(profile: String?): String = when (profile) {
    "little_kid" -> "Younger kid"
    "older_kid" -> "Older kid"
    "teen" -> "Teen"
    null -> "Not restricted"
    else -> profile
}

/**
 * [user]'s PIN, for this one link (K2). The digits live only in this step: they go to
 * [onLink] and are dropped when it closes. Never saved, never logged.
 */
@Composable
private fun PinStep(user: PlexHomeUser, onLink: (String) -> Unit, onCancel: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) {
        runCatching { field.requestFocus() }
        keyboard?.show()
    }
    fun submit() {
        if (pin.length != PIN_LENGTH) return
        keyboard?.hide()
        onLink(pin)
    }
    Note("${user.title} has a PIN in Plex. Enter it to link them: it's used once, now, and not kept.")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BasicTextField(
            value = pin,
            onValueChange = { typed -> pin = typed.filter(Char::isDigit).take(PIN_LENGTH) },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = Palette.Text),
            cursorBrush = SolidColor(LocalAccent.current),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier
                .width(PIN_WIDTH)
                .focusRequester(field)
                .semantics { contentDescription = "${user.title}'s PIN" }
                .fieldLook(if (focused) LocalAccent.current else Color.Transparent)
                .onFocusChanged { focused = it.isFocused }
                .okOpensKeyboard { keyboard?.show() },
            decorationBox = { inner ->
                Box {
                    if (pin.isEmpty()) Text("4 digits", style = MaterialTheme.typography.titleMedium, color = Palette.TextFaint)
                    inner()
                }
            },
        )
        PillButton(text = "Link", onClick = ::submit)
        PillButton(text = "Cancel", onClick = onCancel, colors = pillButtonColors(container = Palette.Surface2))
    }
}

private const val PIN_LENGTH = 4
private val PIN_WIDTH = 140.dp

/** A user to link to: the focused one fills with the accent, like Settings' other short lists. */
@Composable
private fun UserRow(title: String, detail: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = LocalAccent.current,
            focusedContentColor = Palette.AccentInk,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** An app in My apps (K9): the whole row is its switch. */
@Composable
private fun AppRow(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(40.dp).semantics { stateDescription = if (on) "On" else "Off" },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface,
            contentColor = Palette.Text,
            focusedContainerColor = Palette.Surface3,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            OnOff(on)
        }
    }
}

/** The row's switch, as the other Settings sections draw it. */
@Composable
private fun OnOff(on: Boolean) {
    val accent = LocalAccent.current
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 24.dp)
            // Off stays visible on the focused row's Surface3 too.
            .background(if (on) accent else Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(18.dp)
                .background(if (on) Palette.AccentInk else Palette.TextDim, CircleShape),
        )
    }
}

/**
 * Ratings as toggles, films then TV (K3): a check and a brighter pill for each that's
 * allowed. [what] names them for TalkBack and tests: "Ages 7 to 9: PG-13".
 */
@Composable
private fun RatingToggles(selected: Set<String>, what: String, onToggle: (String) -> Unit) {
    Column(Modifier.focusRestorer().focusGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Films" to FILM_RATINGS, "TV" to TV_RATINGS).forEach { (label, ratings) ->
            Text(label, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ratings.forEach { rating ->
                    val on = rating in selected
                    PillButton(
                        text = rating,
                        icon = if (on) AppIcons.Check else null,
                        small = true,
                        onClick = { onToggle(rating) },
                        colors = if (on) {
                            pillButtonColors(container = LocalAccent.current.copy(alpha = 0.22f))
                        } else {
                            pillButtonColors(container = Palette.Surface2, content = Palette.TextDim)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "$what: $rating"
                            stateDescription = if (on) "On" else "Off"
                        },
                    )
                }
            }
        }
    }
}

/**
 * The age-bracket table (K3): each bracket's ages and its ratings as toggles, saved as
 * they change. The page keeps its own copy while it's open, so quick toggles never undo
 * each other.
 */
@Composable
private fun ColumnScope.BracketTable(brackets: List<AgeBracket>, controller: ProfilesController, onClose: () -> Unit) {
    var table by remember { mutableStateOf(brackets.ifEmpty { AgeBrackets.DEFAULT }.sortedBy { it.fromAge }) }
    val first = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Note(
        "A kid's ceiling is their age's bracket, and moves up on their birthday. A kid can have their own " +
            "instead, on their page. Titles with no rating never show for kids. Back returns to Profiles.",
    )
    table.forEachIndexed { index, bracket ->
        Eyebrow("Ages ${bracket.fromAge} to ${bracket.toAge}", Modifier.padding(top = 8.dp))
        Box(if (index == 0) Modifier.focusRequester(first) else Modifier) {
            RatingToggles(selected = bracket.ratings, what = "Ages ${bracket.fromAge} to ${bracket.toAge}") { rating ->
                table = table.mapIndexed { i, row -> if (i == index) row.copy(ratings = row.ratings.toggled(rating)) else row }
                controller.saveBrackets(table)
            }
        }
    }
}
