package com.example.core.live

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

class VoicePreviewPlayer(private val context: Context) {

    companion object {
        private const val TAG = "Aura"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var textToSpeech: TextToSpeech? = null
    private var isTtsReady = false
    private var mediaPlayer: MediaPlayer? = null

    private val _currentlyPlayingVoice = MutableStateFlow<String?>(null)
    val currentlyPlayingVoice: StateFlow<String?> = _currentlyPlayingVoice.asStateFlow()

    private val previewCacheDir by lazy {
        File(context.cacheDir, "voice_previews").apply { if (!exists()) mkdirs() }
    }

    init {
        initTts()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.let { tts ->
                    val result = tts.setLanguage(Locale("hi", "IN"))
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts.language = Locale.ENGLISH
                    }
                    isTtsReady = true
                }
            } else {
                Log.w(TAG, "Failed initializing TextToSpeech for VoicePreviewPlayer")
            }
        }

        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                mainHandler.post {
                    _currentlyPlayingVoice.value = utteranceId
                }
            }

            override fun onDone(utteranceId: String?) {
                mainHandler.post {
                    if (_currentlyPlayingVoice.value == utteranceId) {
                        _currentlyPlayingVoice.value = null
                    }
                }
            }

            override fun onError(utteranceId: String?) {
                mainHandler.post {
                    if (_currentlyPlayingVoice.value == utteranceId) {
                        _currentlyPlayingVoice.value = null
                    }
                }
            }
        })
    }

    fun playPreview(voice: VoiceItem) {
        stop()

        val cachedFile = File(previewCacheDir, "${voice.name.lowercase()}.wav")
        if (cachedFile.exists() && cachedFile.length() > 1000) {
            playFromCachedFile(cachedFile, voice.name)
            return
        }

        // Synthesize and play
        if (isTtsReady && textToSpeech != null) {
            val tts = textToSpeech!!
            tts.setPitch(voice.pitch)
            tts.setSpeechRate(voice.rate)

            _currentlyPlayingVoice.value = voice.name

            // Also attempt to synthesize to file for future zero-delay playback
            try {
                tts.synthesizeToFile(
                    voice.previewText,
                    null,
                    cachedFile,
                    voice.name
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed synthesizeToFile, falling back to direct speak", e)
            }

            tts.speak(voice.previewText, TextToSpeech.QUEUE_FLUSH, null, voice.name)
        } else {
            Log.w(TAG, "TTS not ready for voice preview")
        }
    }

    private fun playFromCachedFile(file: File, voiceName: String) {
        try {
            mediaPlayer?.release()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener {
                    _currentlyPlayingVoice.value = voiceName
                    start()
                }
                setOnCompletionListener {
                    _currentlyPlayingVoice.value = null
                    it.release()
                    mediaPlayer = null
                }
                setOnErrorListener { mp, _, _ ->
                    _currentlyPlayingVoice.value = null
                    mp.release()
                    mediaPlayer = null
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error playing cached preview: ${e.message}", e)
            _currentlyPlayingVoice.value = null
        }
    }

    fun stop() {
        try {
            textToSpeech?.stop()
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
            mediaPlayer = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping VoicePreviewPlayer: ${e.message}", e)
        } finally {
            _currentlyPlayingVoice.value = null
        }
    }

    fun release() {
        stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        isTtsReady = false
    }
}
