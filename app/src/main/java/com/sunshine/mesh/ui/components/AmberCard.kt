package com.sunshine.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberCard
import com.sunshine.mesh.ui.theme.AmberShape

/**
 * Custom card built from Box. No Material Card: the shadow, border, radius and
 * background are all explicit Amber Glow tokens.
 */
@Composable
fun AmberCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .shadow(elevation = 6.dp, shape = AmberShape)
            .clip(AmberShape)
            .background(AmberCard)
            .border(width = 1.dp, color = AmberBorder, shape = AmberShape)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onClick)
                },
            ),
    ) {
        content()
    }
}
