package com.echorunner

import com.echorunner.ui.CoachMachine
import com.echorunner.ui.TutorialStep
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests the first-run coach's gates.
 *
 * These exist because the device cannot answer the question. `adb shell input` has no way to hold
 * a Compose button for a measured duration, so the hold-jump gate — the lesson added *because*
 * v0.2.1 shipped a room that punished tapping — is unverifiable by driving the emulator. Its
 * whole value is that it cannot be cleared by the wrong input, and that is exactly what a test
 * can pin down.
 */
class CoachMachineTest {

    private fun armed(): CoachMachine = CoachMachine().apply { arm(TutorialStep.MOVE) }

    /** Holds jump for [ticks] airborne ticks. */
    private fun CoachMachine.holdJump(ticks: Int) {
        repeat(ticks) { tick(distanceFromSpawn = 5f, onGround = false, jumpHeld = true, echoCount = 0) }
    }

    @Test
    fun `the coach can be armed at any lesson still owed`() {
        assertEquals("a room outside the coach gets nothing", TutorialStep.NONE, CoachMachine().step)
        assertEquals("a fresh player starts at movement", TutorialStep.MOVE, armed().step)
        assertEquals(
            "the banking lesson can be resumed in a later room",
            TutorialStep.BANK,
            CoachMachine().apply { arm(TutorialStep.BANK) }.step,
        )
    }

    /**
     * Guards the bug that shipped past the unit tests and was only caught on a device.
     *
     * 1-1 is authored with `maxEchoes = 0`. The banking lesson therefore cannot complete there,
     * and an earlier version armed all three lessons in 1-1 and stalled on the third forever,
     * never persisting completion. The fix moved banking to 1-2, so this pins the property that
     * made the room unsuitable: with no Echo ever granted, the lesson simply never fires.
     */
    @Test
    fun `the banking lesson never completes in a room that grants no Echo`() {
        val c = CoachMachine().apply { arm(TutorialStep.BANK) }
        repeat(600) {
            val done = c.tick(distanceFromSpawn = 20f, onGround = true, jumpHeld = false, echoCount = 0)
            assertEquals("a single-take room can never satisfy the banking lesson", false, done)
        }
        assertEquals(TutorialStep.BANK, c.step)
    }

    @Test
    fun `a twitch does not count as learning to move`() {
        val c = armed()
        c.tick(distanceFromSpawn = 2.9f, onGround = true, jumpHeld = false, echoCount = 0)
        assertEquals("under three units is not a deliberate move", TutorialStep.MOVE, c.step)
        c.tick(distanceFromSpawn = 3.0f, onGround = true, jumpHeld = false, echoCount = 0)
        assertEquals(TutorialStep.HOLD_JUMP, c.step)
    }

    @Test
    fun `a held jump clears the lesson`() {
        val c = armed()
        c.tick(distanceFromSpawn = 4f, onGround = true, jumpHeld = false, echoCount = 0)
        assertEquals(TutorialStep.HOLD_JUMP, c.step)

        c.holdJump(14)
        assertEquals("fourteen ticks is short of the threshold", TutorialStep.HOLD_JUMP, c.step)
        c.holdJump(1)
        assertEquals("the fifteenth tick teaches it", TutorialStep.BANK, c.step)
    }

    @Test
    fun `tapping never clears the hold lesson, however many times`() {
        val c = armed()
        c.tick(distanceFromSpawn = 4f, onGround = true, jumpHeld = false, echoCount = 0)

        // Ten taps: each is 10 airborne ticks of hold — 100 ticks in total, far past the
        // threshold — but every one is released and landed, so none of them is a *hold*.
        // If this ever passes, the lesson is clearing itself on the exact input it exists to fix.
        repeat(10) {
            c.holdJump(10)
            c.tick(distanceFromSpawn = 4f, onGround = false, jumpHeld = false, echoCount = 0) // released
            c.tick(distanceFromSpawn = 4f, onGround = true, jumpHeld = false, echoCount = 0)  // landed
        }
        assertEquals("ten taps are not one hold", TutorialStep.HOLD_JUMP, c.step)
    }

    @Test
    fun `banking a take finishes the coach exactly once`() {
        val c = armed()
        c.tick(distanceFromSpawn = 4f, onGround = true, jumpHeld = false, echoCount = 0)
        c.holdJump(15)
        assertEquals(TutorialStep.BANK, c.step)

        val firstCompletion = c.tick(distanceFromSpawn = 4f, onGround = true, jumpHeld = false, echoCount = 1)
        assertEquals("completing should be reported so it is persisted once", true, firstCompletion)
        assertEquals(TutorialStep.DONE, c.step)

        val secondCompletion = c.tick(distanceFromSpawn = 4f, onGround = true, jumpHeld = false, echoCount = 2)
        assertEquals("it must not re-report and re-persist every tick", false, secondCompletion)

        c.dismiss()
        assertEquals("dismissing retires the coach for good", TutorialStep.NONE, c.step)
    }

    @Test
    fun `the lessons run in order and cannot be skipped`() {
        val c = armed()
        // Bank an Echo before either earlier lesson: the coach must not jump to the end.
        c.tick(distanceFromSpawn = 0f, onGround = true, jumpHeld = false, echoCount = 3)
        assertEquals("an Echo does not excuse the movement lesson", TutorialStep.MOVE, c.step)

        c.tick(distanceFromSpawn = 6f, onGround = true, jumpHeld = false, echoCount = 3)
        assertEquals("still owes the jump lesson", TutorialStep.HOLD_JUMP, c.step)
    }
}
