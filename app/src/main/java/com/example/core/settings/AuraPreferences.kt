package com.example.core.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.example.BuildConfig
import com.example.core.agent.PersonaBuilder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.io.IOException

data class ApiKeyEntry(
    val key: String,
    val status: String = "UNTESTED", // "WORKING", "EXCEEDED", "UNTESTED"
    val addedMs: Long = System.currentTimeMillis()
)

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "aura_preferences")

class AuraPreferences(
    private val context: Context,
    private val encryptedKeyStorage: EncryptedKeyStorage = EncryptedKeyStorage(context)
) {

    companion object {
        private const val TAG = "Aura"

        val KEY_API_KEY = stringPreferencesKey("api_key")
        val KEY_MODEL_NAME = stringPreferencesKey("model_name")
        val KEY_VOICE_NAME = stringPreferencesKey("voice_name")
        val KEY_THINKING_LEVEL = stringPreferencesKey("thinking_level")
        val KEY_CACHED_LIVE_MODELS = stringSetPreferencesKey("cached_live_models")
        val KEY_UNSUPPORTED_SETUP_FIELDS = stringSetPreferencesKey("unsupported_setup_fields")

        // Language & Speech
        val KEY_DEFAULT_LANGUAGE = stringPreferencesKey("default_language")
        val KEY_GREET_ON_CONNECT = booleanPreferencesKey("greet_on_connect")
        val KEY_SHOW_CAPTIONS = booleanPreferencesKey("show_captions")

        // Interruption & Audio Processing
        val KEY_INTERRUPTION_SENSITIVITY = stringPreferencesKey("interruption_sensitivity")
        val KEY_ALLOW_INTERRUPTING = booleanPreferencesKey("allow_interrupting")
        val KEY_NOISE_GATE_STRENGTH = stringPreferencesKey("noise_gate_strength")
        val KEY_AUTO_HANGUP_SECONDS = intPreferencesKey("auto_hangup_seconds")

        // Wake Word
        val KEY_WAKE_PHRASE_ENABLED = booleanPreferencesKey("wake_phrase_enabled")
        val KEY_WAKE_WORD_ENGINE = stringPreferencesKey("wake_word_engine")
        val KEY_WAKE_WORD_SENSITIVITY = stringPreferencesKey("wake_word_sensitivity")

        // Overlays & Edge Lighting
        val KEY_EDGE_LIGHTING_ENABLED = booleanPreferencesKey("edge_lighting_enabled")
        val KEY_EDGE_LIGHTING_THICKNESS = intPreferencesKey("edge_lighting_thickness")
        val KEY_EDGE_LIGHTING_SHOW_IN_APP = booleanPreferencesKey("edge_lighting_show_in_app") // default true
        val KEY_EDGE_LIGHTING_SHOW_WAKE = booleanPreferencesKey("edge_lighting_show_wake") // default false
        val KEY_EDGE_LIGHTING_INTENSITY = floatPreferencesKey("edge_lighting_intensity")
        val KEY_OVERLAY_ENABLED = booleanPreferencesKey("overlay_enabled")
        val KEY_FLOATING_CAPTIONS_ENABLED = booleanPreferencesKey("floating_captions_enabled")

        // Agent Mouse Cursor
        val KEY_AGENT_CURSOR_ENABLED = booleanPreferencesKey("agent_cursor_enabled")
        val KEY_AGENT_CURSOR_STYLE = stringPreferencesKey("agent_cursor_style") // "ARROW", "DOT", "HAND"
        val KEY_AGENT_CURSOR_SIZE_DP = floatPreferencesKey("agent_cursor_size_dp") // 18f to 40f
        val KEY_AGENT_CURSOR_SPEED = stringPreferencesKey("agent_cursor_speed") // "INSTANT", "FAST", "NORMAL", "SLOW"
        val KEY_AGENT_CURSOR_TRAIL = booleanPreferencesKey("agent_cursor_trail")
        val KEY_AGENT_CURSOR_RIPPLE = booleanPreferencesKey("agent_cursor_ripple")
        val KEY_AGENT_CURSOR_ACTION_LABEL = booleanPreferencesKey("agent_cursor_action_label")

        // Idle & Screen Timeout
        val KEY_IDLE_SILENCE_HANGUP_MINUTES = intPreferencesKey("idle_silence_hangup_minutes") // default 5 min
        val KEY_KEEP_ALIVE_SCREEN_OFF = booleanPreferencesKey("keep_alive_screen_off") // default false
        val KEY_SCREEN_OFF_TIMEOUT_MINUTES = intPreferencesKey("screen_off_timeout_minutes") // default 3 min

        // Autonomous Agent Planner
        val KEY_PLANNER_MODEL_NAME = stringPreferencesKey("planner_model_name") // default "gemini-2.5-flash"
        val KEY_PLANNER_USE_SCREENSHOTS = booleanPreferencesKey("planner_use_screenshots") // default true
        val KEY_PLANNER_MAX_TIME_SECONDS = intPreferencesKey("planner_max_time_seconds") // default 180

        // Persona & Assistant Identity
        val KEY_ASSISTANT_NAME = stringPreferencesKey("assistant_name") // default "Maya"
        val KEY_SELECTED_PERSONA = stringPreferencesKey("selected_persona") // "maya", "friday", "venom", "jarvis", "custom"
        val KEY_RELATIONSHIP_STYLE = stringPreferencesKey("relationship_style") // "girlfriend", "friend", "assistant"
        val KEY_OWNER_NAME = stringPreferencesKey("owner_name")
        val KEY_EXTRA_INSTRUCTIONS = stringPreferencesKey("extra_instructions")
        val KEY_PERSONA_PROMPT_MAYA = stringPreferencesKey("persona_prompt_maya")
        val KEY_PERSONA_PROMPT_FRIDAY = stringPreferencesKey("persona_prompt_friday")
        val KEY_PERSONA_PROMPT_VENOM = stringPreferencesKey("persona_prompt_venom")
        val KEY_PERSONA_PROMPT_JARVIS = stringPreferencesKey("persona_prompt_jarvis")
        val KEY_PERSONA_PROMPT_CUSTOM = stringPreferencesKey("persona_prompt_custom")
        val KEY_PERSONA_VERSION = intPreferencesKey("persona_version")
        val KEY_SYSTEM_INSTRUCTION = stringPreferencesKey("system_instruction")
        val KEY_INCLUDE_SYSTEM_APPS = booleanPreferencesKey("include_system_apps")

        // Incoming Call Announcements
        val KEY_CALL_ANNOUNCEMENT_ENABLED = booleanPreferencesKey("call_announcement_enabled")
        val KEY_CALL_ANNOUNCE_WHATSAPP = booleanPreferencesKey("call_announce_whatsapp")
        val KEY_CALL_AUTO_ANSWER_VOICE = booleanPreferencesKey("call_auto_answer_voice")
        val KEY_CALL_AUTO_REJECT_ENABLED = booleanPreferencesKey("call_auto_reject_enabled")
        val KEY_CALL_AUTO_REJECT_MESSAGE = stringPreferencesKey("call_auto_reject_message")
        val KEY_CALL_AUTO_REPLY_SMS = booleanPreferencesKey("call_auto_reply_sms")
        val KEY_CALL_AUTO_REPLY_WHATSAPP = booleanPreferencesKey("call_auto_reply_whatsapp")

        // Notification Announcement System
        val KEY_NOTIF_ANNOUNCE_ENABLED = booleanPreferencesKey("notif_announce_enabled")
        val KEY_NOTIF_ANNOUNCE_APPS = stringSetPreferencesKey("notif_announce_apps")
        val KEY_NOTIF_ANNOUNCE_READ_CONTENT = booleanPreferencesKey("notif_announce_read_content")

        // Owner Voice Security Lock
        val KEY_OWNER_VOICE_LOCK_ENABLED = booleanPreferencesKey("owner_voice_lock_enabled")

        val DEFAULT_ANNOUNCE_APPS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "com.google.android.apps.messaging",
            "com.google.android.gm",
            "com.instagram.android"
        )

        // Safe Mode
        val KEY_SAFE_MODE_TRIGGERED = booleanPreferencesKey("safe_mode_triggered")

        // Existing General Settings
        val KEY_CONFIRM_RISKY_ACTIONS = booleanPreferencesKey("confirm_risky_actions")
        val KEY_MAX_AGENT_STEPS = intPreferencesKey("max_agent_steps")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_HAPTICS_ENABLED = booleanPreferencesKey("haptics_enabled")
        val KEY_USE_SPEAKER = booleanPreferencesKey("use_speaker")
        val KEY_HEADPHONES_MODE = booleanPreferencesKey("headphones_mode")
        val KEY_DISABLED_TOOLS = stringSetPreferencesKey("disabled_tools")
        val KEY_WHATSAPP_BUSINESS = booleanPreferencesKey("whatsapp_business")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        // Unlock Safety Policy
        val KEY_UNLOCK_CONSECUTIVE_FAILURES = intPreferencesKey("unlock_consecutive_failures")
        val KEY_UNLOCK_BLOCKED_UNTIL_MS = longPreferencesKey("unlock_blocked_until_ms")

        // Floating Captions Customization
        val KEY_INTERACTIVE_CAPTIONS_HANDLE = booleanPreferencesKey("interactive_captions_handle") // default false
        val KEY_CAPTION_POSITION = stringPreferencesKey("caption_position") // "BOTTOM", "TOP"
        val KEY_CAPTION_AUTO_HIDE_SECONDS = intPreferencesKey("caption_auto_hide_seconds") // default 4
        val KEY_CAPTION_TRANSPARENCY = floatPreferencesKey("caption_transparency") // default 0.90f

        const val DEFAULT_MODEL = "gemini-3.8-live"
        const val DEFAULT_VOICE = "Aoede"
        const val DEFAULT_THINKING_LEVEL = "LOW"
        const val DEFAULT_LANGUAGE = "Hinglish"
        const val DEFAULT_ASSISTANT_NAME = "Maya"
        const val DEFAULT_PERSONA = "maya"
        const val DEFAULT_OWNER_NAME = "Boss"

        val INITIAL_LIVE_MODELS = listOf(
            "gemini-3.8-live",
            "gemini-3.8-live-extended-thinking"
        )

        val THINKING_LEVEL_OPTIONS = listOf("LOW", "MEDIUM", "HIGH")

        val LANGUAGE_OPTIONS = listOf(
            "Hinglish",
            "English (India)",
            "Hindi",
            "Gujarati",
            "Marathi",
            "Bengali",
            "Tamil",
            "Telugu",
            "Kannada",
            "Malayalam",
            "English (US)"
        )

        val INTERRUPTION_SENSITIVITY_OPTIONS = listOf("Low", "Medium", "High")
        val NOISE_GATE_OPTIONS = listOf("Off", "Low", "Medium", "High")
        val WAKE_SENSITIVITY_OPTIONS = listOf("normal", "strict", "loose")
    }

    private val poolKeyTrigger = kotlinx.coroutines.flow.MutableStateFlow(0)

    fun getApiKeyPool(): List<ApiKeyEntry> {
        val jsonStr = encryptedKeyStorage.getApiKeyPoolJson()
        if (jsonStr.isBlank()) {
            val single = encryptedKeyStorage.getApiKey()
            if (single.isNotBlank()) {
                return listOf(ApiKeyEntry(key = single, status = "WORKING"))
            }
            return emptyList()
        }
        return try {
            val arr = org.json.JSONArray(jsonStr)
            val list = mutableListOf<ApiKeyEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    ApiKeyEntry(
                        key = obj.optString("key"),
                        status = obj.optString("status", "UNTESTED"),
                        addedMs = obj.optLong("addedMs", System.currentTimeMillis())
                    )
                )
            }
            list
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing API key pool JSON", e)
            emptyList()
        }
    }

    private fun saveApiKeyPool(pool: List<ApiKeyEntry>) {
        val arr = org.json.JSONArray()
        pool.forEach { entry ->
            val obj = org.json.JSONObject().apply {
                put("key", entry.key)
                put("status", entry.status)
                put("addedMs", entry.addedMs)
            }
            arr.put(obj)
        }
        encryptedKeyStorage.setApiKeyPoolJson(arr.toString())
        poolKeyTrigger.value = poolKeyTrigger.value + 1
    }

    fun addApiKey(key: String, status: String = "UNTESTED") {
        val cleanKey = key.trim()
        if (cleanKey.isBlank()) return
        val current = getApiKeyPool().toMutableList()
        val existingIndex = current.indexOfFirst { it.key == cleanKey }
        if (existingIndex >= 0) {
            current[existingIndex] = current[existingIndex].copy(status = status)
        } else {
            current.add(ApiKeyEntry(key = cleanKey, status = status))
        }
        saveApiKeyPool(current)
    }

    fun removeApiKey(key: String) {
        val current = getApiKeyPool().filter { it.key != key.trim() }
        saveApiKeyPool(current)
    }

    fun markKeyStatus(key: String, status: String) {
        val current = getApiKeyPool().map {
            if (it.key == key.trim()) it.copy(status = status) else it
        }
        saveApiKeyPool(current)
    }

    fun getPrimaryWorkingKey(): String {
        val pool = getApiKeyPool()
        if (pool.isNotEmpty()) {
            val working = pool.firstOrNull { it.status.equals("WORKING", ignoreCase = true) }
            if (working != null) return working.key

            val untested = pool.firstOrNull { it.status.equals("UNTESTED", ignoreCase = true) }
            if (untested != null) return untested.key

            val exceeded = pool.firstOrNull { it.status.equals("EXCEEDED", ignoreCase = true) }
            if (exceeded != null) return exceeded.key
        }

        val encrypted = encryptedKeyStorage.getApiKey()
        if (encrypted.isNotBlank()) return encrypted

        return try {
            val field = BuildConfig::class.java.getField("GEMINI_API_KEY")
            val key = field.get(null) as? String
            if (!key.isNullOrBlank() && key != "MY_GEMINI_API_KEY") key else ""
        } catch (e: Exception) {
            ""
        }
    }

    val apiKeyPool: Flow<List<ApiKeyEntry>> = poolKeyTrigger.map { getApiKeyPool() }

    val apiKey: Flow<String> = poolKeyTrigger.map { getPrimaryWorkingKey() }

    suspend fun migrateIfNeeded() {
        try {
            context.dataStore.edit { prefs ->
                val currentModel = prefs[KEY_MODEL_NAME]
                if (currentModel.isNullOrBlank() ||
                    currentModel == "gemini-2.0-flash-exp" ||
                    currentModel == "gemini-live-2.5-flash-preview"
                ) {
                    prefs[KEY_MODEL_NAME] = DEFAULT_MODEL
                    Log.i(TAG, "Migrated model to $DEFAULT_MODEL")
                }

                val legacyKey = prefs[KEY_API_KEY]
                if (!legacyKey.isNullOrBlank()) {
                    addApiKey(legacyKey, "WORKING")
                    prefs.remove(KEY_API_KEY)
                    Log.i(TAG, "Migrated legacy API key to pool")
                }

                val personaVersion = prefs[KEY_PERSONA_VERSION] ?: 0
                if (personaVersion < PersonaBuilder.CURRENT_PERSONA_VERSION) {
                    val legacyPersona = prefs[KEY_SYSTEM_INSTRUCTION]
                    if (!legacyPersona.isNullOrBlank()) {
                        if (!legacyPersona.startsWith("You are Aura, a realtime voice agent for Android")) {
                            prefs[KEY_EXTRA_INSTRUCTIONS] = legacyPersona
                            Log.i(TAG, "Migrated custom system instruction to extraInstructions")
                        }
                        prefs.remove(KEY_SYSTEM_INSTRUCTION)
                    }
                    prefs[KEY_PERSONA_VERSION] = PersonaBuilder.CURRENT_PERSONA_VERSION
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during preferences migration: ${e.message}", e)
        }
    }

    val modelName: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        val model = it[KEY_MODEL_NAME]
        if (model.isNullOrBlank() || model == "gemini-2.0-flash-exp" || model == "gemini-live-2.5-flash-preview") {
            DEFAULT_MODEL
        } else {
            model
        }
    }

    val voiceName: Flow<String> = kotlinx.coroutines.flow.flow { emit(DEFAULT_VOICE) }

    val thinkingLevel: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        val level = it[KEY_THINKING_LEVEL] ?: DEFAULT_THINKING_LEVEL
        if (level.equals("MINIMAL", ignoreCase = true)) "LOW" else level
    }

    val defaultLanguage: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_DEFAULT_LANGUAGE] ?: DEFAULT_LANGUAGE
    }

    val greetOnConnect: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_GREET_ON_CONNECT] ?: true
    }

    val showCaptions: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_SHOW_CAPTIONS] ?: true
    }

    val interruptionSensitivity: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_INTERRUPTION_SENSITIVITY] ?: "Low"
    }

    val allowInterrupting: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ALLOW_INTERRUPTING] ?: true
    }

    val noiseGateStrength: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_NOISE_GATE_STRENGTH] ?: "Medium"
    }

    val autoHangupSeconds: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AUTO_HANGUP_SECONDS] ?: 30
    }

    val wakePhraseEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WAKE_PHRASE_ENABLED] ?: false
    }

    val wakeWordEngine: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WAKE_WORD_ENGINE] ?: "Vosk"
    }

    val wakeWordSensitivity: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WAKE_WORD_SENSITIVITY] ?: "normal"
    }

    // Overlays & Edge Lighting
    val edgeLightingEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_ENABLED] ?: true
    }

    val edgeLightingThickness: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_THICKNESS] ?: 8
    }

    val edgeLightingShowInApp: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_SHOW_IN_APP] ?: true
    }

    val edgeLightingShowWake: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_SHOW_WAKE] ?: false
    }

    val edgeLightingIntensity: Flow<Float> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EDGE_LIGHTING_INTENSITY] ?: 1.0f
    }

    // Agent Mouse Cursor
    val agentCursorEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AGENT_CURSOR_ENABLED] ?: true
    }

    val agentCursorStyle: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AGENT_CURSOR_STYLE] ?: "ARROW"
    }

    val agentCursorSizeDp: Flow<Float> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AGENT_CURSOR_SIZE_DP] ?: 26f
    }

    val agentCursorSpeed: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AGENT_CURSOR_SPEED] ?: "NORMAL"
    }

    val agentCursorTrail: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AGENT_CURSOR_TRAIL] ?: true
    }

    val agentCursorRipple: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AGENT_CURSOR_RIPPLE] ?: true
    }

    val agentCursorActionLabel: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_AGENT_CURSOR_ACTION_LABEL] ?: true
    }

    val floatingCaptionsEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_FLOATING_CAPTIONS_ENABLED] ?: true
    }

    val overlayEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_OVERLAY_ENABLED] ?: false
    }

    // Persona
    val assistantName: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ASSISTANT_NAME] ?: DEFAULT_ASSISTANT_NAME
    }

    val selectedPersona: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_SELECTED_PERSONA] ?: DEFAULT_PERSONA
    }

    val relationshipStyle: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_RELATIONSHIP_STYLE] ?: "girlfriend"
    }

    val ownerName: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_OWNER_NAME] ?: DEFAULT_OWNER_NAME
    }

    val extraInstructions: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_EXTRA_INSTRUCTIONS] ?: ""
    }

    fun getPersonaCustomPrompt(persona: String): Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        when (persona.lowercase()) {
            "maya" -> it[KEY_PERSONA_PROMPT_MAYA] ?: ""
            "friday" -> it[KEY_PERSONA_PROMPT_FRIDAY] ?: ""
            "venom" -> it[KEY_PERSONA_PROMPT_VENOM] ?: ""
            "jarvis" -> it[KEY_PERSONA_PROMPT_JARVIS] ?: ""
            else -> it[KEY_PERSONA_PROMPT_CUSTOM] ?: ""
        }
    }

    // Calls
    val callAnnouncementEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CALL_ANNOUNCEMENT_ENABLED] ?: true
    }

    val callAnnounceWhatsapp: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CALL_ANNOUNCE_WHATSAPP] ?: true
    }

    val callAutoAnswerVoice: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CALL_AUTO_ANSWER_VOICE] ?: true
    }

    val callAutoRejectEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CALL_AUTO_REJECT_ENABLED] ?: false
    }

    val callAutoRejectMessage: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CALL_AUTO_REJECT_MESSAGE] ?: "Mai busy hu, baad me call karta hu."
    }

    val callAutoReplySms: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CALL_AUTO_REPLY_SMS] ?: true
    }

    val callAutoReplyWhatsapp: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CALL_AUTO_REPLY_WHATSAPP] ?: true
    }

    val notifAnnounceEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_NOTIF_ANNOUNCE_ENABLED] ?: false
    }

    val notifAnnounceApps: Flow<Set<String>> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_NOTIF_ANNOUNCE_APPS] ?: DEFAULT_ANNOUNCE_APPS
    }

    val notifAnnounceReadContent: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_NOTIF_ANNOUNCE_READ_CONTENT] ?: true
    }

    val ownerVoiceLockEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_OWNER_VOICE_LOCK_ENABLED] ?: false
    }

    // Secure Screen Lock Credentials & Options (Stored in EncryptedKeyStorage)
    private val _credentialTrigger = kotlinx.coroutines.flow.MutableStateFlow(System.currentTimeMillis())

    val screenLockType: Flow<String> = _credentialTrigger.map {
        encryptedKeyStorage.getLockType()
    }

    val savedPatternPoints: Flow<String> = _credentialTrigger.map {
        encryptedKeyStorage.getPatternPoints()
    }

    val savedLockPin: Flow<String> = _credentialTrigger.map {
        encryptedKeyStorage.getLockPin()
    }

    val savedLockPassword: Flow<String> = _credentialTrigger.map {
        encryptedKeyStorage.getLockPassword()
    }

    val confirmBeforeUnlock: Flow<Boolean> = _credentialTrigger.map {
        encryptedKeyStorage.getConfirmBeforeUnlock()
    }

    val showCursorDuringUnlock: Flow<Boolean> = _credentialTrigger.map {
        encryptedKeyStorage.getShowCursorDuringUnlock()
    }

    // Safe mode
    val isSafeModeTriggered: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_SAFE_MODE_TRIGGERED] ?: false
    }

    val idleSilenceHangupMinutes: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_IDLE_SILENCE_HANGUP_MINUTES] ?: 5
    }
    val idleSilenceMinutes: Flow<Int> get() = idleSilenceHangupMinutes

    val keepAliveScreenOff: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_KEEP_ALIVE_SCREEN_OFF] ?: false
    }

    val screenOffTimeoutMinutes: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_SCREEN_OFF_TIMEOUT_MINUTES] ?: 3
    }

    val plannerModelName: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_PLANNER_MODEL_NAME] ?: "gemini-2.5-flash"
    }
    val plannerModel: Flow<String> get() = plannerModelName

    val plannerUseScreenshots: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_PLANNER_USE_SCREENSHOTS] ?: true
    }
    val useScreenshotInPlanner: Flow<Boolean> get() = plannerUseScreenshots

    val plannerMaxTimeSeconds: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_PLANNER_MAX_TIME_SECONDS] ?: 180
    }

    val includeSystemApps: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_INCLUDE_SYSTEM_APPS] ?: true
    }

    val unsupportedSetupFields: Flow<Set<String>> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_UNSUPPORTED_SETUP_FIELDS] ?: emptySet()
    }

    val cachedLiveModels: Flow<Set<String>> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CACHED_LIVE_MODELS] ?: INITIAL_LIVE_MODELS.toSet()
    }

    val confirmRiskyActions: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CONFIRM_RISKY_ACTIONS] ?: true
    }

    val maxAgentSteps: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_MAX_AGENT_STEPS] ?: 25
    }

    val themeMode: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_THEME_MODE] ?: "dark"
    }

    val hapticsEnabled: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_HAPTICS_ENABLED] ?: true
    }

    val useSpeaker: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_USE_SPEAKER] ?: true
    }

    val headphonesMode: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_HEADPHONES_MODE] ?: false
    }

    val disabledTools: Flow<Set<String>> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_DISABLED_TOOLS] ?: emptySet()
    }

    val isWhatsappBusiness: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_WHATSAPP_BUSINESS] ?: false
    }

    val unlockConsecutiveFailures: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_UNLOCK_CONSECUTIVE_FAILURES] ?: 0
    }

    val unlockBlockedUntilMs: Flow<Long> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_UNLOCK_BLOCKED_UNTIL_MS] ?: 0L
    }

    val interactiveCaptionsHandle: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_INTERACTIVE_CAPTIONS_HANDLE] ?: false
    }

    val captionPosition: Flow<String> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CAPTION_POSITION] ?: "BOTTOM"
    }

    val captionAutoHideSeconds: Flow<Int> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CAPTION_AUTO_HIDE_SECONDS] ?: 4
    }

    val captionTransparency: Flow<Float> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_CAPTION_TRANSPARENCY] ?: 0.90f
    }

    val interactiveCaptionsHandleFlow: Flow<Boolean> get() = interactiveCaptionsHandle
    val captionPositionFlow: Flow<String> get() = captionPosition
    val captionAutoHideSecondsFlow: Flow<Int> get() = captionAutoHideSeconds
    val captionTransparencyFlow: Flow<Float> get() = captionTransparency

    val isOnboardingCompleted: Flow<Boolean> = context.dataStore.data.catch { emitDefaults(it) }.map {
        it[KEY_ONBOARDING_COMPLETED] ?: false
    }

    // Setters
    suspend fun setApiKey(key: String) {
        encryptedKeyStorage.setApiKey(key.trim())
        try {
            context.dataStore.edit { it.remove(KEY_API_KEY) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear legacy API key: ${e.message}", e)
        }
    }

    suspend fun setModelName(name: String) = context.dataStore.edit { it[KEY_MODEL_NAME] = name.trim() }
    suspend fun setVoiceName(voice: String) = context.dataStore.edit { it[KEY_VOICE_NAME] = DEFAULT_VOICE }
    suspend fun setThinkingLevel(level: String) {
        val sanitized = if (level.equals("MINIMAL", ignoreCase = true)) "LOW" else level.uppercase()
        context.dataStore.edit { it[KEY_THINKING_LEVEL] = sanitized }
    }
    suspend fun setDefaultLanguage(lang: String) = context.dataStore.edit { it[KEY_DEFAULT_LANGUAGE] = lang }
    suspend fun setGreetOnConnect(enabled: Boolean) = context.dataStore.edit { it[KEY_GREET_ON_CONNECT] = enabled }
    suspend fun setShowCaptions(show: Boolean) = context.dataStore.edit { it[KEY_SHOW_CAPTIONS] = show }
    suspend fun setInterruptionSensitivity(sens: String) = context.dataStore.edit { it[KEY_INTERRUPTION_SENSITIVITY] = sens }
    suspend fun setAllowInterrupting(allow: Boolean) = context.dataStore.edit { it[KEY_ALLOW_INTERRUPTING] = allow }
    suspend fun setNoiseGateStrength(strength: String) = context.dataStore.edit { it[KEY_NOISE_GATE_STRENGTH] = strength }
    suspend fun setAutoHangupSeconds(sec: Int) = context.dataStore.edit { it[KEY_AUTO_HANGUP_SECONDS] = sec }
    suspend fun setWakePhraseEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_WAKE_PHRASE_ENABLED] = enabled }
    suspend fun setWakeWordEngine(engine: String) = context.dataStore.edit { it[KEY_WAKE_WORD_ENGINE] = engine }
    suspend fun setWakeWordSensitivity(sens: String) = context.dataStore.edit { it[KEY_WAKE_WORD_SENSITIVITY] = sens }

    suspend fun setEdgeLightingEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_ENABLED] = enabled }
    suspend fun setEdgeLightingThickness(thickness: Int) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_THICKNESS] = thickness }
    suspend fun setEdgeLightingShowInApp(show: Boolean) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_SHOW_IN_APP] = show }
    suspend fun setEdgeLightingShowWake(show: Boolean) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_SHOW_WAKE] = show }
    suspend fun setEdgeLightingIntensity(intensity: Float) = context.dataStore.edit { it[KEY_EDGE_LIGHTING_INTENSITY] = intensity }
    suspend fun setFloatingCaptionsEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_FLOATING_CAPTIONS_ENABLED] = enabled }

    // Agent Mouse Cursor Setters
    suspend fun setAgentCursorEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_AGENT_CURSOR_ENABLED] = enabled }
    suspend fun setAgentCursorStyle(style: String) = context.dataStore.edit { it[KEY_AGENT_CURSOR_STYLE] = style }
    suspend fun setAgentCursorSizeDp(size: Float) = context.dataStore.edit { it[KEY_AGENT_CURSOR_SIZE_DP] = size }
    suspend fun setAgentCursorSpeed(speed: String) = context.dataStore.edit { it[KEY_AGENT_CURSOR_SPEED] = speed }
    suspend fun setAgentCursorTrail(enabled: Boolean) = context.dataStore.edit { it[KEY_AGENT_CURSOR_TRAIL] = enabled }
    suspend fun setAgentCursorRipple(enabled: Boolean) = context.dataStore.edit { it[KEY_AGENT_CURSOR_RIPPLE] = enabled }
    suspend fun setAgentCursorActionLabel(enabled: Boolean) = context.dataStore.edit { it[KEY_AGENT_CURSOR_ACTION_LABEL] = enabled }

    suspend fun setAssistantName(name: String) = context.dataStore.edit { it[KEY_ASSISTANT_NAME] = name.trim() }
    suspend fun setSelectedPersona(persona: String) = context.dataStore.edit { it[KEY_SELECTED_PERSONA] = persona.lowercase().trim() }
    suspend fun setRelationshipStyle(style: String) = context.dataStore.edit { it[KEY_RELATIONSHIP_STYLE] = style.lowercase().trim() }
    suspend fun setOwnerName(name: String) = context.dataStore.edit { it[KEY_OWNER_NAME] = name }
    suspend fun setExtraInstructions(instructions: String) = context.dataStore.edit { it[KEY_EXTRA_INSTRUCTIONS] = instructions }

    suspend fun setPersonaCustomPrompt(persona: String, prompt: String) {
        context.dataStore.edit {
            when (persona.lowercase()) {
                "maya" -> it[KEY_PERSONA_PROMPT_MAYA] = prompt
                "friday" -> it[KEY_PERSONA_PROMPT_FRIDAY] = prompt
                "venom" -> it[KEY_PERSONA_PROMPT_VENOM] = prompt
                "jarvis" -> it[KEY_PERSONA_PROMPT_JARVIS] = prompt
                else -> it[KEY_PERSONA_PROMPT_CUSTOM] = prompt
            }
        }
    }

    suspend fun setCallAnnouncementEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_CALL_ANNOUNCEMENT_ENABLED] = enabled }
    suspend fun setCallAnnounceWhatsapp(enabled: Boolean) = context.dataStore.edit { it[KEY_CALL_ANNOUNCE_WHATSAPP] = enabled }
    suspend fun setCallAutoAnswerVoice(enabled: Boolean) = context.dataStore.edit { it[KEY_CALL_AUTO_ANSWER_VOICE] = enabled }
    suspend fun setCallAutoRejectEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_CALL_AUTO_REJECT_ENABLED] = enabled }
    suspend fun setCallAutoRejectMessage(msg: String) = context.dataStore.edit { it[KEY_CALL_AUTO_REJECT_MESSAGE] = msg.trim() }
    suspend fun setCallAutoReplySms(enabled: Boolean) = context.dataStore.edit { it[KEY_CALL_AUTO_REPLY_SMS] = enabled }
    suspend fun setCallAutoReplyWhatsapp(enabled: Boolean) = context.dataStore.edit { it[KEY_CALL_AUTO_REPLY_WHATSAPP] = enabled }

    suspend fun configureCallAutoReject(
        enabled: Boolean,
        message: String? = null,
        replySms: Boolean? = null,
        replyWhatsapp: Boolean? = null
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CALL_AUTO_REJECT_ENABLED] = enabled
            if (message != null && message.isNotBlank()) {
                prefs[KEY_CALL_AUTO_REJECT_MESSAGE] = message.trim()
            }
            if (replySms != null) {
                prefs[KEY_CALL_AUTO_REPLY_SMS] = replySms
            }
            if (replyWhatsapp != null) {
                prefs[KEY_CALL_AUTO_REPLY_WHATSAPP] = replyWhatsapp
            }
        }
    }

    suspend fun setNotifAnnounceEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_NOTIF_ANNOUNCE_ENABLED] = enabled }
    suspend fun setNotifAnnounceApps(apps: Set<String>) = context.dataStore.edit { it[KEY_NOTIF_ANNOUNCE_APPS] = apps }
    suspend fun toggleNotifAnnounceApp(packageName: String) = context.dataStore.edit { prefs ->
        val cur = prefs[KEY_NOTIF_ANNOUNCE_APPS]?.toMutableSet() ?: DEFAULT_ANNOUNCE_APPS.toMutableSet()
        if (cur.contains(packageName)) {
            cur.remove(packageName)
        } else {
            cur.add(packageName)
        }
        prefs[KEY_NOTIF_ANNOUNCE_APPS] = cur
    }
    suspend fun setNotifAnnounceReadContent(readContent: Boolean) = context.dataStore.edit { it[KEY_NOTIF_ANNOUNCE_READ_CONTENT] = readContent }
    suspend fun setOwnerVoiceLockEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_OWNER_VOICE_LOCK_ENABLED] = enabled }

    suspend fun setSafeModeTriggered(triggered: Boolean) = context.dataStore.edit { it[KEY_SAFE_MODE_TRIGGERED] = triggered }

    suspend fun setIdleSilenceHangupMinutes(minutes: Int) = context.dataStore.edit { it[KEY_IDLE_SILENCE_HANGUP_MINUTES] = minutes }
    suspend fun setKeepAliveScreenOff(keepAlive: Boolean) = context.dataStore.edit { it[KEY_KEEP_ALIVE_SCREEN_OFF] = keepAlive }
    suspend fun setScreenOffTimeoutMinutes(minutes: Int) = context.dataStore.edit { it[KEY_SCREEN_OFF_TIMEOUT_MINUTES] = minutes }
    suspend fun setPlannerModelName(model: String) = context.dataStore.edit { it[KEY_PLANNER_MODEL_NAME] = model.trim() }
    suspend fun setPlannerUseScreenshots(use: Boolean) = context.dataStore.edit { it[KEY_PLANNER_USE_SCREENSHOTS] = use }
    suspend fun setPlannerMaxTimeSeconds(seconds: Int) = context.dataStore.edit { it[KEY_PLANNER_MAX_TIME_SECONDS] = seconds }
    suspend fun setIncludeSystemApps(include: Boolean) = context.dataStore.edit { it[KEY_INCLUDE_SYSTEM_APPS] = include }

    suspend fun recordUnsupportedSetupField(fieldKey: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[KEY_UNSUPPORTED_SETUP_FIELDS]?.toMutableSet() ?: mutableSetOf()
            cur.add(fieldKey)
            prefs[KEY_UNSUPPORTED_SETUP_FIELDS] = cur
        }
    }

    suspend fun resetExtraInstructions() {
        context.dataStore.edit { it.remove(KEY_EXTRA_INSTRUCTIONS) }
    }

    suspend fun setCachedLiveModels(models: Set<String>) = context.dataStore.edit { it[KEY_CACHED_LIVE_MODELS] = models }
    suspend fun setConfirmRiskyActions(enabled: Boolean) = context.dataStore.edit { it[KEY_CONFIRM_RISKY_ACTIONS] = enabled }
    suspend fun setMaxAgentSteps(steps: Int) = context.dataStore.edit { it[KEY_MAX_AGENT_STEPS] = steps }
    suspend fun setOverlayEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_OVERLAY_ENABLED] = enabled }
    suspend fun setThemeMode(mode: String) = context.dataStore.edit { it[KEY_THEME_MODE] = mode }
    suspend fun setHapticsEnabled(enabled: Boolean) = context.dataStore.edit { it[KEY_HAPTICS_ENABLED] = enabled }
    suspend fun setUseSpeaker(use: Boolean) = context.dataStore.edit { it[KEY_USE_SPEAKER] = use }
    suspend fun setHeadphonesMode(enabled: Boolean) = context.dataStore.edit { it[KEY_HEADPHONES_MODE] = enabled }
    suspend fun setWhatsappBusiness(enabled: Boolean) = context.dataStore.edit { it[KEY_WHATSAPP_BUSINESS] = enabled }
    suspend fun setOnboardingCompleted(completed: Boolean) = context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = completed }

    // Secure Screen Lock Credentials
    suspend fun setScreenLockType(type: String) {
        encryptedKeyStorage.setLockType(type)
        _credentialTrigger.value = System.currentTimeMillis()
        resetUnlockSafetyLimit()
    }
    suspend fun setSavedPatternPoints(points: String) {
        encryptedKeyStorage.setPatternPoints(points)
        _credentialTrigger.value = System.currentTimeMillis()
        resetUnlockSafetyLimit()
    }
    suspend fun setSavedLockPin(pin: String) {
        encryptedKeyStorage.setLockPin(pin)
        _credentialTrigger.value = System.currentTimeMillis()
        resetUnlockSafetyLimit()
    }
    suspend fun setSavedLockPassword(password: String) {
        encryptedKeyStorage.setLockPassword(password)
        _credentialTrigger.value = System.currentTimeMillis()
        resetUnlockSafetyLimit()
    }
    suspend fun setConfirmBeforeUnlock(confirm: Boolean) {
        encryptedKeyStorage.setConfirmBeforeUnlock(confirm)
        _credentialTrigger.value = System.currentTimeMillis()
    }
    suspend fun setShowCursorDuringUnlock(show: Boolean) {
        encryptedKeyStorage.setShowCursorDuringUnlock(show)
        _credentialTrigger.value = System.currentTimeMillis()
    }
    suspend fun setPatternCalibrationJson(json: String) {
        encryptedKeyStorage.setPatternCalibrationJson(json)
        _credentialTrigger.value = System.currentTimeMillis()
    }

    suspend fun setUnlockConsecutiveFailures(count: Int) = context.dataStore.edit { it[KEY_UNLOCK_CONSECUTIVE_FAILURES] = count }
    suspend fun setUnlockBlockedUntilMs(timestamp: Long) = context.dataStore.edit { it[KEY_UNLOCK_BLOCKED_UNTIL_MS] = timestamp }
    suspend fun resetUnlockSafetyLimit() = context.dataStore.edit {
        it[KEY_UNLOCK_CONSECUTIVE_FAILURES] = 0
        it[KEY_UNLOCK_BLOCKED_UNTIL_MS] = 0L
    }

    suspend fun setInteractiveCaptionsHandle(enabled: Boolean) = context.dataStore.edit { it[KEY_INTERACTIVE_CAPTIONS_HANDLE] = enabled }
    suspend fun setCaptionPosition(position: String) = context.dataStore.edit { it[KEY_CAPTION_POSITION] = position }
    suspend fun setCaptionAutoHideSeconds(sec: Int) = context.dataStore.edit { it[KEY_CAPTION_AUTO_HIDE_SECONDS] = sec }
    suspend fun setCaptionTransparency(alpha: Float) = context.dataStore.edit { it[KEY_CAPTION_TRANSPARENCY] = alpha }

    suspend fun migrateLegacyCredentialsAndEdgeLighting() {
        try {
            context.dataStore.edit { prefs ->
                val legacyType = prefs[stringPreferencesKey("screen_lock_type")]
                val legacyPin = prefs[stringPreferencesKey("saved_lock_pin")]
                val legacyPattern = prefs[stringPreferencesKey("saved_pattern_points")]
                val legacyPassword = prefs[stringPreferencesKey("saved_lock_password")]

                if (!legacyType.isNullOrBlank() && encryptedKeyStorage.getLockType().isBlank()) {
                    encryptedKeyStorage.setLockType(legacyType)
                }
                if (!legacyPin.isNullOrBlank() && encryptedKeyStorage.getLockPin().isBlank()) {
                    encryptedKeyStorage.setLockPin(legacyPin)
                }
                if (!legacyPattern.isNullOrBlank() && encryptedKeyStorage.getPatternPoints().isBlank()) {
                    encryptedKeyStorage.setPatternPoints(legacyPattern)
                }
                if (!legacyPassword.isNullOrBlank() && encryptedKeyStorage.getLockPassword().isBlank()) {
                    encryptedKeyStorage.setLockPassword(legacyPassword)
                }

                // Remove legacy keys from DataStore
                prefs.remove(stringPreferencesKey("screen_lock_type"))
                prefs.remove(stringPreferencesKey("saved_lock_pin"))
                prefs.remove(stringPreferencesKey("saved_pattern_points"))
                prefs.remove(stringPreferencesKey("saved_lock_password"))
                prefs.remove(stringPreferencesKey("edge_lighting_style"))
                prefs.remove(stringPreferencesKey("edge_lighting_theme"))
                prefs.remove(intPreferencesKey("edge_lighting_comet_count"))
                prefs.remove(floatPreferencesKey("edge_lighting_flow_speed"))
                prefs.remove(intPreferencesKey("edge_lighting_depth_percent"))
            }
            _credentialTrigger.value = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.e(TAG, "Failed migration: ${e.message}", e)
        }
    }

    suspend fun setToolEnabled(toolName: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_DISABLED_TOOLS]?.toMutableSet() ?: mutableSetOf()
            if (enabled) current.remove(toolName) else current.add(toolName)
            prefs[KEY_DISABLED_TOOLS] = current
        }
    }

    private suspend fun FlowCollector<Preferences>.emitDefaults(throwable: Throwable) {
        if (throwable is IOException) {
            emit(emptyPreferences())
        } else {
            throw throwable
        }
    }
}
