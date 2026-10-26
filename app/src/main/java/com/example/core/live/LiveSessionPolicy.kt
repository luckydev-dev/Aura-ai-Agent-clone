package com.example.core.live

import kotlin.random.Random

sealed class CloseClassification {
    data class Terminal(val userMessage: String, val hint: String? = null) : CloseClassification()
    data class Transient(val reason: String) : CloseClassification()
}

object LiveSessionPolicy {

    const val MAX_BACKOFF_MS = 30_000L
    const val MAX_CONTINUOUS_FAILURE_WINDOW_MS = 10 * 60 * 1000L // 10 minutes

    /**
     * Classifies a WebSocket close code and reason into either a Terminal error
     * (which should halt the call and display an action card) or a Transient error
     * (which should automatically reconnect and keep the call alive).
     */
    fun classifyClose(
        code: Int,
        reason: String,
        isSetupComplete: Boolean,
        apiKey: String = ""
    ): CloseClassification {
        val lower = reason.lowercase()

        // Setup field 1007 rejection before setupComplete is transiently retried without that field
        if (code == 1007 && !isSetupComplete && (lower.contains("languagecode") || lower.contains("thinkinglevel") || lower.contains("thinkingconfig"))) {
            return CloseClassification.Transient("Setup field rejected: $reason")
        }

        // Terminal error checks
        val isTerminalCode = code in listOf(1008, 400, 401, 403, 404) ||
                (code == 1007 && isSetupComplete) // 1007 after setup is terminal invalid argument

        val isTerminalReason = lower.contains("api key") || lower.contains("api_key") ||
                lower.contains("access_token_type_unsupported") ||
                lower.contains("not found") ||
                lower.contains("models/") && lower.contains("not found") ||
                lower.contains("permission_denied") ||
                lower.contains("quota") || lower.contains("resource_exhausted") || lower.contains("429")

        if (isTerminalCode || isTerminalReason) {
            val userMsg = when {
                lower.contains("quota") || lower.contains("resource_exhausted") || lower.contains("429") ->
                    "Quota exceeded, try later."
                lower.contains("api key") || lower.contains("api_key") || lower.contains("access_token_type_unsupported") ->
                    "API key error: check key format or permissions."
                code == 1008 || lower.contains("not found") ->
                    "Model not found. Pick an available model in Settings."
                code == 1007 ->
                    "Invalid setup argument: $reason"
                else ->
                    "Terminal session error: $reason (Code $code)"
            }
            val hint = GeminiLiveHandshake.getAuthHintForError(apiKey, reason)
            return CloseClassification.Terminal(userMsg, hint)
        }

        // All other codes: 1000 (normal close), 1001 (going away), 1005, 1006 (abnormal),
        // 1011, 1012 (service restart), 1013 (try again later) are strictly TRANSIENT!
        return CloseClassification.Transient(
            if (code == 1000) "Normal server close" else "Transient close ($code): $reason"
        )
    }

    /**
     * Calculates exponential backoff in milliseconds: 1s, 2s, 4s, 8s, 16s... capped at 30s
     * with random jitter bounded between 0 and 20% of the backoff.
     */
    fun calculateBackoffMs(attempt: Int, jitterSource: () -> Double = { Random.nextDouble() }): Long {
        val safeAttempt = (attempt - 1).coerceIn(0, 10)
        val baseBackoff = minOf(1000L * (1L shl safeAttempt), MAX_BACKOFF_MS)
        val jitterMax = (baseBackoff * 0.20).toLong()
        val jitter = (jitterMax * jitterSource()).toLong()
        return baseBackoff + jitter
    }

    /**
     * Determines if a server error indicates that the session resumption handle has expired or is invalid.
     */
    fun isResumptionHandleRejected(code: Int, reason: String): Boolean {
        val lower = reason.lowercase()
        return lower.contains("resumption") || lower.contains("handle") || lower.contains("session not found") ||
                (code in listOf(1007, 1008) && lower.contains("session"))
    }
}
