package com.echorunner.engine

import com.echorunner.core.AnimState
import com.echorunner.core.AudioController
import com.echorunner.core.ChallengeModifier
import com.echorunner.core.GameConstants.AIR_ACCEL
import com.echorunner.core.GameConstants.AIR_FRICTION
import com.echorunner.core.GameConstants.COYOTE_SECONDS
import com.echorunner.core.GameConstants.DASH_COOLDOWN_SECONDS
import com.echorunner.core.GameConstants.DASH_REFUND_ON_LAND
import com.echorunner.core.GameConstants.DASH_SECONDS
import com.echorunner.core.GameConstants.DASH_SPEED
import com.echorunner.core.GameConstants.GRAVITY
import com.echorunner.core.GameConstants.GRAVITY_JUMP_HOLD
import com.echorunner.core.GameConstants.GROUND_ACCEL
import com.echorunner.core.GameConstants.GROUND_FRICTION
import com.echorunner.core.GameConstants.JUMP_BUFFER_SECONDS
import com.echorunner.core.GameConstants.JUMP_CUT_FACTOR
import com.echorunner.core.GameConstants.MIN_JUMP_SECONDS
import com.echorunner.core.GameConstants.JUMP_VELOCITY
import com.echorunner.core.GameConstants.MOVE_SPEED
import com.echorunner.core.GameConstants.TERMINAL_VY
import com.echorunner.core.InputState
import com.echorunner.core.PlayerBody
import com.echorunner.core.SoundCue
import com.echorunner.core.WorldState
import com.echorunner.core.approach
import com.echorunner.core.clamp
import kotlin.math.abs
import kotlin.math.max

/**
 * Turns one tick of intent into player velocity, pose, cues and particles, then hands the body
 * to [Physics].
 *
 * Every tuning number comes from `GameConstants`, because the Echo system replays a take
 * against the same integrator: a constant invented here would be a constant the replay cannot
 * see. The only local numbers are presentation (particle lifetimes, cue volumes), which never
 * feed back into the simulation.
 */
object PlayerController {

    // ---- Presentation only ---------------------------------------------------------------
    // Cosmetic and audio-cadence values. Deliberately not in GameConstants: that object is the
    // frozen tuning surface the simulation and replays depend on, and none of these affect it.

    /** Footstep cadence while running, per the design brief. */
    private const val FOOTSTEP_INTERVAL = 0.28f
    private const val FOOTSTEP_VOLUME = 0.45f

    /** Floor volume for a landing, scaled up to 1.0 by impact speed. */
    private const val LAND_MIN_VOLUME = 0.35f

    private const val JUMP_PUFFS = 6
    private const val DASH_PUFFS = 7
    private const val LAND_PUFFS_MIN = 4
    private const val LAND_PUFFS_RANGE = 7

    // Palette slots, resolved by the renderer's Palette.particleTint.
    private const val TINT_DUST = 0
    private const val TINT_JUMP = 1
    private const val TINT_DASH = 2
    private const val TINT_LAND = 3

    /** Analog deadzone: a float epsilon on joystick input, not a tuning value. */
    private const val INPUT_EPSILON = 0.02f

    /** Above this speed the body reads as running rather than drifting. */
    private const val RUN_THRESHOLD = MOVE_SPEED * 0.2f

    /**
     * Footstep cadence phase.
     *
     * Object state rather than body state because it is pure audio timing: nothing in the
     * simulation, the recording or a replay reads it, so it must not sit on [PlayerBody] where
     * it would imply Echoes needed it. Reset whenever the player is not running on the ground.
     */
    private var footstepPhase = 0f

