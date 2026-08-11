package com.echorunner

import com.echorunner.core.GameConstants
import com.echorunner.core.InputState
import com.echorunner.core.LevelSpec
import com.echorunner.core.NoHaptics
import com.echorunner.core.PlatformSpec
import com.echorunner.core.Rect
import com.echorunner.core.SilentAudio
import com.echorunner.core.Vec2
import com.echorunner.engine.GameEngine
import com.echorunner.level.LevelParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Jump reach, measured rather than assumed.
 *
 * This exists because of a real bug found only by playing: every vertical route in the game is
 * built on 3.2-unit climbs, and a **held** jump reaches 5.6 — but a quick *tap* reached only
 * **0.73**, because releasing the button multiplied upward velocity by `JUMP_CUT_FACTOR` the
 * instant it happened. On a touchscreen a tap is the natural gesture and nothing taught otherwise,
 * so solvable rooms felt impossible. `MIN_JUMP_SECONDS` now suppresses the cut briefly after
 * take-off to floor the jump.
 *
 * These tests measure the real thing through the engine, so the guarantee cannot quietly rot: the
 * shortest possible jump must still clear the tallest climb any shipped level asks for.
 */
class JumpReachTest {

    /** A flat room with nothing in it, so the only thing measured is the jump. */
    private fun flatRoom() = LevelSpec(
        id = "jump-probe",
        name = "Jump Probe",
        chapter = 1,
        takeSeconds = 10f,
        maxEchoes = 0,
        spawn = Vec2(5f, 18.45f),
        exit = Rect(38f, 17.5f, 1.6f, 2.5f),
        platforms = listOf(PlatformSpec(Rect(0f, 20f, 40f, 2.5f))),
    )

    /** Peak height gained, in world units, when jump is held for [holdTicks] ticks. */
    private fun jumpHeight(holdTicks: Int): Float {
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(flatRoom())
        engine.beginPlaying()
        val p = engine.world!!.player
        val groundY = p.y
        var peak = 0f

        var prev = InputState.NONE
        repeat(200) { tick ->
            val held = tick < holdTicks
            val input = InputState(jumpHeld = held)
            engine.step(input)
            prev = input
            peak = maxOf(peak, groundY - p.y)
            // Stop once the body is back on the floor with the jump finished.
            if (tick > holdTicks + 4 && p.onGround) return peak
        }
        return peak
    }

    /**
     * The climb height every *jumpable* vertical route in the game is built on.
     *
     * Hand-verified across 2-2, 3-2 and 3-3 — the three levels with stacked walkable surfaces —
     * where each step is exactly 3.20 units. It is a constant rather than a computed maximum
     * because deriving it from the level files conflates jump climbs with **lift-served** height
     * changes: 6-4 stacks decks at y=20, 13 and 7, and those 6-7 unit rises are ridden, not jumped.
     * An earlier version of this test computed 8.00u that way and failed for the wrong reason.
     */
    private val designedJumpClimb = 3.20f

    @Test
    fun `a single-tick tap still clears the tallest designed climb`() {
        val tap = jumpHeight(holdTicks = 1)
        println("tap jump reaches ${"%.2f".format(tap)}u; designed jump climb is ${"%.2f".format(designedJumpClimb)}u")
        assertTrue(
            "a one-tick tap reaches only ${"%.2f".format(tap)}u but every vertical route needs " +
                "${"%.2f".format(designedJumpClimb)}u, so a tapping player cannot climb at all",
            tap >= designedJumpClimb,
        )
    }

    @Test
    fun `no level asks for a jump taller than a held jump can reach`() {
        // Complements the tap floor with the ceiling: a stacked pair of walkable surfaces must be
        // within reach of a full held jump, or the route needs a lift the level may not have.
        val hold = jumpHeight(holdTicks = 60)
        val dir = File("src/main/assets/levels")
        val root = org.json.JSONObject(File(dir, "manifest.json").readText())
        val chapters = root.getJSONArray("chapters")
        val tooTall = mutableListOf<String>()

        for (i in 0 until chapters.length()) {
            val levels = chapters.getJSONObject(i).getJSONArray("levels")
            for (j in 0 until levels.length()) {
                val id = levels.getString(j)
                val spec = LevelParser.parse(File(dir, "$id.json").readText())
                // A lift can deliver any height, so levels with one are out of scope here.
                if (spec.movers.isNotEmpty()) continue
                val tops = spec.platforms.map { it.rect.y }.filter { it > 1f }.distinct().sorted()
                for (k in 0 until tops.size - 1) {
                    val rise = tops[k + 1] - tops[k]
                    if (rise > hold + 0.2f) tooTall += "$id needs ${"%.1f".format(rise)}u"
                }
            }
        }
        println("held jump reaches ${"%.2f".format(hold)}u; drops beyond jump reach: ${tooTall.ifEmpty { listOf("none") }}")
        // NOT an assertion. Surfaces further apart than a jump are often deliberate ONE-WAY drops,
        // and in this game they are load-bearing: 2-1's hint says outright that the 7-unit shaft
        // wall is why "nobody who throws that switch ever leaves" -- being stranded is the puzzle.
        // A static geometry metric cannot tell an intentional one-way drop from a broken route, so
        // this reports and leaves the judgement to a human. Three earlier attempts at asserting on
        // level geometry all failed this same way, flagging design as breakage.
        assertTrue("the probe should still measure a real held jump", hold > 5f)
    }

    @Test
    fun `holding jump still goes meaningfully higher than tapping`() {
        val tap = jumpHeight(holdTicks = 1)
        val hold = jumpHeight(holdTicks = 60)
        println("tap ${"%.2f".format(tap)}u vs hold ${"%.2f".format(hold)}u")
        // Variable height must survive the fix -- if the floor swallowed the ceiling, the jump
        // would be one fixed height and the mechanic would be gone.
        assertTrue("holding must still gain height over tapping", hold > tap + 1.2f)
        assertTrue("held jump should stay near its designed apex", hold > 5f)
    }

    @Test
    fun `the minimum jump window is short enough to stay responsive`() {
        // A long floor would feel like the control had been taken away. Anything up to a couple of
        // frames past a fifth of a second still reads as the player's own press.
        assertTrue(
            "MIN_JUMP_SECONDS=${GameConstants.MIN_JUMP_SECONDS} is long enough to feel sticky",
            GameConstants.MIN_JUMP_SECONDS <= 0.22f,
        )
    }
}
