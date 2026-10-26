package com.example.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

data class AuraShapes(
    val small: CornerBasedShape = RoundedCornerShape(12.dp),
    val medium: CornerBasedShape = RoundedCornerShape(20.dp),
    val large: CornerBasedShape = RoundedCornerShape(28.dp),
    val sheet: CornerBasedShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
    val pill: CornerBasedShape = RoundedCornerShape(999.dp)
)

val LocalAuraShapes = staticCompositionLocalOf { AuraShapes() }
