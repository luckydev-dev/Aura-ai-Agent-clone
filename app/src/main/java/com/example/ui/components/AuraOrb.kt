package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AuraTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ACTING,
    ERROR,
    RECONNECTING
}

@Composable
fun AuraOrb(
    state: OrbState,
    amplitude: Float, // 0.0 to 1.0 (mic or speaker)
    modifier: Modifier = Modifier,
    size: Dp = 260.dp
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbInfiniteTransition")

    // Breathing pulse for idle
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "IdlePulse"
    )

    // Swirl rotation for thinking
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "SwirlRotation"
    )

    // Ripple radius multiplier for acting
    val actingRipple by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ActingRipple"
    )

    val actingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.7f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ActingAlpha"
    )

    // Interpolate amplitude smoothly
    val animatedAmplitude by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy),
        label = "Amplitude"
    )

    val colors = AuraTheme.colors

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = (this.size.minDimension / 2.8f)

            when (state) {
                OrbState.IDLE -> {
                    val r = baseRadius * idlePulse
                    drawGlowAndOrb(center, r, colors.accentStart, colors.accentMiddle, colors.accentEnd, alpha = 0.85f)
                }
                OrbState.LISTENING -> {
                    // Reacts strongly to microphone amplitude
                    val dynamicRadius = baseRadius * (1.0f + animatedAmplitude * 0.45f)
                    // Draw reactive waveform ring
                    drawWaveformRings(center, dynamicRadius, animatedAmplitude, colors.accentStart, colors.accentMiddle)
                    drawGlowAndOrb(center, dynamicRadius, colors.accentStart, colors.accentMiddle, colors.accentEnd, alpha = 0.95f)
                }
                OrbState.THINKING -> {
                    // Soft swirl pulse
                    val r = baseRadius * 1.02f
                    drawSwirlOrb(center, r, rotationAngle, colors.accentMiddle, colors.accentEnd, colors.accentStart)
                }
                OrbState.SPEAKING -> {
                    // Reacts dynamically to speaker output amplitude
                    val dynamicRadius = baseRadius * (1.0f + animatedAmplitude * 0.55f)
                    drawWaveformRings(center, dynamicRadius, animatedAmplitude, colors.accentMiddle, colors.accentEnd)
                    drawGlowAndOrb(center, dynamicRadius, colors.accentMiddle, colors.accentEnd, colors.accentStart, alpha = 1.0f)
                }
                OrbState.ACTING -> {
                    // Ripple rings indicating phone operation
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(colors.accentEnd.copy(alpha = actingAlpha), Color.Transparent),
                            center = center,
                            radius = baseRadius * actingRipple
                        ),
                        radius = baseRadius * actingRipple,
                        center = center
                    )
                    drawCircle(
                        color = colors.accentStart.copy(alpha = actingAlpha * 0.8f),
                        radius = baseRadius * actingRipple,
                        center = center,
                        style = Stroke(width = 3.dp.toPx())
                    )
                    drawGlowAndOrb(center, baseRadius, colors.accentMiddle, colors.accentEnd, colors.accentStart, alpha = 0.9f)
                }
                OrbState.ERROR -> {
                    // Red shimmer
                    drawGlowAndOrb(center, baseRadius * idlePulse, colors.danger, colors.warning, colors.danger, alpha = 0.9f)
                }
                OrbState.RECONNECTING -> {
                    // Dim amber pulse
                    val dimRadius = baseRadius * idlePulse * 0.9f
                    drawGlowAndOrb(center, dimRadius, colors.warning, colors.textTertiary, colors.surfaceHighlight, alpha = 0.5f)
                }
            }
        }
    }
}

private fun DrawScope.drawGlowAndOrb(
    center: Offset,
    radius: Float,
    c1: Color,
    c2: Color,
    c3: Color,
    alpha: Float
) {
    // 1. Ambient outer aura
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                c2.copy(alpha = 0.40f * alpha),
                c3.copy(alpha = 0.18f * alpha),
                Color.Transparent
            ),
            center = center,
            radius = radius * 1.7f
        ),
        radius = radius * 1.7f,
        center = center
    )

    // 2. Mid organic glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                c1.copy(alpha = 0.70f * alpha),
                c2.copy(alpha = 0.45f * alpha),
                Color.Transparent
            ),
            center = center,
            radius = radius * 1.25f
        ),
        radius = radius * 1.25f,
        center = center
    )

    // 3. Core fluid gradient sphere
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                c1.copy(alpha = alpha),
                c2.copy(alpha = alpha),
                c3.copy(alpha = alpha)
            ),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius)
        ),
        radius = radius,
        center = center
    )

    // 4. Subtle inner highlight shine
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.45f * alpha),
                Color.Transparent
            ),
            center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f),
            radius = radius * 0.7f
        ),
        radius = radius * 0.7f,
        center = Offset(center.x - radius * 0.35f, center.y - radius * 0.35f)
    )
}

private fun DrawScope.drawWaveformRings(
    center: Offset,
    radius: Float,
    amplitude: Float,
    color1: Color,
    color2: Color
) {
    val ringCount = 16
    val maxBarHeight = 32.dp.toPx() * amplitude

    for (i in 0 until ringCount) {
        val angleRad = (i.toFloat() / ringCount) * 2f * PI.toFloat()
        val cosA = cos(angleRad)
        val sinA = sin(angleRad)

        val startX = center.x + radius * cosA
        val startY = center.y + radius * sinA
        val barLen = (maxBarHeight * (0.4f + 0.6f * sin(i * 1.5f).coerceAtLeast(0f)))
        val endX = center.x + (radius + barLen) * cosA
        val endY = center.y + (radius + barLen) * sinA

        drawLine(
            color = if (i % 2 == 0) color1.copy(alpha = 0.7f) else color2.copy(alpha = 0.7f),
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = 3.dp.toPx(),
            cap = androidx.compose.ui.graphics.StrokeCap.Round
        )
    }
}

private fun DrawScope.drawSwirlOrb(
    center: Offset,
    radius: Float,
    angleDeg: Float,
    c1: Color,
    c2: Color,
    c3: Color
) {
    val rad = Math.toRadians(angleDeg.toDouble())
    val startX = center.x + (radius * cos(rad)).toFloat()
    val startY = center.y + (radius * sin(rad)).toFloat()
    val endX = center.x - (radius * cos(rad)).toFloat()
    val endY = center.y - (radius * sin(rad)).toFloat()

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(c1.copy(alpha = 0.4f), Color.Transparent),
            center = center,
            radius = radius * 1.5f
        ),
        radius = radius * 1.5f,
        center = center
    )

    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(c1, c2, c3),
            start = Offset(startX, startY),
            end = Offset(endX, endY)
        ),
        radius = radius,
        center = center
    )
}
