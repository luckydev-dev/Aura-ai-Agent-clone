package com.example.core.live

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Shared helper for Gemini Live BidiGenerateContent WebSocket handshake,
 * payload serialization, deserialization, API key validation, model profile configuration,
 * dynamic live model fetching, and diagnostics logging.
 */
object GeminiLiveHandshake {
    private const val TAG = "Aura"

    /**
     * Builds the standard Gemini Live setup JSON payload according to the BidiGenerateContent spec.
     */
    fun buildSetupMessageJson(
        model: String,
        voice: String = "Aoede",
        systemPrompt: String = "",
        resumptionHandle: String = "",
        toolDeclarationsJson: List<Map<String, Any>> = emptyList(),
        thinkingLevel: String = "LOW",
        language: String = "Hinglish",
        interruptionSensitivity: String = "Low",
        allowInterrupting: Boolean = true,
        unsupportedFields: Set<String> = emptySet()
    ): String {
        val modelPath = if (model.startsWith("models/")) model else "models/$model"
        val cleanModel = model.removePrefix("models/").trim().lowercase()
        val isExtendedThinking = cleanModel == "gemini-3.8-live-extended-thinking"

        val setupObj = JSONObject().apply {
            put("model", modelPath)

            val genConfig = JSONObject().apply {
                put("responseModalities", JSONArray().apply { put("AUDIO") })

                val speechConfig = JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", voice.ifBlank { "Aoede" })
                        })
                    })
                    // Add languageCode = "hi-IN" if not blacklisted for this model
                    if (!unsupportedFields.contains("speechConfig_languageCode")) {
                        put("languageCode", "hi-IN")
                    }
                }
                put("speechConfig", speechConfig)

                // Extended thinking profile
                if (isExtendedThinking) {
                    val safeLevel = when (thinkingLevel.uppercase()) {
                        "MEDIUM" -> "MEDIUM"
                        "HIGH" -> "HIGH"
                        else -> "LOW" // Default LOW, never MINIMAL!
                    }
                    put("thinkingConfig", JSONObject().apply {
                        put("thinkingLevel", safeLevel)
                    })
                }
            }
            put("generationConfig", genConfig)

            // Audio transcription configs
            val inputAudioObj = JSONObject()
            if (!unsupportedFields.contains("inputAudioTranscription_languageCodes")) {
                inputAudioObj.put("languageCodes", JSONArray().apply {
                    put("hi-IN")
                    put("en-IN")
                })
            }
            put("inputAudioTranscription", inputAudioObj)
            put("outputAudioTranscription", JSONObject())

            // Realtime Input Config: VAD sensitivity and Activity Handling
            if (!unsupportedFields.contains("realtimeInputConfig")) {
                val (startSensitivity, prefixPadding, silenceDuration) = when (interruptionSensitivity.lowercase()) {
                    "high" -> Triple("START_SENSITIVITY_HIGH", 40, 250)
                    "medium" -> Triple("START_SENSITIVITY_LOW", 80, 400)
                    else -> Triple("START_SENSITIVITY_LOW", 120, 600) // "low" (default)
                }

                val autoDetection = JSONObject().apply {
                    put("disabled", false)
                    put("startOfSpeechSensitivity", startSensitivity)
                    put("prefixPaddingMs", prefixPadding)
                    put("silenceDurationMs", silenceDuration)
                }

                val realtimeConfig = JSONObject().apply {
                    put("automaticActivityDetection", autoDetection)
                    put(
                        "activityHandling",
                        if (allowInterrupting) "START_OF_ACTIVITY_INTERRUPTS" else "NO_INTERRUPTION"
                    )
                }
                put("realtimeInputConfig", realtimeConfig)
            }

            // Resumption handle if supplied
            if (resumptionHandle.isNotBlank()) {
                put("sessionResumption", JSONObject().apply {
                    put("handle", resumptionHandle)
                })
            }

            // System instruction
            if (systemPrompt.isNotBlank()) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
            }

            // Function declarations for tools
            if (toolDeclarationsJson.isNotEmpty()) {
                val declarationsArray = JSONArray()
                for (tool in toolDeclarationsJson) {
                    try {
                        val toolJson = JSONObject(tool)
                        if (isExtendedThinking) {
                            toolJson.put("behavior", "NON_BLOCKING")
                        }
                        declarationsArray.put(toolJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse tool declaration JSON: $tool", e)
                    }
                }
                if (declarationsArray.length() > 0) {
                    put("tools", JSONArray().apply {
                        put(JSONObject().apply {
                            put("functionDeclarations", declarationsArray)
                        })
                    })
                }
            }
        }

        return JSONObject().apply {
            put("setup", setupObj)
        }.toString()
    }

    /**
     * Dynamically queries the Google AI Studio / Gemini models endpoint and filters
     * exclusively for models that support bidirectional streaming ("bidiGenerateContent").
     */
    suspend fun fetchAvailableLiveModels(apiKey: String): Result<List<String>> = withContext(Dispatchers.IO) {
        val trimmedKey = apiKey.trim()
        if (trimmedKey.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Gemini API Key is empty."))
        }

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val liveModels = mutableListOf<String>()
            var pageToken: String? = null

            do {
                val urlBuilder = StringBuilder("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200&key=$trimmedKey")
                if (!pageToken.isNullOrBlank()) {
                    urlBuilder.append("&pageToken=").append(pageToken)
                }

                val request = Request.Builder().url(urlBuilder.toString()).get().build()
                val response = client.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val hint = getAuthHintForError(trimmedKey, "HTTP ${response.code}: $responseBody")
                    val errorMsg = "HTTP ${response.code}: ${responseBody.take(250)}${if (hint != null) "\n$hint" else ""}"
                    return@withContext Result.failure(Exception(errorMsg))
                }

                val json = JSONObject(responseBody)
                val modelsArray = json.optJSONArray("models")
                if (modelsArray != null) {
                    for (i in 0 until modelsArray.length()) {
                        val m = modelsArray.getJSONObject(i)
                        val methods = m.optJSONArray("supportedGenerationMethods")
                        var hasBidi = false
                        if (methods != null) {
                            for (j in 0 until methods.length()) {
                                if (methods.getString(j) == "bidiGenerateContent") {
                                    hasBidi = true
                                    break
                                }
                            }
                        }
                        if (hasBidi) {
                            val name = m.getString("name").removePrefix("models/")
                            liveModels.add(name)
                        }
                    }
                }

                pageToken = json.optString("nextPageToken").ifBlank { null }
            } while (pageToken != null)

            if (liveModels.isEmpty()) {
                liveModels.addAll(listOf("gemini-3.8-live", "gemini-3.8-live-extended-thinking"))
            }

            Result.success(liveModels)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch live models list", e)
            Result.failure(e)
        }
    }

    fun maskApiKey(key: String): String {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "(Not set)"
        return if (trimmed.length <= 4) "${trimmed}***" else "${trimmed.take(4)}...${trimmed.takeLast(2)}"
    }

    fun getKeyTypeDescription(key: String): String {
        val trimmed = key.trim()
        return when {
            trimmed.isEmpty() -> "Not configured"
            trimmed.startsWith("AQ.", ignoreCase = true) || trimmed.startsWith("AQ") ->
                "AQ.*** (New AI Studio Auth Key format - if rejected with 401 ACCESS_TOKEN_TYPE_UNSUPPORTED, use an AIza*** API key)"
            trimmed.startsWith("AIza") -> "AIza*** (Standard Google Cloud / AI Studio API key)"
            else -> "${trimmed.take(4)}*** (Custom key format)"
        }
    }

    fun getAuthHintForError(key: String, errorText: String): String? {
        val trimmed = key.trim()
        val isAuthFailure = errorText.contains("401") || errorText.contains("403") ||
                errorText.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", ignoreCase = true) ||
                errorText.contains("API_KEY", ignoreCase = true) ||
                errorText.contains("API key not valid", ignoreCase = true) ||
                errorText.contains("UNAUTHENTICATED", ignoreCase = true) ||
                errorText.contains("PERMISSION_DENIED", ignoreCase = true)

        if (!isAuthFailure && !errorText.contains("Setup timeout", ignoreCase = true)) {
            return null
        }

        return if (trimmed.startsWith("AQ", ignoreCase = true)) {
            "Auth Hint: Your key begins with '${trimmed.take(4)}...' (OAuth/Bearer-like token). The Gemini Live WebSocket endpoint requires a Google API Key starting with 'AIza...'. Endpoints reject 'AQ.' keys with 401 ACCESS_TOKEN_TYPE_UNSUPPORTED."
        } else if (trimmed.startsWith("AIza")) {
            "Auth Hint: Your key begins with 'AIza...'. Verify that the Generative Language API is enabled and has quota."
        } else {
            "Auth Hint: Check that a valid Gemini API key is configured in Settings."
        }
    }

    fun extractOffendingFieldFrom1007(reason: String): String? {
        val lower = reason.lowercase()
        return when {
            lower.contains("languagecode") -> "speechConfig_languageCode"
            lower.contains("realtime_input_config") || lower.contains("realtimeinputconfig") -> "realtimeInputConfig"
            lower.contains("inputaudiotranscription") || lower.contains("languagecodes") -> "inputAudioTranscription_languageCodes"
            lower.contains("tools") || lower.contains("functiondeclaration") -> "tools"
            else -> null
        }
    }

    data class ParsedFrameResult(
        val isSetupComplete: Boolean = false,
        val isGoAway: Boolean = false,
        val goAwayReason: String = "",
        val resumptionHandle: String? = null,
        val rawJson: JSONObject? = null,
        val messageKeys: List<String> = emptyList(),
        val isBinary: Boolean = false,
        val byteLength: Int = 0,
        val preview: String = "",
        val parseError: String? = null
    )

    fun parseAndLogIncomingFrame(
        rawText: String,
        isBinary: Boolean,
        diagnostics: LiveDiagnostics?
    ): ParsedFrameResult {
        val byteLength = rawText.toByteArray(Charsets.UTF_8).size
        val preview = rawText.take(300)

        diagnostics?.recordIncomingFrame(isBinary, byteLength, rawText)

        var rootJson: JSONObject? = null
        var parseError: String? = null
        var isSetup = false
        var isGoAway = false
        var goAwayReason = ""
        var resumptionHandle: String? = null
        val keys = mutableListOf<String>()

        try {
            val json = JSONObject(rawText)
            rootJson = json
            val iter = json.keys()
            while (iter.hasNext()) {
                val k = iter.next()
                keys.add(k)
                diagnostics?.recordMessage(k)
            }

            if (json.has("setupComplete")) isSetup = true

            if (json.has("sessionResumptionUpdate")) {
                val sru = json.optJSONObject("sessionResumptionUpdate")
                val handle = sru?.optString("handle")
                if (!handle.isNullOrBlank()) resumptionHandle = handle
            }

            if (json.has("goAway")) {
                isGoAway = true
                val ga = json.optJSONObject("goAway")
                goAwayReason = ga?.optString("reason", "Server requested goAway") ?: "Server requested goAway"
            }
        } catch (e: Exception) {
            parseError = e.message ?: "Invalid JSON"
            Log.e(TAG, "Failed to parse incoming frame JSON (${if (isBinary) "BINARY" else "TEXT"}, $byteLength bytes): ${e.message}. Raw: $preview", e)
            diagnostics?.log(TAG, "JSON Parse Error: ${e.message} in frame: ${preview.take(100)}")
        }

        return ParsedFrameResult(
            isSetupComplete = isSetup,
            isGoAway = isGoAway,
            goAwayReason = goAwayReason,
            resumptionHandle = resumptionHandle,
            rawJson = rootJson,
            messageKeys = keys,
            isBinary = isBinary,
            byteLength = byteLength,
            preview = preview,
            parseError = parseError
        )
    }
}
