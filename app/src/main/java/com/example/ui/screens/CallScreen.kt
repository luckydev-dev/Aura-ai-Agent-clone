package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.core.live.CaptionState
import com.example.core.live.LiveConnectionState
import com.example.core.tools.ActionFeedItem
import com.example.core.tools.ActionStatus
import com.example.service.AuraForegroundService
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun CallScreen(
    onNavigateToActivity: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToTools: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSetup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val isCallActive by AuraForegroundService.isCallActive.collectAsState()
    val service = AuraForegroundService.currentService

    // Permission launcher for microphone
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            try {
                AuraForegroundService.startCall(context)
            } catch (e: Exception) {
                Log.e("Aura", "Failed to start call after permission granted: ${e.message}", e)
            }
        } else {
            Toast.makeText(context, "Microphone permission is required for Aura voice call", Toast.LENGTH_LONG).show()
        }
    }

    fun startCallWithPermission() {
        try {
            val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                AuraForegroundService.startCall(context)
            } else {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } catch (e: Exception) {
            Log.e("Aura", "Failed to launch permission/call: ${e.message}", e)
            Toast.makeText(context, "Unable to start call: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val connectionState by produceState<LiveConnectionState>(initialValue = LiveConnectionState.Disconnected, key1 = service) {
        val flow = runCatching { service?.liveClient?.connectionState }.getOrNull()
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = LiveConnectionState.Disconnected
        }
    }

    val micAmp by produceState<Float>(initialValue = 0f, key1 = service) {
        val flow = runCatching { service?.audioStreamer?.micAmplitude }.getOrNull()
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = 0f
        }
    }

    val speakerAmp by produceState<Float>(initialValue = 0f, key1 = service) {
        val flow = runCatching { service?.audioPlayer?.speakerAmplitude }.getOrNull()
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = 0f
        }
    }

    val isMuted by produceState<Boolean>(initialValue = false, key1 = service) {
        val flow = runCatching { service?.audioStreamer?.isMuted }.getOrNull()
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = false
        }
    }

    val isAuraActing by produceState<Boolean>(initialValue = false, key1 = service) {
        val flow = runCatching { service?.liveClient?.isAuraActing }.getOrNull()
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = false
        }
    }

    val isAuraThinking by produceState<Boolean>(initialValue = false, key1 = service) {
        val flow = runCatching { service?.liveClient?.isAuraThinking }.getOrNull()
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = false
        }
    }

    // Live Dual-Line Caption State from CaptionStateMachine
    val captionState by produceState<CaptionState>(initialValue = CaptionState(), key1 = service) {
        val flow = runCatching { service?.liveClient?.captionState }.getOrNull()
        if (flow != null) {
            flow.collect { value = it }
        } else {
            value = CaptionState()
        }
    }

    val showCaptionsPref by produceState<Boolean>(initialValue = true, key1 = service) {
        val prefFlow = runCatching { service?.preferences?.showCaptions }.getOrNull()
        if (prefFlow != null) {
            prefFlow.collect { value = it }
        } else {
            value = true
        }
    }

    val isCallUiActive = isCallActive && connectionState !is LiveConnectionState.Error

    var isSpeakerOn by remember { mutableStateOf(true) }

    LaunchedEffect(service) {
        if (service != null) {
            val prefSpeaker = service.preferences.useSpeaker.first()
            isSpeakerOn = prefSpeaker
        }
    }

    // Action feed items
    val actionFeed = remember { mutableStateListOf<ActionFeedItem>() }
    LaunchedEffect(service) {
        service?.toolRegistry?.actionFeed?.collect { item ->
            val index = actionFeed.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                actionFeed[index] = item
            } else {
                actionFeed.add(0, item)
            }
        }
    }

    // Call duration timer
    var callSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isCallUiActive) {
        if (isCallUiActive) {
            callSeconds = 0
            while (true) {
                delay(1000)
                callSeconds++
            }
        } else {
            callSeconds = 0
        }
    }

    // Determine current Orb state
    val orbState = when {
        isMuted -> OrbState.IDLE
        connectionState is LiveConnectionState.Error -> OrbState.ERROR
        connectionState is LiveConnectionState.Reconnecting -> OrbState.RECONNECTING
        connectionState is LiveConnectionState.Connecting -> OrbState.IDLE
        connectionState !is LiveConnectionState.Connected || !isCallUiActive -> OrbState.IDLE
        isAuraActing -> OrbState.ACTING
        speakerAmp > 0.04f -> OrbState.SPEAKING
        isAuraThinking -> OrbState.THINKING
        micAmp > 0.04f -> OrbState.LISTENING
        else -> OrbState.IDLE
    }

    val activeAmplitude = if (orbState == OrbState.SPEAKING) speakerAmp else micAmp

    // Keyboard chat sheet toggle
    var showKeyboardInput by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }

    // Caption scroll state for auto-scrolling
    val captionScrollState = rememberScrollState()
    LaunchedEffect(captionState.userText, captionState.auraText) {
        if (captionScrollState.maxValue > 0) {
            captionScrollState.animateScrollTo(captionScrollState.maxValue)
        }
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
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Bar with Timer and Navigation Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Connection/Call status indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surfaceRaised)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isMuted -> colors.warning
                                    connectionState is LiveConnectionState.Connected -> colors.success
                                    connectionState is LiveConnectionState.Connecting || connectionState is LiveConnectionState.Reconnecting -> colors.warning
                                    connectionState is LiveConnectionState.Error -> colors.danger
                                    else -> colors.textTertiary
                                }
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = if (isCallUiActive) "%02d:%02d".format(callSeconds / 60, callSeconds % 60) else "Aura Ready",
                        style = typography.labelSmall.copy(color = colors.textPrimary)
                    )
                }

                // Quick access navigation buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AuraIconButton(
                        icon = AuraIcons.History,
                        onClick = onNavigateToActivity,
                        contentDescription = "Activity",
                        size = 38.dp,
                        testTag = "nav_activity_btn"
                    )
                    AuraIconButton(
                        icon = AuraIcons.Memory,
                        onClick = onNavigateToMemory,
                        contentDescription = "Memory",
                        size = 38.dp,
                        testTag = "nav_memory_btn"
                    )
                    AuraIconButton(
                        icon = AuraIcons.Tools,
                        onClick = onNavigateToTools,
                        contentDescription = "Tools",
                        size = 38.dp,
                        testTag = "nav_tools_btn"
                    )
                    AuraIconButton(
                        icon = AuraIcons.Settings,
                        onClick = onNavigateToSettings,
                        contentDescription = "Settings",
                        size = 38.dp,
                        testTag = "nav_settings_btn"
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.08f))

            // Reconnecting banner
            AnimatedVisibility(
                visible = connectionState is LiveConnectionState.Reconnecting,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                val recon = connectionState as? LiveConnectionState.Reconnecting
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.warning.copy(alpha = 0.2f))
                        .border(1.dp, colors.warning.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(colors.warning)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BasicText(
                        text = "Reconnecting (Attempt ${recon?.attempt ?: 1}): ${recon?.reason?.ifBlank { "Re-establishing audio" } ?: "Reconnecting"}...",
                        style = typography.labelSmall.copy(color = colors.warning)
                    )
                }
            }

            // The Hero Aura Living Orb
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (!isCallUiActive) {
                            startCallWithPermission()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AuraOrb(
                    state = orbState,
                    amplitude = activeAmplitude,
                    size = 250.dp
                )
            }

            // Orb State Status Label
            val stateLabel = when {
                isMuted -> "Microphone Muted (Tap mic to unmute)"
                connectionState is LiveConnectionState.Connecting -> "Connecting..."
                connectionState is LiveConnectionState.Reconnecting -> "Reconnecting..."
                connectionState is LiveConnectionState.Connected -> when {
                    speakerAmp > 0.04f -> "Speaking..."
                    isAuraActing -> "Operating device..."
                    isAuraThinking -> "Thinking..."
                    micAmp > 0.04f -> "Hearing you..."
                    else -> "Listening for you..."
                }
                connectionState is LiveConnectionState.Ended -> "Session Ended"
                connectionState is LiveConnectionState.Error -> "Connection error"
                else -> if (isCallUiActive) "Connecting..." else "Tap orb or button to start call"
            }

            Spacer(modifier = Modifier.height(14.dp))
            BasicText(
                text = stateLabel,
                style = typography.labelLarge.copy(
                    color = when {
                        isMuted -> colors.warning
                        orbState == OrbState.ERROR -> colors.danger
                        connectionState is LiveConnectionState.Ended -> colors.textSecondary
                        else -> colors.accentStart
                    }
                ),
                maxLines = 1
            )

            Spacer(modifier = Modifier.weight(0.08f))

            // Realtime Captions OR Full Scrollable Error Card OR Session Ended Card
            if (connectionState is LiveConnectionState.Ended) {
                val endedReason = (connectionState as LiveConnectionState.Ended).reason
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface.copy(alpha = 0.85f))
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    BasicText(
                        text = "Session Completed",
                        style = typography.titleSmall.copy(color = colors.textPrimary)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    BasicText(
                        text = endedReason.ifBlank { "Session closed peacefully." },
                        style = typography.bodySmall.copy(color = colors.textSecondary)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AuraButton(
                        text = "Start New Call",
                        onClick = { startCallWithPermission() },
                        icon = AuraIcons.Refresh,
                        isPrimary = true,
                        testTag = "start_new_call_btn"
                    )
                }
            } else if (connectionState is LiveConnectionState.Error) {
                val errorMsg = (connectionState as LiveConnectionState.Error).message
                val hint = getErrorHint(errorMsg)
                val fullCopyText = if (hint != null) "$errorMsg\n\n$hint" else errorMsg

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface.copy(alpha = 0.85f))
                        .border(1.dp, colors.danger.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(colors.danger)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        BasicText(
                            text = "Connection Error (Server Message)",
                            style = typography.labelLarge.copy(color = colors.danger)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp, max = 160.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Column {
                            BasicText(
                                text = errorMsg,
                                style = typography.bodySmall.copy(color = colors.textPrimary)
                            )
                            if (hint != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                BasicText(
                                    text = hint,
                                    style = typography.labelSmall.copy(color = colors.warning)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AuraButton(
                            text = "Copy Error",
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                val clip = android.content.ClipData.newPlainText("Aura Error", fullCopyText)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Full error copied to clipboard", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.weight(1f),
                            icon = AuraIcons.Copy,
                            isPrimary = false,
                            testTag = "copy_error_btn"
                        )
                        AuraButton(
                            text = "Retry",
                            onClick = { startCallWithPermission() },
                            modifier = Modifier.weight(1f),
                            icon = AuraIcons.Refresh,
                            isPrimary = true,
                            testTag = "retry_call_btn"
                        )
                    }
                }
            } else if (showCaptionsPref) {
                // Section 1: BOTH lines visible at once, auto-scrolling to bottom, max ~35% height
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 70.dp, max = 150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface.copy(alpha = 0.65f))
                        .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .verticalScroll(captionScrollState)
                ) {
                    val hasUserText = captionState.userText.isNotBlank()
                    val hasAuraText = captionState.auraText.isNotBlank()

                    if (!hasUserText && !hasAuraText) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            BasicText(
                                text = if (isCallUiActive) "Speak naturally in Hinglish. You can interrupt Aura anytime." else "Say 'Hey Aura' or tap the mic to start...",
                                style = typography.bodySmall.copy(color = colors.textTertiary)
                            )
                        }
                    } else {
                        // User transcription line (secondary color)
                        if (hasUserText) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + expandVertically()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                    BasicText(
                                        text = "You",
                                        style = typography.labelSmall.copy(color = colors.textTertiary)
                                    )
                                    BasicText(
                                        text = captionState.userText,
                                        style = typography.bodyMedium.copy(color = colors.textSecondary)
                                    )
                                }
                            }
                        }

                        // Aura response line (primary color)
                        if (hasAuraText) {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn() + expandVertically()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    BasicText(
                                        text = "Aura",
                                        style = typography.labelSmall.copy(color = colors.accentStart)
                                    )
                                    BasicText(
                                        text = captionState.auraText,
                                        style = typography.bodyLarge.copy(color = colors.textPrimary)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Realtime Action Feed Chips
            if (actionFeed.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(actionFeed, key = { it.id }) { item ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.surfaceRaised)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (item.status) {
                                            ActionStatus.RUNNING -> colors.warning
                                            ActionStatus.SUCCESS -> colors.success
                                            ActionStatus.FAILED -> colors.danger
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            BasicText(
                                text = item.description,
                                style = typography.labelSmall.copy(color = colors.textSecondary)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            val quickPrompts = remember {
                listOf(
                    "What's on my screen?",
                    "Turn off Wi-Fi",
                    "Take screenshot",
                    "Check WhatsApp",
                    "List my saved routines"
                )
            }
            if (isCallUiActive) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(quickPrompts) { prompt ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(colors.surfaceRaised)
                                .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                                .clickable {
                                    service?.liveClient?.sendTextMessage(prompt)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            BasicText(
                                text = prompt,
                                style = typography.labelSmall.copy(color = colors.accentStart)
                            )
                        }
                    }
                }
            }

            // Bottom Call Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Emergency Stop Button
                AuraIconButton(
                    icon = AuraIcons.Stop,
                    onClick = {
                        service?.agentLoop?.stop()
                        service?.audioPlayer?.flushAndInterrupt()
                        if (isCallActive) {
                            AuraForegroundService.endCall(context)
                        }
                    },
                    contentDescription = "Stop speech and active actions",
                    backgroundColor = colors.danger.copy(alpha = 0.2f),
                    tint = colors.danger,
                    size = 52.dp,
                    testTag = "emergency_stop_btn"
                )

                // Mute / Hold-to-Talk Button
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (isMuted) colors.warning.copy(alpha = 0.25f) else colors.surfaceRaised)
                        .border(1.dp, if (isMuted) colors.warning else colors.border, CircleShape)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val startTime = System.currentTimeMillis()
                                var isHolding = false

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.any { it.pressed }
                                    if (!pressed) {
                                        val duration = System.currentTimeMillis() - startTime
                                        if (isHolding) {
                                            service?.audioStreamer?.setMuted(true)
                                        } else if (duration < 350) {
                                            val cur = service?.audioStreamer?.isMuted?.value ?: false
                                            service?.audioStreamer?.setMuted(!cur)
                                        }
                                        break
                                    } else if (!isHolding && System.currentTimeMillis() - startTime >= 350) {
                                        isHolding = true
                                        service?.audioStreamer?.setMuted(false)
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = if (isMuted) AuraIcons.MicOff else AuraIcons.Mic,
                        contentDescription = if (isMuted) "Unmute / Hold to Talk" else "Mute / Hold to Talk",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(if (isMuted) colors.warning else colors.textPrimary),
                        modifier = Modifier.size(26.dp)
                    )
                }

                // Master Call Orb Button
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(CircleShape)
                        .background(if (isCallUiActive) colors.danger else colors.accentStart)
                        .clickable {
                            if (isCallUiActive) {
                                AuraForegroundService.endCall(context)
                            } else {
                                startCallWithPermission()
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    androidx.compose.foundation.Image(
                        imageVector = if (isCallUiActive) AuraIcons.CallEnd else AuraIcons.Mic,
                        contentDescription = if (isCallUiActive) "End Call" else "Start Call",
                        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White),
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Keyboard Input Toggle
                AuraIconButton(
                    icon = AuraIcons.Keyboard,
                    onClick = { showKeyboardInput = !showKeyboardInput },
                    contentDescription = "Type to Aura",
                    size = 52.dp,
                    testTag = "keyboard_toggle_btn"
                )

                // Speaker / Routing Toggle Button
                AuraIconButton(
                    icon = AuraIcons.Speaker,
                    onClick = {
                        val newSpeakerState = !isSpeakerOn
                        isSpeakerOn = newSpeakerState
                        service?.audioPlayer?.setSpeakerphoneOn(newSpeakerState)
                        coroutineScope.launch {
                            service?.preferences?.setUseSpeaker(newSpeakerState)
                        }
                    },
                    contentDescription = if (isSpeakerOn) "Switch to Earpiece" else "Switch to Speaker",
                    backgroundColor = if (isSpeakerOn) colors.surfaceRaised else colors.accentStart.copy(alpha = 0.2f),
                    tint = if (isSpeakerOn) colors.textPrimary else colors.accentStart,
                    size = 52.dp,
                    testTag = "speaker_toggle_btn"
                )
            }
        }

        // Slide-up Keyboard Chat Sheet
        AnimatedVisibility(
            visible = showKeyboardInput,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .navigationBarsPadding()
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(colors.surfaceRaised)
                    .border(1.dp, colors.border, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicText(
                        text = "Type to Aura",
                        style = typography.titleMedium.copy(color = colors.textPrimary)
                    )
                    AuraIconButton(
                        icon = AuraIcons.Close,
                        onClick = { showKeyboardInput = false },
                        contentDescription = "Close text input",
                        size = 32.dp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AuraTextField(
                        value = keyboardText,
                        onValueChange = { keyboardText = it },
                        modifier = Modifier.weight(1f),
                        placeholder = "Ask Aura something or give a command...",
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = {
                                if (keyboardText.isNotBlank()) {
                                    val msg = keyboardText.trim()
                                    keyboardText = ""
                                    showKeyboardInput = false
                                    service?.liveClient?.sendTextMessage(msg)
                                }
                            }
                        ),
                        testTag = "keyboard_input_field"
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    AuraIconButton(
                        icon = AuraIcons.Send,
                        onClick = {
                            if (keyboardText.isNotBlank()) {
                                val msg = keyboardText.trim()
                                keyboardText = ""
                                showKeyboardInput = false
                                service?.liveClient?.sendTextMessage(msg)
                            }
                        },
                        contentDescription = "Send Message",
                        backgroundColor = colors.accentStart,
                        tint = Color.White,
                        size = 48.dp,
                        testTag = "keyboard_send_btn"
                    )
                }
            }
        }
    }
}

