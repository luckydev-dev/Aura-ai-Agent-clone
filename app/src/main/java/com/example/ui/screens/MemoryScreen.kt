package com.example.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.core.memory.MemoryRepository
import com.example.core.memory.database.UserFactEntity
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch

@Composable
fun MemoryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val repository = remember { MemoryRepository(context.applicationContext) }
    val facts by repository.getAllFacts().collectAsState(initial = emptyList())
    val snackbar = remember { SnackbarHostState() }
    var showEditor by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var keyText by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("alias") }
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("all") }
    var deleteTarget by remember { mutableStateOf<UserFactEntity?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var undoTarget by remember { mutableStateOf<UserFactEntity?>(null) }
    val categories = listOf("alias", "contact", "preference", "fact", "routine")
    val visibleFacts = facts.filter { fact ->
        (query.isBlank() || fact.key.contains(query, true) || fact.value.contains(query, true)) &&
        (categoryFilter == "all" || fact.category.equals(categoryFilter, true))
    }
    fun startNew() { editingId = null; keyText = ""; valueText = ""; category = "alias"; showEditor = true }

    Column(modifier = modifier.fillMaxSize().background(colors.background).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp)) {
        AuraTopBar(title = "Aura Memory", onBack = onBack, actions = { AuraButton(text = "Add Fact", onClick = ::startNew, isPrimary = true, testTag = "add_fact_btn") })
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.text.BasicText("User Facts & Aliases", style = typography.titleMedium.copy(color = colors.textPrimary))
        androidx.compose.foundation.text.BasicText("Facts saved here are available to Maya. Tap a fact to edit it.", style = typography.bodySmall.copy(color = colors.textSecondary))
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Search facts") }, singleLine = true)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = categoryFilter == "all", onClick = { categoryFilter = "all" }, label = { Text("All (${facts.size})") })
            categories.forEach { cat -> FilterChip(selected = categoryFilter == cat, onClick = { categoryFilter = cat }, label = { Text("$cat (${facts.count { it.category.equals(cat, true) }})") }) }
        }
        Spacer(Modifier.height(10.dp))
        if (facts.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.text.BasicText("No saved facts yet. Add a fact such as mom = Pooja or my gym app = FitNotes.", style = typography.bodyMedium.copy(color = colors.textTertiary))
        } else if (visibleFacts.isEmpty()) Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Text("No facts match this search or category.", color = colors.textTertiary) }
        else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(visibleFacts, key = { it.id }) { fact ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).border(1.dp, colors.border, RoundedCornerShape(16.dp)).clickable { editingId = fact.id; keyText = fact.key; valueText = fact.value; category = fact.category; showEditor = true }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { AuraBadge(text = fact.category.uppercase(), isSuccess = true); Spacer(Modifier.width(8.dp)); androidx.compose.foundation.text.BasicText(fact.key, style = typography.labelLarge.copy(color = colors.textPrimary)) }
                        Spacer(Modifier.height(4.dp))
                        androidx.compose.foundation.text.BasicText(fact.value, style = typography.bodyMedium.copy(color = colors.textSecondary))
                    }
                    AuraIconButton(icon = AuraIcons.Close, onClick = { deleteTarget = fact }, contentDescription = "Delete memory", size = 48.dp)
                }
            }
        }
        if (facts.isNotEmpty()) { Spacer(Modifier.height(10.dp)); AuraButton(text = "Clear All Memories", onClick = { confirmClear = true }, isPrimary = false, isDanger = true, modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp), testTag = "clear_memories_btn") }
    }
    SnackbarHost(hostState = snackbar)

    if (showEditor) AlertDialog(
        onDismissRequest = { showEditor = false },
        title = { Text(if (editingId == null) "Add memory" else "Edit memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = keyText, onValueChange = { keyText = it.take(120) }, modifier = Modifier.fillMaxWidth(), label = { Text("Key") }, singleLine = true)
                OutlinedTextField(value = valueText, onValueChange = { valueText = it.take(8000) }, modifier = Modifier.fillMaxWidth(), label = { Text("Value") }, minLines = 3, maxLines = 6)
                Text("Category", color = colors.textSecondary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) { categories.forEach { cat -> FilterChip(selected = category.equals(cat, true), onClick = { category = cat }, label = { Text(cat) }) } }
                Text("Duplicate keys are matched case-insensitively and updated.", color = colors.textTertiary, style = typography.bodySmall)
            }
        },
        confirmButton = { TextButton(enabled = keyText.isNotBlank() && valueText.isNotBlank(), onClick = {
            val cleanKey = keyText.trim(); val cleanValue = valueText.trim()
            scope.launch {
                try {
                    val oldId = editingId
                    if (oldId == null) repository.saveFact(cleanKey, cleanValue, category) else repository.updateFact(oldId, cleanKey, cleanValue, category)
                    val lower = cleanKey.lowercase()
                    if (lower in listOf("owner_name", "preferred_name", "nickname", "name", "call_me", "user_name") || lower.contains("name")) com.example.core.settings.AuraPreferences(context).setOwnerName(cleanValue)
                    showEditor = false; editingId = null; keyText = ""; valueText = ""
                    snackbar.showSnackbar(if (oldId == null) "Memory saved" else "Memory updated")
                } catch (e: Exception) { Log.e("Aura", "Saving memory failed", e); snackbar.showSnackbar(e.message ?: "Could not save memory") }
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { showEditor = false }) { Text("Cancel") } },
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

    undoTarget?.let { deleted -> androidx.compose.material3.Surface(Modifier.fillMaxWidth().navigationBarsPadding(), color = colors.surface) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Deleted: " + deleted.key, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch {
                try { repository.saveFact(deleted.key, deleted.value, deleted.category); undoTarget = null; snackbar.showSnackbar("Memory restored") }
                catch (e: Exception) { Log.e("Aura", "Undo delete failed", e); snackbar.showSnackbar("Undo failed") }
            } }) { Text("Undo") }
        }
    } }
}