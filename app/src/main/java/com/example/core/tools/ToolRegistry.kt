package com.example.core.tools

import android.content.Context
import android.util.Log
import com.example.core.agent.AgentLoop
import com.example.core.agent.AgentPlanner
import com.example.core.memory.MemoryRepository
import com.example.core.root.PermissionDoctor
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.util.Locale

data class ActionFeedItem(
    val id: String,
    val toolName: String,
    val description: String,
    val status: ActionStatus
)

enum class ActionStatus {
    RUNNING, SUCCESS, FAILED
}

class ToolRegistry(
    context: Context,
    rootManager: RootManager,
    private val preferences: AuraPreferences,
    private val memoryRepository: MemoryRepository,
    agentLoop: AgentLoop,
    appInventory: AppInventory = AppInventory(context, rootManager),
    val permissionDoctor: PermissionDoctor = PermissionDoctor(context, rootManager),
    onLanguageChange: (suspend (String) -> Unit)? = null,
    onVoiceChange: (suspend (String) -> Unit)? = null,
    onEndCall: (() -> Unit)? = null
) {
    private val toolsMap = mutableMapOf<String, Tool>()

    private val _actionFeed = MutableSharedFlow<ActionFeedItem>(extraBufferCapacity = 64)
    val actionFeed: SharedFlow<ActionFeedItem> = _actionFeed.asSharedFlow()

    val agentPlanner = AgentPlanner(context, rootManager, appInventory)
    val screenTools = ScreenAutomationTools(
        context = context,
        rootManager = rootManager,
        agentPlanner = agentPlanner,
        memoryRepository = memoryRepository,
        preferences = preferences,
        appInventory = appInventory
    )

    init {
        val appTools = AppTools(context, rootManager, appInventory)
        val commTools = CommTools(context, rootManager, preferences)
        val deviceTools = DeviceTools(context, rootManager)
        val mediaTools = MediaUtilityTools(context, rootManager)
        val powerTools = PowerTools(rootManager, preferences)
        val routineTools = RoutineTools(memoryRepository) { name, args ->
            executeTool(name, args)
        }
        val memoryTools = MemoryTools(memoryRepository, preferences)
        val voiceControlTools = VoiceAgentControlTools(
            preferences = preferences,
            permissionDoctor = permissionDoctor,
            onLanguageChangeRequested = onLanguageChange,
            onVoiceChangeRequested = onVoiceChange,
            onEndCallRequested = onEndCall
        )

        val all = appTools.getAllTools() +
                commTools.getAllTools() +
                deviceTools.getAllTools() +
                screenTools.getAllTools() +
                routineTools.getAllTools() +
                mediaTools.getAllTools() +
                powerTools.getAllTools() +
                memoryTools.getAllTools() +
                voiceControlTools.getAllTools()

        all.forEach { tool ->
            require(tool.name.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*"""))) {
                "Tool name '${tool.name}' is invalid"
            }
            toolsMap[tool.name] = tool
        }
    }

    fun getAllTools(): List<Tool> = toolsMap.values.toList()

    fun getTool(name: String): Tool? = toolsMap[name]

    /**
     * Sanitizes and normalizes parameter schema to strict Gemini Function Declaration rules.
     */
    fun sanitizeSchema(raw: Map<String, Any?>?): Map<String, Any>? {
        if (raw == null) return null
        val rawProps = raw["properties"] as? Map<*, *>
        if (rawProps.isNullOrEmpty()) {
            return null // OMIT parameters field completely
        }

        fun normalizeValue(value: Any?): Any? {
            return when (value) {
                null -> null
                is Map<*, *> -> {
                    val result = mutableMapOf<String, Any>()
                    for ((k, v) in value) {
                        if (k == null) continue
                        val keyStr = k.toString()
                        if (keyStr in listOf("additionalProperties", "default", "\$schema")) continue

                        when (keyStr) {
                            "type" -> {
                                if (v is String) {
                                    result["type"] = v.uppercase(Locale.ROOT)
                                }
                            }
                            "properties" -> {
                                if (v is Map<*, *>) {
                                    val cleanProps = mutableMapOf<String, Any>()
                                    for ((pk, pv) in v) {
                                        if (pk != null) {
                                            val nv = normalizeValue(pv)
                                            if (nv != null) {
                                                cleanProps[pk.toString()] = nv
                                            }
                                        }
                                    }
                                    result["properties"] = cleanProps
                                }
                            }
                            "items" -> {
                                val ni = normalizeValue(v)
                                if (ni != null) result["items"] = ni
                            }
                            "description" -> {
                                if (v is String) result["description"] = v
                            }
                            "enum" -> {
                                if (v is List<*>) result["enum"] = v.filterNotNull()
                            }
                            "required" -> {
                                if (v is List<*>) {
                                    result["required"] = v.mapNotNull { it?.toString() }
                                }
                            }
                        }
                    }
                    result
                }
                is List<*> -> value.mapNotNull { normalizeValue(it) }
                else -> value
            }
        }

        val normalized = normalizeValue(raw) as? Map<String, Any> ?: return null
        val cleanMap = normalized.toMutableMap()
        val props = cleanMap["properties"] as? Map<String, Any>
        if (props.isNullOrEmpty()) return null

        val req = cleanMap["required"] as? List<*>
        if (req != null) {
            val validReq = req.mapNotNull { it?.toString() }.filter { props.containsKey(it) }
            if (validReq.isNotEmpty()) {
                cleanMap["required"] = validReq
            } else {
                cleanMap.remove("required")
            }
        }
        return cleanMap
    }

    suspend fun getToolDeclarationsJson(): List<Map<String, Any>> {
        val disabled = preferences.disabledTools.first()
        return toolsMap.values
            .filter { it.name !in disabled }
            .map { tool ->
                val declaration = mutableMapOf<String, Any>(
                    "name" to tool.name,
                    "description" to tool.description
                )
                val sanitizedParams = sanitizeSchema(tool.parametersSchema)
                if (sanitizedParams != null) {
                    declaration["parameters"] = sanitizedParams
                }
                declaration
            }
    }

    suspend fun executeTool(name: String, args: Map<String, Any?>): ToolExecutionResult = coroutineScope {
        val tool = toolsMap[name] ?: return@coroutineScope ToolExecutionResult(false, "Unknown tool: $name")
        val itemId = "${name}_${System.currentTimeMillis()}"

        // Emit starting action feed chip
        _actionFeed.tryEmit(
            ActionFeedItem(
                id = itemId,
                toolName = name,
                description = formatActionDescription(name, args),
                status = ActionStatus.RUNNING
            )
        )

        // Per-tool timeout definition
        val timeoutMs = when {
            name in listOf("read_screen", "tap_element", "type_text", "swipe", "perform_agent_task") -> 90_000L
            name in listOf("run_shell", "reboot", "shutdown") -> 30_000L
            else -> 15_000L
        }

        // Progress emission if tool runs longer than 8s
        val progressJob = launch {
            delay(8000L)
            _actionFeed.tryEmit(
                ActionFeedItem(
                    id = itemId,
                    toolName = name,
                    description = "Still working on ${formatActionDescription(name, args)}...",
                    status = ActionStatus.RUNNING
                )
            )
        }

        val start = System.currentTimeMillis()
        var retried = false

        var result: ToolExecutionResult = try {
            withTimeout(timeoutMs) {
                tool.execute(args)
            }
        } catch (te: TimeoutCancellationException) {
            Log.e("Aura", "Tool '$name' timed out after ${timeoutMs / 1000}s", te)
            ToolExecutionResult(false, "Tool timed out after ${timeoutMs / 1000} seconds")
        } catch (e: Exception) {
            Log.e("Aura", "Tool $name execution failed with exception: ${e.message}", e)
            ToolExecutionResult(false, "Tool execution failed: ${e.message}")
        } finally {
            progressJob.cancel()
        }

        // If execution failed or encountered a permission error, invoke PermissionDoctor
        val isPermissionSuspect = !result.success && (
                result.output.contains("permission", ignoreCase = true) ||
                        result.output.contains("denied", ignoreCase = true) ||
                        result.output.contains("SecurityException", ignoreCase = true) ||
                        result.output.contains("disabled", ignoreCase = true)
                )

        if (!result.success || isPermissionSuspect) {
            val diagnosis = permissionDoctor.diagnoseAndAutoFix(name, args, result.output)
            if (diagnosis.anyFixed) {
                // Auto-fixed! Retry the tool ONCE automatically
                Log.i("Aura", "Permission doctor auto-fixed issues. Retrying tool '$name' once...")
                retried = true
                result = try {
                    withTimeout(timeoutMs) {
                        tool.execute(args)
                    }
                } catch (e: Exception) {
                    Log.e("Aura", "Tool '$name' retry failed: ${e.message}", e)
                    ToolExecutionResult(false, "Retry failed: ${e.message}")
                }
            }

            // Enrich output with structured report
            val structuredJson = permissionDoctor.buildStructuredJson(
                success = result.success,
                reason = result.output,
                diagnosis = diagnosis,
                retried = retried
            )
            result = ToolExecutionResult(result.success, structuredJson)
        }

        val duration = System.currentTimeMillis() - start

        // Emit finished action feed chip
        _actionFeed.tryEmit(
            ActionFeedItem(
                id = itemId,
                toolName = name,
                description = formatActionDescription(name, args),
                status = if (result.success) ActionStatus.SUCCESS else ActionStatus.FAILED
            )
        )

        // Log to persistent memory
        val argsJson = try {
            JSONObject(args).toString()
        } catch (e: Exception) {
            args.toString()
        }

        try {
            memoryRepository.logAction(
                toolName = name,
                argsJson = argsJson,
                output = result.output,
                success = result.success,
                durationMs = duration
            )
        } catch (e: Exception) {
            Log.e("Aura", "Failed to log action to memory repository: ${e.message}", e)
        }

        result
    }

    private fun formatActionDescription(name: String, args: Map<String, Any?>): String {
        return when (name) {
            "open_app" -> "Opening ${args["name_or_package"] ?: "app"}"
            "close_app" -> "Closing ${args["name"] ?: "app"}"
            "set_language" -> "Switching language to ${args["language"] ?: ""}"
            "change_voice" -> "Changing voice to ${args["voice_name"] ?: ""}"
            "end_call_session" -> "Ending call"
            "check_permissions" -> "Checking system permissions"
            "send_whatsapp" -> "Sending WhatsApp to ${args["contact_or_number"] ?: "contact"}"
            "send_sms" -> "Sending SMS to ${args["contact"] ?: "contact"}"
            "make_call" -> "Calling ${args["contact_or_number"] ?: "number"}"
            "set_wifi" -> if (args["on"] == true) "Turning on Wi-Fi" else "Turning off Wi-Fi"
            "set_bluetooth" -> if (args["on"] == true) "Turning on Bluetooth" else "Turning off Bluetooth"
            "set_brightness" -> "Setting brightness to ${args["percent"]}%"
            "set_volume" -> "Setting volume to ${args["percent"]}%"
            "take_screenshot" -> "Capturing screenshot"
            "read_screen" -> "Reading screen content"
            "tap_element" -> "Tapping ${args["query"] ?: "element"}"
            "type_text" -> "Typing text"
            "play_music" -> "Playing music: ${args["query"] ?: ""}"
            "set_alarm" -> "Setting alarm for ${args["hour"]}:${args["minute"]}"
            "run_shell" -> "Running command: ${args["command"] ?: ""}"
            else -> name.replace("_", " ").replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }
}
