package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.ui.components.*
import com.example.ui.theme.AuraTheme

data class OnboardingSlide(
    val title: String,
    val subtitle: String,
    val description: String,
    val state: OrbState
)

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    val slides = listOf(
        OnboardingSlide(
            title = "Aura Voice Agent",
            subtitle = "Conversations that feel like real calls",
            description = "Experience zero-delay, bidirectional voice calls with Gemini Live. Speak naturally, interrupt anytime, and hear responses in a warm human voice.",
            state = OrbState.SPEAKING
        ),
        OnboardingSlide(
            title = "Root-Powered Automation",
            subtitle = "Your phone actually does what you say",
            description = "Aura operates your device directly. Open apps, send WhatsApp messages, change settings, tap anywhere on screen, and run shell tasks as root.",
            state = OrbState.ACTING
        ),
        OnboardingSlide(
            title = "Zero Telemetry, Total Privacy",
            subtitle = "Your data stays on your device",
            description = "Action logs, memory facts, and aliases are stored in your local encrypted Room database. Nothing ever leaves your device except your active live session.",
            state = OrbState.IDLE
        )
    )

    var currentSlideIndex by remember { mutableStateOf(0) }
    val currentSlide = slides[currentSlideIndex]

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Skip Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            AuraButton(
                text = "Skip",
                onClick = onComplete,
                isPrimary = false,
                testTag = "skip_onboarding_btn"
            )
        }

        Spacer(modifier = Modifier.weight(0.2f))

        // Orb illustration for the slide
        AuraOrb(
            state = currentSlide.state,
            amplitude = 0.5f,
            size = 220.dp
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Slide Content
        androidx.compose.foundation.text.BasicText(
            text = currentSlide.title,
            style = typography.heroTitle.copy(color = colors.textPrimary)
        )
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.foundation.text.BasicText(
            text = currentSlide.subtitle,
            style = typography.titleMedium.copy(color = colors.accentStart)
        )
        Spacer(modifier = Modifier.height(14.dp))
        androidx.compose.foundation.text.BasicText(
            text = currentSlide.description,
            style = typography.bodyLarge.copy(color = colors.textSecondary)
        )

        Spacer(modifier = Modifier.weight(0.3f))

        // Slide indicator dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 24.dp)
        ) {
            slides.indices.forEach { index ->
                Box(
                    modifier = Modifier
                        .size(if (index == currentSlideIndex) 24.dp else 8.dp, 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (index == currentSlideIndex) colors.accentStart else colors.surfaceRaised)
                )
            }
        }

        // Next / Get Started Button
        AuraButton(
            text = if (currentSlideIndex == slides.size - 1) "Get Started" else "Next",
            onClick = {
                if (currentSlideIndex < slides.size - 1) {
                    currentSlideIndex++
                } else {
                    onComplete()
                }
            },
            modifier = Modifier.fillMaxWidth(),
            isPrimary = true,
            testTag = "next_onboarding_btn"
        )
    }
}
