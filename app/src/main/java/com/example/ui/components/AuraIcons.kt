package com.example.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

object AuraIcons {

    val Mic: ImageVector by lazy {
        ImageVector.Builder(
            name = "Mic",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = SolidColor(Color.White),
                stroke = null
            ) {
                moveTo(12f, 14f)
                curveTo(13.66f, 14f, 15f, 12.66f, 15f, 11f)
                lineTo(15f, 5f)
                curveTo(15f, 3.34f, 13.66f, 2f, 12f, 2f)
                curveTo(10.34f, 2f, 9f, 3.34f, 9f, 5f)
                lineTo(9f, 11f)
                curveTo(9f, 12.66f, 10.34f, 14f, 12f, 14f)
                close()
                moveTo(17f, 11f)
                curveTo(17f, 13.76f, 14.76f, 16f, 12f, 16f)
                curveTo(9.24f, 16f, 7f, 13.76f, 7f, 11f)
                lineTo(5f, 11f)
                curveTo(5f, 14.53f, 7.61f, 17.43f, 11f, 17.93f)
                lineTo(11f, 21f)
                lineTo(13f, 21f)
                lineTo(13f, 17.93f)
                curveTo(16.39f, 17.43f, 19f, 14.53f, 19f, 11f)
                lineTo(17f, 11f)
                close()
            }
        }.build()
    }

    val MicOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "MicOff",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(3f, 3f)
                lineTo(21f, 21f)
                moveTo(9f, 9f)
                lineTo(9f, 11f)
                moveTo(15f, 11f)
                lineTo(15f, 9f)
                moveTo(5f, 11f)
                lineTo(5f, 12f)
                moveTo(19f, 11f)
                lineTo(19f, 12f)
                moveTo(12f, 19f)
                lineTo(12f, 22f)
            }
        }.build()
    }

    val CallEnd: ImageVector by lazy {
        ImageVector.Builder(
            name = "CallEnd",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 9f)
                curveTo(10.39f, 9f, 8.85f, 9.28f, 7.4f, 9.79f)
                lineTo(7.4f, 12.35f)
                curveTo(7.4f, 12.67f, 7.21f, 12.96f, 6.92f, 13.08f)
                curveTo(5.84f, 13.52f, 4.88f, 14.15f, 4.05f, 14.94f)
                curveTo(3.84f, 15.15f, 3.5f, 15.15f, 3.29f, 14.94f)
                lineTo(1.05f, 12.7f)
                curveTo(0.84f, 12.49f, 0.84f, 12.15f, 1.05f, 11.94f)
                curveTo(3.88f, 9.17f, 7.73f, 7.5f, 12f, 7.5f)
                curveTo(16.27f, 7.5f, 20.12f, 9.17f, 22.95f, 11.94f)
                curveTo(23.16f, 12.15f, 23.16f, 12.49f, 22.95f, 12.7f)
                lineTo(20.71f, 14.94f)
                curveTo(20.5f, 15.15f, 20.16f, 15.15f, 19.95f, 14.94f)
                curveTo(19.12f, 14.15f, 18.16f, 13.52f, 17.08f, 13.08f)
                curveTo(16.79f, 12.96f, 16.6f, 12.67f, 16.6f, 12.35f)
                lineTo(16.6f, 9.79f)
                curveTo(15.15f, 9.28f, 13.61f, 9f, 12f, 9f)
                close()
            }
        }.build()
    }

    val Speaker: ImageVector by lazy {
        ImageVector.Builder(
            name = "Speaker",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(3f, 9f)
                lineTo(3f, 15f)
                lineTo(7f, 15f)
                lineTo(12f, 20f)
                lineTo(12f, 4f)
                lineTo(7f, 9f)
                lineTo(3f, 9f)
                close()
                moveTo(16.5f, 12f)
                curveTo(16.5f, 10.23f, 15.48f, 8.71f, 14f, 7.97f)
                lineTo(14f, 16.02f)
                curveTo(15.48f, 15.29f, 16.5f, 13.77f, 16.5f, 12f)
                close()
                moveTo(14f, 3.23f)
                lineTo(14f, 5.29f)
                curveTo(16.89f, 6.15f, 19f, 8.83f, 19f, 12f)
                curveTo(19f, 15.17f, 16.89f, 17.85f, 14f, 18.71f)
                lineTo(14f, 20.77f)
                curveTo(18.01f, 19.86f, 21f, 16.28f, 21f, 12f)
                curveTo(21f, 7.72f, 18.01f, 4.14f, 14f, 3.23f)
                close()
            }
        }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "Settings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(19.43f, 12.98f)
                curveTo(19.47f, 12.66f, 19.5f, 12.34f, 19.5f, 12f)
                curveTo(19.5f, 11.66f, 19.47f, 11.34f, 19.43f, 11.02f)
                lineTo(21.54f, 9.37f)
                curveTo(21.73f, 9.22f, 21.78f, 8.95f, 21.66f, 8.73f)
                lineTo(19.66f, 5.27f)
                curveTo(19.54f, 5.05f, 19.27f, 4.96f, 19.05f, 5.05f)
                lineTo(16.56f, 6.05f)
                curveTo(16.04f, 5.66f, 15.5f, 5.32f, 14.87f, 5.07f)
                lineTo(14.49f, 2.42f)
                curveTo(14.46f, 2.18f, 14.25f, 2f, 14f, 2f)
                lineTo(10f, 2f)
                curveTo(9.75f, 2f, 9.54f, 2.18f, 9.51f, 2.42f)
                lineTo(9.13f, 5.07f)
                curveTo(8.5f, 5.32f, 7.96f, 5.66f, 7.44f, 6.05f)
                lineTo(4.95f, 5.05f)
                curveTo(4.72f, 4.96f, 4.46f, 5.05f, 4.34f, 5.27f)
                lineTo(2.34f, 8.73f)
                curveTo(2.21f, 8.95f, 2.27f, 9.22f, 2.46f, 9.37f)
                lineTo(4.57f, 11.02f)
                curveTo(4.53f, 11.34f, 4.5f, 11.67f, 4.5f, 12f)
                curveTo(4.5f, 12.33f, 4.53f, 12.66f, 4.57f, 12.98f)
                lineTo(2.46f, 14.63f)
                curveTo(2.26f, 14.78f, 2.21f, 15.05f, 2.34f, 15.27f)
                lineTo(4.34f, 18.73f)
                curveTo(4.46f, 18.95f, 4.73f, 19.03f, 4.95f, 18.95f)
                lineTo(7.44f, 17.95f)
                curveTo(7.96f, 18.34f, 8.5f, 18.68f, 9.13f, 18.93f)
                lineTo(9.51f, 21.58f)
                curveTo(9.54f, 21.82f, 9.75f, 22f, 10f, 22f)
                lineTo(14f, 22f)
                curveTo(14.25f, 22f, 14.46f, 21.82f, 14.49f, 21.58f)
                lineTo(14.87f, 18.93f)
                curveTo(15.5f, 18.67f, 16.04f, 18.34f, 16.56f, 17.95f)
                lineTo(19.05f, 18.95f)
                curveTo(19.28f, 19.04f, 19.54f, 18.95f, 19.66f, 18.73f)
                lineTo(21.66f, 15.27f)
                curveTo(21.78f, 15.05f, 21.73f, 14.78f, 21.54f, 14.63f)
                lineTo(19.43f, 12.98f)
                close()
                moveTo(12f, 15.5f)
                curveTo(10.07f, 15.5f, 8.5f, 13.93f, 8.5f, 12f)
                curveTo(8.5f, 10.07f, 10.07f, 8.5f, 12f, 8.5f)
                curveTo(13.93f, 8.5f, 15.5f, 10.07f, 15.5f, 12f)
                curveTo(15.5f, 13.93f, 13.93f, 15.5f, 12f, 15.5f)
                close()
            }
        }.build()
    }

    val Shield: ImageVector by lazy {
        ImageVector.Builder(
            name = "Shield",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(12f, 1f)
                lineTo(3f, 5f)
                lineTo(3f, 11f)
                curveTo(3f, 16.55f, 6.84f, 21.74f, 12f, 23f)
                curveTo(17.16f, 21.74f, 21f, 16.55f, 21f, 11f)
                lineTo(21f, 5f)
                lineTo(12f, 1f)
                close()
                moveTo(10f, 17f)
                lineTo(6f, 13f)
                lineTo(7.41f, 11.59f)
                lineTo(10f, 14.17f)
                lineTo(16.59f, 7.58f)
                lineTo(18f, 9f)
                lineTo(10f, 17f)
                close()
            }
        }.build()
    }

    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "Check",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 12f)
                lineTo(9f, 17f)
                lineTo(20f, 6f)
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Close",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2.5f,
                strokeLineCap = StrokeCap.Round
            ) {
                moveTo(5f, 5f)
                lineTo(19f, 19f)
                moveTo(19f, 5f)
                lineTo(5f, 19f)
            }
        }.build()
    }

    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder(
            name = "ArrowBack",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(20f, 11f)
                lineTo(7.83f, 11f)
                lineTo(13.42f, 5.41f)
                lineTo(12f, 4f)
                lineTo(4f, 12f)
                lineTo(12f, 20f)
                lineTo(13.41f, 18.59f)
                lineTo(7.83f, 13f)
                lineTo(20f, 13f)
                close()
            }
        }.build()
    }

    val Terminal: ImageVector by lazy {
        ImageVector.Builder(
            name = "Terminal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            ) {
                moveTo(4f, 17f)
                lineTo(10f, 11f)
                lineTo(4f, 5f)
                moveTo(12f, 19f)
                lineTo(20f, 19f)
            }
        }.build()
    }

    val Memory: ImageVector by lazy {
        ImageVector.Builder(
            name = "Memory",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(15f, 9f)
                lineTo(9f, 9f)
                lineTo(9f, 15f)
                lineTo(15f, 15f)
                lineTo(15f, 9f)
                close()
                moveTo(21f, 11f)
                lineTo(21f, 9f)
                lineTo(19f, 9f)
                lineTo(19f, 5f)
                curveTo(19f, 3.9f, 18.1f, 3f, 17f, 3f)
                lineTo(13f, 3f)
                lineTo(13f, 1f)
                lineTo(11f, 1f)
                lineTo(11f, 3f)
                lineTo(7f, 3f)
                curveTo(5.9f, 3f, 5f, 3.9f, 5f, 5f)
                lineTo(5f, 9f)
                lineTo(3f, 9f)
                lineTo(3f, 11f)
                lineTo(5f, 11f)
                lineTo(5f, 13f)
                lineTo(3f, 13f)
                lineTo(3f, 15f)
                lineTo(5f, 15f)
                lineTo(5f, 19f)
                curveTo(5f, 20.1f, 5.9f, 21f, 7f, 21f)
                lineTo(11f, 21f)
                lineTo(11f, 23f)
                lineTo(13f, 23f)
                lineTo(13f, 21f)
                lineTo(17f, 21f)
                curveTo(18.1f, 21f, 19f, 20.1f, 19f, 19f)
                lineTo(19f, 15f)
                lineTo(21f, 15f)
                lineTo(21f, 13f)
                lineTo(19f, 13f)
                lineTo(19f, 11f)
                lineTo(21f, 11f)
                close()
                moveTo(17f, 19f)
                lineTo(7f, 19f)
                lineTo(7f, 5f)
                lineTo(17f, 5f)
                lineTo(17f, 19f)
                close()
            }
        }.build()
    }

    val History: ImageVector by lazy {
        ImageVector.Builder(
            name = "History",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(13f, 3f)
                curveTo(8.03f, 3f, 4f, 7.03f, 4f, 12f)
                lineTo(1f, 12f)
                lineTo(4.89f, 15.89f)
                lineTo(5.03f, 16.03f)
                lineTo(9f, 12f)
                lineTo(6f, 12f)
                curveTo(6f, 8.13f, 9.13f, 5f, 13f, 5f)
                curveTo(16.87f, 5f, 20f, 8.13f, 20f, 12f)
                curveTo(20f, 15.87f, 16.87f, 19f, 13f, 19f)
                curveTo(11.07f, 19f, 9.32f, 18.21f, 8.06f, 16.94f)
                lineTo(6.64f, 18.36f)
                curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
                curveTo(17.97f, 21f, 22f, 16.97f, 22f, 12f)
                curveTo(22f, 7.03f, 17.97f, 3f, 13f, 3f)
                close()
                moveTo(12f, 8f)
                lineTo(12f, 13f)
                lineTo(16.28f, 15.54f)
                lineTo(17f, 14.33f)
                lineTo(13.5f, 12.25f)
                lineTo(13.5f, 8f)
                lineTo(12f, 8f)
                close()
            }
        }.build()
    }

    val Tools: ImageVector by lazy {
        ImageVector.Builder(
            name = "Tools",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(22.7f, 19f)
                lineTo(13.6f, 9.9f)
                curveTo(14.5f, 7.6f, 14f, 4.9f, 12.1f, 3f)
                curveTo(10.1f, 1f, 7.1f, 0.6f, 4.7f, 1.7f)
                lineTo(9f, 6f)
                lineTo(6f, 9f)
                lineTo(1.7f, 4.7f)
                curveTo(0.6f, 7.1f, 1f, 10.1f, 3f, 12.1f)
                curveTo(4.9f, 14f, 7.6f, 14.5f, 9.9f, 13.6f)
                lineTo(19f, 22.7f)
                curveTo(19.4f, 23.1f, 20f, 23.1f, 20.4f, 22.7f)
                lineTo(22.7f, 20.4f)
                curveTo(23.1f, 20f, 23.1f, 19.4f, 22.7f, 19f)
                close()
            }
        }.build()
    }

    val Stop: ImageVector by lazy {
        ImageVector.Builder(
            name = "Stop",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(6f, 6f)
                lineTo(18f, 6f)
                lineTo(18f, 18f)
                lineTo(6f, 18f)
                close()
            }
        }.build()
    }

    val Keyboard: ImageVector by lazy {
        ImageVector.Builder(
            name = "Keyboard",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(20f, 5f)
                lineTo(4f, 5f)
                curveTo(2.9f, 5f, 2f, 5.9f, 2f, 7f)
                lineTo(2f, 17f)
                curveTo(2f, 18.1f, 2.9f, 19f, 4f, 19f)
                lineTo(20f, 19f)
                curveTo(21.1f, 19f, 22f, 18.1f, 22f, 17f)
                lineTo(22f, 7f)
                curveTo(22f, 5.9f, 21.1f, 5f, 20f, 5f)
                close()
                moveTo(11f, 8f)
                lineTo(13f, 8f)
                lineTo(13f, 10f)
                lineTo(11f, 10f)
                close()
                moveTo(8f, 16f)
                lineTo(16f, 16f)
                lineTo(16f, 17f)
                lineTo(8f, 17f)
                close()
            }
        }.build()
    }

    val Send: ImageVector by lazy {
        ImageVector.Builder(
            name = "Send",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(2.01f, 21f)
                lineTo(23f, 12f)
                lineTo(2.01f, 3f)
                lineTo(2f, 10f)
                lineTo(17f, 12f)
                lineTo(2f, 14f)
                close()
            }
        }.build()
    }

    val Copy: ImageVector by lazy {
        ImageVector.Builder(
            name = "Copy",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(16f, 1f)
                lineTo(4f, 1f)
                curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f)
                lineTo(2f, 17f)
                lineTo(4f, 17f)
                lineTo(4f, 3f)
                lineTo(16f, 3f)
                lineTo(16f, 1f)
                close()
                moveTo(19f, 5f)
                lineTo(8f, 5f)
                curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f)
                lineTo(6f, 21f)
                curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f)
                lineTo(19f, 23f)
                curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
                lineTo(21f, 7f)
                curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f)
                close()
                moveTo(19f, 21f)
                lineTo(8f, 21f)
                lineTo(8f, 7f)
                lineTo(19f, 7f)
                lineTo(19f, 21f)
                close()
            }
        }.build()
    }

    val Refresh: ImageVector by lazy {
        ImageVector.Builder(
            name = "Refresh",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.White)) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveTo(7.58f, 4f, 4.01f, 7.58f, 4.01f, 12f)
                curveTo(4.01f, 16.42f, 7.58f, 20f, 12f, 20f)
                curveTo(15.73f, 20f, 18.84f, 17.45f, 19.73f, 14f)
                lineTo(17.65f, 14f)
                curveTo(16.83f, 16.33f, 14.61f, 18f, 12f, 18f)
                curveTo(8.69f, 18f, 6f, 15.31f, 6f, 12f)
                curveTo(6f, 8.69f, 8.69f, 6f, 12f, 6f)
                curveTo(13.66f, 6f, 15.14f, 6.69f, 16.22f, 7.78f)
                lineTo(13f, 11f)
                lineTo(20f, 11f)
                lineTo(20f, 4f)
                lineTo(17.65f, 6.35f)
                close()
            }
        }.build()
    }
}
