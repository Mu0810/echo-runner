package com.echorunner.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echorunner.core.GameConstants
import com.echorunner.core.GameMode
import com.echorunner.core.GamePhase
import com.echorunner.core.GameSettings
import com.echorunner.core.LevelSpec
import com.echorunner.core.Rating
import com.echorunner.engine.StepAccumulator
import com.echorunner.render.GameRenderer
import com.echorunner.render.Palette
import kotlin.math.ceil

/**
 * The playable screen: fixed-step simulation, interpolated rendering, HUD and phase overlays.
 */
@Composable
fun GameScreen(
    vm: GameViewModel,
    play: Screen.Play,
    settings: GameSettings,
) {
    val renderer = remember { GameRenderer() }
    val level = vm.currentLevel

    // Frame counter the canvas subscribes to; bumping it is what schedules the next draw.
    var frame by remember(play) { mutableLongStateOf(0L) }
    var alpha by remember(play) { mutableFloatStateOf(0f) }
    var clock by remember(play) { mutableFloatStateOf(0f) }

    // HUD state, updated only when the *displayed* value changes so text recomposes ~10x/s
    // instead of 60x/s. Laying out text every frame is wasted work on a mid-range phone.
    var tenths by remember(play) { mutableIntStateOf(-1) }
    var echoCount by remember(play) { mutableIntStateOf(0) }
    var phase by remember(play) { mutableStateOf(GamePhase.INTRO) }
    var paused by remember(play) { mutableStateOf(false) }

    LaunchedEffect(play) {
        val acc = StepAccumulator()
        var last = 0L
        var advanced = false
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val elapsed = ((now - last).toDouble() / 1_000_000_000.0).toFloat()
                    val steps = acc.stepsFor(elapsed)
                    repeat(steps) { vm.engine.step(vm.input.sample()) }
                    alpha = acc.alpha
                    clock += elapsed
                    frame = now

                    val w = vm.engine.world
                    if (w != null) {
                        val t = ceil(w.secondsLeft * 10f).toInt()
                        if (t != tenths) tenths = t
                        if (w.echoCount != echoCount) echoCount = w.echoCount
                        if (w.phase != phase) phase = w.phase
                        if (w.paused != paused) paused = w.paused
                    }

                    // Advance exactly once, after the completion hold has played out.
                    if (!advanced && vm.shouldAdvance()) {
                        advanced = true
                        vm.onLevelCompleted()
                    }
                }
                last = now
            }
        }
    }

    // Android back: pause first, and only leave from an already-paused game. Dropping straight
    // out of a run on a stray back press would discard every Echo earned.
    BackHandler(enabled = true) {
        val w = vm.engine.world
        when {
            w == null -> vm.openMenu()
            w.paused -> vm.openMenu()
            else -> vm.togglePause()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.void)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            frame // subscribe: forces a redraw every frame
            val world = vm.engine.world ?: return@Canvas
            renderer.draw(this, world, alpha, settings, clock)
        }

        if (!GameEngineOverlay.blocksControls(phase, paused)) {
            TouchControls(vm.input, settings)
        }

        Hud(
            level = level,
            mode = play.mode,
            secondsLeft = tenths / 10f,
            echoCount = echoCount,
            maxEchoes = level?.maxEchoes ?: 0,
            endlessRoom = if (play.mode == GameMode.ENDLESS) vm.endlessRoom else null,
            endlessScore = if (play.mode == GameMode.ENDLESS) vm.endlessScore else null,
            canBank = phase == GamePhase.PLAYING && !paused && echoCount < (level?.maxEchoes ?: 0),
            onBank = vm::bankTake,
            onPause = vm::togglePause,
            onRestart = vm::restartTake,
        )

        when {
            paused -> PauseOverlay(
                level = level,
                canSkip = vm.canSkipCurrent(),
                onResume = vm::togglePause,
                onRestartTake = { vm.togglePause(); vm.restartTake() },
                onRestartLevel = { vm.togglePause(); vm.restartLevel() },
                onSkip = vm::skipCurrent,
                onQuit = vm::openMenu,
            )
            phase == GamePhase.INTRO -> IntroOverlay(level, vm::dismissIntro)
            phase == GamePhase.TAKE_TRANSITION -> Banner(
                title = "ECHO RECORDED",
                subtitle = "$echoCount of ${level?.maxEchoes ?: 0} — it will repeat everything you just did",
                tint = Palette.echo(maxOf(0, echoCount - 1)),
            )
            phase == GamePhase.DEAD -> Banner(
                title = "LOST",
                subtitle = "The take is discarded. Your Echoes remain.",
                tint = Ui.warn,
            )
            phase == GamePhase.OUT_OF_ECHOES -> Banner(
                title = "OUT OF TAKES",
                subtitle = "Restarting the room",
                tint = Ui.textDim,
            )
            phase == GamePhase.COMPLETE -> {
                // Computed live rather than read from vm.lastStars: that field is written when
                // the host advances, which happens AFTER this hold elapses, so it would still be
                // zero here. The inputs are the same, so the two never disagree.
                val w = vm.engine.world
                val takes = (w?.takeIndex ?: 0) + 1
                val stars = Rating.starsFor(takes, level?.parTakes ?: 1, w?.deaths ?: 0)
                CompleteBanner(
                    levelName = level?.name ?: "",
                    stars = stars,
                    takes = takes,
                    par = (level?.parTakes ?: 1).coerceAtLeast(1),
                    deaths = w?.deaths ?: 0,
                )
            }
        }
    }
}

