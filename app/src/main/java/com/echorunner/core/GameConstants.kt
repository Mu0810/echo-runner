package com.echorunner.core

/**
 * Every tunable number in the simulation, in one place.
 *
 * The values here are load-bearing for determinism: the replay system reproduces an Echo by
 * re-running recorded *input* through the same integrator, so changing a constant
 * invalidates previously recorded Echoes. That is fine within a session (Echoes never
 * outlive a level attempt) but it is the reason tuning must not be scattered across files.
 */
object GameConstants {

    // ---- Time -------------------------------------------------------------------------
    /** Simulation rate. Fixed, and independent of display refresh rate. */
    const val TICK_HZ = 60
    /** Seconds per simulation step. */
    const val DT = 1f / TICK_HZ
    /**
     * Hard ceiling on how much real time a single frame may advance the simulation. Without
     * this, a stall (GC pause, app resume) makes the loop try to catch up over hundreds of
     * ticks at once, which reads as a freeze and can teleport bodies through walls.
     */
    const val MAX_FRAME_SECONDS = 0.25f

    // ---- World ------------------------------------------------------------------------
    /** Design resolution in world units, 16:9. Levels are authored against this box. */
    const val WORLD_W = 40f
    const val WORLD_H = 22.5f

    // ---- Player -----------------------------------------------------------------------
    const val PLAYER_W = 0.8f
    const val PLAYER_H = 1.55f

    const val GRAVITY = 62f
    /** Reduced gravity while rising and holding jump, for a controllable arc. */
    const val GRAVITY_JUMP_HOLD = 40f
    const val TERMINAL_VY = 42f

    const val MOVE_SPEED = 9.2f
    const val GROUND_ACCEL = 95f
    const val GROUND_FRICTION = 120f
    const val AIR_ACCEL = 62f
    const val AIR_FRICTION = 18f

    const val JUMP_VELOCITY = -21.2f
    /** Grace period after walking off a ledge during which a jump still works. */
    const val COYOTE_SECONDS = 0.10f
    /** Grace period before landing during which a jump press is remembered. */
    const val JUMP_BUFFER_SECONDS = 0.12f
    /** Releasing jump early cuts upward velocity by this factor (variable jump height). */
    const val JUMP_CUT_FACTOR = 0.45f

    /**
     * The jump cut is suppressed for this long after take-off, guaranteeing a minimum jump.
     *
     * Without it, a quick tap rose **0.73 units** — `JUMP_VELOCITY * JUMP_CUT_FACTOR` squared over
     * twice falling gravity — while every vertical route in the game is built on 3.2-unit climbs.
     * A held jump reaches 5.62, so the mechanic worked, but on a touchscreen a quick tap is the
     * natural gesture and nothing teaches otherwise, which made perfectly solvable rooms feel
     * impossible. Holding the cut for 0.18s floors any jump at roughly 3.3 units — just past the
     * tallest designed climb — while still leaving real variable height above that.
     */
    const val MIN_JUMP_SECONDS = 0.18f

    const val DASH_SPEED = 27f
    const val DASH_SECONDS = 0.16f
    const val DASH_COOLDOWN_SECONDS = 0.70f
    /** Dash refunds on landing so chained platforming stays fluid. */
    const val DASH_REFUND_ON_LAND = true

    /** Reach of the interact action, measured from the player's edges. */
    const val INTERACT_REACH = 0.9f

    // ---- Echo -------------------------------------------------------------------------
    /** Default memory window. Challenge modifiers shorten this (e.g. 15s). */
    const val DEFAULT_TAKE_SECONDS = 30f
    /** Ceiling on recorded ticks per take, sized for the longest supported window. */
    const val MAX_TAKE_TICKS = (45f * TICK_HZ).toInt()
    /** Upper bound on simultaneous Echoes; levels may allow fewer. */
    const val MAX_ECHOES = 5

    // ---- Enemies / hazards ------------------------------------------------------------
    const val BULLET_W = 0.34f
    const val BULLET_H = 0.14f
    const val MAX_BULLETS = 96
    /** How long an Echo holds a walker's attention after entering its alert range. */
    const val DISTRACTION_SECONDS = 1.6f

    // ---- Feel -------------------------------------------------------------------------
    const val CAMERA_FOLLOW_RATE = 7.5f
    const val CAMERA_LOOKAHEAD = 0.22f
    const val SHAKE_DECAY = 5.5f
    const val MAX_PARTICLES = 320
    const val RESPAWN_FREEZE_SECONDS = 0.55f
    const val LEVEL_COMPLETE_HOLD_SECONDS = 1.5f
}
