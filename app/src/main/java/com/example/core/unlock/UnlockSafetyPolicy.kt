package com.example.core.unlock

import android.util.Log
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.flow.first
import java.util.regex.Pattern

/**
 * Production safety policy for automated device unlocking.
 * Does NOT punish tests or successes! Only counts consecutive real credential REJECTIONS.
 *
 * Rules:
 * - 0 failures on UNLOCKED / ALREADY_UNLOCKED / DRY_RUN.
 * - 2 consecutive rejections block attempts for 10 minutes (protecting against device wipe / PIN lockout).
 * - Real screen lockout countdown (e.g. "try again in 30 seconds") is parsed dynamically.
 * - State is persisted in DataStore so restarts don't bypass it.
 */
class UnlockSafetyPolicy(
    private val preferences: AuraPreferences,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    companion object {
        private const val TAG = "Aura"
        const val MAX_CONSECUTIVE_FAILURES = 2
        const val FAILURE_BLOCK_DURATION_MS = 10 * 60 * 1000L // 10 minutes
        const val DEFAULT_REJECTION_BACKOFF_MS = 60 * 1000L // 60 seconds
    }

    /**
     * Checks if an unlock attempt with credentials can proceed.
     * Returns Pair(allowed, blockReasonMessage).
     */
    suspend fun canAttemptUnlock(): Pair<Boolean, String?> {
        val now = timeProvider()
        val blockedUntil = preferences.unlockBlockedUntilMs.first()

        if (now < blockedUntil) {
            val remainSec = ((blockedUntil - now) / 1000L).coerceAtLeast(1)
            val msg = if (remainSec > 60) {
                val min = remainSec / 60
                val sec = remainSec % 60
                "Safety block active ($min min $sec s remaining to protect device)."
            } else {
                "Safety block active ($remainSec s remaining). Please wait or unlock manually."
            }
            Log.w(TAG, "UnlockSafetyPolicy: Blocked until $blockedUntil ($remainSec s remain)")
            return Pair(false, msg)
        }

        val failures = preferences.unlockConsecutiveFailures.first()
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            val blockedMs = now + FAILURE_BLOCK_DURATION_MS
            preferences.setUnlockBlockedUntilMs(blockedMs)
            return Pair(false, "2 wrong attempts in a row; waiting 10 min to protect your phone from lockout/data wipe.")
        }

        return Pair(true, null)
    }

    /**
     * Called on successful unlock or if device was already unlocked.
     * Completely resets failure count and clear any block.
     */
    suspend fun recordSuccess() {
        Log.i(TAG, "UnlockSafetyPolicy: Resetting consecutive failures on success.")
        preferences.setUnlockConsecutiveFailures(0)
        preferences.setUnlockBlockedUntilMs(0L)
    }

    /**
     * Records a rejected credential attempt.
     * Parses lockout countdown from screen text if present, otherwise defaults to 60s (or 10m on 2nd failure).
     */
    suspend fun recordFailure(screenErrorText: String): Long {
        val now = timeProvider()
        val currentFailures = preferences.unlockConsecutiveFailures.first() + 1
        preferences.setUnlockConsecutiveFailures(currentFailures)

        val parsedLockoutSec = parseLockoutSeconds(screenErrorText)
        val blockedUntil: Long = if (parsedLockoutSec != null && parsedLockoutSec > 0) {
            now + (parsedLockoutSec * 1000L)
        } else if (currentFailures >= MAX_CONSECUTIVE_FAILURES) {
            now + FAILURE_BLOCK_DURATION_MS
        } else {
            now + DEFAULT_REJECTION_BACKOFF_MS
        }

        preferences.setUnlockBlockedUntilMs(blockedUntil)
        Log.w(TAG, "UnlockSafetyPolicy: Recorded failure #$currentFailures. Blocked until $blockedUntil")
        return blockedUntil
    }

    /**
     * Manual reset button triggered from Settings.
     */
    suspend fun resetSafetyLimit() {
        Log.i(TAG, "UnlockSafetyPolicy: Manual reset triggered.")
        preferences.resetUnlockSafetyLimit()
    }

    suspend fun getStatusDescription(): String {
        val now = timeProvider()
        val blockedUntil = preferences.unlockBlockedUntilMs.first()
        if (now < blockedUntil) {
            val remainSec = ((blockedUntil - now) / 1000L).coerceAtLeast(1)
            val min = remainSec / 60
            val sec = remainSec % 60
            return "Blocked for ${min}:${sec.toString().padStart(2, '0')} min (Safety Protection)"
        }
        val failures = preferences.unlockConsecutiveFailures.first()
        return if (failures > 0) "OK ($failures warning failure recorded)" else "OK (Protected)"
    }

    /**
     * Extracts lockout seconds from OEM screen text:
     * Examples: "try again in 30 seconds", "retry in 30s", "in 1 minute", "try in 120 sec"
     */
    fun parseLockoutSeconds(text: String): Long? {
        if (text.isBlank()) return null
        val lower = text.lowercase()

        // Match minutes: e.g. "in 1 minute", "in 2 minutes", "1 min"
        val minPattern = Pattern.compile("""(?:in|after)\s+(\d+)\s*(?:minute|min|m\b)""")
        val minMatcher = minPattern.matcher(lower)
        if (minMatcher.find()) {
            val min = minMatcher.group(1)?.toLongOrNull() ?: 1L
            return min * 60L
        }

        // Match seconds: e.g. "in 30 seconds", "in 30s", "30 sec"
        val secPattern = Pattern.compile("""(?:in|after)\s+(\d+)\s*(?:second|sec|s\b)""")
        val secMatcher = secPattern.matcher(lower)
        if (secMatcher.find()) {
            return secMatcher.group(1)?.toLongOrNull()
        }

        // Alternative standalone "30s" or "30 seconds" near "retry" or "try again"
        if (lower.contains("try again") || lower.contains("retry") || lower.contains("incorrect") || lower.contains("wrong")) {
            val standaloneSec = Pattern.compile("""(\d+)\s*(?:second|sec|s\b)""")
            val m = standaloneSec.matcher(lower)
            if (m.find()) {
                return m.group(1)?.toLongOrNull()
            }
        }

        return null
    }
}
