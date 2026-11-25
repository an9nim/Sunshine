package com.sunshine.mesh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.sunshine.mesh.ui.theme.AmberBorder
import com.sunshine.mesh.ui.theme.AmberCard
import com.sunshine.mesh.ui.theme.AmberDestructive
import com.sunshine.mesh.ui.theme.AmberForeground
import com.sunshine.mesh.ui.theme.AmberInput
import com.sunshine.mesh.ui.theme.AmberMutedForeground
import com.sunshine.mesh.ui.theme.AmberPrimary
import com.sunshine.mesh.ui.theme.AmberShape

/**
 * Custom input: BasicTextField inside a bordered 14 dp box. No Material
 * OutlinedTextField, so the border, radius and colors are explicit.
 */
@Composable
fun AmberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    isError: Boolean = false,
    errorText: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(AmberShape)
                .background(AmberCard)
                .border(
                    width = 1.dp,
                    color = if (isError) AmberDestructive else AmberInput,
                    shape = AmberShape,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            if (value.isEmpty() && placeholder != null) {
                AmberText(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AmberMutedForeground,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = AmberForeground),
                singleLine = singleLine,
                maxLines = if (singleLine) 1 else maxLines,
                cursorBrush = SolidColor(AmberPrimary),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
            )
        }
        if (errorText != null) {
            AmberText(
                text = errorText,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                style = MaterialTheme.typography.labelMedium,
                color = AmberDestructive,
            )
        }
    }
}
