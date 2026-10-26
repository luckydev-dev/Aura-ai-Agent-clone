package com.example.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.core.live.CaptionState
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class FloatingCaptionOverlay(
    private val context: Context,
    private val onStopRequested: (() -> Unit)? = null
) {
    companion object {
        private const val TAG = "Aura"
        private const val WINDOW_NAME = "FloatingCaption"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var rootView: FrameLayout? = null
    private var containerLayout: LinearLayout? = null
    private var userTextView: TextView? = null
    private var auraTextView: TextView? = null
    private var dragHandle: View? = null

    private var isAttached = false
    private var isVisible = false
    private val density = context.resources.displayMetrics.density

    private var autoHideJob: Job? = null
    private var restoreDodgeJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var currentCaption = CaptionState()
    private var preferredPosition = "bottom"
    private var isDodged = false
    private var autoHideSeconds = 5
    private var interactiveHandleEnabled = false
    private var customAlphaMultiplier = 0.9f

    private var windowParams: WindowManager.LayoutParams? = null

    init {
        // Load preferences asynchronously
        scope.launch {
            try {
                val prefs = AuraPreferences(context)
                preferredPosition = prefs.captionPositionFlow.first()
                autoHideSeconds = prefs.captionAutoHideSecondsFlow.first()
                interactiveHandleEnabled = prefs.interactiveCaptionsHandleFlow.first()
                customAlphaMultiplier = prefs.captionTransparencyFlow.first()
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading caption preferences: ${e.message}")
            }
        }
    }

    fun show(): Boolean {
        var result = false
        OverlayGuard.run("floating_captions", context) {
            if (isAttached) {
                rootView?.visibility = View.VISIBLE
                fadeIn()
                result = true
                return@run
            }
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show FloatingCaptionOverlay: SYSTEM_ALERT_WINDOW not granted")
                result = false
                return@run
            }

            val screenW = context.resources.displayMetrics.widthPixels
            val maxW = (screenW * 0.90f).toInt().coerceAtMost((480 * density).toInt())

            val touchable = interactiveHandleEnabled
            val params = OverlayWindowDefaults.createLayoutParams(
                name = WINDOW_NAME,
                width = maxW,
                height = WindowManager.LayoutParams.WRAP_CONTENT,
                touchable = touchable
            ).apply {
                gravity = if (preferredPosition == "top") {
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL
                } else {
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                }
                y = (48 * density).toInt()
            }

            windowParams = params
            createView(maxW)

            try {
                windowManager.addView(rootView, params)
                OverlayWindowDefaults.registerWindow(WINDOW_NAME, params.alpha)
                isAttached = true
                isVisible = true
                rootView?.alpha = 0f
                fadeIn()
                Log.i(TAG, "FloatingCaptionOverlay attached (alpha=${params.alpha}, touchable=$touchable)")
                result = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach FloatingCaptionOverlay", e)
                result = false
            }
        }
        return result
    }

    private fun createView(maxW: Int) {
        val root = FrameLayout(context).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val backgroundDrawable = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 16f * density
            val baseAlpha = (230 * customAlphaMultiplier).toInt().coerceIn(40, 255)
            setColor(Color.argb(baseAlpha, 18, 18, 26))
            setStroke((1f * density).toInt(), Color.argb(60, 255, 255, 255))
        }

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = backgroundDrawable
            setPadding(
                (16 * density).toInt(),
                (12 * density).toInt(),
                (16 * density).toInt(),
                (12 * density).toInt()
            )
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // Header row with "Aura Live" badge and optional drag handle
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }

        val badgeText = TextView(context).apply {
            text = "● MAYA LIVE"
            textSize = 10f
            setTextColor(Color.parseColor("#FFB347"))
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerRow.addView(badgeText)

        // If interactive mode is enabled, add a small 40dp handle on the side
        if (interactiveHandleEnabled) {
            val handle = View(context).apply {
                val handleSize = (32 * density).toInt()
                layoutParams = LinearLayout.LayoutParams(handleSize, (18 * density).toInt())
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = 4f * density
                    setColor(Color.argb(80, 255, 255, 255))
                }
                var initialY = 0
                var touchStartY = 0f

                setOnTouchListener { _, event ->
                    val params = windowParams ?: return@setOnTouchListener false
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            initialY = params.y
                            touchStartY = event.rawY
                            true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dy = (event.rawY - touchStartY).toInt()
                            params.y = (initialY - dy).coerceAtLeast((24 * density).toInt())
                            if (isAttached && rootView != null) {
                                windowManager.updateViewLayout(rootView, params)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            dragHandle = handle
            headerRow.addView(handle)
        }

        container.addView(headerRow)

        // User speech line
        val userTv = TextView(context).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#A0A5B9"))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (4 * density).toInt()
            }
        }
        userTextView = userTv
        container.addView(userTv)

        // Aura response speech line
        val auraTv = TextView(context).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        auraTextView = auraTv
        container.addView(auraTv)

        root.addView(container)
        rootView = root
        containerLayout = container
    }

    /**
     * Auto-dodges if agent is about to tap or swipe at (x, y) that intersects caption area.
     */
    fun dodgeIfNeeded(targetX: Int, targetY: Int) {
        val params = windowParams ?: return
        if (!isAttached || !isVisible || rootView?.visibility != View.VISIBLE) return

        val displayMetrics = context.resources.displayMetrics
        val screenH = displayMetrics.heightPixels
        val captionHeightEstimate = (120 * density).toInt()

        val isNearBottom = targetY > (screenH - captionHeightEstimate - (64 * density).toInt())
        val isNearTop = targetY < (captionHeightEstimate + (64 * density).toInt())

        val currentIsBottom = (params.gravity and Gravity.BOTTOM) == Gravity.BOTTOM

        if (currentIsBottom && isNearBottom) {
            // Flip to top temporarily
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.y = (48 * density).toInt()
            isDodged = true
            windowManager.updateViewLayout(rootView, params)
            scheduleRestorePosition()
        } else if (!currentIsBottom && isNearTop) {
            // Flip to bottom temporarily
            params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            params.y = (48 * density).toInt()
            isDodged = true
            windowManager.updateViewLayout(rootView, params)
            scheduleRestorePosition()
        }
    }

    private fun scheduleRestorePosition() {
        restoreDodgeJob?.cancel()
        restoreDodgeJob = scope.launch {
            delay(3000L)
            if (isDodged && isAttached && rootView != null) {
                windowParams?.let { params ->
                    params.gravity = if (preferredPosition == "top") {
                        Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    } else {
                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    }
                    params.y = (48 * density).toInt()
                    isDodged = false
                    windowManager.updateViewLayout(rootView, params)
                }
            }
        }
    }

    fun updateCaptions(caption: CaptionState) {
        OverlayGuard.run("floating_captions", context) {
            currentCaption = caption
            val userText = caption.userText.trim()
            val auraText = caption.auraText.trim()

            if (userText.isBlank() && auraText.isBlank()) {
                scheduleAutoHide(1000L)
                return@run
            }

            rootView?.visibility = View.VISIBLE
            if (!isAttached) {
                show()
            } else if (!isVisible) {
                fadeIn()
            }

            userTextView?.apply {
                if (userText.isNotBlank()) {
                    text = "You: $userText"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            auraTextView?.apply {
                if (auraText.isNotBlank()) {
                    text = "Maya: $auraText"
                    visibility = View.VISIBLE
                } else {
                    visibility = View.GONE
                }
            }

            if (!caption.isAuraSpeaking) {
                val delayMs = if (autoHideSeconds <= 0) -1L else autoHideSeconds * 1000L
                if (delayMs > 0) {
                    scheduleAutoHide(delayMs)
                } else {
                    autoHideJob?.cancel()
                }
            } else {
                autoHideJob?.cancel()
            }
        }
    }

    private fun scheduleAutoHide(delayMs: Long) {
        autoHideJob?.cancel()
        autoHideJob = scope.launch {
            delay(delayMs)
            fadeOut()
        }
    }

    private fun fadeIn() {
        isVisible = true
        rootView?.let { view ->
            view.visibility = View.VISIBLE
            ValueAnimator.ofFloat(view.alpha, 1f).apply {
                duration = 150L
                addUpdateListener { view.alpha = it.animatedValue as Float }
                start()
            }
        }
    }

    private fun fadeOut() {
        isVisible = false
        rootView?.let { view ->
            ValueAnimator.ofFloat(view.alpha, 0f).apply {
                duration = 250L
                addUpdateListener {
                    val a = it.animatedValue as Float
                    view.alpha = a
                    if (a <= 0.01f) {
                        view.visibility = View.GONE
                    }
                }
                start()
            }
        }
    }

    fun hideImmediate() {
        OverlayGuard.run("floating_captions", context) {
            isVisible = false
            autoHideJob?.cancel()
            rootView?.let { view ->
                view.alpha = 0f
                view.visibility = View.GONE
            }
        }
    }

    fun destroy() {
        OverlayGuard.run("floating_captions", context) {
            autoHideJob?.cancel()
            restoreDodgeJob?.cancel()
            scope.cancel()
            if (isAttached && rootView != null) {
                try {
                    windowManager.removeView(rootView)
                    OverlayWindowDefaults.unregisterWindow(WINDOW_NAME)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing FloatingCaptionOverlay", e)
                } finally {
                    rootView = null
                    isAttached = false
                    isVisible = false
                }
            }
        }
    }
}
