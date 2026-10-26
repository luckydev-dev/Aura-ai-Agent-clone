package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable

object AuraTheme {
    val colors: AuraColorPalette
        @Composable
        @ReadOnlyComposable
        get() = LocalAuraColors.current

    val typography: AuraTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalAuraTypography.current

    val shapes: AuraShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalAuraShapes.current
}

@Composable
fun AuraTheme(
    themeMode: String = "dark", // "dark", "light", "system"
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true // default dark
    }

    val palette = if (isDark) DarkAuraColors else LightAuraColors
    val typography = AuraTypography()
    val shapes = AuraShapes()

    CompositionLocalProvider(
        LocalAuraColors provides palette,
        LocalAuraTypography provides typography,
        LocalAuraShapes provides shapes,
        content = content
    )
}
