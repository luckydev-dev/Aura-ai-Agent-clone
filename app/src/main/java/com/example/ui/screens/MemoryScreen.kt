package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.window.DialogProperties
import android.util.Log
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
    val repository = remember { MemoryRepository(context.applicationContext) }
    val snackbar = remember { SnackbarHostState() }

    val facts by repository.getAllFacts().collectAsState(initial = emptyList())

    var showAddDialog by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf("") }
    var newValue by remember { mutableStateOf("") }
    var newCategory by remember { mutableStateOf("alias") }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("all") }
    var deleteTarget by remember { mutableStateOf<UserFactEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var undoTarget by remember { mutableStateOf<UserFactEntity?>(null) }
    val categories = listOf("alias", "contact", "preference", "fact", "routine")
    val visibleFacts = facts.filter { fact ->
        (searchQuery.isBlank() || fact.key.contains(searchQuery, true) || fact.value.contains(searchQuery, true)) &&
            (selectedCategory == "all" || fact.category.equals(selectedCategory, true))
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
            title = "Aura Memory",
            onBack = onBack,
            actions = {
                AuraButton(
                    text = "Add Fact",
                    onClick = {
                        editingId = null
                        newKey = ""
                        newValue = ""
                        newCategory = "alias"
                        showAddDialog = true
                    },
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

        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search facts") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            FilterChip(selected = selectedCategory == "all", onClick = { selectedCategory = "all" }, label = { Text("All (" + facts.size + ")") })
            categories.forEach { category ->
                FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category }, label = { Text(category + " (" + facts.count { it.category.equals(category, true) } + ")") })
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

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
                items(visibleFacts, key = { it.id }) { fact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(colors.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                            .clickable {
                                editingId = fact.id
                                newKey = fact.key
                                newValue = fact.value
                                newCategory = fact.category
                                showAddDialog = true
                            }
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
                            onClick = { deleteTarget = fact },
                            contentDescription = "Delete memory",
                            size = 48.dp
                        )
                    }
                }
            }
        }

        if (facts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            AuraButton(
                text = "Clear All Memories",
                onClick = { confirmClear = true },
                isPrimary = false,
                isDanger = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                testTag = "clear_memories_btn"
            )
        }
    }

    SnackbarHost(hostState = snackbar)

    if (showAddDialog) AlertDialog(
        onDismissRequest = { showAddDialog = false },
        title = { Text(if (editingId == null) "Add memory" else "Edit memory") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = newKey, onValueChange = { newKey = it.take(120) }, modifier = Modifier.fillMaxWidth(), label = { Text("Key") }, singleLine = true)
            OutlinedTextField(value = newValue, onValueChange = { newValue = it.take(8000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Value") }, minLines = 3, maxLines = 6)
            Text("Category", color = colors.textSecondary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { categories.forEach { category -> FilterChip(selected = newCategory.equals(category, true), onClick = { newCategory = category }, label = { Text(category) }) } }
            Text("Duplicate keys are matched case-insensitively and updated.", color = colors.textTertiary, style = typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = newKey.isNotBlank() && newValue.isNotBlank(), onClick = {
            val key = newKey.trim()
            val value = newValue.trim()
            scope.launch {
                try {
                    val oldId = editingId
                    if (oldId == null) repository.saveFact(key, value, newCategory) else repository.updateFact(oldId, key, value, newCategory)
                    val lower = key.lowercase()
                    if (lower in listOf("owner_name", "preferred_name", "nickname", "name", "call_me", "user_name") || lower.contains("name")) com.example.core.settings.AuraPreferences(context).setOwnerName(value)
                    showAddDialog = false
                    editingId = null
                    newKey = ""
                    newValue = ""
                    snackbar.showSnackbar(if (oldId == null) "Memory saved" else "Memory updated")
                } catch (e: Exception) {
                    Log.e("Aura", "Saving memory failed", e)
                    snackbar.showSnackbar(e.message ?: "Could not save memory")
                }
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    )

    deleteTarget?.let { fact -> AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text("Delete memory?") },
        text = { Text("Delete " + fact.key + "? You can undo this immediately.") },
        confirmButton = { TextButton(onClick = { scope.launch {
            try { repository.deleteFact(fact.id); undoTarget = fact; deleteTarget = null; snackbar.showSnackbar("Memory deleted. Use Undo below.") }
            catch (e: Exception) { Log.e("Aura", "Deleting memory failed", e); snackbar.showSnackbar("Could not delete memory") }
        } }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } }
    ) }

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Clear all memories?") },
        text = { Text("This permanently removes every saved fact.") },
        confirmButton = { TextButton(onClick = { scope.launch {
            try { repository.clearAllFacts(); confirmClear = false; undoTarget = null; snackbar.showSnackbar("All memories cleared") }
            catch (e: Exception) { Log.e("Aura", "Clearing memories failed", e); snackbar.showSnackbar("Could not clear memories") }
        } }) { Text("Clear all") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
    )

    undoTarget?.let { fact -> androidx.compose.material3.Surface(Modifier.fillMaxWidth().navigationBarsPadding(), color = colors.surface) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Deleted: " + fact.key, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch {
                try { repository.saveFact(fact.key, fact.value, fact.category); undoTarget = null; snackbar.showSnackbar("Memory restored") }
                catch (e: Exception) { Log.e("Aura", "Undo restore failed", e); snackbar.showSnackbar("Undo failed") }
            } }) { Text("Undo") }
        }
    } }
}
