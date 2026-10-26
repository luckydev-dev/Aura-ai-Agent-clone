package com.example.core.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.SmsManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraForegroundService
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class CallAnnouncementManager(
    private val context: Context,
    private val preferences: AuraPreferences,
    private val rootManager: RootManager
) {
    companion object {
        private const val TAG = "Aura"
        var instance: CallAnnouncementManager? = null
            private set
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var telephonyCallback: Any? = null
    private var phoneStateListener: PhoneStateListener? = null

    var isRinging = false
        private set
    var activeCallerName: String? = null
        private set
    var activeCallerNumber: String? = null
        private set
    var isVoipCall: Boolean = false
        private set

    init {
        instance = this
        registerTelephonyListener()
    }

    private fun registerTelephonyListener() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        handleCallState(state, null)
                    }
                }
                telephonyManager.registerTelephonyCallback(context.mainExecutor, callback)
                telephonyCallback = callback
            } else {
                @Suppress("DEPRECATION")
                val listener = object : PhoneStateListener() {
                    @Deprecated("Deprecated in Java")
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        handleCallState(state, phoneNumber)
                    }
                }
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
                phoneStateListener = listener
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering telephony listener: ${e.message}", e)
        }
    }

    private fun handleCallState(state: Int, number: String?) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> {
                isRinging = true
                isVoipCall = false
                val callerNumber = number ?: ""
                val contactName = resolveCallerName(callerNumber)
                activeCallerName = contactName
                activeCallerNumber = callerNumber

                scope.launch {
                    val autoReject = preferences.callAutoRejectEnabled.first()
                    if (autoReject) {
                        executeAutoReject(contactName ?: callerNumber.ifBlank { "Caller" }, callerNumber, isVoip = false)
                        return@launch
                    }

                    val enabled = preferences.callAnnouncementEnabled.first()
                    if (enabled) {
                        announceIncomingCall(contactName ?: callerNumber.ifBlank { "Unknown Caller" }, isVoip = false)
                    }
                }
            }
            TelephonyManager.CALL_STATE_OFFHOOK -> {
                if (isRinging) {
                    isRinging = false
                    releaseMicrophoneForCall()
                }
            }
            TelephonyManager.CALL_STATE_IDLE -> {
                isRinging = false
                activeCallerName = null
                activeCallerNumber = null
            }
        }
    }

    fun onVoipCallDetected(appName: String, caller: String) {
        scope.launch {
            val cleanAppName = when {
                appName.lowercase().contains("whatsapp") -> "WhatsApp"
                appName.lowercase().contains("telegram") -> "Telegram"
                appName.lowercase().contains("phone") || appName.lowercase().contains("dialer") -> "Phone"
                appName.contains(".") -> "Phone"
                else -> appName
            }
            val cleanCaller = caller.replace(Regex("""(?i)^(whatsapp\s+call\s+from\s+|call\s+from\s+)"""), "").trim()

            isRinging = true
            isVoipCall = true
            activeCallerName = cleanCaller
            activeCallerNumber = ""

            val autoReject = preferences.callAutoRejectEnabled.first()
            if (autoReject) {
                executeAutoReject(cleanCaller, "", isVoip = true, appName = cleanAppName)
                return@launch
            }

            val enabled = preferences.callAnnouncementEnabled.first()
            val allowWhatsapp = preferences.callAnnounceWhatsapp.first()
            if (enabled && allowWhatsapp) {
                announceIncomingCall(cleanCaller, isVoip = true, appName = cleanAppName)
            }
        }
    }

    private suspend fun executeAutoReject(caller: String, number: String, isVoip: Boolean, appName: String? = null) {
        Log.i(TAG, "Executing auto-reject for incoming call from $caller ($number, isVoip=$isVoip)...")
        rejectCall()

        val autoReplyMsg = preferences.callAutoRejectMessage.first().ifBlank { "Mai busy hu, baad me call karta hu." }
        var cleanMsg = autoReplyMsg.trim()
        while (cleanMsg.endsWith("/") || cleanMsg.endsWith("/?")) {
            cleanMsg = cleanMsg.removeSuffix("/?").removeSuffix("/").trim()
        }

        val sendSms = preferences.callAutoReplySms.first()
        val sendWa = preferences.callAutoReplyWhatsapp.first()

        var smsSent = false
        var waSent = false

        if (sendSms && number.isNotBlank()) {
            try {
                val sms = SmsManager.getDefault()
                val parts = sms.divideMessage(cleanMsg)
                sms.sendMultipartTextMessage(number, null, parts, null, null)
                smsSent = true
            } catch (e: Exception) {
                val res = rootManager.execute("service call isms 7 i32 0 s16 \"com.android.mms\" s16 \"$number\" s16 \"null\" s16 \"$cleanMsg\"")
                smsSent = res.success
            }
        }

        if (sendWa) {
            val waTarget = if (number.isNotBlank()) number else caller
            sendWhatsappDirect(waTarget, cleanMsg)
            waSent = true
        }

        AuraForegroundService.currentService?.let { service ->
            service.liveClient.sendTextMessage("[SYSTEM EVENT: Auto-rejected call from $caller. Busy message sent (SMS=$smsSent, WhatsApp=$waSent): \"$cleanMsg\"]")
        }
    }

    private suspend fun sendWhatsappDirect(target: String, message: String) {
        try {
            val isBusiness = preferences.isWhatsappBusiness.first()
            val pkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"
            val digits = target.replace(Regex("""[^\d]"""), "")
            val phone = if (digits.length == 10) "91$digits" else digits

            var cleanMsg = message.trim()
            while (cleanMsg.endsWith("/") || cleanMsg.endsWith("/?")) {
                cleanMsg = cleanMsg.removeSuffix("/?").removeSuffix("/").trim()
            }

            if (phone.isNotBlank()) {
                val directIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(pkg)
                    putExtra(Intent.EXTRA_TEXT, cleanMsg)
                    putExtra("jid", "$phone@s.whatsapp.net")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(directIntent)
                delay(1200)
                rootManager.execute("input keyevent 66")
            } else {
                AuraNotificationListener.replyToNotification(target, cleanMsg)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending auto-reply via WhatsApp: ${e.message}", e)
        }
    }

    private fun announceIncomingCall(caller: String, isVoip: Boolean, appName: String? = null) {
        scope.launch {
            try {
                val maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)
                val softRing = (maxRing * 0.05f).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, softRing, 0)
            } catch (e: Exception) {
                Log.e(TAG, "Failed setting soft ringer volume: ${e.message}")
            }

            val owner = preferences.ownerName.first().ifBlank { "Boss" }
            val cleanApp = when {
                appName?.lowercase()?.contains("whatsapp") == true -> "WhatsApp"
                appName?.lowercase()?.contains("telegram") == true -> "Telegram"
                appName?.lowercase()?.contains("phone") == true -> "Phone"
                appName?.contains(".") == true -> "Phone"
                else -> appName ?: "Phone"
            }
            val cleanCaller = caller.replace(Regex("""(?i)^(whatsapp\s+call\s+from\s+|call\s+from\s+)"""), "").trim()

            val announcement = if (isVoip) {
                "$owner, $cleanApp par $cleanCaller ka call aa raha hai. Uthau ya kaat du?"
            } else {
                "$owner, $cleanCaller ka phone aa raha hai. Uthau ya kaat du?"
            }

            Log.i(TAG, "Incoming call announcement: $announcement")

            AuraForegroundService.currentService?.let { service ->
                service.liveClient.sendTextMessage("[SYSTEM EVENT: Incoming Call from $cleanCaller. Say immediately: \"$announcement\"]")
            }
        }
    }

    suspend fun handleVoiceCallDecision(command: String): Boolean {
        val lower = command.lowercase().trim()
        return when {
            lower.contains("utha") || lower.contains("answer") || lower.contains("receive") || lower.contains("pick up") || lower.contains("haan utha") -> {
                answerCall()
                true
            }
            lower.contains("kaat") || lower.contains("reject") || lower.contains("decline") || lower.contains("cut") -> {
                rejectCall()
                true
            }
            lower.contains("silent") || lower.contains("chup") || lower.contains("mute") -> {
                silenceRinger()
                true
            }
            lower.contains("message") || lower.contains("bol do") -> {
                val msg = command.substringAfter("message", "").ifBlank { "I'll call you later." }
                rejectCallWithMessage(msg.trim())
                true
            }
            else -> false
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun answerCall(): Boolean {
        Log.i(TAG, "Answering incoming call...")
        var answered = false

        if (isVoipCall) {
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
            val callNotif = notifs.firstOrNull {
                it.sbn.notification.category == android.app.Notification.CATEGORY_CALL ||
                it.title.contains("call", ignoreCase = true) ||
                it.text.contains("call", ignoreCase = true)
            }
            if (callNotif != null) {
                val actions = callNotif.sbn.notification.actions ?: emptyArray()
                val answerAction = actions.firstOrNull {
                    it.title.toString().contains("answer", ignoreCase = true) ||
                    it.title.toString().contains("accept", ignoreCase = true) ||
                    it.title.toString().contains("उठाएं")
                }
                if (answerAction != null) {
                    try {
                        answerAction.actionIntent.send()
                        answered = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed triggering answer notification action", e)
                    }
                }
            }
        }

        if (!answered && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.acceptRingingCall()
                    answered = true
                } catch (e: Exception) {
                    Log.e(TAG, "telecomManager.acceptRingingCall failed: ${e.message}", e)
                }
            }
        }

        if (!answered) {
            val res = com.example.AuraApplication.instance.inputController.pressKey(5, "Answer Call")
            answered = res
        }

        releaseMicrophoneForCall()
        isRinging = false
        return answered
    }

    @SuppressLint("MissingPermission")
    suspend fun rejectCall(): Boolean {
        Log.i(TAG, "Rejecting incoming call...")
        var rejected = false

        if (isVoipCall) {
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
            val callNotif = notifs.firstOrNull {
                it.sbn.notification.category == android.app.Notification.CATEGORY_CALL ||
                it.title.contains("call", ignoreCase = true)
            }
            if (callNotif != null) {
                val actions = callNotif.sbn.notification.actions ?: emptyArray()
                val declineAction = actions.firstOrNull {
                    it.title.toString().contains("decline", ignoreCase = true) ||
                    it.title.toString().contains("reject", ignoreCase = true) ||
                    it.title.toString().contains("काटें")
                }
                if (declineAction != null) {
                    try {
                        declineAction.actionIntent.send()
                        rejected = true
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed triggering reject notification action", e)
                    }
                }
            }
        }

        if (!rejected && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            if (context.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                try {
                    telecomManager?.endCall()
                    rejected = true
                } catch (e: Exception) {
                    Log.e(TAG, "telecomManager.endCall failed: ${e.message}", e)
                }
            }
        }

        if (!rejected) {
            val res = com.example.AuraApplication.instance.inputController.pressKey(6, "Reject Call")
            rejected = res
        }

        if (!rejected) {
            val rootRes = rootManager.execute("telecom end-call || input keyevent 6 || service call phone 5 || service call phone 6")
            if (rootRes.success) rejected = true
        }

        isRinging = false
        return rejected
    }

    suspend fun rejectCallWithMessage(message: String): Boolean {
        val num = activeCallerNumber
        rejectCall()
        if (!num.isNullOrBlank()) {
            return try {
                val sms = SmsManager.getDefault()
                sms.sendTextMessage(num, null, message, null, null)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed sending SMS after call rejection", e)
                false
            }
        }
        return true
    }

    suspend fun silenceRinger(): Boolean {
        return try {
            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            true
        } catch (e: Exception) {
            val res = com.example.AuraApplication.instance.inputController.pressKey(164, "Mute Ringer")
            res
        }
    }

    private fun releaseMicrophoneForCall() {
        AuraForegroundService.currentService?.let { service ->
            service.audioStreamer.setMuted(true)
            service.audioPlayer.stop()
        }
    }

    @SuppressLint("Range")
    private fun resolveCallerName(phoneNumber: String): String? {
        if (phoneNumber.isBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor: Cursor? = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up contact name: ${e.message}", e)
            null
        }
    }

    fun simulateIncomingCall(callerName: String, number: String) {
        isRinging = true
        isVoipCall = false
        activeCallerName = callerName
        activeCallerNumber = number
        announceIncomingCall(callerName, isVoip = false)
    }

    fun destroy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                telephonyCallback?.let {
                    telephonyManager.unregisterTelephonyCallback(it as TelephonyCallback)
                }
            } else {
                phoneStateListener?.let {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering telephony listener", e)
        }
        scope.cancel()
        instance = null
    }
}