private fun getErrorHint(errorMessage: String): String? {
    return when {
        errorMessage.contains("1007") || errorMessage.contains("Thinking level must be specified", ignoreCase = true) || errorMessage.contains("invalid argument", ignoreCase = true) -> {
            when {
                errorMessage.contains("thinking level", ignoreCase = true) || errorMessage.contains("thinkingLevel", ignoreCase = true) ->
                    "Hint (Code 1007): Model requires a thinking level. Choose LOW, MEDIUM, or HIGH in Settings."
                errorMessage.contains("realtime_input_config", ignoreCase = true) ->
                    "Hint (Code 1007): Server rejected realtime_input_config. Aura will auto-drop this field."
                errorMessage.contains("tools", ignoreCase = true) ->
                    "Hint (Code 1007): Tool configuration was rejected by server."
                else ->
                    "Hint (Code 1007): A setup argument was rejected by the server. Check your model profile in Settings."
            }
        }
        errorMessage.contains("1008") || errorMessage.contains("not found", ignoreCase = true) -> {
            "Hint (Code 1008): Model not found. Go to Settings and tap 'Fetch available Live models' to pick a valid model."
        }
        errorMessage.contains("401") || errorMessage.contains("403") || errorMessage.contains("ACCESS_TOKEN_TYPE_UNSUPPORTED", ignoreCase = true) || errorMessage.contains("API key not valid", ignoreCase = true) -> {
            "Hint: Authentication error. If key starts with 'AQ.', use a standard API key starting with 'AIza...' in Settings."
        }
        errorMessage.contains("Setup timeout", ignoreCase = true) -> {
            "Hint: Connection timed out waiting for server setupComplete. Check internet connectivity and model availability."
        }
        else -> null
    }
}
