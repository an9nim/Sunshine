package com.sunshine.mesh.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.ui.theme.AmberMutedForeground

@Composable
fun AmberLoadingState(
    text: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AmberText(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = AmberMutedForeground,
        )
    }
}

@Composable
fun AmberEmptyState(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AmberCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AmberText(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                )
                AmberText(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberMutedForeground,
                )
                AmberButton(
                    text = actionText,
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun AmberErrorState(
    title: String,
    body: String,
    actionText: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AmberCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AmberText(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Start,
                )
                AmberText(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberMutedForeground,
                )
                AmberButton(
                    text = actionText,
                    onClick = onAction,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
