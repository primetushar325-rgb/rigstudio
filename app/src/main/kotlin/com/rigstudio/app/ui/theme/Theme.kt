package com.rigstudio.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/**
 * RigStudio is always dark.
 *
 * That is a product decision, not an oversight: the app is a drawing/animation tool where the
 * artwork is the brightest thing on screen, and a light shell would make both the preview and the
 * exported video harder to judge. Following the system light theme would also make the stage's
 * default backdrop (which is part of the export) look wrong.
 */
private val RigColorScheme = darkColorScheme(
    primary = RigColors.Primary,
    onPrimary = RigColors.OnPrimary,
    primaryContainer = RigColors.PrimaryDim,
    onPrimaryContainer = RigColors.TextPrimary,
    inversePrimary = RigColors.PrimaryDim,
    secondary = RigColors.Secondary,
    onSecondary = RigColors.OnSecondary,
    secondaryContainer = RigColors.SurfaceVariant,
    onSecondaryContainer = RigColors.TextPrimary,
    tertiary = RigColors.Tertiary,
    onTertiary = RigColors.OnTertiary,
    tertiaryContainer = RigColors.SurfaceVariant,
    onTertiaryContainer = RigColors.TextPrimary,
    background = RigColors.Background,
    onBackground = RigColors.TextPrimary,
    surface = RigColors.Surface,
    onSurface = RigColors.TextPrimary,
    surfaceVariant = RigColors.SurfaceVariant,
    onSurfaceVariant = RigColors.TextSecondary,
    surfaceTint = RigColors.Primary,
    inverseSurface = RigColors.TextPrimary,
    inverseOnSurface = RigColors.Background,
    error = RigColors.Error,
    onError = RigColors.OnError,
    errorContainer = RigColors.SurfaceVariant,
    onErrorContainer = RigColors.Error,
    outline = RigColors.Outline,
    outlineVariant = RigColors.OutlineSoft,
    scrim = RigColors.Background,
    surfaceBright = RigColors.SurfaceRaised,
    surfaceDim = RigColors.Background,
    surfaceContainer = RigColors.Surface,
    surfaceContainerHigh = RigColors.SurfaceRaised,
    surfaceContainerHighest = RigColors.SurfaceVariant,
    surfaceContainerLow = RigColors.Surface,
    surfaceContainerLowest = RigColors.Background,
)

/** Corner language: cards and controls are softly rounded; the stage is nearly square. */
private val RigShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun RigStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RigColorScheme,
        typography = RigTypography,
        shapes = RigShapes,
        content = content,
    )
}
