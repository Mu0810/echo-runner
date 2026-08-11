package com.echorunner.entity

import com.echorunner.core.AudioController
import com.echorunner.core.GameConstants
import com.echorunner.core.MoverMode
import com.echorunner.core.MoverState
import com.echorunner.core.Rect
import com.echorunner.core.SignalCondition
import com.echorunner.core.SoundCue
import com.echorunner.core.TeleporterState
import com.echorunner.core.WorldState
import com.echorunner.core.approach
import com.echorunner.core.overlaps
import com.echorunner.core.overlapsPadded
import kotlin.math.sqrt

/**
 * Plates, switches, doors, movers, lasers and teleporters — every object that produces or
 * consumes a signal.
 *
 * ## One signal snapshot per tick
 *
 * The orchestrator calls [resolveSignals] at step 2, the *consumers* at steps 3–4
 * ([updateMovers], [updateDoors]) and the *producers* at steps 7–9 ([updatePlates],
 * [updateSwitches], [updateLasers]). So consumers read a snapshot of producer state that is
 * one tick old, and every consumer in a tick reads the *same* snapshot. That ordering is
 * deliberate: it makes a chain of plate → door → mover resolve identically no matter which
 * order the entities happen to sit in their lists, at a cost of 16 ms of latency that no
 * player can perceive. Resolving lazily per consumer would instead make the outcome depend on
 * list order, which is exactly the class of bug that desynchronises a replayed puzzle.
 *
 * ## Echoes are bodies
 *
 * Plates and switches scan `{player} ∪ world.echoes ∪ world.walkers`, never the player alone.
 * An Echo standing on a plate holds it, and an Echo's recorded interact press flips a switch,
 * because that is the only way a 30-second take can both open a door and walk through it.
 * [updatePlates] and [updateSwitches] run after the player has moved (step 5) and after
 * Echoes have advanced (step 6), so they read this tick's positions.
 *
 * ## Allocation
 *
 * Nothing here allocates per tick: entity state and the particle pool already exist, lists are
 * walked with indexed `for` loops, and the door direction memo is sized once per world. The
 * `Boolean` values written into `world.signals` box through `java.lang.Boolean.valueOf`, which
 * returns the two cached instances, so the map churns no garbage either.
 */
object PuzzleSystems {

    // ---- Tuning local to puzzle behaviour --------------------------------------------
    // GameConstants is frozen and owns cross-module tuning; these are read by nothing else.

    /**
     * Contact tolerance for a pressure plate. A standing body's feet rest *on* the plate's top
     * edge, and `overlaps` treats touching edges as not overlapping, so an unpadded test
     * flickers on and off with sub-pixel physics jitter — and a flickering plate re-fires
     * PLATE_PRESS and strobes every door it drives.
     */
    private const val PLATE_PAD = 0.12f

    /**
     * Contact tolerance for a teleporter pad — deliberately tighter than [PLATE_PAD]. Pads are
     * often authored as thin floor strips so the tolerance is still needed, but teleporting is
     * a large, disorienting event and must not trigger on a near miss.
     */
    private const val TELEPORT_PAD = 0.08f

    /** Camera kick on a teleport: enough to register as physical, not enough to disrupt aim. */
    private const val TELEPORT_CAMERA_KICK = 0.3f

    /**
     * Ceiling on waypoints crossed in a single tick. A fast mover on a path of short segments
     * legitimately crosses several, but the cap keeps a degenerate path (duplicate nodes, zero
     * dwell) from spinning the loop.
     */
    private const val MAX_NODE_STEPS = 8

    /** Below this, a distance is treated as zero — guards the segment-length division. */
    private const val EPSILON = 1e-4f

    // Particle tint slots. Palette.particleTint covers 0..7; 0..3 belong to the player
    // (jump/dash/land/death) and 4..5 to enemy fire, so puzzle effects take the top pair.
    private const val TINT_TELEPORT = 6
    private const val TINT_MECHANISM = 7

    // ---- Door travel direction memo --------------------------------------------------
    // DoorState is frozen and stores only `openAmount`, so "which way was this door last told
    // to move" has nowhere to live. Parked here as one BooleanArray keyed by door index and
    // scoped to a single WorldState, allocated on world change and never per tick — the same
    // shape EnemySystems uses for walker aggro locks.

    private var doorWorld: WorldState? = null
    private var doorCommandedOpen = BooleanArray(0)

    // ---- Signals ---------------------------------------------------------------------

