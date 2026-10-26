package com.example.core.live

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticLogEntry(
    val timestamp: Long,
    val tag: String,
    val message: String
)

class LiveDiagnostics {
    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _currentModel = MutableStateFlow("gemini-3.8-live")
    val currentModel: StateFlow<String> = _currentModel.asStateFlow()

    private val _currentVoice = MutableStateFlow("Aoede")
    val currentVoice: StateFlow<String> = _currentVoice.asStateFlow()

    private val _currentLanguage = MutableStateFlow("Hinglish")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    private val _lastCloseCodeAndReason = MutableStateFlow("None")
    val lastCloseCodeAndReason: StateFlow<String> = _lastCloseCodeAndReason.asStateFlow()

    private val _lastServerMessageTypes = MutableStateFlow<List<String>>(emptyList())
    val lastServerMessageTypes: StateFlow<List<String>> = _lastServerMessageTypes.asStateFlow()

    private val _bytesAudioSent = MutableStateFlow(0L)
    val bytesAudioSent: StateFlow<Long> = _bytesAudioSent.asStateFlow()

    private val _bytesAudioReceived = MutableStateFlow(0L)
    val bytesAudioReceived: StateFlow<Long> = _bytesAudioReceived.asStateFlow()

    private val _setupCompleteArrived = MutableStateFlow(false)
    val setupCompleteArrived: StateFlow<Boolean> = _setupCompleteArrived.asStateFlow()

    private val _micPermissionStatus = MutableStateFlow("Not Checked")
    val micPermissionStatus: StateFlow<String> = _micPermissionStatus.asStateFlow()

    private val _audioRecordState = MutableStateFlow("Idle")
    val audioRecordState: StateFlow<String> = _audioRecordState.asStateFlow()

    private val _audioTrackState = MutableStateFlow("Idle")
    val audioTrackState: StateFlow<String> = _audioTrackState.asStateFlow()

    // Frame logging and stats
    private val _framesReceivedCount = MutableStateFlow(0)
    val framesReceivedCount: StateFlow<Int> = _framesReceivedCount.asStateFlow()

    private val _textFramesCount = MutableStateFlow(0)
    val textFramesCount: StateFlow<Int> = _textFramesCount.asStateFlow()

    private val _binaryFramesCount = MutableStateFlow(0)
    val binaryFramesCount: StateFlow<Int> = _binaryFramesCount.asStateFlow()

    private val _socketOpenedTimestamp = MutableStateFlow(0L)
    val socketOpenedTimestamp: StateFlow<Long> = _socketOpenedTimestamp.asStateFlow()

    private val _httpUpgradeResponseCode = MutableStateFlow(0)
    val httpUpgradeResponseCode: StateFlow<Int> = _httpUpgradeResponseCode.asStateFlow()

    private val _lastFramePreview = MutableStateFlow("None")
    val lastFramePreview: StateFlow<String> = _lastFramePreview.asStateFlow()

    private val _firstFrameReceivedContent = MutableStateFlow("")
    val firstFrameReceivedContent: StateFlow<String> = _firstFrameReceivedContent.asStateFlow()

    private val _authKeyType = MutableStateFlow("Not Checked")
    val authKeyType: StateFlow<String> = _authKeyType.asStateFlow()

    // Wake Word & Model Download
    private val _wakeWordState = MutableStateFlow("Idle")
    val wakeWordState: StateFlow<String> = _wakeWordState.asStateFlow()

    private val _modelDownloadState = MutableStateFlow("Ready")
    val modelDownloadState: StateFlow<String> = _modelDownloadState.asStateFlow()

    // Noise gate & Audio stats
    private val _noiseFloor = MutableStateFlow("120 RMS")
    val noiseFloor: StateFlow<String> = _noiseFloor.asStateFlow()

    private val _noiseGateStatus = MutableStateFlow("Closed")
    val noiseGateStatus: StateFlow<String> = _noiseGateStatus.asStateFlow()

    private val _recentInterruptionsCount = MutableStateFlow(0)
    val recentInterruptionsCount: StateFlow<Int> = _recentInterruptionsCount.asStateFlow()

    // Captions & Overlay
    private val _captionTurnInfo = MutableStateFlow("Turn 0")
    val captionTurnInfo: StateFlow<String> = _captionTurnInfo.asStateFlow()

    private val _overlayStateInfo = MutableStateFlow("Inactive")
    val overlayStateInfo: StateFlow<String> = _overlayStateInfo.asStateFlow()

    private val _overlayPassThroughStatus = MutableStateFlow("Overlay pass-through OK (alpha<=0.79)")
    val overlayPassThroughStatus: StateFlow<String> = _overlayPassThroughStatus.asStateFlow()

