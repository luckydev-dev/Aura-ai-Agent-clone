package com.example.core.tools

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.core.root.RootManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class AppEntry(
    val label: String,
    val packageName: String,
    val isSystem: Boolean,
    val isEnabled: Boolean,
    val versionName: String
)

class AppInventory(
    private val context: Context,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "Aura"
        private const val MAX_PROMPT_CHARS = 15000

        val BUILTIN_ALIASES = mapOf(
            "wa" to "com.whatsapp",
            "whatsapp" to "com.whatsapp",
            "व्हाट्सएप" to "com.whatsapp",
            "insta" to "com.instagram.android",
            "instagram" to "com.instagram.android",
            "yt" to "com.google.android.youtube",
            "youtube" to "com.google.android.youtube",
            "gpay" to "com.google.android.apps.nbu.paisa.user",
            "google pay" to "com.google.android.apps.nbu.paisa.user",
            "phonepe" to "com.phonepe.app",
            "paytm" to "net.one97.paytm",
            "chrome" to "com.android.chrome",
            "maps" to "com.google.android.apps.maps",
            "gmail" to "com.google.android.gm",
            "camera" to "camera",
            "gallery" to "gallery",
            "photos" to "com.google.android.apps.photos",
            "clock" to "clock",
            "alarm" to "clock",
            "settings" to "com.android.settings",
            "telegram" to "org.telegram.messenger",
            "spotify" to "com.spotify.music"
        )
    }

    private val inventoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _defaultApps = MutableStateFlow<Map<String, String>>(emptyMap())
    val defaultApps: StateFlow<Map<String, String>> = _defaultApps.asStateFlow()

    private val _formattedPromptSummary = MutableStateFlow("")
    val formattedPromptSummary: StateFlow<String> = _formattedPromptSummary.asStateFlow()

    private var packageReceiver: BroadcastReceiver? = null

    init {
        registerPackageReceiver()
        rebuildInventoryAsync()
    }

    private fun registerPackageReceiver() {
        try {
            packageReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    Log.i(TAG, "Package event received (${intent?.action}). Rebuilding AppInventory...")
                    rebuildInventoryAsync()
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            }
            context.registerReceiver(packageReceiver, filter)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register package broadcast receiver: ${e.message}", e)
        }
    }

    fun rebuildInventoryAsync() {
        inventoryScope.launch {
            rebuildInventory()
        }
    }

    suspend fun rebuildInventory(): List<AppEntry> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val discovered = mutableMapOf<String, AppEntry>()

        try {
            // 1. Query all activities with MAIN + LAUNCHER
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(mainIntent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(mainIntent, PackageManager.MATCH_ALL)
            }

            for (info in resolveInfos) {
                val pkg = info.activityInfo.packageName
                val label = info.loadLabel(pm)?.toString() ?: pkg
                val isSys = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val enabled = info.activityInfo.isEnabled && info.activityInfo.applicationInfo.enabled
                val vName = try {
                    pm.getPackageInfo(pkg, 0).versionName ?: "1.0"
                } catch (e: Exception) {
                    "1.0"
                }
                discovered[pkg] = AppEntry(label, pkg, isSys, enabled, vName)
            }

            // 2. Query all installed applications to capture background and disabled apps
            val allInstalled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
            }

            for (app in allInstalled) {
                if (!discovered.containsKey(app.packageName)) {
                    val label = pm.getApplicationLabel(app).toString()
                    val isSys = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val vName = try {
                        pm.getPackageInfo(app.packageName, 0).versionName ?: "1.0"
                    } catch (e: Exception) {
                        "1.0"
                    }
                    discovered[app.packageName] = AppEntry(label, app.packageName, isSys, app.enabled, vName)
                }
            }

            // 3. Fallback via root if PackageManager returned very few apps
            if (discovered.size < 15) {
                val rootRes = rootManager.execute("pm list packages -f -U")
                if (rootRes.success && rootRes.stdout.isNotBlank()) {
                    rootRes.stdout.lines().forEach { line ->
                        val parts = line.removePrefix("package:").split("=")
                        if (parts.size >= 2) {
                            val pkg = parts.last().trim()
                            if (!discovered.containsKey(pkg) && pkg.isNotBlank()) {
                                val isSys = parts.first().startsWith("/system") || parts.first().startsWith("/product")
                                discovered[pkg] = AppEntry(pkg, pkg, isSys, true, "1.0")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error discovering apps in AppInventory: ${e.message}", e)
        }

        val sortedList = discovered.values.sortedBy { it.label.lowercase(Locale.ROOT) }
        _apps.value = sortedList

        // Discover default apps
        val defaults = resolveDefaultApps(pm)
        _defaultApps.value = defaults

        // Build compact string representation for system prompt
        val summary = buildPromptSummary(sortedList, defaults)
        _formattedPromptSummary.value = summary

        Log.i(TAG, "AppInventory ready: ${sortedList.size} apps found (${sortedList.count { !it.isSystem }} user, ${sortedList.count { it.isSystem }} system)")
        sortedList
    }

    private fun resolveDefaultApps(pm: PackageManager): Map<String, String> {
        val map = mutableMapOf<String, String>()

        fun findDefault(intent: Intent, roleName: String) {
            val resolve = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            val pkg = resolve?.activityInfo?.packageName
            if (!pkg.isNullOrBlank() && pkg != "android") {
                map[roleName] = pkg
            }
        }

        findDefault(Intent(Intent.ACTION_DIAL), "dialer")
        findDefault(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:")), "sms")
        findDefault(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://google.com")), "browser")
        findDefault(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE), "camera")
        findDefault(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), "launcher")

        return map
    }

    private fun buildPromptSummary(appsList: List<AppEntry>, defaults: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("=== INSTALLED APPS INVENTORY ===\n")
        sb.append("Use ONLY packages from this inventory. If an app is not in this list, say it is not installed.\n\n")

        sb.append("DEFAULTS:\n")
        defaults.forEach { (role, pkg) ->
            sb.append("- $role: $pkg\n")
        }
        sb.append("\n")

        val userApps = appsList.filter { !it.isSystem }
        val systemApps = appsList.filter { it.isSystem }

        sb.append("USER APPS (${userApps.size}):\n")
        for (app in userApps) {
            sb.append("${app.label}|${app.packageName}|U\n")
        }

        sb.append("\nSYSTEM APPS (${systemApps.size}):\n")
        for (app in systemApps) {
            if (sb.length > MAX_PROMPT_CHARS - 1000) {
                sb.append("... [remaining system apps truncated; use list_installed_apps tool]\n")
                break
            }
            sb.append("${app.label}|${app.packageName}|S\n")
        }

        return sb.toString().take(MAX_PROMPT_CHARS)
    }

    /**
     * Fuzzy finds an application package from query string using:
     * 1. Exact package match
     * 2. Builtin alias map (Hinglish/abbreviations: wa, insta, yt, gpay, paytm, etc.)
     * 3. Normalized label match
     * 4. Levenshtein edit distance
     */
    fun findApp(query: String): AppMatchResult {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isEmpty()) return AppMatchResult.NotFound

        val currentApps = _apps.value

        // 1. Exact package match
        val exactPkg = currentApps.firstOrNull { it.packageName.lowercase(Locale.ROOT) == q }
        if (exactPkg != null) return AppMatchResult.Found(exactPkg)

        // 2. Builtin Alias Match
        val aliasPkg = BUILTIN_ALIASES[q]
        if (aliasPkg != null) {
            val app = currentApps.firstOrNull { it.packageName.lowercase(Locale.ROOT) == aliasPkg }
            if (app != null) return AppMatchResult.Found(app)
        }

        // 3. Exact label match
        val exactLabel = currentApps.firstOrNull { it.label.lowercase(Locale.ROOT) == q }
        if (exactLabel != null) return AppMatchResult.Found(exactLabel)

        // 4. Substring label matches
        val candidates = currentApps.filter {
            it.label.lowercase(Locale.ROOT).contains(q) || q.contains(it.label.lowercase(Locale.ROOT))
        }

        if (candidates.size == 1) {
            return AppMatchResult.Found(candidates.first())
        } else if (candidates.size > 1) {
            return AppMatchResult.Ambiguous(candidates)
        }

        // 5. Levenshtein distance on labels
        val closeMatches = currentApps.map { app ->
            val dist = levenshtein(q, app.label.lowercase(Locale.ROOT))
            Pair(app, dist)
        }.filter { it.second <= 2 }
        .sortedBy { it.second }

        if (closeMatches.size == 1) {
            return AppMatchResult.Found(closeMatches.first().first)
        } else if (closeMatches.isNotEmpty()) {
            return AppMatchResult.Ambiguous(closeMatches.map { it.first })
        }

        return AppMatchResult.NotFound
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        val lhsLength = lhs.length
        val rhsLength = rhs.length
        var cost = IntArray(lhsLength + 1) { it }
        var newCost = IntArray(lhsLength + 1) { 0 }

        for (i in 1..rhsLength) {
            newCost[0] = i
            for (j in 1..lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[lhsLength]
    }

    suspend fun openApp(nameOrPkg: String): Boolean {
        val trimmed = nameOrPkg.trim()
        if (trimmed.isEmpty()) return false
        val pm = context.packageManager
        val directIntent = pm.getLaunchIntentForPackage(trimmed)
        if (directIntent != null) {
            directIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(directIntent)
            return true
        }

        val match = findApp(trimmed)
        if (match is AppMatchResult.Found) {
            val launchIntent = pm.getLaunchIntentForPackage(match.app.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                return true
            }
        }

        val targetPkg = if (match is AppMatchResult.Found) match.app.packageName else trimmed
        val res = rootManager.execute("monkey -p $targetPkg -c android.intent.category.LAUNCHER 1")
        return res.success
    }

    fun destroy() {
        packageReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering package receiver: ${e.message}", e)
            }
        }
        inventoryScope.cancel()
    }
}

sealed class AppMatchResult {
    data class Found(val app: AppEntry) : AppMatchResult()
    data class Ambiguous(val candidates: List<AppEntry>) : AppMatchResult()
    object NotFound : AppMatchResult()
}
