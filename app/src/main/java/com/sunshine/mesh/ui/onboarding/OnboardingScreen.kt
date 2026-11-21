package com.sunshine.mesh.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.R
import com.sunshine.mesh.domain.validation.NicknameError
import com.sunshine.mesh.ui.main.OnboardingForm

@Composable
fun OnboardingScreen(
    form: OnboardingForm,
    onNicknameChange: (String) -> Unit,
    onCreateProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.onboarding_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = form.nickname,
            onValueChange = onNicknameChange,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.nickname_label)) },
            placeholder = { Text(stringResource(R.string.nickname_hint)) },
            singleLine = true,
            isError = form.nicknameError != null || form.submitFailed,
            supportingText = {
                val error = form.nicknameError
                if (error != null) {
                    Text(stringResource(error.messageRes()))
                } else if (form.submitFailed) {
                    Text(stringResource(R.string.profile_create_error))
                }
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCreateProfile() }),
        )

        Button(
            onClick = onCreateProfile,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !form.isSubmitting,
        ) {
            Text(
                stringResource(
                    if (form.isSubmitting) {
                        R.string.onboarding_action_busy
                    } else {
                        R.string.onboarding_action
                    },
                ),
            )
        }

        Text(
            text = stringResource(R.string.onboarding_footnote),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun NicknameError.messageRes(): Int = when (this) {
    NicknameError.EMPTY -> R.string.nickname_error_empty
    NicknameError.TOO_LONG -> R.string.nickname_error_too_long
    NicknameError.CONTROL_CHARS -> R.string.nickname_error_control
}
