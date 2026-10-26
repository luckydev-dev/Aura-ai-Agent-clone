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
 * Keeps edge lighting attached while a session is active. Attachment state is based on the real View window token.
 */
class EdgeLightingOverlay(private val context: Context) {
    companion object {
        private const val TAG = "Aura"
        private const val WINDOW_NAME = "EdgeLighting"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private var watchdogJob: Job? = null
    private var activeWindowManager: WindowManager? = null
    private var edgeView: EdgeLightingView? = null
    @Volatile private var isOverlayAttached = false
    @Volatile private var detachedUnexpectedly = false
    @Volatile private var currentState = EdgeLightingState.HIDDEN
    private var currentParams: WindowManager.LayoutParams? = null
    @Volatile private var lastAttachAt = 0L
    @Volatile private var lastDetachAt = 0L
    @Volatile private var lastDetachReason = "not attached"
    private var currentIntensity = 1.0f
    private var currentThicknessDp = 8f

    fun show(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            var result = false
            val latch = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try { result = showOnMain() } finally { latch.countDown() }
            }
            return try { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) && result }
            catch (e: InterruptedException) { Thread.currentThread().interrupt(); Log.e(TAG, "Interrupted waiting for main thread", e); false }
        }
        return showOnMain()
    }

    private fun showOnMain(): Boolean {
        var result = false
        OverlayGuard.run("edge_lighting", context) {
            val existing = edgeView
            if (isAttached(existing)) {
                isOverlayAttached = true
                startWatchdog()
                result = true
                return@run
            }
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "Cannot show EdgeLightingOverlay: SYSTEM_ALERT_WINDOW not granted")
                result = false
                return@run
            }
            if (existing != null) {
                runCatching { activeWindowManager?.removeViewImmediate(existing) }
                    .onFailure { Log.w(TAG, "Removing stale edge-lighting view failed", it) }
                edgeView = null
                isOverlayAttached = false
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = OverlayWindowDefaults.createLayoutParams(
                name = WINDOW_NAME,
                width = WindowManager.LayoutParams.MATCH_PARENT,
                height = WindowManager.LayoutParams.MATCH_PARENT,
                alpha = 0.79f,
                touchable = false
            ).apply { type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
            val view = EdgeLightingView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                intensity = (currentIntensity / OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA).coerceAtMost(1.5f)
                strokeThicknessDp = currentThicknessDp
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        isOverlayAttached = v.isAttachedToWindow && v.windowToken != null
                        detachedUnexpectedly = false
                        lastAttachAt = System.currentTimeMillis()
                    }
                    override fun onViewDetachedFromWindow(v: View) {
                        isOverlayAttached = false
                        detachedUnexpectedly = currentState != EdgeLightingState.HIDDEN
                        lastDetachAt = System.currentTimeMillis()
                        lastDetachReason = "view detached unexpectedly"
                        Log.w(TAG, "Edge lighting detached; active=" + detachedUnexpectedly)
                        if (detachedUnexpectedly) watchdogScope.launch {
                            delay(300L)
                            if (currentState != EdgeLightingState.HIDDEN) show()
                        }
                    }
                })
            }
            try {
                wm.addView(view, params)
                activeWindowManager = wm
                currentParams = params
                edgeView = view
                isOverlayAttached = isAttached(view)
                detachedUnexpectedly = false
                lastAttachAt = System.currentTimeMillis()
                OverlayWindowDefaults.registerWindow(WINDOW_NAME, params.alpha)
                Log.i(TAG, "EdgeLightingOverlay attached; type=" + params.type + ", alpha=" + params.alpha)
                startWatchdog()
                result = isOverlayAttached
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach edge-lighting overlay", e)
                result = false
            }
        }
        return result
    }

    private fun isAttached(view: View?): Boolean = view != null && view.isAttachedToWindow && view.windowToken != null

    private fun startWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = watchdogScope.launch {
            while (currentState != EdgeLightingState.HIDDEN) {
                delay(2000L)
                val view = edgeView
                val attached = isAttached(view)
                val visible = view != null && view.visibility == View.VISIBLE && view.alpha > 0f
                if (!attached || !visible || !isOverlayAttached) {
                    val reason = when {
                        detachedUnexpectedly -> lastDetachReason
                        view == null -> "missing-view"
                        !attached -> "window-not-attached"
                        !visible -> "view-not-visible"
                        else -> "attach-flag-stale"
                    }
                    Log.w(TAG, "Edge lighting watchdog repairing overlay; reason=" + reason)
                    runCatching {
                        val stale = edgeView
                        val wm = activeWindowManager
                        if (stale != null && wm != null) wm.removeViewImmediate(stale)
                    }.onFailure { Log.w(TAG, "Watchdog stale-view cleanup failed", it) }
                    edgeView = null
                    activeWindowManager = null
                    currentParams = null
                    isOverlayAttached = false
                    val restored = showOnMain()
                    if (restored) {
                        runCatching { com.example.AuraApplication.instance.liveDiagnostics.log(TAG, "Edge lighting re-attached (reason=" + reason + ")") }
                            .onFailure { Log.w(TAG, "Diagnostics re-attach logging failed", it) }
                        Log.i(TAG, "Edge lighting re-attached (reason=" + reason + ")")
                    } else {
                        Log.e(TAG, "Edge lighting watchdog failed to re-attach (reason=" + reason + ")")
                    }
                }
            }
        }
    }

    fun setState(state: EdgeLightingState) {
        onMain {
            currentState = state
            if (state == EdgeLightingState.HIDDEN) {
                destroyOnMain()
                return@onMain
            }
            if (!isAttached(edgeView)) showOnMain()
            edgeView?.setState(state)
            startWatchdog()
        }
    }

    fun updateAmplitudes(mic: Float, speaker: Float) = onMain { edgeView?.updateAmplitudes(mic, speaker) }

    fun setIntensity(intensity: Float) = onMain {
        currentIntensity = intensity.coerceIn(0f, 1.5f)
        edgeView?.intensity = (currentIntensity / OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA).coerceAtMost(1.5f)
    }

    fun setThicknessDp(thicknessDp: Float) = onMain {
        currentThicknessDp = thicknessDp.coerceIn(1f, 48f)
        edgeView?.strokeThicknessDp = currentThicknessDp
    }

    fun hide() = setState(EdgeLightingState.HIDDEN)

    fun getAttachDiagnostics(): Map<String, Any> = mapOf(
        "windowType" to (currentParams?.type ?: -1),
        "attached" to isAttached(edgeView),
        "alpha" to (currentParams?.alpha ?: 0f),
        "lastAttachAt" to lastAttachAt,
        "lastDetachAt" to lastDetachAt,
        "lastDetachReason" to lastDetachReason,
        "detachedUnexpectedly" to detachedUnexpectedly
    )

    fun onServiceLifecycleChanged() = onMain {
        if (currentState != EdgeLightingState.HIDDEN) {
            isOverlayAttached = false
            showOnMain()
            edgeView?.setState(currentState)
        }
    }

    fun destroy() = onMain { destroyOnMain() }

    private fun destroyOnMain() {
        currentState = EdgeLightingState.HIDDEN
        watchdogJob?.cancel()
        watchdogJob = null
        val view = edgeView
        val wm = activeWindowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        if (view != null) {
            try {
                view.release()
                if (view.isAttachedToWindow) wm.removeViewImmediate(view)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing EdgeLightingOverlay", e)
            } finally {
                edgeView = null
                isOverlayAttached = false
                activeWindowManager = null
                currentParams = null
                detachedUnexpectedly = false
                lastDetachAt = System.currentTimeMillis()
                lastDetachReason = "destroy requested"
                OverlayWindowDefaults.unregisterWindow(WINDOW_NAME)
            }
        } else {
            isOverlayAttached = false
            OverlayWindowDefaults.unregisterWindow(WINDOW_NAME)
        }
    }

    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            try { block() } catch (e: Exception) { Log.e(TAG, "Main-thread overlay action failed", e) }
        } else {
            mainHandler.post {
                try { block() } catch (e: Exception) { Log.e(TAG, "Posted overlay action failed", e) }
            }
        }
    }
}