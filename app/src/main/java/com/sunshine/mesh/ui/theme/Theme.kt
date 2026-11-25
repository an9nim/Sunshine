package com.sunshine.mesh.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** One radius for every surface: cards, inputs, buttons and avatars. */
val AmberShape = RoundedCornerShape(14.dp)

private val AmberColorScheme = darkColorScheme(
    primary = AmberPrimary,
    onPrimary = AmberPrimaryForeground,
    primaryContainer = AmberPrimary,
    onPrimaryContainer = AmberPrimaryForeground,
    secondary = AmberSecondary,
    onSecondary = AmberSecondaryForeground,
    background = AmberBackground,
    onBackground = AmberForeground,
    surface = AmberCard,
    onSurface = AmberCardForeground,
    surfaceVariant = AmberMuted,
    onSurfaceVariant = AmberMutedForeground,
    outline = AmberBorder,
    error = AmberDestructive,
    onError = AmberDestructiveForeground,
)

private val AmberShapes = Shapes(
    extraSmall = AmberShape,
    small = AmberShape,
    medium = AmberShape,
    large = AmberShape,
    extraLarge = AmberShape,
)

/** Dark-only Amber Glow theme. There is deliberately no light variant. */
@Composable
fun SunshineTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AmberColorScheme,
        typography = SunshineTypography,
        shapes = AmberShapes,
        content = content,
    )
}
