package com.example.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

data class AuraColorPalette(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceHighlight: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accentStart: Color,
    val accentMiddle: Color,
    val accentEnd: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean
) {
    val accentGradient: Brush
        get() = Brush.linearGradient(listOf(accentStart, accentMiddle, accentEnd))

    val orbHaloGradient: Brush
        get() = Brush.radialGradient(
            colors = listOf(
                accentMiddle.copy(alpha = 0.45f),
                accentEnd.copy(alpha = 0.2f),
                Color.Transparent
            )
        )
}

val DarkAuraColors = AuraColorPalette(
    background = Color(0xFF0B0B0F),
    surface = Color(0xFF14141B),
    surfaceRaised = Color(0xFF1C1C26),
    surfaceHighlight = Color(0xFF262635),
    border = Color(0x14FFFFFF),
    textPrimary = Color(0xFFF4F4F8),
    textSecondary = Color(0xFFA0A0B2),
    textTertiary = Color(0xFF6B6B7D),
    accentStart = Color(0xFFFFB36B),
    accentMiddle = Color(0xFFFF6B8B),
    accentEnd = Color(0xFF8B6BFF),
    success = Color(0xFF5CE0A1),
    warning = Color(0xFFFFC857),
    danger = Color(0xFFFF5C6C),
    isDark = true
)

val LightAuraColors = AuraColorPalette(
    background = Color(0xFFF6F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceRaised = Color(0xFFEBEBF2),
    surfaceHighlight = Color(0xFFDFDFEA),
    border = Color(0x14000000),
    textPrimary = Color(0xFF12121A),
    textSecondary = Color(0xFF5A5A6E),
    textTertiary = Color(0xFF8C8CA0),
    accentStart = Color(0xFFFF9540),
    accentMiddle = Color(0xFFFF486E),
    accentEnd = Color(0xFF724DFF),
    success = Color(0xFF2EBD78),
    warning = Color(0xFFE6A624),
    danger = Color(0xFFEB3B4D),
    isDark = false
)

val LocalAuraColors = staticCompositionLocalOf { DarkAuraColors }
