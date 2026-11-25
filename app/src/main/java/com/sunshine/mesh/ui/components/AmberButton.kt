package com.sunshine.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberCard
import com.sunshine.mesh.ui.theme.AmberDestructive
import com.sunshine.mesh.ui.theme.AmberDestructiveForeground
import com.sunshine.mesh.ui.theme.AmberMuted
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary
import com.sunshine.mesh.ui.theme.AmberPrimaryForeground
import com.sunshine.mesh.ui.theme.AmberShape

enum class AmberButtonStyle {
    Primary,
    Ghost,
    Destructive,
}

/** Custom button: Box + explicit background, border and 14 dp radius. */
@Composable
fun AmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: AmberButtonStyle = AmberButtonStyle.Primary,
) {
    val background = when {
        !enabled -> AmberMuted
        style == AmberButtonStyle.Primary -> AmberPrimary
        style == AmberButtonStyle.Destructive -> AmberDestructive
        else -> AmberCard
    }
    val contentColor = when {
        !enabled -> AmberMutedForeground
        style == AmberButtonStyle.Primary -> AmberPrimaryForeground
        style == AmberButtonStyle.Destructive -> AmberDestructiveForeground
        else -> AmberPrimary
    }
    val borderColor = if (style == AmberButtonStyle.Ghost) AmberBorder else background

    Box(
        modifier = modifier
            .clip(AmberShape)
            .background(background)
            .border(width = 1.dp, color = borderColor, shape = AmberShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        AmberText(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
            maxLines = 1,
        )
    }
}

/** Borderless text action used in custom top bars. */
@Composable
fun AmberTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(AmberShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        AmberText(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) AmberPrimary else AmberMutedForeground,
            maxLines = 1,
        )
    }
}
