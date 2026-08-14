package com.echorunner

import com.echorunner.core.GamePhase
import com.echorunner.core.InputState
import com.echorunner.core.NoHaptics
import com.echorunner.core.SilentAudio
import com.echorunner.engine.GameEngine
import com.echorunner.level.LevelParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Covers the undo verb added in v0.3.0.
 *
 * Undo touches the one invariant the whole game rests on: Echoes replay in lockstep from tick 0,
 * and every puzzle is choreographed against that. A discard that left the budget counter and the
 * Echo list disagreeing, or that failed to rewind the survivors, would not crash — it would
 * quietly desynchronise plans several rooms later, which is the worst possible failure mode for a
 * puzzle game. So these assert the bookkeeping directly.
 */
class UndoEchoTest {

    private val dir = File("src/main/assets/levels")

    private fun engineOn(levelId: String): GameEngine {
        val spec = LevelParser.parse(File(dir, "$levelId.json").readText())
        val engine = GameEngine(SilentAudio, NoHaptics)
        engine.load(spec)
        engine.beginPlaying()
        return engine
    }

    /** Walks right for [ticks] then banks, leaving one Echo behind. */
    private fun bankATake(engine: GameEngine, ticks: Int = 60) {
        repeat(ticks) { engine.step(InputState(moveX = 1f)) }
        engine.endTakeNow()
        var guard = 0
        while (engine.world!!.phase != GamePhase.PLAYING && guard++ < 400) engine.step(InputState.NONE)
    }

    @Test
    fun `discarding returns the Echo and its budget together`() {
        val engine = engineOn("1-2")
        val w = engine.world!!
        bankATake(engine)

        assertEquals("one take banked should leave one Echo", 1, w.echoes.size)
        assertEquals("budget counter should follow the Echo stack", 1, w.takeIndex)

        assertTrue("discard should succeed while an Echo exists", engine.discardLastEcho())

        assertEquals("the Echo should be gone", 0, w.echoes.size)
        assertEquals("the budget should have come back with it", 0, w.takeIndex)
        assertEquals("the undo should be recorded for the rating", 1, w.undosUsed)
        assertEquals("play should resume immediately", GamePhase.PLAYING, w.phase)
        assertEquals("the take should have restarted from tick 0", 0, w.tick)
    }

    @Test
    fun `discarding with nothing banked is refused rather than corrupting state`() {
        val engine = engineOn("1-2")
        val w = engine.world!!
        assertFalse("nothing to undo", engine.discardLastEcho())
        assertEquals(0, w.echoes.size)
        assertEquals(0, w.takeIndex)
        assertEquals("a refused undo must not be priced", 0, w.undosUsed)
    }

    @Test
    fun `the world is rebuilt after an undo, not left mid-take`() {
        val engine = engineOn("1-2")
        val w = engine.world!!
        bankATake(engine, ticks = 90)
        // Move away from the spawn so a failure to reset would be visible.
        repeat(40) { engine.step(InputState(moveX = 1f)) }
        val movedTo = w.player.x
        assertTrue("the player should have travelled before the undo", movedTo > w.level.spawn.x + 1f)

        engine.discardLastEcho()

        assertEquals("player returns to spawn", w.level.spawn.x, w.player.x, 0.01f)
        assertTrue("signals cleared", w.plates.none { it.pressed })
        assertEquals("tick restarted", 0, w.tick)
    }

    @Test
    fun `a re-banked take after undo lands on the same stack slot`() {
        val engine = engineOn("1-2")
        val w = engine.world!!
        bankATake(engine)
        engine.discardLastEcho()
        bankATake(engine)

        assertEquals("re-banking should refill the slot, not append past it", 1, w.echoes.size)
        assertEquals(1, w.takeIndex)
        assertEquals("index 0 is the only slot in use", 0, w.echoes[0].index)
        assertEquals("the undo is still on the record", 1, w.undosUsed)
    }

    @Test
    fun `restarting the room clears the undo count`() {
        val engine = engineOn("1-2")
        val w = engine.world!!
        bankATake(engine)
        engine.discardLastEcho()
        assertEquals(1, w.undosUsed)

        engine.restartLevel()

        assertEquals("a fresh attempt starts unpriced", 0, w.undosUsed)
        assertEquals(0, w.echoes.size)
        assertEquals(0, w.takeIndex)
    }
}
