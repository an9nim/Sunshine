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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.R
import com.sunshine.mesh.domain.validation.NicknameError
import com.sunshine.mesh.ui.components.AmberButton
import com.sunshine.mesh.ui.components.AmberText
import com.sunshine.mesh.ui.components.AmberTextField
import com.sunshine.mesh.ui.main.OnboardingForm
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary

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
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AmberText(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineLarge,
            color = AmberPrimary,
        )
        AmberText(
            text = stringResource(R.string.onboarding_tagline),
            style = MaterialTheme.typography.headlineMedium,
        )
        AmberText(
            text = stringResource(R.string.onboarding_description),
            style = MaterialTheme.typography.bodyMedium,
            color = AmberMutedForeground,
        )

        Spacer(Modifier.height(20.dp))

        AmberText(
            text = stringResource(R.string.nickname_label),
            style = MaterialTheme.typography.labelMedium,
            color = AmberMutedForeground,
        )
        AmberTextField(
            value = form.nickname,
            onValueChange = onNicknameChange,
            placeholder = stringResource(R.string.nickname_hint),
            isError = form.nicknameError != null || form.submitFailed,
            errorText = when {
                form.nicknameError != null ->
                    stringResource(form.nicknameError.messageRes())

                form.submitFailed -> stringResource(R.string.profile_create_error)
                else -> null
            },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onCreateProfile() }),
        )

        AmberButton(
            text = stringResource(
                if (form.isSubmitting) {
                    R.string.onboarding_action_busy
                } else {
                    R.string.onboarding_action
                },
            ),
            onClick = onCreateProfile,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            enabled = !form.isSubmitting,
        )

        AmberText(
            text = stringResource(R.string.onboarding_footnote),
            style = MaterialTheme.typography.labelSmall,
            color = AmberMutedForeground,
        )
    }
}

private fun NicknameError.messageRes(): Int = when (this) {
    NicknameError.EMPTY -> R.string.nickname_error_empty
    NicknameError.TOO_LONG -> R.string.nickname_error_too_long
    NicknameError.CONTROL_CHARS -> R.string.nickname_error_control
}
