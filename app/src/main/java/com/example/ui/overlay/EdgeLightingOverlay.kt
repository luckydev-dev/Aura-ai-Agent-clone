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

class EdgeLightingOverlay(private val context: Context) {
    companion object { private const val TAG = "Aura"; private const val WINDOW_NAME = "EdgeLighting" }

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
        if (Looper.myLooper() == Looper.getMainLooper()) return showOnMain()
        val result = java.util.concurrent.atomic.AtomicBoolean(false)
        val done = java.util.concurrent.CountDownLatch(1)
        mainHandler.post { try { result.set(showOnMain()) } finally { done.countDown() } }
        return try { done.await(2000L, java.util.concurrent.TimeUnit.MILLISECONDS) && result.get() }
        catch (e: InterruptedException) { Thread.currentThread().interrupt(); Log.e(TAG, "Interrupted waiting for main thread", e); false }
    }

    private fun showOnMain(): Boolean {
        var result = false
        OverlayGuard.run("edge_lighting", context) {
            val existing = edgeView
            if (isAttached(existing)) { isOverlayAttached = true; startWatchdog(); result = true; return@run }
            if (!Settings.canDrawOverlays(context)) { Log.w(TAG, "Cannot show edge lighting: overlay permission missing"); result = false; return@run }
            if (existing != null) {
                runCatching { activeWindowManager?.removeViewImmediate(existing) }.onFailure { Log.w(TAG, "Removing stale edge view failed", it) }
                edgeView = null; isOverlayAttached = false
            }
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = OverlayWindowDefaults.createLayoutParams(title = WINDOW_NAME, width = WindowManager.LayoutParams.MATCH_PARENT, height = WindowManager.LayoutParams.MATCH_PARENT, alpha = 0.79f, isTouchable = false).also { it.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY }
            val view = EdgeLightingView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                intensity = (currentIntensity / OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA).coerceAtMost(1.5f)
                strokeThicknessDp = currentThicknessDp
                addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) { isOverlayAttached = v.isAttachedToWindow && v.windowToken != null; detachedUnexpectedly = false; lastAttachAt = System.currentTimeMillis() }
                    override fun onViewDetachedFromWindow(v: View) {
                        isOverlayAttached = false; detachedUnexpectedly = currentState != EdgeLightingState.HIDDEN; lastDetachAt = System.currentTimeMillis(); lastDetachReason = "view detached unexpectedly"
                        Log.w(TAG, "Edge lighting detached unexpectedly; active=" + detachedUnexpectedly)
                        if (detachedUnexpectedly) watchdogScope.launch { delay(300L); if (currentState != EdgeLightingState.HIDDEN) showOnMain() }
                    }
                })
            }
            try {
                wm.addView(view, params)
                activeWindowManager = wm; currentParams = params; edgeView = view; isOverlayAttached = isAttached(view); detachedUnexpectedly = false; lastAttachAt = System.currentTimeMillis()
                OverlayWindowDefaults.registerWindow(WINDOW_NAME, params.alpha)
                Log.i(TAG, "Edge lighting attached; type=" + params.type + ", alpha=" + params.alpha)
                startWatchdog(); result = isOverlayAttached
            } catch (e: Exception) { Log.e(TAG, "Failed to attach edge lighting", e); result = false }
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
                    val reason = when { detachedUnexpectedly -> lastDetachReason; view == null -> "missing-view"; !attached -> "window-not-attached"; !visible -> "view-not-visible"; else -> "attach-flag-stale" }
                    Log.w(TAG, "Edge lighting watchdog repairing overlay; reason=" + reason)
                    runCatching { val stale = edgeView; val manager = activeWindowManager; if (stale != null && manager != null) manager.removeViewImmediate(stale) }.onFailure { Log.w(TAG, "Watchdog cleanup failed", it) }
                    edgeView = null; activeWindowManager = null; currentParams = null; isOverlayAttached = false
                    val restored = showOnMain()
                    if (restored) {
                        runCatching { com.example.AuraApplication.instance.liveDiagnostics.log(TAG, "Edge lighting re-attached (reason=" + reason + ")") }.onFailure { Log.w(TAG, "Diagnostics log failed", it) }
                        Log.i(TAG, "Edge lighting re-attached (reason=" + reason + ")")
                    } else Log.e(TAG, "Edge lighting re-attach failed (reason=" + reason + ")")
                }
            }
        }
    }

    fun setState(state: EdgeLightingState) = runOnMain {
        currentState = state
        if (state == EdgeLightingState.HIDDEN) { destroyOnMain(); return@runOnMain }
        if (!isAttached(edgeView)) showOnMain()
        edgeView?.setState(state)
        startWatchdog()
    }
    fun updateAmplitudes(mic: Float, speaker: Float) = runOnMain { edgeView?.updateAmplitudes(mic, speaker) }
    fun setIntensity(intensity: Float) = runOnMain { currentIntensity = intensity.coerceIn(0f, 1.5f); edgeView?.intensity = (currentIntensity / OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA).coerceAtMost(1.5f) }
    fun setThicknessDp(thicknessDp: Float) = runOnMain { currentThicknessDp = thicknessDp.coerceIn(1f, 48f); edgeView?.strokeThicknessDp = currentThicknessDp }
    fun hide() = setState(EdgeLightingState.HIDDEN)
    fun onServiceLifecycleChanged() = runOnMain { if (currentState != EdgeLightingState.HIDDEN) { isOverlayAttached = false; showOnMain(); edgeView?.setState(currentState) } }
    fun getAttachDiagnostics(): Map<String, Any> = mapOf("windowType" to (currentParams?.type ?: -1), "attached" to isAttached(edgeView), "alpha" to (currentParams?.alpha ?: 0f), "lastAttachAt" to lastAttachAt, "lastDetachAt" to lastDetachAt, "lastDetachReason" to lastDetachReason, "detachedUnexpectedly" to detachedUnexpectedly)
    fun destroy() = runOnMain { destroyOnMain() }

    private fun destroyOnMain() {
        currentState = EdgeLightingState.HIDDEN; watchdogJob?.cancel(); watchdogJob = null
        val view = edgeView; val wm = activeWindowManager ?: (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
        if (view != null) {
            try { view.release(); if (view.isAttachedToWindow) wm.removeViewImmediate(view) }
            catch (e: Exception) { Log.e(TAG, "Error removing edge lighting", e) }
            finally { edgeView = null; isOverlayAttached = false; activeWindowManager = null; currentParams = null; detachedUnexpectedly = false; lastDetachAt = System.currentTimeMillis(); lastDetachReason = "destroy requested"; OverlayWindowDefaults.unregisterWindow(WINDOW_NAME) }
        } else { isOverlayAttached = false; OverlayWindowDefaults.unregisterWindow(WINDOW_NAME) }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) { try { action() } catch (e: Exception) { Log.e(TAG, "Overlay main-thread action failed", e) } }
        else mainHandler.post { try { action() } catch (e: Exception) { Log.e(TAG, "Posted overlay action failed", e) } }
    }
}