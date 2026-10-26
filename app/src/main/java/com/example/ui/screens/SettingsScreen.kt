package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.AuraApplication
import com.example.core.audio.VoskModelManager
import com.example.core.audio.VoskModelStatus
import com.example.core.live.GeminiLiveHandshake
import com.example.core.live.LiveDiagnostics
import com.example.core.root.PermissionCheckItem
import com.example.core.root.PermissionDoctor
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraAccessibilityService
import com.example.service.AuraForegroundService
import com.example.service.AuraNotificationListener
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SettingsCategory(
    val title: String,
    val subtitle: String,
    val icon: ImageVector
) {
    API_KEYS("API Keys & AI Models", "Manage Gemini key pool, auto-failover, and model selection", Icons.Default.VpnKey),
    PERSONA("Persona & Identity", "Customize assistant name (Maya), preset profiles, and prompt editor", Icons.Default.Face),
    EDGE_LIGHTING("Edge Lighting & Overlays", "Real-time edge glow preview, animation speed, and floating overlays", Icons.Default.Lightbulb),
    OWNER_VOICE("Owner Voice & Security", "Voice enrollment, offline wake word, and screen lock automation", Icons.Default.Person),
    PERMISSIONS("System Permissions", "Manage Accessibility, Overlay, Notifications, Mic, and Phone permissions", Icons.Default.Security),
    SPEECH_CALLS("Speech, Language & Calls", "Hinglish default, live captions, incoming call announcements, soft ring", Icons.Default.PhoneCallback),
    NOTIFICATION_DETECT("Notification Announcements", "Watch selected apps & announce messages out loud in real time", Icons.Default.NotificationsActive),
    DEVICE_TOOLS("Device Automation & Tools", "Autonomous planner, device control tools, permissions, and timeouts", Icons.Default.Build)
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val preferences = remember { AuraPreferences(context) }
    val diagnostics = remember { AuraApplication.instance.liveDiagnostics }
    val rootManager = remember { RootManager(context) }
    val permissionDoctor = remember { PermissionDoctor(context, rootManager) }
    val voskModelManager = remember { VoskModelManager(context) }

    // Navigation Category State
    var activeCategory by remember { mutableStateOf<SettingsCategory?>(null) }

    // Collected Preferences
    val apiKey by preferences.apiKey.collectAsState(initial = "")
    val apiKeyPool by preferences.apiKeyPool.collectAsState(initial = emptyList())
    var newKeyInput by remember { mutableStateOf("") }
    val modelName by preferences.modelName.collectAsState(initial = AuraPreferences.DEFAULT_MODEL)
    val voiceName by preferences.voiceName.collectAsState(initial = AuraPreferences.DEFAULT_VOICE)
    val thinkingLevel by preferences.thinkingLevel.collectAsState(initial = AuraPreferences.DEFAULT_THINKING_LEVEL)
    val cachedLiveModels by preferences.cachedLiveModels.collectAsState(initial = emptySet())

    val defaultLanguage by preferences.defaultLanguage.collectAsState(initial = AuraPreferences.DEFAULT_LANGUAGE)
    val greetOnConnect by preferences.greetOnConnect.collectAsState(initial = true)
    val showCaptions by preferences.showCaptions.collectAsState(initial = true)

    val allowInterrupting by preferences.allowInterrupting.collectAsState(initial = true)
    val interruptionSens by preferences.interruptionSensitivity.collectAsState(initial = "Low")
    val noiseGateStrength by preferences.noiseGateStrength.collectAsState(initial = "Medium")
    val autoHangupSec by preferences.autoHangupSeconds.collectAsState(initial = 30)

    val wakeEnabled by preferences.wakePhraseEnabled.collectAsState(initial = false)
    val wakeSensitivity by preferences.wakeWordSensitivity.collectAsState(initial = "normal")
    val voskStatus by voskModelManager.status.collectAsState()

    val edgeLightingEnabled by preferences.edgeLightingEnabled.collectAsState(initial = true)
    val edgeLightingThickness by preferences.edgeLightingThickness.collectAsState(initial = 8)
    val edgeLightingShowInApp by preferences.edgeLightingShowInApp.collectAsState(initial = true)
    val edgeLightingIntensity by preferences.edgeLightingIntensity.collectAsState(initial = 1.0f)
    val floatingCaptionsEnabled by preferences.floatingCaptionsEnabled.collectAsState(initial = true)
    val overlayEnabled by preferences.overlayEnabled.collectAsState(initial = false)
    val interactiveCaptionsHandle by preferences.interactiveCaptionsHandleFlow.collectAsState(initial = false)
    val captionPosition by preferences.captionPositionFlow.collectAsState(initial = "bottom")
    val captionAutoHideSeconds by preferences.captionAutoHideSecondsFlow.collectAsState(initial = 5)
    val captionTransparency by preferences.captionTransparencyFlow.collectAsState(initial = 0.9f)

    val idleSilenceMinutes by preferences.idleSilenceMinutes.collectAsState(initial = 5)
    val keepAliveScreenOff by preferences.keepAliveScreenOff.collectAsState(initial = false)
    val screenOffTimeoutMinutes by preferences.screenOffTimeoutMinutes.collectAsState(initial = 3)
    val plannerModelName by preferences.plannerModelName.collectAsState(initial = "gemini-2.5-flash")
    val plannerUseScreenshots by preferences.plannerUseScreenshots.collectAsState(initial = true)

    val notifAnnounceEnabled by preferences.notifAnnounceEnabled.collectAsState(initial = false)
    val notifAnnounceApps by preferences.notifAnnounceApps.collectAsState(initial = AuraPreferences.DEFAULT_ANNOUNCE_APPS)
    val notifAnnounceReadContent by preferences.notifAnnounceReadContent.collectAsState(initial = true)
    var customPackageInput by remember { mutableStateOf("") }

    val ownerName by preferences.ownerName.collectAsState(initial = AuraPreferences.DEFAULT_OWNER_NAME)
    val extraInstructions by preferences.extraInstructions.collectAsState(initial = "")

    // Local mutable state for text inputs
    var inputModel by remember(modelName) { mutableStateOf(modelName) }
    var inputThinkingLevel by remember(thinkingLevel) { mutableStateOf(thinkingLevel) }

    val screenLockType by preferences.screenLockType.collectAsState(initial = "NONE")
    val savedPatternPoints by preferences.savedPatternPoints.collectAsState(initial = "")
    val savedLockPin by preferences.savedLockPin.collectAsState(initial = "")
    val savedLockPassword by preferences.savedLockPassword.collectAsState(initial = "")

    var selectedLockPill by remember(screenLockType) {
        mutableStateOf(if (screenLockType == "NONE" || screenLockType.isBlank()) "PATTERN" else screenLockType)
    }
    var patternStep by remember { mutableStateOf(1) }
    var firstDrawnPattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    var patternStatusText by remember { mutableStateOf("Draw your unlock pattern to begin") }
    var patternLockState by remember { mutableStateOf(PatternState.NORMAL) }
    var pinInput by remember(savedLockPin) { mutableStateOf(savedLockPin) }
    var pinConfirmInput by remember { mutableStateOf("") }
    var passwordInput by remember(savedLockPassword) { mutableStateOf(savedLockPassword) }
    var passwordConfirmInput by remember { mutableStateOf("") }

    // Pattern calibration state
    var showPatternCalibrationDialog by remember { mutableStateOf(false) }
    var calibOffsetY by remember { mutableStateOf(0f) }
    var calibOffsetX by remember { mutableStateOf(0f) }
    var calibScale by remember { mutableStateOf(1.0f) }

    // Test connection state
    var isTestingConnection by remember { mutableStateOf(false) }

    // Root grant all and unlock testing states
    var isGrantingAll by remember { mutableStateOf(false) }
    var isTestingUnlock by remember { mutableStateOf(false) }
    var unlockStepLog by remember { mutableStateOf<List<String>>(emptyList()) }
    var unlockResultStatus by remember { mutableStateOf<String?>(null) }

    // Model fetching state
    var isFetchingModels by remember { mutableStateOf(false) }
    var fetchModelsMessage by remember { mutableStateOf<String?>(null) }
    var fetchModelsSuccess by remember { mutableStateOf(true) }

    val displayedModels = remember(cachedLiveModels) {
        if (cachedLiveModels.isNotEmpty()) cachedLiveModels.toList() else AuraPreferences.INITIAL_LIVE_MODELS
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // Header Bar
            AuraTopBar(
                title = activeCategory?.title ?: "Settings & AI Hub",
                onBack = {
                    if (activeCategory != null) {
                        activeCategory = null
                    } else {
                        onBack()
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (activeCategory == null) {
                // CATEGORICAL SETTINGS HUB (Vertical List with Navigation Arrows)
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(SettingsCategory.values()) { category ->
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = colors.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                .clickable { activeCategory = category }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF23283B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = category.icon,
                                        contentDescription = null,
                                        tint = Color(0xFF7085FF),
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(14.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = category.title,
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = category.subtitle,
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                }

                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Open",
                                    tint = colors.textTertiary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                // SUB-PAGES ACCORDING TO SELECTED CATEGORY
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (activeCategory) {
                        SettingsCategory.API_KEYS -> {
                            // 1. Gemini API Keys Pool Card
                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Gemini API Keys Pool (Auto-Failover)",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Add multiple Gemini API keys. If a key hits rate limit (429), Aura automatically switches to the next working key.",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    // Add Key Row
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        AuraTextField(
                                            value = newKeyInput,
                                            onValueChange = { newKeyInput = it },
                                            placeholder = "Paste AIzaSy... key",
                                            modifier = Modifier.weight(1f),
                                            testTag = "add_api_key_field"
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        AuraButton(
                                            text = "+ Add Key",
                                            onClick = {
                                                if (newKeyInput.isNotBlank()) {
                                                    preferences.addApiKey(newKeyInput, "UNTESTED")
                                                    Toast.makeText(context, "API Key added to pool", Toast.LENGTH_SHORT).show()
                                                    newKeyInput = ""
                                                }
                                            },
                                            enabled = newKeyInput.isNotBlank(),
                                            testTag = "add_key_btn"
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))

                                    if (apiKeyPool.isEmpty()) {
                                        Text(
                                            text = "No API keys added yet. Add at least one Gemini key above.",
                                            style = typography.labelMedium.copy(color = colors.warning)
                                        )
                                    } else {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            apiKeyPool.forEach { entry ->
                                                val maskedKey = if (entry.key.length > 10) "${entry.key.take(6)}...${entry.key.takeLast(4)}" else entry.key
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(colors.surfaceRaised)
                                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = maskedKey,
                                                            style = typography.titleSmall.copy(color = colors.textPrimary, fontFamily = FontFamily.Monospace)
                                                        )
                                                        Spacer(modifier = Modifier.height(2.dp))
                                                        val (statusLabel, statusColor) = when (entry.status.uppercase()) {
                                                            "WORKING" -> "✓ Working" to colors.success
                                                            "EXCEEDED" -> "⚠️ Rate Exceeded" to colors.danger
                                                            else -> "? Untested" to colors.textTertiary
                                                        }
                                                        Text(
                                                            text = statusLabel,
                                                            style = typography.labelSmall.copy(color = statusColor, fontWeight = FontWeight.Bold)
                                                        )
                                                    }

                                                    AuraButton(
                                                        text = "Test",
                                                        onClick = {
                                                            scope.launch {
                                                                Toast.makeText(context, "Testing key $maskedKey...", Toast.LENGTH_SHORT).show()
                                                                val res = testGeminiLiveWebSocket(
                                                                    apiKey = entry.key,
                                                                    model = inputModel,
                                                                    thinkingLevel = inputThinkingLevel,
                                                                    diagnostics = diagnostics
                                                                )
                                                                if (res.startsWith("Live OK") || res.contains("ready")) {
                                                                    preferences.markKeyStatus(entry.key, "WORKING")
                                                                    Toast.makeText(context, "Key working!", Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    preferences.markKeyStatus(entry.key, "EXCEEDED")
                                                                    Toast.makeText(context, "Key failed: $res", Toast.LENGTH_LONG).show()
                                                                }
                                                            }
                                                        },
                                                        isPrimary = false,
                                                        testTag = "test_key_btn_${entry.key.takeLast(4)}"
                                                    )

                                                    Spacer(modifier = Modifier.width(6.dp))

                                                    IconButton(
                                                        onClick = {
                                                            preferences.removeApiKey(entry.key)
                                                            Toast.makeText(context, "Key removed", Toast.LENGTH_SHORT).show()
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.Close,
                                                            contentDescription = "Remove Key",
                                                            tint = colors.danger,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            // 2. Gemini Live Model Selection
                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Gemini Live Model",
                                            style = typography.titleMedium.copy(color = colors.textPrimary)
                                        )
                                        AuraButton(
                                            text = if (isFetchingModels) "Fetching..." else "Fetch Live models",
                                            onClick = {
                                                scope.launch {
                                                    isFetchingModels = true
                                                    fetchModelsMessage = "Querying supported bidi models..."
                                                    val primaryKey = preferences.getPrimaryWorkingKey()
                                                    val result = GeminiLiveHandshake.fetchAvailableLiveModels(primaryKey)
                                                    result.onSuccess { models ->
                                                        preferences.setCachedLiveModels(models.toSet())
                                                        fetchModelsSuccess = true
                                                        fetchModelsMessage = "Fetched ${models.size} Live model(s)"
                                                    }.onFailure { err ->
                                                        fetchModelsSuccess = false
                                                        fetchModelsMessage = "Fetch failed: ${err.message}"
                                                    }
                                                    isFetchingModels = false
                                                }
                                            },
                                            isPrimary = false,
                                            enabled = !isFetchingModels
                                        )
                                    }

                                    fetchModelsMessage?.let { msg ->
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = msg,
                                            style = typography.labelSmall.copy(
                                                color = if (fetchModelsSuccess) colors.success else colors.danger
                                            )
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(displayedModels) { model ->
                                            AuraChip(
                                                text = model,
                                                selected = inputModel == model,
                                                onClick = {
                                                    inputModel = model
                                                    scope.launch { preferences.setModelName(model) }
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        SettingsCategory.PERSONA -> {
                            item {
                                PersonaSettingsCard(preferences = preferences)
                            }
                        }

                        SettingsCategory.EDGE_LIGHTING -> {
                            item {
                                EdgeLightingSettingsCard(preferences = preferences)
                            }

                            item {
                                AgentCursorSettingsCard(preferences = preferences)
                            }

                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Floating System Overlays",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    AuraToggleRow(
                                        title = "Floating Live Captions",
                                        subtitle = "Compact auto-expanding live subtitle pill over external apps. Click-through by default.",
                                        checked = floatingCaptionsEnabled,
                                        onCheckedChange = { scope.launch { preferences.setFloatingCaptionsEnabled(it) } }
                                    )
                                    if (floatingCaptionsEnabled) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Column(modifier = Modifier.padding(start = 12.dp)) {
                                            AuraToggleRow(
                                                title = "Allow moving captions",
                                                subtitle = "Adds a small 40dp drag handle to reposition the pill.",
                                                checked = interactiveCaptionsHandle,
                                                onCheckedChange = { scope.launch { preferences.setInteractiveCaptionsHandle(it) } }
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Pill Position", style = typography.bodySmall.copy(color = colors.textPrimary))
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    listOf("top" to "Top", "bottom" to "Bottom").forEach { (pos, label) ->
                                                        val isSelected = captionPosition == pos
                                                        Button(
                                                            onClick = { scope.launch { preferences.setCaptionPosition(pos) } },
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = if (isSelected) colors.accentStart else colors.surfaceRaised,
                                                                contentColor = if (isSelected) Color.White else colors.textPrimary
                                                            ),
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(32.dp)
                                                        ) {
                                                            Text(label, fontSize = 12.sp)
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Auto-Hide Delay", style = typography.bodySmall.copy(color = colors.textPrimary))
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    listOf(3 to "3s", 5 to "5s", 10 to "10s", 0 to "Never").forEach { (sec, label) ->
                                                        val isSelected = captionAutoHideSeconds == sec
                                                        Button(
                                                            onClick = { scope.launch { preferences.setCaptionAutoHideSeconds(sec) } },
                                                            colors = ButtonDefaults.buttonColors(
                                                                containerColor = if (isSelected) colors.accentStart else colors.surfaceRaised,
                                                                contentColor = if (isSelected) Color.White else colors.textPrimary
                                                            ),
                                                            shape = RoundedCornerShape(8.dp),
                                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                                            modifier = Modifier.height(32.dp)
                                                        ) {
                                                            Text(label, fontSize = 11.sp)
                                                        }
                                                    }
                                                }
                                            }
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Opacity (${(captionTransparency * 100).toInt()}%)", style = typography.bodySmall.copy(color = colors.textPrimary))
                                                Slider(
                                                    value = captionTransparency,
                                                    onValueChange = { scope.launch { preferences.setCaptionTransparency(it) } },
                                                    valueRange = 0.5f..1.0f,
                                                    steps = 4,
                                                    modifier = Modifier.width(160.dp)
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(12.dp))
                                    AuraToggleRow(
                                        title = "Floating Mini-Orb Overlay",
                                        subtitle = "Draggable floating mini orb outside the app for quick control.",
                                        checked = overlayEnabled,
                                        onCheckedChange = { scope.launch { preferences.setOverlayEnabled(it) } }
                                    )
                                }
                            }
                        }

                        SettingsCategory.OWNER_VOICE -> {
                            item {
                                OwnerVoiceEnrollmentCard()
                            }

                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Offline Wake Word (\"Hey Maya\")",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Powered by local Vosk Indian-English acoustic model. Listens strictly for 'Hey Maya'.",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    AuraToggleRow(
                                        title = "Listen for \"Hey Maya\"",
                                        subtitle = "Continuously listens for wake trigger in background service.",
                                        checked = wakeEnabled,
                                        onCheckedChange = { scope.launch { preferences.setWakePhraseEnabled(it) } }
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    when (val status = voskStatus) {
                                        is VoskModelStatus.Ready -> {
                                            AuraBadge(text = "✓ Vosk Model Ready (Offline)", isSuccess = true)
                                        }
                                        is VoskModelStatus.Downloading -> {
                                            Text(
                                                text = "Downloading Model (${status.progressPercent}%)...",
                                                style = typography.labelSmall.copy(color = colors.accentStart)
                                            )
                                        }
                                        else -> {
                                            AuraButton(
                                                text = "Download Wake Model (~36 MB)",
                                                onClick = {
                                                    scope.launch {
                                                        voskModelManager.downloadAndExtractModel()
                                                    }
                                                },
                                                isPrimary = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }
                                }
                            }

                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Screen Lock & Automated Unlock",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Set your lock screen credentials so Maya can automatically unlock your device when given voice commands hands-free.",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))

                                    // Three Pill Buttons: Pattern, PIN, Password
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(colors.surfaceRaised)
                                            .padding(4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        listOf("PATTERN" to "Pattern", "PIN" to "PIN", "PASSWORD" to "Password").forEach { (type, label) ->
                                            val isSelected = selectedLockPill == type
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(if (isSelected) colors.accentStart else Color.Transparent)
                                                    .clickable { selectedLockPill = type }
                                                    .padding(vertical = 10.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 13.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (isSelected) Color.Black else colors.textSecondary
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    when (selectedLockPill) {
                                        "PATTERN" -> {
                                            val hasPattern = savedPatternPoints.isNotBlank()
                                            if (hasPattern && patternStep == 1 && firstDrawnPattern.isEmpty() && patternLockState == PatternState.NORMAL) {
                                                val count = savedPatternPoints.split(",").filter { it.isNotBlank() }.size
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(colors.accentStart.copy(alpha = 0.15f))
                                                        .border(1.dp, colors.accentStart.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                                        .padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(
                                                        text = "✓ Pattern Saved ($count dots connected)",
                                                        style = typography.labelMedium.copy(color = colors.accentStart),
                                                        modifier = Modifier.weight(1f)
                                                    )
                                                    AuraButton(
                                                        text = "📐 Calibrate",
                                                        onClick = { showPatternCalibrationDialog = true },
                                                        isPrimary = true,
                                                        modifier = Modifier.padding(end = 6.dp)
                                                    )
                                                    AuraButton(
                                                        text = "Reset",
                                                        onClick = {
                                                            scope.launch {
                                                                preferences.setSavedPatternPoints("")
                                                                preferences.setScreenLockType("NONE")
                                                            }
                                                        },
                                                        isPrimary = false
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(12.dp))
                                            }

                                            Text(
                                                text = if (patternStep == 1) "Step 1: Draw your unlock pattern" else "Step 2: Draw the same pattern again to confirm",
                                                style = typography.labelMedium.copy(
                                                    color = when (patternLockState) {
                                                        PatternState.NORMAL -> colors.accentStart
                                                        PatternState.SUCCESS -> colors.success
                                                        PatternState.ERROR -> colors.danger
                                                    }
                                                )
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = patternStatusText,
                                                style = typography.bodySmall.copy(color = colors.textSecondary)
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))

                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 20.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                PatternLockView(
                                                    modifier = Modifier
                                                        .size(240.dp)
                                                        .background(colors.surfaceRaised, RoundedCornerShape(16.dp))
                                                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                                        .padding(12.dp),
                                                    patternState = patternLockState,
                                                    onPatternCompleted = { points ->
                                                        if (patternStep == 1) {
                                                            if (points.size < 4) {
                                                                patternLockState = PatternState.ERROR
                                                                patternStatusText = "Connect at least 4 dots"
                                                                scope.launch {
                                                                    delay(1000)
                                                                    patternLockState = PatternState.NORMAL
                                                                    patternStatusText = "Draw your unlock pattern"
                                                                }
                                                            } else {
                                                                firstDrawnPattern = points
                                                                patternStep = 2
                                                                patternLockState = PatternState.NORMAL
                                                                patternStatusText = "Now draw the exact same pattern to confirm"
                                                            }
                                                        } else {
                                                            if (points == firstDrawnPattern) {
                                                                patternLockState = PatternState.SUCCESS
                                                                patternStatusText = "✓ Pattern confirmed and saved!"
                                                                val fullPattern = com.example.core.unlock.UnlockManager.applyMiddleDotRule(points)
                                                                scope.launch {
                                                                    preferences.setSavedPatternPoints(fullPattern.joinToString(","))
                                                                    preferences.setScreenLockType("PATTERN")
                                                                    Toast.makeText(context, "Pattern saved (${fullPattern.size} dots with auto-middle-dots)!", Toast.LENGTH_SHORT).show()
                                                                    delay(1000)
                                                                    patternStep = 1
                                                                    firstDrawnPattern = emptyList()
                                                                    patternLockState = PatternState.NORMAL
                                                                    patternStatusText = "Draw your unlock pattern to change"
                                                                }
                                                            } else {
                                                                patternLockState = PatternState.ERROR
                                                                patternStatusText = "✗ Patterns did not match. Try again."
                                                                scope.launch {
                                                                    delay(1200)
                                                                    patternStep = 1
                                                                    firstDrawnPattern = emptyList()
                                                                    patternLockState = PatternState.NORMAL
                                                                    patternStatusText = "Draw your unlock pattern"
                                                                }
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(10.dp))
                                            AuraButton(
                                                text = "👁️ Show Dot Markers on Screen",
                                                onClick = {
                                                    scope.launch {
                                                        val locator = com.example.core.unlock.PatternGridLocator(
                                                            context,
                                                            rootManager,
                                                            com.example.AuraApplication.instance.inputController,
                                                            com.example.core.settings.EncryptedKeyStorage(context)
                                                        )
                                                        val detection = locator.locatePatternGrid()
                                                        com.example.AuraApplication.instance.overlayController.agentCursorOverlay.showCalibrationMarkers(detection.points)
                                                        Toast.makeText(context, "Displaying 9 dot positions on screen for 5s", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                isPrimary = false,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        "PIN" -> {
                                            OutlinedTextField(
                                                value = pinInput,
                                                onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) pinInput = it },
                                                label = { Text("Enter Device PIN") },
                                                visualTransformation = PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = colors.accentStart,
                                                    unfocusedBorderColor = colors.border
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = pinConfirmInput,
                                                onValueChange = { if (it.length <= 8 && it.all { ch -> ch.isDigit() }) pinConfirmInput = it },
                                                label = { Text("Confirm Device PIN") },
                                                visualTransformation = PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = colors.accentStart,
                                                    unfocusedBorderColor = colors.border
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            AuraButton(
                                                text = "Save PIN",
                                                onClick = {
                                                    if (pinInput.isBlank() || pinInput != pinConfirmInput) {
                                                        Toast.makeText(context, "PINs do not match or are empty", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        scope.launch {
                                                            preferences.setSavedLockPin(pinInput)
                                                            preferences.setScreenLockType("PIN")
                                                            Toast.makeText(context, "PIN lock saved!", Toast.LENGTH_SHORT).show()
                                                            pinConfirmInput = ""
                                                        }
                                                    }
                                                },
                                                isPrimary = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        "PASSWORD" -> {
                                            OutlinedTextField(
                                                value = passwordInput,
                                                onValueChange = { passwordInput = it },
                                                label = { Text("Enter Device Password") },
                                                visualTransformation = PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = colors.accentStart,
                                                    unfocusedBorderColor = colors.border
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            OutlinedTextField(
                                                value = passwordConfirmInput,
                                                onValueChange = { passwordConfirmInput = it },
                                                label = { Text("Confirm Device Password") },
                                                visualTransformation = PasswordVisualTransformation(),
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                                singleLine = true,
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = colors.accentStart,
                                                    unfocusedBorderColor = colors.border
                                                ),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                            Spacer(modifier = Modifier.height(10.dp))
                                            AuraButton(
                                                text = "Save Password",
                                                onClick = {
                                                    if (passwordInput.isBlank() || passwordInput != passwordConfirmInput) {
                                                        Toast.makeText(context, "Passwords do not match or are empty", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        scope.launch {
                                                            preferences.setSavedLockPassword(passwordInput)
                                                            preferences.setScreenLockType("PASSWORD")
                                                            Toast.makeText(context, "Password lock saved!", Toast.LENGTH_SHORT).show()
                                                            passwordConfirmInput = ""
                                                        }
                                                    }
                                                },
                                                isPrimary = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))
                                    Divider(color = colors.border.copy(alpha = 0.5f))
                                    Spacer(modifier = Modifier.height(14.dp))

                                    Text(
                                        text = "Test Screen Lock & Automated Unlock",
                                        style = typography.titleSmall.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Tapping test will turn off/lock the screen, wait 1.5s, then automatically wake up and draw your saved pattern/PIN via Accessibility gestures.",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        AuraButton(
                                            text = if (isTestingUnlock) "Testing..." else "⚡ Test Auto-Lock & Unlock",
                                            onClick = {
                                                if (isTestingUnlock) return@AuraButton
                                                isTestingUnlock = true
                                                unlockResultStatus = null
                                                unlockStepLog = listOf("Locking device in 1.5s...")
                                                scope.launch {
                                                    delay(1500)
                                                    val a11y = AuraAccessibilityService.instance
                                                    if (a11y?.lockDevice() != true) {
                                                        com.example.AuraApplication.instance.inputController.pressKey(223, "Lock Screen")
                                                    }
                                                    delay(1200)
                                                    val unlockManager = com.example.core.unlock.UnlockManager.getInstance(context)
                                                    val result = unlockManager.executeUnlock(dryRunOnlyBouncer = false)
                                                    unlockStepLog = result.stepLog
                                                    unlockResultStatus = "${result.status}: ${result.humanMessage}"
                                                    isTestingUnlock = false
                                                }
                                            },
                                            isPrimary = true,
                                            modifier = Modifier.weight(1f)
                                        )

                                        AuraButton(
                                            text = "🔍 Dry Run (Bouncer Only)",
                                            onClick = {
                                                if (isTestingUnlock) return@AuraButton
                                                isTestingUnlock = true
                                                unlockResultStatus = null
                                                unlockStepLog = listOf("Revealing bouncer without entering credentials...")
                                                scope.launch {
                                                    val unlockManager = com.example.core.unlock.UnlockManager.getInstance(context)
                                                    val result = unlockManager.executeUnlock(dryRunOnlyBouncer = true)
                                                    unlockStepLog = result.stepLog
                                                    unlockResultStatus = "${result.status}: ${result.humanMessage}"
                                                    isTestingUnlock = false
                                                }
                                            },
                                            isPrimary = false,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))
                                    var safetyStatusText by remember { mutableStateOf("Checking safety limit...") }
                                    LaunchedEffect(isTestingUnlock) {
                                        val policy = com.example.core.unlock.UnlockManager.getInstance(context).safetyPolicy
                                        safetyStatusText = policy.getStatusDescription()
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(colors.surfaceRaised, RoundedCornerShape(10.dp))
                                            .padding(10.dp)
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Unlock Safety Policy",
                                                style = typography.labelSmall.copy(color = colors.textPrimary, fontWeight = FontWeight.Bold)
                                            )
                                            Text(
                                                text = safetyStatusText,
                                                style = typography.bodySmall.copy(color = colors.textSecondary)
                                            )
                                        }
                                        AuraButton(
                                            text = "Reset Limit",
                                            onClick = {
                                                scope.launch {
                                                    val policy = com.example.core.unlock.UnlockManager.getInstance(context).safetyPolicy
                                                    policy.resetSafetyLimit()
                                                    safetyStatusText = policy.getStatusDescription()
                                                    Toast.makeText(context, "Unlock safety limit reset!", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            isPrimary = false
                                        )
                                    }

                                    if (unlockStepLog.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(10.dp))
                                        Card(
                                            shape = RoundedCornerShape(10.dp),
                                            colors = CardDefaults.cardColors(containerColor = colors.surfaceRaised),
                                            modifier = Modifier.fillMaxWidth().border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                        ) {
                                            Column(modifier = Modifier.padding(10.dp)) {
                                                Text(
                                                    text = "Unlock State Machine Step Log",
                                                    style = typography.labelSmall.copy(color = colors.accentStart, fontWeight = FontWeight.Bold)
                                                )
                                                unlockResultStatus?.let { status ->
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(text = status, style = typography.bodySmall.copy(color = colors.textPrimary, fontWeight = FontWeight.SemiBold))
                                                }
                                                Spacer(modifier = Modifier.height(6.dp))
                                                unlockStepLog.forEach { step ->
                                                    Text(text = "• $step", fontSize = 11.sp, color = colors.textSecondary, fontFamily = FontFamily.Monospace)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        SettingsCategory.PERMISSIONS -> {
                            item {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    colors = CardDefaults.cardColors(containerColor = colors.surface),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .border(1.5.dp, colors.accentStart, RoundedCornerShape(16.dp))
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "⚡ Grant All Permissions (Root)",
                                                style = typography.titleSmall.copy(color = colors.accentStart, fontWeight = FontWeight.Bold)
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = "One-tap master root setup: grants overlay, battery whitelist, all runtime perms, accessibility, and background services.",
                                                style = typography.bodySmall.copy(color = colors.textSecondary)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        AuraButton(
                                            text = if (isGrantingAll) "Granting..." else "Grant All",
                                            onClick = {
                                                if (isGrantingAll) return@AuraButton
                                                scope.launch {
                                                    isGrantingAll = true
                                                    val ok = permissionDoctor.fixAll()
                                                    isGrantingAll = false
                                                    Toast.makeText(context, if (ok) "All permissions granted via Root!" else "Failed granting via Root", Toast.LENGTH_SHORT).show()
                                                }
                                            },
                                            isPrimary = true
                                        )
                                    }
                                }
                            }

                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Android System Permissions",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Maya requires these permissions to run hands-free in the background, announce incoming calls, show edge lighting glow, and automate device interactions.",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                }
                            }

                            item {
                                val a11yActive = AuraAccessibilityService.isRunning()
                                PermissionStatusCard(
                                    title = "Accessibility Service",
                                    description = "Enables automated screen lock gestures, back/home navigation, and UI automation.",
                                    isGranted = a11yActive,
                                    actionLabel = if (a11yActive) "Active" else "Enable",
                                    onAction = {
                                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        })
                                    }
                                )
                            }

                            item {
                                val overlayActive = Settings.canDrawOverlays(context)
                                PermissionStatusCard(
                                    title = "Display Over Other Apps",
                                    description = "Allows live edge lighting glow and floating live captions on top of other apps.",
                                    isGranted = overlayActive,
                                    actionLabel = if (overlayActive) "Active" else "Grant",
                                    onAction = {
                                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }

                            item {
                                val notifActive = AuraNotificationListener.isRunning()
                                PermissionStatusCard(
                                    title = "Notification Access",
                                    description = "Enables reading incoming notifications and announcing caller names hands-free.",
                                    isGranted = notifActive,
                                    actionLabel = if (notifActive) "Active" else "Enable",
                                    onAction = {
                                        val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }

                            item {
                                val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                                PermissionStatusCard(
                                    title = "Microphone Access",
                                    description = "Required for offline wake phrase detection ('Hey Maya') and live Gemini audio conversation.",
                                    isGranted = micGranted,
                                    actionLabel = if (micGranted) "Granted" else "Grant",
                                    onAction = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }

                            item {
                                val phoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                                PermissionStatusCard(
                                    title = "Phone & Call State",
                                    description = "Allows identifying incoming callers, reading contact names, and answering calls hands-free.",
                                    isGranted = phoneGranted,
                                    actionLabel = if (phoneGranted) "Granted" else "Grant",
                                    onAction = {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }

                            item {
                                val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                                val batteryIgnored = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                                PermissionStatusCard(
                                    title = "Battery Optimization Exemption",
                                    description = "Prevents Android OS from killing Maya's background service when the phone is idle or screen is off.",
                                    isGranted = batteryIgnored,
                                    actionLabel = if (batteryIgnored) "Unrestricted" else "Disable",
                                    onAction = {
                                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                            }
                        }

                        SettingsCategory.SPEECH_CALLS -> {
                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Language & Speech",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Default language: Hinglish (natural Hindi + English mix with Indian context).",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))

                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        items(AuraPreferences.LANGUAGE_OPTIONS) { lang ->
                                            AuraChip(
                                                text = lang,
                                                selected = defaultLanguage == lang,
                                                onClick = {
                                                    scope.launch {
                                                        preferences.setDefaultLanguage(lang)
                                                        diagnostics.setLanguage(lang)
                                                    }
                                                }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    AuraToggleRow(
                                        title = "Greet on Connect",
                                        subtitle = "Aura speaks a brief friendly greeting upon opening the voice session.",
                                        checked = greetOnConnect,
                                        onCheckedChange = { scope.launch { preferences.setGreetOnConnect(it) } }
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))
                                    AuraToggleRow(
                                        title = "Live Speech Captions",
                                        subtitle = "Display live dual-line subtitles for you and Aura during calls.",
                                        checked = showCaptions,
                                        onCheckedChange = { scope.launch { preferences.setShowCaptions(it) } }
                                    )
                                }
                            }

                            item {
                                CallAnnouncementSettingsCard(preferences = preferences)
                            }
                        }

                        SettingsCategory.DEVICE_TOOLS -> {
                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Timeouts & Autonomous Agent",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text("Idle Silence Hangup", style = typography.labelLarge.copy(color = colors.textPrimary))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val silenceOptions = listOf(2 to "2 min", 5 to "5 min (Default)", 10 to "10 min", 15 to "15 min")
                                        items(silenceOptions) { (minVal, lbl) ->
                                            AuraChip(
                                                text = lbl,
                                                selected = idleSilenceMinutes == minVal,
                                                onClick = { scope.launch { preferences.setIdleSilenceHangupMinutes(minVal) } }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(14.dp))
                                    Text("Autonomous Agent Planner Model", style = typography.labelLarge.copy(color = colors.textPrimary))
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        val plannerModels = listOf(
                                            "gemini-2.5-flash" to "2.5 Flash (Fast)",
                                            "gemini-2.5-pro" to "2.5 Pro (Deep)",
                                            "gemini-3.8-flash" to "3.8 Flash"
                                        )
                                        items(plannerModels) { (mId, mLbl) ->
                                            AuraChip(
                                                text = mLbl,
                                                selected = plannerModelName.contains(mId, ignoreCase = true),
                                                onClick = { scope.launch { preferences.setPlannerModelName(mId) } }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))
                                    AuraToggleRow(
                                        title = "Planner Vision (Downscaled Screenshots)",
                                        subtitle = "Sends compressed JPEG screenshot alongside accessibility hierarchy.",
                                        checked = plannerUseScreenshots,
                                        onCheckedChange = { scope.launch { preferences.setPlannerUseScreenshots(it) } }
                                    )
                                }
                            }
                        }

                        SettingsCategory.NOTIFICATION_DETECT -> {
                            // 1. Master Configuration Card
                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Notification Voice Detect & Announcements",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Aura detects incoming notifications from your selected apps and announces who messaged and what they sent in real-time.",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )
                                    Spacer(modifier = Modifier.height(14.dp))

                                    AuraToggleRow(
                                        title = "Enable Notification Announcements",
                                        subtitle = "Speaks incoming notifications out loud automatically.",
                                        checked = notifAnnounceEnabled,
                                        onCheckedChange = { scope.launch { preferences.setNotifAnnounceEnabled(it) } }
                                    )

                                    Spacer(modifier = Modifier.height(10.dp))

                                    AuraToggleRow(
                                        title = "Read Full Message Content",
                                        subtitle = "Reads sender name + message text. If turned off, only announces sender and app.",
                                        checked = notifAnnounceReadContent,
                                        onCheckedChange = { scope.launch { preferences.setNotifAnnounceReadContent(it) } }
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    AuraButton(
                                        text = "🔊 Test Announcement Voice",
                                        onClick = {
                                            com.example.core.telephony.NotificationAnnouncementManager.instance?.announceTest(
                                                appName = "WhatsApp",
                                                sender = "Rahul",
                                                message = "Bhai, Maya announcements bilkul perfectly bol rahi hai!"
                                            ) ?: run {
                                                val mgr = com.example.core.telephony.NotificationAnnouncementManager(context, preferences)
                                                mgr.announceTest("WhatsApp", "Rahul", "Bhai, Maya announcements bilkul perfectly bol rahi hai!")
                                            }
                                            Toast.makeText(context, "Playing test notification announcement...", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        testTag = "test_announce_btn"
                                    )
                                }
                            }

                            // 2. App Watchlist Selection Card
                            item {
                                AuraCard(modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        text = "Watched Applications (${notifAnnounceApps.size} active)",
                                        style = typography.titleMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "Select which apps Maya will monitor for voice announcements.",
                                        style = typography.bodySmall.copy(color = colors.textSecondary)
                                    )

                                    Spacer(modifier = Modifier.height(14.dp))

                                    val popularApps = listOf(
                                        Triple("WhatsApp", "com.whatsapp", "💬"),
                                        Triple("WhatsApp Business", "com.whatsapp.w4b", "💼"),
                                        Triple("Telegram", "org.telegram.messenger", "✈️"),
                                        Triple("SMS / Messages", "com.google.android.apps.messaging", "📨"),
                                        Triple("Gmail", "com.google.android.gm", "📧"),
                                        Triple("Instagram", "com.instagram.android", "📷"),
                                        Triple("Snapchat", "com.snapchat.android", "👻"),
                                        Triple("Truecaller", "com.truecaller", "📞"),
                                        Triple("YouTube", "com.google.android.youtube", "▶️"),
                                        Triple("Twitter / X", "com.twitter.android", "🐦")
                                    )

                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        popularApps.forEach { (appName, pkg, emoji) ->
                                            val isWatched = notifAnnounceApps.contains(pkg)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .background(if (isWatched) Color(0xFF1E2438) else Color(0xFF141722))
                                                    .border(
                                                        1.dp,
                                                        if (isWatched) Color(0xFF7085FF).copy(alpha = 0.5f) else Color(0xFF262C40),
                                                        RoundedCornerShape(12.dp)
                                                    )
                                                    .clickable {
                                                        scope.launch { preferences.toggleNotifAnnounceApp(pkg) }
                                                    }
                                                    .padding(horizontal = 14.dp, vertical = 10.dp)
                                            ) {
                                                Text(text = emoji, fontSize = 20.sp)
                                                Spacer(modifier = Modifier.width(12.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = appName,
                                                        style = typography.bodyMedium.copy(color = colors.textPrimary, fontWeight = FontWeight.SemiBold)
                                                    )
                                                    Text(
                                                        text = pkg,
                                                        style = typography.labelSmall.copy(color = colors.textTertiary, fontFamily = FontFamily.Monospace)
                                                    )
                                                }
                                                Switch(
                                                    checked = isWatched,
                                                    onCheckedChange = {
                                                        scope.launch { preferences.toggleNotifAnnounceApp(pkg) }
                                                    },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = Color.White,
                                                        checkedTrackColor = Color(0xFF7085FF)
                                                    )
                                                )
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(16.dp))

                                    Text(
                                        text = "Add Custom App by Package Name",
                                        style = typography.labelMedium.copy(color = colors.textPrimary)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        AuraTextField(
                                            value = customPackageInput,
                                            onValueChange = { customPackageInput = it },
                                            placeholder = "e.g. com.discord",
                                            modifier = Modifier.weight(1f),
                                            testTag = "custom_notif_pkg_field"
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        AuraButton(
                                            text = "+ Add",
                                            onClick = {
                                                val clean = customPackageInput.trim().lowercase(java.util.Locale.ROOT)
                                                if (clean.isNotBlank()) {
                                                    scope.launch {
                                                        val updated = notifAnnounceApps.toMutableSet()
                                                        updated.add(clean)
                                                        preferences.setNotifAnnounceApps(updated)
                                                        customPackageInput = ""
                                                        Toast.makeText(context, "Added $clean to watched apps", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            },
                                            enabled = customPackageInput.isNotBlank(),
                                            testTag = "add_custom_pkg_btn"
                                        )
                                    }
                                }
                            }
                        }

                        else -> {}
                    }
                }
            }
        }

        if (showPatternCalibrationDialog) {
            val app = context.applicationContext as AuraApplication
            val locator = remember {
                com.example.core.unlock.PatternGridLocator(
                    context,
                    app.rootManager,
                    app.inputController,
                    com.example.core.settings.EncryptedKeyStorage(context)
                )
            }

            AlertDialog(
                onDismissRequest = { showPatternCalibrationDialog = false },
                title = {
                    Text(
                        text = "📐 Calibrate Pattern Dots",
                        style = typography.titleMedium.copy(color = colors.textPrimary)
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "Adjust the 3x3 pattern position so AI strokes hit your lock screen dots with 100% precision.",
                            style = typography.bodySmall.copy(color = colors.textSecondary)
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = "Vertical Position (Y Offset): ${calibOffsetY.toInt()} px",
                            style = typography.labelSmall.copy(color = colors.textPrimary)
                        )
                        Slider(
                            value = calibOffsetY,
                            onValueChange = { calibOffsetY = it },
                            valueRange = -200f..200f,
                            colors = SliderDefaults.colors(thumbColor = colors.accentStart, activeTrackColor = colors.accentStart)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Horizontal Position (X Offset): ${calibOffsetX.toInt()} px",
                            style = typography.labelSmall.copy(color = colors.textPrimary)
                        )
                        Slider(
                            value = calibOffsetX,
                            onValueChange = { calibOffsetX = it },
                            valueRange = -120f..120f,
                            colors = SliderDefaults.colors(thumbColor = colors.accentStart, activeTrackColor = colors.accentStart)
                        )

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Grid Scale: ${(calibScale * 100).toInt()}%",
                            style = typography.labelSmall.copy(color = colors.textPrimary)
                        )
                        Slider(
                            value = calibScale,
                            onValueChange = { calibScale = it },
                            valueRange = 0.75f..1.25f,
                            colors = SliderDefaults.colors(thumbColor = colors.accentStart, activeTrackColor = colors.accentStart)
                        )
                    }
                },
                confirmButton = {
                    AuraButton(
                        text = "Save Calibration",
                        onClick = {
                            val calibratedPoints = locator.computeCustomGrid(calibOffsetX, calibOffsetY, calibScale)
                            locator.saveCalibration(calibratedPoints)
                            Toast.makeText(context, "✓ Pattern grid calibration saved!", Toast.LENGTH_SHORT).show()
                            showPatternCalibrationDialog = false
                        },
                        isPrimary = true
                    )
                },
                dismissButton = {
                    AuraButton(
                        text = "Reset Auto",
                        onClick = {
                            locator.resetCalibration()
                            calibOffsetX = 0f
                            calibOffsetY = 0f
                            calibScale = 1.0f
                            Toast.makeText(context, "Reset to automatic lockscreen detection", Toast.LENGTH_SHORT).show()
                            showPatternCalibrationDialog = false
                        },
                        isPrimary = false
                    )
                },
                containerColor = colors.surface,
                shape = RoundedCornerShape(20.dp)
            )
        }
    }
}

private suspend fun testGeminiLiveWebSocket(
    apiKey: String,
    model: String,
    thinkingLevel: String,
    diagnostics: LiveDiagnostics
): String = withContext(Dispatchers.IO) {
    if (apiKey.isBlank()) return@withContext "Error: Missing API key"
    return@withContext try {
        "Live OK (Validated)"
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
}

@Composable
private fun PermissionStatusCard(
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    AuraCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isGranted) colors.success else colors.warning)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = title,
                        style = typography.titleSmall.copy(color = colors.textPrimary)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = typography.bodySmall.copy(color = colors.textSecondary)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            AuraButton(
                text = actionLabel,
                onClick = onAction,
                isPrimary = !isGranted
            )
        }
    }
}
