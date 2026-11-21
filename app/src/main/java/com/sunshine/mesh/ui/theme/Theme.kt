package com.sunshine.mesh.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val SunshineColorScheme = darkColorScheme(
    primary = SunshineYellow,
    onPrimary = SunshineOnYellow,
    secondary = SunshineMuted,
    onSecondary = SunshineInk,
    background = SunshineInk,
    onBackground = SunshinePaper,
    surface = SunshineSurface,
    onSurface = SunshinePaper,
    surfaceVariant = SunshineElevated,
    onSurfaceVariant = SunshineMuted,
    outline = SunshineOutline,
    error = SunshineError,
    onError = SunshineInk,
)

@Composable
fun SunshineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SunshineColorScheme,
        typography = SunshineTypography,
        content = content,
    )
}