    /**
     * Rebuilds this tick's signal snapshot from producer state.
     *
     * A plate contributes `pressed || latched` — a latching plate keeps emitting after the body
     * that triggered it has walked away, which is how one Echo "holds" a door for the rest of
     * the level. A switch contributes `on`.
     *
     * The map is cleared and repopulated in place; replacing it would allocate a HashMap every
     * tick and hand the renderer a different instance mid-frame.
     */
    fun resolveSignals(world: WorldState) {
        val signals = world.signals
        signals.clear()

        val plates = world.plates
        for (i in 0 until plates.size) {
            val plate = plates[i]
            signals[plate.spec.id] = plate.pressed || plate.latched
        }

        val switches = world.switches
        for (i in 0 until switches.size) {
            val state = switches[i]
            signals[state.spec.id] = state.on
        }
    }

    // ---- Plates ----------------------------------------------------------------------

    /**
     * Recomputes plate contact from every body in the world.
     *
     * `contacts` counts only bodies that can actually *trigger* this plate, so an `echoOnly`
     * plate does not visually sink under a player who cannot operate it — the renderer derives
     * press depth from this count, and a plate that depressed while emitting nothing would read
     * as a bug rather than as a rule.
     */
    fun updatePlates(world: WorldState, audio: AudioController) {
        val plates = world.plates
        val echoes = world.echoes
        val walkers = world.walkers

        for (i in 0 until plates.size) {
            val plate = plates[i]
            val spec = plate.spec
            val r = spec.rect
            var contacts = 0

            if (!spec.echoOnly) {
                val player = world.player
                if (player.alive &&
                    overlapsPadded(r.x, r.y, r.w, r.h, player.x, player.y, player.w, player.h, PLATE_PAD)
                ) contacts++
            }

            for (e in 0 until echoes.size) {
                val echo = echoes[e]
                if (overlapsPadded(r.x, r.y, r.w, r.h, echo.x, echo.y, echo.w, echo.h, PLATE_PAD)) contacts++
            }

            // Walkers are heavy enough to hold a plate, which makes "lure the guard onto the
            // switch" a solution the level author gets for free.
            if (!spec.echoOnly) {
                for (wi in 0 until walkers.size) {
                    val walker = walkers[wi]
                    if (overlapsPadded(
                            r.x, r.y, r.w, r.h,
                            walker.x, walker.y, walker.w, walker.h, PLATE_PAD,
                        )
                    ) contacts++
                }
            }

            val wasPressed = plate.pressed
            plate.contacts = contacts
            plate.pressed = contacts > 0

            if (!plate.pressed) continue
            if (spec.latching) plate.latched = true
            if (wasPressed) continue

            audio.play(SoundCue.PLATE_PRESS, 0.8f)
            val cx = r.centerX
            world.particles.emit(cx - 0.12f, r.y, -1.7f, -1.3f, 0.22f, 0.11f, TINT_MECHANISM)
            world.particles.emit(cx + 0.12f, r.y, 1.7f, -1.1f, 0.20f, 0.10f, TINT_MECHANISM)
        }
    }

    // ---- Switches --------------------------------------------------------------------

    /**
     * Toggles switches on an interact press from any body within [GameConstants.INTERACT_REACH],
     * and expires timed switches.
     *
     * `consumedThisTake` is the press edge: a held interact toggles once, and the guard clears
     * only when nobody is in reach, so an Echo frozen mid-press cannot rattle the switch every
     * tick. `holdSeconds > 0` makes it a timed switch that falls back off on its own — the basis
     * of every "an Echo flips it, you sprint for the door" puzzle.
     */
    fun updateSwitches(world: WorldState, dt: Float, audio: AudioController) {
        val switches = world.switches
        for (i in 0 until switches.size) {
            val state = switches[i]
            val spec = state.spec
            var toggledThisTick = false

            if (interactInReach(world, spec.rect)) {
                if (!state.consumedThisTake) {
                    state.consumedThisTake = true
                    state.on = !state.on
                    toggledThisTick = true
                    if (state.on) {
                        state.timer = spec.holdSeconds
                        audio.play(SoundCue.SWITCH_ON, 0.85f)
                    } else {
                        state.timer = 0f
                        audio.play(SoundCue.SWITCH_OFF, 0.85f)
                    }
                }
            } else {
                state.consumedThisTake = false
            }

            // Momentary switch, off, or just flipped this tick: nothing left to age.
            if (spec.holdSeconds <= 0f || !state.on || toggledThisTick) continue

            if (state.timer <= 0f) {
                // Invariant: expiry always clears `on`, and a toggle-on always writes a full
                // window, so "on with no time left" can only be a `startsOn` switch straight out
                // of SwitchState.reset(), which leaves the timer at zero. Arm it instead of
                // expiring it on the take's first tick — a timed switch that starts on and dies
                // immediately is indistinguishable from one that never started on at all.
                state.timer = spec.holdSeconds
                continue
            }

            state.timer -= dt
            if (state.timer <= 0f) {
                state.timer = 0f
                state.on = false
                audio.play(SoundCue.SWITCH_OFF, 0.8f)
            }
        }
    }

