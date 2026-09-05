package com.rigstudio.app.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * RigStudio's palette: a premium dark shell with one confident accent.
 *
 * The stage is the only bright thing on screen — everything around it stays out of the way, which
 * is what makes artwork (and exported video) easy to judge.
 */
object RigColors {
    val Background = Color(0xFF0B0E14)
    val Surface = Color(0xFF12161F)
    val SurfaceRaised = Color(0xFF171C28)
    val SurfaceVariant = Color(0xFF1E2534)
    val Outline = Color(0xFF2A3242)
    val OutlineSoft = Color(0xFF1F2634)

    val Primary = Color(0xFF3FBFAE)
    val PrimaryDim = Color(0xFF2A8C80)
    val OnPrimary = Color(0xFF04120F)

    val Secondary = Color(0xFF7AA2FF)
    val OnSecondary = Color(0xFF08122B)

    val Tertiary = Color(0xFFF0B357)
    val OnTertiary = Color(0xFF2A1B02)

    val Error = Color(0xFFFF6B6B)
    val OnError = Color(0xFF2B0707)

    val TextPrimary = Color(0xFFE7ECF5)
    val TextSecondary = Color(0xFF9AA6BC)
    val TextDisabled = Color(0xFF5C6779)

    /** The editor's default backdrop; identical to the export default so preview == output. */
    val StageDefault = Color(0xFF12161C)
}
