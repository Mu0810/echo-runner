package com.echorunner

import com.echorunner.core.GameConstants
import com.echorunner.core.GamePhase
import com.echorunner.core.InputState
import com.echorunner.core.LaserState
import com.echorunner.core.LevelSpec
import com.echorunner.core.MoverState
import com.echorunner.core.NoHaptics
import com.echorunner.core.SignalCondition
import com.echorunner.core.SilentAudio
import com.echorunner.core.SignalMode
import com.echorunner.core.WalkerState
import com.echorunner.engine.GameEngine
import com.echorunner.engine.Physics
import kotlin.math.abs
import kotlin.math.sign

/**
 * An automated player that plans a level from its own signal graph, then executes the plan
 * through the real engine.
 *
 * ## Why a planner and not a pathfinder
 *
 * The previous bot walked to every plate and switch in x order, one per take. That is not what
 * these levels ask. A room's difficulty lives in *which* signals the exit needs and *who* has to
 * be standing where when the player walks through — so the bot now reads
 * [LevelSpec.exitCondition] and every [com.echorunner.core.DoorSpec.opensWhen], resolves the ids
 * back to their producers, and spends one Echo per producer. Objectives the level does not
 * actually gate on are skipped, which matters when the Echo budget is exactly the number of
 * required plates.
 *
 * ## The load-bearing engine fact
 *
 * A spent Echo keeps holding a plate. [com.echorunner.echo.EchoManager.advance] sets
 * `active = false` when a recording runs out but leaves the body at its final coordinates, and
 * `updatePlates` counts every Echo regardless of `active`. So banking a take early is safe as
 * long as the last recorded pose is *on* the plate — the plate stays held for the rest of the
 * level. The bot relies on this: it walks onto a plate, settles, and banks immediately instead of
 * burning thirty seconds of wall clock per take.
 *
 * ## What it still cannot do
 *
 * It does not solve for *timing* — a puzzle whose answer is "be on the plate at second 12, not
 * second 2" is beyond it, because it never plans a take around another Echo's schedule. Failures
 * are therefore reported with a reason, and only the reasons that indicate broken geometry are
 * ever asserted on. A bot that cannot finish a room is not evidence the room is unfinishable.
 */
class Autoplayer(private val spec: LevelSpec) {

    private val engine = GameEngine(SilentAudio, NoHaptics)
    private val world get() = engine.world!!
    private val p get() = world.player

    /** Ticks spent waiting for a beam or an enemy before giving up on patience. */
    private var patience = 0

    // ---- perception -------------------------------------------------------------------

    private fun groundAhead(dir: Int, lookahead: Float): Boolean =
        Physics.solidOverlap(world, p.x + dir * lookahead, p.y + p.h + 0.05f, p.w, 0.35f)

    private fun wallAhead(dir: Int): Boolean =
        Physics.solidOverlap(world, p.x + dir * 0.45f, p.y, p.w, p.h * 0.9f)

    /** An armed beam close enough ahead to walk into, whose span covers our body. */
    private fun beamAhead(dir: Int): LaserState? = world.lasers.firstOrNull { l ->
        if (!l.on) return@firstOrNull false
        val r = l.spec.rect
        val gap = (r.centerX - (p.x + p.w * 0.5f)) * dir
        gap in 0f..2.2f && r.y < p.y + p.h && r.bottom > p.y
    }

    /** A walker on our level, ahead, close enough that walking on means walking into it. */
    private fun walkerAhead(dir: Int): WalkerState? = world.walkers.firstOrNull { w ->
        val gap = (w.x - p.x) * dir
        gap in 0f..3.0f && abs(w.y - p.y) < 2.0f
    }

    /**
     * The surface the objective stands on: the platform whose top is a body's height below it and
     * whose span covers it. Null when the objective is on a lift or in mid-air.
     */
    private fun supportUnder(targetX: Float, targetY: Float?): com.echorunner.core.Rect? {
        if (targetY == null) return null
        val feet = targetY + GameConstants.PLAYER_H
        return spec.platforms
            .map { it.rect }
            .filter { targetX >= it.x - 0.3f && targetX <= it.right + 0.3f && abs(it.y - feet) < 0.75f }
            .minByOrNull { abs(it.y - feet) }
    }

    /** The lift we are standing on, if any. */
    private fun moverUnderfoot(): MoverState? = world.movers.firstOrNull { m ->
        val top = m.y
        val feet = p.y + p.h
        feet > top - 0.30f && feet < top + 0.45f &&
            p.x + p.w > m.x && p.x < m.x + m.spec.size.w
    }

    // ---- primitives -------------------------------------------------------------------

