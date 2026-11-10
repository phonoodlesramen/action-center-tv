package com.phonoodlesramen.actioncenter.ui.profiles

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea

/**
 * "Who's watching?" (K1, spec §4): everyone in a row, each a 96 dp avatar with their name
 * and "Kid · 9" or "You". Focus starts on the profile that's on now ([currentId]) and lifts
 * a person 1.08, with a double ring round their avatar. OK picks them ([onPick]); Back
 * closes it ([onBack]), leaving whoever was on. With the parent PIN shelved (K5), anyone
 * can pick anyone.
 *
 * It opens from the avatar in the top bar, and when the home comes back after the screen
 * was off, if there's a kid ([opensAfterScreenOff]).
 *
 * @param ownerColor your avatar's colour: Settings › Look's accent, whoever is on now.
 */
@Composable
fun WhosWatchingScreen(
    profiles: List<ActiveProfile>,
    currentId: String,
    ownerColor: Color,
    onPick: (ActiveProfile) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val people = remember(profiles.map { it.id }) { profiles.associate { it.id to FocusRequester() } }
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { runCatching { (people[currentId] ?: people.values.first()).requestFocus() } }

    Box(modifier.fillMaxSize().background(Palette.Background).safeArea(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Who's watching?", style = MaterialTheme.typography.headlineMedium)
            Row(
                modifier = Modifier.padding(top = 28.dp).focusRestorer().focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
            ) {
                profiles.forEach { profile ->
                    Person(
                        profile = profile,
                        color = profile.avatarColor(ownerColor),
                        now = profile.id == currentId,
                        onClick = { onPick(profile) },
                        modifier = Modifier.focusRequester(people.getValue(profile.id)),
                    )
                }
            }
        }
    }
}

/** K1: after the screen was off, "Who's watching?" opens only when there's a kid to pick. */
fun opensAfterScreenOff(profiles: List<ActiveProfile>): Boolean = profiles.any { it.kid }

/** One person: their avatar, name and "Kid · 9" or "You". The whole column is the button. */
@Composable
private fun Person(profile: ActiveProfile, color: Color, now: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Palette.Text,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Palette.Text,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) {
        Column(
            modifier = Modifier.padding(8.dp).widthIn(max = 168.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AvatarDisc(
                initial = initialOf(profile.name),
                color = color,
                size = WhoAvatarSize,
                // Focused: a ring of the background, then one of the text colour (the prototype's double ring).
                modifier = Modifier.drawBehind {
                    if (focused) {
                        val radius = size.minDimension / 2
                        drawCircle(Palette.Text, radius + 6.dp.toPx())
                        drawCircle(Palette.Background, radius + 3.dp.toPx())
                    }
                },
            )
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = profile.name.ifBlank { "You" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (now) Text("(now)", style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
            }
            // Without a name in Settings, yours already reads "You".
            val role = if (profile.kid) profile.age?.let { "Kid · $it" } ?: "Kid" else "You".takeIf { profile.name.isNotBlank() }
            if (role != null) Text(role, style = MaterialTheme.typography.bodySmall, color = Palette.TextDim, maxLines = 1)
        }
    }
}
