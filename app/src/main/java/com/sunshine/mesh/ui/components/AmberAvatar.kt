package com.sunshine.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberMuted
import com.sunshine.mesh.ui.theme.AmberPrimary
import com.sunshine.mesh.ui.theme.AmberShape

/** Initial avatar. Uses the same 14 dp radius as every other surface. */
@Composable
fun AmberAvatar(
    initial: String,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(AmberShape)
            .background(AmberMuted)
            .border(width = 1.dp, color = AmberBorder, shape = AmberShape),
        contentAlignment = Alignment.Center,
    ) {
        AmberText(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = AmberPrimary,
            maxLines = 1,
        )
    }
}
