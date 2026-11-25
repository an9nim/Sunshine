package com.sunshine.mesh.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.sunshine.mesh.ui.theme.AmberForeground

/**
 * Thin wrapper over BasicText: no Material component, explicit color and
 * typography from the Amber Glow theme.
 */
@Composable
fun AmberText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = AmberForeground,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    textAlign: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign == null) {
            style.copy(color = color)
        } else {
            style.copy(color = color, textAlign = textAlign)
        },
        maxLines = maxLines,
        overflow = overflow,
    )
}
