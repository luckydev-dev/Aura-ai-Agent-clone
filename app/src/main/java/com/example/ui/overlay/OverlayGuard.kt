package com.example.ui.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.AuraApplication
import com.example.MainActivity
import com.example.R
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.ConcurrentHashMap

object OverlayGuard {

    private const val TAG = "Aura"
    private const val CHANNEL_ID = "aura_overlay_guard_channel"
    private const val NOTIFICATION_ID_BASE = 9200
    private const val MAX_FAILURES = 2
    private const val TIME_WINDOW_MS = 5 * 60 * 1000L // 5 minutes

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Tracks failure timestamps per overlay key: "mini_orb", "edge_lighting", "floating_captions"
    private val failureTimestamps = ConcurrentHashMap<String, MutableList<Long>>()

    fun run(
        overlayKey: String,
        context: Context,
        action: () -> Unit
    ) {
        val runnable = Runnable {
            try {
                action()
            } catch (t: Throwable) {
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()
                Log.e(TAG, "OverlayGuard caught error in '$overlayKey': ${t.message}", t)

                recordFailure(overlayKey, stackTrace, context)
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            runnable.run()
        } else {
            mainHandler.post(runnable)
        }
    }

    private fun recordFailure(overlayKey: String, stackTrace: String, context: Context) {
        val now = System.currentTimeMillis()
        val list = failureTimestamps.computeIfAbsent(overlayKey) { mutableListOf() }
        synchronized(list) {
            list.removeAll { now - it > TIME_WINDOW_MS }
            list.add(now)

            val count = list.size
            val diag = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()
            diag?.log(TAG, "Overlay error in '$overlayKey' ($count/$MAX_FAILURES within 5m):\n$stackTrace")

            if (count >= MAX_FAILURES) {
                Log.w(TAG, "Overlay '$overlayKey' exceeded failure threshold ($count). Auto-disabling.")
                autoDisableOverlay(overlayKey, context, stackTrace)
                list.clear()
            }
        }
    }

    private fun autoDisableOverlay(overlayKey: String, context: Context, lastError: String) {
        val preferences = AuraPreferences(context)
        val overlayName = when (overlayKey) {
            "mini_orb" -> "Mini Orb"
            "edge_lighting" -> "Edge Lighting"
            "floating_captions" -> "Floating Captions"
            else -> overlayKey
        }

        scope.launch {
            try {
                when (overlayKey) {
                    "mini_orb" -> preferences.setOverlayEnabled(false)
                    "edge_lighting" -> preferences.setEdgeLightingEnabled(false)
                    "floating_captions" -> preferences.setFloatingCaptionsEnabled(false)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed updating preference for disabled overlay: ${e.message}", e)
            }
        }

        postNotification(context, overlayKey, overlayName, lastError)
    }

    private fun postNotification(context: Context, overlayKey: String, overlayName: String, errorText: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val chan = NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Protection",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Alerts when an overlay is turned off due to rendering errors"
                }
                nm.createNotificationChannel(chan)
            }

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("overlay_disabled_notice", overlayName)
                putExtra("overlay_error_text", errorText)
            }
            val pi = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID_BASE + overlayKey.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("$overlayName turned off")
                .setContentText("Overlay $overlayName was turned off after an error (tap for details)")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()

            nm.notify(NOTIFICATION_ID_BASE + overlayKey.hashCode(), notification)
        } catch (e: Throwable) {
            Log.e(TAG, "Error posting OverlayGuard notification: ${e.message}", e)
        }
    }
}
