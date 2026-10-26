package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.agent.AgentLoop
import com.example.core.memory.MemoryRepository
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.core.tools.Tool
import com.example.core.tools.ToolExecutionResult
import com.example.core.tools.ToolRegistry
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch

@Composable
fun ToolsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val preferences = remember { AuraPreferences(context) }
    val rootManager = remember { RootManager(context) }
    val memoryRepo = remember { MemoryRepository(context) }
    val agentLoop = remember { AgentLoop(rootManager) }
    val registry = remember { ToolRegistry(context, rootManager, preferences, memoryRepo, agentLoop) }

    val disabledTools by preferences.disabledTools.collectAsState(initial = emptySet())
    val tools = remember { registry.getAllTools().sortedBy { it.name } }

    var searchQuery by remember { mutableStateOf("") }
    var testResultDialog by remember { mutableStateOf<Pair<String, ToolExecutionResult>?>(null) }
    var testingToolName by remember { mutableStateOf<String?>(null) }

    val filteredTools = tools.filter {
        if (searchQuery.isBlank()) true else it.name.contains(searchQuery, ignoreCase = true) || it.description.contains(searchQuery, ignoreCase = true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        AuraTopBar(
            title = "Agent Tools (${tools.size})",
            onBack = onBack
        )

        Spacer(modifier = Modifier.height(8.dp))

        AuraTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = "Filter tools by name or description...",
            testTag = "tools_search_field"
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filteredTools, key = { it.name }) { tool ->
                val isEnabled = tool.name !in disabledTools

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        androidx.compose.foundation.text.BasicText(
                            text = tool.name,
                            style = typography.labelLarge.copy(color = colors.textPrimary)
                        )
                        AuraToggle(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                scope.launch { preferences.setToolEnabled(tool.name, checked) }
                            },
                            testTag = "toggle_${tool.name}"
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    androidx.compose.foundation.text.BasicText(
                        text = tool.description,
                        style = typography.bodySmall.copy(color = colors.textSecondary)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        AuraButton(
                            text = if (testingToolName == tool.name) "Testing..." else "Test Tool",
                            onClick = {
                                scope.launch {
                                    testingToolName = tool.name
                                    val sampleArgs = generateSampleArgs(tool)
                                    val res = tool.execute(sampleArgs)
                                    testResultDialog = Pair(tool.name, res)
                                    testingToolName = null
                                }
                            },
                            isPrimary = false,
                            enabled = isEnabled && testingToolName == null,
                            testTag = "test_${tool.name}"
                        )
                    }
                }
            }
        }
    }

    testResultDialog?.let { (toolName, result) ->
        AuraDialog(
            title = "Test: $toolName",
            message = "Success: ${result.success}\n\nOutput:\n${result.output.ifEmpty { "(No output)" }}",
            confirmText = "OK",
            cancelText = "Close",
            onConfirm = { testResultDialog = null },
            onCancel = { testResultDialog = null }
        )
    }
}

private fun generateSampleArgs(tool: Tool): Map<String, Any?> {
    return when (tool.name) {
        "open_app" -> mapOf("name_or_package" to "Settings")
        "close_app" -> mapOf("name" to "Settings")
        "set_wifi" -> mapOf("on" to true)
        "set_bluetooth" -> mapOf("on" to true)
        "set_brightness" -> mapOf("percent" to 50)
        "set_volume" -> mapOf("percent" to 40, "stream" to "media")
        "read_screen" -> emptyMap()
        "take_screenshot" -> mapOf("describe" to false)
        "run_shell" -> mapOf("command" to "id", "as_root" to true)
        "web_search" -> mapOf("query" to "Android documentation")
        "get_battery", "get_storage", "get_ram", "get_device_info" -> emptyMap()
        else -> emptyMap()
    }
}