    fun updateOverlayPassThroughStatus() {
        val attached = com.example.ui.overlay.OverlayWindowDefaults.getAttachedWindows()
        if (attached.isEmpty()) {
            _overlayPassThroughStatus.value = "Overlay pass-through OK (0 windows attached)"
            return
        }
        val hasViolation = attached.any { it.value > com.example.ui.overlay.OverlayWindowDefaults.MAX_PASSTHROUGH_ALPHA }
        val desc = attached.entries.joinToString { "${it.key}: ${it.value}" }
        if (hasViolation) {
            _overlayPassThroughStatus.value = "WARNING: Window alpha > 0.8 blocks touches! ($desc)"
        } else {
            _overlayPassThroughStatus.value = "Overlay pass-through OK: alpha<=0.79 on all windows ($desc)"
        }
    }

    // Permission Doctor & App Inventory
    private val _permissionDoctorReport = MutableStateFlow("All systems verified")
    val permissionDoctorReport: StateFlow<String> = _permissionDoctorReport.asStateFlow()

    private val _appInventorySize = MutableStateFlow("0 apps")
    val appInventorySize: StateFlow<String> = _appInventorySize.asStateFlow()

    // Token Usage & Session Stats
    private val _reconnectCount = MutableStateFlow(0)
    val reconnectCount: StateFlow<Int> = _reconnectCount.asStateFlow()

    private val _promptTokens = MutableStateFlow(0)
    val promptTokens: StateFlow<Int> = _promptTokens.asStateFlow()

    private val _candidateTokens = MutableStateFlow(0)
    val candidateTokens: StateFlow<Int> = _candidateTokens.asStateFlow()

    private val _totalTokens = MutableStateFlow(0)
    val totalTokens: StateFlow<Int> = _totalTokens.asStateFlow()

    private val _sessionStartTimeMs = MutableStateFlow(0L)
    val sessionStartTimeMs: StateFlow<Long> = _sessionStartTimeMs.asStateFlow()

    private val _thoughtHistory = MutableStateFlow<List<String>>(emptyList())
    val thoughtHistory: StateFlow<List<String>> = _thoughtHistory.asStateFlow()

    private val _logHistory = MutableStateFlow<List<DiagnosticLogEntry>>(emptyList())
    val logHistory: StateFlow<List<DiagnosticLogEntry>> = _logHistory.asStateFlow()

    fun resetForNewConnection() {
        _setupCompleteArrived.value = false
        _framesReceivedCount.value = 0
        _textFramesCount.value = 0
        _binaryFramesCount.value = 0
        _socketOpenedTimestamp.value = 0L
        _httpUpgradeResponseCode.value = 0
        _lastFramePreview.value = "None"
        _firstFrameReceivedContent.value = ""
        _lastServerMessageTypes.value = emptyList()
        _recentInterruptionsCount.value = 0
    }

    fun updateConnectionState(state: String) { _connectionState.value = state }
    fun setModel(model: String) { _currentModel.value = model }
    fun setVoice(voice: String) { _currentVoice.value = voice }
    fun setLanguage(lang: String) { _currentLanguage.value = lang }
    fun setAuthKey(key: String) { _authKeyType.value = GeminiLiveHandshake.getKeyTypeDescription(key) }
    fun setSocketOpened(httpCode: Int) {
        _socketOpenedTimestamp.value = System.currentTimeMillis()
        _httpUpgradeResponseCode.value = httpCode
    }
    fun setLastClose(code: Int, reason: String) { _lastCloseCodeAndReason.value = "Code $code: $reason" }
    fun setSetupComplete(arrived: Boolean) { _setupCompleteArrived.value = arrived }
    fun setMicPermission(status: String) { _micPermissionStatus.value = status }
    fun setAudioRecordState(state: String) { _audioRecordState.value = state }
    fun setAudioTrackState(state: String) { _audioTrackState.value = state }
    fun recordAudioSent(bytes: Long) { _bytesAudioSent.value += bytes }
    fun recordAudioReceived(bytes: Long) { _bytesAudioReceived.value += bytes }

    fun recordUsage(prompt: Int, candidates: Int, total: Int) {
        _promptTokens.value += prompt
        _candidateTokens.value += candidates
        _totalTokens.value += total
    }
    fun incrementReconnectCount() { _reconnectCount.value += 1 }
    fun resetReconnectCount() { _reconnectCount.value = 0 }
    fun setSessionStartTime(timeMs: Long) { _sessionStartTimeMs.value = timeMs }

    fun setWakeWordState(state: String) { _wakeWordState.value = state }
    fun setModelDownloadState(state: String) { _modelDownloadState.value = state }
    fun setNoiseFloor(floor: Double) { _noiseFloor.value = "%.1f RMS".format(floor) }
    fun setGateStatus(isOpen: Boolean, gatedFrames: Long) {
        _noiseGateStatus.value = if (isOpen) "OPEN" else "CLOSED ($gatedFrames gated)"
    }
    fun incrementInterruptionCount() { _recentInterruptionsCount.value += 1 }
    fun setCaptionTurnInfo(turnId: Long, isAuraSpeaking: Boolean) {
        _captionTurnInfo.value = "Turn $turnId ${if (isAuraSpeaking) "(Aura Speaking)" else "(Idle)"}"
    }
    fun setOverlayState(info: String) { _overlayStateInfo.value = info }
    fun setPermissionReport(report: String) { _permissionDoctorReport.value = report }
    fun setAppInventorySize(userCount: Int, systemCount: Int) {
        _appInventorySize.value = "${userCount + systemCount} apps ($userCount user, $systemCount system)"
    }

