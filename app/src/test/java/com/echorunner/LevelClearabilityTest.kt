package com.echorunner

import com.echorunner.core.GameConstants
import com.echorunner.core.GamePhase
import com.echorunner.core.InputState
import com.echorunner.core.LevelSpec
import com.echorunner.core.NoHaptics
import com.echorunner.core.SilentAudio
import com.echorunner.engine.GameEngine
import com.echorunner.engine.Physics
import com.echorunner.level.LevelParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * A platformer bot that can jump, used to answer one question: **which levels can actually be
 * finished?**
 *
 * This exists because progression is strictly linear — a level's chip only unlocks when the
 * previous level is completed — so a single unclearable room walls off every room after it. A
 * static "the geometry looks fine" check cannot detect that; only trying to finish each level can.
 *
 * The bot is deliberately dumb but not helpless: it walks toward an objective, jumps when a wall
 * blocks it or the ground ahead runs out, and banks a take at each plate/switch so the walk
 * becomes an Echo. It cannot ride a lift, time a laser, bait a guard or use a teleporter, so a
 * level it fails is *suspect*, not proven broken — the report separates "finished" from "needs
 * skills the bot lacks" and never asserts on the latter.
 */
class LevelClearabilityTest {

    private val dir = File("src/main/assets/levels")

    private fun manifestIds(): List<String> {
        val root = org.json.JSONObject(File(dir, "manifest.json").readText())
        val chapters = root.getJSONArray("chapters")
        return buildList {
            for (i in 0 until chapters.length()) {
                val l = chapters.getJSONObject(i).getJSONArray("levels")
                for (j in 0 until l.length()) add(l.getString(j))
            }
        }
    }

    private class Bot(val engine: GameEngine) {
        val world get() = engine.world!!
        val p get() = world.player

        /** Solid ground exists just beyond the feet in [dir]? Used to decide to jump a gap. */
        fun groundAhead(dir: Int, lookahead: Float): Boolean =
            Physics.solidOverlap(
                world, p.x + dir * lookahead, p.y + p.h + 0.05f, p.w, 0.35f,
            )

        /** Something blocks the body at torso height in [dir]? Used to decide to jump a wall. */
        fun wallAhead(dir: Int): Boolean =
            Physics.solidOverlap(world, p.x + dir * 0.45f, p.y, p.w, p.h * 0.9f)

        /**
         * Steers toward [targetX] (and up to [targetY] when it is above), jumping when blocked or
         * when the floor runs out. Returns true on arrival.
         */
        fun goTo(targetX: Float, targetY: Float?, maxTicks: Int): Boolean {
            var jumpHold = 0
            var stuckAt = p.x
            var stuckTicks = 0

            repeat(maxTicks) {
                if (world.phase != GamePhase.PLAYING) return false
                val centre = p.x + p.w * 0.5f
                val dx = targetX - centre
                if (abs(dx) < 0.30f && (targetY == null || p.y <= targetY + 0.6f)) return true

                val dir = if (dx > 0) 1 else -1
                var wantJump = false

                // Gap ahead: no floor within a step, but there is floor a jump away.
                if (p.onGround && !groundAhead(dir, 0.9f)) wantJump = true
                // Wall ahead: a door or bulkhead. Jumping is free, so try it.
                if (p.onGround && wallAhead(dir)) wantJump = true
                // Objective is above us and we are roughly under it.
                if (p.onGround && targetY != null && p.y > targetY + 0.6f && abs(dx) < 4f) wantJump = true
                // Not moving despite trying: shove upward rather than grind against geometry.
                if (abs(p.x - stuckAt) < 0.02f) stuckTicks++ else { stuckTicks = 0; stuckAt = p.x }
                if (stuckTicks > 12 && p.onGround) { wantJump = true; stuckTicks = 0 }

                if (wantJump) jumpHold = 14
                val holding = jumpHold > 0
                if (jumpHold > 0) jumpHold--

                engine.step(InputState(moveX = dir.toFloat(), jumpHeld = holding))
            }
            return abs((p.x + p.w * 0.5f) - targetX) < 0.6f
        }

        fun stand(ticks: Int) {
            repeat(ticks) {
                if (world.phase != GamePhase.PLAYING) return
                engine.step(InputState.NONE)
            }
        }

        fun press(ticks: Int = 10) {
            repeat(ticks) {
                if (world.phase != GamePhase.PLAYING) return
                engine.step(InputState(interactHeld = true))
            }
        }

        fun runOutPhase(limit: Int = 400) {
            var n = 0
            while (world.phase != GamePhase.PLAYING && n < limit) { engine.step(InputState.NONE); n++ }
        }
    }

    /** Result of attempting one level. */
    private data class Attempt(val takes: Int?, val note: String)

