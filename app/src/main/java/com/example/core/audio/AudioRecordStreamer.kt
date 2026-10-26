package com.example.core.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import android.util.Log
import com.example.AuraApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import kotlin.math.sqrt

enum class AudioRecordState {
    IDLE, RECORDING, INIT_FAILED, PERMISSION_DENIED
}

class AudioRecordStreamer(
    private val onChunkEncoded: (String) -> Unit
) {
    companion object {
        private const val TAG = "Aura"
        const val SAMPLE_RATE = 16000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        const val CHUNK_SIZE_BYTES = 1280 // 40ms of 16kHz 16-bit mono PCM (640 samples)
        const val SAMPLES_PER_CHUNK = 640
        const val PRE_ROLL_CHUNKS = 5 // 200ms pre-roll
        const val HANGOVER_CHUNKS = 8 // 320ms hangover
    }

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    private var recordingJob: Job? = null
    private var isRecording = false

    private val _micAmplitude = MutableStateFlow(0f)
    val micAmplitude: StateFlow<Float> = _micAmplitude.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _recordState = MutableStateFlow(AudioRecordState.IDLE)
    val recordState: StateFlow<AudioRecordState> = _recordState.asStateFlow()

    // Noise gate diagnostics
    private val _noiseFloor = MutableStateFlow(120.0)
    val noiseFloor: StateFlow<Double> = _noiseFloor.asStateFlow()

    private val _isGateOpen = MutableStateFlow(false)
    val isGateOpen: StateFlow<Boolean> = _isGateOpen.asStateFlow()

    private val _gatedFramesCount = MutableStateFlow(0L)
    val gatedFramesCount: StateFlow<Long> = _gatedFramesCount.asStateFlow()

    // Settings
    var noiseGateStrength: String = "Medium" // Off, Low, Medium, High
    var isAuraSpeaking: Boolean = false
    var isHeadphonesMode: Boolean = false
    var ownerVoiceLockEnabled: Boolean = false
    var ownerVoiceVerifier: OwnerVoiceVerifier? = null

    // 1st-order High-Pass Filter state (~120Hz cutoff at 16kHz: alpha ≈ 0.955f)
    private val hpAlpha = 0.955f
    private var prevInputSample = 0f
    private var prevOutputSample = 0f

    // Pre-roll ring buffer
    private val preRollBuffer = ArrayDeque<ByteArray>(PRE_ROLL_CHUNKS)

    private val silenceChunk = ByteArray(CHUNK_SIZE_BYTES)
    private val silenceChunkBase64 = Base64.encodeToString(silenceChunk, Base64.NO_WRAP)

    private val diagnostics = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
    }

    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope): Boolean {
        if (isRecording) return true

        val minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufSize, CHUNK_SIZE_BYTES * 4)

        var record: AudioRecord? = null

        // Attempt 1: VOICE_COMMUNICATION (hardware AEC enabled)
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                record = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord VOICE_COMMUNICATION failed to init, trying MIC fallback: ${e.message}")
            record = null
        }

        // Attempt 2: Fallback to MIC
        if (record == null) {
            try {
                record = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    _recordState.value = AudioRecordState.INIT_FAILED
                    diagnostics?.setAudioRecordState("Init Failed (MIC uninitialized)")
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord MIC init failed", e)
                _recordState.value = AudioRecordState.INIT_FAILED
                diagnostics?.setAudioRecordState("Init Failed: ${e.message}")
                return false
            }
        }

        val activeRecord = record ?: run {
            _recordState.value = AudioRecordState.INIT_FAILED
            diagnostics?.setAudioRecordState("Init Failed")
            return false
        }

        // Attach hardware/OS audio enhancements if supported
        val sessionId = activeRecord.audioSessionId
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
            if (AutomaticGainControl.isAvailable()) {
                gainControl = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Audio effect attachment notice: ${e.message}")
        }

        try {
            activeRecord.startRecording()
            audioRecord = activeRecord
            isRecording = true
            _recordState.value = AudioRecordState.RECORDING
            diagnostics?.setAudioRecordState("Recording (16kHz Mono)")

            // Reset filter & gate state
            prevInputSample = 0f
            prevOutputSample = 0f
            preRollBuffer.clear()
            _isGateOpen.value = false
            _gatedFramesCount.value = 0L

            recordingJob = scope.launch(Dispatchers.IO) {
                val rawBuffer = ByteArray(CHUNK_SIZE_BYTES)
                var consecutiveSpeechFrames = 0
                var hangoverCounter = 0
                var gateOpen = false

                while (isActive && isRecording) {
                    val read = activeRecord.read(rawBuffer, 0, rawBuffer.size)
                    if (read > 0) {
                        // 1. High-Pass Filter (120 Hz)
                        val filteredBuffer = ByteArray(read)
                        var sum = 0.0
                        var i = 0
                        while (i < read - 1) {
                            val rawSample = (rawBuffer[i].toInt() and 0xFF) or (rawBuffer[i + 1].toInt() shl 8)
                            val rawShort = rawSample.toShort().toFloat()

                            // y[n] = alpha * (y[n-1] + x[n] - x[n-1])
                            val filteredSample = (hpAlpha * (prevOutputSample + rawShort - prevInputSample)).coerceIn(-32768f, 32767f)
                            prevInputSample = rawShort
                            prevOutputSample = filteredSample

                            val outShort = filteredSample.toInt().toShort()
                            filteredBuffer[i] = (outShort.toInt() and 0xFF).toByte()
                            filteredBuffer[i + 1] = ((outShort.toInt() shr 8) and 0xFF).toByte()

                            sum += outShort * outShort
                            i += 2
                        }

                        // 2. Compute RMS
                        val sampleCount = read / 2
                        val rms = sqrt(sum / sampleCount)
                        val normalizedAmp = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                        _micAmplitude.value = if (_isMuted.value) 0f else normalizedAmp

                        if (_isMuted.value) {
                            // If muted, send digital silence
                            onChunkEncoded(silenceChunkBase64)
                            _isGateOpen.value = false
                            continue
                        }

                        // 3. Adaptive Noise Floor Tracking (slow EMA during quiet)
                        val currentNoiseFloor = _noiseFloor.value
                        if (rms < currentNoiseFloor * 2.0 && rms > 20.0) {
                            _noiseFloor.value = currentNoiseFloor * 0.98 + rms * 0.02
                        }

                        // 4. Threshold & Consecutive frames criteria
                        val gateStrength = noiseGateStrength.lowercase()
                        if (gateStrength == "off") {
                            // Gate disabled: send all audio
                            _isGateOpen.value = true
                            val base64Chunk = Base64.encodeToString(filteredBuffer, 0, read, Base64.NO_WRAP)
                            onChunkEncoded(base64Chunk)
                            continue
                        }

                        val (multiplier, minRms, requiredFrames) = when {
                            isAuraSpeaking && !isHeadphonesMode -> {
                                // Loudspeaker playback: raise gate to avoid speaker feedback / breath interrupts
                                Triple(6.0, 900.0, 6) // ~240ms sustained
                            }
                            gateStrength == "high" -> Triple(4.0, 500.0, 3)
                            gateStrength == "low" -> Triple(2.2, 250.0, 2)
                            else -> Triple(3.0, 350.0, 2) // "Medium" (Default)
                        }

                        val openThreshold = maxOf(currentNoiseFloor * multiplier, minRms)

                        if (rms >= openThreshold) {
                            consecutiveSpeechFrames++
                            if (consecutiveSpeechFrames >= requiredFrames) {
                                if (!gateOpen) {
                                    gateOpen = true
                                    _isGateOpen.value = true
                                    // Flush pre-roll buffer so initial consonants are preserved!
                                    while (preRollBuffer.isNotEmpty()) {
                                        val preChunk = preRollBuffer.removeFirst()
                                        val encoded = Base64.encodeToString(preChunk, Base64.NO_WRAP)
                                        onChunkEncoded(encoded)
                                    }
                                }
                                hangoverCounter = HANGOVER_CHUNKS
                            }
                        } else {
                            consecutiveSpeechFrames = 0
                            if (hangoverCounter > 0) {
                                hangoverCounter--
                            } else {
                                if (gateOpen) {
                                    gateOpen = false
                                    _isGateOpen.value = false
                                }
                            }
                        }

                        // Update pre-roll buffer
                        if (preRollBuffer.size >= PRE_ROLL_CHUNKS) {
                            preRollBuffer.removeFirst()
                        }
                        preRollBuffer.addLast(filteredBuffer.copyOf())

                        // 5. Send Audio or Digital Silence
                        if (gateOpen) {
                            val verifier = ownerVoiceVerifier
                            if (ownerVoiceLockEnabled && verifier != null && verifier.hasProfile()) {
                                val shorts = ShortArray(read / 2)
                                var si = 0
                                var bi = 0
                                while (bi + 1 < read && si < shorts.size) {
                                    shorts[si] = ((filteredBuffer[bi].toInt() and 0xFF) or (filteredBuffer[bi + 1].toInt() shl 8)).toShort()
                                    si++
                                    bi += 2
                                }
                                val matchResult = verifier.verifyOwnerVoice(shorts, si)
                                if (!matchResult.isMatch) {
                                    // Unrecognized voice: silently mute & drop audio frame
                                    _gatedFramesCount.value += 1
                                    onChunkEncoded(silenceChunkBase64)
                                    continue
                                }
                            }
                            val base64Chunk = Base64.encodeToString(filteredBuffer, 0, read, Base64.NO_WRAP)
                            onChunkEncoded(base64Chunk)
                        } else {
                            // Send DIGITAL SILENCE frame so server VAD has continuous stream
                            _gatedFramesCount.value += 1
                            onChunkEncoded(silenceChunkBase64)
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            _recordState.value = AudioRecordState.INIT_FAILED
            diagnostics?.setAudioRecordState("Start Failed: ${e.message}")
            stop()
            return false
        }
    }

    fun stop() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null

        try {
            audioRecord?.apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audioRecord", e)
        }
        audioRecord = null

        try {
            echoCanceler?.release()
            noiseSuppressor?.release()
            gainControl?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing audio effects", e)
        }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null

        preRollBuffer.clear()
        _micAmplitude.value = 0f
        _isGateOpen.value = false
        _recordState.value = AudioRecordState.IDLE
        diagnostics?.setAudioRecordState("Idle")
    }
}
