package com.echorunner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The game's only button.
 *
 * Ripple is suppressed and replaced with a scale-and-brighten press response: a Material ripple
 * on a dark neon surface reads as a grey smear, and the whole art direction depends on light
 * coming only from thin bright edges.
 *
 * Minimum height is 44 dp so every control clears the recommended touch-target size.
 */
@Composable
fun TextButtonChip(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    enabled: Boolean = true,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.965f else 1f,
        animationSpec = tween(90),
        label = "chipScale",
    )
    val shade = if (enabled) tint else Ui.textFaint

    Box(
        modifier
            .scale(scale)
            .widthIn(min = if (wide) 232.dp else 0.dp)
            .defaultMinSize(minHeight = 44.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (pressed) shade.copy(alpha = 0.16f) else Color.Transparent)
            .border(
                BorderStroke(
                    width = if (pressed) 1.6.dp else 1.dp,
                    color = shade.copy(alpha = if (pressed) 1f else 0.42f),
                ),
                RoundedCornerShape(3.dp),
            )
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        contentAlignment = if (wide) Alignment.CenterStart else Alignment.Center,
    ) {
        Text(
            text = if (trailing != null) "$label   $trailing" else label,
            color = shade.copy(alpha = if (enabled) 1f else 0.55f),
            fontSize = 12.sp,
            letterSpacing = 3.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** Tap target with no visual feedback, for full-screen "tap to continue" surfaces. */
@Composable
fun Modifier.clickableNoIndication(onClick: () -> Unit): Modifier {
    val interaction = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}

/** Section heading used across the menu screens. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Ui.textFaint,
        fontSize = 10.sp,
        letterSpacing = 4.sp,
        fontWeight = FontWeight.Bold,
        modifier = modifier,
    )
}
