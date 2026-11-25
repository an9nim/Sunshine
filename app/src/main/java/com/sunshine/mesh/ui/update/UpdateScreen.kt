package com.sunshine.mesh.ui.update

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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.R
import com.sunshine.mesh.ui.components.AmberButton
import com.sunshine.mesh.ui.components.AmberCard
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.components.AmberTextButton
import com.sunshine.mesh.ui.components.AmberTopBar
import com.sunshine.mesh.ui.theme.AmberBackground
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberDestructive
import com.sunshine.mesh.ui.theme.AmberMono
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary

@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onCheckForUpdates: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AmberBackground)
            .systemBarsPadding(),
    ) {
        AmberTopBar(
            title = stringResource(R.string.nav_update),
            subtitle = stringResource(R.string.update_subtitle),
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
                VersionCard(
                    state = state,
                    onCheckForUpdates = onCheckForUpdates,
                )
            }
            item {
                UpdateStatusCard(
                    state = state,
                    onDownload = { url -> runCatching { uriHandler.openUri(url) } },
                )
            }
            item {
                OfflineCard()
            }
        }
    }
}

@Composable
private fun VersionCard(
    state: UpdateUiState,
    onCheckForUpdates: () -> Unit,
) {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AmberText(
                text = stringResource(R.string.update_current_version),
                style = MaterialTheme.typography.labelLarge,
                color = AmberMutedForeground,
            )
            AmberText(
                text = state.currentVersion,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = AmberMono),
                color = AmberPrimary,
            )
            AmberButton(
                text = stringResource(R.string.update_check),
                onClick = onCheckForUpdates,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.checking,
            )
        }
    }
}

@Composable
private fun UpdateStatusCard(
    state: UpdateUiState,
    onDownload: (String) -> Unit,
) {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AmberText(
                text = stringResource(R.string.update_check_title),
                style = MaterialTheme.typography.titleMedium,
            )
            AmberText(
                text = stringResource(R.string.help_separator),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
                color = AmberMutedForeground,
                maxLines = 1,
            )

            when {
                state.checking -> AmberText(
                    text = stringResource(R.string.update_checking),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = AmberMono),
                    color = AmberMutedForeground,
                )

                state.result == null -> AmberText(
                    text = stringResource(R.string.update_idle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberMutedForeground,
                )

                else -> when (val result = state.result) {
                    is UpdateResult.Available -> {
                        AmberText(
                            text = stringResource(R.string.update_available),
                            style = MaterialTheme.typography.labelLarge,
                            color = AmberMutedForeground,
                        )
                        AmberText(
                            text = result.tag,
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontFamily = AmberMono,
                            ),
                            color = AmberPrimary,
                        )
                        AmberButton(
                            text = stringResource(R.string.update_download),
                            onClick = { onDownload(result.downloadUrl) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    is UpdateResult.UpToDate -> {
                        AmberText(
                            text = stringResource(R.string.update_up_to_date),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        AmberText(
                            text = result.latestTag,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = AmberMono,
                            ),
                            color = AmberPrimary,
                        )
                    }

                    UpdateResult.NoConnection -> AmberText(
                        text = stringResource(R.string.update_no_connection),
                        style = MaterialTheme.typography.bodyMedium,
                        color = AmberDestructive,
                    )

                    is UpdateResult.Failed -> {
                        AmberText(
                            text = stringResource(R.string.update_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberDestructive,
                        )
                        AmberText(
                            text = stringResource(R.string.update_error_code, result.code),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = AmberMono,
                            ),
                            color = AmberDestructive,
                        )
                    }

                    null -> Unit
                }
            }

            AmberText(
                text = stringResource(R.string.update_source),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = AmberMono),
                color = AmberMutedForeground,
            )
        }
    }
}

@Composable
private fun OfflineCard() {
    AmberCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AmberText(
                text = stringResource(R.string.update_offline_title),
                style = MaterialTheme.typography.titleMedium,
            )
            AmberText(
                text = stringResource(R.string.update_offline_body),
                style = MaterialTheme.typography.bodyMedium,
                color = AmberMutedForeground,
            )
        }
    }
}
