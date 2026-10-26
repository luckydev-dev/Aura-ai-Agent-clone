package com.example.core.telephony

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.AuraApplication
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class NotificationAnnouncementManager(
    private val context: Context,
    private val preferences: AuraPreferences
) {
    companion object {
        private const val TAG = "Aura"
        var instance: NotificationAnnouncementManager? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private val recentAnnounceMap = ConcurrentHashMap<String, Long>()

    init {
        instance = this
        initTts()
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val res = tts?.setLanguage(Locale("en", "IN"))
                    if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                        tts?.language = Locale.ENGLISH
                    }
                    tts?.setPitch(1.05f)
                    tts?.setSpeechRate(1.0f)
                    isTtsReady = true
                    Log.i(TAG, "NotificationAnnouncementManager TTS initialized successfully")
                } else {
                    Log.w(TAG, "Failed initializing TTS engine for notification announcements (status: $status)")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception initializing TTS: ${e.message}", e)
        }
    }

    fun processNotification(sbn: StatusBarNotification) {
        val notif = sbn.notification ?: return
        if (sbn.isOngoing) return

        val category = notif.category
        if (category == Notification.CATEGORY_CALL ||
            category == Notification.CATEGORY_PROGRESS ||
            category == Notification.CATEGORY_TRANSPORT ||
            category == Notification.CATEGORY_SERVICE) {
            return
        }

        val pkg = sbn.packageName ?: return
        val extras = notif.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: ""

        if (title.isBlank() && text.isBlank()) return

        scope.launch {
            try {
                val enabled = preferences.notifAnnounceEnabled.first()
                if (!enabled) return@launch

                val allowedPackages = preferences.notifAnnounceApps.first()
                if (!allowedPackages.contains(pkg)) return@launch

                // Throttle / Deduplicate identical notification within 6 seconds
                val hashKey = "$pkg|$title|$text"
                val now = System.currentTimeMillis()
                val lastTime = recentAnnounceMap[hashKey] ?: 0L
                if (now - lastTime < 6000L) {
                    return@launch
                }
                recentAnnounceMap[hashKey] = now
                cleanupOldRecentAnnouncements(now)

                val readContent = preferences.notifAnnounceReadContent.first()
                val appLabel = resolveAppLabel(pkg)
                val senderName = if (title.isNotBlank()) title else "Someone"

                val announcementText = if (readContent && text.isNotBlank()) {
                    val cleanText = sanitizeNotificationText(text)
                    when {
                        pkg.contains("whatsapp") -> "WhatsApp par $senderName ka message aaya hai: $cleanText"
                        pkg.contains("telegram") -> "Telegram par $senderName ka message aaya: $cleanText"
                        pkg.contains("messaging") || pkg.contains("mms") -> "SMS aaya hai $senderName se: $cleanText"
                        pkg.contains("gm") -> "Gmail par $senderName se email aaya: $cleanText"
                        else -> "$appLabel par $senderName ka message: $cleanText"
                    }
                } else {
                    when {
                        pkg.contains("whatsapp") -> "WhatsApp par $senderName ka naya message aaya hai"
                        pkg.contains("telegram") -> "Telegram par $senderName ka naya message aaya hai"
                        pkg.contains("messaging") || pkg.contains("mms") -> "Naya SMS aaya hai $senderName se"
                        else -> "$appLabel par $senderName ka naya notification aaya hai"
                    }
                }

                announce(announcementText, pkg, senderName, text)
            } catch (e: Exception) {
                Log.e(TAG, "Error in processNotification: ${e.message}", e)
            }
        }
    }

    fun announceTest(appName: String, sender: String, message: String) {
        scope.launch {
            val announcement = "$appName par $sender ka message aaya hai: $message"
            announce(announcement, "com.test", sender, message)
        }
    }

    private suspend fun announce(announcement: String, pkg: String, sender: String, rawContent: String) {
        Log.i(TAG, "Announcing notification: $announcement")

        val service = AuraForegroundService.currentService
        if (AuraForegroundService.isCallActive.value && service != null) {
            // Live AI Gemini voice call is active: have Maya announce it naturally
            val prompt = "[SYSTEM EVENT: New Notification from $pkg by $sender: \"$rawContent\". Say immediately out loud in natural Hinglish: \"$announcement\"]"
            service.liveClient.sendTextMessage(prompt)
        } else {
            // Live call is not active: Speak via Android TextToSpeech
            speakTts(announcement)
        }
    }

    private fun speakTts(text: String) {
        if (!isTtsReady || tts == null) {
            initTts()
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aura_notif_${System.currentTimeMillis()}")
            } else {
                @Suppress("DEPRECATION")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed speaking announcement via TTS: ${e.message}", e)
        }
    }

    private fun sanitizeNotificationText(text: String): String {
        return text
            .replace(Regex("""https?://\S+"""), "link")
            .replace(Regex("""[\r\n]+"""), " ")
            .trim()
            .take(160)
    }

    private fun resolveAppLabel(pkg: String): String {
        return when {
            pkg.contains("whatsapp.w4b") -> "WhatsApp Business"
            pkg.contains("whatsapp") -> "WhatsApp"
            pkg.contains("telegram") -> "Telegram"
            pkg.contains("apps.messaging") || pkg.contains("mms") -> "Messages"
            pkg.contains("gm") -> "Gmail"
            pkg.contains("instagram") -> "Instagram"
            pkg.contains("snapchat") -> "Snapchat"
            pkg.contains("youtube") -> "YouTube"
            else -> {
                try {
                    val pm = context.packageManager
                    val ai = pm.getApplicationInfo(pkg, 0)
                    pm.getApplicationLabel(ai).toString()
                } catch (e: Exception) {
                    "App"
                }
            }
        }
    }

    private fun cleanupOldRecentAnnouncements(now: Long) {
        if (recentAnnounceMap.size > 50) {
            val iterator = recentAnnounceMap.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 30000L) {
                    iterator.remove()
                }
            }
        }
    }

    fun destroy() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        scope.cancel()
        instance = null
    }
}
