package com.example.core.unlock

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.AuraApplication
import com.example.core.input.InputController
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.settings.EncryptedKeyStorage
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

enum class UnlockStatus {
    UNLOCKED,
    ALREADY_UNLOCKED,
    NO_CREDENTIALS,
    ROOT_AND_ACCESSIBILITY_MISSING,
    BOUNCER_NOT_SHOWN,
    CREDENTIAL_REJECTED_OR_LOCKED_OUT,
    RATE_LIMITED,
    DEVICE_NOT_FIRST_UNLOCKED,
    TIMEOUT,
    CANCELLED
}

data class UnlockResult(
    val status: UnlockStatus,
    val humanMessage: String,
    val stepLog: List<String>
)

/**
 * Verified, root-first Phone Unlock state machine.
 * Enforces UnlockSafetyPolicy, multi-OEM BouncerDetector, single-stroke pattern gesture,
 * proper shell escaping, and truthful verification.
 */
class UnlockManager(
    private val context: Context,
    private val rootManager: RootManager,
    private val inputController: InputController,
    private val encryptedStorage: EncryptedKeyStorage,
    private val patternLocator: PatternGridLocator,
    val safetyPolicy: UnlockSafetyPolicy = UnlockSafetyPolicy(AuraPreferences(context)),
    private val bouncerDetector: BouncerDetector = BouncerDetector(rootManager, inputController)
) {
    companion object {
        private const val TAG = "Aura"

        @Volatile
        var isCancelled: Boolean = false

        @Volatile
        private var INSTANCE: UnlockManager? = null

        fun getInstance(context: Context): UnlockManager {
            return INSTANCE ?: synchronized(this) {
                val app = context.applicationContext as AuraApplication
                val root = app.rootManager
                val input = app.inputController
                val storage = EncryptedKeyStorage(context.applicationContext)
                val locator = PatternGridLocator(context.applicationContext, root, input, storage)
                val prefs = AuraPreferences(context.applicationContext)
                val safety = UnlockSafetyPolicy(prefs)
                val bouncer = BouncerDetector(root, input)
                UnlockManager(context.applicationContext, root, input, storage, locator, safety, bouncer).also { INSTANCE = it }
            }
        }

        fun applyMiddleDotRule(raw: List<Int>): List<Int> {
            if (raw.size < 2) return raw
            val result = mutableListOf<Int>()
            result.add(raw.first())

            val middleMap = mapOf(
                Pair(0, 2) to 1, Pair(2, 0) to 1,
                Pair(3, 5) to 4, Pair(5, 3) to 4,
                Pair(6, 8) to 7, Pair(8, 6) to 7,
                Pair(0, 6) to 3, Pair(6, 0) to 3,
                Pair(1, 7) to 4, Pair(7, 1) to 4,
                Pair(2, 8) to 5, Pair(8, 2) to 5,
                Pair(0, 8) to 4, Pair(8, 0) to 4,
                Pair(2, 6) to 4, Pair(6, 2) to 4
            )

            for (i in 1 until raw.size) {
                val prev = result.last()
                val curr = raw[i]
                val mid = middleMap[Pair(prev, curr)]
                if (mid != null && mid !in result) {
                    result.add(mid)
                }
                if (curr !in result) {
                    result.add(curr)
                }
            }
            return result
        }
    }

    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val prefs = AuraPreferences(context)

    suspend fun isLocked(): Boolean {
        if (!keyguardManager.isKeyguardLocked) return false
        val res = rootManager.execute("dumpsys window | grep -E \"mShowing=true|mKeyguardShowing=true\"")
        return res.stdout.isNotBlank() || keyguardManager.isKeyguardLocked
    }

    /**
     * Checks if device is in Direct Boot mode (after reboot, prior to first user unlock).
     */
    private suspend fun isDeviceFirstUnlocked(): Boolean {
        if (keyguardManager.isDeviceLocked) {
            val res = rootManager.execute("dumpsys trust | grep -i \"user is unlocked\"")
            if (res.stdout.isNotBlank() && res.stdout.contains("false", ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    suspend fun executeUnlock(dryRunOnlyBouncer: Boolean = false): UnlockResult = withContext(Dispatchers.IO) {
        val stepLog = mutableListOf<String>()
        fun logStep(msg: String) {
            Log.i(TAG, "[Unlock] $msg")
            stepLog.add(msg)
        }

        isCancelled = false
        logStep("1. Checking keyguard lock state...")

        // Step 1: Already unlocked?
        if (!isLocked()) {
            logStep("Device is already unlocked.")
            return@withContext UnlockResult(
                UnlockStatus.ALREADY_UNLOCKED,
                "Phone is already unlocked.",
                stepLog
            )
        }

        // Check Direct Boot first unlock
        if (!isDeviceFirstUnlocked()) {
            logStep("Device is in Direct Boot state (not unlocked after reboot). Keystore locked.")
            return@withContext UnlockResult(
                UnlockStatus.DEVICE_NOT_FIRST_UNLOCKED,
                "Device was just restarted. Android requires manual first unlock before apps can access storage.",
                stepLog
            )
        }

        // Step 2: Safety rate limit check (only consecutive wrong attempts trigger limit)
        val (safetyAllowed, safetyReason) = safetyPolicy.canAttemptUnlock()
        if (!safetyAllowed) {
            logStep("Unlock blocked by Safety Policy: $safetyReason")
            return@withContext UnlockResult(
                UnlockStatus.RATE_LIMITED,
                safetyReason ?: "Safety limit active. Unlock manually.",
                stepLog
            )
        }

        // Step 3: Check credentials
        val lockType = encryptedStorage.getLockType().uppercase()
        val hasCredentials = when (lockType) {
            "PIN" -> encryptedStorage.getLockPin().isNotBlank()
            "PASSWORD" -> encryptedStorage.getLockPassword().isNotBlank()
            "PATTERN" -> encryptedStorage.getPatternPoints().isNotBlank()
            else -> false
        }
        if (!hasCredentials && !dryRunOnlyBouncer) {
            logStep("No unlock credentials configured in Settings.")
            return@withContext UnlockResult(
                UnlockStatus.NO_CREDENTIALS,
                "No lock screen credentials configured. Please set Pattern/PIN in Settings.",
                stepLog
            )
        }

        val hasRoot = rootManager.isRootAvailable()
        val a11yRunning = AuraAccessibilityService.isRunning()
        if (!hasRoot && !a11yRunning) {
            logStep("Root and Accessibility Service are both missing.")
            return@withContext UnlockResult(
                UnlockStatus.ROOT_AND_ACCESSIBILITY_MISSING,
                "Neither Root nor Accessibility Service is available to unlock the device.",
                stepLog
            )
        }

        val showCursor = prefs.showCursorDuringUnlock.first()

        if (showCursor) {
            performUnlockAttempt(lockType, dryRunOnlyBouncer, hasRoot, stepLog)
        } else {
            inputController.withCursorHidden {
                performUnlockAttempt(lockType, dryRunOnlyBouncer, hasRoot, stepLog)
            }
        }
    }

    private suspend fun performUnlockAttempt(
        lockType: String,
        dryRunOnlyBouncer: Boolean,
        hasRoot: Boolean,
        stepLog: MutableList<String>
    ): UnlockResult {
        fun logStep(msg: String) {
            Log.i(TAG, "[Unlock] $msg")
            stepLog.add(msg)
        }

        // Step 4: Wake up device
        logStep("2. Waking up device screen...")
        if (!powerManager.isInteractive) {
            if (hasRoot) {
                rootManager.execute("input keyevent 224") // KEYCODE_WAKEUP
            } else {
                @Suppress("DEPRECATION")
                val wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "Aura:UnlockWake"
                )
                wakeLock.acquire(3000)
            }

            delay(300)
            if (!powerManager.isInteractive && hasRoot) {
                rootManager.execute("input keyevent 82") // KEYCODE_MENU fallback
            }

            var wakePoll = 0
            while (!powerManager.isInteractive && wakePoll < 10) {
                delay(100)
                wakePoll++
            }
        }
        logStep("Screen is interactive: ${powerManager.isInteractive}")

        if (isCancelled) {
            return UnlockResult(UnlockStatus.CANCELLED, "Unlock was cancelled.", stepLog)
        }

        // Step 5: Reveal bouncer
        logStep("3. Dismissing keyguard / Swiping up for bouncer...")
        if (hasRoot) {
            rootManager.execute("wm dismiss-keyguard")
        }

        var bouncerCheck = bouncerDetector.detectBouncer(lockType)
        if (!bouncerCheck.isShowing) {
            // Swipe up
            val metrics = inputController.getDisplayMetrics()
            val w = metrics.x.toFloat()
            val h = metrics.y.toFloat()
            inputController.swipe(w * 0.5f, h * 0.88f, w * 0.5f, h * 0.30f, 250L, "Dismiss Keyguard")

            var waitPoll = 0
            while (waitPoll < 12) {
                delay(120)
                waitPoll++
                bouncerCheck = bouncerDetector.detectBouncer(lockType)
                if (bouncerCheck.isShowing) break
            }
        }

        if (!bouncerCheck.isShowing) {
            // Retry swipe
            logStep("Bouncer not detected on first attempt, retrying swipe...")
            val metrics = inputController.getDisplayMetrics()
            inputController.swipe(metrics.x * 0.5f, metrics.y * 0.90f, metrics.x * 0.5f, metrics.y * 0.25f, 280L, "Retry Swipe")
            delay(400)
            bouncerCheck = bouncerDetector.detectBouncer(lockType)
        }

        if (!bouncerCheck.isShowing) {
            logStep("Bouncer did not appear. Aborting credential entry for safety.")
            return UnlockResult(
                UnlockStatus.BOUNCER_NOT_SHOWN,
                "Lock screen bouncer did not appear. Please wake phone manually.",
                stepLog
            )
        }
        logStep("Bouncer confirmed showing (type=${bouncerCheck.type}, score=${bouncerCheck.score})")

        if (dryRunOnlyBouncer) {
            logStep("Dry run mode: Bouncer displayed successfully.")
            return UnlockResult(
                UnlockStatus.UNLOCKED,
                "Dry run: Bouncer displayed successfully.",
                stepLog
            )
        }

        // Step 6: Enter Credential ONCE
        logStep("4. Entering credentials ($lockType)...")

        when (lockType) {
            "PIN" -> {
                val pin = encryptedStorage.getLockPin()
                var pinSuccess = false
                if (hasRoot) {
                    val res = rootManager.execute("input text $pin && sleep 0.1 && input keyevent 66")
                    delay(400)
                    if (!isLocked()) {
                        pinSuccess = true
                    }
                }

                if (!pinSuccess) {
                    if (bouncerCheck.keypadNodes.isNotEmpty()) {
                        logStep("Entering PIN via keypad coordinates...")
                        pin.forEach { digitChar ->
                            val pt = bouncerCheck.keypadNodes[digitChar.toString()]
                            if (pt != null) {
                                inputController.tap(pt.first, pt.second, "PIN $digitChar")
                                delay(100)
                            }
                        }
                        delay(150)
                        inputController.pressKey(66, "Enter")
                    } else {
                        enterPinViaAccessibility(pin)
                    }
                }
            }
            "PASSWORD" -> {
                val password = encryptedStorage.getLockPassword()
                val a11y = AuraAccessibilityService.instance
                val textSet = a11y?.setTextOnActiveNode(password) ?: false
                if (!textSet) {
                    val escaped = escapeShellArg(password)
                    if (hasRoot) {
                        rootManager.execute("input text $escaped && sleep 0.1 && input keyevent 66")
                    } else {
                        inputController.typeText(password, "Enter Password")
                        inputController.pressKey(66, "Enter")
                    }
                } else {
                    inputController.pressKey(66, "Enter")
                }
            }
            "PATTERN" -> {
                val rawPoints = encryptedStorage.getPatternPoints()
                    .split(",")
                    .mapNotNull { it.trim().toIntOrNull() }

                val points = applyMiddleDotRule(rawPoints)
                logStep("Pattern points to draw (${points.size} dots)")

                val detection = patternLocator.locatePatternGrid()
                logStep("Grid detected via method: ${detection.method}")

                val screenCoords = points.map { idx ->
                    detection.points[idx]
                }

                val duration = (points.size * 140L).coerceAtLeast(600L)
                inputController.drawPath(screenCoords, duration, "Draw Pattern")
            }
        }

        // Step 7: Verify unlock outcome
        logStep("5. Verifying lock dismissal...")
        var verifiedUnlocked = false
        var pollVerify = 0
        while (pollVerify < 16) {
            delay(150)
            pollVerify++
            if (!isLocked()) {
                verifiedUnlocked = true
                break
            }
        }

        if (verifiedUnlocked) {
            logStep("✓ Phone unlocked successfully!")
            safetyPolicy.recordSuccess()
            return UnlockResult(
                UnlockStatus.UNLOCKED,
                "Phone unlocked successfully.",
                stepLog
            )
        }

        // If still locked, check screen text for rejection / lockout
        val screenText = getLockscreenErrorText()
        logStep("Lockscreen text: $screenText")

        val isRejected = screenText.contains("try again", ignoreCase = true) ||
                screenText.contains("wrong", ignoreCase = true) ||
                screenText.contains("incorrect", ignoreCase = true) ||
                screenText.contains("too many", ignoreCase = true)

        if (isRejected) {
            val lockoutMs = safetyPolicy.recordFailure(screenText)
            val secRemain = ((lockoutMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(1)
            logStep("Credential rejected or lockout triggered ($secRemain s remain).")

            return UnlockResult(
                UnlockStatus.CREDENTIAL_REJECTED_OR_LOCKED_OUT,
                "Credential was rejected. Cooldown for $secRemain seconds.",
                stepLog
            )
        }

        logStep("Unlock timed out waiting for keyguard to clear.")
        return UnlockResult(
            UnlockStatus.TIMEOUT,
            "Unlock timed out. The phone is still locked.",
            stepLog
        )
    }

    private fun escapeShellArg(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                ' ', '\\', '"', '\'', '$', '`', '&', ';', '|', '<', '>', '(', ')', '{', '}', '[', ']', '*', '?', '~' -> {
                    sb.append('\\').append(c)
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private suspend fun enterPinViaAccessibility(pin: String) {
        val a11y = AuraAccessibilityService.instance ?: return
        val root = a11y.rootInActiveWindow ?: return

        pin.forEach { ch ->
            val digitStr = ch.toString()
            val digitNode = findExactDigitNode(root, digitStr)
            if (digitNode != null) {
                val clickableNode = findClickableAncestorOrSelf(digitNode)
                if (clickableNode != null) {
                    clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    val rect = android.graphics.Rect()
                    digitNode.getBoundsInScreen(rect)
                    inputController.tap(rect.centerX().toFloat(), rect.centerY().toFloat(), "PIN $digitStr")
                }
            }
            delay(120)
        }
        delay(150)
        inputController.pressKey(66, "Enter")
    }

    private fun findExactDigitNode(node: AccessibilityNodeInfo, digit: String): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        if (text == digit || desc == digit) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findExactDigitNode(child, digit)
            if (found != null) return found
        }
        return null
    }

    private fun findClickableAncestorOrSelf(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        while (cur != null) {
            if (cur.isClickable) return cur
            cur = cur.parent
        }
        return null
    }

    private suspend fun getLockscreenErrorText(): String {
        val a11y = AuraAccessibilityService.instance
        if (a11y != null) {
            val root = a11y.rootInActiveWindow
            if (root != null) {
                val sb = StringBuilder()
                collectAllText(root, sb)
                return sb.toString()
            }
        }
        val dump = rootManager.execute("cat /data/local/tmp/aura_ui.xml")
        return dump.stdout
    }

    private fun collectAllText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val t = node.text?.toString()
        if (!t.isNullOrBlank()) sb.append(t).append(" ")
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            collectAllText(c, sb)
        }
    }
}
