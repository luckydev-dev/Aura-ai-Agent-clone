package com.example.core.root

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.service.AuraAccessibilityService
import com.example.service.AuraNotificationListener
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class RootManager(private val context: Context) {
    companion object {
        private const val TAG = "Aura"
        @Volatile
        private var isBuilderConfigured = false

        @Synchronized
        fun ensureConfigured() {
            if (isBuilderConfigured) return
            try {
                Shell.enableVerboseLogging = false
                Shell.setDefaultBuilder(
                    Shell.Builder.create()
                        .setFlags(Shell.FLAG_REDIRECT_STDERR)
                        .setTimeout(15)
                )
                isBuilderConfigured = true
            } catch (e: IllegalStateException) {
                // Main shell was already created by LibSU; builder cannot and does not need to be reset
                isBuilderConfigured = true
            } catch (e: Exception) {
                Log.e(TAG, "Error initializing Shell builder: ${e.message}", e)
            }
        }
    }

    init {
        ensureConfigured()
    }

    /**
     * Checks if root access is available and granted.
     */
    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (Shell.isAppGrantedRoot() == true) return@withContext true
            val shell = Shell.getShell()
            if (shell.isRoot) return@withContext true
            false
        }.getOrElse {
            checkSuBinaryDirect()
        }
    }

    /**
     * Requests root access via prompt if not yet granted.
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val result = execute("id")
            result.success && result.stdout.contains("uid=0")
        }.getOrDefault(false)
    }

    /**
     * Diagnostic string indicating root type (Magisk, KernelSU, APatch, or None).
     */
    suspend fun getDiagnosticInfo(): String = withContext(Dispatchers.IO) {
        runCatching {
            val magiskCheck = execute("magisk -v")
            if (magiskCheck.success && magiskCheck.stdout.isNotBlank()) {
                return@withContext "Magisk detected (${magiskCheck.stdout.trim()})"
            }
            val ksuCheck = execute("which ksu || which ksud")
            if (ksuCheck.success && ksuCheck.stdout.isNotBlank()) {
                return@withContext "KernelSU detected"
            }
            val apatchCheck = execute("which apd")
            if (apatchCheck.success && apatchCheck.stdout.isNotBlank()) {
                return@withContext "APatch detected"
            }
            val suCheck = execute("which su")
            if (suCheck.success && suCheck.stdout.isNotBlank()) {
                return@withContext "Root available (su at ${suCheck.stdout.trim()})"
            }
            "No su binary found. Device may not be rooted."
        }.getOrDefault("Root diagnostic unavailable.")
    }

    /**
     * Executes a command via root shell with a timeout.
     */
    suspend fun execute(command: String, timeoutMs: Long = 10000L): RootCommandResult = withContext(Dispatchers.IO) {
        runCatching {
            val output = mutableListOf<String>()
            val result = Shell.cmd(command).to(output).exec()
            val stdout = output.joinToString("\n")
            RootCommandResult(
                stdout = stdout,
                stderr = if (!result.isSuccess) stdout else "",
                exitCode = result.code,
                success = result.isSuccess
            )
        }.getOrElse {
            fallbackExecute(command, timeoutMs)
        }
    }

    private fun fallbackExecute(command: String, timeoutMs: Long): RootCommandResult {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val sb = StringBuilder()
            val completed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            } else {
                process.waitFor()
                true
            }
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line).append("\n")
            }
            if (!completed) {
                process.destroyForcibly()
                return RootCommandResult("", "Command timed out", -1, false)
            }
            val exitCode = process.exitValue()
            RootCommandResult(
                stdout = sb.toString().trim(),
                stderr = if (exitCode != 0) sb.toString().trim() else "",
                exitCode = exitCode,
                success = exitCode == 0
            )
        } catch (ex: Exception) {
            RootCommandResult("", ex.message ?: "Unknown error", -1, false)
        }
    }

    private fun checkSuBinaryDirect(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            val line = reader.readLine()
            p.waitFor()
            p.exitValue() == 0 && line?.contains("uid=0") == true
        } catch (e: Exception) {
            Log.e(TAG, "checkSuBinaryDirect failed", e)
            false
        }
    }

    /**
     * Auto-grants all required permissions, settings, and services in one go via root.
     * Uses accurate ComponentNames derived from class references (handling decoupled package names).
     */
    suspend fun autoGrantEverything(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val results = mutableMapOf<String, Boolean>()

        val permissions = listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.READ_CONTACTS",
            "android.permission.CALL_PHONE",
            "android.permission.SEND_SMS",
            "android.permission.READ_SMS",
            "android.permission.READ_CALL_LOG",
            "android.permission.CAMERA",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WRITE_SECURE_SETTINGS"
        )

        for (perm in permissions) {
            val res = execute("pm grant $pkg $perm")
            results[perm] = res.success
        }

        // SYSTEM_ALERT_WINDOW (draw over other apps)
        val alertRes = execute("appops set $pkg SYSTEM_ALERT_WINDOW allow")
        results["SYSTEM_ALERT_WINDOW"] = alertRes.success

        // Battery optimization exemption (whitelist)
        val batteryRes = execute("dumpsys deviceidle whitelist +$pkg")
        results["BATTERY_OPTIMIZATION"] = batteryRes.success

        // Disable Android 12+ untrusted touch blocking
        execute("settings put global block_untrusted_touches 0")

        // Enable Accessibility Service with real ComponentName
        val accessComponent = ComponentName(context, AuraAccessibilityService::class.java).flattenToString()
        val existingServices = execute("settings get secure enabled_accessibility_services").stdout.trim()
        val newServices = if (existingServices.isNotBlank() && existingServices != "null") {
            if (existingServices.contains(accessComponent)) existingServices else "$existingServices:$accessComponent"
        } else {
            accessComponent
        }
        val setAccess = execute("settings put secure enabled_accessibility_services $newServices")
        execute("settings put secure accessibility_enabled 1")

        // Enable Notification Listener with real ComponentName
        val notifComponent = ComponentName(context, AuraNotificationListener::class.java).flattenToString()
        val notifRes = execute("cmd notification allow_listener $notifComponent")

        val existingNotifs = execute("settings get secure enabled_notification_listeners").stdout.trim()
        val newNotifs = if (existingNotifs.isNotBlank() && existingNotifs != "null") {
            if (existingNotifs.contains(notifComponent)) existingNotifs else "$existingNotifs:$notifComponent"
        } else {
            notifComponent
        }
        execute("settings put secure enabled_notification_listeners $newNotifs")

        // Brief delay to allow system service binding
        delay(500)

        // Verify status
        val verifiedServices = execute("settings get secure enabled_accessibility_services").stdout.trim()
        val isAccessEnabled = verifiedServices.contains(accessComponent) || AuraAccessibilityService.isRunning()
        results["ACCESSIBILITY_SERVICE"] = setAccess.success && isAccessEnabled

        val isNotifRunning = AuraNotificationListener.isRunning()
        results["NOTIFICATION_LISTENER"] = notifRes.success || isNotifRunning

        results
    }
}
