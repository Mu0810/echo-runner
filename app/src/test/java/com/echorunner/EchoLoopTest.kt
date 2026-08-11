package com.echorunner

import com.echorunner.core.DoorSpec
import com.echorunner.core.GameConstants
import com.echorunner.core.GamePhase
import com.echorunner.core.InputState
import com.echorunner.core.LevelSpec
import com.echorunner.core.NoHaptics
import com.echorunner.core.PlatformSpec
import com.echorunner.core.PlateSpec
import com.echorunner.core.Rect
import com.echorunner.core.SignalCondition
import com.echorunner.core.SilentAudio
import com.echorunner.core.Vec2
import com.echorunner.core.WorldState
import com.echorunner.engine.GameEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end tests for the Echo loop, run headlessly on the JVM.
 *
 * The whole simulation layer is deliberately free of Android imports, which lets the actual
 * shipping engine be driven here rather than a stand-in. These tests exist because the two
 * things most likely to be quietly broken — replay drifting out of sync, and an Echo failing to
 * hold a plate it held while being recorded — are both invisible in a screenshot and only show
 * up as a puzzle that "feels wrong".
 */
class EchoLoopTest {

    /** A one-plate, one-door room: the exit is unreachable unless something holds the plate. */
    private fun plateAndDoorLevel(takeSeconds: Float = 5f) = LevelSpec(
        id = "test-plate",
        name = "Plate Test",
        chapter = 1,
        takeSeconds = takeSeconds,
        maxEchoes = 1,
        spawn = Vec2(3f, 18.45f),
        exit = Rect(24f, 17.5f, 1.6f, 2.5f),
        platforms = listOf(
            PlatformSpec(Rect(0f, 20f, 40f, 2.5f)),
            // Bulkhead above the door so the door cannot simply be jumped.
            PlatformSpec(Rect(17f, 0f, 3.2f, 14f)),
        ),
        plates = listOf(PlateSpec(id = "p1", rect = Rect(8f, 19.6f, 2.2f, 0.4f))),
        doors = listOf(
            DoorSpec(id = "d1", rect = Rect(18f, 14f, 1.2f, 6f), opensWhen = SignalCondition.of("p1"))
        ),
    )

    private fun walkRightThenStand(engine: GameEngine, ticks: Int, walkTicks: Int): List<Pair<Float, Float>> {
        val trace = ArrayList<Pair<Float, Float>>(ticks)
        repeat(ticks) { i ->
            val input = if (i < walkTicks) InputState(moveX = 1f) else InputState.NONE
            engine.step(input)
            val p = engine.world!!.player
            trace.add(p.x to p.y)
        }
        return trace
    }

    /** Steps until the engine leaves a frozen phase and is live again. */
    private fun runOutPhase(engine: GameEngine, limit: Int = 400) {
        var n = 0
        while (engine.world!!.phase != GamePhase.PLAYING && n < limit) {
            engine.step(InputState.NONE)
            n++
        }
    }

    @Test
    fun `player alone cannot pass the door`() {
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(plateAndDoorLevel())
        val world = engine.world!!

        // Hold right for the whole take: the door never opens, so the player must be stopped
        // short of it rather than reaching the exit.
        repeat(world.takeTicks) { engine.step(InputState(moveX = 1f)) }

        assertFalse("door must stay shut with nothing on the plate", world.signal("p1"))
        assertTrue(
            "player should be blocked left of the door, was at x=${world.player.x}",
            world.player.x < 18f,
        )
        assertTrue(
            "level must not be completable in one take",
            world.phase != GamePhase.COMPLETE,
        )
    }

    @Test
    fun `echo replays the recorded take position for position`() {
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(plateAndDoorLevel())
        val world = engine.world!!
        val takeTicks = world.takeTicks

        // Take 1: walk onto the plate, then stand for the rest of the take.
        val recorded = walkRightThenStand(engine, takeTicks, walkTicks = 45)
        assertEquals("take should have ended and banked an Echo", 1, world.echoCount)

        runOutPhase(engine)
        assertEquals(GamePhase.PLAYING, world.phase)

        // Take 2: at the start of tick i the Echo must sit on recorded sample i. This is the
        // assertion that would fail if Echoes were advanced mid-tick instead of at the end.
        val echo = world.echoes.first()
        var maxDrift = 0f
        for (i in 0 until minOf(recorded.size, takeTicks)) {
            val (ex, ey) = recorded[i]
            maxDrift = maxOf(maxDrift, abs(echo.x - ex), abs(echo.y - ey))
            engine.step(InputState.NONE)
            if (world.phase != GamePhase.PLAYING) break
        }

        assertTrue("echo drifted from its recording by $maxDrift world units", maxDrift < 0.001f)
    }

    @Test
    fun `echo holds the plate so the player can leave`() {
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(plateAndDoorLevel())
        val world = engine.world!!

        walkRightThenStand(engine, world.takeTicks, walkTicks = 45)
        assertEquals(1, world.echoCount)
        runOutPhase(engine)

        // Take 2: the Echo walks to the plate on its own; run right and the door should be open
        // by the time we get there.
        var completed = false
        repeat(world.takeTicks) {
            engine.step(InputState(moveX = 1f))
            if (world.phase == GamePhase.COMPLETE) { completed = true; return@repeat }
        }

        assertTrue("the Echo should be holding the plate down", world.signal("p1"))
        assertTrue("player should have reached the exit on take 2", completed)
    }

    @Test
    fun `simulation is deterministic for identical input`() {
        fun run(): List<Pair<Float, Float>> {
            val engine = GameEngine(SilentAudio, NoHaptics)
            engine.load(plateAndDoorLevel())
            return walkRightThenStand(engine, 300, walkTicks = 80)
        }

        val a = run()
        val b = run()
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals("x drift at tick $i", a[i].first, b[i].first, 0f)
            assertEquals("y drift at tick $i", a[i].second, b[i].second, 0f)
        }
    }

    @Test
    fun `death discards the take rather than banking a partial echo`() {
        val level = plateAndDoorLevel().copy(
            hazards = listOf(
                com.echorunner.core.HazardSpec(Rect(6f, 19.2f, 2f, 0.8f))
            ),
        )
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(level)
        val world = engine.world!!

        // Walk right into the spikes.
        var died = false
        repeat(200) {
            engine.step(InputState(moveX = 1f))
            if (world.phase == GamePhase.DEAD) { died = true; return@repeat }
        }

        assertTrue("player should have died on the hazard", died)
        assertEquals("a fatal take must not produce an Echo", 0, world.echoCount)
        assertTrue("death should be counted", world.deaths >= 1)
    }

    @Test
    fun `take timer matches the level's memory window`() {
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(plateAndDoorLevel(takeSeconds = 4f))
        val world: WorldState = engine.world!!
        assertEquals(4 * GameConstants.TICK_HZ, world.takeTicks)
        assertEquals(4f, world.secondsLeft, 0.001f)

        repeat(GameConstants.TICK_HZ) { engine.step(InputState.NONE) }
        assertEquals("one second of ticks should burn one second", 3f, world.secondsLeft, 0.02f)
    }
}
