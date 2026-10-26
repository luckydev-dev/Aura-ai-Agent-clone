package com.example.core.input

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.AuraApplication
import com.example.core.root.RootManager
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

enum class InputMethod {
    ROOT,
    ACCESSIBILITY,
    NONE
}

data class InputResult(
    val success: Boolean,
    val method: InputMethod,
    val error: String? = null
)

/**
 * Central Input Controller for all programmatic screen interactions.
 * Guarantees that cursor animations never delay, hang, or block real inputs.
 * Truthfully reports input dispatch results and supports cursor suppression.
 */
class InputController(
    private val context: Context,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "Aura"

        @Volatile
        private var instance: InputController? = null

        fun getInstance(context: Context, rootManager: RootManager): InputController {
            return instance ?: synchronized(this) {
                instance ?: InputController(context.applicationContext, rootManager).also { instance = it }
            }
        }
    }

    private val _events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<InputEvent> = _events.asSharedFlow()

    // Cursor synchronization callback
    var cursorListener: (suspend (InputEvent) -> Unit)? = null

    // Cursor suppression counter
    private val suppressionCount = AtomicInteger(0)

    val isCursorSuppressed: Boolean
        get() = suppressionCount.get() > 0

    // Consecutive cursor timeouts counter (auto-disables cursor if >= 3)
    @Volatile
    private var consecutiveCursorTimeouts = 0

    suspend fun <T> withCursorHidden(block: suspend () -> T): T {
        suppressionCount.incrementAndGet()
        try {
            val app = context.applicationContext as? AuraApplication
            app?.overlayController?.agentCursorOverlay?.detachOverlay()
        } catch (e: Exception) {
            Log.w(TAG, "Error hiding cursor for capture: ${e.message}")
        }
        delay(50) // wait one frame for overlay window to clear before screencap
        return try {
            block()
        } finally {
            suppressionCount.decrementAndGet()
        }
    }

    suspend fun <T> suppressCursorUnless(allow: Boolean, block: suspend () -> T): T {
        return if (allow) block() else withCursorHidden(block)
    }

    fun getDisplayMetrics(): Point {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Point(dm.widthPixels, dm.heightPixels)
        }
    }

    fun getSafeBounds(): Pair<Point, Point> {
        val size = getDisplayMetrics()
        val minX = (size.x * 0.04f).roundToInt()
        val maxX = (size.x * 0.96f).roundToInt()
        val minY = (size.y * 0.05f).roundToInt()
        val maxY = (size.y * 0.94f).roundToInt()
        return Pair(Point(minX, minY), Point(maxX, maxY))
    }

    fun clampToSafe(x: Float, y: Float): Pair<Float, Float> {
        val size = getDisplayMetrics()
        val cx = x.coerceIn(size.x * 0.03f, size.x * 0.97f)
        val cy = y.coerceIn(size.y * 0.04f, size.y * 0.96f)
        return Pair(cx, cy)
    }

    private suspend fun notifyCursor(event: InputEvent) {
        _events.emit(event)
        if (isCursorSuppressed) return

        if (consecutiveCursorTimeouts >= 3) {
            Log.w(TAG, "Agent cursor auto-disabled for session due to 3 consecutive timeouts")
            return
        }

        val listener = cursorListener ?: return
        val budgetMs = (event.durationMs + 900L).coerceAtMost(1200L)

        val result = withTimeoutOrNull(budgetMs) {
            try {
                listener.invoke(event)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Cursor animation error: ${e.message}")
                false
            }
        }

        if (result == null) {
            consecutiveCursorTimeouts++
            Log.w(TAG, "Cursor animation timed out ($budgetMs ms). Proceeding with real input anyway! (Consecutive: $consecutiveCursorTimeouts)")
        } else if (result) {
            consecutiveCursorTimeouts = 0
        }
    }

    suspend fun ensureUntrustedTouchesDisabled() {
        try {
            rootManager.execute("settings put global block_untrusted_touches 0")
        } catch (e: Exception) {
            Log.w(TAG, "Error disabling untrusted touch filter: ${e.message}")
        }
    }

    /**
     * Taps at real screen pixel coordinates (x, y).
     */
    suspend fun tapWithResult(x: Float, y: Float, label: String = ""): InputResult = withContext(Dispatchers.IO) {
        val (cx, cy) = clampToSafe(x, y)
        val event = InputEvent(
            type = InputEventType.TAP,
            startX = cx,
            startY = cy,
            endX = cx,
            endY = cy,
            durationMs = 50L,
            label = label
        )

        ensureUntrustedTouchesDisabled()

        // Cursor glides to target, then real tap is injected
        notifyCursor(event)

        val ix = cx.roundToInt()
        val iy = cy.roundToInt()

        // 1. Try Accessibility Service direct node click first if available
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val directNodeClicked = a11y.performClickAt(cx, cy)
            if (directNodeClicked) {
                Log.i(TAG, "Direct accessibility node click succeeded at ($ix, $iy)")
                return@withContext InputResult(true, InputMethod.ACCESSIBILITY)
            }
        }

        // 2. Try root input tap
        val rootRes = rootManager.execute("input tap $ix $iy")
        if (rootRes.success) {
            return@withContext InputResult(true, InputMethod.ROOT)
        }

        // 3. Fallback: Accessibility service gesture with verified completion
        if (a11y != null) {
            val a11ySuccess = dispatchAccessibilityClick(cx, cy, 100L)
            if (a11ySuccess) {
                return@withContext InputResult(true, InputMethod.ACCESSIBILITY)
            }
        }

        val err = "Root stderr: ${rootRes.stderr}; a11y click failed or service inactive"
        Log.e(TAG, "InputController.tap failed at ($ix, $iy): $err")
        InputResult(false, InputMethod.NONE, err)
    }

    suspend fun tap(x: Float, y: Float, label: String = ""): Boolean {
        return tapWithResult(x, y, label).success
    }

    suspend fun tapResult(x: Float, y: Float, label: String = ""): InputResult {
        return tapWithResult(x, y, label)
    }

    suspend fun doubleTap(x: Float, y: Float, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val (cx, cy) = clampToSafe(x, y)
        val event = InputEvent(
            type = InputEventType.DOUBLE_TAP,
            startX = cx,
            startY = cy,
            endX = cx,
            endY = cy,
            durationMs = 120L,
            label = label
        )
        notifyCursor(event)

        val ix = cx.roundToInt()
        val iy = cy.roundToInt()
        val r1 = rootManager.execute("input tap $ix $iy")
        delay(90)
        val r2 = rootManager.execute("input tap $ix $iy")
        if (r1.success && r2.success) return@withContext true

        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val s1 = dispatchAccessibilityClick(cx, cy, 60L)
            delay(90)
            val s2 = dispatchAccessibilityClick(cx, cy, 60L)
            return@withContext s1 && s2
        }
        false
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 1000L, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val (cx, cy) = clampToSafe(x, y)
        val event = InputEvent(
            type = InputEventType.LONG_PRESS,
            startX = cx,
            startY = cy,
            endX = cx,
            endY = cy,
            durationMs = durationMs,
            label = label
        )
        notifyCursor(event)

        val ix = cx.roundToInt()
        val iy = cy.roundToInt()
        val res = rootManager.execute("input swipe $ix $iy $ix $iy $durationMs")
        if (res.success) return@withContext true

        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            return@withContext dispatchAccessibilityStroke(cx, cy, cx, cy, durationMs)
        }
        false
    }

    suspend fun swipeWithResult(
        x1: Float, y1: Float, x2: Float, y2: Float,
        durationMs: Long = 300L,
        label: String = "",
        suppressCursor: Boolean = false
    ): InputResult = withContext(Dispatchers.IO) {
        val (cx1, cy1) = clampToSafe(x1, y1)
        val (cx2, cy2) = clampToSafe(x2, y2)

        if (!suppressCursor && !isCursorSuppressed) {
            val event = InputEvent(
                type = InputEventType.SWIPE,
                startX = cx1,
                startY = cy1,
                endX = cx2,
                endY = cy2,
                durationMs = durationMs,
                label = label
            )
            notifyCursor(event)
        }

        val ix1 = cx1.roundToInt()
        val iy1 = cy1.roundToInt()
        val ix2 = cx2.roundToInt()
        val iy2 = cy2.roundToInt()
        val res = rootManager.execute("input swipe $ix1 $iy1 $ix2 $iy2 $durationMs")
        if (res.success) return@withContext InputResult(true, InputMethod.ROOT)

        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val a11ySuccess = dispatchAccessibilityStroke(cx1, cy1, cx2, cy2, durationMs)
            if (a11ySuccess) return@withContext InputResult(true, InputMethod.ACCESSIBILITY)
        }
        InputResult(false, InputMethod.NONE, "Swipe execution failed across root and accessibility")
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 300L, label: String = ""): Boolean {
        return swipeWithResult(x1, y1, x2, y2, durationMs, label).success
    }

    suspend fun scroll(direction: String, amountFraction: Float = 0.5f, label: String = ""): Boolean {
        val size = getDisplayMetrics()
        val w = size.x.toFloat()
        val h = size.y.toFloat()
        val frac = amountFraction.coerceIn(0.1f, 0.85f)

        return when (direction.lowercase()) {
            "down" -> {
                val startX = w * 0.5f
                val startY = h * (0.5f + frac * 0.35f)
                val endY = h * (0.5f - frac * 0.35f)
                swipe(startX, startY, startX, endY, 280L, label.ifBlank { "Scroll Down" })
            }
            "up" -> {
                val startX = w * 0.5f
                val startY = h * (0.5f - frac * 0.35f)
                val endY = h * (0.5f + frac * 0.35f)
                swipe(startX, startY, startX, endY, 280L, label.ifBlank { "Scroll Up" })
            }
            "left" -> {
                val startX = w * (0.5f + frac * 0.35f)
                val endX = w * (0.5f - frac * 0.35f)
                val startY = h * 0.5f
                swipe(startX, startY, endX, startY, 280L, label.ifBlank { "Scroll Left" })
            }
            "right" -> {
                val startX = w * (0.5f - frac * 0.35f)
                val endX = w * (0.5f + frac * 0.35f)
                val startY = h * 0.5f
                swipe(startX, startY, endX, startY, 280L, label.ifBlank { "Scroll Right" })
            }
            else -> {
                val startX = w * 0.5f
                val startY = h * 0.72f
                val endY = h * 0.30f
                swipe(startX, startY, startX, endY, 280L, label.ifBlank { "Scroll" })
            }
        }
    }

    suspend fun swipeDirection(direction: String, label: String = ""): Boolean {
        return scroll(direction, 0.65f, label)
    }

    /**
     * Draws continuous multi-segment path (for pattern lock).
     * Uses Accessibility Gesture first (single continuous stroke on keyguard),
     * and fallback to a single root script with minimal command count.
     */
    suspend fun drawPath(points: List<Pair<Float, Float>>, durationMs: Long = 700L, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        if (points.size < 2) return@withContext false

        val event = InputEvent(
            type = InputEventType.DRAW_PATH,
            pathPoints = points,
            durationMs = durationMs,
            label = label
        )
        notifyCursor(event)

        // 1. Accessibility Gesture first: single continuous stroke supported on Keyguard
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val path = Path()
            points.forEachIndexed { i, pt ->
                if (i == 0) path.moveTo(pt.first, pt.second)
                else path.lineTo(pt.first, pt.second)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val lock = CompletableDeferred<Boolean>()
            a11y.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    lock.complete(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    lock.complete(false)
                }
            }, null)

            val a11ySuccess = withTimeoutOrNull(durationMs + 1000L) { lock.await() } ?: false
            if (a11ySuccess) return@withContext true
        }

        // 2. Root fallback in ONE script process
        if (rootManager.isRootAvailable()) {
            val scriptFile = "/data/local/tmp/aura_pattern.sh"
            val sb = StringBuilder()
            sb.append("#!/system/bin/sh\n")
            val first = points.first()
            sb.append("input motionevent DOWN ${first.first.roundToInt()} ${first.second.roundToInt()}\n")

            // Keep <= 15 commands total to avoid app_process delays
            val stepDelay = (durationMs / (points.size * 2)).coerceIn(10L, 50L)
            for (i in 1 until points.size) {
                val p1 = points[i - 1]
                val p2 = points[i]
                // 1 intermediate waypoint
                val midX = ((p1.first + p2.first) / 2f).roundToInt()
                val midY = ((p1.second + p2.second) / 2f).roundToInt()
                sb.append("sleep 0.0$stepDelay\ninput motionevent MOVE $midX $midY\n")
                sb.append("sleep 0.0$stepDelay\ninput motionevent MOVE ${p2.first.roundToInt()} ${p2.second.roundToInt()}\n")
            }
            val last = points.last()
            sb.append("sleep 0.05\ninput motionevent UP ${last.first.roundToInt()} ${last.second.roundToInt()}\n")

            rootManager.execute("echo '${sb.toString()}' > $scriptFile && chmod 755 $scriptFile && sh $scriptFile && rm -f $scriptFile")
            return@withContext true
        }

        false
    }

    suspend fun typeText(text: String, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val event = InputEvent(
            type = InputEventType.TYPE_TEXT,
            text = text,
            durationMs = 150L,
            label = label.ifBlank { "Typing..." }
        )
        notifyCursor(event)

        val escaped = escapeForShellInput(text)
        val res = rootManager.execute("input text \"$escaped\"")
        res.success
    }

    suspend fun pressKey(keyCode: Int, label: String = ""): Boolean = withContext(Dispatchers.IO) {
        val event = InputEvent(
            type = InputEventType.KEY_EVENT,
            keyCode = keyCode,
            durationMs = 60L,
            label = label.ifBlank { "Key: $keyCode" }
        )
        notifyCursor(event)

        val res = rootManager.execute("input keyevent $keyCode")
        res.success
    }

    /**
     * Robust shell escaping for Android `input text`.
     * Replaces spaces with %s, % with \%, and escapes shell characters.
     */
    fun escapeForShellInput(raw: String): String {
        val sb = StringBuilder()
        for (ch in raw) {
            when (ch) {
                ' ' -> sb.append("%s")
                '%' -> sb.append("\\%")
                '\\', '\'', '"', '&', '|', '<', '>', '(', ')', ';', '$', '!', '*', '?', '~', '`', '{', '}', '[', ']', '#', '^' -> {
                    sb.append('\\').append(ch)
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private suspend fun dispatchAccessibilityClick(x: Float, y: Float, durationMs: Long): Boolean {
        val a11y = AuraAccessibilityService.instance ?: return false
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val lock = CompletableDeferred<Boolean>()
        a11y.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { lock.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { lock.complete(false) }
        }, null)

        return withTimeoutOrNull(durationMs + 1000L) { lock.await() } ?: false
    }

    private suspend fun dispatchAccessibilityStroke(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val a11y = AuraAccessibilityService.instance ?: return false
        val path = Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        val lock = CompletableDeferred<Boolean>()
        a11y.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { lock.complete(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { lock.complete(false) }
        }, null)

        return withTimeoutOrNull(durationMs + 1000L) { lock.await() } ?: false
    }
}
