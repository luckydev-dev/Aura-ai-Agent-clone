package com.example.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.AuraApplication
import com.example.MainActivity
import com.example.R
import com.example.core.settings.AuraPreferences
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "Aura"
        const val BOOT_NOTIFICATION_ID = 1002
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = AuraPreferences(context)
            CoroutineScope(Dispatchers.IO).launch {
                val wakeEnabled = prefs.wakePhraseEnabled.first()
                if (wakeEnabled) {
                    Log.i(TAG, "Boot completed. Initiating WAKE_LISTENING (not a Live call)")
                    startWakeListeningWithRootFallback(context)
                }
            }
        }
    }

    private fun startWakeListeningWithRootFallback(context: Context) {
        val serviceComponent = ComponentName(context, AuraForegroundService::class.java).flattenToString()

        var started = false

        // Attempt 1: Standard startForegroundService (may fail on Android 14+ background mic restriction)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                AuraForegroundService.startWakeListening(context)
                started = true
            } catch (e: Exception) {
                Log.w(TAG, "Standard startWakeListening failed at boot: ${e.message}")
            }
        }

        // Attempt 2: Root shell start (bypasses Android 14+ background microphone restriction)
        if (!started) {
            try {
                val res = Shell.cmd("am start-foreground-service -a ${AuraForegroundService.ACTION_START_WAKE_LISTENING} -n $serviceComponent").exec()
                if (res.isSuccess) {
                    Log.i(TAG, "Started AuraForegroundService in WAKE_LISTENING mode via root shell successfully")
                    started = true
                } else {
                    Log.e(TAG, "Root am start-foreground-service failed: ${res.err}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception running root start-foreground-service: ${e.message}", e)
            }
        }

        // Attempt 3: If both failed, post user-action notification: "Tap to enable Hey Aura"
        if (!started) {
            Log.w(TAG, "Posting 'Tap to enable Hey Aura' notification due to Android 14+ background mic limits")
            val diagnostics = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()
            diagnostics?.log(TAG, "Boot start prevented by Android 14+ background limits; notification posted")

            postEnableNotification(context)
        }
    }

    private fun postEnableNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            context, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val startIntent = Intent(context, AuraForegroundService::class.java).apply {
            action = AuraForegroundService.ACTION_START_WAKE_LISTENING
        }
        val pendingStart = PendingIntent.getService(
            context, 1, startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AuraForegroundService.CHANNEL_ID)
            .setContentTitle("Aura Ready")
            .setContentText("Tap to enable 'Hey Aura' wake-word detection")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpen)
            .addAction(android.R.drawable.ic_btn_speak_now, "Enable Hey Aura", pendingStart)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        notificationManager.notify(BOOT_NOTIFICATION_ID, notification)
    }
}
