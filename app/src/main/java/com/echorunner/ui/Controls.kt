package com.echorunner.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echorunner.core.GameSettings
import com.echorunner.core.InputBuffer
import com.echorunner.core.clamp
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Touch controls.
 *
 * Both halves write straight into [InputBuffer], which the game loop samples once per tick.
 * Nothing here is Compose state that the simulation reads — a control surface that recomposed
 * the world every time a thumb moved would cost more than the simulation itself.
 *
 * Hit areas are deliberately larger than what is drawn: the visible ring is a target hint, and
 * a thumb that lands 10 dp outside it still counts. Missing a jump because of a few pixels is
 * the single worst thing a touch platformer can do.
 *
 * ## Why the actions form an arc rather than a row
 *
 * The world is 16:9 and the renderer fits it to screen *height*, so on a taller phone
 * (19.5:9, 20:9) there is an unused letterbox column down each side. A horizontal row of three
 * buttons is ~250 dp wide and spills far past that column into the middle of the play field —
 * on level 1-1 the jump button sat directly over the exit portal. Arranging them as an arc
 * hugging the corner cuts the horizontal spread by about a third and keeps the mass in the
 * screen corner, which is the least play-critical region of a side-scrolling level.
 */
@Composable
fun TouchControls(
    input: InputBuffer,
    settings: GameSettings,
    modifier: Modifier = Modifier,
) {
    val scale = settings.controlScale.coerceIn(0.75f, 1.4f)
    Box(modifier.fillMaxSize()) {
        val stick: @Composable () -> Unit = {
            Joystick(
                input = input,
                diameter = 150.dp * scale,
                // Sit as close to the screen edge as comfort allows, so as much of the control
                // as possible lives in the letterbox column rather than over the level.
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 18.dp),
            )
        }
        val actions: @Composable () -> Unit = {
            ActionCluster(
                input = input,
                scale = scale,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 18.dp),
            )
        }

        // Left-handed play mirrors the two halves rather than flipping individual controls, so
        // the muscle memory of "stick on one side, actions on the other" survives the switch.
        Box(Modifier.align(Alignment.BottomStart)) {
            if (settings.leftHanded) actions() else stick()
        }
        Box(Modifier.align(Alignment.BottomEnd)) {
            if (settings.leftHanded) stick() else actions()
        }
    }
}

/**
 * Analog stick. Only the horizontal axis is wired to movement — this is a platformer with a
 * dedicated jump button, so a vertical axis would add a way to fight the jump rather than a
 * capability. The knob still shows vertical travel so the control does not feel broken.
 */
@Composable
private fun Joystick(
    input: InputBuffer,
    diameter: Dp,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    val radiusPx = with(density) { diameter.toPx() } * 0.5f

    Box(
        modifier
            .size(diameter)
            .semantics { contentDescription = "Movement joystick" }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        active = true
                        val centre = Offset(size.width * 0.5f, size.height * 0.5f)

                        fun apply(p: Offset) {
                            val d = p - centre
                            val len = hypot(d.x, d.y)
                            val limit = radiusPx * 0.78f
                            knob = if (len > limit && len > 0f) d * (limit / len) else d
                            // Dead zone stops a resting thumb from creeping the player sideways.
                            val nx = knob.x / limit
                            input.moveX = if (abs(nx) < 0.16f) 0f else clamp(nx * 1.18f, -1f, 1f)
                        }

                        apply(down.position)
                        var tracking = true
                        while (tracking) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) {
                                tracking = false
                            } else {
                                apply(change.position)
                                change.consume()
                            }
                        }
                        active = false
                        knob = Offset.Zero
                        input.moveX = 0f
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            val r = size.minDimension * 0.5f
            // Outer ring: a hint, not a boundary.
            drawCircle(
                color = Ui.hairline.copy(alpha = if (active) 0.95f else 0.55f),
                radius = r * 0.92f, center = c, style = Stroke(width = 1.6f),
            )
            drawCircle(color = Ui.void.copy(alpha = 0.45f), radius = r * 0.92f, center = c)
            // Knob.
            val kc = c + knob
            drawCircle(
                color = Ui.accent.copy(alpha = if (active) 0.20f else 0.10f),
                radius = r * 0.40f, center = kc,
            )
            drawCircle(
                color = Ui.accent.copy(alpha = if (active) 0.95f else 0.5f),
                radius = r * 0.34f, center = kc, style = Stroke(width = 1.8f),
            )
        }
    }
}

