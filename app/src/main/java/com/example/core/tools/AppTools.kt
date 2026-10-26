package com.example.core.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.example.core.root.RootManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class AppTools(
    private val context: Context,
    private val rootManager: RootManager,
    private val appInventory: AppInventory
) {

    val openAppTool = object : Tool {
        override val name = "open_app"
        override val description = "Opens an installed Android app using exact name, package, or alias (e.g. 'wa', 'yt', 'insta')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name_or_package" to mapOf(
                    "type" to "string",
                    "description" to "The name of the app (e.g. 'WhatsApp', 'Chrome', 'YouTube', 'insta') or its package name."
                )
            ),
            "required" to listOf("name_or_package")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["name_or_package"]?.toString()?.trim() ?: return ToolExecutionResult(false, "Missing app name")

            val match = appInventory.findApp(query)
            val pkgToLaunch = when (match) {
                is AppMatchResult.Found -> match.app.packageName
                is AppMatchResult.Ambiguous -> {
                    val candidateNames = match.candidates.take(5).joinToString(", ") { "${it.label} (${it.packageName})" }
                    return ToolExecutionResult(false, "Multiple apps matched '$query': $candidateNames. Which one should I open?")
                }
                AppMatchResult.NotFound -> {
                    return ToolExecutionResult(false, "App '$query' is not installed on this device.")
                }
            }

            // Launch via PackageManager launch intent
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(pkgToLaunch)
            return if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                ToolExecutionResult(true, "Opened $pkgToLaunch successfully")
            } else {
                // Root monkey fallback
                val res = rootManager.execute("monkey -p $pkgToLaunch -c android.intent.category.LAUNCHER 1")
                if (res.success) {
                    ToolExecutionResult(true, "Started $pkgToLaunch via system launcher")
                } else {
                    ToolExecutionResult(false, "Failed to start $pkgToLaunch: ${res.stderr}")
                }
            }
        }
    }

    val closeAppTool = object : Tool {
        override val name = "close_app"
        override val description = "Closes or force-stops an app by its name or package."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "The name or package of the app to close.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val pkg = when (val m = appInventory.findApp(name)) {
                is AppMatchResult.Found -> m.app.packageName
                else -> name.trim()
            }
            val res = rootManager.execute("am force-stop $pkg")
            return if (res.success) {
                ToolExecutionResult(true, "Force stopped $pkg")
            } else {
                ToolExecutionResult(false, "Failed to stop $pkg: ${res.stderr}")
            }
        }
    }

    val listInstalledAppsTool = object : Tool {
        override val name = "list_installed_apps"
        override val description = "Lists installed apps with full pagination, filtering, and system app options."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "filter" to mapOf("type" to "string", "description" to "Optional search filter."),
                "include_system" to mapOf("type" to "boolean", "description" to "Whether to include system apps (default true)."),
                "only_launchable" to mapOf("type" to "boolean", "description" to "Only apps that can be opened by user (default true)."),
                "limit" to mapOf("type" to "integer", "description" to "Maximum items to return (default 50, max 200)."),
                "offset" to mapOf("type" to "integer", "description" to "Pagination offset index (default 0).")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val filter = args["filter"]?.toString()?.lowercase(Locale.ROOT) ?: ""
            val includeSystem = (args["include_system"] as? Boolean) ?: true
            val limit = (args["limit"] as? Number)?.toInt()?.coerceIn(1, 200) ?: 50
            val offset = (args["offset"] as? Number)?.toInt()?.coerceAtLeast(0) ?: 0

            var appList = appInventory.apps.value
            if (!includeSystem) {
                appList = appList.filter { !it.isSystem }
            }
            if (filter.isNotBlank()) {
                appList = appList.filter {
                    it.label.lowercase(Locale.ROOT).contains(filter) ||
                            it.packageName.lowercase(Locale.ROOT).contains(filter)
                }
            }

            val total = appList.size
            val paged = appList.drop(offset).take(limit)

            val lines = paged.map { "${it.label} (${it.packageName}) [${if (it.isSystem) "System" else "User"}]" }
            val header = "Showing ${paged.size} of $total apps (offset $offset, limit $limit):\n"
            return ToolExecutionResult(true, header + lines.joinToString("\n"))
        }
    }

    val getAppInfoTool = object : Tool {
        override val name = "get_app_info"
        override val description = "Gets detailed package info: version, system flag, enabled state, and granted permissions."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "package_name" to mapOf("type" to "string", "description" to "Exact package name or app name.")
            ),
            "required" to listOf("package_name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val input = args["package_name"]?.toString()?.trim() ?: return ToolExecutionResult(false, "Missing package_name")
            val pkg = when (val m = appInventory.findApp(input)) {
                is AppMatchResult.Found -> m.app.packageName
                else -> input
            }

            val pm = context.packageManager
            return try {
                val pkgInfo = pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
                val appInfo = pkgInfo.applicationInfo ?: return ToolExecutionResult(false, "ApplicationInfo null for $pkg")

                val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val grantedPerms = mutableListOf<String>()
                val requested = pkgInfo.requestedPermissions
                val flags = pkgInfo.requestedPermissionsFlags

                if (requested != null && flags != null) {
                    for (i in requested.indices) {
                        if ((flags[i] and PackageManager.PERMISSION_GRANTED) != 0) {
                            grantedPerms.add(requested[i].removePrefix("android.permission."))
                        }
                    }
                }

                val obj = JSONObject().apply {
                    put("package", pkg)
                    put("label", pm.getApplicationLabel(appInfo).toString())
                    put("versionName", pkgInfo.versionName ?: "Unknown")
                    put("isSystem", isSys)
                    put("isEnabled", appInfo.enabled)
                    put("grantedPermissionsCount", grantedPerms.size)
                    put("samplePermissions", JSONArray(grantedPerms.take(15)))
                }

                ToolExecutionResult(true, obj.toString(2))
            } catch (e: Exception) {
                ToolExecutionResult(false, "Package $pkg not found on device: ${e.message}")
            }
        }
    }

    val listSystemPackagesTool = object : Tool {
        override val name = "list_system_packages"
        override val description = "Lists raw system packages installed via root pm command."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "filter" to mapOf("type" to "string", "description" to "Optional string filter.")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val filter = args["filter"]?.toString()?.trim() ?: ""
            val cmd = if (filter.isNotBlank()) "pm list packages -s | grep $filter" else "pm list packages -s"
            val res = rootManager.execute(cmd)
            return if (res.success) {
                val pkgs = res.stdout.lines().map { it.removePrefix("package:").trim() }.filter { it.isNotBlank() }
                ToolExecutionResult(true, "Found ${pkgs.size} system packages:\n" + pkgs.take(100).joinToString("\n"))
            } else {
                ToolExecutionResult(false, "Failed to list system packages: ${res.stderr}")
            }
        }
    }

    val clearAppDataTool = object : Tool {
        override val name = "clear_app_data"
        override val description = "Clears all data and cache of an app (requires user confirmation)."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "App name or package to clear.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val pkg = when (val m = appInventory.findApp(name)) {
                is AppMatchResult.Found -> m.app.packageName
                else -> name.trim()
            }
            val res = rootManager.execute("pm clear $pkg")
            return if (res.success) {
                ToolExecutionResult(true, "Cleared all data for $pkg")
            } else {
                ToolExecutionResult(false, "Failed to clear data: ${res.stderr}")
            }
        }
    }

    val clearAppCacheTool = object : Tool {
        override val name = "clear_app_cache"
        override val description = "Clears cache directory of an app."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "App name or package.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString() ?: return ToolExecutionResult(false, "Missing app name")
            val pkg = when (val m = appInventory.findApp(name)) {
                is AppMatchResult.Found -> m.app.packageName
                else -> name.trim()
            }
            val res = rootManager.execute("pm trim-caches 999G && rm -rf /data/data/$pkg/cache/*")
            return if (res.success) {
                ToolExecutionResult(true, "Cleared cache for $pkg")
            } else {
                ToolExecutionResult(false, "Failed to clear cache: ${res.stderr}")
            }
        }
    }

    val getForegroundAppTool = object : Tool {
        override val name = "get_foreground_app"
        override val description = "Gets the currently active/focused app on screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'")
            return if (res.success && res.stdout.isNotBlank()) {
                ToolExecutionResult(true, res.stdout.trim())
            } else {
                ToolExecutionResult(false, "Could not determine foreground app")
            }
        }
    }

    val goHomeTool = object : Tool {
        override val name = "go_home"
        override val description = "Navigates to the Android home screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = com.example.AuraApplication.instance.inputController.pressKey(3, "Home")
            return ToolExecutionResult(res, if (res) "Navigated to home" else "Failed to navigate home")
        }
    }

    val goBackTool = object : Tool {
        override val name = "go_back"
        override val description = "Performs back navigation button press."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = com.example.AuraApplication.instance.inputController.pressKey(4, "Back")
            return ToolExecutionResult(res, if (res) "Went back" else "Failed to go back")
        }
    }

    val openRecentsTool = object : Tool {
        override val name = "open_recents"
        override val description = "Opens the Android app switcher / recents menu."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = com.example.AuraApplication.instance.inputController.pressKey(187, "Recents")
            return ToolExecutionResult(res, if (res) "Opened recents" else "Failed to open recents")
        }
    }

    val openNotificationsTool = object : Tool {
        override val name = "open_notifications"
        override val description = "Pulls down the notification shade."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("cmd statusbar expand-notifications")
            return ToolExecutionResult(res.success, if (res.success) "Opened notifications" else res.stderr)
        }
    }

    val openQuickSettingsTool = object : Tool {
        override val name = "open_quick_settings"
        override val description = "Pulls down the quick settings panel."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = rootManager.execute("cmd statusbar expand-settings")
            return ToolExecutionResult(res.success, if (res.success) "Opened quick settings" else res.stderr)
        }
    }

    val lockScreenTool = object : Tool {
        override val name = "lock_screen"
        override val description = "Locks the device screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val a11y = com.example.service.AuraAccessibilityService.instance
            if (a11y?.lockDevice() == true) {
                return ToolExecutionResult(true, "Screen locked via accessibility")
            }
            val res = com.example.AuraApplication.instance.inputController.pressKey(223, "Lock Screen") // KEYCODE_SLEEP
            return ToolExecutionResult(res, if (res) "Screen locked" else "Failed to lock screen")
        }
    }

    val wakeScreenTool = object : Tool {
        override val name = "wake_screen"
        override val description = "Wakes up the device screen."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val res = com.example.AuraApplication.instance.inputController.pressKey(224, "Wake Screen") // KEYCODE_WAKEUP
            return ToolExecutionResult(res, if (res) "Screen awakened" else "Failed to wake screen")
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        openAppTool, closeAppTool, clearAppDataTool, clearAppCacheTool,
        listInstalledAppsTool, getAppInfoTool, listSystemPackagesTool,
        getForegroundAppTool, goHomeTool, goBackTool,
        openRecentsTool, openNotificationsTool, openQuickSettingsTool,
        lockScreenTool, wakeScreenTool
    )
}
