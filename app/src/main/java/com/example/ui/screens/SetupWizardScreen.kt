package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.service.AuraAccessibilityService
import com.example.service.AuraNotificationListener
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.launch

data class SetupCheckItem(
    val id: String,
    val title: String,
    val description: String,
    val isGranted: Boolean,
    val isService: Boolean = false
)

@Composable
fun SetupWizardScreen(
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val rootManager = remember { RootManager(context) }
    val preferences = remember { AuraPreferences(context) }

    var isChecking by remember { mutableStateOf(false) }
    var diagnosticInfo by remember { mutableStateOf("Checking root environment...") }
    var checkItems by remember { mutableStateOf<List<SetupCheckItem>>(emptyList()) }

    fun refreshChecks() {
        scope.launch {
            isChecking = true
            diagnosticInfo = rootManager.getDiagnosticInfo()

            val isRoot = rootManager.isRootAvailable()

            val hasAudio = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasContacts = context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasPhone = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasSms = context.checkSelfPermission(android.Manifest.permission.SEND_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED

            val hasOverlay = Settings.canDrawOverlays(context)
            val hasAccessibility = AuraAccessibilityService.isRunning()
            val hasNotification = AuraNotificationListener.isRunning()

            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val isBatteryWhitelisted = pm.isIgnoringBatteryOptimizations(context.packageName)

            val secureSettingsCheck = rootManager.execute("pm check-permission android.permission.WRITE_SECURE_SETTINGS ${context.packageName}")
            val hasSecureSettings = secureSettingsCheck.stdout.contains("granted") || secureSettingsCheck.success

            checkItems = listOf(
                SetupCheckItem("root", "Root Shell Access", diagnosticInfo, isRoot),
                SetupCheckItem("audio", "Audio & Microphone", "Allows real-time conversation and wake phrase", hasAudio),
                SetupCheckItem("contacts", "Contacts & Phone Calling", "Allows voice calling and messaging contacts", hasContacts && hasPhone),
                SetupCheckItem("sms", "SMS & Text Messaging", "Allows sending and reading text messages", hasSms),
                SetupCheckItem("accessibility", "Accessibility Service", if (hasAccessibility) "Bound and active for screen automation" else "NOT BOUND - required for device automation", hasAccessibility, isService = true),
                SetupCheckItem("notification", "Notification Listener", if (hasNotification) "Bound and listening to notifications" else "NOT BOUND - required for reading/replying", hasNotification, isService = true),
                SetupCheckItem("overlay", "Draw Over Other Apps", "Allows floating mini orb over other apps", hasOverlay),
                SetupCheckItem("battery", "Battery Exemption", "Ensures voice calls are not killed in background", isBatteryWhitelisted),
                SetupCheckItem("secure", "Secure System Settings", "Allows toggling Wi-Fi, Bluetooth, Airplane mode", hasSecureSettings)
            )
            isChecking = false
        }
    }

    fun openPermissionSettings(itemId: String) {
        val intent = when (itemId) {
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notification" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "overlay" -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            "battery" -> Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
            "root" -> {
                scope.launch {
                    rootManager.requestRoot()
                    refreshChecks()
                }
                return
            }
            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        }.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("Aura", "Failed to start settings activity for $itemId", e)
            val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(appDetails)
            } catch (ex: Exception) {
                Log.e("Aura", "Failed to start application details activity", ex)
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshChecks()
    }

    val allGranted = checkItems.isNotEmpty() && checkItems.all { it.isGranted }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
    ) {
        AuraTopBar(
            title = "Setup & Permissions",
            onBack = onFinish
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Diagnostic Card
        AuraCard(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (diagnosticInfo.contains("detected") || diagnosticInfo.contains("available")) colors.success else colors.warning)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    androidx.compose.foundation.text.BasicText(
                        text = "Device Environment",
                        style = typography.labelLarge.copy(color = colors.textPrimary)
                    )
                    androidx.compose.foundation.text.BasicText(
                        text = diagnosticInfo,
                        style = typography.bodySmall.copy(color = colors.textSecondary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        androidx.compose.foundation.text.BasicText(
            text = "System Capability Checklist",
            style = typography.titleMedium.copy(color = colors.textPrimary)
        )
        Spacer(modifier = Modifier.height(6.dp))
        androidx.compose.foundation.text.BasicText(
            text = "Tap any item to grant permission directly, or tap 'Grant Everything' for root auto-configuration. Unbound services show red.",
            style = typography.bodySmall.copy(color = colors.textSecondary)
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Checklist
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(checkItems, key = { it.id }) { item ->
                val isRedUnbound = !item.isGranted && item.isService
                val cardBorder = if (isRedUnbound) colors.danger else colors.border

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(colors.surface)
                        .border(1.dp, cardBorder, RoundedCornerShape(16.dp))
                        .clickable { openPermissionSettings(item.id) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    item.isGranted -> colors.success.copy(alpha = 0.2f)
                                    isRedUnbound -> colors.danger.copy(alpha = 0.2f)
                                    else -> colors.surfaceRaised
                                }
                            )
                            .border(
                                1.dp,
                                when {
                                    item.isGranted -> colors.success
                                    isRedUnbound -> colors.danger
                                    else -> colors.border
                                },
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (item.isGranted) {
                            androidx.compose.foundation.Image(
                                imageVector = AuraIcons.Check,
                                contentDescription = "Granted",
                                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(colors.success),
                                modifier = Modifier.size(14.dp)
                            )
                        } else if (isRedUnbound) {
                            androidx.compose.foundation.text.BasicText(
                                text = "!",
                                style = typography.labelSmall.copy(color = colors.danger)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        androidx.compose.foundation.text.BasicText(
                            text = item.title,
                            style = typography.labelLarge.copy(
                                color = if (isRedUnbound) colors.danger else colors.textPrimary
                            )
                        )
                        androidx.compose.foundation.text.BasicText(
                            text = item.description,
                            style = typography.bodySmall.copy(
                                color = if (isRedUnbound) colors.danger.copy(alpha = 0.8f) else colors.textSecondary
                            )
                        )
                    }

                    AuraBadge(
                        text = when {
                            item.isGranted -> "Verified"
                            isRedUnbound -> "Not Bound - Retry"
                            else -> "Tap to Grant"
                        },
                        isSuccess = item.isGranted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action Buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AuraButton(
                text = if (isChecking) "Checking..." else "Grant Everything (Root)",
                onClick = {
                    scope.launch {
                        isChecking = true
                        rootManager.requestRoot()
                        rootManager.autoGrantEverything()
                        refreshChecks()
                    }
                },
                modifier = Modifier.weight(1f),
                isPrimary = true,
                testTag = "grant_everything_btn"
            )

            AuraButton(
                text = "Retry / Refresh",
                onClick = { refreshChecks() },
                modifier = Modifier.weight(0.6f),
                isPrimary = false,
                testTag = "refresh_btn"
            )
        }
    }
}
