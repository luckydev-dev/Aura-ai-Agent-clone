package com.example.core.tools

import com.example.core.live.VoiceCatalog
import com.example.core.root.PermissionDoctor
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

class VoiceAgentControlTools(
    private val preferences: AuraPreferences,
    private val permissionDoctor: PermissionDoctor,
    private val onLanguageChangeRequested: (suspend (String) -> Unit)? = null,
    private val onVoiceChangeRequested: (suspend (String) -> Unit)? = null,
    private val onEndCallRequested: (() -> Unit)? = null
) {

    val setLanguageTool = object : Tool {
        override val name = "set_language"
        override val description = "Switches Aura's active language (e.g. 'Hinglish', 'English (India)', 'Hindi', 'Gujarati')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "language" to mapOf(
                    "type" to "string",
                    "description" to "Target language: Hinglish, English (India), Hindi, Gujarati, Marathi, Bengali, Tamil, Telugu, Kannada, Malayalam, English (US)."
                )
            ),
            "required" to listOf("language")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val lang = args["language"]?.toString()?.trim() ?: return ToolExecutionResult(false, "Missing language parameter")
            preferences.setDefaultLanguage(lang)
            onLanguageChangeRequested?.invoke(lang)
            return ToolExecutionResult(true, "Language switched to $lang. Aura will now speak in $lang.")
        }
    }

    val endCallSessionTool = object : Tool {
        override val name = "end_call_session"
        override val description = "Ends the voice call session when user says 'bye Aura', 'alvida', or 'bas abhi ke liye itna hi'."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            onEndCallRequested?.invoke()
            return ToolExecutionResult(true, "Call session ended. Returning to background wake-word listening.")
        }
    }

    val listVoicesTool = object : Tool {
        override val name = "list_voices"
        override val description = "Lists all available prebuilt Gemini Live speech voices with gender and style descriptions."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val currentVoice = preferences.voiceName.first()
            val list = JSONArray()
            VoiceCatalog.VOICES.forEach { v ->
                list.put(JSONObject().apply {
                    put("name", v.name)
                    put("gender", v.gender)
                    put("style", v.styleNote)
                    put("isCurrent", v.name.equals(currentVoice, ignoreCase = true))
                })
            }
            return ToolExecutionResult(true, list.toString(2))
        }
    }

    val changeVoiceTool = object : Tool {
        override val name = "change_voice"
        override val description = "Changes Aura's voice to a specific Gemini voice name (or 'random')."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "voice_name" to mapOf(
                    "type" to "string",
                    "description" to "The name of the voice (e.g. 'Aoede', 'Puck', 'Charon', 'Kore', 'Fenrir') or 'random'."
                )
            ),
            "required" to listOf("voice_name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val targetVoice = AuraPreferences.DEFAULT_VOICE
            preferences.setVoiceName(targetVoice)
            onVoiceChangeRequested?.invoke(targetVoice)

            val resp = JSONObject().apply {
                put("success", true)
                put("voice", targetVoice)
                put("message", "Awaaz Maya par fixed hai.")
            }
            return ToolExecutionResult(true, resp.toString())
        }
    }

    val checkPermissionsTool = object : Tool {
        override val name = "check_permissions"
        override val description = "Checks system permissions, root access, accessibility service, and battery optimization status."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val items = permissionDoctor.getAllCheckItems()
            val sb = StringBuilder("=== AURA PERMISSION DOCTOR REPORT ===\n")
            var allGood = true
            items.forEach { item ->
                val status = if (item.isGranted) "✅ GRANTED" else "❌ MISSING"
                sb.append("- ${item.title}: $status\n")
                if (!item.isGranted) allGood = false
            }
            if (allGood) {
                sb.append("\nAll permissions and services are active and fully operational.")
            } else {
                sb.append("\nSome permissions are missing. You can ask me to fix them or use the Permission Doctor in Settings.")
            }
            return ToolExecutionResult(true, sb.toString())
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        setLanguageTool,
        endCallSessionTool,
        listVoicesTool,
        changeVoiceTool,
        checkPermissionsTool
    )
}
