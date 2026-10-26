package com.example.core.tools

data class ToolExecutionResult(
    val success: Boolean,
    val output: String,
    val data: Map<String, Any?> = emptyMap(),
    val requiresConfirmation: Boolean = false,
    val confirmationPrompt: String? = null
)

interface Tool {
    val name: String
    val description: String
    val parametersSchema: Map<String, Any?>
    suspend fun execute(args: Map<String, Any?>): ToolExecutionResult
}