/** Small helper so the overlay/controls rule lives in one place. */
private object GameEngineOverlay {
    fun blocksControls(phase: GamePhase, paused: Boolean): Boolean =
        paused || when (phase) {
            GamePhase.PLAYING, GamePhase.TAKE_TRANSITION -> false
            else -> true
        }
}

@Composable
private fun Hud(
    level: LevelSpec?,
    mode: GameMode,
    secondsLeft: Float,
    echoCount: Int,
    maxEchoes: Int,
    endlessRoom: Int?,
    endlessScore: Int?,
    canBank: Boolean,
    onBank: () -> Unit,
    onPause: () -> Unit,
    onRestart: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column {
            Text(
                text = level?.name?.uppercase() ?: "",
                color = Ui.textDim,
                fontSize = 11.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = when (mode) {
                    GameMode.ENDLESS -> "ROOM ${endlessRoom ?: 1}   ${endlessScore ?: 0} PTS"
                    GameMode.CHALLENGE -> "CHALLENGE"
                    GameMode.STORY -> level?.id?.let { "SECTOR $it" } ?: ""
                },
                color = Ui.textFaint,
                fontSize = 10.sp,
                letterSpacing = 1.5.sp,
            )
        }

        Spacer(Modifier.width(20.dp))

        // Take timer. Turns amber under 5s: the exact moment the player must decide whether
        // this take is worth banking.
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val urgent = secondsLeft <= 5f
            Text(
                text = String.format("%04.1f", secondsLeft),
                color = if (urgent) Ui.warn else Ui.text,
                fontSize = 26.sp,
                letterSpacing = 2.sp,
                fontWeight = FontWeight.Bold,
            )
            Text("MEMORY", color = Ui.textFaint, fontSize = 9.sp, letterSpacing = 3.sp)
        }

        Spacer(Modifier.width(20.dp))

        // Echo pips: filled = recorded and replaying, hollow = still available.
        Column {
            Text("ECHOES", color = Ui.textFaint, fontSize = 9.sp, letterSpacing = 3.sp)
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (maxEchoes == 0) {
                    Text("SINGLE TAKE", color = Ui.textDim, fontSize = 10.sp, letterSpacing = 1.sp)
                } else {
                    repeat(maxEchoes) { i ->
                        val filled = i < echoCount
                        Box(
                            Modifier
                                .size(width = 16.dp, height = 5.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    if (filled) Palette.echo(i) else Ui.hairline
                                )
                        )
                    }
                }
            }
        }

        Spacer(Modifier.weight(1f))

        // BANK ends the take immediately and freezes what you did into an Echo. Without it a
        // player who finished their part of the choreography in eight seconds still had to stand
        // around for the remaining twenty-two, which is dead time in a game entirely about
        // deliberate performances. Hidden when the level grants no Echoes (nothing to bank into)
        // or the budget is spent, since it would do nothing.
        if (canBank) {
            TextButtonChip(label = "BANK", tint = Palette.echo(echoCount), onClick = onBank)
            Spacer(Modifier.width(8.dp))
        }
        HudButton("RESTART", onRestart)
        Spacer(Modifier.width(8.dp))
        HudButton("PAUSE", onPause)
    }
}

@Composable
private fun HudButton(label: String, onClick: () -> Unit) {
    TextButtonChip(label = label, tint = Ui.textDim, onClick = onClick)
}

@Composable
private fun IntroOverlay(level: LevelSpec?, onDismiss: () -> Unit) {
    var index by remember(level?.id) { mutableIntStateOf(0) }
    val lines = level?.dialogue ?: emptyList()
    val line = lines.getOrNull(index)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF205060B))
            .clickableNoIndication {
                if (index < lines.lastIndex) index++ else onDismiss()
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.padding(horizontal = 64.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = level?.name?.uppercase() ?: "",
                color = Ui.textFaint,
                fontSize = 11.sp,
                letterSpacing = 5.sp,
            )
            Spacer(Modifier.height(26.dp))
            if (line != null) {
                if (line.speaker.isNotBlank()) {
                    Text(
                        text = line.speaker.uppercase(),
                        color = Ui.echo,
                        fontSize = 11.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(
                    text = line.text,
                    color = Ui.text,
                    fontSize = 17.sp,
                    letterSpacing = 0.5.sp,
                )
            }
            Spacer(Modifier.height(34.dp))
            Text(
                text = if (index < lines.lastIndex) "TAP TO CONTINUE" else "TAP TO BEGIN",
                color = Ui.textFaint,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
            )
        }
    }
}

