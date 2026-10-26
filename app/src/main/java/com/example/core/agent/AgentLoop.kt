package com.example.core.agent

import com.example.core.root.RootManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentLoop(
    private val rootManager: RootManager
) {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _currentGoal = MutableStateFlow("")
    val currentGoal: StateFlow<String> = _currentGoal.asStateFlow()

    private val _currentStep = MutableStateFlow(0)
    val currentStep: StateFlow<Int> = _currentStep.asStateFlow()

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private var cancelRequested = false

    fun stop() {
        cancelRequested = true
        _isRunning.value = false
        _statusText.value = "Stopped by user"
    }

    suspend fun executeTask(
        goal: String,
        maxSteps: Int = 25,
        stepExecutor: suspend (step: Int, screenSummary: String) -> AgentStepDecision
    ): AgentTaskResult {
        if (_isRunning.value) return AgentTaskResult(false, "Another task is currently running")

        _isRunning.value = true
        _currentGoal.value = goal
        _currentStep.value = 0
        cancelRequested = false

        var lastScreenSignature = ""
        var duplicateScreenCount = 0

        try {
            for (step in 1..maxSteps) {
                if (cancelRequested) {
                    return AgentTaskResult(false, "Task was stopped by user")
                }
                _currentStep.value = step
                _statusText.value = "Step $step: Analyzing screen..."

                // 1. Observe screen via uiautomator dump
                val dumpRes = rootManager.execute("uiautomator dump /sdcard/ui_aura.xml && cat /sdcard/ui_aura.xml")
                val nodes = ScreenXmlParser.parse(dumpRes.stdout)
                val summary = ScreenXmlParser.toCompactSummary(nodes)

                // Detect infinite loops
                val screenSignature = nodes.take(5).joinToString { it.text + it.bounds }
                if (screenSignature.isNotBlank() && screenSignature == lastScreenSignature) {
                    duplicateScreenCount++
                    if (duplicateScreenCount >= 4) {
                        return AgentTaskResult(false, "Screen state did not change after multiple attempts. Aborting.")
                    }
                } else {
                    duplicateScreenCount = 0
                    lastScreenSignature = screenSignature
                }

                // 2. Decide next action
                _statusText.value = "Step $step: Deciding next action..."
                val decision = stepExecutor(step, summary)

                when (decision) {
                    is AgentStepDecision.Completed -> {
                        return AgentTaskResult(true, decision.finalMessage)
                    }
                    is AgentStepDecision.Failed -> {
                        return AgentTaskResult(false, decision.errorMessage)
                    }
                    is AgentStepDecision.PerformAction -> {
                        _statusText.value = "Step $step: ${decision.actionDescription}"
                        decision.action()
                        delay(1200) // Allow UI animation to settle
                    }
                }
            }
            return AgentTaskResult(false, "Task reached maximum step limit ($maxSteps) without completion")
        } catch (e: CancellationException) {
            return AgentTaskResult(false, "Task was cancelled")
        } catch (e: Exception) {
            return AgentTaskResult(false, "Error during execution: ${e.message}")
        } finally {
            _isRunning.value = false
            _currentGoal.value = ""
        }
    }
}

sealed class AgentStepDecision {
    data class PerformAction(val actionDescription: String, val action: suspend () -> Unit) : AgentStepDecision()
    data class Completed(val finalMessage: String) : AgentStepDecision()
    data class Failed(val errorMessage: String) : AgentStepDecision()
}

data class AgentTaskResult(
    val success: Boolean,
    val message: String
)