    private fun tick(input: InputState): Boolean {
        engine.step(input)
        return world.phase == GamePhase.PLAYING
    }

    private fun stand(ticks: Int) {
        repeat(ticks) { if (!tick(InputState.NONE)) return }
    }

    /** Runs the engine through a non-PLAYING interlude (take transition, death) back to play. */
    private fun settle(limit: Int = 400) {
        var n = 0
        while (world.phase != GamePhase.PLAYING && n < limit) {
            if (world.phase == GamePhase.COMPLETE || world.phase == GamePhase.OUT_OF_ECHOES) return
            engine.step(InputState.NONE)
            n++
        }
    }

    /**
     * Walks and jumps toward [targetX], climbing to [targetY] when it is above.
     *
     * The behaviour cascade is ordered by what kills a run: a beam or an enemy is fatal and
     * waiting is free, so patience comes before movement. Riding comes next because a lift that
     * is already carrying us upward does its job only if we stop steering.
     */
    private fun goTo(targetX: Float, targetY: Float?, budget: Int, landed: Boolean = false): Boolean {
        var jumpHold = 0
        var stuckAt = p.x
        var stuckTicks = 0
        patience = 0

        // When the objective sits on a ledge, aim at the ledge's near edge first. Jumping straight
        // up from underneath cannot land on a platform, and the bot used to spend an entire take
        // bouncing against its underside.
        var stagedX: Float? = null
        if (targetY != null && p.y > targetY + 2.0f) {
            val support = supportUnder(targetX, targetY)
            if (support != null && (p.x + p.w > support.x) && (p.x < support.right)) {
                stagedX = if (p.x + p.w * 0.5f < support.centerX) support.x - 1.1f else support.right + 1.1f
            }
        }

        repeat(budget) {
            if (world.phase != GamePhase.PLAYING) return false
            val centre = p.x + p.w * 0.5f
            val dx = targetX - centre
            // A plate is not "reached" from three units overhead. `landed` demands the body be
            // standing at the objective's own height — without it the bot sailed over plates
            // mid-jump, declared arrival, and then reported the plate as faulty.
            val atHeight = when {
                targetY == null -> true
                landed -> p.onGround && abs(p.y - targetY) < 0.8f
                else -> p.y <= targetY + 0.65f
            }
            val there = abs(dx) < 0.32f && atHeight
            if (there) return true

            // Steer to the staging point until we are clear of the ledge we must climb onto.
            val aim = stagedX?.takeIf { p.y > targetY!! + 2.0f } ?: targetX
            if (stagedX != null && abs((p.x + p.w * 0.5f) - stagedX!!) < 0.6f) stagedX = null
            val dx2 = aim - centre
            val dir = if (dx2 >= 0f) 1 else -1

            // Riding: a lift moving us toward the objective is doing the work. Steering off it
            // is the single most common way an automated player loses a vertical route.
            val lift = moverUnderfoot()
            if (lift != null && targetY != null && p.y > targetY + 0.9f && lift.dy < -0.0005f) {
                tick(InputState.NONE)
                return@repeat
            }

            // Patience: beams cycle and walkers patrol. Waiting costs take time, which the
            // budget already accounts for; walking into either costs the whole take.
            if (patience < MAX_PATIENCE) {
                if (beamAhead(dir) != null) { patience++; tick(InputState.NONE); return@repeat }
                val walker = walkerAhead(dir)
                if (walker != null) {
                    val closing = (walker.dir > 0) != (dir > 0)
                    if (closing && abs(walker.x - p.x) < 1.9f && p.onGround) {
                        jumpHold = 20 // hop it rather than trade places
                    } else if (!closing || abs(walker.x - p.x) > 1.9f) {
                        patience++
                        tick(InputState.NONE)
                        return@repeat
                    }
                }
            }

            var wantJump = false
            if (p.onGround && !groundAhead(dir, 0.9f)) wantJump = true
            if (p.onGround && wallAhead(dir)) wantJump = true
            if (p.onGround && targetY != null && p.y > targetY + 0.65f && stagedX == null && abs(dx2) < 4.5f) wantJump = true
            if (abs(p.x - stuckAt) < 0.02f) stuckTicks++ else { stuckTicks = 0; stuckAt = p.x }
            if (stuckTicks > 12 && p.onGround) { wantJump = true; stuckTicks = 0 }

            // 20 ticks clears the 3.2-unit designed climb with margin; a 14-tick hold used to
            // fall short of the taller ledges because the jump cut caps the arc early.
            if (wantJump) jumpHold = 20
            val holding = jumpHold > 0
            if (jumpHold > 0) jumpHold--

            tick(InputState(moveX = dir.toFloat(), jumpHeld = holding))
        }
        // Height matters as much as distance. This used to return on horizontal proximity alone,
        // so a bot standing on the floor *underneath* a ledge plate reported that it had arrived,
        // stood there for twenty ticks, and then blamed the plate for not registering a press.
        return abs((p.x + p.w * 0.5f) - targetX) < 0.6f && when {
            targetY == null -> true
            landed -> p.onGround && abs(p.y - targetY) < 0.8f
            else -> p.y <= targetY + 0.8f
        }
    }