    /** True when the player or a live Echo is pressing interact within reach of [rect]. */
    private fun interactInReach(world: WorldState, rect: Rect): Boolean {
        val reach = GameConstants.INTERACT_REACH

        val player = world.player
        if (player.alive && player.interacting &&
            overlapsPadded(rect.x, rect.y, rect.w, rect.h, player.x, player.y, player.w, player.h, reach)
        ) return true

        val echoes = world.echoes
        for (i in 0 until echoes.size) {
            val echo = echoes[i]
            // An Echo past the end of its track holds its final recorded frame forever, and
            // `interacting` is a level read rather than an edge. A take that happened to end
            // mid-press would therefore jam the interact input on for the rest of the level and
            // lock every later body out of this switch. Core's own `EchoReplay.edge` refuses to
            // fire actions past the track end for exactly this reason; presence still counts
            // (an expired Echo keeps holding plates), only its *input* stops.
            if (echo.tick >= echo.replay.length) continue
            if (!echo.replay.interacting(echo.tick)) continue
            if (overlapsPadded(
                    rect.x, rect.y, rect.w, rect.h,
                    echo.x, echo.y, echo.w, echo.h, reach,
                )
            ) return true
        }
        return false
    }

    // ---- Doors -----------------------------------------------------------------------

    /**
     * Animates each door toward its commanded state at `1 / travelSeconds` per second.
     *
     * Doors sweep rather than pop because `DoorState.solid` flips at 0.85: the travel is the
     * player's read on whether they can make it through, so it has to be visible. The
     * open/close cue fires on a change of *direction*, including a reversal caught mid-sweep,
     * and is suppressed when the door is already parked at the target — after `resetTake()`
     * every door is shut, and a shut door being told to close must not announce itself.
     */
    fun updateDoors(world: WorldState, dt: Float, audio: AudioController) {
        val doors = world.doors
        // Bind first even for a doorless level: rebinding releases the previous world, so a
        // finished level is not held alive by this memo.
        val commandedOpen = doorCommands(world)
        if (doors.isEmpty()) return

        for (i in 0 until doors.size) {
            val door = doors[i]
            val spec = door.spec
            val opening = world.evaluate(spec.opensWhen)

            if (opening != commandedOpen[i]) {
                commandedOpen[i] = opening
                if (opening) {
                    if (door.openAmount < 1f) audio.play(SoundCue.DOOR_OPEN, 0.7f)
                } else {
                    if (door.openAmount > 0f) audio.play(SoundCue.DOOR_CLOSE, 0.7f)
                }
            }

            val target = if (opening) 1f else 0f
            door.openAmount =
                if (spec.travelSeconds <= 0f) target
                else approach(door.openAmount, target, dt / spec.travelSeconds)
        }
    }

    /** Per-world door direction memo, reallocated (and therefore cleared) when the world changes. */
    private fun doorCommands(world: WorldState): BooleanArray {
        if (doorWorld !== world || doorCommandedOpen.size < world.doors.size) {
            doorCommandedOpen = BooleanArray(world.doors.size)
            doorWorld = world
        }
        return doorCommandedOpen
    }

    // ---- Movers ----------------------------------------------------------------------

    /**
     * Walks each mover along its path and publishes the per-tick delta as `dx`/`dy`.
     *
     * Physics reads those deltas to carry riders, which is why the delta is measured from the
     * resolved positions rather than derived from `speed * dt`: dwell pauses, waypoint corners
     * and a signal reversal all make the two disagree, and a rider must inherit what the
     * platform *did*, not what it intended.
     *
     * A path of fewer than two nodes, or a non-positive speed, is a static platform — it holds
     * its reset position and reports a zero delta rather than dividing by a zero-length segment.
     */
    fun updateMovers(world: WorldState, dt: Float) {
        val movers = world.movers
        for (i in 0 until movers.size) {
            val mover = movers[i]
            val spec = mover.spec
            mover.prevX = mover.x
            mover.prevY = mover.y

            if (spec.path.size < 2 || spec.speed <= 0f) {
                mover.dx = 0f
                mover.dy = 0f
                continue
            }

            when (spec.mode) {
                // Shuttles its path forever, reversing at each end.
                MoverMode.LOOP -> advanceAlongPath(mover, dt, pingPong = true)

                // The elevator: climbs while its signal holds, sinks back when it drops, and
                // parks at whichever end it reaches. `activeWhen == null` evaluates true, so an
                // unauthored SIGNAL mover simply runs to the end of its path and stays there.
                MoverMode.SIGNAL -> {
                    val active = world.evaluate(spec.activeWhen)
                    if (active != mover.forward) reverseDirection(mover)
                    advanceAlongPath(mover, dt, pingPong = false)
                }

                // Latches on the signal's first rise, then runs the path once and stops. The
                // latch is what makes it a one-shot: the signal may fall again immediately.
                MoverMode.ONE_SHOT -> {
                    if (!mover.triggered && world.evaluate(spec.activeWhen)) mover.triggered = true
                    if (mover.triggered) advanceAlongPath(mover, dt, pingPong = false)
                }
            }

            mover.dx = mover.x - mover.prevX
            mover.dy = mover.y - mover.prevY
        }
    }