/**
 * The three action buttons, arranged as an arc tucked into the screen corner.
 *
 * Sizes are deliberately unequal: JUMP is the most-used and least-forgiving input, so it gets
 * the largest target and the corner position a thumb rests on naturally. DASH sits inboard of
 * it, and USE — pressed while standing still, never mid-platforming — is smallest and highest.
 */
@Composable
private fun ActionCluster(
    input: InputBuffer,
    scale: Float,
    modifier: Modifier = Modifier,
) {
    val jump = 96.dp * scale
    val dash = 74.dp * scale
    val use = 62.dp * scale
    val gap = 9.dp * scale
    val inset = 11.dp * scale

    // Geometry is written as explicit non-overlapping offsets rather than proportions, because
    // proportional guesses produced overlapping hit areas — two buttons sharing pixels means a
    // thumb aimed at DASH sometimes fires JUMP, which is worse than any layout compromise.
    // JUMP owns the corner; DASH sits directly above it; USE tucks inboard of DASH. That keeps
    // the horizontal spread to roughly one and a half buttons instead of three in a row.
    val clusterW = dash + inset + gap + use
    val clusterH = jump + gap + dash

    Box(modifier.size(width = clusterW, height = clusterH)) {
        HoldButton(
            label = "USE",
            diameter = use,
            tint = Ui.ok,
            description = "Interact",
            onPress = input::pressInteract,
            onRelease = input::releaseInteract,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = -(dash + inset + gap), y = -(jump + gap - 4.dp * scale)),
        )
        HoldButton(
            label = "DASH",
            diameter = dash,
            tint = Ui.echo,
            description = "Dash",
            onPress = input::pressDash,
            onRelease = input::releaseDash,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = -inset, y = -(jump + gap)),
        )
        HoldButton(
            label = "JUMP",
            diameter = jump,
            tint = Ui.accent,
            description = "Jump",
            onPress = input::pressJump,
            onRelease = input::releaseJump,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

/**
 * A press-and-hold button.
 *
 * Fires on **down**, not on click: waiting for a click to resolve would add a frame of latency
 * to every jump, and jump height here depends on how long the button is held.
 */
@Composable
private fun HoldButton(
    label: String,
    diameter: Dp,
    tint: Color,
    description: String,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()

    Box(
        modifier
            .size(diameter)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        pressed = true
                        onPress()
                        var tracking = true
                        while (tracking) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) tracking = false
                            else change.consume()
                        }
                        pressed = false
                        onRelease()
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 0.5f)
            val r = size.minDimension * 0.5f
            // Idle alpha is kept low on purpose: these sit over playable ground, so they must
            // read as an overlay a thumb can find, not as scenery competing with the level.
            drawCircle(color = Ui.void.copy(alpha = if (pressed) 0.55f else 0.34f), radius = r, center = c)
            drawCircle(
                color = tint.copy(alpha = if (pressed) 0.26f else 0.06f),
                radius = r * 0.94f, center = c,
            )
            drawCircle(
                color = tint.copy(alpha = if (pressed) 1f else 0.44f),
                radius = r * 0.94f, center = c,
                style = Stroke(width = if (pressed) 2.4f else 1.5f),
            )
            val layout = measurer.measure(
                text = label,
                style = TextStyle(
                    color = tint.copy(alpha = if (pressed) 1f else 0.62f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = (r * 0.30f).coerceIn(9f, 15f).sp,
                    letterSpacing = 1.sp,
                ),
            )
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(
                    c.x - layout.size.width * 0.5f,
                    c.y - layout.size.height * 0.5f,
                ),
            )
        }
    }
}
