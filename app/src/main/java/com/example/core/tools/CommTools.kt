package com.example.core.tools

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.example.core.agent.ScreenXmlParser
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.telephony.CallAnnouncementManager
import com.example.service.AuraNotificationListener
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.net.URLEncoder
import java.util.Locale

class CommTools(
    private val context: Context,
    private val rootManager: RootManager,
    private val preferences: AuraPreferences
) {

    @SuppressLint("Range")
    fun resolveContactNumber(query: String): Pair<String, String>? {
        val cleanQuery = query.trim()
        if (cleanQuery.matches(Regex("""^\+?[0-9\s\-]{7,16}$"""))) {
            return Pair(cleanQuery.replace(Regex("""[\s\-]"""), ""), cleanQuery)
        }

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val cursor = context.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$cleanQuery%"),
            null
        )

        cursor?.use {
            if (it.moveToFirst()) {
                val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                return Pair(name, number.replace(Regex("""[\s\-]"""), ""))
            }
        }
        return null
    }

    suspend fun sendWhatsappInternal(target: String, message: String): ToolExecutionResult {
        var cleanMessage = message.trim()
        while (cleanMessage.endsWith("/") || cleanMessage.endsWith("/?")) {
            cleanMessage = cleanMessage.removeSuffix("/?").removeSuffix("/").trim()
        }

        val contactInfo = resolveContactNumber(target)
        val phone = contactInfo?.second ?: target.replace(Regex("""[^\d+]"""), "")

        val isBusiness = preferences.isWhatsappBusiness.first()
        val pkg = if (isBusiness) "com.whatsapp.w4b" else "com.whatsapp"

        val rawDigits = phone.replace(Regex("""[^\d]"""), "")
        val cleanPhone = if (rawDigits.length == 10) "91$rawDigits" else rawDigits

        var intentDispatched = false

        // 1. First attempt: Direct JID Intent (no URL parsing quirks, avoids '/?' additions)
        if (cleanPhone.isNotBlank()) {
            try {
                val directIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(pkg)
                    putExtra(Intent.EXTRA_TEXT, cleanMessage)
                    putExtra("jid", "$cleanPhone@s.whatsapp.net")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(directIntent)
                intentDispatched = true
            } catch (e: Exception) {
                android.util.Log.w("Aura", "Direct JID WhatsApp intent failed: ${e.message}")
            }
        }

        // 2. Second attempt: Custom scheme whatsapp://send (NOT https://api.whatsapp.com)
        if (!intentDispatched && cleanPhone.isNotBlank()) {
            try {
                val encodedMsg = URLEncoder.encode(cleanMessage, "UTF-8").replace("+", "%20")
                val uri = Uri.parse("whatsapp://send?phone=$cleanPhone&text=$encodedMsg")
                val schemeIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    setPackage(pkg)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(schemeIntent)
                intentDispatched = true
            } catch (e: Exception) {
                android.util.Log.w("Aura", "Custom scheme whatsapp:// intent failed: ${e.message}")
            }
        }

        // 3. Fallback: root am start
        if (!intentDispatched && cleanPhone.isNotBlank()) {
            val encodedMsg = URLEncoder.encode(cleanMessage, "UTF-8").replace("+", "%20")
            rootManager.execute("am start -a android.intent.action.VIEW -d \"whatsapp://send?phone=$cleanPhone&text=$encodedMsg\" $pkg")
        }

        delay(1800)

        val dumpRes = rootManager.execute("uiautomator dump /sdcard/ui_wa.xml && cat /sdcard/ui_wa.xml")
        val nodes = ScreenXmlParser.parse(dumpRes.stdout)
        val sendButton = nodes.find {
            it.resourceId.contains("send") ||
            it.contentDescription.equals("Send", ignoreCase = true) ||
            it.contentDescription.contains("भेजें")
        }

        return if (sendButton != null) {
            val ok = com.example.AuraApplication.instance.inputController.tap(sendButton.centerX.toFloat(), sendButton.centerY.toFloat(), "Send WhatsApp")
            ToolExecutionResult(ok, "Sent WhatsApp message to ${contactInfo?.first ?: phone}: '$cleanMessage'")
        } else {
            val ok = com.example.AuraApplication.instance.inputController.pressKey(66, "Send")
            ToolExecutionResult(ok, "Opened WhatsApp and sent message to ${contactInfo?.first ?: phone}: '$cleanMessage'")
        }
    }

    val sendWhatsappTool = object : Tool {
        override val name = "send_whatsapp"
        override val description = "Sends a WhatsApp message to a contact name or phone number."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact_or_number" to mapOf("type" to "string", "description" to "The contact name or full phone number with country code."),
                "message" to mapOf("type" to "string", "description" to "The message text to send.")
            ),
            "required" to listOf("contact_or_number", "message")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val target = args["contact_or_number"]?.toString() ?: return ToolExecutionResult(false, "Missing contact")
            val message = args["message"]?.toString() ?: return ToolExecutionResult(false, "Missing message")
            return sendWhatsappInternal(target, message)
        }
    }

    val sendSmsTool = object : Tool {
        override val name = "send_sms"
        override val description = "Sends a standard SMS text message."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact" to mapOf("type" to "string", "description" to "The contact name or phone number."),
                "text" to mapOf("type" to "string", "description" to "The message body.")
            ),
            "required" to listOf("contact", "text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val contact = args["contact"]?.toString() ?: return ToolExecutionResult(false, "Missing contact")
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")
            val info = resolveContactNumber(contact)
            val destination = info?.second ?: contact

            return try {
                val smsManager = SmsManager.getDefault()
                val parts = smsManager.divideMessage(text)
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
                ToolExecutionResult(true, "SMS sent to ${info?.first ?: destination}")
            } catch (e: Exception) {
                val res = rootManager.execute("service call isms 7 i32 0 s16 \"com.android.mms\" s16 \"$destination\" s16 \"null\" s16 \"$text\"")
                if (res.success) {
                    ToolExecutionResult(true, "SMS sent via root to $destination")
                } else {
                    ToolExecutionResult(false, "Failed to send SMS: ${e.message}")
                }
            }
        }
    }

    val makeCallTool = object : Tool {
        override val name = "make_call"
        override val description = "Makes a phone call to a contact or phone number."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact_or_number" to mapOf("type" to "string", "description" to "The contact name or phone number.")
            ),
            "required" to listOf("contact_or_number")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val target = args["contact_or_number"]?.toString() ?: return ToolExecutionResult(false, "Missing phone number")
            val info = resolveContactNumber(target)
            val number = info?.second ?: target

            val res = rootManager.execute("am start -a android.intent.action.CALL -d tel:${Uri.encode(number)}")
            return if (res.success) {
                ToolExecutionResult(true, "Calling ${info?.first ?: number}")
            } else {
                ToolExecutionResult(false, "Could not initiate call: ${res.stderr}")
            }
        }
    }

    val answerCallTool = object : Tool {
        override val name = "answer_call"
        override val description = "Answers an incoming cellular or VoIP phone call and releases microphone."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val cam = CallAnnouncementManager.instance
            val success = cam?.answerCall() ?: run {
                com.example.AuraApplication.instance.inputController.pressKey(5, "Answer Call")
            }
            return ToolExecutionResult(success, if (success) "Answered incoming call and released microphone" else "Could not answer call")
        }
    }

    val rejectCallTool = object : Tool {
        override val name = "reject_call"
        override val description = "Rejects or declines an incoming phone call."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val cam = CallAnnouncementManager.instance
            val success = cam?.rejectCall() ?: run {
                com.example.AuraApplication.instance.inputController.pressKey(6, "Reject Call")
            }
            return ToolExecutionResult(success, if (success) "Rejected incoming call" else "Could not reject call")
        }
    }

    val rejectCallWithMessageTool = object : Tool {
        override val name = "reject_call_with_message"
        override val description = "Rejects incoming call and sends a quick SMS or message to the caller."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "message" to mapOf("type" to "string", "description" to "Text message to send to the caller.")
            ),
            "required" to listOf("message")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val message = args["message"]?.toString() ?: "I am busy, will call you back later."
            val cam = CallAnnouncementManager.instance
            val success = cam?.rejectCallWithMessage(message) ?: false
            return ToolExecutionResult(success, if (success) "Rejected call and sent SMS: '$message'" else "Could not send rejection message")
        }
    }

    val silenceRingerTool = object : Tool {
        override val name = "silence_ringer"
        override val description = "Silences or mutes the incoming call ringer."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val cam = CallAnnouncementManager.instance
            val success = cam?.silenceRinger() ?: false
            return ToolExecutionResult(success, if (success) "Ringer silenced" else "Failed to silence ringer")
        }
    }

    val endCallTool = object : Tool {
        override val name = "end_call"
        override val description = "Ends or hangs up the active phone call."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val cam = CallAnnouncementManager.instance
            val success = cam?.rejectCall() ?: run {
                com.example.AuraApplication.instance.inputController.pressKey(6, "End Call")
            }
            return ToolExecutionResult(success, if (success) "Ended call" else "Could not end call")
        }
    }

    @SuppressLint("Range")
    val readSmsTool = object : Tool {
        override val name = "read_sms"
        override val description = "Reads recent received SMS text messages."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "integer", "description" to "Number of SMS messages to read (default 5).")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val cursor = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"),
                null,
                null,
                "date DESC LIMIT $count"
            )

            val messages = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val address = it.getString(it.getColumnIndex("address")) ?: "Unknown"
                    val body = it.getString(it.getColumnIndex("body")) ?: ""
                    messages.add("From: $address | Text: $body")
                }
            }

            return if (messages.isNotEmpty()) {
                ToolExecutionResult(true, messages.joinToString("\n---\n"))
            } else {
                ToolExecutionResult(true, "No recent SMS messages found.")
            }
        }
    }

    @SuppressLint("Range")
    val readCallLogTool = object : Tool {
        override val name = "read_call_log"
        override val description = "Reads recent incoming, outgoing, or missed calls."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "integer", "description" to "Number of call records to fetch (default 5).")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.CACHED_NAME, CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DURATION),
                null,
                null,
                "${CallLog.Calls.DATE} DESC LIMIT $count"
            )

            val records = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndex(CallLog.Calls.CACHED_NAME))
                    val number = it.getString(it.getColumnIndex(CallLog.Calls.NUMBER)) ?: ""
                    val type = when (it.getInt(it.getColumnIndex(CallLog.Calls.TYPE))) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        else -> "Call"
                    }
                    records.add("$type: ${name ?: number}")
                }
            }

            return if (records.isNotEmpty()) {
                ToolExecutionResult(true, records.joinToString("\n"))
            } else {
                ToolExecutionResult(true, "No call logs found.")
            }
        }
    }

    val readNotificationsTool = object : Tool {
        override val name = "read_notifications"
        override val description = "Reads currently posted notifications on the device."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "count" to mapOf("type" to "integer", "description" to "Number of notifications to read (default 5)."),
                "app_filter" to mapOf("type" to "string", "description" to "Optional app name filter.")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val count = (args["count"] as? Number)?.toInt() ?: 5
            val filter = args["app_filter"]?.toString()?.lowercase(Locale.ROOT) ?: ""
            val notifs = AuraNotificationListener.getActiveNotificationsCached()
                .filter { if (filter.isBlank()) true else it.appName.lowercase(Locale.ROOT).contains(filter) }
                .take(count)

            return if (notifs.isNotEmpty()) {
                val formatted = notifs.joinToString("\n") {
                    "[${it.appName}] ${it.title}: ${it.text} (id: ${it.key})"
                }
                ToolExecutionResult(true, formatted)
            } else {
                ToolExecutionResult(true, "No active notifications matching.")
            }
        }
    }

    val replyToNotificationTool = object : Tool {
        override val name = "reply_to_notification"
        override val description = "Sends an inline reply to a notification by notification_id, sender name, or app name (e.g. 'whatsapp', 'Rahul')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "contact_or_id" to mapOf("type" to "string", "description" to "The contact name, app name (e.g. 'whatsapp'), or notification key/id to reply to."),
                "message" to mapOf("type" to "string", "description" to "The reply message.")
            ),
            "required" to listOf("message")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val target = args["contact_or_id"]?.toString()
                ?: args["notification_id"]?.toString()
                ?: args["contact"]?.toString()
                ?: args["target"]?.toString()
                ?: "whatsapp"
            val message = args["message"]?.toString() ?: return ToolExecutionResult(false, "Missing message")
            var cleanMsg = message.trim()
            while (cleanMsg.endsWith("/") || cleanMsg.endsWith("/?")) {
                cleanMsg = cleanMsg.removeSuffix("/?").removeSuffix("/").trim()
            }

            val success = AuraNotificationListener.replyToNotification(target, cleanMsg)
            if (success) {
                return ToolExecutionResult(true, "Replied successfully to notification ($target): '$cleanMsg'")
            }

            val waRes = sendWhatsappInternal(target, cleanMsg)
            return if (waRes.success) {
                ToolExecutionResult(true, "Replied via WhatsApp to $target: '$cleanMsg'")
            } else {
                ToolExecutionResult(false, "Notification reply failed and WhatsApp fallback failed: ${waRes.output}")
            }
        }
    }

    val dismissNotificationTool = object : Tool {
        override val name = "dismiss_notification"
        override val description = "Dismisses a notification."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "notification_id" to mapOf("type" to "string", "description" to "The key of the notification.")
            ),
            "required" to listOf("notification_id")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val id = args["notification_id"]?.toString() ?: return ToolExecutionResult(false, "Missing id")
            val success = AuraNotificationListener.dismissNotification(id)
            return ToolExecutionResult(success, if (success) "Notification dismissed" else "Could not find notification")
        }
    }

    @SuppressLint("Range")
    val searchContactsTool = object : Tool {
        override val name = "search_contacts"
        override val description = "Searches contacts by name."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "Contact name to search.")
            ),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString() ?: return ToolExecutionResult(false, "Missing query")
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$query%"),
                null
            )

            val results = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val number = it.getString(it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    results.add("$name: $number")
                }
            }

            return if (results.isNotEmpty()) {
                ToolExecutionResult(true, results.joinToString("\n"))
            } else {
                ToolExecutionResult(true, "No contacts found matching '$query'")
            }
        }
    }

    val configureCallAutoRejectTool = object : Tool {
        override val name = "configure_call_auto_reject"
        override val description = "Configures automatic incoming call rejection and auto-reply message via SMS and WhatsApp."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "enabled" to mapOf("type" to "boolean", "description" to "true to auto-reject incoming calls, false to disable auto-reject."),
                "message" to mapOf("type" to "string", "description" to "The busy message text to send to callers (e.g. 'Mai busy hu, baad me call karta hu')."),
                "reply_via_sms" to mapOf("type" to "boolean", "description" to "Whether to send busy SMS to cellular callers (default true)."),
                "reply_via_whatsapp" to mapOf("type" to "boolean", "description" to "Whether to send busy WhatsApp message to WhatsApp/known callers (default true).")
            ),
            "required" to listOf("enabled")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val enabled = args["enabled"] as? Boolean ?: return ToolExecutionResult(false, "Missing 'enabled' parameter")
            val msg = args["message"]?.toString()
            val replySms = args["reply_via_sms"] as? Boolean
            val replyWhatsapp = args["reply_via_whatsapp"] as? Boolean

            preferences.configureCallAutoReject(
                enabled = enabled,
                message = msg,
                replySms = replySms,
                replyWhatsapp = replyWhatsapp
            )
            val desc = if (enabled) {
                "Auto-reject calls enabled with busy message: '${msg ?: "Mai busy hu, baad me call karta hu."}' (SMS: ${replySms ?: true}, WhatsApp: ${replyWhatsapp ?: true})"
            } else {
                "Auto-reject calls disabled"
            }
            return ToolExecutionResult(true, desc)
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        sendWhatsappTool, sendSmsTool, makeCallTool, answerCallTool, rejectCallTool,
        rejectCallWithMessageTool, silenceRingerTool, endCallTool,
        readSmsTool, readCallLogTool, readNotificationsTool, replyToNotificationTool,
        dismissNotificationTool, searchContactsTool, configureCallAutoRejectTool
    )
}