    /**
     * Advances the player one tick. Ends by calling [Physics.movePlayer], as the tick order
     * requires — the only work after the move is reacting to a landing, which is not knowable
     * until the sweep has resolved.
     */
    fun update(
        world: WorldState,
        input: InputState,
        prevInput: InputState,
        dt: Float,
        audio: AudioController,
        modifier: ChallengeModifier,
    ) {
        val p = world.player
        val wasGrounded = p.onGround

        // ---- Press edges ------------------------------------------------------------------
        // Derived here and nowhere else, so a single tap can never be consumed by two systems.
        val jumpPressed = input.jumpHeld && !prevInput.jumpHeld
        val jumpReleased = !input.jumpHeld && prevInput.jumpHeld

        // Airborne clock, used only to floor the jump height (see MIN_JUMP_SECONDS).
        if (p.onGround) p.airborneFor = 0f else p.airborneFor += dt
        val dashPressed = input.dashHeld && !prevInput.dashHeld

        p.jumpHeld = input.jumpHeld
        // `interacting` is the HELD state, not a press edge, and is recorded into the Echo
        // track that way. Switches guard a held press with their own `consumedThisTake` flag,
        // and the flag has to mean the same thing for the player and for a ghost or an Echo
        // would toggle things the player never could.
        p.interacting = input.interactHeld
        p.startedJump = false
        p.startedDash = false

        // ---- Timers -----------------------------------------------------------------------
        if (p.dashCooldown > 0f) p.dashCooldown = max(0f, p.dashCooldown - dt)
        if (p.coyoteTimer > 0f) p.coyoteTimer = max(0f, p.coyoteTimer - dt)
        if (p.jumpBuffer > 0f) p.jumpBuffer = max(0f, p.jumpBuffer - dt)
        if (p.onGround) p.coyoteTimer = COYOTE_SECONDS
        if (jumpPressed) p.jumpBuffer = JUMP_BUFFER_SECONDS

        // ---- Dash start -------------------------------------------------------------------
        val budget = modifier.dashBudget
        val dashReady = p.dashTimer <= 0f &&
            p.dashCooldown <= 0f &&
            (budget == null || p.dashesUsed < budget)
        if (dashPressed && dashReady) {
            val dir = when {
                input.moveX > INPUT_EPSILON -> 1
                input.moveX < -INPUT_EPSILON -> -1
                else -> if (p.facingRight) 1 else -1
            }
            p.facingRight = dir > 0
            p.dashTimer = DASH_SECONDS
            p.dashesUsed++
            p.startedDash = true
            p.vx = DASH_SPEED * dir
            p.vy = 0f
            audio.play(SoundCue.DASH)
            emitDash(world, p)
        }

        if (p.dashTimer > 0f) {
            // The dash owns the body for its duration: horizontal control, gravity and jump are
            // all suspended so the burst reads as one clean beat instead of a steerable arc.
            p.vx = DASH_SPEED * if (p.facingRight) 1f else -1f
            p.vy = 0f
            p.dashTimer -= dt
            if (p.dashTimer <= 0f) {
                p.dashTimer = 0f
                p.dashCooldown = DASH_COOLDOWN_SECONDS
                // Exit speed is left at DASH_SPEED on purpose: GROUND_FRICTION bleeds it back
                // to MOVE_SPEED over ~0.2 s, and that slide-out is what makes the dash feel
                // like it carried weight. Clamping it here would end the dash with a stutter.
            }
        } else {
            // ---- Horizontal ---------------------------------------------------------------
            val neutral = abs(input.moveX) <= INPUT_EPSILON
            if (!neutral) p.facingRight = input.moveX > 0f
            val target = if (neutral) 0f else input.moveX * MOVE_SPEED
            val rate = if (neutral) {
                if (p.onGround) GROUND_FRICTION else AIR_FRICTION
            } else {
                if (p.onGround) GROUND_ACCEL else AIR_ACCEL
            }
            p.vx = approach(p.vx, target, rate * dt)

            // ---- Vertical -----------------------------------------------------------------
            // Lighter gravity while rising with jump held gives a floatier, readable arc; the
            // full value on the way down keeps landings snappy.
            val gravity = if (p.vy < 0f && input.jumpHeld) GRAVITY_JUMP_HOLD else GRAVITY
            p.vy += gravity * dt
            if (p.vy > TERMINAL_VY) p.vy = TERMINAL_VY

            if (!modifier.jumpDisabled) {
                if (p.jumpBuffer > 0f && p.coyoteTimer > 0f) {
                    // Coyote time and the jump buffer are two halves of one forgiveness: one
                    // covers pressing slightly late, the other slightly early. Both are
                    // consumed so a single press can only ever produce a single jump.
                    p.vy = JUMP_VELOCITY
                    p.jumpBuffer = 0f
                    p.coyoteTimer = 0f
                    p.onGround = false
                    p.startedJump = true
                    audio.play(SoundCue.JUMP)
                    emitJump(world, p)
                } else if (jumpReleased && p.vy < 0f && p.airborneFor >= MIN_JUMP_SECONDS) {
                    // Variable height: letting go mid-rise trades altitude for control -- but only
                    // after MIN_JUMP_SECONDS, so a tap still clears the climbs the levels are built
                    // from. See the constant for the numbers.
                    p.vy *= JUMP_CUT_FACTOR
                }
            }
        }

        // ---- Pose -------------------------------------------------------------------------
        p.anim = when {
            p.dashTimer > 0f -> AnimState.DASH
            p.vy < 0f -> AnimState.RISE
            !p.onGround && p.vy > 0f -> AnimState.FALL
            p.onGround && abs(p.vx) > RUN_THRESHOLD -> AnimState.RUN
            else -> AnimState.IDLE
        }

        // Captured before the move because a landing zeroes vy inside the sweep.
        val impactVy = p.vy

        Physics.movePlayer(world, dt)

        // ---- Landing ----------------------------------------------------------------------
        // Only knowable after the sweep resolved, which is why this reaction sits below the
        // move rather than above it.
        if (!wasGrounded && p.onGround) {
            val impact = clamp(impactVy / TERMINAL_VY, 0f, 1f)
            audio.play(SoundCue.LAND, LAND_MIN_VOLUME + (1f - LAND_MIN_VOLUME) * impact)
            emitLand(world, p, impact)
            // Refunding on landing is what keeps a dash-jump-dash chain fluid instead of
            // gating the second half of every traversal behind a cooldown.
            if (DASH_REFUND_ON_LAND) p.dashCooldown = 0f
        }

        // ---- Footsteps --------------------------------------------------------------------
        if (p.onGround && p.dashTimer <= 0f && abs(p.vx) > RUN_THRESHOLD) {
            footstepPhase += dt
            if (footstepPhase >= FOOTSTEP_INTERVAL) {
                // Subtract rather than zero, so the cadence stays even instead of drifting by
                // up to a tick every step.
                footstepPhase -= FOOTSTEP_INTERVAL
                audio.play(SoundCue.FOOTSTEP, FOOTSTEP_VOLUME)
                world.particles.emit(
                    p.x + p.w * 0.5f, p.y + p.h,
                    -p.vx * 0.08f, -0.6f,
                    0.22f, 0.09f, TINT_DUST,
                )
            }
        } else {
            footstepPhase = 0f
        }
    }

