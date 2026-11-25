package com.sunshine.mesh.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.sunshine.mesh.domain.model.MessageStatus
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMutedForeground

/** Delivery glyph in the mono face. Received messages have no outgoing mark. */
@Composable
fun MessageStatusMark(
    status: MessageStatus,
    modifier: Modifier = Modifier,
    color: Color = AmberMutedForeground,
) {
    val symbol = when (status) {
        MessageStatus.PENDING -> "…"
        MessageStatus.SENT -> "✓"
        MessageStatus.DELIVERED -> "✓✓"
        MessageStatus.FAILED -> "!"
        MessageStatus.RECEIVED -> return
    }
    AmberText(
        text = symbol,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
        color = color,
        maxLines = 1,
    )
}
