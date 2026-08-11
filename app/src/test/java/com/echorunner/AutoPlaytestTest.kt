package com.echorunner

import com.echorunner.core.GameConstants
import com.echorunner.core.GamePhase
import com.echorunner.core.InputState
import com.echorunner.core.LevelSpec
import com.echorunner.core.NoHaptics
import com.echorunner.core.PlatformKind
import com.echorunner.core.SilentAudio
import com.echorunner.engine.GameEngine
import com.echorunner.level.LevelParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * An automated player that actually solves levels, driving the real shipping engine.
 *
 * ## Why this exists
 *
 * Every other check on the level set is static: it proves a level *parses* and is
 * *structurally* sane (spawn grounded, no dangling signals, par reachable). None of it proves a
 * room can be *finished*. Driving the shipping build through `adb` cannot close that gap either,
 * because `adb` has no multitouch and the game needs the stick held while jumping.
 *
 * The simulation layer has no Android dependencies, so the honest way to playtest is to drive it
 * directly: an agent walks the player to each pressure plate in turn, banks a take so the walk
 * becomes an Echo, and on the final take runs for the exit. If the door opens and the player
 * reaches the exit, the room is *empirically* solvable — not merely well-formed.
 *
 * ## Deliberate scope
 *
 * The agent only knows how to walk and to bank. It cannot jump, ride a lift, time a laser or
 * bait a guard. So it is pointed only at levels whose intended solution is "hold every plate,
 * then leave": a flat floor, plates on that floor, and no movers, lasers, turrets, walkers or
 * teleporters. Those it must finish. The rest are reported as out of scope rather than silently
 * skipped, so the coverage number stays honest.
 */
class AutoPlaytestTest {

    private val levelsDir = File("src/main/assets/levels")

    private fun manifestIds(): List<String> {
        val root = org.json.JSONObject(File(levelsDir, "manifest.json").readText())
        val chapters = root.getJSONArray("chapters")
        return buildList {
            for (i in 0 until chapters.length()) {
                val levels = chapters.getJSONObject(i).getJSONArray("levels")
                for (j in 0 until levels.length()) add(levels.getString(j))
            }
        }
    }

    /** True when "hold every plate / flip every switch, then leave" is a plausible solution. */
    private fun isWalkOnly(spec: LevelSpec): Boolean {
        if (spec.plates.isEmpty() && spec.switches.isEmpty()) return false
        if (spec.movers.isNotEmpty() || spec.lasers.isNotEmpty()) return false
        if (spec.turrets.isNotEmpty() || spec.walkers.isNotEmpty()) return false
        if (spec.teleporters.isNotEmpty()) return false
        if (spec.hazards.isNotEmpty()) return false
        // Every target and the exit must sit at the same standing height as the spawn, otherwise
        // the route needs a jump the agent does not have.
        val floorTop = spec.spawn.y + GameConstants.PLAYER_H
        if (spec.plates.any { abs(it.rect.bottom - floorTop) > 0.35f }) return false
        if (spec.switches.any { abs(it.rect.bottom - floorTop) > 1.2f }) return false
        if (abs(spec.exit.bottom - floorTop) > 0.9f) return false
        // A ONE_WAY platform implies a vertical route.
        return spec.platforms.none { it.kind == PlatformKind.ONE_WAY }
    }

    /** Walks toward [targetX] for at most [maxTicks], returning true once within tolerance. */
    private fun walkTo(engine: GameEngine, targetX: Float, maxTicks: Int): Boolean {
        val world = engine.world!!
        repeat(maxTicks) {
            val centre = world.player.x + world.player.w * 0.5f
            val dx = targetX - centre
            if (abs(dx) < 0.22f) return true
            engine.step(InputState(moveX = if (dx > 0) 1f else -1f))
            if (world.phase != GamePhase.PLAYING) return false
        }
        return abs((world.player.x + world.player.w * 0.5f) - targetX) < 0.5f
    }

    /** Holds still for [ticks] so a plate registers and the take has a stable end pose. */
    private fun stand(engine: GameEngine, ticks: Int) {
        repeat(ticks) {
            if (engine.world!!.phase != GamePhase.PLAYING) return
            engine.step(InputState.NONE)
        }
    }

    private fun runOutPhase(engine: GameEngine, limit: Int = 400) {
        var n = 0
        while (engine.world!!.phase != GamePhase.PLAYING && n < limit) {
            engine.step(InputState.NONE); n++
        }
    }

