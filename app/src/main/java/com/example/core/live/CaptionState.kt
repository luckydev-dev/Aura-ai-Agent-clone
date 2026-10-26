package com.example.core.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CaptionState(
    val userText: String = "",
    val auraText: String = "",
    val turnId: Long = 0L,
    val auraSpeaking: Boolean = false,
    val isInterrupted: Boolean = false
) {
    val isAuraSpeaking: Boolean get() = auraSpeaking
}

enum class TurnSource {
    IDLE, USER, AURA
}

/**
 * State machine managing live dual-line captions between the user and Aura.
 *
 * Rules:
 * 1. First input transcription chunk after Aura finished a turn -> start a NEW user turn:
 *    clears both userText and auraText.
 * 2. First output transcription chunk after user spoke -> start a NEW Aura turn:
 *    clears auraText only (preserves userText so "You" remains visible).
 * 3. turnComplete does NOT clear text; caption remains readable until the next turn begins.
 * 4. interrupted marks Aura line as cut off (appends " ...") without clearing text.
 * 5. Reconnection, session resumption, and tool execution never wipe captions.
 * 6. Strips markdown (*, **, #, `, __) and collapses whitespace.
 */
class CaptionStateMachine {

    private val _state = MutableStateFlow(CaptionState())
    val state: StateFlow<CaptionState> = _state.asStateFlow()

    private var lastTurnSource = TurnSource.IDLE
    private var currentTurnId = 0L
    private var interruptedInCurrentTurn = false

    companion object {
        fun stripMarkdown(text: String): String {
            if (text.isEmpty()) return ""
            return text
                .replace(Regex("""\*\*|\*|__|_|`|#+"""), "")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }
    }

    @Synchronized
    fun onInputTranscription(chunk: String) {
        val clean = stripMarkdown(chunk)
        if (clean.isEmpty()) return

        if (lastTurnSource == TurnSource.AURA || lastTurnSource == TurnSource.IDLE) {
            // Start a brand new user turn: clear both userText and auraText
            currentTurnId++
            lastTurnSource = TurnSource.USER
            interruptedInCurrentTurn = false
            _state.value = CaptionState(
                userText = clean,
                auraText = "",
                turnId = currentTurnId,
                auraSpeaking = false,
                isInterrupted = false
            )
        } else {
            // Continuation of current user turn
            val cur = _state.value
            val separator = if (cur.userText.isNotEmpty() && !cur.userText.endsWith(" ") && !clean.startsWith(" ")) " " else ""
            val updated = cur.userText + separator + clean
            _state.value = cur.copy(
                userText = updated,
                auraSpeaking = false
            )
        }
    }

    @Synchronized
    fun onOutputTranscription(chunk: String) {
        val clean = stripMarkdown(chunk)
        if (clean.isEmpty()) return

        if (lastTurnSource == TurnSource.USER || lastTurnSource == TurnSource.IDLE) {
            // Start a new Aura turn: clear auraText only, keeping userText visible
            currentTurnId++
            lastTurnSource = TurnSource.AURA
            interruptedInCurrentTurn = false
            val cur = _state.value
            _state.value = cur.copy(
                auraText = clean,
                turnId = currentTurnId,
                auraSpeaking = true,
                isInterrupted = false
            )
        } else {
            // Continuation of Aura's turn
            val cur = _state.value
            val separator = if (cur.auraText.isNotEmpty() && !cur.auraText.endsWith(" ") && !clean.startsWith(" ")) " " else ""
            val updated = cur.auraText + separator + clean
            _state.value = cur.copy(
                auraText = updated,
                auraSpeaking = true
            )
        }
    }

    @Synchronized
    fun onTurnComplete() {
        val cur = _state.value
        // Do NOT clear userText or auraText; just mark speaking as false
        _state.value = cur.copy(auraSpeaking = false)
    }

    @Synchronized
    fun onInterrupted() {
        val cur = _state.value
        if (!interruptedInCurrentTurn && cur.auraText.isNotBlank()) {
            interruptedInCurrentTurn = true
            val cutoff = cur.auraText.trimEnd() + " ..."
            _state.value = cur.copy(
                auraText = cutoff,
                auraSpeaking = false,
                isInterrupted = true
            )
        } else {
            _state.value = cur.copy(auraSpeaking = false, isInterrupted = true)
        }
    }

    @Synchronized
    fun reset() {
        currentTurnId = 0L
        lastTurnSource = TurnSource.IDLE
        interruptedInCurrentTurn = false
        _state.value = CaptionState()
    }

    fun onInputTranscriptionChunk(chunk: String) = onInputTranscription(chunk)
    fun onOutputTranscriptionChunk(chunk: String) = onOutputTranscription(chunk)
}