    fun recordThought(thought: String) {
        val clean = thought.trim()
        if (clean.isBlank()) return
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        val list = _thoughtHistory.value.toMutableList()
        list.add(0, "[$time] ${clean.take(200)}")
        if (list.size > 20) list.removeLast()
        _thoughtHistory.value = list
        log("AuraThought", clean.take(120))
    }

    fun recordIncomingFrame(isBinary: Boolean, byteLength: Int, rawText: String) {
        _framesReceivedCount.value += 1
        if (isBinary) _binaryFramesCount.value += 1 else _textFramesCount.value += 1
        val preview = rawText.take(300)
        val frameTypeStr = if (isBinary) "BINARY" else "TEXT"
        _lastFramePreview.value = "[$frameTypeStr $byteLength B] $preview"
        if (_firstFrameReceivedContent.value.isEmpty()) _firstFrameReceivedContent.value = preview
        Log.d("Aura", "Live Frame [$frameTypeStr] length=$byteLength: $preview")
        log("Aura", "Frame [$frameTypeStr] ${byteLength}B: ${preview.take(120)}")
    }

    fun recordServerMessageType(type: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val current = _lastServerMessageTypes.value.toMutableList()
        current.add(0, "[$time] $type")
        if (current.size > 50) current.removeLast()
        _lastServerMessageTypes.value = current
    }

    fun recordMessage(type: String) = recordServerMessageType(type)

    fun log(tag: String, message: String) {
        val entry = DiagnosticLogEntry(System.currentTimeMillis(), tag, message)
        val list = _logHistory.value.toMutableList()
        list.add(0, entry)
        if (list.size > 100) list.removeLast()
        _logHistory.value = list
    }

    fun getUptimeFormatted(): String {
        val opened = _socketOpenedTimestamp.value
        if (opened <= 0L) return "Not open"
        val seconds = (System.currentTimeMillis() - opened) / 1000
        return "${seconds}s"
    }

    fun getFullLogText(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val sb = StringBuilder()
        sb.append("=== AURA LIVE DIAGNOSTICS ===\n")
        sb.append("Timestamp: ${sdf.format(Date())}\n")
        sb.append("State: ${_connectionState.value}\n")
        sb.append("Model: ${_currentModel.value}\n")
        sb.append("Voice: ${_currentVoice.value}\n")
        sb.append("Language: ${_currentLanguage.value}\n")
        sb.append("Auth: ${_authKeyType.value}\n")
        sb.append("Wake Word: ${_wakeWordState.value}\n")
        sb.append("Model Download: ${_modelDownloadState.value}\n")
        sb.append("Noise Floor: ${_noiseFloor.value}\n")
        sb.append("Noise Gate: ${_noiseGateStatus.value}\n")
        sb.append("Recent Interruptions: ${_recentInterruptionsCount.value}\n")
        sb.append("Caption Info: ${_captionTurnInfo.value}\n")
        sb.append("Overlay: ${_overlayStateInfo.value}\n")
        updateOverlayPassThroughStatus()
        sb.append("Overlay Pass-Through: ${_overlayPassThroughStatus.value}\n")
        sb.append("Permission Doctor: ${_permissionDoctorReport.value}\n")
        sb.append("App Inventory: ${_appInventorySize.value}\n")
        sb.append("SetupComplete: ${_setupCompleteArrived.value}\n")
        sb.append("HTTP Upgrade Code: ${_httpUpgradeResponseCode.value}\n")
        sb.append("Socket Uptime: ${getUptimeFormatted()}\n")
        sb.append("Frames Received: ${_framesReceivedCount.value} (text ${_textFramesCount.value} / binary ${_binaryFramesCount.value})\n")
        sb.append("Last Frame: ${_lastFramePreview.value}\n")
        sb.append("Last Close: ${_lastCloseCodeAndReason.value}\n")
        sb.append("Audio Sent: ${_bytesAudioSent.value} bytes\n")
        sb.append("Audio Received: ${_bytesAudioReceived.value} bytes\n\n")

        if (_thoughtHistory.value.isNotEmpty()) {
            sb.append("--- Recent Model Thoughts (Non-Speech) ---\n")
            _thoughtHistory.value.forEach { sb.append("$it\n") }
            sb.append("\n")
        }

        sb.append("--- Last 50 Server Messages ---\n")
        _lastServerMessageTypes.value.forEach { sb.append("$it\n") }
        sb.append("\n--- Event Log ---\n")
        _logHistory.value.forEach { entry ->
            sb.append("${sdf.format(Date(entry.timestamp))} [${entry.tag}] ${entry.message}\n")
        }
        return sb.toString()
    }
}
