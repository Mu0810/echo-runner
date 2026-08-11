package com.echorunner.core

/**
 * One tick's worth of intent, sampled once per simulation step.
 *
 * Only *held* states live here. Press edges (jump tapped this tick, dash tapped this tick)
 * are derived by the engine from the previous tick's snapshot, which keeps edge detection in
 * a single place and makes it impossible for a fast tap to be counted twice by two systems.
 */
data class InputState(
    /** Horizontal intent in -1..1. Analog from the joystick, ±1 from a keyboard. */
    val moveX: Float = 0f,
    val jumpHeld: Boolean = false,
    val dashHeld: Boolean = false,
    val interactHeld: Boolean = false,
) {
    companion object {
        val NONE = InputState()
    }
}

/**
 * Mutable landing zone for control input.
 *
 * The UI thread writes (pointer drags, key events); the game loop reads exactly once per
 * tick via [sample]. Fields are `@Volatile` so a write on the UI thread is visible to the
 * loop without a lock — a torn read here is harmless (worst case one tick of stale input)
 * whereas a lock on the render path is not.
 *
 * Jump/dash/interact are latched: a tap shorter than one tick would otherwise be dropped
 * entirely, which feels broken on a touchscreen. [sample] consumes the latch.
 */
class InputBuffer {
    @Volatile var moveX: Float = 0f
    @Volatile var jumpHeld: Boolean = false
    @Volatile var dashHeld: Boolean = false
    @Volatile var interactHeld: Boolean = false

    @Volatile private var jumpLatched: Boolean = false
    @Volatile private var dashLatched: Boolean = false
    @Volatile private var interactLatched: Boolean = false

    fun pressJump() { jumpHeld = true; jumpLatched = true }
    fun releaseJump() { jumpHeld = false }

    fun pressDash() { dashHeld = true; dashLatched = true }
    fun releaseDash() { dashHeld = false }

    fun pressInteract() { interactHeld = true; interactLatched = true }
    fun releaseInteract() { interactHeld = false }

    /** Reads the current intent and clears any one-shot latches. Called once per tick. */
    fun sample(): InputState {
        val s = InputState(
            moveX = clamp(moveX, -1f, 1f),
            jumpHeld = jumpHeld || jumpLatched,
            dashHeld = dashHeld || dashLatched,
            interactHeld = interactHeld || interactLatched,
        )
        jumpLatched = false
        dashLatched = false
        interactLatched = false
        return s
    }

    /** Drops all input. Used on pause, death and level transitions so nothing sticks. */
    fun clear() {
        moveX = 0f
        jumpHeld = false; dashHeld = false; interactHeld = false
        jumpLatched = false; dashLatched = false; interactLatched = false
    }
}
