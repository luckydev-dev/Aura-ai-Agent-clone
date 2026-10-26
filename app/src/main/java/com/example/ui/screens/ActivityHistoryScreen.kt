package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.ActionLogEntity
import com.example.core.memory.database.ConversationEntity
import com.example.service.AuraForegroundService
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ActivityHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val repository = remember { MemoryRepository(context) }

    var selectedTab by remember { mutableStateOf(0) } // 0: Conversations, 1: Actions
    var searchQuery by remember { mutableStateOf("") }
    var filterType by remember { mutableStateOf("all") } // "all", "success", "failed"
    var selectedLog by remember { mutableStateOf<ActionLogEntity?>(null) }
    var selectedConversation by remember { mutableStateOf<ConversationEntity?>(null) }

    // Conversations Flow
    val conversationsFlow = remember(searchQuery) {
        if (searchQuery.isBlank()) repository.getRecentConversations(100) else repository.searchConversations(searchQuery)
    }
    val conversations by conversationsFlow.collectAsState(initial = emptyList())

    // Action Logs Flow
    val logsFlow = remember(searchQuery) {
        if (searchQuery.isBlank()) repository.getActionLogs(100) else repository.searchLogs(searchQuery)
    }
    val logs by logsFlow.collectAsState(initial = emptyList())

    val filteredLogs = logs.filter {
        when (filterType) {
            "success" -> it.success
            "failed" -> !it.success
            else -> true
        }
    }

    val dateFormat = remember { SimpleDateFormat("HH:mm:ss · dd MMM", Locale.getDefault()) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        AuraTopBar(
            title = if (selectedTab == 0) "Conversations" else "Action History",
            onBack = onBack,
            actions = {
                AuraIconButton(
                    icon = AuraIcons.Close,
                    onClick = {
                        scope.launch {
                            if (selectedTab == 0) repository.clearConversations() else repository.clearActionLogs()
                        }
                    },
                    contentDescription = "Clear All",
                    size = 36.dp
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Tab Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surfaceRaised)
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedTab == 0) colors.accentStart else colors.surfaceRaised)
                    .clickable { selectedTab = 0 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "Conversations (${conversations.size})",
                    style = typography.labelMedium.copy(
                        color = if (selectedTab == 0) androidx.compose.ui.graphics.Color.White else colors.textSecondary
                    )
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selectedTab == 1) colors.accentStart else colors.surfaceRaised)
                    .clickable { selectedTab = 1 }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                BasicText(
                    text = "Tool Actions (${logs.size})",
                    style = typography.labelMedium.copy(
                        color = if (selectedTab == 1) androidx.compose.ui.graphics.Color.White else colors.textSecondary
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Bar
        AuraTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = if (selectedTab == 0) "Search conversations..." else "Search actions or output...",
            testTag = "history_search_field"
        )

        Spacer(modifier = Modifier.height(10.dp))

        if (selectedTab == 0) {
            // CONVERSATIONS TAB
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicText(
                    text = "Recent Turns",
                    style = typography.labelSmall.copy(color = colors.textTertiary)
                )
                if (conversations.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AuraChip(
                            text = "Export Chat",
                            selected = false,
                            onClick = {
                                val fullChat = conversations.reversed().joinToString("\n\n") {
                                    "[${it.speaker.uppercase()} - ${dateFormat.format(Date(it.timestamp))}]: ${it.text}"
                                }
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Aura Chat", fullChat))
                                Toast.makeText(context, "Full chat copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                        )
                        AuraChip(
                            text = "Continue Chat",
                            selected = true,
                            onClick = {
                                AuraForegroundService.startCall(context)
                                onBack()
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (conversations.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "No saved conversations yet. Start a call with Aura to see chats here.",
                        style = typography.bodyMedium.copy(color = colors.textTertiary)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(conversations, key = { it.id }) { item ->
                        val isUser = item.speaker.equals("user", ignoreCase = true)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (isUser) colors.surface else colors.surfaceRaised)
                                .border(
                                    1.dp,
                                    if (isUser) colors.border else colors.accentStart.copy(alpha = 0.3f),
                                    RoundedCornerShape(16.dp)
                                )
                                .clickable { selectedConversation = item }
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicText(
                                    text = if (isUser) "You" else "Aura",
                                    style = typography.labelMedium.copy(
                                        color = if (isUser) colors.textSecondary else colors.accentStart
                                    )
                                )
                                BasicText(
                                    text = dateFormat.format(Date(item.timestamp)),
                                    style = typography.labelSmall.copy(color = colors.textTertiary)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            BasicText(
                                text = item.text,
                                style = typography.bodyMedium.copy(color = colors.textPrimary)
                            )
                        }
                    }
                }
            }
        } else {
            // TOOL ACTIONS TAB
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AuraChip(
                    text = "All (${logs.size})",
                    selected = filterType == "all",
                    onClick = { filterType = "all" },
                    testTag = "filter_all_chip"
                )
                AuraChip(
                    text = "Success",
                    selected = filterType == "success",
                    onClick = { filterType = "success" },
                    testTag = "filter_success_chip"
                )
                AuraChip(
                    text = "Failed",
                    selected = filterType == "failed",
                    onClick = { filterType = "failed" },
                    testTag = "filter_failed_chip"
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    BasicText(
                        text = "No action history found.",
                        style = typography.bodyMedium.copy(color = colors.textTertiary)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surface)
                                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                .clickable { selectedLog = log }
                                .padding(14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    AuraBadge(
                                        text = if (log.success) "SUCCESS" else "FAILED",
                                        isSuccess = log.success
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    BasicText(
                                        text = log.toolName,
                                        style = typography.labelLarge.copy(color = colors.textPrimary)
                                    )
                                }
                                BasicText(
                                    text = "${log.durationMs}ms",
                                    style = typography.labelSmall.copy(color = colors.textTertiary)
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (log.output.isNotBlank()) {
                                BasicText(
                                    text = log.output.trim(),
                                    style = typography.bodySmall.copy(color = colors.textSecondary),
                                    maxLines = 2
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            BasicText(
                                text = dateFormat.format(Date(log.timestamp)),
                                style = typography.labelSmall.copy(color = colors.textTertiary)
                            )
                        }
                    }
                }
            }
        }
    }

    // Detail Dialog for Action Log
    selectedLog?.let { log ->
        AuraDialog(
            title = "Tool: ${log.toolName}",
            message = "Status: ${if (log.success) "Success" else "Failed"}\nDuration: ${log.durationMs}ms\nArgs:\n${log.argsJson}\n\nOutput:\n${log.output.ifEmpty { "(None)" }}",
            confirmText = "Delete",
            cancelText = "Close",
            isDangerous = true,
            onConfirm = {
                scope.launch {
                    repository.deleteActionLog(log.id)
                    selectedLog = null
                }
            },
            onCancel = { selectedLog = null }
        )
    }

    // Detail Dialog for Conversation Turn
    selectedConversation?.let { conv ->
        AuraDialog(
            title = "Turn: ${conv.speaker.uppercase()}",
            message = "${conv.text}\n\nTime: ${dateFormat.format(Date(conv.timestamp))}",
            confirmText = "Delete",
            cancelText = "Close",
            isDangerous = true,
            onConfirm = {
                scope.launch {
                    repository.deleteConversation(conv.id)
                    selectedConversation = null
                }
            },
            onCancel = { selectedConversation = null }
        )
    }
}
