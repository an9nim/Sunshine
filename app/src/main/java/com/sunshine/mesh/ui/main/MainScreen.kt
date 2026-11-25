package com.sunshine.mesh.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sunshine.mesh.AppGraph
import com.sunshine.mesh.R
import com.sunshine.mesh.ui.components.AmberButton
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.navigation.SunshineNavHost
import com.sunshine.mesh.ui.onboarding.OnboardingScreen
import com.sunshine.mesh.ui.theme.AmberBackground
import com.sunshine.mesh.ui.theme.AmberMutedForeground

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    graph: AppGraph,
) {
    val profileState by viewModel.profileState.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AmberBackground),
    ) {
        when (val state = profileState) {
            ProfileUiState.Loading -> LoadingContent()
            ProfileUiState.Missing -> OnboardingScreen(
                form = form,
                onNicknameChange = viewModel::onNicknameChange,
                onCreateProfile = viewModel::createProfile,
            )

            is ProfileUiState.Ready -> {
                LaunchedEffect(Unit) {
                    graph.transportClient.start()
                }
                SunshineNavHost(graph = graph)
            }

            ProfileUiState.LoadFailed -> LoadFailedContent(onRetry = viewModel::reload)
        }
    }
}

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        AmberText(
            text = stringResource(R.string.loading),
            color = AmberMutedForeground,
        )
    }
}

@Composable
private fun LoadFailedContent(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AmberText(
            text = stringResource(R.string.load_error_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        AmberText(
            text = stringResource(R.string.load_error_body),
            modifier = Modifier.padding(top = 12.dp),
            color = AmberMutedForeground,
            textAlign = TextAlign.Center,
        )
        AmberButton(
            text = stringResource(R.string.load_error_retry),
            onClick = onRetry,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
