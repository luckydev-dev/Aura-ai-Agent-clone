package com.example.ui.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.example.core.input.InputController
import com.example.core.input.InputEvent
import com.example.core.input.InputEventType
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class CursorStyle {
    ARROW,
    DOT,
    HAND
}

enum class CursorSpeed(val durationMultiplier: Float) {
    INSTANT(0f),
    FAST(0.5f),
    NORMAL(1.0f),
    SLOW(1.5f)
}

/**
 * High-performance agent mouse pointer overlay.
 * Uses an on-demand transient window architecture that attaches strictly during
 * cursor movements / taps and detaches when idle.
 * Enforces OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA (0.79f) so it NEVER blocks
 * or drops user touches on Android 12+ (API 31+).
 */
class AgentCursorOverlay(
    private val context: Context,
    private val inputController: InputController
) {
    companion object {
        private const val TAG = "Aura"
    }

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var activeWindowManager: WindowManager? = null
    private var cursorView: AgentCursorView? = null

    @Volatile
    var isAttached = false
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    var isEnabled: Boolean = true
    var cursorStyle: CursorStyle = CursorStyle.ARROW
    var cursorSpeed: CursorSpeed = CursorSpeed.NORMAL
    var cursorSizeDp: Float = 26f
    var showTrail: Boolean = true
    var showRipple: Boolean = true
    var showActionLabel: Boolean = true
    var showDuringUnlock: Boolean = false

    init {
        // Register cursor listener with InputController
        inputController.cursorListener = { event ->
            handleInputEvent(event)
        }
    }

    @Synchronized
    fun ensureAttached(): Boolean {
        if (isAttached && cursorView != null) return true
        if (!Settings.canDrawOverlays(context) && !com.example.service.AuraAccessibilityService.isRunning()) {
            Log.w(TAG, "Cannot attach AgentCursorOverlay: neither SYSTEM_ALERT_WINDOW nor Accessibility active")
            return false
        }

        return try {
            val (wm, windowType) = OverlayWindowDefaults.getAppropriateWindowManager(context)
            activeWindowManager = wm

            val params = OverlayWindowDefaults.createLayoutParams(
                title = "AgentCursor",
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
                alpha = OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA,
                isTouchable = false
            ).apply {
                type = windowType
            }

            val view = AgentCursorView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                onIdleDismissed = {
                    scope.launch(Dispatchers.Main) {
                        detachOverlay()
                    }
                }
            }

            wm.addView(view, params)
            cursorView = view
            isAttached = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach AgentCursorOverlay: ${e.message}")
            false
        }
    }

    @Synchronized
    fun detachOverlay() {
        OverlayWindowDefaults.unregisterOverlay("AgentCursor")
        val view = cursorView
        val wm = activeWindowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        if (isAttached && view != null) {
            try {
                wm.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing AgentCursorOverlay: ${e.message}")
            } finally {
                cursorView = null
                isAttached = false
                activeWindowManager = null
            }
        }
    }

    fun setVisibility(visibility: Int) {
        cursorView?.visibility = visibility
    }

    fun hideTemporarily() {
        detachOverlay()
    }

    fun show(): Boolean {
        isEnabled = true
        return true
    }

    fun hide() {
        detachOverlay()
    }

    private suspend fun handleInputEvent(event: InputEvent) {
        if (!isEnabled || inputController.isCursorSuppressed) return

        if (!powerManager.isInteractive) {
            // Screen off / dozing; never stall the input pipeline
            return
        }

        if (cursorSpeed == CursorSpeed.INSTANT) {
            withContext(Dispatchers.Main) {
                if (ensureAttached()) {
                    cursorView?.flashAt(event.startX, event.startY, event.label)
                }
            }
            return
        }

        withContext(Dispatchers.Main) {
            if (ensureAttached()) {
                cursorView?.let { v ->
                    v.cursorStyle = cursorStyle
                    v.cursorSizeDp = cursorSizeDp
                    v.showTrail = showTrail
                    v.showRipple = showRipple
                    v.showActionLabel = showActionLabel
                    v.executeAnimatedEvent(event, cursorSpeed.durationMultiplier)
                }
            }
        }
    }

    /**
     * Instantly plays a live cursor preview animation across the screen.
     */
    fun previewAnimation(
        style: CursorStyle? = null,
        speed: CursorSpeed? = null,
        sizeDp: Float? = null
    ) {
        if (!Settings.canDrawOverlays(context)) return

        val targetStyle = style ?: cursorStyle
        val targetSpeed = speed ?: cursorSpeed
        val targetSize = sizeDp ?: cursorSizeDp

        scope.launch(Dispatchers.Main) {
            if (!ensureAttached()) return@launch

            cursorView?.let { v ->
                v.cursorStyle = targetStyle
                v.cursorSizeDp = targetSize
                v.showTrail = showTrail
                v.showRipple = showRipple
                v.showActionLabel = showActionLabel

                val metrics = inputController.getDisplayMetrics()
                val cx = metrics.x * 0.5f
                val cy = metrics.y * 0.42f

                val previewEvent = InputEvent(
                    type = InputEventType.TAP,
                    startX = cx - 110f,
                    startY = cy - 70f,
                    endX = cx + 80f,
                    endY = cy + 40f,
                    durationMs = 90L,
                    label = when (targetStyle) {
                        CursorStyle.ARROW -> "Arrow Pointer"
                        CursorStyle.DOT -> "Touch Dot"
                        CursorStyle.HAND -> "Hand Pointer"
                    }
                )
                v.executeAnimatedEvent(previewEvent, targetSpeed.durationMultiplier)
            }
        }
    }

    /**
     * Displays calibrated pattern dot markers on screen for durationMs
     * so user can visually verify alignment over real lock screen dots in the wizard.
     */
    fun showCalibrationMarkers(points: List<Pair<Float, Float>>, durationMs: Long = 3000L) {
        if (!Settings.canDrawOverlays(context)) return
        scope.launch(Dispatchers.Main) {
            if (!ensureAttached()) return@launch
            cursorView?.displayCalibrationMarkers(points, durationMs)
        }
    }

    fun testDemo() {
        previewAnimation()
    }

    fun destroy() {
        scope.cancel()
        detachOverlay()
    }
}

