package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AuraTheme

@Composable
fun AuraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    isPrimary: Boolean = true,
    isDanger: Boolean = false,
    enabled: Boolean = true,
    testTag: String = ""
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.95f else 1f, label = "buttonScale")

    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val shapes = AuraTheme.shapes

    val bgBrush = when {
        !enabled -> SolidColor(colors.surfaceRaised.copy(alpha = 0.5f))
        isDanger -> SolidColor(colors.danger)
        isPrimary -> colors.accentGradient
        else -> SolidColor(colors.surfaceRaised)
    }

    val contentColor = when {
        !enabled -> colors.textTertiary
        isPrimary || isDanger -> Color.White
        else -> colors.textPrimary
    }

    Box(
        modifier = modifier
            .scale(scale)
            .clip(shapes.pill)
            .background(bgBrush)
            .then(
                if (!isPrimary && !isDanger && enabled) {
                    Modifier.border(1.dp, colors.border, shapes.pill)
                } else Modifier
            )
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 24.dp, vertical = 14.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                androidx.compose.foundation.Image(
                    imageVector = icon,
                    contentDescription = null,
                    colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(contentColor),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            androidx.compose.foundation.text.BasicText(
                text = text,
                style = typography.labelLarge.copy(color = contentColor),
                maxLines = 1
            )
        }
    }
}

@Composable
fun AuraIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    backgroundColor: Color? = null,
    tint: Color? = null,
    size: Dp = 48.dp,
    testTag: String = ""
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.90f else 1f, label = "iconButtonScale")

    val colors = AuraTheme.colors
    val effectiveBg = backgroundColor ?: colors.surfaceRaised
    val effectiveTint = tint ?: colors.textPrimary

    Box(
        modifier = modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(effectiveBg)
            .border(1.dp, colors.border, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            imageVector = icon,
            contentDescription = contentDescription,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(effectiveTint),
            modifier = Modifier.size(size * 0.5f)
        )
    }
}

@Composable
fun AuraToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    val colors = AuraTheme.colors
    val trackWidth = 52.dp
    val trackHeight = 30.dp
    val thumbSize = 24.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - 3.dp else 3.dp,
        animationSpec = tween(250),
        label = "thumbOffset"
    )

    val trackBg by animateColorAsState(
        targetValue = if (checked) colors.accentMiddle else colors.surfaceRaised,
        animationSpec = tween(250),
        label = "trackBg"
    )

    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(15.dp))
            .background(trackBg)
            .border(1.dp, if (checked) colors.accentMiddle else colors.border, RoundedCornerShape(15.dp))
            .clickable { onCheckedChange(!checked) }
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
fun AuraToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = ""
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            androidx.compose.foundation.text.BasicText(
                text = title,
                style = typography.titleMedium.copy(color = colors.textPrimary)
            )
            Spacer(modifier = Modifier.height(4.dp))
            androidx.compose.foundation.text.BasicText(
                text = subtitle,
                style = typography.bodySmall.copy(color = colors.textSecondary)
            )
        }
        AuraToggle(
            checked = checked,
            onCheckedChange = onCheckedChange,
            testTag = testTag
        )
    }
}

@Composable
fun AuraCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = AuraTheme.colors
    val shapes = AuraTheme.shapes
    val bg = backgroundColor ?: colors.surface

    val boxModifier = modifier
        .clip(shapes.medium)
        .background(bg)
        .border(1.dp, colors.border, shapes.medium)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(16.dp)

    Column(modifier = boxModifier, content = content)
}

@Composable
fun AuraChip(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    leadingIcon: ImageVector? = null,
    testTag: String = ""
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val shapes = AuraTheme.shapes

    val bg = if (selected) colors.accentStart.copy(alpha = 0.2f) else colors.surfaceRaised
    val borderCol = if (selected) colors.accentStart else colors.border
    val textCol = if (selected) colors.accentStart else colors.textSecondary

    Row(
        modifier = modifier
            .clip(shapes.pill)
            .background(bg)
            .border(1.dp, borderCol, shapes.pill)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            androidx.compose.foundation.Image(
                imageVector = leadingIcon,
                contentDescription = null,
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(textCol),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        androidx.compose.foundation.text.BasicText(
            text = text,
            style = typography.labelSmall.copy(color = textCol)
        )
    }
}

@Composable
fun AuraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    testTag: String = ""
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val shapes = AuraTheme.shapes

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(shapes.medium)
            .background(colors.surfaceRaised)
            .border(1.dp, colors.border, shapes.medium)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        textStyle = typography.bodyLarge.copy(color = colors.textPrimary),
        cursorBrush = SolidColor(colors.accentMiddle),
        singleLine = singleLine,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        decorationBox = { innerTextField ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    androidx.compose.foundation.text.BasicText(
                        text = placeholder,
                        style = typography.bodyLarge.copy(color = colors.textTertiary)
                    )
                }
                innerTextField()
            }
        }
    )
}

@Composable
fun AuraBadge(
    text: String,
    isSuccess: Boolean = true,
    modifier: Modifier = Modifier
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val shapes = AuraTheme.shapes

    val col = if (isSuccess) colors.success else colors.danger
    val bg = col.copy(alpha = 0.15f)

    Box(
        modifier = modifier
            .clip(shapes.pill)
            .background(bg)
            .border(1.dp, col.copy(alpha = 0.4f), shapes.pill)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.text.BasicText(
            text = text,
            style = typography.labelSmall.copy(color = col)
        )
    }
}

@Composable
fun AuraTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            AuraIconButton(
                icon = AuraIcons.ArrowBack,
                onClick = onBack,
                contentDescription = "Back",
                size = 40.dp
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        androidx.compose.foundation.text.BasicText(
            text = title,
            style = typography.titleLarge.copy(color = colors.textPrimary),
            modifier = Modifier.weight(1f)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            content = actions
        )
    }
}

@Composable
fun AuraDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isDangerous: Boolean = false
) {
    val colors = AuraTheme.colors
    val typography = AuraTheme.typography
    val shapes = AuraTheme.shapes

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.7f))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .clip(shapes.large)
                .background(colors.surface)
                .border(1.dp, colors.border, shapes.large)
                .clickable(enabled = false) {}
                .padding(24.dp)
        ) {
            androidx.compose.foundation.text.BasicText(
                text = title,
                style = typography.titleMedium.copy(color = colors.textPrimary)
            )
            Spacer(modifier = Modifier.height(12.dp))
            androidx.compose.foundation.text.BasicText(
                text = message,
                style = typography.bodyMedium.copy(color = colors.textSecondary)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                AuraButton(
                    text = cancelText,
                    onClick = onCancel,
                    isPrimary = false
                )
                Spacer(modifier = Modifier.width(12.dp))
                AuraButton(
                    text = confirmText,
                    onClick = onConfirm,
                    isPrimary = !isDangerous,
                    isDanger = isDangerous
                )
            }
        }
    }
}