/**
 * Level-complete card: the rating, then the numbers that produced it.
 *
 * Stars appear one at a time rather than all at once — the stagger is what makes a three-star
 * solve feel earned instead of merely reported.
 */
@Composable
private fun CompleteBanner(
    levelName: String,
    stars: Int,
    takes: Int,
    par: Int,
    deaths: Int,
) {
    // Drives the stagger. One pass only; the banner is torn down when the hold ends.
    var shown by remember(levelName, stars) { mutableIntStateOf(0) }
    LaunchedEffect(levelName, stars) {
        shown = 0
        repeat(Rating.MAX_STARS) {
            kotlinx.coroutines.delay(190)
            shown += 1
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CLEAR",
                color = Ui.exit,
                fontSize = 30.sp,
                letterSpacing = 8.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = levelName.uppercase(),
                color = Ui.textFaint,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
            )

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                repeat(Rating.MAX_STARS) { i ->
                    val earned = i < stars
                    val visible = i < shown
                    Text(
                        text = if (earned) "★" else "☆",
                        color = when {
                            !visible -> Color.Transparent
                            earned -> Ui.exit
                            else -> Ui.textFaint
                        },
                        fontSize = 30.sp,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                // Takes are the score, so lead with them and show par for context.
                text = "$takes ${if (takes == 1) "TAKE" else "TAKES"}   ·   PAR $par" +
                    if (deaths > 0) "   ·   $deaths LOST" else "   ·   NO LOSSES",
                color = Ui.textDim,
                fontSize = 11.sp,
                letterSpacing = 1.5.sp,
            )
            if (stars < Rating.MAX_STARS) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (takes > par) "SOLVE IT IN $par TO GO CLEAN"
                    else "SOLVE IT WITHOUT LOSING A TAKE",
                    color = Ui.textFaint,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp,
                )
            }
        }
    }
}

@Composable
private fun Banner(title: String, subtitle: String, tint: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                color = tint,
                fontSize = 32.sp,
                letterSpacing = 8.sp,
                fontWeight = FontWeight.Bold,
            )
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(subtitle, color = Ui.textDim, fontSize = 12.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun PauseOverlay(
    level: LevelSpec?,
    canSkip: Boolean,
    onResume: () -> Unit,
    onRestartTake: () -> Unit,
    onRestartLevel: () -> Unit,
    onSkip: () -> Unit,
    onQuit: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF205060B)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("PAUSED", color = Ui.text, fontSize = 26.sp, letterSpacing = 8.sp,
                fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(
                text = level?.name?.uppercase() ?: "",
                color = Ui.textFaint, fontSize = 10.sp, letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(28.dp))
            MenuAction("RESUME", Ui.accent, onResume)
            MenuAction("RESTART TAKE", Ui.textDim, onRestartTake)
            MenuAction("RESTART ROOM", Ui.textDim, onRestartLevel)
            // Appears only after the room has been tried several times. A puzzle that has beaten
            // you should not also confiscate the rest of the game.
            if (canSkip) MenuAction("SKIP THIS ROOM", Ui.echo, onSkip)
            MenuAction("LEAVE", Ui.warn, onQuit)

            // One-line nudge only. The level's `hint` field holds the full take-by-take
            // solution and is design documentation — printing it here would spoil the puzzle.
            val goal = level?.let { spec ->
                when {
                    spec.maxEchoes == 0 -> "No Echoes here. One take, start to finish."
                    spec.plates.isNotEmpty() && spec.doors.isNotEmpty() ->
                        "Something has to stay behind and hold it down."
                    spec.switches.isNotEmpty() ->
                        "The switch will not wait for you. Send someone earlier."
                    spec.walkers.isNotEmpty() -> "It follows whoever it noticed first."
                    spec.movers.isNotEmpty() -> "The lift needs weight it cannot carry."
                    else -> "Use the ones you already recorded."
                }
            }
            if (goal != null) {
                Spacer(Modifier.height(26.dp))
                Text(goal, color = Ui.textFaint, fontSize = 11.sp, letterSpacing = 0.5.sp)
            }
        }
    }
}

/** Shared vertical menu entry, used by the pause overlay and the menu screens. */
@Composable
fun MenuAction(label: String, tint: Color, onClick: () -> Unit) {
    Box(Modifier.padding(vertical = 6.dp)) {
        TextButtonChip(label = label, tint = tint, wide = true, onClick = onClick)
    }
}
