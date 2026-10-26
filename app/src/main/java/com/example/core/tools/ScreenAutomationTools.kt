package com.example.core.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.example.core.agent.*
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.TaskExecutionEntity
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraAccessibilityService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

class ScreenAutomationTools(
    private val context: Context,
    private val rootManager: RootManager,
    private val agentPlanner: AgentPlanner,
    private val memoryRepository: MemoryRepository,
    private val preferences: AuraPreferences,
    private val appInventory: AppInventory = AppInventory(context, rootManager)
) {
    private val inputController by lazy { com.example.AuraApplication.instance.inputController }

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
            val nodes = getScreenNodes()
            val summary = ScreenXmlParser.toCompactSummary(nodes)
            return ToolExecutionResult(true, summary.ifBlank { "Screen is empty or unable to read hierarchy." })
        }
    }

    val tapElementTool = object : Tool {
        override val name = "tap_element"
        override val description = "After read_screen, tap one labelled target exactly once. Never retry toggles merely because the screen looks unchanged."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "query" to mapOf("type" to "string", "description" to "The button text, label, or content-desc.")
            ),
            "required" to listOf("query")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val query = args["query"]?.toString()?.lowercase(Locale.ROOT) ?: return ToolExecutionResult(false, "Missing query")
            val nodes = getScreenNodes()

            val match = nodes.find {
                it.text.lowercase(Locale.ROOT).contains(query) ||
                it.contentDescription.lowercase(Locale.ROOT).contains(query) ||
                it.resourceId.lowercase(Locale.ROOT).contains(query)
            }

            return if (match != null) {
                runCatching { com.example.AuraApplication.instance.overlayController.dodgeCaptions(match.centerX, match.centerY) }
                    .onFailure { Log.w("Aura", "Caption dodge failed; continuing tap", it) }
                val targetLabel = match.text.ifEmpty { match.contentDescription.ifEmpty { match.resourceId } }
                val beforeState = "${match.text}|${match.contentDescription}|${match.bounds}"
                val tapRes = inputController.tapResult(match.centerX.toFloat(), match.centerY.toFloat(), "Tap $targetLabel")
                delay(350)
                val nodesAfter = getScreenNodes()
                val targetAfter = nodesAfter.firstOrNull { it.text.equals(match.text, true) && it.contentDescription.equals(match.contentDescription, true) && it.resourceId.equals(match.resourceId, true) }
                val afterState = targetAfter?.let { "${it.text}|${it.contentDescription}|${it.bounds}" } ?: "target_missing"
                val togglePattern = Regex("""(?i)\b(like|liked|unlike|heart|favorite|favourite|star|follow|following|save|saved|bookmark|mute|unmute|pin|react|subscribe|switch|checkbox|radio)\b""")
                val isToggle = togglePattern.containsMatchIn("$targetLabel ${match.className} ${match.resourceId}")
                val changed = targetAfter == null || beforeState != afterState || nodesAfter.size != nodes.size ||
                    nodesAfter.map { it.text + it.contentDescription }.hashCode() != nodes.map { it.text + it.contentDescription }.hashCode()
                if (!tapRes.success && !isToggle) {
                    val clicked = runCatching { AuraAccessibilityService.instance?.findNodeByQuery(query)?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK) == true }
                        .onFailure { Log.w("Aura", "Accessibility click fallback failed", it) }.getOrDefault(false)
                    return ToolExecutionResult(clicked, if (clicked) "Tapped via accessibility fallback; verified=false; state_before=$beforeState; state_after=unknown" else "Tap failed; error_code=GESTURE_CANCELLED; try a more exact element label.")
                }
                if (isToggle && !changed) Log.i("Aura", "Toggle target did not visibly change; not retrying: " + targetLabel)
                ToolExecutionResult(tapRes.success, if (tapRes.success) "Tapped $targetLabel via ${tapRes.method}; verified=$changed; state_before=$beforeState; state_after=$afterState" else "Tap failed; error_code=GESTURE_CANCELLED; try tap_element with another label.")
            } else {
                ToolExecutionResult(false, "Could not find element matching '$query' on current screen")
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
            val x = (args["x"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing x")
            val y = (args["y"] as? Number)?.toFloat() ?: return ToolExecutionResult(false, "Missing y")
            runCatching { com.example.AuraApplication.instance.overlayController.dodgeCaptions(x.toInt(), y.toInt()) }.onFailure { Log.w("Aura", "Caption dodge failed; continuing coordinate tap", it) }
            val res = inputController.tapResult(x, y, "Tap ($x, $y)")
            return ToolExecutionResult(res.success, if (res.success) "Tapped at ($x, $y) via ${res.method}" else "Failed to tap ($x, $y): ${res.error}")
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
            val first = inputController.tapResult(match.centerX.toFloat(), match.centerY.toFloat(), "Double tap item")
            if (!first.success) return ToolExecutionResult(false, "error_code=GESTURE_CANCELLED; double-tap failed.")
            delay(80L)
            val second = inputController.tapResult(match.centerX.toFloat(), match.centerY.toFloat(), "Double tap item")
            delay(350L)
            val after = getScreenNodes().firstOrNull { it.text.equals(match.text, true) || it.contentDescription.equals(match.contentDescription, true) }?.let { "${it.text}|${it.contentDescription}|${it.bounds}" } ?: "target_missing"
            return ToolExecutionResult(second.success, "Double-tap dispatched; verified=" + (before != after) + "; state_before=" + before + "; state_after=" + after)
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
            val maxSteps = (args["max_steps"] as? Number)?.toInt()?.coerceIn(1, 40) ?: 20

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

            try {
                coroutineScope {
                    activeTaskJob = coroutineContext[Job]

                    for (step in 1..maxSteps) {
                        if (isTaskCancelRequested || !isActive) {
                            taskOutcome = "Task cancelled by user"
                            return@coroutineScope
                        }

                        // 1. Wait for UI to settle
                        val nodes = waitForUiIdle()

                        // 2. Sensitive screen check
                        val (isSensitive, reason) = agentPlanner.isSensitiveScreen(nodes)
                        if (isSensitive) {
                            taskOutcome = "Safety Layer: $reason. Requires manual user entry."
                            taskSuccess = false
                            return@coroutineScope
                        }

                        // 3. Loop guard
                        if (agentPlanner.isRepeatingLoop(history)) {
                            Log.w(TAG, "Repeating loop detected. Attempting to scroll or press back...")
                            inputController.pressKey(4, "Back")
                            delay(500)
                        }

                        // 4. Foreground app
                        val fgApp = nodes.firstOrNull { it.packageName.isNotBlank() }?.packageName ?: "Unknown"

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

                        // 6. Execute decided action
                        var executionResultMsg = "Done"
                        var screenSig = nodes.take(4).joinToString { it.text + it.bounds }

                        when (decision.action) {
                            "tap_index" -> {
                                val idx = (decision.params["index"] as? Number)?.toInt() ?: 1
                                val targetNode = nodes.find { it.index == idx }
                                if (targetNode != null) {
                                    inputController.tap(targetNode.centerX.toFloat(), targetNode.centerY.toFloat(), "Tap #${targetNode.index}")
                                    executionResultMsg = "Tapped node #$idx (${targetNode.text})"
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
                                    inputController.tap(targetNode.centerX.toFloat(), targetNode.centerY.toFloat(), "Tap $query")
                                    executionResultMsg = "Tapped '$query' at (${targetNode.centerX}, ${targetNode.centerY})"
                                } else {
                                    executionResultMsg = "Text '$query' not found"
                                }
                            }
                            "tap_xy" -> {
                                val x = (decision.params["x"] as? Number)?.toFloat() ?: 540f
                                val y = (decision.params["y"] as? Number)?.toFloat() ?: 1200f
                                inputController.tap(x, y, "Tap ($x, $y)")
                                executionResultMsg = "Tapped ($x, $y)"
                            }
                            "long_press" -> {
                                val x = (decision.params["x"] as? Number)?.toFloat() ?: 540f
                                val y = (decision.params["y"] as? Number)?.toFloat() ?: 1200f
                                inputController.longPress(x, y, 1000L, "Long press")
                                executionResultMsg = "Long pressed ($x, $y)"
                            }
                            "type_text" -> {
                                val text = decision.params["text"]?.toString() ?: ""
                                val res = typeTextTool.execute(mapOf("text" to text))
                                executionResultMsg = res.output
                            }
                            "scroll" -> {
                                val dir = decision.params["direction"]?.toString() ?: "down"
                                scrollTool.execute(mapOf("direction" to dir))
                                executionResultMsg = "Scrolled $dir"
                            }
                            "swipe" -> {
                                val x1 = (decision.params["x1"] as? Number)?.toFloat() ?: 540f
                                val y1 = (decision.params["y1"] as? Number)?.toFloat() ?: 1800f
                                val x2 = (decision.params["x2"] as? Number)?.toFloat() ?: 540f
                                val y2 = (decision.params["y2"] as? Number)?.toFloat() ?: 600f
                                inputController.swipe(x1, y1, x2, y2, 300L, "Swipe")
                                executionResultMsg = "Swiped ($x1, $y1) to ($x2, $y2)"
                            }
                            "back" -> {
                                inputController.pressKey(4, "Back")
                                executionResultMsg = "Pressed back"
                            }
                            "home" -> {
                                inputController.pressKey(3, "Home")
                                executionResultMsg = "Pressed home"
                            }
                            "recents" -> {
                                inputController.pressKey(187, "Recents")
                                executionResultMsg = "Opened recents"
                            }
                            "open_app" -> {
                                val pkg = decision.params["package_name"]?.toString()
                                    ?: decision.params["app_name"]?.toString() ?: ""
                                val launched = appInventory.openApp(pkg)
                                executionResultMsg = if (launched) "Opened $pkg" else "Could not open $pkg"
                            }
                            "wait" -> {
                                val ms = (decision.params["ms"] as? Number)?.toLong() ?: 1000L
                                delay(ms)
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

                        history.add(
                            AgentActionHistory(
                                stepIndex = step,
                                action = "${decision.action}: ${decision.thought}",
                                target = decision.params.toString(),
                                result = executionResultMsg,
                                screenSignature = screenSig
                            )
                        )

                        delay(600L) // Wait for action to take effect
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
