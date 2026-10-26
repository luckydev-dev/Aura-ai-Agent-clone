package com.example.ui.overlay

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.AuraApplication
import com.example.core.lifecycle.AppForegroundState
import com.example.core.live.CaptionState
import com.example.core.live.LiveConnectionState
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

class OverlayController(
    private val context: Context,
    private val preferences: AuraPreferences,
    private val overlayManager: OverlayManager,
    private val edgeLightingOverlay: EdgeLightingOverlay,
    val floatingCaptionOverlay: FloatingCaptionOverlay = FloatingCaptionOverlay(context),
    val agentCursorOverlay: AgentCursorOverlay = AgentCursorOverlay(context, AuraApplication.instance.inputController)
) {
    companion object {
        private const val TAG = "Aura"
    }

    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Cached states
    private var isCallActive = false
    private var connectionState: LiveConnectionState = LiveConnectionState.Disconnected
    private var isAuraActing = false
    private var isAuraThinking = false
    private var isMuted = false
    private var speakerAmp = 0f
    private var latestCaption = CaptionState()

    fun startObserving() {
        // Edge lighting controls
        controllerScope.launch {
            preferences.edgeLightingIntensity.collectLatest { inten ->
                edgeLightingOverlay.setIntensity(inten)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingThickness.collectLatest { thickness ->
                edgeLightingOverlay.setThicknessDp(thickness.toFloat())
            }
        }
        controllerScope.launch {
            preferences.edgeLightingEnabled.collectLatest {
                reEvaluateOverlays(AppForegroundState.current)
            }
        }
        controllerScope.launch {
            preferences.edgeLightingShowInApp.collectLatest {
                reEvaluateOverlays(AppForegroundState.current)
            }
        }

        // Agent Mouse Cursor observations
        controllerScope.launch {
            preferences.agentCursorEnabled.collectLatest { enabled ->
                agentCursorOverlay.isEnabled = enabled
                if (!enabled) {
                    agentCursorOverlay.detachOverlay()
                }
            }
        }
        controllerScope.launch {
            preferences.agentCursorStyle.collectLatest { styleStr ->
                agentCursorOverlay.cursorStyle = when (styleStr.uppercase()) {
                    "DOT" -> CursorStyle.DOT
                    "HAND" -> CursorStyle.HAND
                    else -> CursorStyle.ARROW
                }
            }
        }
        controllerScope.launch {
            preferences.agentCursorSizeDp.collectLatest { size ->
                agentCursorOverlay.cursorSizeDp = size
            }
        }
        controllerScope.launch {
            preferences.agentCursorSpeed.collectLatest { speedStr ->
                agentCursorOverlay.cursorSpeed = when (speedStr.uppercase()) {
                    "INSTANT" -> CursorSpeed.INSTANT
                    "FAST" -> CursorSpeed.FAST
                    "SLOW" -> CursorSpeed.SLOW
                    else -> CursorSpeed.NORMAL
                }
            }
        }
        controllerScope.launch {
            preferences.agentCursorTrail.collectLatest { trail ->
                agentCursorOverlay.showTrail = trail
            }
        }
        controllerScope.launch {
            preferences.agentCursorRipple.collectLatest { ripple ->
                agentCursorOverlay.showRipple = ripple
            }
        }
        controllerScope.launch {
            preferences.agentCursorActionLabel.collectLatest { label ->
                agentCursorOverlay.showActionLabel = label
            }
        }
        controllerScope.launch {
            preferences.showCursorDuringUnlock.collectLatest { show ->
                agentCursorOverlay.showDuringUnlock = show
            }
        }

        // Re-evaluate overlays whenever Foreground State changes!
        controllerScope.launch {
            AppForegroundState.isForeground.collectLatest { isForeground ->
                Log.d(TAG, "AppForegroundState changed: isForeground=$isForeground. Re-evaluating overlays...")
                reEvaluateOverlays(isForeground)
            }
        }
    }

    fun dodgeCaptions(targetX: Int, targetY: Int) {
        floatingCaptionOverlay.dodgeIfNeeded(targetX, targetY)
    }

    /**
     * Temporarily hides cursor and caption overlay during screenshot/dump capture,
     * ensuring perfectly clean vision/planner captures.
     */
    suspend fun <T> hideForCapture(block: suspend () -> T): T {
        return AuraApplication.instance.inputController.withCursorHidden {
            val wasCaptionVisible = floatingCaptionOverlay
            floatingCaptionOverlay.hideImmediate()
            // Wait 1 frame (~50ms) to ensure window manager removes/hides before capture
            delay(50)
            try {
                block()
            } finally {
                if (latestCaption.auraText.isNotBlank() || latestCaption.userText.isNotBlank()) {
                    floatingCaptionOverlay.updateCaptions(latestCaption)
                }
            }
        }
    }

    fun updateAmplitudes(micAmp: Float, speakerAmp: Float) {
        this.speakerAmp = speakerAmp
        edgeLightingOverlay.updateAmplitudes(micAmp, speakerAmp)
    }

    fun updateCaptions(caption: CaptionState) {
        latestCaption = caption
        val isForeground = AppForegroundState.current
        if (!isForeground && isCallActive && !AuraApplication.isSafeMode.value) {
            controllerScope.launch {
                val floatingCaptionsOn = preferences.floatingCaptionsEnabled.first()
                if (floatingCaptionsOn) {
                    floatingCaptionOverlay.updateCaptions(caption)
                }
            }
        } else {
            floatingCaptionOverlay.hideImmediate()
        }
    }

    suspend fun updateCallState(
        isCallActive: Boolean,
        connectionState: LiveConnectionState,
        isAuraActing: Boolean,
        isAuraThinking: Boolean,
        isMuted: Boolean,
        speakerAmp: Float
    ) {
        this.isCallActive = isCallActive
        this.connectionState = connectionState
        this.isAuraActing = isAuraActing
        this.isAuraThinking = isAuraThinking
        this.isMuted = isMuted
        this.speakerAmp = speakerAmp

        reEvaluateOverlays(AppForegroundState.current)
    }

    private suspend fun reEvaluateOverlays(isAppInForeground: Boolean) {
        // In Safe Mode, never display overlays
        if (AuraApplication.isSafeMode.value) {
            floatingCaptionOverlay.hideImmediate()
            overlayManager.hideOverlay()
            edgeLightingOverlay.setState(EdgeLightingState.HIDDEN)
            agentCursorOverlay.hide()
            return
        }

        val edgeEnabled = preferences.edgeLightingEnabled.first()
        val showEdgeInApp = preferences.edgeLightingShowInApp.first()
        val miniOrbEnabled = preferences.overlayEnabled.first()
        val floatingCaptionsOn = preferences.floatingCaptionsEnabled.first()
        val cursorEnabled = preferences.agentCursorEnabled.first()

        // Auto-grant overlay permission via root if needed
        if ((edgeEnabled || miniOrbEnabled || floatingCaptionsOn || cursorEnabled) && !Settings.canDrawOverlays(context)) {
            runCatching {
                com.topjohnwu.superuser.Shell.cmd("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow").exec()
            }
        }

        if (cursorEnabled) {
            agentCursorOverlay.show()
        } else {
            agentCursorOverlay.hide()
        }

        // 1. Manage Floating Captions & Mini Orb:
        if (isAppInForeground || !isCallActive) {
            floatingCaptionOverlay.hideImmediate()
            overlayManager.hideOverlay()
        } else {
            if (floatingCaptionsOn && (latestCaption.auraText.isNotBlank() || latestCaption.userText.isNotBlank())) {
                floatingCaptionOverlay.updateCaptions(latestCaption)
            }
            if (miniOrbEnabled) {
                overlayManager.showOverlay()
            } else {
                overlayManager.hideOverlay()
            }
        }

        // 2. Manage Edge Lighting: keep visible everywhere across all app launches while call is active
        val shouldShowEdge = edgeEnabled && isCallActive
        if (shouldShowEdge) {
            val targetState = when {
                isMuted -> EdgeLightingState.MUTED
                connectionState is LiveConnectionState.Error -> EdgeLightingState.ERROR
                connectionState is LiveConnectionState.Connecting -> EdgeLightingState.CONNECTING
                connectionState is LiveConnectionState.Reconnecting -> EdgeLightingState.RECONNECTING
                connectionState is LiveConnectionState.Connected -> when {
                    isAuraActing -> EdgeLightingState.ACTING
                    speakerAmp > 0.05f -> EdgeLightingState.SPEAKING
                    isAuraThinking -> EdgeLightingState.THINKING
                    else -> EdgeLightingState.LISTENING
                }
                else -> EdgeLightingState.LISTENING
            }
            edgeLightingOverlay.setState(targetState)
        } else {
            edgeLightingOverlay.setState(EdgeLightingState.HIDDEN)
        }
    }

    fun hideAll() {
        edgeLightingOverlay.hide()
        overlayManager.hideOverlay()
        floatingCaptionOverlay.hideImmediate()
        agentCursorOverlay.hide()
    }

    fun destroy() {
        controllerScope.cancel()
        hideAll()
        floatingCaptionOverlay.destroy()
        agentCursorOverlay.destroy()
    }
}
