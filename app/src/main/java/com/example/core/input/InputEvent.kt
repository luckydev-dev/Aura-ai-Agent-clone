package com.example.core.input

enum class InputEventType {
    TAP,
    DOUBLE_TAP,
    LONG_PRESS,
    SWIPE,
    SCROLL,
    DRAG,
    DRAW_PATH,
    TYPE_TEXT,
    KEY_EVENT
}

data class InputEvent(
    val type: InputEventType,
    val startX: Float = 0f,
    val startY: Float = 0f,
    val endX: Float = 0f,
    val endY: Float = 0f,
    val pathPoints: List<Pair<Float, Float>> = emptyList(),
    val durationMs: Long = 0L,
    val text: String = "",
    val keyCode: Int = 0,
    val label: String = ""
)