    /**
     * Plays [spec] the way a human would: one take per plate, then a final run for the exit.
     * Returns the number of takes used, or null if it never got out.
     */
    private fun play(spec: LevelSpec): Int? {
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(spec)
        engine.beginPlaying()
        val world = engine.world!!

        // One target per take, left to right: the agent walks past earlier ones anyway, so this
        // ordering keeps each take's walk short enough to fit inside the memory window.
        data class Target(val x: Float, val interact: Boolean)
        val targets = (
            spec.plates.map { Target(it.rect.centerX, false) } +
                spec.switches.map { Target(it.rect.centerX, true) }
            ).sortedBy { it.x }

        for (target in targets) {
            if (world.echoCount >= spec.maxEchoes) break
            if (!walkTo(engine, target.x, world.takeTicks - 60)) return null
            if (target.interact) {
                // Hold interact for a few ticks: the switch consumes one press edge, and the
                // engine derives that edge from the previous tick's input.
                repeat(8) {
                    if (world.phase != GamePhase.PLAYING) return null
                    engine.step(InputState(interactHeld = true))
                }
            }
            stand(engine, 30)
            engine.endTakeNow()          // bank this walk as an Echo
            runOutPhase(engine)
            if (world.phase != GamePhase.PLAYING) return null
        }

        // Final take: the Echoes are holding the plates, so the door should be open.
        val exitX = spec.exit.centerX
        repeat(world.takeTicks) {
            if (world.phase == GamePhase.COMPLETE) return world.takeIndex + 1
            val centre = world.player.x + world.player.w * 0.5f
            engine.step(InputState(moveX = if (exitX - centre > 0) 1f else -1f))
        }
        return if (world.phase == GamePhase.COMPLETE) world.takeIndex + 1 else null
    }

    @Test
    fun `walk-only levels are solvable end to end by an automated player`() {
        val inScope = mutableListOf<String>()
        val solved = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (id in manifestIds()) {
            val spec = LevelParser.parse(File(levelsDir, "$id.json").readText())
            if (!isWalkOnly(spec)) continue
            inScope += id
            val takes = play(spec)
            if (takes != null) solved += "$id(${takes}T)" else failed += id
        }

        println("automated playtest: ${solved.size}/${inScope.size} walk-only levels finished")
        println("  solved: ${solved.joinToString(", ")}")
        if (failed.isNotEmpty()) println("  FAILED: ${failed.joinToString(", ")}")

        assertTrue("no level matched the walk-only profile; the agent tested nothing", inScope.isNotEmpty())
        assertTrue("automated player could not finish: $failed", failed.isEmpty())
    }

    @Test
    fun `sweep every level and report what the simple agent can finish`() {
        // Informational, not an assertion: most levels need a jump, a lift, a laser window or a
        // guard baited, none of which this agent can do. A level it cannot finish is therefore
        // NOT evidence of a broken level. What this sweep gives is a lower bound on how much of
        // the game is empirically completable, and it fails loudly if a level throws or hangs.
        val finished = mutableListOf<String>()
        val notFinished = mutableListOf<String>()

        for (id in manifestIds()) {
            val spec = LevelParser.parse(File(levelsDir, "$id.json").readText())
            val takes = try {
                play(spec)
            } catch (t: Throwable) {
                throw AssertionError("level $id threw while being played: ${t.message}", t)
            }
            if (takes != null) finished += "$id(${takes}T/par${spec.parTakes})" else notFinished += id
        }

        println("agent sweep: finished ${finished.size}/${manifestIds().size} levels")
        println("  finished:     ${finished.joinToString(", ")}")
        println("  needs skills the agent lacks: ${notFinished.joinToString(", ")}")
        assertTrue("the agent finished nothing at all, which means it is broken", finished.isNotEmpty())
    }

    @Test
    fun `a plate level cannot be finished without banking any echo`() {
        // Guards the inverse: if walking straight to the exit worked, the plate puzzle would be
        // decorative and the solvability result above would prove nothing.
        val candidates = manifestIds()
            .map { it to LevelParser.parse(File(levelsDir, "$it.json").readText()) }
            .filter { (_, s) -> isWalkOnly(s) && s.maxEchoes >= 1 }

        assertTrue("expected at least one walk-only plate level", candidates.isNotEmpty())

        for ((id, spec) in candidates) {
            val engine = GameEngine(SilentAudio, NoHaptics)
            engine.load(spec)
            engine.beginPlaying()
            val world = engine.world!!
            repeat(world.takeTicks) { engine.step(InputState(moveX = 1f)) }
            assertTrue(
                "$id was completed in one take with no Echo, so its door is not really gated",
                world.phase != GamePhase.COMPLETE,
            )
        }
    }
}
