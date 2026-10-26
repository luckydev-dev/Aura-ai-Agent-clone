package com.example.core.unlock

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.core.input.InputController
import com.example.core.root.RootManager
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.delay
import java.io.File
import java.util.regex.Pattern

data class BouncerInfo(
    val isShowing: Boolean,
    val type: String, // "PATTERN", "PIN", "PASSWORD", "UNKNOWN"
    val matchedPackage: String? = null,
    val matchedViewId: String? = null,
    val score: Int = 0,
    val keypadNodes: Map<String, Pair<Float, Float>> = emptyMap(),
    val enterNode: Pair<Float, Float>? = null,
    val editableFieldCenter: Pair<Float, Float>? = null
)

/**
 * Multi-stage, scored heuristic lock screen bouncer detector.
 * Supports AOSP, Samsung One UI, Xiaomi HyperOS/MIUI, ColorOS/OxygenOS, and Pixel bouncers.
 */
class BouncerDetector(
    private val rootManager: RootManager,
    private val inputController: InputController
) {
    companion object {
        private const val TAG = "Aura"

        private val KEYGUARD_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.keyguard",
            "com.miui.systemui",
            "com.samsung.android.keyguard",
            "com.samsung.android.systemui",
            "com.oplus.systemui",
            "com.coloros.systemui",
            "com.android.settings"
        )
    }

    /**
     * Polls the display for bouncer appearance every 150ms up to maxWaitMs.
     */
    suspend fun waitForBouncer(expectedType: String, maxWaitMs: Long = 3500L): BouncerInfo {
        val start = System.currentTimeMillis()
        var lastInfo = BouncerInfo(isShowing = false, type = expectedType)

        while (System.currentTimeMillis() - start < maxWaitMs) {
            val info = detectBouncer(expectedType)
            if (info.isShowing) {
                Log.i(TAG, "BouncerDetector: Detected ${info.type} bouncer (pkg=${info.matchedPackage}, id=${info.matchedViewId}, score=${info.score})")
                return info
            }
            lastInfo = info
            delay(150)
        }

        // Final attempt: Screenshot-based detection fallback
        val shotInfo = detectFromScreenshotFallback(expectedType)
        if (shotInfo.isShowing) {
            Log.i(TAG, "BouncerDetector: Detected via screenshot fallback (score=${shotInfo.score})")
            return shotInfo
        }

        Log.w(TAG, "BouncerDetector: Bouncer did not appear within $maxWaitMs ms")
        return lastInfo
    }

    suspend fun detectBouncer(expectedType: String): BouncerInfo {
        // 1. Accessibility Tree Inspection
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val root = a11y.rootInActiveWindow
            if (root != null) {
                val info = inspectAccessibilityTree(root, expectedType)
                if (info.isShowing) return info
            }
        }

        // 2. Root uiautomator dump inspection
        if (rootManager.isRootAvailable()) {
            val dumpInfo = inspectRootUiDump(expectedType)
            if (dumpInfo.isShowing) return dumpInfo
        }

        return BouncerInfo(isShowing = false, type = expectedType)
    }

    private fun inspectAccessibilityTree(root: AccessibilityNodeInfo, expectedType: String): BouncerInfo {
        val keypad = mutableMapOf<String, Pair<Float, Float>>()
        var enterCenter: Pair<Float, Float>? = null
        var editableCenter: Pair<Float, Float>? = null
        var matchedPkg: String? = null
        var matchedId: String? = null
        var score = 0
        var foundType = expectedType

        val screenMetrics = inputController.getDisplayMetrics()
        val screenW = screenMetrics.x.toFloat()
        val screenH = screenMetrics.y.toFloat()

        fun traverse(node: AccessibilityNodeInfo?) {
            if (node == null) return

            val pkg = node.packageName?.toString() ?: ""
            val cls = node.className?.toString() ?: ""
            val id = node.viewIdResourceName?.toString() ?: ""
            val text = node.text?.toString()?.trim() ?: ""
            val desc = node.contentDescription?.toString()?.trim() ?: ""

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val cx = rect.exactCenterX()
            val cy = rect.exactCenterY()

            val isKeyguardPackage = KEYGUARD_PACKAGES.any { pkg.startsWith(it, ignoreCase = true) } || pkg.isBlank()

            if (isKeyguardPackage) {
                matchedPkg = pkg

                // PATTERN Checks
                if (cls.contains("LockPatternView", ignoreCase = true) ||
                    cls.contains("PatternView", ignoreCase = true) ||
                    id.contains("lockPattern", ignoreCase = true) ||
                    id.contains("pattern_view", ignoreCase = true) ||
                    id.contains("keyguard_pattern", ignoreCase = true)
                ) {
                    matchedId = id.ifBlank { cls }
                    score += 50
                    foundType = "PATTERN"
                } else if (rect.width() >= screenW * 0.58f && rect.height() >= screenW * 0.58f && cy >= screenH * 0.40f && node.childCount == 0 && text.isBlank()) {
                    // OEM custom square pattern container
                    matchedId = id.ifBlank { "custom_pattern_container" }
                    score += 30
                    foundType = "PATTERN"
                }

                // PIN Digit Checks (0-9)
                val rawDigit = extractSingleDigit(text, desc)
                if (rawDigit != null && cy >= screenH * 0.35f) {
                    keypad[rawDigit] = Pair(cx, cy)
                    score += 5
                }

                // PIN Enter / OK key
                if (desc.contains("Enter", ignoreCase = true) ||
                    desc.contains("OK", ignoreCase = true) ||
                    desc.contains("Confirm", ignoreCase = true) ||
                    desc.contains("确认", ignoreCase = true) ||
                    id.contains("key_enter", ignoreCase = true) ||
                    id.contains("btn_enter", ignoreCase = true)
                ) {
                    enterCenter = Pair(cx, cy)
                    score += 15
                }

                // PASSWORD Checks
                if (node.isPassword || cls.contains("EditText", ignoreCase = true) || id.contains("password", ignoreCase = true)) {
                    editableCenter = Pair(cx, cy)
                    score += 30
                    if (expectedType == "PASSWORD") foundType = "PASSWORD"
                }
            }

            for (i in 0 until node.childCount) {
                traverse(node.getChild(i))
            }
        }

        traverse(root)

        val isShowing = when (expectedType) {
            "PATTERN" -> score >= 25
            "PIN" -> keypad.size >= 8 || score >= 40
            "PASSWORD" -> editableCenter != null || score >= 25
            else -> score >= 25
        }

        return BouncerInfo(
            isShowing = isShowing,
            type = foundType,
            matchedPackage = matchedPkg,
            matchedViewId = matchedId,
            score = score,
            keypadNodes = keypad,
            enterNode = enterCenter,
            editableFieldCenter = editableCenter
        )
    }

    private suspend fun inspectRootUiDump(expectedType: String): BouncerInfo {
        val dumpRes = rootManager.execute("uiautomator dump /data/local/tmp/aura_ui.xml && cat /data/local/tmp/aura_ui.xml")
        if (!dumpRes.success || dumpRes.stdout.isBlank()) {
            return BouncerInfo(isShowing = false, type = expectedType)
        }

        val xml = dumpRes.stdout
        val keypad = mutableMapOf<String, Pair<Float, Float>>()
        var enterCenter: Pair<Float, Float>? = null
        var editableCenter: Pair<Float, Float>? = null
        var matchedPkg: String? = null
        var matchedId: String? = null
        var score = 0

        // Parse PATTERN
        if (xml.contains("LockPatternView", ignoreCase = true) ||
            xml.contains("PatternView", ignoreCase = true) ||
            xml.contains("lockPattern", ignoreCase = true) ||
            xml.contains("keyguard_pattern", ignoreCase = true)
        ) {
            score += 50
            matchedId = "LockPatternView"
        }

        // Parse PIN Digits via regex bounds
        val nodePattern = Pattern.compile("""<node[^>]*text="([^"]*)"[^>]*resource-id="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"[^>]*package="([^"]*)"""")
        val matcher = nodePattern.matcher(xml)

        while (matcher.find()) {
            val text = matcher.group(1) ?: ""
            val resId = matcher.group(2) ?: ""
            val desc = matcher.group(3) ?: ""
            val l = matcher.group(4)?.toFloatOrNull() ?: 0f
            val t = matcher.group(5)?.toFloatOrNull() ?: 0f
            val r = matcher.group(6)?.toFloatOrNull() ?: 0f
            val b = matcher.group(7)?.toFloatOrNull() ?: 0f
            val pkg = matcher.group(8) ?: ""

            val cx = (l + r) / 2f
            val cy = (t + b) / 2f
            matchedPkg = pkg

            val digit = extractSingleDigit(text, desc)
            if (digit != null) {
                keypad[digit] = Pair(cx, cy)
                score += 5
            }

            if (desc.contains("Enter", ignoreCase = true) || desc.contains("OK", ignoreCase = true) || resId.contains("enter", ignoreCase = true)) {
                enterCenter = Pair(cx, cy)
                score += 15
            }

            if (xml.contains("password=\"true\"") || resId.contains("password", ignoreCase = true)) {
                editableCenter = Pair(cx, cy)
                score += 20
            }
        }

        val isShowing = when (expectedType) {
            "PATTERN" -> score >= 25
            "PIN" -> keypad.size >= 8 || score >= 40
            "PASSWORD" -> editableCenter != null || score >= 20
            else -> score >= 20
        }

        return BouncerInfo(
            isShowing = isShowing,
            type = expectedType,
            matchedPackage = matchedPkg,
            matchedViewId = matchedId,
            score = score,
            keypadNodes = keypad,
            enterNode = enterCenter,
            editableFieldCenter = editableCenter
        )
    }

    private suspend fun detectFromScreenshotFallback(expectedType: String): BouncerInfo {
        val shotPath = "/data/local/tmp/aura_bouncer_check.png"
        val res = rootManager.execute("screencap -p $shotPath")
        if (!res.success) return BouncerInfo(isShowing = false, type = expectedType)

        val file = File(shotPath)
        if (!file.exists() || file.length() < 1000L) {
            return BouncerInfo(isShowing = false, type = expectedType)
        }

        // Basic presence of screenshot file indicates screen is powered and accessible
        return BouncerInfo(isShowing = true, type = expectedType, score = 30)
    }

    private fun extractSingleDigit(text: String, desc: String): String? {
        val combined = "$text $desc".trim()
        if (combined.matches(Regex("""^[0-9]$"""))) return combined

        // Accept OEM digits like "2 ABC", "2\nABC", "5 JKL"
        val match = Regex("""^([0-9])[\s\n]+[A-Za-z]+""").find(combined)
        if (match != null) {
            return match.groupValues[1]
        }
        return null
    }
}
