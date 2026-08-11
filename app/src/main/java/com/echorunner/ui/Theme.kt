package com.echorunner.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.echorunner.render.Palette

/**
 * UI palette. Deliberately re-exports [Palette] rather than defining its own colours, so the
 * menus and the rendered world cannot drift apart — one hue change updates both.
 */
object Ui {
    val void = Palette.void
    val voidLift = Palette.voidLift
    val accent = Palette.player          // cyan: the player's colour, used for focus/primary
    val echo = Palette.echo(0)           // magenta: anything about Echoes
    val warn = Palette.hazard
    val ok = Palette.plateOn
    val exit = Palette.exit

    val text = Color(0xFFE8EEF6)
    val textDim = Color(0xFF8494A8)
    val textFaint = Color(0xFF4C5A6E)
    val hairline = Color(0xFF1B2534)
    val panel = Color(0xE60A0F1A)
}

/**
 * Type scale. Monospace throughout: this is a game about replaying a recording, and the
 * technical register suits it — but more practically, a timer that changes every frame must not
 * reflow, and only a fixed-advance face guarantees that.
 */
private val GameTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        fontSize = 46.sp, letterSpacing = 6.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, letterSpacing = 3.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium,
        fontSize = 15.sp, letterSpacing = 2.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 0.5.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
        fontSize = 14.sp, letterSpacing = 3.sp, textAlign = TextAlign.Center,
    ),
)

@Composable
fun EchoRunnerTheme(content: @Composable () -> Unit) {
    // The game is always dark; the system setting is read only so the call site is honest
    // about ignoring it rather than silently overriding.
    @Suppress("UNUSED_VARIABLE") val systemDark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ui.accent,
            secondary = Ui.echo,
            background = Ui.void,
            surface = Ui.voidLift,
            onPrimary = Ui.void,
            onBackground = Ui.text,
            onSurface = Ui.text,
            error = Ui.warn,
        ),
        typography = GameTypography,
        content = content,
    )
}
