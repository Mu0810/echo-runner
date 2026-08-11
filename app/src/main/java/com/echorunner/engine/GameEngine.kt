package com.echorunner.engine

import com.echorunner.core.AnimState
import com.echorunner.core.AudioController
import com.echorunner.core.ChallengeModifier
import com.echorunner.core.GameConstants
import com.echorunner.core.GamePhase
import com.echorunner.core.HapticEvent
import com.echorunner.core.Haptics
import com.echorunner.core.InputState
import com.echorunner.core.LevelSpec
import com.echorunner.core.SoundCue
import com.echorunner.core.TakeEndReason
import com.echorunner.core.WorldState
import com.echorunner.core.clamp
import com.echorunner.core.damp
import com.echorunner.echo.EchoManager
import com.echorunner.echo.EchoRecorder
import com.echorunner.entity.EnemySystems
import com.echorunner.entity.PuzzleSystems

/**
 * Drives one level: owns the tick order, the take lifecycle and the win/lose transitions.
 *
 * Everything here runs on a **fixed 60 Hz step** ([GameConstants.DT]). Nothing in this class
 * reads wall-clock time or frame duration, which is what lets a recorded Echo line up tick for
 * tick with the take that produced it.
 */
class GameEngine(
    private val audio: AudioController,
    private val haptics: Haptics,
) {
    var world: WorldState? = null
        private set

    /** Active challenge restriction. [ChallengeModifier.NONE] in Story and Endless. */
    var modifier: ChallengeModifier = ChallengeModifier.NONE
        private set

    private val recorder = EchoRecorder()
    private var prevInput = InputState.NONE

    /** Set when the player reaches the exit, so the host can advance and persist progress. */
    var completed = false
        private set

    /** Seconds of simulated time in the attempt, for scoring. */
    val elapsedSeconds: Float get() = world?.totalSeconds ?: 0f

    // ---- lifecycle -------------------------------------------------------------------

    fun load(level: LevelSpec, challenge: ChallengeModifier = ChallengeModifier.NONE) {
        modifier = challenge
        completed = false
        val w = WorldState(level)
        world = w
        w.resetTake()
        recorder.beginTake()
        prevInput = InputState.NONE
        // Levels with dialogue open on it; the rest drop straight into play.
        w.phase = if (level.dialogue.isEmpty()) GamePhase.PLAYING else GamePhase.INTRO
        centreCameraOnPlayer(w)
    }

    /** Dismisses the opening dialogue and starts take one. */
    fun beginPlaying() {
        val w = world ?: return
        if (w.phase == GamePhase.INTRO) w.phase = GamePhase.PLAYING
    }

    fun setPaused(paused: Boolean) {
        world?.paused = paused
    }

    /** Restarts the current take, keeping every Echo already earned. */
    fun restartTake() {
        val w = world ?: return
        w.resetTake()
        recorder.beginTake()
        prevInput = InputState.NONE
        w.phase = GamePhase.PLAYING
        centreCameraOnPlayer(w)
    }

    /** Full restart: drops all Echoes and returns to take one. */
    fun restartLevel() {
        val w = world ?: return
        w.resetLevel()
        recorder.beginTake()
        prevInput = InputState.NONE
        completed = false
        centreCameraOnPlayer(w)
    }

    /** Ends the take early, banking what the player did so far as an Echo. */
    fun endTakeNow() {
        val w = world ?: return
        if (w.phase == GamePhase.PLAYING) endTake(w, TakeEndReason.MANUAL)
    }

    // ---- the tick --------------------------------------------------------------------

    /**
     * Advances the simulation by exactly one fixed step.
     *
     * The caller is responsible for calling this the right number of times per frame (see
     * the accumulator in the host loop); this method never scales anything by frame time.
     */
    fun step(input: InputState) {
        val w = world ?: return
        if (w.paused) return

        when (w.phase) {
            // Frozen states: only their exit timer runs.
            GamePhase.INTRO -> return
            GamePhase.DEAD -> {
                w.phaseTimer -= GameConstants.DT
                if (w.phaseTimer <= 0f) restartTake()
                return
            }
            GamePhase.TAKE_TRANSITION -> {
                w.phaseTimer -= GameConstants.DT
                if (w.phaseTimer <= 0f) restartTake()
                return
            }
            GamePhase.OUT_OF_ECHOES -> {
                w.phaseTimer -= GameConstants.DT
                if (w.phaseTimer <= 0f) restartLevel()
                return
            }
            GamePhase.COMPLETE -> {
                w.phaseTimer -= GameConstants.DT
                return
            }
            GamePhase.PLAYING -> simulate(w, input)
        }
    }

    /**
     * One tick of live gameplay.
     *
     * ## Tick order, and the replay-sync trap in it
     *
     * Echoes are advanced at the **end** of the tick, not the middle. This is the single most
     * important ordering decision in the game and it is easy to get wrong.
     *
     * [EchoManager.advance] increments an Echo's cursor and *then* reads its position, so after
     * k advances an Echo sits on recorded sample k. The player, meanwhile, finishes tick i
     * standing on what will be recorded as sample i. If Echoes were advanced before the
     * interaction checks, an Echo would be evaluated at sample i+1 while the player is at
     * sample i — every Echo would run one tick (16.7 ms) ahead of the timeline that produced
     * it, and the drift would show up as plates releasing a frame early and tight puzzles
     * becoming unsolvable for no visible reason.
     *
     * Advancing last means that throughout tick i an Echo occupies exactly the position the
     * player occupied at the same point of the take that recorded it, so plate, switch,
     * bullet and enemy checks all see a faithful replay.
     */
    private fun simulate(w: WorldState, input: InputState) {
        val dt = GameConstants.DT

        // Signals resolve from last tick's plate/switch state, so consumers below act on a
        // consistent snapshot rather than a half-updated one.
        PuzzleSystems.resolveSignals(w)

        // Geometry that carries bodies moves before the bodies do.
        PuzzleSystems.updateMovers(w, dt)
        PuzzleSystems.updateDoors(w, dt, audio)

        PlayerController.update(w, input, prevInput, dt, audio, modifier)

        // Interaction checks: player is at sample `tick`, Echoes still are too (see above).
        PuzzleSystems.updatePlates(w, audio)
        PuzzleSystems.updateSwitches(w, dt, audio)
        PuzzleSystems.updateLasers(w, dt, audio)

        EnemySystems.updateTurrets(w, dt, audio)
        EnemySystems.updateBullets(w, dt)
        EnemySystems.updateWalkers(w, dt, audio)

        PuzzleSystems.updateTeleporters(w, dt, audio)

        w.totalSeconds += dt

        if (checkDeath(w)) return
        if (checkExit(w)) return

        updateParticles(w, dt)
        updateCamera(w, dt)

        // Record AFTER the world settled: sample `tick` is the resolved end-of-tick pose.
        recorder.record(w)

        // Advance Echoes last — see the doc comment.
        EchoManager.advance(w, audio)

        prevInput = input
        w.tick++

        if (w.tick >= w.takeTicks) endTake(w, TakeEndReason.TIMER)
    }

    // ---- transitions -----------------------------------------------------------------

    private fun checkDeath(w: WorldState): Boolean {
        val p = w.player
        val lethal = EnemySystems.lethalForPlayer(w) ||
            PuzzleSystems.laserLethalAt(w, p.x, p.y, p.w, p.h)
        if (!lethal) return false

        p.alive = false
        p.anim = AnimState.DEAD
        w.deaths++
        w.phase = GamePhase.DEAD
        w.phaseTimer = GameConstants.RESPAWN_FREEZE_SECONDS
        w.lastTakeEnd = TakeEndReason.DEATH
        audio.play(SoundCue.DEATH)
        haptics.fire(HapticEvent.DEATH)
        w.camera.kick(0.8f)
        burst(w, p.x + p.w * 0.5f, p.y + p.h * 0.5f, 22, tint = 4)
        // The take is abandoned, not banked: dying must not hand out a broken Echo.
        recorder.discard()
        return true
    }

    private fun checkExit(w: WorldState): Boolean {
        val p = w.player
        val e = w.level.exit
        val touching = p.x < e.right && p.x + p.w > e.x && p.y < e.bottom && p.y + p.h > e.y
        if (!touching || !w.evaluate(w.level.exitCondition)) return false

        w.phase = GamePhase.COMPLETE
        w.phaseTimer = GameConstants.LEVEL_COMPLETE_HOLD_SECONDS
        completed = true
        audio.play(SoundCue.LEVEL_COMPLETE)
        haptics.fire(HapticEvent.LEVEL_COMPLETE)
        w.camera.kick(0.5f)
        burst(w, e.centerX, e.centerY, 34, tint = 5)
        return true
    }

    /**
     * Banks the finished take as an Echo when the level still allows one.
     *
     * A level permits `maxEchoes + 1` takes: each take but the last leaves an Echo behind for
     * the next one. Running out without reaching the exit is not a soft fail — the level
     * restarts, because a stale set of Echoes cannot be partially rewound.
     */
    private fun endTake(w: WorldState, reason: TakeEndReason) {
        w.lastTakeEnd = reason

        if (w.takeIndex < w.level.maxEchoes && recorder.ticks > 0) {
            EchoManager.materialize(w, recorder.finish(w.echoes.size), audio)
            haptics.fire(HapticEvent.ECHO_CREATED)
            w.takeIndex++
            w.phase = GamePhase.TAKE_TRANSITION
            w.phaseTimer = 0.9f
        } else {
            w.phase = GamePhase.OUT_OF_ECHOES
            w.phaseTimer = 1.2f
            audio.play(SoundCue.ECHO_EXPIRE)
        }
    }

    // ---- feel ------------------------------------------------------------------------

    /**
     * Particle integration. Owned here rather than by the renderer so effects advance on the
     * fixed step and look identical regardless of display refresh rate.
     */
    private fun updateParticles(w: WorldState, dt: Float) {
        val p = w.particles
        for (i in 0 until p.capacityCount) {
            if (p.life[i] <= 0f) continue
            p.life[i] -= dt
            p.x[i] += p.vx[i] * dt
            p.y[i] += p.vy[i] * dt
            p.vy[i] += 14f * dt          // light gravity; sparks arc rather than drift
            p.vx[i] *= 1f - 2.2f * dt    // drag
        }
    }

    private fun updateCamera(w: WorldState, dt: Float) {
        val cam = w.camera
        val p = w.player
        // Lead the camera slightly in the direction of travel so fast movement reveals
        // what is ahead instead of what has already been crossed.
        val target = p.x + p.w * 0.5f + p.vx * GameConstants.CAMERA_LOOKAHEAD
        cam.x = damp(cam.x, target, GameConstants.CAMERA_FOLLOW_RATE, dt)
        cam.y = damp(cam.y, p.y + p.h * 0.5f, GameConstants.CAMERA_FOLLOW_RATE * 0.6f, dt)

        if (cam.shake > 0.0005f) {
            cam.shake = damp(cam.shake, 0f, GameConstants.SHAKE_DECAY, dt)
            // Deterministic pseudo-noise from the tick: no RNG in the simulation.
            val t = w.tick.toFloat()
            cam.shakeOffsetX = kotlin.math.sin(t * 12.9898f) * cam.shake * 0.42f
            cam.shakeOffsetY = kotlin.math.sin(t * 7.233f) * cam.shake * 0.30f
        } else {
            cam.shake = 0f
            cam.shakeOffsetX = 0f
            cam.shakeOffsetY = 0f
        }
    }

    private fun centreCameraOnPlayer(w: WorldState) {
        val p = w.player
        w.camera.x = p.x + p.w * 0.5f
        w.camera.y = p.y + p.h * 0.5f
    }

    private fun burst(w: WorldState, cx: Float, cy: Float, count: Int, tint: Int) {
        // Deterministic radial spray: angle derived from the index, not from an RNG.
        for (i in 0 until count) {
            val a = (i.toFloat() / count) * 6.28318f
            val speed = 5.5f + (i % 4) * 1.4f
            w.particles.emit(
                cx, cy,
                kotlin.math.cos(a) * speed,
                kotlin.math.sin(a) * speed,
                0.45f + (i % 3) * 0.12f,
                0.10f + (i % 2) * 0.05f,
                tint,
            )
        }
    }

    companion object {
        /** True when the phase should show a full-screen overlay instead of live controls. */
        fun isOverlayPhase(phase: GamePhase): Boolean = when (phase) {
            GamePhase.PLAYING, GamePhase.TAKE_TRANSITION -> false
            else -> true
        }
    }
}

/** Convenience for the host loop: how many fixed steps a frame's elapsed time owes. */
class StepAccumulator {
    private var carry = 0f

    /**
     * Converts elapsed real time into whole fixed steps.
     *
     * Clamps to [GameConstants.MAX_FRAME_SECONDS] first: after a GC pause or an app resume the
     * elapsed time can be seconds, and replaying hundreds of ticks at once would read as a
     * freeze and can shove bodies through walls.
     */
    fun stepsFor(elapsedSeconds: Float): Int {
        carry += clamp(elapsedSeconds, 0f, GameConstants.MAX_FRAME_SECONDS)
        var steps = 0
        while (carry >= GameConstants.DT) {
            carry -= GameConstants.DT
            steps++
        }
        return steps
    }

    /** Fraction of the way into the next step, for render interpolation. */
    val alpha: Float get() = clamp(carry / GameConstants.DT, 0f, 1f)

    fun reset() { carry = 0f }
}