    /**
     * Advances one mover by `speed * dt` along its path in its current direction.
     *
     * `node` is the waypoint the body departed from and `nodeProgress` its 0..1 fraction toward
     * the next one in the direction of travel. Leftover distance carries across waypoints so a
     * fast mover on short segments does not stall at every corner.
     */
    private fun advanceAlongPath(mover: MoverState, dt: Float, pingPong: Boolean) {
        if (mover.dwell > 0f) {
            mover.dwell -= dt
            return
        }

        val path = mover.spec.path
        var remaining = mover.spec.speed * dt
        var steps = 0

        while (remaining > EPSILON && steps < MAX_NODE_STEPS) {
            steps++
            val toIndex = if (mover.forward) mover.node + 1 else mover.node - 1

            if (toIndex < 0 || toIndex >= path.size) {
                if (!pingPong) {
                    // End of a one-way run: hold position instead of clamping to the far node.
                    mover.nodeProgress = 0f
                    return
                }
                mover.forward = !mover.forward
                continue
            }

            val from = path[mover.node]
            val to = path[toIndex]
            val segX = to.x - from.x
            val segY = to.y - from.y
            val segLength = sqrt(segX * segX + segY * segY)

            if (segLength <= EPSILON) {
                // Duplicate waypoints would divide by zero; treat them as already reached.
                arriveAt(mover, toIndex, to.x, to.y)
                if (mover.dwell > 0f) return
                continue
            }

            val travelled = mover.nodeProgress * segLength + remaining
            if (travelled < segLength) {
                mover.nodeProgress = travelled / segLength
                mover.x = from.x + segX * mover.nodeProgress
                mover.y = from.y + segY * mover.nodeProgress
                return
            }

            remaining = travelled - segLength
            arriveAt(mover, toIndex, to.x, to.y)
            if (mover.dwell > 0f) return
        }
    }

    /** Snaps a mover onto a waypoint and starts its dwell pause. */
    private fun arriveAt(mover: MoverState, node: Int, x: Float, y: Float) {
        mover.node = node
        mover.nodeProgress = 0f
        mover.x = x
        mover.y = y
        mover.dwell = mover.spec.dwellSeconds
    }

    /**
     * Flips a mover's direction without moving it.
     *
     * Re-anchoring is the whole point: progress is measured from `node` toward the *next* node
     * in the direction of travel, so after a flip the same fraction would describe a completely
     * different segment and the platform would teleport. Anchoring onto the node it was heading
     * for and inverting the fraction keeps the body exactly where it is — which matters because
     * a SIGNAL mover reverses mid-segment every time the player steps off the plate.
     */
    private fun reverseDirection(mover: MoverState) {
        if (mover.nodeProgress > 0f) {
            val path = mover.spec.path
            val toIndex = if (mover.forward) mover.node + 1 else mover.node - 1
            if (toIndex >= 0 && toIndex < path.size) {
                mover.node = toIndex
                mover.nodeProgress = 1f - mover.nodeProgress
            } else {
                mover.nodeProgress = 0f
            }
        }
        mover.forward = !mover.forward
    }

    // ---- Lasers ----------------------------------------------------------------------

