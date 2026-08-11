package com.echorunner.echo

import com.echorunner.core.EchoRecording
import com.echorunner.core.EchoReplay
import com.echorunner.core.GameConstants
import com.echorunner.core.WorldState

/**
 * Captures the take in progress and freezes it into an [EchoReplay].
 *
 * ## One buffer, forever
 *
 * A single [EchoRecording] is allocated in the constructor and reused for every take of every
 * level. A take is 1800 ticks of six parallel arrays, so a per-take buffer would hand the GC
 * ~34 KB of garbage every thirty seconds — arriving precisely at the take transition, the one
 * moment the game is animating a new Echo into existence and can least afford a hitch.
 *
 * ## Why state and not input
 *
 * Only resolved player *state* is captured: position, velocity, action edges and pose. The
 * long note on [EchoRecording] carries the full argument, and this class honours it — nothing
 * here samples [com.echorunner.core.InputState], and nothing replayed from it will ever be
 * re-simulated. An Echo is a fixed record of what happened, which is what lets a plan made
 * two takes ago still be true after later takes have changed which doors are open.
 *
 * ## Lifecycle
 *
 * [beginTake] → [record] once per tick → [finish] to keep the take as an Echo, or [discard]
 * to throw it away. The recorder holds no reference to the world and no per-take identity, so
 * one instance serves the whole session.
 */
class EchoRecorder {

    /**
     * The single reusable buffer. Capacity is the engine ceiling rather than the current
     * level's take length, so a Challenge modifier can shorten or lengthen a take without
     * reallocating; [EchoRecording.push] drops anything past the ceiling instead of crashing.
     */
    private val recording = EchoRecording(GameConstants.MAX_TAKE_TICKS)

    /**
     * Ticks captured so far in the take.
     *
     * Deliberately still readable after [finish] — the take-transition UI wants the length of
     * the take that just ended. [beginTake] and [discard] are what clear it.
     */
    val ticks: Int get() = recording.length

    /**
     * Rewinds the write cursor for a fresh take.
     *
     * Cheap by design: it resets a length, it does not touch the backing arrays. Restarting a
     * take after a death has to feel instant, so nothing on that path may allocate.
     */
    fun beginTake() {
        recording.reset()
    }

    /**
     * Appends one frame for the tick that has just finished simulating.
     *
     * Must run as the final step of the tick (step 17 of the canonical order). The frame has
     * to hold the position physics actually *resolved* to and the `startedJump`/`startedDash`
     * edges raised during this same tick; recording earlier would bake in a pre-collision
     * position and shift every action one tick late, so a materialised Echo would arrive off
     * the plate it was choreographed to press.
     */
    fun record(world: WorldState) {
        val p = world.player
        recording.push(
            x = p.x,
            y = p.y,
            vx = p.vx,
            vy = p.vy,
            jump = p.startedJump,
            dash = p.startedDash,
            interact = p.interacting,
            grounded = p.onGround,
            facingRight = p.facingRight,
            state = p.anim,
        )
    }

    /**
     * Freezes the take into an immutable replay owned by the caller.
     *
     * [EchoRecording.freeze] copies down to the recorded length, so the returned replay never
     * aliases the live buffer and this recorder is safe to reuse on the very next tick. A take
     * with zero recorded ticks yields an empty replay rather than throwing: an
     * [com.echorunner.core.EchoBody] built from one starts inactive, which is an inert Echo
     * rather than a crash on an edge case the player can reach by dying on tick zero.
     *
     * @param index 0-based creation order. Drives the Echo's hue and its trail tint, so the
     *   player can tell timelines apart at a glance; pass the count of existing Echoes.
     */
    fun finish(index: Int): EchoReplay = recording.freeze(index)

    /** Throws the take away without producing an Echo — death, or a manual restart. */
    fun discard() {
        recording.reset()
    }
}
