package com.example.ui.overlay

import android.graphics.PixelFormat
import android.os.Build
import android.view.WindowManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized WindowManager LayoutParams factory enforcing the Android 12+ (API 31+)
 * untrusted touch pass-through rules across all Aura overlays.
 *
 * CRITICAL RULE: Window opacity (params.alpha) MUST be strictly <= 0.80f (we use 0.79f)
 * for any FLAG_NOT_TOUCHABLE overlay window so Android's InputDispatcher never drops
 * or occludes touches to underlying apps.
 */
object OverlayWindowDefaults {

    const val MAX_PASSTHROUGH_ALPHA = 0.79f

    private val activeOverlays = ConcurrentHashMap<String, Float>()

    fun registerOverlay(name: String, alpha: Float) {
        activeOverlays[name] = alpha
    }

    fun unregisterOverlay(name: String) {
        activeOverlays.remove(name)
    }

    fun registerWindow(name: String, alpha: Float) = registerOverlay(name, alpha)
    fun unregisterWindow(name: String) = unregisterOverlay(name)
    fun getActiveOverlays(): Map<String, Float> = activeOverlays.toMap()
    fun getAttachedWindows(): Map<String, Float> = activeOverlays.toMap()

    fun isAllOverlaysPassThroughOk(): Boolean {
        return activeOverlays.values.all { it <= MAX_PASSTHROUGH_ALPHA }
    }

    /**
     * Obtains the most trusted WindowManager and LayoutParams type available.
     * When AuraAccessibilityService is active, uses its context and TYPE_ACCESSIBILITY_OVERLAY,
     * which Android's InputDispatcher treats as a trusted system window, bypassing all untrusted
     * touch filtering and obscured touch drops.
     */
    fun getAppropriateWindowManager(context: android.content.Context, useAccessibilityIfAvailable: Boolean = true): Pair<WindowManager, Int> {
        val a11y = if (useAccessibilityIfAvailable) com.example.service.AuraAccessibilityService.instance else null
        if (a11y != null) {
            val a11yWm = a11y.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager
            if (a11yWm != null) {
                return Pair(a11yWm, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            }
        }
        val wm = context.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }
        return Pair(wm, type)
    }

    fun createLayoutParams(
        name: String,
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.MATCH_PARENT,
        touchable: Boolean = false
    ): WindowManager.LayoutParams {
        return createLayoutParams(
            title = name,
            width = width,
            height = height,
            isTouchable = touchable
        )
    }

    fun createLayoutParams(
        title: String,
        width: Int = WindowManager.LayoutParams.MATCH_PARENT,
        height: Int = WindowManager.LayoutParams.MATCH_PARENT,
        gravity: Int? = null,
        alpha: Float = MAX_PASSTHROUGH_ALPHA,
        isTouchable: Boolean = false,
        x: Int = 0,
        y: Int = 0
    ): WindowManager.LayoutParams {
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

        if (!isTouchable) {
            // Strictly non-touchable: DO NOT include FLAG_NOT_TOUCH_MODAL or FLAG_LAYOUT_NO_LIMITS!
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }

        val safeAlpha = if (!isTouchable) alpha.coerceAtMost(MAX_PASSTHROUGH_ALPHA) else alpha

        val params = WindowManager.LayoutParams(
            width,
            height,
            layoutType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            this.alpha = safeAlpha
            this.setTitle("AuraOverlay:$title")
            if (gravity != null) {
                this.gravity = gravity
            }
            this.x = x
            this.y = y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                setFitInsetsTypes(0)
            }
        }

        registerOverlay(title, safeAlpha)
        return params
    }
}