    /**
     * Advances every laser's duty cycle and drives its beam.
     *
     * The cursor keeps running even while a beam is suppressed by its signal, so a corridor
     * authored as a rhythm stays phase-locked: cutting one beam must not desynchronise it from
     * its neighbours when the signal returns, or a memorised crossing stops working.
     *
     * `LaserState.reset()` seeds `cycle` with `spec.phase`, so the authored offset is already
     * baked into the cursor and must not be applied a second time here.
     */
    fun updateLasers(world: WorldState, dt: Float, audio: AudioController) {
        val lasers = world.lasers
        for (i in 0 until lasers.size) {
            val laser = lasers[i]
            val spec = laser.spec
            val period = spec.onSeconds + spec.offSeconds

            var cycle = laser.cycle + dt
            if (period > 0f) {
                cycle %= period
                if (cycle < 0f) cycle += period
            }
            laser.cycle = cycle

            // A degenerate cycle is a constant beam: all-on when it has an on window, otherwise
            // permanently dark. Either is authored intent; neither may divide by the period.
            val litByCycle = if (period <= 0f) spec.onSeconds > 0f else cycle < spec.onSeconds

            val wasOn = laser.on
            laser.on = litByCycle && !signalHolds(world, spec.disabledWhen)
            if (laser.on && !wasOn) audio.play(SoundCue.LASER_ON, 0.55f)
        }
    }

    /** True when the AABB is inside any beam that is currently firing. */
    fun laserLethalAt(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean {
        val lasers = world.lasers
        for (i in 0 until lasers.size) {
            val laser = lasers[i]
            if (!laser.on) continue
            val r = laser.spec.rect
            if (overlaps(x, y, w, h, r.x, r.y, r.w, r.h)) return true
        }
        return false
    }

    // ---- Teleporters -----------------------------------------------------------------

    /**
     * Moves the player between a teleporter's two pads.
     *
     * Only the player travels. An Echo's position comes straight from its recording, so
     * relocating one would desynchronise it from the take it is replaying — an Echo that used a
     * teleporter already has the trip baked into its track.
     */
    fun updateTeleporters(world: WorldState, dt: Float, audio: AudioController) {
        val teleporters = world.teleporters
        if (teleporters.isEmpty()) return

        val player = world.player
        for (i in 0 until teleporters.size) {
            val state = teleporters[i]
            if (state.cooldown > 0f) {
                state.cooldown -= dt
                continue
            }
            if (!player.alive) continue

            val a = state.spec.a
            val b = state.spec.b
            if (overlapsPadded(a.x, a.y, a.w, a.h, player.x, player.y, player.w, player.h, TELEPORT_PAD)) {
                teleport(world, state, a, b, audio)
            } else if (overlapsPadded(b.x, b.y, b.w, b.h, player.x, player.y, player.w, player.h, TELEPORT_PAD)) {
                teleport(world, state, b, a, audio)
            }
        }
    }

    private fun teleport(
        world: WorldState,
        state: TeleporterState,
        from: Rect,
        to: Rect,
        audio: AudioController,
    ) {
        val player = world.player
        player.x = to.centerX - player.w * 0.5f
        player.y = to.centerY - player.h * 0.5f
        // Rendering interpolates prev -> current, so leaving `prev` at the entry pad would smear
        // the player across the level for a frame. A teleport is the one motion with no in-between.
        player.prevX = player.x
        player.prevY = player.y
        // Momentum survives the trip — a teleporter redirects, it does not brake — but the rider
        // link cannot: the body is no longer standing on whatever it left behind.
        player.carrierId = null

        state.cooldown = state.spec.cooldownSeconds
        audio.play(SoundCue.TELEPORT, 0.9f)
        emitPadBurst(world, from)
        emitPadBurst(world, to)
        world.camera.kick(TELEPORT_CAMERA_KICK)
    }

    /** Three upward sparks, so both ends of the trip read as connected. */
    private fun emitPadBurst(world: WorldState, pad: Rect) {
        val particles = world.particles
        val cx = pad.centerX
        val cy = pad.centerY
        particles.emit(cx, cy, -2.6f, -3.4f, 0.32f, 0.16f, TINT_TELEPORT)
        particles.emit(cx, cy, 2.6f, -3.1f, 0.30f, 0.14f, TINT_TELEPORT)
        particles.emit(cx, cy, 0f, -4.6f, 0.36f, 0.12f, TINT_TELEPORT)
    }

    // ---- Signal gates ----------------------------------------------------------------

    /**
     * Whether a *negative* gate is currently holding.
     *
     * [WorldState.evaluate] treats a null or empty condition as "always true", which is correct
     * for `opensWhen` (an unconditioned door is an open door) and exactly wrong for
     * `disabledWhen` (an unconditioned laser must never be disabled). Routing negative gates
     * through here stops an unauthored field from silently switching a hazard off forever.
     *
     * Duplicated from the same helper in EnemySystems on purpose: both modules are leaves, and
     * sharing it would couple puzzle simulation to enemy simulation for four lines.
     */
    private fun signalHolds(world: WorldState, cond: SignalCondition?): Boolean =
        cond != null && cond.ids.isNotEmpty() && world.evaluate(cond)
}
