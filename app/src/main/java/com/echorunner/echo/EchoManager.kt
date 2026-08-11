package com.echorunner.echo

import com.echorunner.core.AudioController
import com.echorunner.core.EchoBody
import com.echorunner.core.EchoReplay
import com.echorunner.core.SoundCue
import com.echorunner.core.WorldState

/**
 * Drives every materialised Echo: one cursor step per tick, plus the cues and particles that
 * make a replay read as a *presence* rather than a second sprite.
 *
 * Echoes are kinematic. Nothing in here runs physics, resolves a collision or reads input —
 * position comes straight out of the recording, which is the guarantee the puzzle design rests
 * on (see the note on [EchoReplay]). Presence is granted by the systems that ask *where* an
 * Echo is: plates, switches, turret targeting, walker aggro and bullet blocking all read these
 * bodies. This runs at step 6 of the canonical tick order, after the player has moved and
 * before plates are sampled, so every consumer in the same tick sees the same lockstep world.
 */
object EchoManager {

    /**
     * Trail cadence in ticks: ~20 puffs a second per Echo, dense enough to read as a smear of
     * motion without draining the shared particle pool when five timelines are running.
     */
    private const val TRAIL_INTERVAL = 3
    private const val TRAIL_TTL = 0.45f
    private const val TRAIL_SIZE = 0.13f
    /** Trail drifts backward against the Echo's own recorded velocity, so it lags the body. */
    private const val TRAIL_DRAG = 0.06f
    /** Slight upward drift; y is down, so negative rises. */
    private const val TRAIL_RISE = -0.35f

    private const val BURST_SPEED = 4.5f
    private const val BURST_TTL = 0.6f
    private const val BURST_SIZE = 0.16f

    /** Camera kick on materialisation, so a new timeline lands as a physical beat. */
    private const val MATERIALIZE_SHAKE = 0.55f

    /** Echo cues sit under the player's so the mix never buries the take being performed. */
    private const val ECHO_DASH_VOLUME = 0.35f
    private const val ECHO_JUMP_VOLUME = 0.22f
    private const val ECHO_EXPIRE_VOLUME = 0.75f

    /**
     * Unit ring for the materialisation burst, precomputed so the birth of an Echo costs no
     * trig and no allocation, and is bit-identical on every run.
     */
    private val BURST_DIR_X = floatArrayOf(1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f, 0f, 0.7071f)
    private val BURST_DIR_Y = floatArrayOf(0f, 0.7071f, 1f, 0.7071f, 0f, -0.7071f, -1f, -0.7071f)

    /**
     * Steps every active Echo one tick along its own recording.
     *
     * An exhausted track *holds its final pose* rather than looping or vanishing: plans are
     * routinely built on "the Echo stands on the plate and stays there", and a wrap would
     * silently walk it off again. On the single tick a track runs out, the body deactivates and
     * [SoundCue.ECHO_EXPIRE] fires once — never again while it sits there dead.
     *
     * The expire cue is additionally coalesced to one per tick across all Echoes. Every Echo in
     * a level was recorded from an equal-length take, so they all run out on the *same* tick;
     * without this, a four-Echo room ends with four phase-coherent copies of one cue stacking
     * into a spike far louder than any other sound in the game.
     */
    fun advance(world: WorldState, audio: AudioController) {
        val echoes = world.echoes
        var expireCuePlayed = false
        for (i in 0 until echoes.size) {
            val echo = echoes[i]
            // Spent Echoes are skipped entirely: prev == current, so the renderer keeps
            // drawing the held pose with no interpolation smear and no further cues.
            if (!echo.active) continue

            echo.prevX = echo.x
            echo.prevY = echo.y

            val replay = echo.replay
            if (echo.tick >= replay.length - 1) {
                echo.active = false
                if (!replay.isEmpty && !expireCuePlayed) {
                    audio.play(SoundCue.ECHO_EXPIRE, ECHO_EXPIRE_VOLUME)
                    expireCuePlayed = true
                }
                continue
            }

            val tick = echo.tick + 1
            echo.tick = tick
            echo.x = replay.x(tick)
            echo.y = replay.y(tick)
            echo.facingRight = replay.facingRight(tick)
            echo.anim = replay.animAt(tick)

            // Action edges are baked into the recording, so these fire on exactly the tick the
            // player performed them — the Echo is audible as a peer without competing.
            if (replay.startedDash(tick)) audio.play(SoundCue.DASH, ECHO_DASH_VOLUME)
            if (replay.startedJump(tick)) audio.play(SoundCue.JUMP, ECHO_JUMP_VOLUME)

            if (tick % TRAIL_INTERVAL == 0) {
                world.particles.emit(
                    echo.x + echo.w * 0.5f,
                    echo.y + echo.h * 0.5f,
                    -replay.vx(tick) * TRAIL_DRAG,
                    TRAIL_RISE,
                    TRAIL_TTL,
                    TRAIL_SIZE,
                    trailTint(echo.index),
                )
            }
        }
    }

    /**
     * Promotes a finished take into a live Echo.
     *
     * Rewound *before* it joins the world so no system can observe a body sitting at the origin
     * for a tick, and left at tick 0 so it starts in lockstep with every other Echo on the next
     * take. The allowance check (`world.echoesRemaining`) belongs to the caller: silently
     * refusing an Echo here would leave a puzzle unsolvable with no visible cause.
     */
    fun materialize(world: WorldState, replay: EchoReplay, audio: AudioController) {
        val body = EchoBody(replay)
        body.rewind()
        world.echoes.add(body)
        audio.play(SoundCue.ECHO_CREATED)
        world.camera.kick(MATERIALIZE_SHAKE)
        emitBirthBurst(world, body)
    }

    /**
     * Returns every Echo to tick 0 for a fresh take.
     *
     * Lockstep is the contract the whole game is choreographed against: the world resets at the
     * start of each take and all Echoes must re-run from the same tick 0, or a plan timed
     * against an earlier performance stops landing. Uses an indexed loop rather than `forEach`
     * to stay iterator-free on the restart path.
     */
    fun rewindAll(world: WorldState) {
        val echoes = world.echoes
        for (i in 0 until echoes.size) echoes[i].rewind()
    }

    /**
     * Particle tint slot for an Echo's effects.
     *
     * The player's own dust occupies palette slots 0..3, so Echoes take the upper half of the
     * 0..7 range — a timeline's trail must never be mistaken for the player's.
     */
    private fun trailTint(index: Int): Int = 4 + (index % 4)

    /** Outward ring at the Echo's centre, marking the instant a timeline becomes real. */
    private fun emitBirthBurst(world: WorldState, echo: EchoBody) {
        val cx = echo.x + echo.w * 0.5f
        val cy = echo.y + echo.h * 0.5f
        val tint = trailTint(echo.index)
        for (i in BURST_DIR_X.indices) {
            world.particles.emit(
                cx,
                cy,
                BURST_DIR_X[i] * BURST_SPEED,
                BURST_DIR_Y[i] * BURST_SPEED,
                BURST_TTL,
                BURST_SIZE,
                tint,
            )
        }
    }
}
