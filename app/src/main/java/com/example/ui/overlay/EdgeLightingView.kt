package com.example.ui.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator

enum class EdgeLightingState {
    HIDDEN,
    WAKE_LISTENING,
    CONNECTING,
    RECONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    ACTING,
    ERROR,
    MUTED
}

/**
 * Original Aura glowing edge border ring.
 * Draws rotating SweepGradient along the screen border with a blurred wide glow stroke
 * and a thinner core stroke, dynamic amplitudes, per-state color palettes, and hardware acceleration.
 */
class EdgeLightingView(context: Context) : View(context) {

    private var lightingState = EdgeLightingState.HIDDEN
    private var micAmplitude = 0f
    private var speakerAmplitude = 0f

    var strokeThicknessDp: Float = 8f
        set(value) {
            field = value.coerceIn(4f, 16f)
            updatePaintStroke()
            recomputeBorderPath()
            invalidate()
        }

    var intensity: Float = 1.0f
        set(value) {
            field = value.coerceIn(0.2f, 2.0f)
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private var cornerRadiusPx = 40f * density

    private val borderPath = Path()
    private val gradientMatrix = Matrix()
    private var cachedSweepGradient: SweepGradient? = null
    private var cachedGradientColors: IntArray? = null

    // Paints
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private var flowPhase = 0f
    private var phaseAnimator: ValueAnimator? = null
    private var globalAlpha = 0f
    private var alphaAnimator: ValueAnimator? = null

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        updatePaintStroke()
        startPhaseAnimation()
    }

    private fun updatePaintStroke() {
        val strokePx = strokeThicknessDp * density
        corePaint.strokeWidth = strokePx
        glowPaint.strokeWidth = strokePx * 2.2f
        glowPaint.maskFilter = BlurMaskFilter(strokePx * 1.5f, BlurMaskFilter.Blur.NORMAL)
    }

    private fun startPhaseAnimation() {
        val durationScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1.0f
        )
        if (durationScale <= 0.001f) {
            flowPhase = 0.5f
            invalidate()
            return
        }

        phaseAnimator?.cancel()
        phaseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                flowPhase = it.animatedValue as Float
                if (lightingState != EdgeLightingState.HIDDEN) {
                    invalidate()
                }
            }
            start()
        }
    }

    fun setState(state: EdgeLightingState) {
        if (lightingState == state) return
        val wasHidden = lightingState == EdgeLightingState.HIDDEN
        lightingState = state

        if (state == EdgeLightingState.HIDDEN) {
            animateAlphaTo(0f, 400L)
        } else {
            if (wasHidden) {
                animateAlphaTo(1f, 250L)
            }
            invalidate()
        }
    }

    fun updateAmplitudes(mic: Float, speaker: Float) {
        micAmplitude = mic
        speakerAmplitude = speaker
        if (lightingState == EdgeLightingState.LISTENING || lightingState == EdgeLightingState.SPEAKING) {
            invalidate()
        }
    }

    private fun animateAlphaTo(target: Float, durationMs: Long) {
        alphaAnimator?.cancel()
        alphaAnimator = ValueAnimator.ofFloat(globalAlpha, target).apply {
            duration = durationMs
            addUpdateListener {
                globalAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        readCornerRadius(rootWindowInsets)
        recomputeBorderPath()
    }

    override fun onApplyWindowInsets(insets: WindowInsets?): WindowInsets {
        readCornerRadius(insets)
        recomputeBorderPath()
        return super.onApplyWindowInsets(insets)
    }

    private fun readCornerRadius(insets: WindowInsets?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && insets != null) {
            val corner = insets.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
            if (corner != null && corner.radius > 0) {
                cornerRadiusPx = corner.radius.toFloat()
            }
        }
    }

    private fun recomputeBorderPath() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val halfStroke = (strokeThicknessDp * density) / 2f
        borderPath.reset()
        val rectF = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        borderPath.addRoundRect(rectF, cornerRadiusPx, cornerRadiusPx, Path.Direction.CW)
    }

    @SuppressLint("DrawAllocation")
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeBorderPath()
        cachedSweepGradient = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (globalAlpha <= 0.01f || lightingState == EdgeLightingState.HIDDEN) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Select color spectrum based on state
        val (colors, speedMult) = when (lightingState) {
            EdgeLightingState.CONNECTING, EdgeLightingState.RECONNECTING -> Pair(
                intArrayOf(Color.parseColor("#FFB347"), Color.parseColor("#4A90E2"), Color.parseColor("#FFB347")),
                0.7f
            )
            EdgeLightingState.LISTENING -> {
                val blueViolet = Color.parseColor("#7F5AF0")
                val cyan = Color.parseColor("#2CB67D")
                Pair(intArrayOf(blueViolet, cyan, blueViolet), 1.0f + micAmplitude * 2f)
            }
            EdgeLightingState.THINKING -> Pair(
                intArrayOf(Color.parseColor("#2CB67D"), Color.parseColor("#7F5AF0"), Color.parseColor("#FF8E53")),
                1.8f
            )
            EdgeLightingState.SPEAKING -> {
                val amber = Color.parseColor("#FFB347")
                val coral = Color.parseColor("#FF6B6B")
                Pair(intArrayOf(amber, coral, amber), 1.2f + speakerAmplitude * 2f)
            }
            EdgeLightingState.ACTING -> Pair(
                intArrayOf(Color.parseColor("#FFD166"), Color.parseColor("#EF476F"), Color.parseColor("#06D6A0")),
                2.5f
            )
            EdgeLightingState.ERROR -> Pair(
                intArrayOf(Color.parseColor("#FF3B30"), Color.parseColor("#D90429"), Color.parseColor("#FF3B30")),
                0.5f
            )
            EdgeLightingState.MUTED -> Pair(
                intArrayOf(Color.parseColor("#E0A96D"), Color.parseColor("#776B5D"), Color.parseColor("#E0A96D")),
                0.5f
            )
            else -> Pair(
                intArrayOf(Color.parseColor("#FFB347"), Color.parseColor("#FF6B6B"), Color.parseColor("#7F5AF0")),
                1.0f
            )
        }

        // Reuse SweepGradient if colors match
        if (cachedSweepGradient == null || cachedGradientColors?.contentEquals(colors) != true) {
            cachedSweepGradient = SweepGradient(w / 2f, h / 2f, colors, null)
            cachedGradientColors = colors
        }

        gradientMatrix.reset()
        gradientMatrix.postRotate((flowPhase * 360f * speedMult) % 360f, w / 2f, h / 2f)
        cachedSweepGradient?.setLocalMatrix(gradientMatrix)

        val dynamicAlpha = ((globalAlpha * intensity).coerceIn(0.2f, 1f) * 255).toInt()

        glowPaint.shader = cachedSweepGradient
        glowPaint.alpha = (dynamicAlpha * 0.65f).toInt()
        canvas.drawPath(borderPath, glowPaint)

        corePaint.shader = cachedSweepGradient
        corePaint.alpha = dynamicAlpha
        canvas.drawPath(borderPath, corePaint)
    }

    fun release() {
        phaseAnimator?.cancel()
        phaseAnimator = null
        alphaAnimator?.cancel()
        alphaAnimator = null
    }
}