/**
 * Custom View that renders the mouse pointer, motion trails, tap ripples,
 * long-press progress rings, action chips, and calibration markers.
 */
class AgentCursorView(context: Context) : View(context) {

    private val density = context.resources.displayMetrics.density
    var cursorStyle: CursorStyle = CursorStyle.ARROW
    var cursorSizeDp: Float = 26f
    var showTrail: Boolean = true
    var showRipple: Boolean = true
    var showActionLabel: Boolean = true

    var onIdleDismissed: (() -> Unit)? = null

    // Track active animators for strict cancellation safety
    private val activeAnimators = CopyOnWriteArrayList<Animator>()

    // Current pointer position & scale
    private var posX = 0f
    private var posY = 0f
    private var pointerScale = 1.0f
    private var pointerAlpha = 0f

    // Motion & Gestures
    private var rippleRadius = 0f
    private var rippleAlpha = 0f
    private var longPressProgress = 0f
    private var currentLabel: String = ""
    private var labelAlpha = 0f

    // Trail points for swipe / drag
    private val trailPoints = mutableListOf<PointF>()
    private val maxTrail = 20

    // Calibration markers
    private val calibrationPoints = mutableListOf<Pair<Float, Float>>()
    private var calibrationAlpha = 0f

    // Visual brightness multiplier to compensate for window alpha = 0.79f
    private val boost = 1.25f

