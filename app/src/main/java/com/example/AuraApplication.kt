package com.example

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.example.core.lifecycle.AppForegroundState
import com.example.core.live.LiveDiagnostics
import com.example.core.settings.AuraPreferences
import com.example.ui.overlay.OverlayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AuraApplication : Application(), Application.ActivityLifecycleCallbacks {

    val liveDiagnostics = LiveDiagnostics()

    val rootManager by lazy { com.example.core.root.RootManager(this) }
    val inputController by lazy { com.example.core.input.InputController.getInstance(this, rootManager) }

    lateinit var overlayManager: OverlayManager
        private set

    val overlayController by lazy {
        com.example.ui.overlay.OverlayController(
            context = this,
            preferences = com.example.core.settings.AuraPreferences(this),
            overlayManager = overlayManager,
            edgeLightingOverlay = com.example.ui.overlay.EdgeLightingOverlay(this)
        ).apply {
            startObserving()
        }
    }

    companion object {
        private const val TAG = "Aura"
        lateinit var instance: AuraApplication
            private set

        val isAppInForeground: Boolean
            get() = AppForegroundState.current

        // Crash breaker & Safe Mode state
        val isSafeMode = mutableStateOf(false)
        val lastCrashReport = mutableStateOf<String?>(null)
    }

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this

        checkCrashHistoryAndSafeMode()
        setupCrashHandler()
        registerActivityLifecycleCallbacks(this)

        // Pre-configure LibSU Shell once
        com.example.core.root.RootManager.ensureConfigured()

        overlayManager = OverlayManager(this)
        com.example.core.telephony.NotificationAnnouncementManager(this, AuraPreferences(this))

        appScope.launch {
            try {
                AuraPreferences(this@AuraApplication).migrateLegacyCredentialsAndEdgeLighting()
            } catch (e: Exception) {
                Log.e(TAG, "Error running legacy migration: ${e.message}", e)
            }
            try {
                rootManager.execute("settings put global block_untrusted_touches 0")
            } catch (e: Exception) {
                Log.w(TAG, "Error disabling untrusted touches on init: ${e.message}")
            }
        }
    }

    private fun checkCrashHistoryAndSafeMode() {
        val prefs = getSharedPreferences("aura_crash_meta", Context.MODE_PRIVATE)
        val lastTrace = prefs.getString("last_crash_trace", null)
        val lastTime = prefs.getLong("last_crash_timestamp", 0L)
        val isSafe = prefs.getBoolean("safe_mode_active", false)

        if (!lastTrace.isNullOrBlank() && System.currentTimeMillis() - lastTime < 24 * 60 * 60 * 1000L) {
            lastCrashReport.value = lastTrace
        }

        isSafeMode.value = isSafe
        if (isSafe) {
            Log.w(TAG, "Aura starting in SAFE MODE (overlays & auto wake word disabled)")
        }
    }

    fun dismissLastCrashReport() {
        lastCrashReport.value = null
        val prefs = getSharedPreferences("aura_crash_meta", Context.MODE_PRIVATE)
        prefs.edit().remove("last_crash_trace").apply()
    }

    fun disableSafeMode() {
        isSafeMode.value = false
        val prefs = getSharedPreferences("aura_crash_meta", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("safe_mode_active", false)
            .remove("crash_timestamps")
            .apply()

        appScope.launch {
            val auraPrefs = AuraPreferences(this@AuraApplication)
            auraPrefs.setSafeModeTriggered(false)
        }
    }

    private fun setupCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stackTrace = sw.toString()
                Log.e(TAG, "FATAL CRASH in thread ${thread.name}: $stackTrace")

                val now = System.currentTimeMillis()
                val crashFile = File(filesDir, "crash_logs.txt")
                val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))
                val logEntry = "\n--- CRASH AT $timestampStr [Thread: ${thread.name}] ---\n$stackTrace\n"

                if (crashFile.exists() && crashFile.length() > 64 * 1024) {
                    val prev = crashFile.readText().takeLast(32 * 1024)
                    crashFile.writeText(prev + logEntry)
                } else {
                    crashFile.appendText(logEntry)
                }

                // Crash loop breaker: 3 crashes within 2 minutes (120,000 ms)
                val prefs = getSharedPreferences("aura_crash_meta", Context.MODE_PRIVATE)
                val rawHistory = prefs.getString("crash_timestamps", "") ?: ""
                val recentTimestamps = rawHistory.split(",")
                    .mapNotNull { it.trim().toLongOrNull() }
                    .filter { now - it < 2 * 60 * 1000L }
                    .toMutableList()

                recentTimestamps.add(now)

                val triggerSafeMode = recentTimestamps.size >= 3

                prefs.edit()
                    .putString("last_crash_trace", stackTrace)
                    .putLong("last_crash_timestamp", now)
                    .putString("crash_timestamps", recentTimestamps.joinToString(","))
                    .putBoolean("safe_mode_active", triggerSafeMode || prefs.getBoolean("safe_mode_active", false))
                    .commit()

            } catch (e: Exception) {
                Log.e(TAG, "Failed writing crash log", e)
            } finally {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        AppForegroundState.onActivityStarted()
    }

    override fun onActivityStopped(activity: Activity) {
        AppForegroundState.onActivityStopped()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