    /**
     * Boards a signalled lift and rides it to [targetY].
     *
     * Walks to the lift's column, waits for the platform to arrive at foot level, steps aboard,
     * then stops steering. Called only when a plain [goTo] has already failed to climb, so the
     * cost of the extra waiting is paid on routes that genuinely need it.
     */
    private fun rideLift(targetY: Float, budget: Int): Boolean {
        val lift = world.movers.firstOrNull() ?: return false
        var spent = 0
        // Meet it at its lowest waypoint: standing under a lift that is still up top does nothing.
        val boardX = lift.spec.path.minByOrNull { it.y }?.x ?: lift.x
        if (!goTo(boardX + lift.spec.size.w * 0.5f, null, (budget * 0.5f).toInt())) return false
        while (spent < budget && world.phase == GamePhase.PLAYING) {
            if (moverUnderfoot() != null && p.y <= targetY + 0.65f) return true
            tick(InputState.NONE)
            spent++
        }
        return false
    }

    // ---- planning ---------------------------------------------------------------------

    /** Ids the level genuinely gates on: the exit, plus every door and every signalled lift. */
    private fun requiredIds(): List<String> {
        val out = LinkedHashSet<String>()
        fun take(c: SignalCondition?) {
            if (c == null || c.invert) return // an inverted gate is open until fired: leave it
            when (c.mode) {
                SignalMode.ALL -> out.addAll(c.ids)
                // ANY needs only one producer; the cheapest is the nearest to the spawn.
                SignalMode.ANY -> c.ids.minByOrNull { id ->
                    producerX(id)?.let { abs(it - spec.spawn.x) } ?: Float.MAX_VALUE
                }?.let { out.add(it) }
            }
        }
        take(spec.exitCondition)
        spec.doors.forEach { take(it.opensWhen) }
        spec.movers.forEach { take(it.activeWhen) }
        return out.toList()
    }

    private fun producerX(id: String): Float? =
        spec.plates.firstOrNull { it.id == id }?.rect?.centerX
            ?: spec.switches.firstOrNull { it.id == id }?.rect?.centerX

    /**
     * A signal the plan must satisfy.
     *
     * The split is the whole planner. A **hold** needs a body parked on it, which costs an entire
     * Echo. A **tap** only needs to be touched once — a latching plate keeps its detent for the
     * rest of the take, and a switch stays thrown — so it costs nothing but a detour, and the
     * Echo that replays the take taps it again for free.
     *
     * Conflating the two is what made 1-4 look unsolvable: it gates two doors, but only one of
     * its plates is plain. Counting both as holds demanded two Echoes from a one-Echo room and
     * reported a design bug that was not there. The level's own hint says so — "the latching
     * plate needs no body at all once it is struck, the plain one still costs you a whole
     * timeline."
     */
    private data class Signal(
        val id: String,
        val x: Float,
        val y: Float,
        val interact: Boolean,
        val holdSeconds: Float,
        val hold: Boolean,
    )

    private fun signals(): List<Signal> = requiredIds().mapNotNull { id ->
        spec.plates.firstOrNull { it.id == id }?.let {
            Signal(
                id = id,
                x = it.rect.centerX,
                y = it.rect.y - GameConstants.PLAYER_H,
                interact = false,
                holdSeconds = 0f,
                // A latching plate is struck once and stays down; only a plain plate needs a body.
                hold = !it.latching,
            )
        } ?: spec.switches.firstOrNull { it.id == id }?.let {
            Signal(id, it.rect.centerX, it.rect.y - GameConstants.PLAYER_H, true, it.holdSeconds, hold = false)
        }
    }

    /** Walks a chain of waypoints, then the destination. Returns the waypoint that defeated us. */
    private fun runRoute(waypoints: List<Signal>, toX: Float, toY: Float?, landed: Boolean = false): String? {
        for (w in waypoints) {
            val budget = world.takeTicks - world.tick - 20
            if (budget <= 0) return w.id
            if (!goTo(w.x, w.y, budget, landed = true)) return w.id
            if (w.interact) {
                val holdTicks = maxOf(12, (w.holdSeconds * GameConstants.TICK_HZ).toInt() + 12)
                repeat(holdTicks) { if (!tick(InputState(interactHeld = true))) return@repeat }
            }
        }
        val budget = world.takeTicks - world.tick - 5
        if (budget <= 0) return "time"
        return if (goTo(toX, toY, budget, landed)) null else "destination"
    }

