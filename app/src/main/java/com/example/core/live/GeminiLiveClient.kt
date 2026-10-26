package com.example.core.live

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.AuraApplication
import com.example.core.audio.AudioTrackPlayer
import com.example.core.memory.MemoryRepository
import com.example.core.tools.ToolRegistry
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed class LiveConnectionState {
    object Disconnected : LiveConnectionState()
    object Connecting : LiveConnectionState()
    object Connected : LiveConnectionState()
    data class Reconnecting(val attempt: Int, val reason: String = "") : LiveConnectionState()
    data class Error(val message: String, val isTerminal: Boolean = true, val hint: String? = null) : LiveConnectionState()
    data class Ended(val reason: String) : LiveConnectionState()
}

class GeminiLiveClient(
    private val toolRegistry: ToolRegistry,
    private val audioPlayer: AudioTrackPlayer,
    private val memoryRepository: MemoryRepository? = null,
    private val preferences: AuraPreferences? = null
) {
    companion object {
        private const val TAG = "Aura"
        private const val BASE_WS_URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }

    private var okHttpClient: OkHttpClient? = null
    private var webSocket: WebSocket? = null
    private var scope: CoroutineScope? = null

    // Per-connection ID to strictly reject callbacks from stale sockets
    private var activeConnectionId = 0L

    private val _connectionState = MutableStateFlow<LiveConnectionState>(LiveConnectionState.Disconnected)
    val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _isAuraActing = MutableStateFlow(false)
    val isAuraActing: StateFlow<Boolean> = _isAuraActing.asStateFlow()

    private val _isAuraThinking = MutableStateFlow(false)
    val isAuraThinking: StateFlow<Boolean> = _isAuraThinking.asStateFlow()

    // Dual-line Live Captions State Machine
    val captionStateMachine = CaptionStateMachine()
    val captionState: StateFlow<CaptionState> = captionStateMachine.state

    // Backwards-compatible transcript properties
    val userTranscript: StateFlow<String> get() = MutableStateFlow(captionStateMachine.state.value.userText).asStateFlow()
    val modelTranscript: StateFlow<String> get() = MutableStateFlow(captionStateMachine.state.value.auraText).asStateFlow()

    // Active session parameters
    private var currentSessionId: String = UUID.randomUUID().toString()
    private var currentApiKey: String = ""
    private var currentModel: String = "gemini-3.8-live"
    private var currentVoice: String = "Aoede"
    private var currentSystemPrompt: String = ""
    private var currentThinkingLevel: String = "LOW"
    private var currentLanguage: String = "Hinglish"
    private var currentInterruptionSensitivity: String = "Low"
    private var allowInterrupting: Boolean = true
    private var greetOnConnect: Boolean = true
    private var autoHangupSeconds: Int = 30
    private var idleSilenceHangupMinutes: Int = 5
    private var keepAliveScreenOff: Boolean = false
    private var screenOffTimeoutMinutes: Int = 3

    private var unsupportedFields = mutableSetOf<String>()

    private var isSetupComplete = false
    private var resumptionHandle: String? = null
    private var intentionalDisconnect = false
    private var terminalError = false
    private var reconnectAttempt = 0
    private var hasDoneInitialGreeting = false
    private var isReconnectingForVoiceSwitch = false
    private var isNetworkAvailable = true

    private var reconnectJob: Job? = null
    private var setupTimeoutJob: Job? = null
    private var silenceTimeoutJob: Job? = null
    private var screenOffJob: Job? = null
    private var cachedToolDeclarations: List<Map<String, Any>> = emptyList()

    private var continuousFailureStartTime = 0L
    private var lastActivityTimestamp = System.currentTimeMillis()

    var onSilenceTimeoutListener: (() -> Unit)? = null
    var onSessionEndedListener: ((String) -> Unit)? = null

    private val diagnostics: LiveDiagnostics?
        get() = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()

    fun connect(
        coroutineScope: CoroutineScope,
        apiKey: String,
        model: String,
        voice: String,
        systemPrompt: String,
        thinkingLevel: String = "LOW",
        language: String = "Hinglish",
        interruptionSensitivity: String = "Low",
        allowInterrupt: Boolean = true,
        greet: Boolean = true,
        autoHangup: Int = 30,
        idleHangupMinutes: Int = 5,
        keepScreenOffAlive: Boolean = false,
        screenOffTimeoutMin: Int = 3,
        unsupported: Set<String> = emptySet()
    ) {
        scope = coroutineScope
        currentSessionId = UUID.randomUUID().toString()
        currentApiKey = apiKey.trim()
        currentModel = model.ifBlank { "gemini-3.8-live" }.trim()
        currentVoice = voice.ifBlank { "Aoede" }.trim()
        currentSystemPrompt = systemPrompt
        currentThinkingLevel = thinkingLevel
        currentLanguage = language
        currentInterruptionSensitivity = interruptionSensitivity
        allowInterrupting = allowInterrupt
        greetOnConnect = greet
        autoHangupSeconds = autoHangup
        idleSilenceHangupMinutes = idleHangupMinutes
        keepAliveScreenOff = keepScreenOffAlive
        screenOffTimeoutMinutes = screenOffTimeoutMin
        unsupportedFields = unsupported.toMutableSet()

        intentionalDisconnect = false
        terminalError = false
        reconnectAttempt = 0
        continuousFailureStartTime = 0L
        hasDoneInitialGreeting = false
        isReconnectingForVoiceSwitch = false
        lastActivityTimestamp = System.currentTimeMillis()

        diagnostics?.setSessionStartTime(System.currentTimeMillis())
        diagnostics?.resetReconnectCount()

        captionStateMachine.reset()
        startSilenceWatchdog()

        scope?.launch(Dispatchers.IO) {
            cachedToolDeclarations = toolRegistry.getToolDeclarationsJson()
        }
        startWebSocketConnection()
    }

    private fun startSilenceWatchdog() {
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = scope?.launch(Dispatchers.Default) {
            while (isActive) {
                delay(3000L)
                if (_connectionState.value == LiveConnectionState.Connected && isSetupComplete) {
                    val idleMs = System.currentTimeMillis() - lastActivityTimestamp

                    // 1. Short silence auto-hangup if configured (e.g. 30s)
                    if (autoHangupSeconds > 0 && idleMs >= autoHangupSeconds * 1000L) {
                        Log.i(TAG, "Short silence auto-hangup triggered after ${autoHangupSeconds}s")
                        withContext(Dispatchers.Main) {
                            onSilenceTimeoutListener?.invoke()
                        }
                        break
                    }

                    // 2. Extended idle silence hangup (e.g. 5 min of total silence)
                    val idleLimitMs = idleSilenceHangupMinutes * 60 * 1000L
                    if (idleLimitMs > 0 && idleMs >= idleLimitMs) {
                        Log.i(TAG, "Extended idle silence hangup triggered after ${idleSilenceHangupMinutes}m")
                        withContext(Dispatchers.Main) {
                            endSessionGracefully("Aura session ended (idle for ${idleSilenceHangupMinutes}m)")
                        }
                        break
                    }
                }
            }
        }
    }

    fun onScreenStateChanged(isScreenOn: Boolean) {
        if (isScreenOn) {
            screenOffJob?.cancel()
            screenOffJob = null
            // If socket was disconnected while screen off, immediately attempt reconnect
            if (!intentionalDisconnect && !terminalError && _connectionState.value !is LiveConnectionState.Connected) {
                scheduleReconnect("Screen turned on")
            }
        } else {
            if (!keepAliveScreenOff && _connectionState.value == LiveConnectionState.Connected) {
                screenOffJob?.cancel()
                screenOffJob = scope?.launch {
                    val delayMs = screenOffTimeoutMinutes * 60 * 1000L
                    Log.i(TAG, "Screen turned off: starting $screenOffTimeoutMinutes minute idle countdown")
                    delay(delayMs)
                    if (isActive && _connectionState.value == LiveConnectionState.Connected) {
                        Log.i(TAG, "Ending call: screen off for $screenOffTimeoutMinutes minutes")
                        endSessionGracefully("Aura session ended (screen off)")
                    }
                }
            }
        }
    }

    fun onNetworkLost() {
        Log.w(TAG, "Network connection lost. Setting Reconnecting(No internet)")
        isNetworkAvailable = false
        reconnectJob?.cancel()
        if (_connectionState.value == LiveConnectionState.Connected) {
            _connectionState.value = LiveConnectionState.Reconnecting(reconnectAttempt, "No internet")
            diagnostics?.updateConnectionState("Reconnecting: No internet")
        }
    }

    fun onNetworkAvailable() {
        Log.i(TAG, "Network restored. Reconnecting immediately...")
        isNetworkAvailable = true
        if (!intentionalDisconnect && !terminalError && _connectionState.value !is LiveConnectionState.Connected) {
            reconnectAttempt = 0 // Reset backoff on explicit network recovery
            scheduleReconnect("Network restored", immediate = true)
        }
    }

    private fun markActivity() {
        lastActivityTimestamp = System.currentTimeMillis()
    }

    private fun startWebSocketConnection(backgroundSwap: Boolean = false) {
        if (currentApiKey.isBlank()) {
            val err = "Gemini API Key is missing. Please set it in Settings."
            Log.e(TAG, err)
            terminalError = true
            _connectionState.value = LiveConnectionState.Error(err, isTerminal = true)
            diagnostics?.updateConnectionState("Error: No API Key")
            return
        }

        val myConnectionId = ++activeConnectionId
        setupTimeoutJob?.cancel()

        if (!backgroundSwap) {
            _connectionState.value = if (reconnectAttempt > 0) {
                LiveConnectionState.Reconnecting(reconnectAttempt, "Attempt #$reconnectAttempt")
            } else {
                LiveConnectionState.Connecting
            }
            diagnostics?.updateConnectionState(
                if (reconnectAttempt > 0) "Reconnecting ($reconnectAttempt)" else "Connecting"
            )
        }

        if (okHttpClient == null) {
            okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive indefinitely
                .writeTimeout(15, TimeUnit.SECONDS)
                .build()
        }

        val url = "$BASE_WS_URL?key=$currentApiKey"
        val request = Request.Builder().url(url).build()

        val socketListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myConnectionId != activeConnectionId) {
                    Log.d(TAG, "Ignoring onOpen from stale socket #$myConnectionId (current is #$activeConnectionId)")
                    webSocket.close(1000, "Stale socket")
                    return
                }

                val httpCode = response.code
                diagnostics?.setSocketOpened(httpCode)
                Log.i(TAG, "WebSocket #$myConnectionId connected HTTP $httpCode. Sending setup message...")
                diagnostics?.log(TAG, "WebSocket #$myConnectionId connected HTTP $httpCode")

                scope?.launch(Dispatchers.IO) {
                    sendSetupMessage(webSocket)
                    startSetupWatchdog(myConnectionId)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myConnectionId != activeConnectionId) return
                processIncomingFrame(text, isBinary = false)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (myConnectionId != activeConnectionId) return
                processIncomingFrame(bytes.utf8(), isBinary = true)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (myConnectionId != activeConnectionId) {
                    Log.d(TAG, "Ignoring onClosing from stale socket #$myConnectionId")
                    return
                }

                setupTimeoutJob?.cancel()
                diagnostics?.setLastClose(code, reason)
                diagnostics?.log(TAG, "WebSocket #$myConnectionId onClosing: $code $reason")
                Log.w(TAG, "WebSocket #$myConnectionId onClosing: code=$code, reason=$reason, setupComplete=$isSetupComplete")

                // Handle setup argument 1007 retry path
                if (code == 1007 && !isSetupComplete) {
                    handleSetup1007Retry(reason)
                    webSocket.close(code, null)
                    return
                }

                // Check if resumption handle was rejected
                if (resumptionHandle != null && LiveSessionPolicy.isResumptionHandleRejected(code, reason)) {
                    Log.w(TAG, "Session resumption handle was rejected by server. Dropping handle and reconnecting fresh.")
                    resumptionHandle = null
                    webSocket.close(code, null)
                    scheduleReconnect("Resumption handle expired", immediate = true, injectRecentContext = true)
                    return
                }

                val classification = LiveSessionPolicy.classifyClose(code, reason, isSetupComplete, currentApiKey)
                webSocket.close(code, null)

                when (classification) {
                    is CloseClassification.Terminal -> {
                        Log.e(TAG, "Terminal close received: ${classification.userMessage}")
                        terminalError = true
                        _connectionState.value = LiveConnectionState.Error(
                            message = classification.userMessage,
                            isTerminal = true,
                            hint = classification.hint
                        )
                        diagnostics?.updateConnectionState("Error: ${classification.userMessage}")
                    }
                    is CloseClassification.Transient -> {
                        if (!intentionalDisconnect) {
                            Log.i(TAG, "Transient close received ($code). Scheduling auto-reconnect...")
                            scheduleReconnect(classification.reason)
                        } else {
                            _connectionState.value = LiveConnectionState.Disconnected
                            diagnostics?.updateConnectionState("Disconnected")
                        }
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (myConnectionId != activeConnectionId) return
                Log.i(TAG, "WebSocket #$myConnectionId onClosed: $code $reason")
                diagnostics?.setLastClose(code, reason)
                if (!intentionalDisconnect && !terminalError && _connectionState.value is LiveConnectionState.Connected) {
                    scheduleReconnect("Server closed: $code $reason")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (myConnectionId != activeConnectionId) return

                setupTimeoutJob?.cancel()
                val body = runCatching { response?.body?.string() }.getOrNull() ?: ""
                val code = response?.code ?: -1
                val errSummary = "${t.message} (HTTP $code $body)"

                Log.e(TAG, "WebSocket #$myConnectionId onFailure: $errSummary", t)
                diagnostics?.setLastClose(code, errSummary)
                diagnostics?.log(TAG, "Failure: $errSummary")

                // Check for 429 / Quota Exceeded / Rate Limit and failover to next key
                if (code == 429 || code == 403 || code == 401 || errSummary.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || errSummary.contains("quota", ignoreCase = true)) {
                    Log.w(TAG, "Rate limit / Quota exceeded on key ${currentApiKey.take(6)}... Marking EXCEEDED and rotating key.")
                    if (currentApiKey.isNotBlank()) {
                        preferences?.markKeyStatus(currentApiKey, "EXCEEDED")
                    }
                    val nextKey = preferences?.getPrimaryWorkingKey()
                    if (!nextKey.isNullOrBlank() && nextKey != currentApiKey) {
                        Log.i(TAG, "Failover: Retrying with next API key in pool: ${nextKey.take(6)}...")
                        currentApiKey = nextKey
                        reconnectAttempt = 0
                        startWebSocketConnection(backgroundSwap = true)
                        return
                    }
                }

                // Check for terminal HTTP response
                if (code in listOf(400, 401, 403, 404)) {
                    val classification = LiveSessionPolicy.classifyClose(code, errSummary, isSetupComplete, currentApiKey)
                    terminalError = true
                    val terminalMsg = if (classification is CloseClassification.Terminal) classification.userMessage else errSummary
                    _connectionState.value = LiveConnectionState.Error(terminalMsg, isTerminal = true)
                    diagnostics?.updateConnectionState("Error: $terminalMsg")
                    return
                }

                if (!intentionalDisconnect && !terminalError) {
                    scheduleReconnect("Network failure: ${t.message ?: "I/O Error"}")
                }
            }
        }

        webSocket = okHttpClient?.newWebSocket(request, socketListener)
    }

    private fun sendSetupMessage(ws: WebSocket) {
        val setupPayload = GeminiLiveHandshake.buildSetupMessageJson(
            model = currentModel,
            voice = currentVoice,
            systemPrompt = currentSystemPrompt,
            thinkingLevel = currentThinkingLevel,
            language = currentLanguage,
            toolDeclarationsJson = cachedToolDeclarations,
            interruptionSensitivity = currentInterruptionSensitivity,
            allowInterrupting = allowInterrupting,
            unsupportedFields = unsupportedFields,
            resumptionHandle = resumptionHandle ?: ""
        )
        diagnostics?.log(TAG, "Sending setup JSON (model=$currentModel, voice=$currentVoice, tools=${cachedToolDeclarations.size}, resume=${resumptionHandle != null})")
        ws.send(setupPayload)
    }

    private fun startSetupWatchdog(connectionId: Long) {
        setupTimeoutJob?.cancel()
        setupTimeoutJob = scope?.launch(Dispatchers.IO) {
            delay(10000L) // 10s setup timeout
            if (connectionId != activeConnectionId) return@launch
            if (!isSetupComplete && _connectionState.value !is LiveConnectionState.Error) {
                val frames = diagnostics?.framesReceivedCount?.value ?: 0
                val reason = if (frames == 0) {
                    "Setup timeout: 0 frames received within 10s (check API key / model)"
                } else {
                    "Setup timeout: frames received but setupComplete not delivered within 10s"
                }
                Log.e(TAG, reason)
                diagnostics?.log(TAG, reason)

                // If resumption handle was used and timed out, clear handle and retry fresh
                if (resumptionHandle != null) {
                    Log.w(TAG, "Setup timed out with resumption handle. Clearing handle and retrying fresh.")
                    resumptionHandle = null
                    scheduleReconnect("Setup timeout with handle", immediate = true, injectRecentContext = true)
                } else {
                    scheduleReconnect(reason)
                }
            }
        }
    }

    private fun processIncomingFrame(rawText: String, isBinary: Boolean) {
        markActivity()
        val parsed = GeminiLiveHandshake.parseAndLogIncomingFrame(rawText, isBinary, diagnostics)

        if (parsed.isSetupComplete) {
            setupTimeoutJob?.cancel()
            isSetupComplete = true
            reconnectAttempt = 0
            continuousFailureStartTime = 0L
            _connectionState.value = LiveConnectionState.Connected
            diagnostics?.setSetupComplete(true)
            diagnostics?.updateConnectionState("Connected")
            diagnostics?.resetReconnectCount()
            Log.i(TAG, "Setup complete confirmed on active connection #$activeConnectionId.")

            if (currentApiKey.isNotBlank()) {
                preferences?.markKeyStatus(currentApiKey, "WORKING")
            }

            // Send initial friendly greeting only once on the first connect of a call
            if (greetOnConnect && !hasDoneInitialGreeting) {
                hasDoneInitialGreeting = true
                scope?.launch {
                    delay(300L)
                    val owner = preferences?.ownerName?.first()?.ifBlank { "Boss" } ?: "Boss"
                    sendTextMessage("(Aura session connected. Greet $owner warmly in Hinglish with one short sentence, e.g. 'Haan $owner, bolo kya help chahiye?')")
                }
            }
            return
        }

        try {
            val root = JSONObject(rawText)

            // Track Token Usage Metadata if present
            if (root.has("usageMetadata")) {
                val usage = root.getJSONObject("usageMetadata")
                val prompt = usage.optInt("promptTokenCount", 0)
                val candidates = usage.optInt("candidatesTokenCount", 0)
                val total = usage.optInt("totalTokenCount", 0)
                diagnostics?.recordUsage(prompt, candidates, total)
            }

            if (root.has("serverContent")) {
                val serverContent = root.getJSONObject("serverContent")

                // Check for goAway event
                if (serverContent.has("goAway")) {
                    val goAway = serverContent.optJSONObject("goAway")
                    val timeDurationRemaining = goAway?.optString("timeDurationRemaining", "") ?: ""
                    Log.w(TAG, "Server sent goAway: remaining=$timeDurationRemaining. Initiating seamless background reconnect...")
                    diagnostics?.log(TAG, "Server goAway received")
                    handleGoAwaySeamlessSwap()
                    return
                }

                // Check for sessionResumptionUpdate
                if (serverContent.has("sessionResumptionUpdate")) {
                    val sru = serverContent.getJSONObject("sessionResumptionUpdate")
                    val handle = sru.optString("handle", "")
                    if (handle.isNotBlank()) {
                        resumptionHandle = handle
                        diagnostics?.log(TAG, "Updated resumption handle: ${handle.take(16)}...")
                    }
                }

                // Input Transcription (User speech)
                val inputTrans = serverContent.optJSONObject("inputTranscription")
                    ?: serverContent.optJSONObject("inputAudioTranscription")
                if (inputTrans != null) {
                    val text = inputTrans.optString("text", "")
                    if (text.isNotBlank()) {
                        captionStateMachine.onInputTranscriptionChunk(text)
                        diagnostics?.setCaptionTurnInfo(captionStateMachine.state.value.turnId, isAuraSpeaking = false)
                    }
                }

                // Output Transcription (Aura speech text)
                val outputTrans = serverContent.optJSONObject("outputTranscription")
                    ?: serverContent.optJSONObject("outputAudioTranscription")
                if (outputTrans != null) {
                    val text = outputTrans.optString("text", "")
                    if (text.isNotBlank()) {
                        captionStateMachine.onOutputTranscriptionChunk(text)
                        diagnostics?.setCaptionTurnInfo(captionStateMachine.state.value.turnId, isAuraSpeaking = true)
                    }
                }

                // Turn complete
                if (serverContent.optBoolean("turnComplete", false)) {
                    captionStateMachine.onTurnComplete()
                    _isAuraThinking.value = false
                    diagnostics?.setCaptionTurnInfo(captionStateMachine.state.value.turnId, isAuraSpeaking = false)

                    // Persist turn to conversation repository
                    val currentState = captionStateMachine.state.value
                    if (currentState.auraText.isNotBlank()) {
                        scope?.launch(Dispatchers.IO) {
                            memoryRepository?.logConversation(
                                speaker = "aura",
                                text = currentState.auraText,
                                sessionId = currentSessionId,
                                language = currentLanguage
                            )
                        }
                    }
                }

                // User interrupted Aura speech
                if (serverContent.optBoolean("interrupted", false)) {
                    Log.i(TAG, "Model speech interrupted by user")
                    audioPlayer.flushAndInterrupt()
                    captionStateMachine.onInterrupted()
                    _isAuraThinking.value = false
                    diagnostics?.incrementInterruptionCount()
                }

                // Model turn parts (Audio + Thought separation)
                if (serverContent.has("modelTurn")) {
                    val modelTurn = serverContent.getJSONObject("modelTurn")
                    val parts = modelTurn.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue

                            // If part is a thought, DO NOT put in captions; log only to diagnostics
                            val isThought = part.optBoolean("thought", false)
                            val textContent = part.optString("text", "")
                            if (textContent.isNotBlank()) {
                                if (isThought) {
                                    diagnostics?.recordThought(textContent)
                                    _isAuraThinking.value = true
                                }
                            }

                            // Audio PCM parts
                            if (part.has("inlineData")) {
                                val inlineData = part.getJSONObject("inlineData")
                                val mimeType = inlineData.optString("mimeType", "")
                                if (mimeType.startsWith("audio/")) {
                                    val base64Data = inlineData.getString("data")
                                    val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
                                    diagnostics?.recordAudioReceived(audioBytes.size.toLong())
                                    audioPlayer.playPcmChunk(audioBytes)
                                    _isAuraThinking.value = false
                                }
                            }
                        }
                    }
                }

                if (serverContent.optBoolean("generationComplete", false)) {
                    _isAuraThinking.value = false
                }
            }

            // Tool call dispatch
            if (root.has("toolCall")) {
                val toolCall = root.getJSONObject("toolCall")
                handleToolCall(toolCall)
            }

            if (root.has("toolCallCancellation")) {
                Log.w(TAG, "Tool calls cancelled by server")
                _isAuraActing.value = false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming frame: ${e.message}", e)
            diagnostics?.log(TAG, "Frame dispatch error: ${e.message}")
        }
    }

    private fun handleGoAwaySeamlessSwap() {
        scope?.launch {
            // Keep current connection active while starting replacement
            val oldSocket = webSocket
            Log.i(TAG, "Starting replacement WebSocket for goAway seamless swap...")
            startWebSocketConnection(backgroundSwap = true)

            // Wait for new socket setupComplete
            for (i in 0..40) {
                delay(200L)
                if (isSetupComplete && _connectionState.value == LiveConnectionState.Connected) {
                    Log.i(TAG, "Replacement socket connected. Closing old socket...")
                    try {
                        oldSocket?.close(1000, "goAway handoff complete")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error closing old socket during goAway swap", e)
                    }
                    return@launch
                }
            }

            // Fallback: close old socket anyway
            oldSocket?.close(1000, "goAway timeout fallback")
        }
    }

    fun sendAudioChunk(base64Pcm: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete || _connectionState.value != LiveConnectionState.Connected) return

        try {
            val root = JSONObject().apply {
                put("realtimeInput", JSONObject().apply {
                    put("audio", JSONObject().apply {
                        put("mimeType", "audio/pcm;rate=16000")
                        put("data", base64Pcm)
                    })
                })
            }
            ws.send(root.toString())
            val byteCount = base64Pcm.length * 3 / 4
            diagnostics?.recordAudioSent(byteCount.toLong())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio chunk", e)
        }
    }

    fun sendTextMessage(text: String) {
        val ws = webSocket ?: return
        if (!isSetupComplete || text.isBlank()) return
        markActivity()

        try {
            val root = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", text) })
                            })
                        })
                    })
                    put("turnComplete", true)
                })
            }
            ws.send(root.toString())
            diagnostics?.recordMessage("clientContent(text)")
            Log.d(TAG, "Sent text message to Live: $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send text message", e)
        }
    }

    /**
     * Injects recent conversation turns as hidden context without triggering model speech.
     */
    fun injectConversationContext(turns: List<Pair<String, String>>) {
        val ws = webSocket ?: return
        if (!isSetupComplete || turns.isEmpty()) return

        try {
            val turnsArray = JSONArray()
            turns.takeLast(10).forEach { (speaker, text) ->
                turnsArray.put(JSONObject().apply {
                    put("role", if (speaker == "user") "user" else "model")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", text) })
                    })
                })
            }

            val root = JSONObject().apply {
                put("clientContent", JSONObject().apply {
                    put("turns", turnsArray)
                    put("turnComplete", false)
                })
            }
            ws.send(root.toString())
            Log.i(TAG, "Injected ${turns.size} turns of hidden conversation context after reconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject conversation context", e)
        }
    }

    private fun handleToolCall(toolCall: JSONObject) {
        val functionCalls = toolCall.optJSONArray("functionCalls") ?: return
        val isExtendedThinking = currentModel.removePrefix("models/").trim()
            .equals("gemini-3.8-live-extended-thinking", ignoreCase = true)

        if (!isExtendedThinking) {
            _isAuraActing.value = true
        }

        scope?.launch(Dispatchers.Default) {
            executeToolsAsync(functionCalls, isExtendedThinking)
        }
    }

    private suspend fun executeToolsAsync(functionCalls: JSONArray, isExtendedThinking: Boolean) = coroutineScope {
        val responses = mutableListOf<JSONObject>()

        for (i in 0 until functionCalls.length()) {
            val call = functionCalls.optJSONObject(i) ?: continue
            val callId = call.optString("id", "")
            val name = call.optString("name", "")
            val argsObj = call.optJSONObject("args")

            val argsMap = mutableMapOf<String, Any?>()
            argsObj?.keys()?.forEach { k -> argsMap[k] = argsObj.opt(k) }

            Log.i(TAG, "Executing tool '$name' [callId=$callId]")
            diagnostics?.log(TAG, "Tool call: $name")

            val execResult = toolRegistry.executeTool(name, argsMap)

            val respObj = JSONObject().apply {
                put("id", callId)
                put("name", name)
                put("response", JSONObject().apply {
                    put("result", execResult.output)
                    put("success", execResult.success)
                })
            }
            responses.add(respObj)
        }

        sendToolResponses(responses, isExtendedThinking)
    }

    /**
     * Sends function call response back to Gemini Live API.
     */
    fun sendToolResponseDirect(callId: String, toolName: String, output: String, success: Boolean, scheduling: String = "WHEN_IDLE") {
        val ws = webSocket ?: return
        try {
            val responseObj = JSONObject().apply {
                put("id", callId)
                put("name", toolName)
                put("response", JSONObject().apply {
                    put("result", output)
                    put("success", success)
                    put("scheduling", scheduling)
                })
            }

            val root = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", JSONArray().apply { put(responseObj) })
                })
            }
            ws.send(root.toString())
            Log.i(TAG, "Sent async direct tool response for $toolName (scheduling=$scheduling)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending direct tool response for $toolName", e)
        }
    }

    private fun sendToolResponses(responses: List<JSONObject>, isExtendedThinking: Boolean) {
        val ws = webSocket ?: return

        try {
            val responsesArray = JSONArray()
            for (r in responses) {
                if (isExtendedThinking) {
                    val respInner = r.optJSONObject("response")
                    respInner?.put("scheduling", "WHEN_IDLE")
                }
                responsesArray.put(r)
            }

            val root = JSONObject().apply {
                put("toolResponse", JSONObject().apply {
                    put("functionResponses", responsesArray)
                })
            }

            ws.send(root.toString())
            _isAuraActing.value = false
            diagnostics?.recordMessage("toolResponse(${responses.size})")
            Log.i(TAG, "Sent tool responses for ${responses.size} tools to Live API")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send toolResponse", e)
            _isAuraActing.value = false
        }
    }

    /**
     * Seamlessly switches voice, language, or model during an active session.
     */
    fun reconnectWith(
        newVoice: String? = null,
        newModel: String? = null,
        newThinking: String? = null,
        newLang: String? = null
    ) {
        scope?.launch {
            Log.i(TAG, "reconnectWith initiated: voice=$newVoice, model=$newModel, lang=$newLang")
            isReconnectingForVoiceSwitch = true
            _connectionState.value = LiveConnectionState.Reconnecting(1, "Switching voice/settings")

            newVoice?.let { currentVoice = it }
            newModel?.let { currentModel = it }
            newThinking?.let { currentThinkingLevel = it }
            newLang?.let { currentLanguage = it }

            // Gracefully close current socket and mark as retired
            val oldSocket = webSocket
            webSocket = null
            isSetupComplete = false

            try {
                oldSocket?.close(1000, "Switching session configuration")
            } catch (e: Exception) {
                Log.e(TAG, "Error closing websocket during reconnectWith", e)
            }

            delay(300L)
            startWebSocketConnection()

            // After reconnection completes, greet in new voice
            launch {
                for (i in 0..30) {
                    delay(200L)
                    if (isSetupComplete && _connectionState.value == LiveConnectionState.Connected) {
                        sendTextMessage("(Aura switched voice/language. Say one short natural question in Hinglish like 'Ye wali awaaz theek hai?')")
                        break
                    }
                }
            }
        }
    }

    private fun scheduleReconnect(
        reason: String,
        immediate: Boolean = false,
        injectRecentContext: Boolean = false
    ) {
        if (terminalError || intentionalDisconnect) {
            Log.w(TAG, "Bypassing reconnect: terminalError=$terminalError, intentionalDisconnect=$intentionalDisconnect")
            return
        }

        if (!isNetworkAvailable) {
            Log.i(TAG, "Network not available; pausing reconnect attempts until network returns.")
            _connectionState.value = LiveConnectionState.Reconnecting(reconnectAttempt, "No internet")
            return
        }

        if (continuousFailureStartTime == 0L) {
            continuousFailureStartTime = System.currentTimeMillis()
        } else {
            val failureDuration = System.currentTimeMillis() - continuousFailureStartTime
            if (failureDuration >= LiveSessionPolicy.MAX_CONTINUOUS_FAILURE_WINDOW_MS) {
                Log.e(TAG, "Continuous failure window (10 minutes) exceeded. Ending session gracefully.")
                endSessionGracefully("Session ended after 10m of continuous connection issues")
                return
            }
        }

        reconnectAttempt++
        diagnostics?.incrementReconnectCount()
        _connectionState.value = LiveConnectionState.Reconnecting(reconnectAttempt, reason)
        diagnostics?.updateConnectionState("Reconnecting ($reconnectAttempt)")

        val backoffMs = if (immediate) 100L else LiveSessionPolicy.calculateBackoffMs(reconnectAttempt)
        Log.i(TAG, "Scheduling reconnect #$reconnectAttempt in ${backoffMs}ms: $reason")

        reconnectJob?.cancel()
        reconnectJob = scope?.launch {
            delay(backoffMs)
            if (!intentionalDisconnect && !terminalError) {
                startWebSocketConnection()

                if (injectRecentContext) {
                    // Inject last turns after setup completes
                    launch {
                        for (i in 0..30) {
                            delay(200L)
                            if (isSetupComplete && _connectionState.value == LiveConnectionState.Connected) {
                                val turns = memoryRepository?.getRecentTurnsSync(8)
                                    ?.reversed()
                                    ?.map { it.speaker to it.text } ?: emptyList()
                                if (turns.isNotEmpty()) {
                                    injectConversationContext(turns)
                                }
                                break
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleSetup1007Retry(reason: String) {
        val offendingField = GeminiLiveHandshake.extractOffendingFieldFrom1007(reason)
        if (offendingField != null && !unsupportedFields.contains(offendingField)) {
            Log.w(TAG, "1007 setup rejection detected for field '$offendingField'. Retrying without it...")
            unsupportedFields.add(offendingField)
            scope?.launch {
                try {
                    AuraApplication.instance.applicationContext.let { ctx ->
                        com.example.core.settings.AuraPreferences(ctx).recordUnsupportedSetupField(offendingField)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed storing unsupported field $offendingField", e)
                }
                delay(300L)
                startWebSocketConnection()
            }
        } else {
            terminalError = true
            val err = "Setup argument error (1007): $reason"
            _connectionState.value = LiveConnectionState.Error(err, isTerminal = true)
            diagnostics?.updateConnectionState("Error (1007): $reason")
        }
    }

    private fun endSessionGracefully(reason: String) {
        intentionalDisconnect = true
        _connectionState.value = LiveConnectionState.Ended(reason)
        diagnostics?.updateConnectionState("Ended: $reason")
        disconnect(isIntentional = true)
        onSessionEndedListener?.invoke(reason)
    }

    fun disconnect(isIntentional: Boolean = true) {
        intentionalDisconnect = isIntentional
        reconnectJob?.cancel()
        setupTimeoutJob?.cancel()
        silenceTimeoutJob?.cancel()
        screenOffJob?.cancel()

        val socketToClose = webSocket
        webSocket = null
        activeConnectionId++ // Retire previous socket id so its callbacks are ignored

        try {
            socketToClose?.close(1000, if (isIntentional) "Session closed by user" else "Reconnecting")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing websocket", e)
        }

        isSetupComplete = false
        _isAuraActing.value = false
        _isAuraThinking.value = false

        if (isIntentional) {
            _connectionState.value = LiveConnectionState.Disconnected
            diagnostics?.updateConnectionState("Disconnected")
            audioPlayer.flushAndInterrupt()
        }
    }
}
