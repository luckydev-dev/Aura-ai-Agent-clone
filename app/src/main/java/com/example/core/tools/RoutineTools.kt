package com.example.core.tools

import android.util.Log
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.RoutineEntity
import org.json.JSONArray
import org.json.JSONObject

class RoutineTools(
    private val memoryRepository: MemoryRepository,
    private val toolExecutor: suspend (name: String, args: Map<String, Any?>) -> ToolExecutionResult
) {
    companion object {
        private const val TAG = "AuraRoutines"
    }

    val saveRoutineTool = object : Tool {
        override val name = "save_routine"
        override val description = "Saves or updates an automated routine consisting of multiple tool actions."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Routine name (e.g. 'Good Morning', 'Bedtime', 'Workout')."),
                "description" to mapOf("type" to "string", "description" to "Short description of what the routine does."),
                "trigger_phrase" to mapOf("type" to "string", "description" to "Spoken phrase that triggers this routine."),
                "steps_json" to mapOf("type" to "string", "description" to "JSON array of steps, e.g. [{\"tool\": \"set_wifi\", \"args\": {\"on\": true}}]")
            ),
            "required" to listOf("name", "steps_json")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString()?.trim() ?: return ToolExecutionResult(false, "Missing routine name")
            val stepsJson = args["steps_json"]?.toString()?.trim() ?: return ToolExecutionResult(false, "Missing steps_json")
            val desc = args["description"]?.toString()?.trim() ?: ""
            val trigger = args["trigger_phrase"]?.toString()?.trim() ?: ""

            // Validate that steps_json is a valid JSON array
            try {
                JSONArray(stepsJson)
            } catch (e: Exception) {
                return ToolExecutionResult(false, "Invalid JSON format for steps_json: ${e.message}")
            }

            return try {
                val existing = memoryRepository.findRoutine(name)
                if (existing != null) {
                    val updated = existing.copy(
                        description = desc.ifBlank { existing.description },
                        triggerPhrase = trigger.ifBlank { existing.triggerPhrase },
                        stepsJson = stepsJson
                    )
                    memoryRepository.updateRoutine(updated)
                    ToolExecutionResult(true, "Routine '$name' updated successfully with new steps.")
                } else {
                    val newEntity = RoutineEntity(
                        name = name,
                        description = desc,
                        triggerPhrase = trigger,
                        stepsJson = stepsJson
                    )
                    memoryRepository.saveRoutine(newEntity)
                    ToolExecutionResult(true, "Routine '$name' created and saved successfully.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save routine '$name'", e)
                ToolExecutionResult(false, "Failed to save routine: ${e.message}")
            }
        }
    }

    val runRoutineTool = object : Tool {
        override val name = "run_routine"
        override val description = "Executes all the steps of a saved routine by its name."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Name of the routine to execute.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString()?.trim() ?: return ToolExecutionResult(false, "Missing routine name")

            val routine = try {
                memoryRepository.findRoutine(name)
            } catch (e: Exception) {
                Log.e(TAG, "Error looking up routine '$name'", e)
                null
            } ?: return ToolExecutionResult(false, "Routine '$name' not found.")

            return try {
                val array = JSONArray(routine.stepsJson)
                val results = mutableListOf<String>()
                var allSucceeded = true

                for (i in 0 until array.length()) {
                    val stepObj = array.getJSONObject(i)
                    val toolName = stepObj.optString("tool", "")
                    val toolArgsObj = stepObj.optJSONObject("args") ?: JSONObject()

                    val toolArgs = mutableMapOf<String, Any?>()
                    toolArgsObj.keys().forEach { k ->
                        toolArgs[k] = toolArgsObj.opt(k)
                    }

                    if (toolName.isBlank()) continue

                    val stepResult = toolExecutor(toolName, toolArgs)
                    results.add("Step ${i + 1} ($toolName): ${if (stepResult.success) "Success" else "Failed - ${stepResult.output}"}")
                    if (!stepResult.success) {
                        allSucceeded = false
                    }
                }

                val summary = "Routine '$name' executed (${results.size} steps):\n" + results.joinToString("\n")
                ToolExecutionResult(allSucceeded, summary)
            } catch (e: Exception) {
                Log.e(TAG, "Error executing routine '$name'", e)
                ToolExecutionResult(false, "Routine execution error: ${e.message}")
            }
        }
    }

    val listRoutinesTool = object : Tool {
        override val name = "list_routines"
        override val description = "Lists all saved routines and their trigger phrases."
        override val parametersSchema = mapOf("type" to "object", "properties" to emptyMap<String, Any>())

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            return try {
                val list = memoryRepository.getActiveRoutinesSync()
                if (list.isEmpty()) {
                    ToolExecutionResult(true, "No routines currently configured.")
                } else {
                    val sb = StringBuilder("Saved Routines (${list.size}):\n")
                    list.forEachIndexed { idx, r ->
                        sb.append("${idx + 1}. \"${r.name}\"")
                        if (r.triggerPhrase.isNotBlank()) sb.append(" [Trigger: \"${r.triggerPhrase}\"]")
                        if (r.description.isNotBlank()) sb.append(" - ${r.description}")
                        sb.append("\n")
                    }
                    ToolExecutionResult(true, sb.toString().trim())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error listing routines", e)
                ToolExecutionResult(false, "Failed to list routines: ${e.message}")
            }
        }
    }

    val deleteRoutineTool = object : Tool {
        override val name = "delete_routine"
        override val description = "Deletes a saved routine by its name."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "name" to mapOf("type" to "string", "description" to "Name of the routine to delete.")
            ),
            "required" to listOf("name")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val name = args["name"]?.toString()?.trim() ?: return ToolExecutionResult(false, "Missing routine name")
            return try {
                memoryRepository.deleteRoutineByName(name)
                ToolExecutionResult(true, "Routine '$name' deleted.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed deleting routine '$name'", e)
                ToolExecutionResult(false, "Failed deleting routine: ${e.message}")
            }
        }
    }

    fun getAllTools(): List<Tool> = listOf(
        saveRoutineTool, runRoutineTool, listRoutinesTool, deleteRoutineTool
    )
}