    private fun attempt(spec: LevelSpec): Attempt {
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(spec)
        engine.beginPlaying()
        val bot = Bot(engine)
        val world = bot.world

        data class Objective(val x: Float, val y: Float, val interact: Boolean)
        val objectives = (
            spec.plates.map { Objective(it.rect.centerX, it.rect.y - GameConstants.PLAYER_H, false) } +
                spec.switches.map { Objective(it.rect.centerX, it.rect.y - GameConstants.PLAYER_H, true) }
            ).sortedBy { it.x }

        // One take per objective, up to the Echo budget.
        for (o in objectives) {
            if (world.echoCount >= spec.maxEchoes) break
            if (!bot.goTo(o.x, o.y, world.takeTicks - 40)) {
                return Attempt(null, "could not reach objective at x=${"%.1f".format(o.x)}")
            }
            if (o.interact) bot.press()
            bot.stand(24)
            engine.endTakeNow()
            bot.runOutPhase()
            if (world.phase != GamePhase.PLAYING) return Attempt(null, "take did not restart cleanly")
        }

        // Final run for the exit.
        val exitX = spec.exit.centerX
        val exitY = spec.exit.y + spec.exit.h - GameConstants.PLAYER_H
        bot.goTo(exitX, exitY, world.takeTicks)
        if (world.phase == GamePhase.COMPLETE) return Attempt(world.takeIndex + 1, "cleared")

        // One more attempt in case the first pass banked its last Echo late.
        bot.runOutPhase()
        if (world.phase == GamePhase.PLAYING) {
            bot.goTo(exitX, exitY, world.takeTicks)
            if (world.phase == GamePhase.COMPLETE) return Attempt(world.takeIndex + 1, "cleared on retry")
        }
        return Attempt(null, "reached x=${"%.1f".format(bot.p.x)} of exit x=${"%.1f".format(exitX)}")
    }

    @Test
    fun `every level is physically traversable with all gates open`() {
        // Isolates geometry from choreography. Every door is forced open and every threat
        // removed, so the only question left is: can a body that can walk and jump get from the
        // spawn to the exit at all? A level that fails THIS is broken regardless of how well it
        // is played — no amount of Echo planning can create a ledge that is out of reach.
        //
        // Levels driven by a SIGNAL-mode lift are excluded and reported separately: their
        // traversability genuinely depends on the lift running, which depends on a plate being
        // held, which is choreography rather than geometry.
        val ok = mutableListOf<String>()
        val broken = mutableListOf<String>()
        val liftDriven = mutableListOf<String>()

        for (id in manifestIds()) {
            val spec = LevelParser.parse(File(dir, "$id.json").readText())
            if (spec.movers.any { it.activeWhen != null }) { liftDriven += id; continue }

            val open = spec.copy(
                doors = spec.doors.map { it.copy(opensWhen = com.echorunner.core.SignalCondition.ALWAYS) },
                hazards = emptyList(),
                lasers = emptyList(),
                turrets = emptyList(),
                walkers = emptyList(),
                exitCondition = null,
            )
            val engine = GameEngine(SilentAudio, NoHaptics)
            engine.load(open)
            engine.beginPlaying()
            val bot = Bot(engine)
            val exitX = open.exit.centerX
            val exitY = open.exit.y + open.exit.h - GameConstants.PLAYER_H

            // Generous budget: three full takes of walking, since a bot wanders more than a human.
            var reached = false
            var furthest = bot.p.x
            repeat(3) {
                if (reached) return@repeat
                bot.goTo(exitX, exitY, bot.world.takeTicks)
                furthest = maxOf(furthest, bot.p.x)
                if (bot.world.phase == GamePhase.COMPLETE) reached = true
                else { bot.runOutPhase() }
            }
            if (reached) ok += id
            else broken += "$id[furthest x=${"%.1f".format(furthest)}, exit x=${"%.1f".format(exitX)}]"
        }

        println("TRAVERSAL (all gates open): ${ok.size} traversable, ${broken.size} unreachable, ${liftDriven.size} lift-driven")
        println("  traversable:  ${ok.joinToString(", ")}")
        println("  lift-driven (excluded): ${liftDriven.joinToString(", ")}")
        if (broken.isNotEmpty()) {
            println("  UNREACHABLE EXITS:")
            broken.forEach { println("    $it") }
        }

        // NOT asserted as a hard failure: this bot jumps on simple heuristics and cannot chain
        // precise multi-hop climbs, so a level it fails here needs a human look rather than being
        // proven broken. What IS asserted is that the majority are traversable, which would catch
        // a systemic geometry regression.
        assertTrue(
            "most levels should be traversable with gates open; only ${ok.size} were, which " +
                "suggests a systemic geometry problem rather than bot limits",
            ok.size >= (ok.size + broken.size) * 2 / 3,
        )
    }

    @Test
    fun `report which levels the bot can clear`() {
        val cleared = mutableListOf<String>()
        val stuck = mutableListOf<String>()

        for (id in manifestIds()) {
            val spec = LevelParser.parse(File(dir, "$id.json").readText())
            val r = try {
                attempt(spec)
            } catch (t: Throwable) {
                throw AssertionError("level $id threw while being played: ${t.message}", t)
            }
            if (r.takes != null) cleared += "$id(${r.takes}T)" else stuck += "$id[${r.note}]"
        }

        println("BOT CLEARABILITY: ${cleared.size}/${manifestIds().size}")
        println("  cleared: ${cleared.joinToString(", ")}")
        println("  stuck:")
        stuck.forEach { println("    $it") }

        // The first level gates the entire game under linear progression, so it must be clearable
        // by a bot that can only walk and jump — that is all 1-1 is supposed to require.
        assertTrue(
            "level 1-1 could not be cleared by a walk+jump bot, which would wall off the whole game",
            cleared.any { it.startsWith("1-1(") },
        )
    }
}
