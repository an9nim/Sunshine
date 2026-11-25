package com.sunshine.mesh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary

/** Custom top bar built from Row/Column, not Material TopAppBar. */
@Composable
fun AmberTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    titleFontFamily: FontFamily? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AmberText(
                text = title,
                style = if (titleFontFamily == null) {
                    MaterialTheme.typography.headlineMedium
                } else {
                    MaterialTheme.typography.headlineMedium.copy(fontFamily = titleFontFamily)
                },
                color = AmberPrimary,
                maxLines = 1,
            )
            if (subtitle != null) {
                val subtitleStyle = MaterialTheme.typography.labelMedium
                AmberText(
                    text = subtitle,
                    style = if (subtitleMono) {
                        subtitleStyle.copy(fontFamily = AmberMono)
                    } else {
                        subtitleStyle
                    },
                    color = AmberMutedForeground,
                    maxLines = 1,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}
