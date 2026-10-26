package com.example.core.tools

import android.util.Log
import com.example.core.memory.MemoryRepository
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.flow.first
import java.util.Locale

class MemoryTools(
    private val memoryRepository: MemoryRepository,
    private val preferences: AuraPreferences
) {
    companion object {
        private const val TAG = "AuraMemory"
    }

    val manageMemoryTool = object : Tool {
        override val name = "manage_memory"
        override val description = "Saves, updates, retrieves, or deletes facts, preferences, user nicknames/names, and long-term memories."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "action" to mapOf(
                    "type" to "string",
                    "description" to "The operation to perform: 'save', 'get', 'delete', or 'list'."
                ),
                "key" to mapOf(
                    "type" to "string",
                    "description" to "The memory key (e.g. 'owner_name', 'nickname', 'preferred_name', 'favorite_food', 'hobby')."
                ),
                "value" to mapOf(
                    "type" to "string",
                    "description" to "The value or information to save (required for action='save')."
                ),
                "category" to mapOf(
                    "type" to "string",
                    "description" to "Optional category: 'preference', 'alias', 'contact', or 'fact'."
                )
            ),
            "required" to listOf("action", "key")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val action = args["action"]?.toString()?.trim()?.lowercase(Locale.ROOT) ?: "save"
            val key = args["key"]?.toString()?.trim() ?: ""
            val value = args["value"]?.toString()?.trim() ?: ""
            val category = args["category"]?.toString()?.trim() ?: "preference"

            if (key.isBlank() && action != "list") {
                return ToolExecutionResult(false, "Missing required 'key' parameter for memory operation.")
            }

            return try {
                when (action) {
                    "save", "remember", "store" -> {
                        if (value.isBlank()) {
                            return ToolExecutionResult(false, "Cannot save empty value for key '$key'.")
                        }

                        // 1. Save into Room UserFact database
                        memoryRepository.saveFact(key, value, category)

                        // 2. If this is the user's name/nickname, immediately update AuraPreferences ownerName!
                        val lowerKey = key.lowercase(Locale.ROOT)
                        val isNameKey = lowerKey in listOf("owner_name", "preferred_name", "nickname", "name", "call_me", "user_name", "lucky")
                        if (isNameKey || lowerKey.contains("name") || lowerKey.contains("nickname")) {
                            preferences.setOwnerName(value)
                            Log.i(TAG, "Persisted preferred owner name to '$value' across preferences and database.")
                        }

                        ToolExecutionResult(
                            true,
                            "Remembered in persistent memory: $key = $value. This information is saved permanently and will be retained across calls and device restarts."
                        )
                    }

                    "get", "recall", "find" -> {
                        val fact = memoryRepository.findFact(key)
                        if (fact != null) {
                            ToolExecutionResult(true, "Found memory for '$key': $fact")
                        } else {
                            ToolExecutionResult(false, "No memory found for key '$key'.")
                        }
                    }

                    "list" -> {
                        val summary = memoryRepository.buildMemoryContextSummary()
                        ToolExecutionResult(true, if (summary.isNotBlank()) summary else "No facts currently stored in memory.")
                    }

                    "delete", "forget" -> {
                        val fact = memoryRepository.findFact(key)
                        if (fact != null) {
                            val all = memoryRepository.getAllFacts().first()
                            val toDelete = all.firstOrNull { it.key.equals(key, ignoreCase = true) }
                            if (toDelete != null) {
                                memoryRepository.deleteFact(toDelete.id)
                            }
                            ToolExecutionResult(true, "Removed memory for '$key'.")
                        } else {
                            ToolExecutionResult(true, "No memory found for '$key' to delete.")
                        }
                    }

                    else -> ToolExecutionResult(false, "Unknown action '$action'. Use 'save', 'get', 'list', or 'delete'.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error executing manage_memory for key '$key'", e)
                ToolExecutionResult(false, "Failed to manage memory: ${e.message}")
            }
        }
    }

    val saveMemoryTool = object : Tool {
        override val name = "save_memory"
        override val description = "Alias for manage_memory with action 'save'. Persists facts, preferences, user names, or notes into long-term memory."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "key" to mapOf(
                    "type" to "string",
                    "description" to "Memory key (e.g. 'owner_name', 'nickname', 'favorite_color')."
                ),
                "value" to mapOf(
                    "type" to "string",
                    "description" to "The detail, name, or fact to remember permanently."
                ),
                "category" to mapOf(
                    "type" to "string",
                    "description" to "Optional category: 'preference', 'alias', or 'fact'."
                )
            ),
            "required" to listOf("key", "value")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val updatedArgs = args.toMutableMap().apply {
                put("action", "save")
            }
            return manageMemoryTool.execute(updatedArgs)
        }
    }

    fun getAllTools(): List<Tool> = listOf(manageMemoryTool, saveMemoryTool)
}
