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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.echorunner.core.GameSettings
import com.echorunner.core.Rating
import com.echorunner.mode.Challenges
import com.echorunner.render.Palette
import kotlin.math.sin

/**
 * Shared shell for every menu: the same drifting void the game is rendered against, so moving
 * from a menu into a level does not feel like changing applications.
 */
@Composable
private fun MenuShell(content: @Composable () -> Unit) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) t += ((now - last).toDouble() / 1_000_000_000.0).toFloat()
                last = now
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.void)
    ) {
        // Two slow washes and a horizon line. Deliberately almost nothing: the menus should
        // read as an empty facility, not a title screen.
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            drawCircle(
                color = Palette.fog.copy(alpha = 0.22f),
                radius = h * 0.75f,
                center = Offset(w * (0.22f + 0.03f * sin(t * 0.11f)), h * 0.32f),
            )
            drawCircle(
                color = Palette.echo(0).copy(alpha = 0.05f),
                radius = h * 0.55f,
                center = Offset(w * (0.82f + 0.04f * sin(t * 0.07f + 1.7f)), h * 0.72f),
            )
            drawLine(
                color = Ui.hairline,
                start = Offset(0f, h * 0.5f),
                end = Offset(w, h * 0.5f),
                strokeWidth = 1f,
            )
        }
        content()
    }
}

@Composable
fun MainMenuScreen(vm: GameViewModel) {
    val completed = vm.levels.levelIds().count { vm.isCompleted(it) }
    val total = vm.levels.levelIds().size
    val (stars, starCeiling) = vm.starTotals()

    MenuShell {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 52.dp, vertical = 34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "ECHO",
                    color = Ui.text,
                    fontSize = 52.sp,
                    letterSpacing = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "RUNNER",
                    color = Palette.echo(0),
                    fontSize = 52.sp,
                    letterSpacing = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    "THE WORLD REMEMBERS",
                    color = Ui.textFaint,
                    fontSize = 11.sp,
                    letterSpacing = 6.sp,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    "$completed / $total SECTORS CLEAR",
                    color = Ui.textDim,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(6.dp))
                // Stars are the completion metric that still has headroom once every sector is
                // cleared, so it belongs on the front page next to the sector count.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "★",
                        color = if (stars > 0) Ui.exit else Ui.textFaint,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$stars / $starCeiling",
                        color = Ui.textDim,
                        fontSize = 10.sp,
                        letterSpacing = 2.sp,
                    )
                }
            }

            Column(
                Modifier.width(250.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                MenuAction("PLAY", Ui.accent, vm::quickPlay)
                MenuAction("STORY", Ui.textDim, vm::openStory)
                MenuAction("ENDLESS ECHO", Ui.textDim) { vm.startEndless(1) }
                MenuAction("CHALLENGES", Ui.textDim, vm::openChallenges)
                MenuAction("SETTINGS", Ui.textDim, vm::openSettings)
            }
        }
    }
}

@Composable
fun StoryScreen(vm: GameViewModel) {
    BackHandler { vm.openMenu() }
    MenuShell {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 26.dp)
        ) {
            MenuHeader("STORY", vm::openMenu)
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                vm.chapters().forEach { chapter ->
                    // A chapter unlocks when the previous chapter's last level is done, so the
                    // teaching order cannot be skipped past.
                    Text(
                        "CH ${chapter.number} — ${chapter.title.uppercase()}",
                        color = Ui.text,
                        fontSize = 13.sp,
                        letterSpacing = 3.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        chapter.subtitle,
                        color = Ui.textFaint,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        chapter.levelIds.forEach { id ->
                            val done = vm.isCompleted(id)
                            val unlocked = isUnlocked(vm, id)
                            TextButtonChip(
                                label = id,
                                tint = when {
                                    done -> Ui.ok
                                    unlocked -> Ui.accent
                                    else -> Ui.textFaint
                                },
                                enabled = unlocked,
                                // Stars beat a raw take count here: the count alone means nothing
                                // without knowing the level's par, and the chip has no room to
                                // explain it.
                                trailing = if (done) Rating.glyphs(vm.bestStars(id)) else null,
                            ) { vm.startStory(id) }
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                }
            }
        }
    }
}

