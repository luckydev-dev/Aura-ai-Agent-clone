package com.example.ui.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.service.AuraForegroundService
import com.example.ui.components.AuraOrb
import com.example.ui.components.OrbState
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

class OverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayHost: ComposeOverlayHost? = null
    private var isOverlayShowing = false

    private val currentCaptionText = MutableStateFlow("")

    fun updateCaption(text: String) {
        OverlayGuard.run("mini_orb", context) {
            currentCaptionText.value = text
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    fun showOverlay() {
        OverlayGuard.run("mini_orb", context) {
            if (isOverlayShowing || !Settings.canDrawOverlays(context)) return@run

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 80
                y = 300
            }

            // Create or reuse host
            val host = ComposeOverlayHost(context, windowManager)
            overlayHost = host

            // Handle touch dragging
            var initialX = 0
            var initialY = 0
            var initialTouchX = 0f
            var initialTouchY = 0f

            val touchListener = android.view.View.OnTouchListener { view, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        host.updateLayout(params)
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = abs(event.rawX - initialTouchX)
                        val diffY = abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            view.performClick()
                        }
                        true
                    }
                    else -> false
                }
            }

            val attached = host.attach(params, touchListener) {
                AuraTheme {
                    val service = AuraForegroundService.currentService
                    val isCallActive by AuraForegroundService.isCallActive.collectAsState()
                    val micAmp = runCatching { service?.audioStreamer?.micAmplitude?.collectAsState()?.value }.getOrNull() ?: 0f
                    val speakerAmp = runCatching { service?.audioPlayer?.speakerAmplitude?.collectAsState()?.value }.getOrNull() ?: 0f
                    val isActing = runCatching { service?.liveClient?.isAuraActing?.collectAsState()?.value }.getOrNull() ?: false
                    val caption by currentCaptionText.collectAsState()

                    val state = when {
                        !isCallActive -> OrbState.IDLE
                        isActing -> OrbState.ACTING
                        speakerAmp > 0.05f -> OrbState.SPEAKING
                        micAmp > 0.04f -> OrbState.LISTENING
                        else -> OrbState.IDLE
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(8.dp)
                    ) {
                        // Draggable mini orb
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .clickable {
                                    if (isCallActive) {
                                        AuraForegroundService.endCall(context)
                                    } else {
                                        AuraForegroundService.startCall(context)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            AuraOrb(
                                state = state,
                                amplitude = if (state == OrbState.SPEAKING) speakerAmp else micAmp,
                                size = 68.dp
                            )
                        }

                        // Compact caption pill while speaking or acting
                        if (isCallActive && caption.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 200.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0xE614141B))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                androidx.compose.foundation.text.BasicText(
                                    text = caption,
                                    style = AuraTheme.typography.labelSmall.copy(color = Color.White),
                                    maxLines = 2
                                )
                            }
                        }
                    }
                }
            }

            if (attached) {
                isOverlayShowing = true
                Log.i(TAG, "Mini-orb overlay shown safely via ComposeOverlayHost")
            } else {
                isOverlayShowing = false
                overlayHost = null
            }
        }
    }

    fun hideOverlay() {
        OverlayGuard.run("mini_orb", context) {
            if (!isOverlayShowing || overlayHost == null) return@run
            overlayHost?.detach()
            overlayHost = null
            isOverlayShowing = false
            Log.i(TAG, "Mini-orb overlay safely hidden")
        }
    }
}
