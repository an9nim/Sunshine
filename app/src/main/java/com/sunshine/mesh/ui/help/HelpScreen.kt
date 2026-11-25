package com.sunshine.mesh.ui.help

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.R
import com.sunshine.mesh.ui.components.AmberCard
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.components.AmberTextButton
import com.sunshine.mesh.ui.components.AmberTopBar
import com.sunshine.mesh.ui.theme.AmberBackground
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary

@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmberBackground)
            .systemBarsPadding(),
    ) {
        AmberTopBar(
            title = stringResource(R.string.nav_help),
            subtitle = stringResource(R.string.help_subtitle),
        ) {
            AmberTextButton(
                text = stringResource(R.string.common_back),
                onClick = onBack,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AmberBorder),
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                HelpCard(
                    title = stringResource(R.string.help_intro_title),
                    lines = listOf(stringResource(R.string.help_intro_body)),
                )
            }
            item {
                HelpCard(
                    title = stringResource(R.string.help_ranges_title),
                    lines = listOf(
                        stringResource(R.string.help_range_ble),
                        stringResource(R.string.help_range_wifi),
                        stringResource(R.string.help_ranges_hint),
                    ),
                )
            }
            item {
                HelpCard(
                    title = stringResource(R.string.help_connect_title),
                    lines = listOf(
                        stringResource(R.string.help_connect_step_1),
                        stringResource(R.string.help_connect_step_2),
                        stringResource(R.string.help_connect_step_3),
                        stringResource(R.string.help_connect_step_4),
                    ),
                )
            }
            item {
                HelpCard(
                    title = stringResource(R.string.help_empty_title),
                    lines = listOf(
                        stringResource(R.string.help_empty_step_1),
                        stringResource(R.string.help_empty_step_2),
                        stringResource(R.string.help_empty_step_3),
                    ),
                )
            }
            item {
                HelpCard(
                    title = stringResource(R.string.help_limits_title),
                    lines = listOf(
                        stringResource(R.string.help_limit_offline),
                        stringResource(R.string.help_limit_keys),
                        stringResource(R.string.help_limit_history),
                    ),
                )
            }
        }
    }
}

@Composable
private fun HelpCard(
    title: String,
    lines: List<String>,
) {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AmberText(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = AmberPrimary,
            )
            AmberText(
                text = stringResource(R.string.help_separator),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
                color = AmberMutedForeground,
                maxLines = 1,
            )
            lines.forEach { line ->
                AmberText(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AmberMono),
                    color = AmberMutedForeground,
                )
            }
        }
    }
}