/** First level is always open; every later one needs its predecessor cleared. */
private fun isUnlocked(vm: GameViewModel, levelId: String): Boolean {
    val ids = vm.levels.levelIds()
    val i = ids.indexOf(levelId)
    if (i <= 0) return true
    return vm.isCompleted(ids[i - 1])
}

@Composable
fun ChallengeScreen(vm: GameViewModel) {
    BackHandler { vm.openMenu() }
    MenuShell {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 26.dp)
        ) {
            MenuHeader("CHALLENGES", vm::openMenu)
            Spacer(Modifier.height(6.dp))
            Text(
                "Rooms you have already solved, made harder.",
                color = Ui.textFaint, fontSize = 11.sp,
            )
            Spacer(Modifier.height(16.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Challenges.ALL.forEach { challenge ->
                    // Locked until its underlying level is cleared normally — a challenge is a
                    // remix, and remixing a room the player has never seen is just confusing.
                    val unlocked = vm.isCompleted(challenge.levelId)
                    val done = vm.challengeDone(challenge.id)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButtonChip(
                            label = challenge.name.uppercase(),
                            tint = if (done) Ui.ok else if (unlocked) Palette.echo(1) else Ui.textFaint,
                            enabled = unlocked,
                            wide = true,
                            trailing = if (done) "✓" else null,
                        ) { vm.startChallenge(challenge.id) }
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Text(
                                challenge.modifier.label.uppercase(),
                                color = Ui.textDim, fontSize = 10.sp, letterSpacing = 2.sp,
                            )
                            Text(
                                if (unlocked) challenge.modifier.description
                                else "Clear sector ${challenge.levelId} first",
                                color = Ui.textFaint, fontSize = 10.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(vm: GameViewModel, settings: GameSettings) {
    BackHandler { vm.openMenu() }
    MenuShell {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 44.dp, vertical = 26.dp)
        ) {
            MenuHeader("SETTINGS", vm::openMenu)
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(40.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("Audio")
                    Spacer(Modifier.height(6.dp))
                    Toggle("MUSIC", settings.musicEnabled) { on ->
                        vm.updateSettings { it.copy(musicEnabled = on) }
                        if (on) vm.audio.startAmbient() else vm.audio.stopAmbient()
                    }
                    Toggle("SOUND", settings.sfxEnabled) { on ->
                        vm.updateSettings { it.copy(sfxEnabled = on) }
                    }
                    Toggle("HAPTICS", settings.hapticsEnabled) { on ->
                        vm.updateSettings { it.copy(hapticsEnabled = on) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("Controls")
                    Spacer(Modifier.height(6.dp))
                    Toggle("LEFT HANDED", settings.leftHanded) { on ->
                        vm.updateSettings { it.copy(leftHanded = on) }
                    }
                    TextButtonChip(
                        label = "CONTROL SIZE",
                        tint = Ui.textDim,
                        wide = true,
                        trailing = "${(settings.controlScale * 100).toInt()}%",
                    ) {
                        // Cycles rather than using a slider: three sizes cover every hand, and a
                        // slider on a control you cannot see while dragging is worse.
                        val next = when {
                            settings.controlScale < 0.95f -> 1f
                            settings.controlScale < 1.15f -> 1.3f
                            else -> 0.8f
                        }
                        vm.updateSettings { it.copy(controlScale = next) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SectionLabel("Visuals")
                    Spacer(Modifier.height(6.dp))
                    Toggle("ECHO TRAILS", settings.showEchoTrails) { on ->
                        vm.updateSettings { it.copy(showEchoTrails = on) }
                    }
                    Toggle("REDUCED EFFECTS", settings.reducedEffects) { on ->
                        vm.updateSettings { it.copy(reducedEffects = on) }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reduced effects drops fog and most\nparticles. Use it if frames stutter.",
                        color = Ui.textFaint, fontSize = 10.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    TextButtonChip(
        label = label,
        tint = if (value) Ui.accent else Ui.textFaint,
        wide = true,
        trailing = if (value) "ON" else "OFF",
    ) { onChange(!value) }
}

@Composable
private fun MenuHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButtonChip(label = "BACK", tint = Ui.textDim, onClick = onBack)
        Spacer(Modifier.width(18.dp))
        Text(
            title,
            color = Ui.text,
            fontSize = 20.sp,
            letterSpacing = 6.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}
