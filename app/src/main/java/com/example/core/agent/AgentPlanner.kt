package com.example.core.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.example.core.root.RootManager
import com.example.core.tools.AppInventory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit

data class AgentActionStep(
    val thought: String,
    val action: String, // tap_index, tap_text, tap_xy, long_press, type_text, swipe, scroll, back, home, recents, open_app, wait, ask_user, done, fail
    val params: Map<String, Any?>,
    val confidence: Double = 1.0,
    val rawJson: String = ""
)

data class AgentActionHistory(
    val stepIndex: Int,
    val action: String,
    val target: String,
    val result: String,
    val screenSignature: String
)

class AgentPlanner(
    private val context: Context,
    private val rootManager: RootManager,
    private val appInventory: AppInventory,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        private const val TAG = "AuraPlanner"
        private const val BASE_REST_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    /**
     * Determines whether the current screen contains sensitive operations
     * (banking, payments, UPI, passwords, critical deletions).
     */
    fun isSensitiveScreen(nodes: List<ScreenNode>): Pair<Boolean, String> {
        val sensitiveKeywords = listOf(
            "upi pin", "enter pin", "cvv", "otp", "password", "passcode",
            "net banking", "card number", "debit card", "credit card",
            "pay now", "confirm payment", "transfer money", "delete account",
            "factory reset", "erase all data"
        )

        for (node in nodes) {
            val combined = "${node.text} ${node.contentDescription} ${node.resourceId}".lowercase()
            for (kw in sensitiveKeywords) {
                if (combined.contains(kw)) {
                    return Pair(true, "Sensitive screen detected: contains '$kw'")
                }
            }
            if (node.className.contains("password", ignoreCase = true) ||
                node.resourceId.contains("password", ignoreCase = true)
            ) {
                return Pair(true, "Password entry screen detected")
            }
        }
        return Pair(false, "")
    }

    /**
     * Takes an on-device screenshot using root and returns a downscaled JPEG Base64 string.
     */
    suspend fun captureDownscaledScreenshot(): String? = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "planner_screen.png")
        try {
            val cmd = "screencap -p ${tempFile.absolutePath}"
            val res = com.example.AuraApplication.instance.overlayController.hideForCapture {
                rootManager.execute(cmd)
            }
            if (!res.success || !tempFile.exists()) return@withContext null

            val originalBitmap = BitmapFactory.decodeFile(tempFile.absolutePath) ?: return@withContext null
            val maxDimension = 768
            val scale = minOf(1.0f, maxDimension.toFloat() / maxOf(originalBitmap.width, originalBitmap.height))
            val scaledWidth = (originalBitmap.width * scale).toInt()
            val scaledHeight = (originalBitmap.height * scale).toInt()

            val scaledBitmap = Bitmap.createScaledBitmap(originalBitmap, scaledWidth, scaledHeight, true)
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
            val bytes = outputStream.toByteArray()

            scaledBitmap.recycle()
            originalBitmap.recycle()
            tempFile.delete()

            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot capture for planner failed: ${e.message}", e)
            tempFile.delete()
            null
        }
    }

    /**
     * Generates a single autonomous step decision given the user's goal, the UI state, and history.
     */
    suspend fun planNextStep(
        apiKey: String,
        modelName: String,
        goal: String,
        nodes: List<ScreenNode>,
        foregroundApp: String,
        history: List<AgentActionHistory>,
        useScreenshot: Boolean
    ): Result<AgentActionStep> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Gemini API key is required for AgentPlanner"))
        }

        val effectiveModel = if (modelName.isBlank()) "gemini-2.5-flash" else modelName.removePrefix("models/").trim()
        val url = "$BASE_REST_URL/$effectiveModel:generateContent?key=$apiKey"

        val screenshotBase64 = if (useScreenshot) captureDownscaledScreenshot() else null

        // Format compact numbered tree
        val sbTree = StringBuilder()
        nodes.take(50).forEach { node ->
            sbTree.append("[#${node.index}] ")
            if (node.text.isNotBlank()) sbTree.append("\"${node.text}\" ")
            if (node.contentDescription.isNotBlank() && node.contentDescription != node.text) {
                sbTree.append("desc=\"${node.contentDescription}\" ")
            }
            if (node.resourceId.isNotBlank()) sbTree.append("id=${node.resourceId} ")
            if (node.isEditable) sbTree.append("[EDITABLE] ")
            if (node.isClickable) sbTree.append("[CLICKABLE] ")
            if (node.isScrollable) sbTree.append("[SCROLLABLE] ")
            sbTree.append("(${node.centerX}, ${node.centerY})\n")
        }

        // Format recent action history
        val sbHistory = StringBuilder()
        if (history.isEmpty()) {
            sbHistory.append("None (Starting task)\n")
        } else {
            history.takeLast(8).forEach { h ->
                sbHistory.append("Step ${h.stepIndex}: ${h.action} -> ${h.result}\n")
            }
        }

        val promptText = buildString {
            append("You are Aura Autonomous Android Agent. Your goal is to operate the Android smartphone to accomplish the user's request.\n")
            append("Goal: \"$goal\"\n\n")
            append("Current Foreground App: $foregroundApp\n\n")
            append("Recent Action History:\n$sbHistory\n")
            append("Current Screen Interactive Elements:\n${sbTree.ifBlank { "Screen has no indexed elements or is loading." }}\n\n")
            append("Available Actions:\n")
            append("- tap_index: {\"index\": <number>}\n")
            append("- tap_text: {\"query\": \"<label>\"}\n")
            append("- tap_xy: {\"x\": <int>, \"y\": <int>}\n")
            append("- long_press: {\"x\": <int>, \"y\": <int>}\n")
            append("- type_text: {\"text\": \"<string>\"} (types into currently focused or tapped field; handles Hindi/Unicode)\n")
            append("- swipe: {\"x1\": <int>, \"y1\": <int>, \"x2\": <int>, \"y2\": <int>}\n")
            append("- scroll: {\"direction\": \"down\"|\"up\"}\n")
            append("- back: {}\n")
            append("- home: {}\n")
            append("- recents: {}\n")
            append("- open_app: {\"package_name\": \"<pkg>\" or \"app_name\": \"<name>\"}\n")
            append("- wait: {\"ms\": <int>}\n")
            append("- ask_user: {\"question\": \"<short question in Hinglish>\"}\n")
            append("- done: {\"summary\": \"<one-sentence spoken summary in Hinglish of what was done>\"}\n")
            append("- fail: {\"summary\": \"<one-sentence spoken reason in Hinglish why goal cannot be completed>\"}\n\n")
            append("Rules:\n")
            append("1. Choose the single most effective action to advance toward the goal.\n")
            append("2. When goal is completed, output 'done'. If impossible, output 'fail'.\n")
            append("3. Return ONLY a single JSON object matching this schema:\n")
            append("{\n  \"thought\": \"Brief reasoning for this step\",\n  \"action\": \"tap_index|tap_text|tap_xy|type_text|open_app|scroll|back|home|wait|ask_user|done|fail\",\n  \"params\": { ... },\n  \"confidence\": 0.95\n}")
        }

        try {
            val partsArray = JSONArray().apply {
                put(JSONObject().apply { put("text", promptText) })
                if (screenshotBase64 != null) {
                    put(JSONObject().apply {
                        put("inlineData", JSONObject().apply {
                            put("mimeType", "image/jpeg")
                            put("data", screenshotBase64)
                        })
                    })
                }
            }

            val requestJson = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("parts", partsArray)
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("temperature", 0.2)
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val respBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val err = "Planner REST call failed: HTTP ${response.code} $respBody"
                Log.e(TAG, err)
                return@withContext Result.failure(IllegalStateException(err))
            }

            val root = JSONObject(respBody)
            val candidates = root.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")
            val textOutput = parts?.optJSONObject(0)?.optString("text", "") ?: ""

            val parsed = parsePlanJson(textOutput)
            Result.success(parsed)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing planner REST call: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Parses the LLM JSON decision into an AgentActionStep.
     */
    fun parsePlanJson(jsonText: String): AgentActionStep {
        val clean = jsonText.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val obj = try {
            JSONObject(clean)
        } catch (e: Exception) {
            // Fallback object
            JSONObject().apply {
                put("thought", "Proceeding with caution")
                put("action", "wait")
                put("params", JSONObject().apply { put("ms", 1000) })
            }
        }

        val thought = obj.optString("thought", "Executing step")
        val action = obj.optString("action", "wait").lowercase().trim()
        val confidence = obj.optDouble("confidence", 1.0)
        val paramsObj = obj.optJSONObject("params") ?: JSONObject()

        val paramsMap = mutableMapOf<String, Any?>()
        paramsObj.keys().forEach { k ->
            paramsMap[k] = paramsObj.opt(k)
        }

        return AgentActionStep(
            thought = thought,
            action = action,
            params = paramsMap,
            confidence = confidence,
            rawJson = clean
        )
    }

    /**
     * Detects repeated loop patterns in the last 6 actions.
     */
    fun isRepeatingLoop(history: List<AgentActionHistory>): Boolean {
        if (history.size < 4) return false
        val last = history.last()
        val count = history.takeLast(4).count {
            it.action == last.action && it.target == last.target && it.screenSignature == last.screenSignature
        }
        return count >= 3
    }
}