    // ---- execution --------------------------------------------------------------------

    fun solve(): Solve {
        engine.load(spec)
        engine.beginPlaying()
        settle()

        val all = signals()
        val holds = all.filter { it.hold }.sortedBy { abs(it.x - spec.spawn.x) }
        val taps = all.filterNot { it.hold }.sortedBy { abs(it.x - spec.spawn.x) }

        if (holds.size > spec.maxEchoes) {
            return Solve(
                spec.id, false, 0,
                "plan needs ${holds.size} bodies parked on plain plates but the budget is ${spec.maxEchoes} Echoes",
            )
        }

        // One take per plate that needs holding. Latching plates and switches are tapped on the
        // way past, in the same take, exactly as a human would.
        for (job in holds) {
            if (world.phase != GamePhase.PLAYING) settle()
            if (world.phase != GamePhase.PLAYING) break
            if (world.echoCount >= spec.maxEchoes) break

            val enRoute = taps.filter { it.x.between(spec.spawn.x, job.x) }
            val blocker = runRoute(enRoute, job.x, job.y, landed = true)
            if (blocker != null) {
                return Solve(spec.id, false, world.takeIndex, "could not reach ${if (blocker == "destination") job.id else blocker}")
            }

            val echoOnly = spec.plates.firstOrNull { it.id == job.id }?.echoOnly == true
            // Settle on the spot so the final recorded pose is squarely on the plate: that pose
            // is what holds the signal once this take becomes a spent Echo.
            stand(20)
            if (!echoOnly && !world.signal(job.id)) {
                // Carry the evidence: a plate that cannot be pressed is a game bug, while a body
                // parked next to one instead of on it is a bot bug, and the note has to say which.
                val st = world.plates.first { it.spec.id == job.id }
                val r = st.spec.rect
                return Solve(
                    spec.id, false, world.takeIndex,
                    "${job.id} not pressed: body at (${fmt(p.x)}, ${fmt(p.y)}) feet=${fmt(p.y + p.h)} " +
                        "plate=[${fmt(r.x)}..${fmt(r.right)}, top ${fmt(r.y)}] contacts=${st.contacts} onGround=${p.onGround}",
                )
            }
            engine.endTakeNow()
            settle()
        }

        // Final run: every plain plate is now held by a spent Echo, so the player only has to
        // tap the latching gates on the way and walk out.
        val exitX = spec.exit.centerX
        val exitY = spec.exit.y + spec.exit.h - GameConstants.PLAYER_H
        repeat(2) {
            if (world.phase == GamePhase.COMPLETE) return@repeat
            if (world.phase != GamePhase.PLAYING) settle()
            if (world.phase != GamePhase.PLAYING) return@repeat
            if (runRoute(taps, exitX, exitY) != null && world.phase == GamePhase.PLAYING) {
                // A vertical route we could not walk is the signature of a lift we never boarded.
                if (spec.movers.isNotEmpty()) rideLift(exitY, 240)
                goTo(exitX, exitY, maxOf(60, world.takeTicks - world.tick - 5))
            }
        }

        return if (world.phase == GamePhase.COMPLETE) {
            Solve(spec.id, true, world.takeIndex + 1, "cleared")
        } else {
            val sealed = spec.exitCondition?.let { !world.evaluate(it) } ?: false
            val shut = spec.doors.filter { !world.evaluate(it.opensWhen) }.map { it.id }
            val why = when {
                sealed -> "exit still sealed: ${spec.exitCondition?.ids?.joinToString("+")} not held"
                world.phase == GamePhase.OUT_OF_ECHOES -> "out of Echoes at x=${fmt(p.x)}, exit x=${fmt(exitX)}"
                shut.isNotEmpty() -> "stopped at x=${fmt(p.x)} with ${shut.joinToString("+")} still shut"
                else -> "stopped at x=${fmt(p.x)} short of exit x=${fmt(exitX)}"
            }
            Solve(spec.id, false, world.takeIndex + 1, why)
        }
    }

    private fun Float.between(a: Float, b: Float): Boolean =
        this >= minOf(a, b) - 0.5f && this <= maxOf(a, b) + 0.5f

    private fun fmt(v: Float) = "%.1f".format(v)

    private companion object {
        /** Waiting is free but a level with a permanently armed beam must not hang the suite. */
        const val MAX_PATIENCE = 260
    }
}

/** Outcome of one automated attempt. */
data class Solve(
    val levelId: String,
    val cleared: Boolean,
    val takesUsed: Int,
    val note: String,
)
