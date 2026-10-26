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
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.UserFactEntity
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val repository = remember { MemoryRepository(context) }

    val facts by repository.getAllFacts().collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("alias") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        AuraTopBar(
            title = "Aura Memory",
            onBack = onBack,
            actions = {
                AuraButton(
                    text = "Add Fact",
                    onClick = { showAddDialog = true },
                    isPrimary = true,
                    testTag = "add_fact_btn"
                )
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        androidx.compose.foundation.text.BasicText(
            text = "User Facts & Aliases",
            style = typography.titleMedium.copy(color = colors.textPrimary)
        )
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.foundation.text.BasicText(
            text = "Aura injects these memories directly into the conversation so it knows your preferences, relationships, and app aliases.",
            style = typography.bodySmall.copy(color = colors.textSecondary)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (facts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.text.BasicText(
                    text = "No saved memories yet.\nTap 'Add Fact' to teach Aura aliases or relationships.",
                    style = typography.bodyMedium.copy(color = colors.textTertiary)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(facts, key = { it.id }) { fact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AuraBadge(text = fact.category.uppercase(), isSuccess = true)
                                Spacer(modifier = Modifier.width(8.dp))
                                androidx.compose.foundation.text.BasicText(
                                    text = fact.key,
                                    style = typography.labelLarge.copy(color = colors.textPrimary)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            androidx.compose.foundation.text.BasicText(
                                text = fact.value,
                                style = typography.bodyMedium.copy(color = colors.textSecondary)
                            )
                        }

                        AuraIconButton(
                            icon = AuraIcons.Close,
                            onClick = {
                                scope.launch { repository.deleteFact(fact.id) }
                            },
                            contentDescription = "Delete",
                            size = 32.dp
                        )
                    }
                }
            }
        }

        if (facts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            AuraButton(
                text = "Clear All Memories",
                onClick = {
                    scope.launch { repository.clearAllFacts() }
                },
                isPrimary = false,
                isDanger = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                testTag = "clear_memories_btn"
            )
        }
    }

    if (showAddDialog) {
        AuraDialog(
            title = "Add Memory / Alias",
            message = "Example: key='mom', value='Pooja (+91 98765 43210)', or key='my gym app', value='FitNotes'",
            confirmText = "Save",
            cancelText = "Cancel",
            onConfirm = {
                if (newKey.isNotBlank() && newValue.isNotBlank()) {
                    scope.launch {
                        repository.saveFact(newKey, newValue, newCategory)
                        val lower = newKey.lowercase()
                        if (lower in listOf("owner_name", "preferred_name", "nickname", "name", "call_me", "user_name") || lower.contains("name")) {
                            com.example.core.settings.AuraPreferences(context).setOwnerName(newValue.trim())
                        }
                        newKey = ""
                        newValue = ""
                        showAddDialog = false
                    }
                }
            },
            onCancel = { showAddDialog = false }
        )
    }
}
