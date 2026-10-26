package com.example.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.*
import android.net.ConnectivityManager
import android.net.Network
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.AuraApplication
import com.example.MainActivity
import com.example.R
import com.example.core.agent.AgentLoop
import com.example.core.agent.PersonaBuilder
import com.example.core.audio.*
import com.example.core.live.GeminiLiveClient
import com.example.core.live.LiveConnectionState
import com.example.core.memory.MemoryRepository
import com.example.core.root.PermissionDoctor
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.tools.AppInventory
import com.example.core.tools.ToolRegistry
import com.example.ui.overlay.EdgeLightingOverlay
import com.example.ui.overlay.OverlayController
import com.example.core.telephony.CallAnnouncementManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first

enum class ServiceMode {
    STOPPED,
    WAKE_LISTENING,
    IN_CALL
}

class AuraForegroundService : Service() {

    companion object {
        private const val TAG = "Aura"

        const val ACTION_START_CALL = "com.example.aura.START_CALL"
        const val ACTION_END_CALL = "com.example.aura.END_CALL"
        const val ACTION_START_WAKE_LISTENING = "com.example.aura.START_WAKE_LISTENING"
        const val ACTION_STOP_SERVICE = "com.example.aura.STOP_SERVICE"
        const val ACTION_TOGGLE_MUTE = "com.example.aura.TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.aura.TOGGLE_SPEAKER"

        const val CHANNEL_ID = "aura_live_call_channel"
        const val NOTIFICATION_ID = 1001

        private val _isCallActive = MutableStateFlow(false)
        val isCallActive: StateFlow<Boolean> = _isCallActive.asStateFlow()

        private val _serviceMode = MutableStateFlow(ServiceMode.STOPPED)
        val serviceMode: StateFlow<ServiceMode> = _serviceMode.asStateFlow()

        var currentService: AuraForegroundService? = null
            private set

        fun startCall(context: Context) {
            try {
                val intent = Intent(context, AuraForegroundService::class.java).apply {
                    action = ACTION_START_CALL
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start call foreground service: ${e.message}", e)
            }
        }

        fun endCall(context: Context) {
            try {
                val intent = Intent(context, AuraForegroundService::class.java).apply {
                    action = ACTION_END_CALL
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to end call service: ${e.message}", e)
            }
        }

        fun startWakeListening(context: Context) {
            try {
                val intent = Intent(context, AuraForegroundService::class.java).apply {
                    action = ACTION_START_WAKE_LISTENING
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start wake listening: ${e.message}", e)
            }
        }

        fun stopServiceCompletely(context: Context) {
            try {
                val intent = Intent(context, AuraForegroundService::class.java).apply {
                    action = ACTION_STOP_SERVICE
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service: ${e.message}", e)
            }
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    lateinit var preferences: AuraPreferences
    lateinit var memoryRepository: MemoryRepository
    lateinit var rootManager: RootManager
    lateinit var permissionDoctor: PermissionDoctor
    lateinit var appInventory: AppInventory
    lateinit var toolRegistry: ToolRegistry
    lateinit var audioStreamer: AudioRecordStreamer
    lateinit var audioPlayer: AudioTrackPlayer
    lateinit var liveClient: GeminiLiveClient
    lateinit var agentLoop: AgentLoop
    lateinit var voskModelManager: VoskModelManager
    lateinit var wakePhraseDetector: WakePhraseDetector
    lateinit var overlayController: OverlayController
    lateinit var callAnnouncementManager: CallAnnouncementManager

    private val diagnostics get() = AuraApplication.instance.liveDiagnostics

    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private var isNetworkCallbackRegistered = false
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            liveClient.onNetworkAvailable()
        }
        override fun onLost(network: Network) {
            liveClient.onNetworkLost()
        }
    }

    private var isScreenReceiverRegistered = false
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> liveClient.onScreenStateChanged(true)
                Intent.ACTION_SCREEN_OFF -> liveClient.onScreenStateChanged(false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        try {
            preferences = AuraPreferences(this)
            memoryRepository = MemoryRepository(this)
            rootManager = RootManager(this)
            permissionDoctor = PermissionDoctor(this, rootManager)
            appInventory = AppInventory(this, rootManager)
            agentLoop = AgentLoop(rootManager)
            voskModelManager = VoskModelManager(this)

            audioPlayer = AudioTrackPlayer(this)
            audioStreamer = AudioRecordStreamer { base64Chunk ->
                if (::liveClient.isInitialized) {
                    liveClient.sendAudioChunk(base64Chunk)
                }
            }

            wakePhraseDetector = WakePhraseDetector(this, voskModelManager) {
                handleWakePhraseDetected()
            }

            toolRegistry = ToolRegistry(
                context = this,
                rootManager = rootManager,
                preferences = preferences,
                memoryRepository = memoryRepository,
                agentLoop = agentLoop,
                appInventory = appInventory,
                permissionDoctor = permissionDoctor,
                onLanguageChange = { newLang ->
                    if (::liveClient.isInitialized) {
                        liveClient.reconnectWith(newLang = newLang)
                    }
                },
                onVoiceChange = { newVoice ->
                    if (::liveClient.isInitialized) {
                        liveClient.reconnectWith(newVoice = newVoice)
                    }
                },
                onEndCall = {
                    terminateCall()
                }
            )

            liveClient = GeminiLiveClient(toolRegistry, audioPlayer, memoryRepository).apply {
                onSilenceTimeoutListener = {
                    terminateCall()
                }
                onSessionEndedListener = { _ ->
                    terminateCall()
                }
            }

            try {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
                isNetworkCallbackRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering network callback", e)
            }

            try {
                val filter = IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }
                registerReceiver(screenReceiver, filter)
                isScreenReceiverRegistered = true
            } catch (e: Exception) {
                Log.e(TAG, "Failed registering screen receiver", e)
            }

            overlayController = AuraApplication.instance.overlayController

            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aura:CallWakeLock").apply {
                setReferenceCounted(false)
            }

            createNotificationChannel()
            callAnnouncementManager = CallAnnouncementManager(this, preferences, rootManager)
            observeLiveState()

            // Publish instance only after all fields are fully initialized!
            currentService = this
        } catch (e: Throwable) {
            Log.e(TAG, "Fatal failure during AuraForegroundService.onCreate: ${e.message}", e)
        }
    }

    private fun observeLiveState() {
        serviceScope.launch {
            preferences.assistantName.collectLatest { name ->
                wakePhraseDetector.assistantName = name
            }
        }

        serviceScope.launch {
            liveClient.captionState.collectLatest { caption ->
                overlayController.updateCaptions(caption)
                if (::callAnnouncementManager.isInitialized && callAnnouncementManager.isRinging && caption.userText.isNotBlank()) {
                    callAnnouncementManager.handleVoiceCallDecision(caption.userText)
                }
            }
        }

        serviceScope.launch {
            audioStreamer.micAmplitude.collectLatest { mic ->
                overlayController.updateAmplitudes(mic, audioPlayer.speakerAmplitude.value)
            }
        }

        serviceScope.launch {
            audioPlayer.speakerAmplitude.collectLatest { spk ->
                overlayController.updateAmplitudes(audioStreamer.micAmplitude.value, spk)
                audioStreamer.isAuraSpeaking = spk > 0.04f
            }
        }

        serviceScope.launch {
            preferences.headphonesMode.collectLatest { hp ->
                audioStreamer.isHeadphonesMode = hp
            }
        }

        val voiceVerifier = com.example.core.audio.OwnerVoiceVerifier(this)
        audioStreamer.ownerVoiceVerifier = voiceVerifier

        serviceScope.launch {
            preferences.ownerVoiceLockEnabled.collectLatest { lockEnabled ->
                audioStreamer.ownerVoiceLockEnabled = lockEnabled
            }
        }

        serviceScope.launch {
            preferences.noiseGateStrength.collectLatest { strength ->
                audioStreamer.noiseGateStrength = strength
            }
        }

        serviceScope.launch {
            audioStreamer.noiseFloor.collectLatest { nf ->
                diagnostics.setNoiseFloor(nf)
            }
        }

        serviceScope.launch {
            audioStreamer.isGateOpen.collectLatest { isOpen ->
                diagnostics.setGateStatus(isOpen, audioStreamer.gatedFramesCount.value)
            }
        }

        fun syncOverlayStates() {
            if (!::overlayController.isInitialized) return
            serviceScope.launch {
                overlayController.updateCallState(
                    isCallActive = _isCallActive.value,
                    connectionState = if (::liveClient.isInitialized) liveClient.connectionState.value else LiveConnectionState.Disconnected,
                    isAuraActing = if (::liveClient.isInitialized) liveClient.isAuraActing.value else false,
                    isAuraThinking = if (::liveClient.isInitialized) liveClient.isAuraThinking.value else false,
                    isMuted = if (::audioStreamer.isInitialized) audioStreamer.isMuted.value else false,
                    speakerAmp = if (::audioPlayer.isInitialized) audioPlayer.speakerAmplitude.value else 0f
                )
                updateNotification()
            }
        }

        serviceScope.launch {
            _isCallActive.collectLatest { syncOverlayStates() }
        }

        serviceScope.launch {
            liveClient.connectionState.collectLatest { syncOverlayStates() }
        }

        serviceScope.launch {
            liveClient.isAuraActing.collectLatest { syncOverlayStates() }
        }

        serviceScope.launch {
            liveClient.isAuraThinking.collectLatest { syncOverlayStates() }
        }

        serviceScope.launch {
            audioStreamer.isMuted.collectLatest { syncOverlayStates() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_CALL -> {
                startForegroundNotification()
                initiateCall()
            }
            ACTION_START_WAKE_LISTENING -> {
                startForegroundNotification()
                initiateWakeListening()
            }
            ACTION_END_CALL -> {
                terminateCall()
            }
            ACTION_STOP_SERVICE -> {
                stopForegroundAndSelf()
            }
            ACTION_TOGGLE_MUTE -> {
                val current = audioStreamer.isMuted.value
                audioStreamer.setMuted(!current)
                updateNotification()
            }
            ACTION_TOGGLE_SPEAKER -> {
                serviceScope.launch {
                    val current = preferences.useSpeaker.first()
                    preferences.setUseSpeaker(!current)
                    audioPlayer.setSpeakerphoneOn(!current)
                    updateNotification()
                }
            }
        }
        return START_STICKY
    }

    private fun handleWakePhraseDetected() {
        serviceScope.launch(Dispatchers.Main) {
            Log.i(TAG, "Wake word triggered! Transitioning from WAKE_LISTENING to IN_CALL")

            // 1. Chime + Haptic
            playWakeFeedback()

            // 2. Start call directly in same service
            initiateCall()
        }
    }

    private fun playWakeFeedback() {
        try {
            // Haptic
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 50, 60), -1))
            }

            // Audio Chime
            val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP2, 120)
            serviceScope.launch {
                delay(200)
                toneGen.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing wake feedback: ${e.message}", e)
        }
    }

    private fun initiateWakeListening() {
        if (AuraApplication.isSafeMode.value) {
            Log.w(TAG, "Safe Mode is active: wake word listening disabled")
            diagnostics.setWakeWordState("Disabled (Safe Mode)")
            _isCallActive.value = false
            _serviceMode.value = ServiceMode.STOPPED
            updateNotification()
            return
        }

        _isCallActive.value = false
        _serviceMode.value = ServiceMode.WAKE_LISTENING

        // Stop call audio capture & player if active
        audioStreamer.stop()
        audioPlayer.stop()
        abandonVoiceAudioFocus()

        serviceScope.launch {
            val name = preferences.assistantName.first()
            val sensitivity = preferences.wakeWordSensitivity.first()
            wakePhraseDetector.assistantName = name
            wakePhraseDetector.sensitivity = sensitivity
            diagnostics.setWakeWordState("Listening for '$name' / 'Aura'")

            val started = wakePhraseDetector.startListening(serviceScope)
            if (started) {
                diagnostics.setWakeWordState("Active ('$name')")
            } else {
                diagnostics.setWakeWordState("Idle / Model Not Ready")
            }
            updateNotification()
        }
    }

    private fun requestVoiceAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttrs)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.d(TAG, "Voice audio focus changed: $focusChange")
                    }
                    .build()

                audioFocusRequest = focusRequest
                val result = audioManager.requestAudioFocus(focusRequest)
                Log.i(TAG, "Requested USAGE_VOICE_COMMUNICATION audio focus: result=$result")
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager.requestAudioFocus(
                    { focusChange -> Log.d(TAG, "Voice audio focus changed: $focusChange") },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                Log.i(TAG, "Requested legacy voice audio focus: result=$result")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request voice audio focus", e)
        }
    }

    private fun abandonVoiceAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
            Log.i(TAG, "Abandoned voice audio focus")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to abandon voice audio focus", e)
        }
    }

    private fun initiateCall() {
        if (_isCallActive.value) return

        try {
            // 1. CRITICAL: Stop and release wake detector before call capture starts
            wakePhraseDetector.stopListening()
            diagnostics.setWakeWordState("Call Active")

            _isCallActive.value = true
            _serviceMode.value = ServiceMode.IN_CALL
            try {
                wakeLock?.acquire(3 * 3600 * 1000L) // 3h max
            } catch (e: Exception) {
                Log.w(TAG, "Failed acquiring wakeLock: ${e.message}")
            }

            requestVoiceAudioFocus()

            serviceScope.launch {
                try {
                    val apiKey = preferences.apiKey.first()
                    val model = preferences.modelName.first()
                    val voice = preferences.voiceName.first()
                    val thinkingLevel = preferences.thinkingLevel.first()
                    val language = preferences.defaultLanguage.first()
                    val interruptionSens = preferences.interruptionSensitivity.first()
                    val allowInterrupt = preferences.allowInterrupting.first()
                    val greet = preferences.greetOnConnect.first()
                    val autoHangup = preferences.autoHangupSeconds.first()
                    val unsupported = preferences.unsupportedSetupFields.first()

                    // Assembles fresh system instruction with AppInventory & device context
                    val fullPrompt = PersonaBuilder.assembleSystemInstruction(
                        context = this@AuraForegroundService,
                        preferences = preferences,
                        memoryRepository = memoryRepository,
                        appInventory = appInventory
                    )

                    val useSpeaker = preferences.useSpeaker.first()
                    audioPlayer.setSpeakerphoneOn(useSpeaker)

                    audioPlayer.start(serviceScope)
                    audioStreamer.start(serviceScope)

                    val idleHangupMinutes = preferences.idleSilenceMinutes.first()
                    val keepScreenOffAlive = preferences.keepAliveScreenOff.first()
                    val screenOffTimeoutMin = preferences.screenOffTimeoutMinutes.first()

                    // Silent health check right after starting call
                    permissionDoctor.runSilentHealthCheck()

                    // Listen for errors or ended state to release mic/speaker and reset active call state
                    launch {
                        liveClient.connectionState.collect { state ->
                            if (state is LiveConnectionState.Error || state is LiveConnectionState.Ended) {
                                _isCallActive.value = false
                                audioStreamer.stop()
                                audioPlayer.stop()
                                abandonVoiceAudioFocus()
                                checkReturnToWakeListening()
                            }
                        }
                    }

                    liveClient.connect(
                        coroutineScope = serviceScope,
                        apiKey = apiKey,
                        model = model,
                        voice = voice,
                        systemPrompt = fullPrompt,
                        thinkingLevel = thinkingLevel,
                        language = language,
                        interruptionSensitivity = interruptionSens,
                        allowInterrupt = allowInterrupt,
                        greet = greet,
                        autoHangup = autoHangup,
                        idleHangupMinutes = idleHangupMinutes,
                        keepScreenOffAlive = keepScreenOffAlive,
                        screenOffTimeoutMin = screenOffTimeoutMin,
                        unsupported = unsupported
                    )
                } catch (e: Throwable) {
                    Log.e(TAG, "Exception during initiateCall launch: ${e.message}", e)
                    _isCallActive.value = false
                    runCatching { audioStreamer.stop() }
                    runCatching { audioPlayer.stop() }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed initiating call: ${e.message}", e)
            _isCallActive.value = false
        }
    }

    private fun terminateCall() {
        _isCallActive.value = false
        liveClient.disconnect()
        audioStreamer.stop()
        audioPlayer.stop()
        abandonVoiceAudioFocus()

        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wakeLock", e)
        }

        checkReturnToWakeListening()
    }

    private fun checkReturnToWakeListening() {
        serviceScope.launch {
            val wakeEnabled = preferences.wakePhraseEnabled.first()
            if (wakeEnabled) {
                Log.i(TAG, "Wake word enabled in settings. Returning to WAKE_LISTENING mode.")
                initiateWakeListening()
            } else {
                Log.i(TAG, "Wake word disabled. Stopping foreground service completely.")
                stopForegroundAndSelf()
            }
        }
    }

    private fun stopForegroundAndSelf() {
        _isCallActive.value = false
        _serviceMode.value = ServiceMode.STOPPED
        wakePhraseDetector.stopListening()
        overlayController.hideAll()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasAudioPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                val fgsType = if (hasAudioPerm) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIFICATION_ID, notification, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val hasAudioPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                if (hasAudioPerm) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed startForeground with FGS types: ${e.message}", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                Log.e(TAG, "Fatal fallback startForeground failure: ${ex.message}", ex)
            }
        }
    }

    private fun updateNotification() {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification: ${e.message}", e)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endCallIntent = Intent(this, AuraForegroundService::class.java).apply {
            action = ACTION_END_CALL
        }
        val pendingEnd = PendingIntent.getService(
            this, 1, endCallIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleMuteIntent = Intent(this, AuraForegroundService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val pendingMute = PendingIntent.getService(
            this, 2, toggleMuteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val isMuted = if (::audioStreamer.isInitialized) audioStreamer.isMuted.value else false
        val isCall = _isCallActive.value

        val title = if (isCall) "Aura Live Voice Call Active" else "Aura: Say 'Hey Aura'"
        val text = if (isCall) {
            if (::liveClient.isInitialized) {
                when (liveClient.connectionState.value) {
                    is LiveConnectionState.Connected -> if (isMuted) "Microphone Muted - Tap to speak" else "Streaming audio bidirectional"
                    is LiveConnectionState.Connecting -> "Connecting to Live Gemini..."
                    is LiveConnectionState.Reconnecting -> "Reconnecting session..."
                    is LiveConnectionState.Error -> "Connection error"
                    else -> "Call session initialized"
                }
            } else {
                "Starting live voice session..."
            }
        } else {
            "Listening for 'Hey Aura' in background"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingOpen)
            .setOngoing(true)
            .setPriority(if (isCall) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)

        if (isCall) {
            builder.addAction(
                if (isMuted) android.R.drawable.ic_lock_silent_mode else android.R.drawable.ic_btn_speak_now,
                if (isMuted) "Unmute" else "Mute",
                pendingMute
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "End Call", pendingEnd)
        } else {
            val startCallIntent = Intent(this, AuraForegroundService::class.java).apply {
                action = ACTION_START_CALL
            }
            val pendingStartCall = PendingIntent.getService(
                this, 3, startCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_btn_speak_now, "Start Call", pendingStartCall)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Aura Live Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows active realtime Gemini Live and Wake Word session"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        currentService = null
        terminateCall()
        wakePhraseDetector.release()
        if (::callAnnouncementManager.isInitialized) {
            callAnnouncementManager.destroy()
        }
        appInventory.destroy()
        overlayController.hideAll()

        if (isNetworkCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering network callback", e)
            }
        }
        if (isScreenReceiverRegistered) {
            try {
                unregisterReceiver(screenReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen receiver", e)
            }
        }

        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