    /** Dust kicked outward from the feet on launch. */
    private fun emitJump(world: WorldState, p: PlayerBody) {
        val cx = p.x + p.w * 0.5f
        val feet = p.y + p.h
        for (i in 0 until JUMP_PUFFS) {
            val t = spread(i, JUMP_PUFFS)
            world.particles.emit(
                cx + t * p.w * 0.6f, feet,
                t * 3.4f, -1.6f * (1f - abs(t) * 0.5f),
                0.34f, 0.16f, TINT_JUMP,
            )
        }
    }

    /**
     * Trail streaming backwards out of the dash, so the burst leaves a readable direction.
     */
    private fun emitDash(world: WorldState, p: PlayerBody) {
        val dir = if (p.facingRight) 1f else -1f
        val tailX = p.x + p.w * 0.5f - dir * p.w * 0.5f
        for (i in 0 until DASH_PUFFS) {
            val t = spread(i, DASH_PUFFS)
            world.particles.emit(
                tailX, p.y + p.h * (0.5f + t * 0.35f),
                -dir * 6.5f * (0.5f + 0.5f * abs(t)), t * 1.4f,
                0.28f, 0.14f, TINT_DASH,
            )
        }
    }

    /** Landing splash, scaled by impact so a hop and a long drop do not look identical. */
    private fun emitLand(world: WorldState, p: PlayerBody, impact: Float) {
        val cx = p.x + p.w * 0.5f
        val feet = p.y + p.h
        val count = LAND_PUFFS_MIN + (LAND_PUFFS_RANGE * impact).toInt()
        for (i in 0 until count) {
            val t = spread(i, count)
            world.particles.emit(
                cx + t * p.w * 0.7f, feet,
                t * (4f + 5f * impact), -0.9f * (1f - abs(t) * 0.7f),
                0.3f + 0.2f * impact, 0.13f, TINT_LAND,
            )
        }
    }

    /**
     * Evenly spaced fan in -1..1, derived from the loop index.
     *
     * Particles live in [WorldState] and are therefore part of the simulation's state, so they
     * must not consume randomness — an identical take has to produce an identical world.
     */
    private fun spread(i: Int, count: Int): Float =
        if (count <= 1) 0f else (i / (count - 1).toFloat()) * 2f - 1f
}
