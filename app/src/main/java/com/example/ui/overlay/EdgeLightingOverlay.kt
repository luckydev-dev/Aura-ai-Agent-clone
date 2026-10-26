package com.example.ui.overlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

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
    private var isOverlayAttached = false

    private var currentIntensity = 1.0f
    private var currentThicknessDp = 8f

    fun show(): Boolean {
        var result = false
        OverlayGuard.run("edge_lighting", context) {
            if (isOverlayAttached) {
                result = true
                return@run
            }
            if (!Settings.canDrawOverlays(context) && !com.example.service.AuraAccessibilityService.isRunning()) {
                Log.w(TAG, "Cannot show EdgeLightingOverlay: neither SYSTEM_ALERT_WINDOW nor Accessibility active")
                result = false
                return@run
            }

            val (wm, windowType) = OverlayWindowDefaults.getAppropriateWindowManager(context)
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
                // Compensate brightness internally for window alpha
                intensity = (currentIntensity / OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA).coerceAtMost(1.5f)
                strokeThicknessDp = currentThicknessDp
            }

            try {
                wm.addView(view, params)
                OverlayWindowDefaults.registerWindow(WINDOW_NAME, params.alpha)
                edgeView = view
                isOverlayAttached = true
                Log.i(TAG, "EdgeLightingOverlay attached with type $windowType, pass-through alpha=${params.alpha}")
                result = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add EdgeLightingOverlay window", e)
                result = false
            }
        }
        return result
    }

    fun setState(state: EdgeLightingState) {
        OverlayGuard.run("edge_lighting", context) {
            if (state == EdgeLightingState.HIDDEN) {
                destroy()
                return@run
            }
            if (!isOverlayAttached) {
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

    fun destroy() {
        OverlayGuard.run("edge_lighting", context) {
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
