package com.example.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Base64
import android.util.Log
import com.example.AuraApplication
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.sqrt

class AudioTrackPlayer(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
        const val SAMPLE_RATE = 24000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var playbackJob: Job? = null
    private var isPlaying = false

    private val _speakerAmplitude = MutableStateFlow(0f)
    val speakerAmplitude: StateFlow<Float> = _speakerAmplitude.asStateFlow()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val diagnostics = runCatching { AuraApplication.instance.liveDiagnostics }.getOrNull()

    fun start(scope: CoroutineScope) {
        if (isPlaying) return

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerphoneOn(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed setting audioManager mode in AudioTrackPlayer", e)
        }

        val minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = maxOf(minBufSize, 12000) // ~250ms buffer at 24kHz 16-bit mono

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(CHANNEL_CONFIG)
            .setEncoding(AUDIO_FORMAT)
            .build()

        try {
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()

            audioTrack?.setVolume(1.0f)
            audioTrack?.play()
            isPlaying = true
            diagnostics?.setAudioTrackState("Playing (24kHz Mono)")
        } catch (e: Throwable) {
            Log.e(TAG, "AudioTrack init failed", e)
            isPlaying = false
            diagnostics?.setAudioTrackState("Init Failed: ${e.message}")
            return
        }

        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && isPlaying) {
                val chunk = audioQueue.poll()
                if (chunk != null && chunk.isNotEmpty()) {
                    // Compute amplitude
                    var sum = 0.0
                    var i = 0
                    while (i < chunk.size - 1) {
                        val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
                        sum += sample * sample
                        i += 2
                    }
                    val rms = sqrt(sum / (chunk.size / 2.0))
                    val norm = (rms / 32768.0).toFloat().coerceIn(0f, 1f)
                    _speakerAmplitude.value = norm

                    val written = audioTrack?.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING) ?: 0
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack write error code: $written")
                    }
                } else {
                    _speakerAmplitude.value = 0f
                    delay(10)
                }
            }
        }
    }

    fun enqueueBase64Chunk(base64: String) {
        try {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            if (bytes.isNotEmpty()) {
                audioQueue.add(bytes)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Base64 decode audio chunk error", e)
        }
    }

    fun enqueuePcm(bytes: ByteArray) {
        if (bytes.isNotEmpty()) {
            audioQueue.add(bytes)
        }
    }

    fun playPcmChunk(bytes: ByteArray) = enqueuePcm(bytes)

    /**
     * Instantly flushes the playback queue and stops current audio when barge-in occurs.
     */
    fun flushAndInterrupt() {
        audioQueue.clear()
        try {
            audioTrack?.apply {
                pause()
                flush()
                play()
            }
        } catch (e: Exception) {
            Log.e(TAG, "flushAndInterrupt error", e)
        }
        _speakerAmplitude.value = 0f
    }

    fun setSpeakerphoneOn(on: Boolean) {
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager.availableCommunicationDevices
                val targetType = if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = devices.find { it.type == targetType }
                if (device != null) {
                    audioManager.setCommunicationDevice(device)
                    Log.d(TAG, "Audio routed to: ${device.productName} (type=$targetType)")
                } else {
                    audioManager.isSpeakerphoneOn = on
                }
            } else {
                audioManager.isSpeakerphoneOn = on
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting speakerphone to $on", e)
        }
    }

    fun stop() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null
        audioQueue.clear()

        try {
            audioTrack?.apply {
                pause()
                flush()
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audioTrack", e)
        }
        audioTrack = null
        _speakerAmplitude.value = 0f
        diagnostics?.setAudioTrackState("Stopped")

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting audioManager mode", e)
        }
    }
}
