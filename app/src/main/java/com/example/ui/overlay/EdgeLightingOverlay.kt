package com.example.ui.overlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Manages the screen-edge lighting overlay window.
 * Uses OverlayWindowDefaults to enforce alpha <= 0.79f so it NEVER blocks touches on Android 12+.
 */
class EdgeLightingOverlay(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
        private const val WINDOW_NAME = "EdgeLighting"
    }

    private var activeWindowManager: WindowManager? = null
    private var edgeView: EdgeLightingView? = null
    @Volatile private var isOverlayAttached = false
    @Volatile private var detachedUnexpectedly = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var watchdogJob: Job? = null
    private var currentState: EdgeLightingState = EdgeLightingState.HIDDEN
    private var currentParams: WindowManager.LayoutParams? = null
    @Volatile private var lastAttachAt: Long = 0L
    @Volatile private var lastDetachReason: String = "not attached"

    private var currentIntensity = 1.0f
    private var currentThicknessDp = 8f

    fun show(): Boolean {
        var result = false
        OverlayGuard.run("edge_lighting", context) {
            val existing = edgeView
            if (isOverlayAttached && existing != null && existing.isAttachedToWindow && existing.windowToken != null) {
                result = true
                return@run
            }
            if (existing != null) {
                runCatching { activeWindowManager?.removeViewImmediate(existing) }
                    .onFailure { Log.w(TAG, "Removing stale edge view failed", it) }
                edgeView = null
                isOverlayAttached = false
            }
            if (!Settings.canDrawOverlays(context) && !com.example.service.AuraAccessibilityService.isRunning()) {
                Log.w(TAG, "Cannot show EdgeLightingOverlay: neither SYSTEM_ALERT_WINDOW nor Accessibility active")
                result = false
                return@run
            }

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            activeWindowManager = wm

            val params = OverlayWindowDefaults.createLayoutParams(
                name = WINDOW_NAME,
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
                touchable = false
            ).apply {
                type = windowType
            }

            val view = EdgeLightingView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                alpha = 1f
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        isOverlayAttached = v.isAttachedToWindow && v.windowToken != null
                        detachedUnexpectedly = false
                        lastAttachAt = System.currentTimeMillis()
                    }

                    override fun onViewDetachedFromWindow(v: View) {
                        val wasActive = currentState != EdgeLightingState.HIDDEN
                        isOverlayAttached = false
                        detachedUnexpectedly = wasActive
                        lastDetachReason = "view detached unexpectedly"
                        Log.w(TAG, "Edge lighting view detached (active=" + wasActive + ")")
                        if (wasActive) watchdogScope.launch {
                            delay(300L)
                            if (currentState != EdgeLightingState.HIDDEN) show()
                        }
                    }
                })
                // Compensate brightness internally for window alpha
                intensity = (currentIntensity / OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA).coerceAtMost(1.5f)
                strokeThicknessDp = currentThicknessDp
            }

            try {
                wm.addView(view, params)
                OverlayWindowDefaults.registerWindow(WINDOW_NAME, params.alpha)
                edgeView = view
                currentParams = params
                isOverlayAttached = view.isAttachedToWindow && view.windowToken != null
                detachedUnexpectedly = false
                lastAttachAt = System.currentTimeMillis()
                Log.i(TAG, "EdgeLightingOverlay attached with type " + windowType + ", pass-through alpha=" + params.alpha)
                startWatchdog()
                result = isOverlayAttached
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add EdgeLightingOverlay window", e)
                result = false
            }
        }
        return result
    }

    fun setState(state: EdgeLightingState) {
        OverlayGuard.run("edge_lighting", context) {
            currentState = state
            if (state == EdgeLightingState.HIDDEN) {
                destroy()
                return@run
            }
            val view = edgeView
            if (!isOverlayAttached || view == null || !view.isAttachedToWindow || view.windowToken == null) {
                show()
            }
            edgeView?.setState(state)
        }
    }

    fun updateAmplitudes(mic: Float, speaker: Float) {
        OverlayGuard.run("edge_lighting", context) {
            edgeView?.updateAmplitudes(mic, speaker)
        }
    }

    fun setIntensity(intensity: Float) {
        OverlayGuard.run("edge_lighting", context) {
            currentIntensity = intensity
            edgeView?.intensity = (intensity / OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA).coerceAtMost(1.5f)
        }
    }

    fun setThicknessDp(thicknessDp: Float) {
        OverlayGuard.run("edge_lighting", context) {
            currentThicknessDp = thicknessDp
            edgeView?.strokeThicknessDp = thicknessDp
        }
    }

    fun hide() {
        setState(EdgeLightingState.HIDDEN)
    }

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = watchdogScope.launch {
            while (currentState != EdgeLightingState.HIDDEN) {
                delay(2000L)
                val view = edgeView
                val attached = view != null && view.isAttachedToWindow && view.windowToken != null
                val visible = view != null && view.visibility == View.VISIBLE && view.alpha > 0f
                if (!attached || !visible || !isOverlayAttached) {
                    val reason = when {
                        detachedUnexpectedly -> lastDetachReason
                        view == null -> "missing-view"
                        !attached -> "window-not-attached"
                        !visible -> "view-not-visible"
                        else -> "attach-flag-stale"
                    }
                    Log.w(TAG, "Edge lighting watchdog repairing overlay (reason=" + reason + ")")
                    runCatching {
                        val stale = edgeView
                        val wm = activeWindowManager
                        if (stale != null && wm != null) wm.removeViewImmediate(stale)
                    }.onFailure { Log.w(TAG, "Watchdog stale-window cleanup failed", it) }
                    edgeView = null
                    isOverlayAttached = false
                    activeWindowManager = null
                    currentParams = null
                    lastDetachReason = "destroy requested"
                    val restored = show()
                    if (restored) {
                        runCatching { com.example.AuraApplication.instance.liveDiagnostics.log(TAG, "Edge lighting re-attached (reason=" + reason + ")") }
                        Log.i(TAG, "Edge lighting re-attached (reason=" + reason + ")")
                    } else {
                        Log.e(TAG, "Edge lighting watchdog re-attach failed (reason=" + reason + ")")
                    }
                }
            }
        }
    }

    fun getAttachDiagnostics(): Map<String, Any> = mapOf(
        "windowType" to (currentParams?.type ?: -1),
        "attached" to (edgeView?.let { it.isAttachedToWindow && it.windowToken != null } == true),
        "alpha" to (currentParams?.alpha ?: 0f),
        "lastAttachAt" to lastAttachAt,
        "lastDetachReason" to lastDetachReason,
        "detachedUnexpectedly" to detachedUnexpectedly
    )

    fun onServiceLifecycleChanged() {
        runOnMain {
            if (currentState != EdgeLightingState.HIDDEN) {
                isOverlayAttached = false
                show()
            }
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
    fun destroy() {
        OverlayGuard.run("edge_lighting", context) {
            currentState = EdgeLightingState.HIDDEN
            watchdogJob?.cancel()
            watchdogJob = null
            val view = edgeView
            val wm = activeWindowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            if (isOverlayAttached && view != null) {
                try {
                    view.release()
                    wm.removeView(view)
                    OverlayWindowDefaults.unregisterWindow(WINDOW_NAME)
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing EdgeLightingOverlay", e)
                } finally {
                    edgeView = null
                    isOverlayAttached = false
                    activeWindowManager = null
                }
            }
        }
    }
}
