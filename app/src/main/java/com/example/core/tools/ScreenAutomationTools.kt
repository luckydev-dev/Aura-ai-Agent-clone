package com.example.core.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.core.agent.*
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.TaskExecutionEntity
import com.example.core.memory.database.TaskStepEntity
import com.example.core.memory.database.TaskStepEntity
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ScreenAutomationTools(
    private val context: Context,
    private val rootManager: RootManager,
    private val agentPlanner: AgentPlanner,
    private val memoryRepository: MemoryRepository,
    private val preferences: AuraPreferences,
    private val appInventory: AppInventory = AppInventory(context, rootManager)
) {
    private val inputController by lazy { com.example.AuraApplication.instance.inputController }
    private val recentDispatches = ConcurrentHashMap<String, Long>()

    private fun errorResult(
        code: String,
        message: String,
        suggestedNext: String,
        extra: Map<String, Any?> = emptyMap()
    ): ToolExecutionResult {
        val payload = linkedMapOf<String, Any?>(
            "success" to false,
            "error_code" to code,
            "message" to message,
            "suggested_next" to suggestedNext
        )
        payload.putAll(extra)
        return ToolExecutionResult(false, JSONObject(payload).toString(), data = payload)
    }

    private fun screenIsAuraFailure(): ToolExecutionResult? {
        val info = runCatching { AuraAccessibilityService.instance?.getForegroundInfo() }
            .onFailure { Log.w("Aura", "Could not inspect foreground app before input", it) }
            .getOrNull() ?: return null
        if (!info.isAuraOnTop) return null
        return errorResult(
            code = "SCREEN_IS_AURA",
            message = "Aura's own screen is on top. It was not returned as the external app screen.",
            suggestedNext = "Press Back to return to the last external app, then call read_screen again.",
            extra = mapOf("lastExternalPackage" to info.lastExternalPackage)
        )
    }

    private fun isRecentlyDispatched(target: String, intent: String): Boolean {
        val key = intent.trim().lowercase(Locale.ROOT) + "|" + target.trim().lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        val previous = recentDispatches[key]
        if (previous != null && now - previous < 1200L) return true
        recentDispatches[key] = now
        if (recentDispatches.size > 256) {
            val cutoff = now - 5000L
            recentDispatches.entries.removeIf { it.value < cutoff }
        }
        return false
    }


    companion object {
        private const val TAG = "AuraScreenTools"
    }

    private var activeTaskJob: Job? = null
    @Volatile
    private var isTaskCancelRequested = false

    fun cancelRunningTask() {
        isTaskCancelRequested = true
        activeTaskJob?.cancel()
        activeTaskJob = null
    }

    private fun screenSignature(nodes: List<ScreenNode>): String {
        val material = nodes.joinToString("|") {
            "${it.packageName}:${it.text}:${it.contentDescription}:${it.resourceId}:${it.bounds}"
        }
        return "count=${nodes.size};hash=${material.hashCode().toString(16)}"
    }

    suspend fun getScreenNodes(): List<ScreenNode> {
        return inputController.withCursorHidden {
            val a11y = AuraAccessibilityService.instance
            if (a11y != null) {
                val a11yNodes = a11y.extractScreenNodes()
                if (a11yNodes.isNotEmpty()) return@withCursorHidden a11yNodes
            }

            // Fallback to uiautomator dump
            val res = rootManager.execute("uiautomator dump /sdcard/ui_aura.xml && cat /sdcard/ui_aura.xml")
            ScreenXmlParser.parse(res.stdout)
        }
    }

    suspend fun waitForUiIdle(maxWaitMs: Long = 1000L, pollIntervalMs: Long = 250L): List<ScreenNode> {
        var lastNodes = getScreenNodes()
        var elapsed = 0L

        while (elapsed < maxWaitMs) {
            delay(pollIntervalMs)
            elapsed += pollIntervalMs
            val currentNodes = getScreenNodes()
            if (currentNodes.size == lastNodes.size &&
                currentNodes.take(5).map { it.text } == lastNodes.take(5).map { it.text }
            ) {
                return currentNodes
            }
            lastNodes = currentNodes
        }
        return lastNodes
    }

    val readScreenTool = object : Tool {
        override val name = "read_screen"
        override val description = "Read the external app screen first; use labelled elements next and coordinates only as a last resort."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            screenIsAuraFailure()?.let { return it }
            val nodes = getScreenNodes()
            val summary = ScreenXmlParser.toCompactSummary(nodes)
            return ToolExecutionResult(true, summary.ifBlank { "Screen is empty or unable to read hierarchy." },
                data = mapOf("source" to if (nodes.size >= 8) "a11y" else "a11y-sparse", "node_count" to nodes.size))
        }
    }

    val tapElementTool = object : Tool {
        override val name = "tap_element"
        override val description = "Read the screen first, then tap one labelled element. Supports query/index, nth match, region, exact match, and intent. Toggle actions are dispatched once and never retried just because the UI looks unchanged."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "Visible text, content description, or resource ID."),
                "index" to mapOf("type" to "integer", "description" to "Exact node index returned by read_screen."),
                "nth" to mapOf("type" to "integer", "description" to "1-based n-th match when labels repeat."),
                "region" to mapOf("type" to "string", "description" to "Optional top, bottom, left, or right region."),
                "exact" to mapOf("type" to "boolean", "description" to "If true, require an exact label match."),
                "intent" to mapOf("type" to "string", "description" to "Intent such as like, follow, save, open, or tap. Same target+intent is debounced for 1.2 seconds.")
            )
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            screenIsAuraFailure()?.let { return it }
            val query = args["query"]?.toString()?.trim().orEmpty()
            val index = (args["index"] as? Number)?.toInt()
            val nth = ((args["nth"] as? Number)?.toInt() ?: 1).coerceAtLeast(1)
            val region = args["region"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val exact = args["exact"] as? Boolean ?: false
            val intent = args["intent"]?.toString()?.ifBlank { "tap" } ?: "tap"
            if (index == null && query.isBlank()) {
                return errorResult("TARGET_NOT_FOUND", "Provide a visible query or node index.", "Call read_screen and choose a label or index.")
            }

            val nodes = getScreenNodes()
            val display = inputController.getDisplayMetrics()
            val matched = if (index != null) {
                nodes.filter { it.index == index }
            } else {
                nodes.filter { node ->
                    val labels = listOf(node.text, node.contentDescription, node.resourceId)
                    val labelMatch = labels.any { label ->
                        if (label.isBlank()) false
                        else if (exact) label.equals(query, ignoreCase = true)
                        else label.contains(query, ignoreCase = true)
                    }
                    val regionMatch = when (region) {
                        "top" -> node.centerY < display.y / 3
                        "bottom" -> node.centerY >= display.y * 2 / 3
                        "left" -> node.centerX < display.x / 2
                        "right" -> node.centerX >= display.x / 2
                        "", "all" -> true
                        else -> false
                    }
                    labelMatch && regionMatch
                }
            }
            val match = matched.getOrNull(nth - 1)
                ?: return errorResult(
                    "TARGET_NOT_FOUND",
                    "No screen element matched the supplied selector.",
                    "Call read_screen, adjust query/index/region, and retry."
                )

            val targetLabel = match.text.ifEmpty { match.contentDescription.ifEmpty { match.resourceId } }
            val targetKey = "${match.packageName}|${match.resourceId}|${match.text}|${match.contentDescription}|${match.centerX},${match.centerY}"
            if (isRecentlyDispatched(targetKey, intent)) {
                return errorResult(
                    "DUPLICATE_ACTION",
                    "The same target and intent was dispatched within the last 1.2 seconds; a second dispatch was blocked.",
                    "Read the screen once to verify the current state before trying again.",
                    extra = mapOf("target" to targetLabel, "intent" to intent)
                )
            }

            runCatching { com.example.AuraApplication.instance.overlayController.dodgeCaptions(match.centerX, match.centerY) }
                .onFailure { Log.w("Aura", "Caption dodge failed; continuing tap", it) }

            val beforeState = "${match.text}|${match.contentDescription}|${match.bounds}|selected=${match.isSelected}|checked=${match.isChecked}|enabled=${match.isEnabled}"
            val tapRes = inputController.tapResult(match.centerX.toFloat(), match.centerY.toFloat(), "Tap $targetLabel")
            delay(350L)
            val nodesAfter = getScreenNodes()
            val targetAfter = nodesAfter.firstOrNull {
                it.resourceId.equals(match.resourceId, true) &&
                    it.contentDescription.equals(match.contentDescription, true) &&
                    it.text.equals(match.text, true)
            }
            val afterState = targetAfter?.let {
                "${it.text}|${it.contentDescription}|${it.bounds}|selected=${it.isSelected}|checked=${it.isChecked}|enabled=${it.isEnabled}"
            } ?: "target_missing"
            val togglePattern = Regex("""(?i)\b(like|liked|unlike|heart|favorite|favourite|star|follow|following|save|saved|bookmark|mute|unmute|pin|react|subscribe|notification bell|switch|checkbox|radio)\b""")
            val isToggle = togglePattern.containsMatchIn("$targetLabel ${match.className} ${match.resourceId}")
            val changed = targetAfter == null ||
                beforeState != afterState ||
                nodesAfter.size != nodes.size ||
                nodesAfter.map { it.text + it.contentDescription }.hashCode() != nodes.map { it.text + it.contentDescription }.hashCode()

            if (!tapRes.success && !isToggle) {
                val clicked = runCatching {
                    AuraAccessibilityService.instance?.findNodeByQuery(query)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true
                }.onFailure { Log.w("Aura", "Accessibility click fallback failed", it) }.getOrDefault(false)
                return if (clicked) {
                    ToolExecutionResult(true, "Tapped via accessibility fallback; verified=false; state_before=$beforeState; state_after=unknown",
                        data = mapOf("verified" to false, "method" to "accessibility", "state_before" to beforeState, "state_after" to "unknown"))
                } else {
                    errorResult("GESTURE_CANCELLED", "Android did not dispatch the tap.", "Try a more exact element, index, or tap_xy after checking the screen.",
                        extra = mapOf("state_before" to beforeState, "state_after" to afterState))
                }
            }
            if (isToggle && !changed) Log.i("Aura", "Toggle target did not visibly change; not retrying: " + targetLabel)
            return if (tapRes.success) {
                val msg = if (isToggle && !changed) "Tapped '$targetLabel'; state unchanged (toggle), not retrying."
                    else "Tapped '$targetLabel' via ${tapRes.method}; verified=$changed."
                ToolExecutionResult(true, msg, data = mapOf("verified" to changed, "method" to tapRes.method.name, "state_before" to beforeState, "state_after" to afterState, "intent" to intent))
            } else {
                errorResult("GESTURE_CANCELLED", "The tap was not dispatched successfully.", "Read the screen and try a different interaction method.",
                    extra = mapOf("state_before" to beforeState, "state_after" to afterState, "intent" to intent))
            }
        }
    }

    val tapXyTool = object : Tool {
        override val name = "tap_xy"
        override val description = "Last-resort coordinate tap only when a labelled element cannot be located."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate in pixels."),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate in pixels.")
            ),
            "required" to listOf("x", "y")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            screenIsAuraFailure()?.let { return it }
            val x = (args["x"] as? Number)?.toFloat() ?: return errorResult("TARGET_NOT_FOUND", "Missing x coordinate.", "Provide x and y from screen_size.")
            val y = (args["y"] as? Number)?.toFloat() ?: return errorResult("TARGET_NOT_FOUND", "Missing y coordinate.", "Provide x and y from screen_size.")
            val intent = args["intent"]?.toString()?.ifBlank { "tap" } ?: "tap"
            if (isRecentlyDispatched("xy=${x.toInt()},${y.toInt()}", intent)) {
                return errorResult("DUPLICATE_ACTION", "The same coordinate tap was recently dispatched.", "Read the screen once before trying again.")
            }
            runCatching { com.example.AuraApplication.instance.overlayController.dodgeCaptions(x.toInt(), y.toInt()) }.onFailure { Log.w("Aura", "Caption dodge failed; continuing coordinate tap", it) }
            val res = inputController.tapResult(x, y, "Tap ($x, $y)")
            return if (res.success) ToolExecutionResult(true, "Tapped at ($x, $y) via ${res.method}", data = mapOf("verified" to false, "method" to res.method.name))
                else errorResult("GESTURE_CANCELLED", "The coordinate tap was not dispatched.", "Read the screen and try another location.")
        }
    }

    val longPressTool = object : Tool {
        override val name = "long_press"
        override val description = "Last-resort coordinate long press. Prefer long_press_element when the screen exposes a label."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "x" to mapOf("type" to "integer", "description" to "X coordinate."),
                "y" to mapOf("type" to "integer", "description" to "Y coordinate."),
                "duration_ms" to mapOf("type" to "integer", "description" to "Duration in ms (default 1000).")
            ),
            "required" to listOf("x", "y")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val x = (args["x"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing x")
            val y = (args["y"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing y")
            val duration = (args["duration_ms"] as? Number)?.toLong() ?: 1000L
            val res = inputController.longPress(x, y, duration, "Long press")
            return ToolExecutionResult(res, "Long pressed at ($x, $y)")
        }
    }

    val longPressElementTool = object : Tool {
        override val name = "long_press_element"
        override val description = "Long-press one visible item using its label or node index. Read the screen first."
        override val parametersSchema = mapOf("type" to "object", "properties" to mapOf("query" to mapOf("type" to "string", "description" to "Visible label or content description."), "index" to mapOf("type" to "integer", "description" to "Optional node index."), "duration_ms" to mapOf("type" to "integer", "description" to "Duration milliseconds; default 1000.")))
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            screenIsAuraFailure()?.let { return it }
            val nodes = getScreenNodes()
            val query = args["query"]?.toString()?.trim().orEmpty()
            val index = (args["index"] as? Number)?.toInt()
            val match = if (index != null) nodes.firstOrNull { it.index == index } else nodes.firstOrNull { query.isNotBlank() && (it.text.equals(query, true) || it.contentDescription.equals(query, true)) } ?: nodes.firstOrNull { query.isNotBlank() && (it.text.contains(query, true) || it.contentDescription.contains(query, true)) }
            if (match == null) return ToolExecutionResult(false, "error_code=TARGET_NOT_FOUND; read_screen and retry with a visible label.")
            val duration = (args["duration_ms"] as? Number)?.toLong()?.coerceIn(500L, 5000L) ?: 1000L
            runCatching { com.example.AuraApplication.instance.overlayController.dodgeCaptions(match.centerX, match.centerY) }.onFailure { Log.w("Aura", "Caption dodge failed; continuing long press", it) }
            val ok = inputController.longPress(match.centerX.toFloat(), match.centerY.toFloat(), duration, "Long press item")
            return ToolExecutionResult(ok, if (ok) "Long-pressed " + match.text.ifBlank { match.contentDescription } + " for " + duration + "ms" else "error_code=GESTURE_CANCELLED; long press was not accepted.")
        }
    }

    val doubleTapElementTool = object : Tool {
        override val name = "double_tap_element"
        override val description = "Use only when double-tap is the intended app gesture; check toggle state first."
        override val parametersSchema = mapOf("type" to "object", "properties" to mapOf("query" to mapOf("type" to "string", "description" to "Visible label or content description.")), "required" to listOf("query"))
        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString()?.trim().orEmpty()
            if (query.isBlank()) return ToolExecutionResult(false, "error_code=TARGET_NOT_FOUND; query missing.")
            val nodes = getScreenNodes()
            val match = nodes.firstOrNull { it.text.contains(query, true) || it.contentDescription.contains(query, true) } ?: return ToolExecutionResult(false, "error_code=TARGET_NOT_FOUND; target not found.")
            val before = "${match.text}|${match.contentDescription}|${match.bounds}"
            runCatching { com.example.AuraApplication.instance.overlayController.dodgeCaptions(match.centerX, match.centerY) }.onFailure { Log.w("Aura", "Caption dodge failed; continuing double-tap", it) }
            val targetKey = "${match.packageName}|${match.resourceId}|${match.text}|${match.contentDescription}|${match.centerX},${match.centerY}"
            if (isRecentlyDispatched(targetKey, "double_tap")) return errorResult("DUPLICATE_ACTION", "A double-tap was already dispatched for this item recently.", "Read the screen to confirm whether the item is already liked.")
            val dispatched = inputController.doubleTap(match.centerX.toFloat(), match.centerY.toFloat(), "Double tap item")
            delay(350L)
            val after = getScreenNodes().firstOrNull { it.text.equals(match.text, true) || it.contentDescription.equals(match.contentDescription, true) }
                ?.let { "${it.text}|${it.contentDescription}|${it.bounds}" } ?: "target_missing"
            return if (dispatched) ToolExecutionResult(true, "Double-tap dispatched once; verified=${before != after}; state_before=$before; state_after=$after",
                data = mapOf("verified" to (before != after), "state_before" to before, "state_after" to after, "method" to "double_tap"))
            else errorResult("GESTURE_CANCELLED", "Android did not dispatch the double-tap.", "Check the screen and try a labelled tap if appropriate.")
        }
    }

    val swipeTool = object : Tool {
        override val name = "swipe"
        override val description = "Swipes across the screen in a specified direction ('up', 'down', 'left', 'right') or custom coords."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "direction" to mapOf("type" to "string", "description" to "'up', 'down', 'left', or 'right'.")
            ),
            "required" to listOf("direction")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val dir = args["direction"]?.toString()?.lowercase(Locale.ROOT) ?: "up"
            val res = inputController.swipeDirection(dir, "Swipe $dir")
            return ToolExecutionResult(res, "Swiped $dir")
        }
    }

    val scrollTool = object : Tool {
        override val name = "scroll"
        override val description = "Scrolls the screen down or up."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("direction" to mapOf("type" to "string", "description" to "'down' or 'up' (default 'down')."))
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val dir = args["direction"]?.toString()?.lowercase(Locale.ROOT) ?: "down"
            val res = inputController.scroll(dir, 0.5f, "Scroll $dir")
            return ToolExecutionResult(res, "Scrolled $dir")
        }
    }

    val typeTextTool = object : Tool {
        override val name = "type_text"
        override val description = "Types text into the currently focused input field. Handles Unicode and Hindi."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("text" to mapOf("type" to "string", "description" to "Text to type.")),
            "required" to listOf("text")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val text = args["text"]?.toString() ?: return ToolExecutionResult(false, "Missing text")
            val res = inputController.typeText(text, "Type text")
            return ToolExecutionResult(res, if (res) "Typed: $text" else "Failed to type: $text")
        }
    }

    val pressKeyTool = object : Tool {
        override val name = "press_key"
        override val description = "Presses a key on device: 'enter', 'back', 'home', 'recents', 'tab', 'delete', 'power'."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("key" to mapOf("type" to "string", "description" to "Key name or keycode.")),
            "required" to listOf("key")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val key = args["key"]?.toString()?.lowercase(Locale.ROOT) ?: "enter"
            val code = when (key) {
                "enter" -> 66
                "back" -> 4
                "home" -> 3
                "recents", "app_switch" -> 187
                "tab" -> 61
                "delete", "backspace" -> 67
                "power" -> 26
                "volume_up" -> 24
                "volume_down" -> 25
                else -> key.toIntOrNull() ?: 66
            }
            val res = inputController.pressKey(code, "Key $key")
            return ToolExecutionResult(res, "Pressed $key")
        }
    }

    val waitTool = object : Tool {
        override val name = "wait"
        override val description = "Waits for a duration in milliseconds to allow UI animation."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("ms" to mapOf("type" to "integer", "description" to "Milliseconds to wait (e.g. 1000).")),
            "required" to listOf("ms")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val ms = (args["ms"] as? Number)?.toLong()?.coerceIn(100, 10000) ?: 1000
            delay(ms)
            return ToolExecutionResult(true, "Waited ${ms}ms")
        }
    }

    val findOnScreenTool = object : Tool {
        override val name = "find_on_screen"
        override val description = "Searches for text on screen and returns matched elements and coordinates."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf("query" to mapOf("type" to "string", "description" to "Text to find.")),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString()?.lowercase(Locale.ROOT) ?: return ToolExecutionResult(false, "Missing query")
            val nodes = getScreenNodes()
            val matches = nodes.filter {
                it.text.lowercase(Locale.ROOT).contains(query) ||
                it.contentDescription.lowercase(Locale.ROOT).contains(query)
            }

            return if (matches.isNotEmpty()) {
                val formatted = matches.joinToString("\n") {
                    "Found \"${it.text.ifEmpty { it.contentDescription }}\" at (${it.centerX}, ${it.centerY})"
                }
                ToolExecutionResult(true, formatted)
            } else {
                ToolExecutionResult(true, "No match for '$query' on screen")
            }
        }
    }

    private fun confirmationPromptForGoal(goal: String): String? {
        val phrases = listOf(
            "transfer money", "send money", "send payment", "make a payment", "confirm payment",
            "pay now", "submit payment", "delete account", "factory reset", "erase all data",
            "wipe device", "place order", "purchase", "buy now"
        )
        val text = goal.lowercase(Locale.ROOT)
        val hasIrreversibleIntent = phrases.any { phrase ->
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(phrase) + "(?![\\p{L}\\p{N}])").containsMatchIn(text)
        }
        if (!hasIrreversibleIntent) return null
        return "This task may perform an irreversible financial or account action. Confirm karun? Say yes explicitly before I proceed."
    }

    private fun confirmationRequired(prompt: String): ToolExecutionResult {
        val payload = mapOf(
            "success" to false,
            "error_code" to "CONFIRMATION_REQUIRED",
            "message" to prompt,
            "suggested_next" to "Ask the user for an explicit yes before starting this task again."
        )
        return ToolExecutionResult(
            success = false,
            output = JSONObject(payload).toString(),
            data = payload,
            requiresConfirmation = true,
            confirmationPrompt = prompt
        )
    }
    val runTaskTool = object : Tool {
        override val name = "run_task"
        override val description = "Executes an autonomous multi-step Android phone task using Gemini observation and planning."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "goal" to mapOf("type" to "string", "description" to "The user goal to achieve on the phone."),
                "max_steps" to mapOf("type" to "integer", "description" to "Maximum planning steps (default 20).")
            ),
            "required" to listOf("goal")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val goal = args["goal"]?.toString() ?: return ToolExecutionResult(false, "Missing goal")
            confirmationPromptForGoal(goal)?.let { prompt -> return confirmationRequired(prompt) }
            val maxSteps = (args["max_steps"] as? Number)?.toInt()?.coerceIn(1, 80) ?: 40

            val apiKey = preferences.apiKey.first()
            val plannerModel = preferences.plannerModelName.first()
            val useScreenshot = preferences.plannerUseScreenshots.first()

            isTaskCancelRequested = false
            val taskId = UUID.randomUUID().toString()
            val startTime = System.currentTimeMillis()
            val history = mutableListOf<AgentActionHistory>()
            val stepsJsonArray = JSONArray()

            var taskOutcome = "Completed"
            var taskSuccess = false
            var confirmationPrompt: String? = null
            val taskDeadline = startTime + 300_000L

            try {
                coroutineScope {
                    activeTaskJob = coroutineContext[Job]

                    for (step in 1..maxSteps) {
                        if (isTaskCancelRequested || !isActive) {
                            taskOutcome = "Task cancelled by user"
                            return@coroutineScope
                        }

                        if (System.currentTimeMillis() >= taskDeadline) {
                            taskOutcome = "Task reached the 5-minute time limit."
                            taskSuccess = false
                            return@coroutineScope
                        }

                        // Observe the actual external application, never Aura's own activity.
                        var nodes = waitForUiIdle(maxWaitMs = 2500L, pollIntervalMs = 250L)
                        var foregroundInfo = AuraAccessibilityService.instance?.getForegroundInfo()
                        if (foregroundInfo?.isAuraOnTop == true) {
                            Log.w(TAG, "Aura is on top; returning to the last external app before observing")
                            inputController.pressKey(4, "Back to external app")
                            delay(400L)
                            foregroundInfo = AuraAccessibilityService.instance?.getForegroundInfo()
                            if (foregroundInfo?.isAuraOnTop == true && foregroundInfo.lastExternalPackage.isNotBlank()) {
                                appInventory.openApp(foregroundInfo.lastExternalPackage)
                                delay(600L)
                                foregroundInfo = AuraAccessibilityService.instance?.getForegroundInfo()
                            }
                            if (foregroundInfo?.isAuraOnTop == true) {
                                taskOutcome = "SCREEN_IS_AURA: Aura is still on top; last external app is " + foregroundInfo.lastExternalPackage
                                taskSuccess = false
                                return@coroutineScope
                            }
                            nodes = waitForUiIdle(maxWaitMs = 2500L, pollIntervalMs = 250L)
                        }

                        // Do not abort merely because a login or settings screen contains an OTP/password label.
                        // Destructive confirmation is evaluated against the planned action below.
                        // Loop guard
                        if (agentPlanner.isRepeatingLoop(history)) {
                            Log.w(TAG, "Repeating loop detected. Trying an alternate recovery step")
                            val scrollResult = inputController.swipeDirection("down", "Task recovery scroll")
                            if (!scrollResult) inputController.pressKey(4, "Back")
                            delay(500L)
                            nodes = waitForUiIdle(maxWaitMs = 2500L, pollIntervalMs = 250L)
                            foregroundInfo = AuraAccessibilityService.instance?.getForegroundInfo()
                        }

                        // Foreground app always comes from the same window-aware reader.
                        val fgApp = foregroundInfo?.foregroundPackage?.takeIf { it.isNotBlank() }
                            ?: foregroundInfo?.lastExternalPackage?.takeIf { it.isNotBlank() }
                            ?: nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName
                            ?: "Unknown"

                        // 5. Plan next step via Gemini REST
                        val planResult = agentPlanner.planNextStep(
                            apiKey = apiKey,
                            modelName = plannerModel,
                            goal = goal,
                            nodes = nodes,
                            foregroundApp = fgApp,
                            history = history,
                            useScreenshot = useScreenshot
                        )

                        if (planResult.isFailure) {
                            taskOutcome = "Planning error: ${planResult.exceptionOrNull()?.message}"
                            taskSuccess = false
                            return@coroutineScope
                        }

                        val decision = planResult.getOrThrow()
                        Log.i(TAG, "Planner Step $step: ${decision.action} - ${decision.thought}")

                        val stepLogObj = JSONObject().apply {
                            put("step", step)
                            put("thought", decision.thought)
                            put("action", decision.action)
                            put("params", JSONObject(decision.params))
                        }
                        stepsJsonArray.put(stepLogObj)

                        // 6. Execute decided action and track whether it was actually dispatched.
                        var executionResultMsg = "Done"
                        val screenSig = screenSignature(nodes)
                        var actionDispatched = false
                        var stepVerified = false

                        when (decision.action) {
                            "tap_index" -> {
                                val idx = (decision.params["index"] as? Number)?.toInt() ?: 1
                                val targetNode = nodes.find { it.index == idx }
                                if (targetNode != null) {
                                    actionDispatched = inputController.tap(targetNode.centerX.toFloat(), targetNode.centerY.toFloat(), "Tap #${targetNode.index}")
                                    executionResultMsg = if (actionDispatched) "Tapped node #$idx (${targetNode.text})" else "Tap dispatch failed for node #$idx"
                                } else {
                                    executionResultMsg = "Node #$idx not found"
                                }
                            }
                            "tap_text" -> {
                                val query = decision.params["query"]?.toString() ?: ""
                                val targetNode = nodes.find {
                                    it.text.contains(query, ignoreCase = true) ||
                                    it.contentDescription.contains(query, ignoreCase = true)
                                }
                                if (targetNode != null) {
                                    actionDispatched = inputController.tap(targetNode.centerX.toFloat(), targetNode.centerY.toFloat(), "Tap $query")
                                    executionResultMsg = if (actionDispatched) "Tapped '$query' at (${targetNode.centerX}, ${targetNode.centerY})" else "Tap dispatch failed for '$query'"
                                } else {
                                    executionResultMsg = "Text '$query' not found"
                                }
                            }
                            "tap_xy" -> {
                                val x = (decision.params["x"] as? Number)?.toFloat() ?: 540f
                                val y = (decision.params["y"] as? Number)?.toFloat() ?: 1200f
                                actionDispatched = inputController.tap(x, y, "Tap ($x, $y)")
                                executionResultMsg = if (actionDispatched) "Tapped ($x, $y)" else "Tap dispatch failed at ($x, $y)"
                            }
                            "long_press" -> {
                                val x = (decision.params["x"] as? Number)?.toFloat() ?: 540f
                                val y = (decision.params["y"] as? Number)?.toFloat() ?: 1200f
                                actionDispatched = inputController.longPress(x, y, 1000L, "Long press")
                                executionResultMsg = if (actionDispatched) "Long pressed ($x, $y)" else "Long press dispatch failed at ($x, $y)"
                            }
                            "type_text" -> {
                                val text = decision.params["text"]?.toString() ?: ""
                                val res = typeTextTool.execute(mapOf("text" to text))
                                actionDispatched = res.success
                                executionResultMsg = res.output
                            }
                            "scroll" -> {
                                val dir = decision.params["direction"]?.toString() ?: "down"
                                val scrollResult = scrollTool.execute(mapOf("direction" to dir))
                                actionDispatched = scrollResult.success
                                executionResultMsg = scrollResult.output
                            }
                            "swipe" -> {
                                val x1 = (decision.params["x1"] as? Number)?.toFloat() ?: 540f
                                val y1 = (decision.params["y1"] as? Number)?.toFloat() ?: 1800f
                                val x2 = (decision.params["x2"] as? Number)?.toFloat() ?: 540f
                                val y2 = (decision.params["y2"] as? Number)?.toFloat() ?: 600f
                                actionDispatched = inputController.swipe(x1, y1, x2, y2, 300L, "Swipe")
                                executionResultMsg = if (actionDispatched) "Swiped ($x1, $y1) to ($x2, $y2)" else "Swipe dispatch failed"
                            }
                            "back" -> {
                                actionDispatched = inputController.pressKey(4, "Back")
                                executionResultMsg = if (actionDispatched) "Pressed back" else "Back key dispatch failed"
                            }
                            "home" -> {
                                actionDispatched = inputController.pressKey(3, "Home")
                                executionResultMsg = if (actionDispatched) "Pressed home" else "Home key dispatch failed"
                            }
                            "recents" -> {
                                actionDispatched = inputController.pressKey(187, "Recents")
                                executionResultMsg = if (actionDispatched) "Opened recents" else "Recents key dispatch failed"
                            }
                            "open_app" -> {
                                val pkg = decision.params["package_name"]?.toString()
                                    ?: decision.params["app_name"]?.toString() ?: ""
                                val launched = appInventory.openApp(pkg)
                                actionDispatched = launched
                                executionResultMsg = if (launched) "Opened $pkg" else "Could not open $pkg"
                            }
                            "wait" -> {
                                val ms = (decision.params["ms"] as? Number)?.toLong() ?: 1000L
                                delay(ms)
                                actionDispatched = true
                                executionResultMsg = "Waited ${ms}ms"
                            }
                            "ask_user" -> {
                                val q = decision.params["question"]?.toString() ?: "Need input"
                                taskOutcome = q
                                taskSuccess = true
                                return@coroutineScope
                            }
                            "done" -> {
                                val summary = decision.params["summary"]?.toString() ?: "Task complete."
                                taskOutcome = summary
                                taskSuccess = true
                                return@coroutineScope
                            }
                            "fail" -> {
                                val summary = decision.params["summary"]?.toString() ?: "Cannot complete task."
                                taskOutcome = summary
                                taskSuccess = false
                                return@coroutineScope
                            }
                            else -> {
                                executionResultMsg = "Unrecognized action: ${decision.action}"
                            }
                        }

                        delay(350L)
                        val nodesAfter = waitForUiIdle(maxWaitMs = 2500L, pollIntervalMs = 250L)
                        val signatureAfter = screenSignature(nodesAfter)
                        stepVerified = actionDispatched && (
                            signatureAfter != screenSig ||
                            decision.action in listOf("wait", "back", "home", "recents", "open_app")
                        )
                        if (decision.action in listOf("tap_index", "tap_text", "tap_xy") && signatureAfter == screenSig) {
                            stepVerified = false
                        }
                        stepLogObj.put("result", executionResultMsg)
                        stepLogObj.put("verified", stepVerified)
                        stepLogObj.put("screen_signature_after", signatureAfter)
                        try {
                            memoryRepository.logTaskStep(
                                TaskStepEntity(
                                    taskId = taskId,
                                    step = step,
                                    thought = decision.thought,
                                    action = decision.action,
                                    paramsJson = JSONObject(decision.params).toString(),
                                    result = executionResultMsg,
                                    verified = stepVerified,
                                    screenSignature = signatureAfter
                                )
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to persist task step $step", e)
                        }
                        history.add(
                            AgentActionHistory(
                                stepIndex = step,
                                action = "${decision.action}: ${decision.thought}",
                                target = decision.params.toString(),
                                result = executionResultMsg + if (stepVerified) " [verified]" else " [unverified]",
                                screenSignature = signatureAfter
                            )
                        )

                        delay(250L) // Give the UI a short settling period before the next observation
                    }

                    taskOutcome = "Task reached maximum step limit ($maxSteps) without full completion."
                    taskSuccess = false
                }
            } catch (ce: CancellationException) {
                taskOutcome = "Task was cancelled."
                taskSuccess = false
            } catch (e: Exception) {
                Log.e(TAG, "Autonomous task execution error", e)
                taskOutcome = "Error executing task: ${e.message}"
                taskSuccess = false
            } finally {
                activeTaskJob = null
                val duration = System.currentTimeMillis() - startTime

                try {
                    memoryRepository.logTask(
                        TaskExecutionEntity(
                            taskId = taskId,
                            goal = goal,
                            stepsJson = stepsJsonArray.toString(),
                            outcome = taskOutcome,
                            success = taskSuccess,
                            durationMs = duration
                        )
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed logging task execution to Room", e)
                }
            }

            return ToolExecutionResult(taskSuccess, taskOutcome)
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        readScreenTool, tapElementTool, tapXyTool, longPressTool, longPressElementTool, doubleTapElementTool, swipeTool,
        scrollTool, typeTextTool, pressKeyTool, waitTool, findOnScreenTool, runTaskTool
    )
}
