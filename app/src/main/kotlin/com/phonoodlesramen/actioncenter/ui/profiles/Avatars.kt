package com.phonoodlesramen.actioncenter.ui.profiles

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.profiles.ActiveProfile
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette

/** A profile's colour (U13): a kid's own, else [ownerColor], your accent from Settings › Look. */
fun ActiveProfile.avatarColor(ownerColor: Color): Color = color?.let(::argb) ?: ownerColor

/** A stored ARGB colour, as a kid's profile keeps it. */
fun argb(color: Long): Color = Color(color.toInt())

/** The avatar's letter: [name]'s first, upper-case; null when it's blank (the avatar shows a person). */
fun initialOf(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return null
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, 1)).uppercase()
}

/** A round avatar that isn't focusable itself: [initial] on [color], or a person when there's no name. */
@Composable
fun AvatarDisc(initial: String?, color: Color, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).background(color, CircleShape), contentAlignment = Alignment.Center) {
        if (initial != null) {
            // About 0.38 of the disc, as the prototype draws it, and never under 12 sp (spec §2).
            Text(initial, style = TextStyle(fontSize = (size.value * 0.38f).coerceAtLeast(12f).sp, fontWeight = FontWeight.Bold), color = Palette.AccentInk)
        } else {
            Icon(AppIcons.Person, contentDescription = null, modifier = Modifier.size(size * 0.55f), tint = Palette.AccentInk)
        }
    }
}

/** The six colours a kid can have (Settings › Profiles): coral, green, purple, amber, pink and teal. */
val KidColors: List<Long> = listOf(0xFFFF8A65, 0xFFAED581, 0xFFB39DDB, 0xFFFFB74D, 0xFFF48FB1, 0xFF4DD0E1)

/** [KidColors]' names, for TalkBack. */
val KidColorNames: List<String> = listOf("Coral", "Green", "Purple", "Amber", "Pink", "Teal")

/** The size of each avatar on "Who's watching?" (spec §4). */
internal val WhoAvatarSize = 96.dp