    // Paints
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0C0E14")
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val dotGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(7f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val dotInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val dotBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3.5f * density
        maskFilter = BlurMaskFilter(4f * density, BlurMaskFilter.Blur.NORMAL)
    }

    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }

    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 4.5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val chipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(240, 16, 18, 28)
        style = Paint.Style.FILL
    }

    private val chipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }

    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 12f * density
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
    }

    private val calibDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
    }

    private val calibFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 0, 229, 255)
        style = Paint.Style.FILL
    }

    private val calibTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f * density
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }

    private val arrowPath = Path()
    private val handPath = Path()
    private var idleFadeJob: Job? = null
    private val viewScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        val metrics = context.resources.displayMetrics
        posX = metrics.widthPixels / 2f
        posY = metrics.heightPixels / 2f
        buildArrowPath()
        buildHandPath()
    }

    private var restingPulseProgress = 0f
    private var pulseAnimator: ValueAnimator? = null

    private fun startRestingPulseAnimation() {
        stopRestingPulseAnimation()
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                restingPulseProgress = f
                pointerScale = 0.96f + 0.10f * f
                invalidate()
            }
        }
        activeAnimators.add(pulseAnimator!!)
        pulseAnimator?.start()
    }

    private fun stopRestingPulseAnimation() {
        pulseAnimator?.cancel()
        pulseAnimator?.let { activeAnimators.remove(it) }
        pulseAnimator = null
        restingPulseProgress = 0f
        pointerScale = 1.0f
    }

    private fun cancelAllAnimators() {
        idleFadeJob?.cancel()
        idleFadeJob = null
        stopRestingPulseAnimation()
        for (anim in activeAnimators) {
            try {
                anim.removeAllListeners()
                anim.cancel()
            } catch (e: Exception) {
                // ignore
            }
        }
        activeAnimators.clear()
        pointerAlpha = 0f
        labelAlpha = 0f
        onIdleDismissed?.invoke()
    }

    private fun buildArrowPath() {
        val s = cursorSizeDp * density
        arrowPath.reset()
        arrowPath.moveTo(0f, 0f)
        arrowPath.lineTo(0f, s)
        arrowPath.lineTo(s * 0.28f, s * 0.75f)
        arrowPath.lineTo(s * 0.58f, s * 1.25f)
        arrowPath.lineTo(s * 0.82f, s * 1.10f)
        arrowPath.lineTo(s * 0.52f, s * 0.62f)
        arrowPath.lineTo(s * 0.88f, s * 0.62f)
        arrowPath.close()
    }

    private fun buildHandPath() {
        val s = cursorSizeDp * density * 0.9f
        handPath.reset()
        handPath.moveTo(0f, 0f)
        handPath.lineTo(s * 0.22f, 0f)
        handPath.lineTo(s * 0.22f, s * 0.55f)
        handPath.lineTo(s * 0.42f, s * 0.55f)
        handPath.lineTo(s * 0.42f, s * 0.95f)
        handPath.lineTo(s * 0.62f, s * 0.95f)
        handPath.lineTo(s * 0.62f, s * 1.30f)
        handPath.lineTo(-s * 0.15f, s * 1.30f)
        handPath.lineTo(-s * 0.35f, s * 0.80f)
        handPath.lineTo(-s * 0.15f, s * 0.50f)
        handPath.lineTo(0f, s * 0.50f)
        handPath.close()
    }

    fun flashAt(x: Float, y: Float, label: String) {
        posX = x
        posY = y
        currentLabel = label
        pointerAlpha = 1f
        labelAlpha = if (label.isNotBlank()) 1f else 0f
        rippleRadius = 24f * density
        rippleAlpha = 0.85f
        invalidate()
        scheduleIdleFade(400L)
    }

    fun displayCalibrationMarkers(points: List<Pair<Float, Float>>, durationMs: Long) {
        calibrationPoints.clear()
        calibrationPoints.addAll(points)
        calibrationAlpha = 1.0f
        pointerAlpha = 0f
        invalidate()

        viewScope.launch {
            delay(durationMs)
            ValueAnimator.ofFloat(1.0f, 0f).apply {
                duration = 300L
                addUpdateListener {
                    calibrationAlpha = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        calibrationPoints.clear()
                        onIdleDismissed?.invoke()
                    }
                })
                start()
            }
        }
    }

    suspend fun executeAnimatedEvent(event: InputEvent, speedMult: Float) = suspendCancellableCoroutine<Unit> { cont ->
        cancelAllAnimators()
        buildArrowPath()
        buildHandPath()

        cont.invokeOnCancellation {
            cancelAllAnimators()
        }

        val startX = if (pointerAlpha > 0.05f) posX else event.startX
        val startY = if (pointerAlpha > 0.05f) posY else event.startY
        val targetX = event.startX
        val targetY = event.startY

        val dist = hypot(targetX - startX, targetY - startY)
        val baseDuration = (140L + (dist * 0.22f).toLong()).coerceIn(140L, 380L)
        val glideDuration = (baseDuration * speedMult).toLong()

        currentLabel = event.label
        labelAlpha = if (event.label.isNotBlank()) 1f else 0f
        pointerAlpha = 1f
        trailPoints.clear()

        val moveAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = glideDuration
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val fraction = it.animatedValue as Float
                posX = startX + (targetX - startX) * fraction
                posY = startY + (targetY - startY) * fraction
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimators.remove(animation)
                    when (event.type) {
                        InputEventType.TAP, InputEventType.DOUBLE_TAP -> {
                            // Resume continuation IMMEDIATELY so real tap is injected concurrently with visual ripple!
                            if (cont.isActive) cont.resume(Unit) {}
                            playTapRippleVisual()
                        }
                        InputEventType.SWIPE -> {
                            // Resume immediately so real swipe runs concurrently with cursor drag!
                            if (cont.isActive) cont.resume(Unit) {}
                            playSwipeVisual(event.startX, event.startY, event.endX, event.endY, event.durationMs)
                        }
                        InputEventType.LONG_PRESS -> {
                            playLongPressVisual(event.durationMs, cont)
                        }
                        InputEventType.DRAW_PATH -> {
                            if (cont.isActive) cont.resume(Unit) {}
                            playPathVisual(event.pathPoints, event.durationMs)
                        }
                        else -> {
                            if (cont.isActive) cont.resume(Unit) {}
                            scheduleIdleFade(500L)
                        }
                    }
                }
            })
        }
        activeAnimators.add(moveAnim)
        moveAnim.start()
    }

    private fun playTapRippleVisual() {
        stopRestingPulseAnimation()
        pointerScale = 0.82f
        rippleRadius = 6f * density
        rippleAlpha = 1.0f
        invalidate()

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180L
            addUpdateListener {
                val f = it.animatedValue as Float
                pointerScale = 0.82f + 0.18f * f
                rippleRadius = (6f + 26f * f) * density
                rippleAlpha = 1.0f - f
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimators.remove(animation)
                    startRestingPulseAnimation()
                    scheduleIdleFade(3000L) // 3 seconds resting time (2-4s as requested)
                }
            })
        }
        activeAnimators.add(anim)
        anim.start()
    }

    private fun playLongPressVisual(durationMs: Long, continuation: CancellableContinuation<Unit>) {
        stopRestingPulseAnimation()
        pointerScale = 0.85f
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            addUpdateListener {
                longPressProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimators.remove(animation)
                    pointerScale = 1.0f
                    longPressProgress = 0f
                    if (continuation.isActive) continuation.resume(Unit) {}
                    startRestingPulseAnimation()
                    scheduleIdleFade(2800L)
                }
            })
        }
        activeAnimators.add(anim)
        anim.start()
    }

    private fun playSwipeVisual(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        stopRestingPulseAnimation()
        pointerScale = 0.90f
        trailPoints.clear()
        trailPoints.add(PointF(x1, y1))

        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs.coerceIn(160L, 700L)
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                posX = x1 + (x2 - x1) * f
                posY = y1 + (y2 - y1) * f
                if (showTrail) {
                    trailPoints.add(PointF(posX, posY))
                    if (trailPoints.size > maxTrail) trailPoints.removeAt(0)
                }
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    activeAnimators.remove(animation)
                    pointerScale = 1.0f
                    trailPoints.clear()
                    startRestingPulseAnimation()
                    scheduleIdleFade(2800L)
                }
            })
        }
        activeAnimators.add(anim)
        anim.start()
    }

    private fun playPathVisual(points: List<Pair<Float, Float>>, durationMs: Long) {
        if (points.size < 2) {
            scheduleIdleFade(2800L)
            return
        }

        stopRestingPulseAnimation()
        pointerScale = 0.92f
        trailPoints.clear()
        val totalSegments = points.size - 1
        val segmentDuration = (durationMs / totalSegments).coerceAtLeast(30L)

        viewScope.launch {
            for (i in 0 until totalSegments) {
                val p1 = points[i]
                val p2 = points[i + 1]
                val anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = segmentDuration
                    addUpdateListener {
                        val f = it.animatedValue as Float
                        posX = p1.first + (p2.first - p1.first) * f
                        posY = p1.second + (p2.second - p1.second) * f
                        if (showTrail) {
                            trailPoints.add(PointF(posX, posY))
                            if (trailPoints.size > maxTrail) trailPoints.removeAt(0)
                        }
                        invalidate()
                    }
                }
                anim.start()
                delay(segmentDuration)
            }
            pointerScale = 1.0f
            trailPoints.clear()
            startRestingPulseAnimation()
            scheduleIdleFade(2800L)
        }
    }

    private fun scheduleIdleFade(delayMs: Long) {
        idleFadeJob?.cancel()
        idleFadeJob = viewScope.launch {
            val adjustedDelay = delayMs.coerceIn(2000L, 4000L)
            delay(adjustedDelay)
            stopRestingPulseAnimation()
            val fadeAnim = ValueAnimator.ofFloat(pointerAlpha, 0f).apply {
                duration = 250L
                addUpdateListener {
                    val a = it.animatedValue as Float
                    pointerAlpha = a
                    labelAlpha = a
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        activeAnimators.remove(animation)
                        onIdleDismissed?.invoke()
                    }
                })
            }
            activeAnimators.add(fadeAnim)
            fadeAnim.start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw calibration markers if active
        if (calibrationAlpha > 0.01f && calibrationPoints.isNotEmpty()) {
            calibDotPaint.alpha = (calibrationAlpha * 255).roundToInt()
            calibFillPaint.alpha = (calibrationAlpha * 120).roundToInt()
            calibTextPaint.alpha = (calibrationAlpha * 255).roundToInt()

            calibrationPoints.forEachIndexed { idx, pt ->
                val r = 22f * density
                canvas.drawCircle(pt.first, pt.second, r, calibFillPaint)
                canvas.drawCircle(pt.first, pt.second, r, calibDotPaint)
                canvas.drawText("${idx + 1}", pt.first, pt.second + 4f * density, calibTextPaint)
            }
        }

        if (pointerAlpha <= 0.01f) return

        // 1. Draw trail
        if (showTrail && trailPoints.size > 1) {
            val path = Path()
            path.moveTo(trailPoints.first().x, trailPoints.first().y)
            for (i in 1 until trailPoints.size) {
                path.lineTo(trailPoints[i].x, trailPoints[i].y)
            }
            trailPaint.alpha = ((pointerAlpha * 200 * boost).coerceAtMost(255f)).roundToInt()
            canvas.drawPath(path, trailPaint)
        }

        // 2. Draw tap ripple
        if (showRipple && rippleAlpha > 0.01f) {
            ripplePaint.alpha = ((rippleAlpha * 255 * boost).coerceAtMost(255f)).roundToInt()
            canvas.drawCircle(posX, posY, rippleRadius, ripplePaint)
        }

        // 2b. Draw breathing pulse aura ring when resting
        if (restingPulseProgress > 0.01f) {
            val auraRadius = (16f + restingPulseProgress * 12f) * density
            val auraAlpha = ((pointerAlpha * (1f - restingPulseProgress) * 160 * boost).coerceIn(0f, 255f)).roundToInt()
            ripplePaint.alpha = auraAlpha
            canvas.drawCircle(posX, posY, auraRadius, ripplePaint)
        }

        // 3. Draw long press progress ring
        if (longPressProgress > 0.01f) {
            val ringRadius = 20f * density
            val oval = RectF(posX - ringRadius, posY - ringRadius, posX + ringRadius, posY + ringRadius)
            glowPaint.alpha = 255
            canvas.drawArc(oval, -90f, longPressProgress * 360f, false, glowPaint)
        }

        // 4. Draw pointer icon
        canvas.save()
        canvas.translate(posX, posY)
        canvas.scale(pointerScale, pointerScale)

        val alpha255 = ((pointerAlpha * 255 * boost).coerceAtMost(255f)).roundToInt()
        arrowFillPaint.alpha = alpha255
        arrowStrokePaint.alpha = alpha255

        when (cursorStyle) {
            CursorStyle.ARROW -> {
                canvas.drawPath(arrowPath, arrowFillPaint)
                canvas.drawPath(arrowPath, arrowStrokePaint)
            }
            CursorStyle.DOT -> {
                val r = (cursorSizeDp * 0.45f) * density
                dotGlowPaint.alpha = ((pointerAlpha * 200 * boost).coerceAtMost(255f)).roundToInt()
                dotInnerPaint.alpha = alpha255
                dotBorderPaint.alpha = alpha255

                canvas.drawCircle(0f, 0f, r + 3f * density, dotGlowPaint)
                canvas.drawCircle(0f, 0f, r, dotInnerPaint)
                canvas.drawCircle(0f, 0f, r, dotBorderPaint)
            }
            CursorStyle.HAND -> {
                canvas.drawPath(handPath, arrowFillPaint)
                canvas.drawPath(handPath, arrowStrokePaint)
            }
        }
        canvas.restore()

        // 5. Draw Action Label Chip
        if (showActionLabel && currentLabel.isNotBlank() && labelAlpha > 0.01f) {
            val text = currentLabel
            val textWidth = labelTextPaint.measureText(text)
            val chipPadX = 10f * density
            val chipPadY = 5f * density
            val chipH = 22f * density
            val chipW = textWidth + chipPadX * 2

            val chipLeft = posX + 16f * density
            val chipTop = posY + 6f * density
            val chipRect = RectF(chipLeft, chipTop, chipLeft + chipW, chipTop + chipH)

            chipBgPaint.alpha = ((labelAlpha * 240 * boost).coerceAtMost(255f)).roundToInt()
            chipBorderPaint.alpha = ((labelAlpha * 180 * boost).coerceAtMost(255f)).roundToInt()
            labelTextPaint.alpha = ((labelAlpha * 255 * boost).coerceAtMost(255f)).roundToInt()

            canvas.drawRoundRect(chipRect, 6f * density, 6f * density, chipBgPaint)
            canvas.drawRoundRect(chipRect, 6f * density, 6f * density, chipBorderPaint)
            canvas.drawText(text, chipLeft + chipPadX, chipTop + chipH - chipPadY - 1f * density, labelTextPaint)
        }
    }
}
