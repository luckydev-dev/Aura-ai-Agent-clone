package com.example.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

enum class PatternState {
    NORMAL,
    SUCCESS,
    ERROR
}

/**
 * Authentic Android 3x3 Pattern Lock View with touch drag gesture,
 * connecting glow lines, dot selection rings, and haptic feedback.
 */
@Composable
fun PatternLockView(
    modifier: Modifier = Modifier,
    patternState: PatternState = PatternState.NORMAL,
    enabled: Boolean = true,
    onPatternCompleted: (List<Int>) -> Unit
) {
    val view = LocalView.current
    var selectedDots by remember { mutableStateOf(listOf<Int>()) }
    var currentTouchPos by remember { mutableStateOf<Offset?>(null) }

    // Colors matching theme
    val normalLineColor = Color(0xFF00E5FF)
    val successLineColor = Color(0xFF00E676)
    val errorLineColor = Color(0xFFFF5252)

    val lineColor = when (patternState) {
        PatternState.NORMAL -> normalLineColor
        PatternState.SUCCESS -> successLineColor
        PatternState.ERROR -> errorLineColor
    }

    val unselectedDotColor = Color.White.copy(alpha = 0.35f)
    val activeDotColor = lineColor

    BoxWithConstraints(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(16.dp))
            .pointerInput(enabled, patternState) {
                if (!enabled) return@pointerInput

                detectDragGestures(
                    onDragStart = { offset ->
                        selectedDots = emptyList()
                        currentTouchPos = offset
                        val hit = findHitDot(offset, size.width.toFloat(), size.height.toFloat())
                        if (hit != null) {
                            selectedDots = listOf(hit)
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        val pos = change.position
                        currentTouchPos = pos
                        val hit = findHitDot(pos, size.width.toFloat(), size.height.toFloat())
                        if (hit != null && hit !in selectedDots) {
                            selectedDots = selectedDots + hit
                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    },
                    onDragEnd = {
                        currentTouchPos = null
                        if (selectedDots.isNotEmpty()) {
                            onPatternCompleted(selectedDots)
                        }
                    },
                    onDragCancel = {
                        currentTouchPos = null
                        selectedDots = emptyList()
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cellW = w / 3f
            val cellH = h / 3f

            fun getDotCenter(index: Int): Offset {
                val col = index % 3
                val row = index / 3
                return Offset(
                    x = cellW * col + cellW / 2f,
                    y = cellH * row + cellH / 2f
                )
            }

            // 1. Draw connecting lines
            if (selectedDots.isNotEmpty()) {
                val path = Path()
                val firstCenter = getDotCenter(selectedDots.first())
                path.moveTo(firstCenter.x, firstCenter.y)

                for (i in 1 until selectedDots.size) {
                    val center = getDotCenter(selectedDots[i])
                    path.lineTo(center.x, center.y)
                }

                // Line to active finger position
                currentTouchPos?.let { touchPos ->
                    path.lineTo(touchPos.x, touchPos.y)
                }

                // Outer glow stroke
                drawPath(
                    path = path,
                    color = lineColor.copy(alpha = 0.35f),
                    style = Stroke(
                        width = 12.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )

                // Main stroke
                drawPath(
                    path = path,
                    color = lineColor,
                    style = Stroke(
                        width = 4.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
            }

            // 2. Draw 3x3 Dots
            val dotRadius = 7.dp.toPx()
            val ringRadius = 22.dp.toPx()

            for (i in 0 until 9) {
                val center = getDotCenter(i)
                val isSelected = i in selectedDots

                if (isSelected) {
                    // Outer ring for selected dot
                    drawCircle(
                        color = activeDotColor.copy(alpha = 0.25f),
                        radius = ringRadius,
                        center = center
                    )
                    drawCircle(
                        color = activeDotColor,
                        radius = ringRadius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                    // Inner dot
                    drawCircle(
                        color = activeDotColor,
                        radius = dotRadius * 1.3f,
                        center = center
                    )
                } else {
                    // Idle dot
                    drawCircle(
                        color = unselectedDotColor,
                        radius = dotRadius,
                        center = center
                    )
                }
            }
        }
    }
}

private fun findHitDot(offset: Offset, width: Float, height: Float): Int? {
    val cellW = width / 3f
    val cellH = height / 3f
    val hitRadius = (cellW * 0.38f).coerceAtMost(cellH * 0.38f)

    for (i in 0 until 9) {
        val col = i % 3
        val row = i / 3
        val cx = cellW * col + cellW / 2f
        val cy = cellH * row + cellH / 2f

        val dist = hypot(offset.x - cx, offset.y - cy)
        if (dist <= hitRadius) {
            return i
        }
    }
    return null
}
