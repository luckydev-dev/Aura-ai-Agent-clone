package com.example.core.root

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.service.AuraAccessibilityService
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class PermissionCheckItem(
    val id: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val isCritical: Boolean = true,
    val fixAction: suspend () -> Boolean
)

data class PermissionReportItem(
    val name: String,
    val status: String, // "granted", "missing", "fixed"
    val autoFixed: Boolean
)

data class PermissionDiagnosis(
    val hasIssues: Boolean,
    val reports: List<PermissionReportItem>,
    val anyFixed: Boolean,
    val userActionNeeded: String?
)

class PermissionDoctor(
    private val context: Context,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "Aura"
    }

    private val _lastReport = MutableStateFlow<String>("No issues detected")
    val lastReport: StateFlow<String> = _lastReport.asStateFlow()

    fun isAccessibilityRunning(): Boolean = AuraAccessibilityService.isRunning()
    fun isNotificationListenerRunning(): Boolean = AuraNotificationListener.isRunning()

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    fun isBatteryOptimizationIgnored(): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    suspend fun getAllCheckItems(): List<PermissionCheckItem> = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val items = mutableListOf<PermissionCheckItem>()

        // 1. Root Access
        val isRoot = rootManager.isRootAvailable()
        items.add(
            PermissionCheckItem(
                id = "ROOT",
                title = "Root Access (su)",
                description = "Required for system commands, zero-touch granting, and deep control.",
                isGranted = isRoot,
                isCritical = true,
                fixAction = { rootManager.requestRoot() }
            )
        )

        // 2. Microphone
        items.add(
            PermissionCheckItem(
                id = "RECORD_AUDIO",
                title = "Microphone Permission",
                description = "Required for voice streaming and keyword spotting.",
                isGranted = hasPermission("android.permission.RECORD_AUDIO"),
                isCritical = true,
                fixAction = { grantRuntimePermission("android.permission.RECORD_AUDIO") }
            )
        )

        // 3. Accessibility Service
        val accessBound = isAccessibilityRunning()
        items.add(
            PermissionCheckItem(
                id = "ACCESSIBILITY",
                title = "Aura Accessibility Service",
                description = "Required for screen observation, reading UI, and tapping elements.",
                isGranted = accessBound,
                isCritical = true,
                fixAction = { fixAccessibilityService() }
            )
        )

        // 4. Notification Listener
        val notifBound = isNotificationListenerRunning()
        items.add(
            PermissionCheckItem(
                id = "NOTIFICATION_LISTENER",
                title = "Notification Listener Service",
                description = "Allows Aura to monitor and read incoming notifications.",
                isGranted = notifBound,
                isCritical = false,
                fixAction = { fixNotificationListener() }
            )
        )

        // 5. Draw Over Other Apps (Overlay)
        val overlayAllowed = canDrawOverlays()
        items.add(
            PermissionCheckItem(
                id = "SYSTEM_ALERT_WINDOW",
                title = "Display Over Other Apps",
                description = "Required for edge lighting and floating mini-orb overlays.",
                isGranted = overlayAllowed,
                isCritical = false,
                fixAction = {
                    rootManager.execute("appops set $pkg SYSTEM_ALERT_WINDOW allow")
                    canDrawOverlays()
                }
            )
        )

        // 6. Battery Optimization
        val batteryIgnored = isBatteryOptimizationIgnored()
        items.add(
            PermissionCheckItem(
                id = "BATTERY_OPTIMIZATION",
                title = "Battery Optimization Exemption",
                description = "Prevents Android from killing Aura in the background.",
                isGranted = batteryIgnored,
                isCritical = true,
                fixAction = {
                    rootManager.execute("dumpsys deviceidle whitelist +$pkg")
                    isBatteryOptimizationIgnored()
                }
            )
        )

        // 7. Contacts, Phone & SMS
        val commPerms = listOf(
            "android.permission.READ_CONTACTS",
            "android.permission.CALL_PHONE",
            "android.permission.SEND_SMS",
            "android.permission.READ_SMS",
            "android.permission.READ_CALL_LOG"
        )
        val allCommGranted = commPerms.all { hasPermission(it) }
        items.add(
            PermissionCheckItem(
                id = "COMMUNICATIONS",
                title = "Contacts, Phone & SMS",
                description = "Required for voice calling, WhatsApp messaging, and SMS automation.",
                isGranted = allCommGranted,
                isCritical = false,
                fixAction = {
                    var allOk = true
                    for (p in commPerms) {
                        if (!grantRuntimePermission(p)) allOk = false
                    }
                    allOk
                }
            )
        )

        // 8. Camera
        items.add(
            PermissionCheckItem(
                id = "CAMERA",
                title = "Camera Permission",
                description = "Required for photo capture and visual tools.",
                isGranted = hasPermission("android.permission.CAMERA"),
                isCritical = false,
                fixAction = { grantRuntimePermission("android.permission.CAMERA") }
            )
        )

        // 9. Touch Pass-Through Test (Android 12+ Overlay Alpha Check)
        val attachedOverlays = com.example.ui.overlay.OverlayWindowDefaults.getAttachedWindows()
        val allPassThroughValid = attachedOverlays.none { it.value > com.example.ui.overlay.OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA }
        items.add(
            PermissionCheckItem(
                id = "TOUCH_PASSTHROUGH",
                title = "Touch Pass-Through Integrity",
                description = "Guarantees all overlay windows use opacity <= 0.79 to prevent Android 12+ untrusted touch blocking.",
                isGranted = allPassThroughValid,
                isCritical = true,
                fixAction = {
                    com.example.ui.overlay.OverlayWindowDefaults.getAttachedWindows().none { it.value > com.example.ui.overlay.OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA }
                }
            )
        )

        items
    }

    suspend fun grantRuntimePermission(perm: String): Boolean {
        val res = rootManager.execute("pm grant ${context.packageName} $perm")
        return hasPermission(perm) || res.success
    }

    suspend fun fixAccessibilityService(): Boolean {
        val accessComponent = ComponentName(context, AuraAccessibilityService::class.java).flattenToString()
        val cur = rootManager.execute("settings get secure enabled_accessibility_services").stdout.trim()
        val newServices = if (cur.isNotBlank() && cur != "null") {
            if (cur.contains(accessComponent)) cur else "$cur:$accessComponent"
        } else {
            accessComponent
        }
        rootManager.execute("settings put secure enabled_accessibility_services $newServices")
        rootManager.execute("settings put secure accessibility_enabled 1")
        delay(600)
        return isAccessibilityRunning()
    }

    suspend fun fixNotificationListener(): Boolean {
        val notifComponent = ComponentName(context, AuraNotificationListener::class.java).flattenToString()
        rootManager.execute("cmd notification allow_listener $notifComponent")
        val cur = rootManager.execute("settings get secure enabled_notification_listeners").stdout.trim()
        val newNotifs = if (cur.isNotBlank() && cur != "null") {
            if (cur.contains(notifComponent)) cur else "$cur:$notifComponent"
        } else {
            notifComponent
        }
        rootManager.execute("settings put secure enabled_notification_listeners $newNotifs")
        delay(600)
        return isNotificationListenerRunning()
    }

    /**
     * OEM-specific auto-fixes for Xiaomi / HyperOS / Oppo / Vivo devices.
     */
    suspend fun applyOemFixes() {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val pkg = context.packageName
        if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
            Log.i(TAG, "Applying Xiaomi/HyperOS specific background permissions via root")
            rootManager.execute("appops set $pkg AUTO_START allow")
            rootManager.execute("appops set $pkg SHOW_WHEN_LOCKED allow")
            rootManager.execute("appops set $pkg BACKGROUND_START_ACTIVITY allow")
        }
    }

    /**
     * Diagnoses failure for a specific tool and attempts root auto-fix with re-verification.
     */
    suspend fun diagnoseAndAutoFix(
        toolName: String,
        args: Map<String, Any?>,
        errorMsg: String
    ): PermissionDiagnosis = withContext(Dispatchers.IO) {
        val reports = mutableListOf<PermissionReportItem>()
        var anyFixed = false
        var userActionNeeded: String? = null

        val isScreenTool = toolName in listOf("read_screen", "tap_element", "type_text", "swipe", "press_key")
        val isNotifTool = toolName in listOf("get_notifications", "reply_notification")
        val isCallTool = toolName in listOf("make_call", "search_contacts")
        val isSmsTool = toolName in listOf("send_sms", "read_sms")

        if (isScreenTool && !isAccessibilityRunning()) {
            reports.add(PermissionReportItem("ACCESSIBILITY", "missing", false))
            val fixed = fixAccessibilityService()
            if (fixed) {
                anyFixed = true
                reports[reports.lastIndex] = PermissionReportItem("ACCESSIBILITY", "fixed", true)
            } else {
                userActionNeeded = "Accessibility service is disabled. Please enable Aura in Android Accessibility Settings."
            }
        }

        if (isNotifTool && !isNotificationListenerRunning()) {
            reports.add(PermissionReportItem("NOTIFICATION_LISTENER", "missing", false))
            val fixed = fixNotificationListener()
            if (fixed) {
                anyFixed = true
                reports[reports.lastIndex] = PermissionReportItem("NOTIFICATION_LISTENER", "fixed", true)
            } else {
                userActionNeeded = "Notification listener is disabled. Please enable Aura in Notification Access Settings."
            }
        }

        if (isCallTool && !hasPermission("android.permission.CALL_PHONE")) {
            val fixed = grantRuntimePermission("android.permission.CALL_PHONE")
            reports.add(PermissionReportItem("CALL_PHONE", if (fixed) "fixed" else "missing", fixed))
            if (fixed) anyFixed = true else userActionNeeded = "Call permission required."
        }

        if (isSmsTool && !hasPermission("android.permission.SEND_SMS")) {
            val fixed = grantRuntimePermission("android.permission.SEND_SMS")
            reports.add(PermissionReportItem("SEND_SMS", if (fixed) "fixed" else "missing", fixed))
            if (fixed) anyFixed = true else userActionNeeded = "SMS permission required."
        }

        applyOemFixes()

        val summary = if (anyFixed) {
            "Auto-fixed: ${reports.filter { it.autoFixed }.joinToString { it.name }}"
        } else if (reports.isNotEmpty()) {
            "Issues remaining: ${reports.joinToString { "${it.name}=${it.status}" }}"
        } else {
            "No permission issues found for '$toolName'"
        }
        _lastReport.value = summary

        PermissionDiagnosis(
            hasIssues = reports.isNotEmpty(),
            reports = reports,
            anyFixed = anyFixed,
            userActionNeeded = userActionNeeded
        )
    }

    /**
     * Silent health check run immediately after connecting.
     */
    suspend fun runSilentHealthCheck() = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        if (rootManager.isRootAvailable()) {
            if (!canDrawOverlays()) {
                rootManager.execute("appops set $pkg SYSTEM_ALERT_WINDOW allow")
            }
            if (!isBatteryOptimizationIgnored()) {
                rootManager.execute("dumpsys deviceidle whitelist +$pkg")
            }
            if (!hasPermission("android.permission.RECORD_AUDIO")) {
                grantRuntimePermission("android.permission.RECORD_AUDIO")
            }
            applyOemFixes()
        }
    }

    /**
     * One-tap master root fixer: grants all runtime permissions, enables services,
     * whitelist battery, enables overlays, and applies OEM fixes.
     */
    suspend fun fixAll(): Boolean = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        var allSuccess = true

        try {
            // 1. Overlays
            rootManager.execute("appops set $pkg SYSTEM_ALERT_WINDOW allow")

            // 2. Battery optimization
            rootManager.execute("dumpsys deviceidle whitelist +$pkg")

            // 3. Runtime permissions
            val permissionsToGrant = mutableListOf(
                "android.permission.RECORD_AUDIO",
                "android.permission.CAMERA",
                "android.permission.READ_CONTACTS",
                "android.permission.CALL_PHONE",
                "android.permission.SEND_SMS",
                "android.permission.READ_SMS",
                "android.permission.READ_CALL_LOG",
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION"
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissionsToGrant.add("android.permission.POST_NOTIFICATIONS")
            }

            for (perm in permissionsToGrant) {
                grantRuntimePermission(perm)
            }

            // 4. Accessibility service
            fixAccessibilityService()

            // 5. Notification listener
            fixNotificationListener()

            // 6. OEM background fixes
            applyOemFixes()

            _lastReport.value = "All permissions granted & services enabled via Root"
            true
        } catch (e: Exception) {
            Log.e(TAG, "fixAll failed: ${e.message}", e)
            false
        }
    }

    /**
     * Builds structured JSON string for model responses.
     */
    fun buildStructuredJson(
        success: Boolean,
        reason: String,
        diagnosis: PermissionDiagnosis?,
        retried: Boolean
    ): String {
        val root = JSONObject().apply {
            put("success", success)
            put("reason", reason)
            put("retried", retried)
            put("user_action_needed", diagnosis?.userActionNeeded ?: JSONObject.NULL)

            val repArr = JSONArray()
            diagnosis?.reports?.forEach { r ->
                repArr.put(JSONObject().apply {
                    put("name", r.name)
                    put("status", r.status)
                    put("auto_fixed", r.autoFixed)
                })
            }
            put("permission_report", repArr)
        }
        return root.toString()
    }
}
