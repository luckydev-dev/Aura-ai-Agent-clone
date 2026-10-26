package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.core.root.RootManager
import com.example.core.settings.AuraPreferences
import com.example.ui.components.CrashReportCard
import com.example.ui.components.SafeModeBanner
import com.example.service.AuraForegroundService
import com.example.ui.screens.*
import com.example.ui.theme.AuraTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ScreenRoute {
    ONBOARDING,
    SETUP_WIZARD,
    CALL,
    ACTIVITY,
    MEMORY,
    TOOLS,
    SETTINGS
}

class MainActivity : ComponentActivity() {

    private lateinit var preferences: AuraPreferences
    private lateinit var rootManager: RootManager

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        preferences = AuraPreferences(this)
        rootManager = RootManager(this)

        val autoStartCall = intent?.getBooleanExtra("action_auto_start_call", false) ?: false
        val sharedText = if (intent?.action == android.content.Intent.ACTION_SEND) {
            intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
                ?: intent?.clipData?.getItemAt(0)?.text?.toString()
        } else null

        if (autoStartCall || sharedText != null) {
            AuraForegroundService.startCall(this)
            if (sharedText != null) {
                lifecycleScope.launch {
                    kotlinx.coroutines.delay(1200L)
                    AuraForegroundService.currentService?.liveClient?.sendTextMessage(
                        "User shared this text with Aura: \"$sharedText\". Please acknowledge and ask how to help with it."
                    )
                }
            }
        }

        setContent {
            val themeMode by preferences.themeMode.collectAsState(initial = "dark")
            val onboardingDone by preferences.isOnboardingCompleted.collectAsState(initial = true)

            var currentScreen by remember { mutableStateOf<ScreenRoute>(ScreenRoute.CALL) }
            val screenStack = remember { mutableStateListOf<ScreenRoute>() }

            fun navigateTo(screen: ScreenRoute) {
                screenStack.add(currentScreen)
                currentScreen = screen
            }

            fun navigateBack() {
                if (screenStack.isNotEmpty()) {
                    currentScreen = screenStack.removeLast()
                } else {
                    currentScreen = ScreenRoute.CALL
                }
            }

            // Auto-route on initial launch if first run
            LaunchedEffect(onboardingDone) {
                if (!onboardingDone) {
                    currentScreen = ScreenRoute.ONBOARDING
                } else {
                    // Check if root is available, otherwise suggest setup
                    val isRoot = rootManager.isRootAvailable()
                    if (!isRoot) {
                        currentScreen = ScreenRoute.SETUP_WIZARD
                    }
                }
            }

            BackHandler(enabled = currentScreen != ScreenRoute.CALL) {
                navigateBack()
            }

            AuraTheme(themeMode = themeMode) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AuraTheme.colors.background)
                ) {
                    val crashReport = AuraApplication.lastCrashReport.value
                    val isSafeMode = AuraApplication.isSafeMode.value

                    when (currentScreen) {
                        ScreenRoute.ONBOARDING -> {
                            OnboardingScreen(
                                onComplete = {
                                    lifecycleScope.launch {
                                        preferences.setOnboardingCompleted(true)
                                        currentScreen = ScreenRoute.SETUP_WIZARD
                                    }
                                }
                            )
                        }
                        ScreenRoute.SETUP_WIZARD -> {
                            SetupWizardScreen(
                                onFinish = { currentScreen = ScreenRoute.CALL }
                            )
                        }
                        ScreenRoute.CALL -> {
                            CallScreen(
                                onNavigateToActivity = { navigateTo(ScreenRoute.ACTIVITY) },
                                onNavigateToMemory = { navigateTo(ScreenRoute.MEMORY) },
                                onNavigateToTools = { navigateTo(ScreenRoute.TOOLS) },
                                onNavigateToSettings = { navigateTo(ScreenRoute.SETTINGS) },
                                onNavigateToSetup = { navigateTo(ScreenRoute.SETUP_WIZARD) }
                            )
                        }
                        ScreenRoute.ACTIVITY -> {
                            ActivityHistoryScreen(
                                onBack = { navigateBack() }
                            )
                        }
                        ScreenRoute.MEMORY -> {
                            MemoryScreen(
                                onBack = { navigateBack() }
                            )
                        }
                        ScreenRoute.TOOLS -> {
                            ToolsScreen(
                                onBack = { navigateBack() }
                            )
                        }
                        ScreenRoute.SETTINGS -> {
                            SettingsScreen(
                                onBack = { navigateBack() },
                                onNavigateToSetup = { navigateTo(ScreenRoute.SETUP_WIZARD) }
                            )
                        }
                    }

                    if (crashReport != null || isSafeMode) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (isSafeMode) {
                                SafeModeBanner(
                                    onResetSafeMode = {
                                        AuraApplication.instance.disableSafeMode()
                                    }
                                )
                            }
                            if (crashReport != null) {
                                CrashReportCard(
                                    crashTrace = crashReport,
                                    onDismiss = {
                                        AuraApplication.instance.dismissLastCrashReport()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
