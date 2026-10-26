package com.example.core.tools

import com.example.core.root.CommandSafetyAssessment
import com.example.core.root.RootManager
import com.example.core.root.SafetyLayer
import com.example.core.settings.AuraPreferences
import kotlinx.coroutines.flow.first

class PowerTools(
    private val rootManager: RootManager,
    private val preferences: AuraPreferences
) {

    val runShellTool = object : Tool {
        override val name = "run_shell"
        override val description = "Runs a raw shell command on the device (as root by default). Subject to safety blocklist."
        override val parametersSchema = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "command" to mapOf("type" to "string", "description" to "The shell command to execute."),
                "as_root" to mapOf("type" to "boolean", "description" to "Whether to run as root (default true).")
            ),
            "required" to listOf("command")
        )

        override suspend fun execute(args: Map<String, Any?>): ToolExecutionResult {
            val command = args["command"]?.toString() ?: return ToolExecutionResult(false, "Missing command")
            val asRoot = args["as_root"] as? Boolean ?: true

            val confirmRisky = preferences.confirmRiskyActions.first()
            val assessment = SafetyLayer.evaluateCommand(command, confirmRisky)

            when (assessment) {
                is CommandSafetyAssessment.CatastrophicBlocked -> {
                    return ToolExecutionResult(false, "Command rejected by safety layer: ${assessment.reason}")
                }
                is CommandSafetyAssessment.RequiresConfirmation -> {
                    return ToolExecutionResult(
                        false,
                        "Command requires confirmation: ${assessment.message}",
                        requiresConfirmation = true,
                        confirmationPrompt = "Confirm execution of: $command"
                    )
                }
                is CommandSafetyAssessment.Safe -> {
                    val res = rootManager.execute(command)
                    return ToolExecutionResult(
                        success = res.success,
                        output = if (res.success) res.stdout.ifEmpty { "Success (no output)" } else "Exit code ${res.exitCode}: ${res.stderr.ifEmpty { res.stdout }}"
                    )
                }
            }
        }
    }

    fun getAllTools(): List<Tool> = listOf(runShellTool)
}
