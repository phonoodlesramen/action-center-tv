package com.phonoodlesramen.actioncenter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.phonoodlesramen.actioncenter.ui.theme.AppIcons
import com.phonoodlesramen.actioncenter.ui.theme.Palette
import com.phonoodlesramen.actioncenter.ui.theme.safeArea
import kotlinx.coroutines.delay

/**
 * A message for [Toast]. Each instance shows once, even with the same text. A [caution] one
 * is something refused (Phase 13: a favorite while the station can't be reached, N5).
 */
class ToastMessage(val text: String, val caution: Boolean = false)

/**
 * Spec §4: a light pill at the bottom centre for 2.6 s. Never focusable, so nothing
 * that needs an action goes in a toast [U12]. A caution one is a dark pill with the warning
 * sign and its sentence in the caution colour, as editing's refusals are.
 */
@Composable
fun Toast(message: ToastMessage?, onDone: () -> Unit, modifier: Modifier = Modifier) {
    if (message == null) return
    LaunchedEffect(message) {
        delay(TOAST_MILLIS)
        onDone()
    }
    Box(modifier.fillMaxSize().safeArea(), contentAlignment = Alignment.BottomCenter) {
        Row(
            modifier = Modifier
                .padding(bottom = 9.dp)
                .background(if (message.caution) Palette.Surface3 else Palette.Text, RoundedCornerShape(50))
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.caution) Icon(AppIcons.Warning, contentDescription = null, tint = Palette.Caution, modifier = Modifier.size(18.dp))
            Text(
                text = message.text,
                style = MaterialTheme.typography.labelLarge,
                color = if (message.caution) Palette.Caution else Color(0xFF0B0F13),
            )
        }
    }
}

private const val TOAST_MILLIS = 2_600L
