package com.echorunner.engine

import com.echorunner.core.GameConstants
import com.echorunner.core.MoverState
import com.echorunner.core.PlatformKind
import com.echorunner.core.PlayerBody
import com.echorunner.core.WorldState
import com.echorunner.core.clamp
import com.echorunner.core.overlaps
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/**
 * Collision and integration for the only body that needs it: the player.
 *
 * Echoes are kinematic replays of a path already known to be valid and never come through
 * here; enemies do their own trivial axis motion. So this file is deliberately one AABB
 * against level geometry, and nothing else.
 *
 * The world is **y-down**: gravity is +y, a jump is -y, and a platform's top edge is its `y`,
 * so a body standing on a platform satisfies `body.y + body.h == platform.y`.
 */
object Physics {

    /**
     * Longest displacement resolved in a single collision step.
     *
     * A dash covers `27 * 1/60 = 0.45` units per tick, which is enough to straddle a thin
     * platform entirely and be resolved as "no overlap" on both sides of it. Cutting motion
     * into pieces no longer than this makes tunnelling geometrically impossible for every
     * speed the game can produce.
     */
    private const val SUBSTEP_DISTANCE = 0.4f
    private const val MAX_SUBSTEPS = 8

    /** Thin band probed below the feet so resting contact survives `vy` being zeroed. */
    private const val GROUND_PROBE = 0.06f

    /**
     * Slack on the "was this body above that platform" test and on de-penetration.
     *
     * Comfortably above float error accumulated across a 40-unit world, far below anything a
     * player can see or feel.
     */
    private const val EDGE_TOLERANCE = 0.02f

    /** Half-height of the band used to decide the player is riding a mover. */
    private const val CARRY_PROBE = 0.08f

    /**
     * Integrates the player one tick and resolves it out of the world.
     *
     * Axis-separated: move X and resolve X, then move Y and resolve Y. Solving both axes at
     * once from a single overlap makes a body running along a floor snag on the seam between
     * two adjacent platforms, because the shallowest escape from a corner overlap is
     * arbitrary. Separating the axes removes the ambiguity entirely.
     */
    fun movePlayer(world: WorldState, dt: Float) {
        val p = world.player

        // Rendering interpolates prev -> current, so the pre-move pose is captured before
        // anything touches the body. The ONE_WAY crossing test also reads the tick's starting
        // bottom edge from here, which is why it must be written first and not later.
        p.prevX = p.x
        p.prevY = p.y

        val w = p.w
        val h = p.h
        val prevBottom = p.prevY + h

        applyCarrier(world, p)

        val steps = substepCount(p.vx, p.vy, dt)
        val sdt = dt / steps
        var landed = false

        for (step in 0 until steps) {
            // ---- X ---------------------------------------------------------------------
            if (p.vx != 0f) {
                val nx = p.x + p.vx * sdt
                val movingRight = p.vx > 0f
                var resolved = nx
                var blocked = false
                forEachBlocker(world, includeOneWay = false, prevBottom = prevBottom) { rx, ry, rw, rh ->
                    if (overlaps(nx, p.y, w, h, rx, ry, rw, rh)) {
                        // Snap flush against the blocking face, keeping the nearest one when
                        // several overlap: anything else lets a body pass through the second.
                        val edge = if (movingRight) rx - w else rx + rw
                        if (!blocked) {
                            resolved = edge
                            blocked = true
                        } else if (movingRight) {
                            if (edge < resolved) resolved = edge
                        } else if (edge > resolved) {
                            resolved = edge
                        }
                    }
                }
                p.x = resolved
                if (blocked) p.vx = 0f
            }

            // ---- Y ---------------------------------------------------------------------
            if (p.vy != 0f) {
                val ny = p.y + p.vy * sdt
                val movingDown = p.vy > 0f
                var resolved = ny
                var blocked = false
                forEachBlocker(world, includeOneWay = movingDown, prevBottom = prevBottom) { rx, ry, rw, rh ->
                    if (overlaps(p.x, ny, w, h, rx, ry, rw, rh)) {
                        val edge = if (movingDown) ry - h else ry + rh
                        if (!blocked) {
                            resolved = edge
                            blocked = true
                        } else if (movingDown) {
                            if (edge < resolved) resolved = edge
                        } else if (edge > resolved) {
                            resolved = edge
                        }
                    }
                }
                p.y = resolved
                if (blocked) {
                    if (movingDown) landed = true
                    p.vy = 0f
                }
            }
        }

        // Horizontally the world is a closed box. The floor is not: falling past WORLD_H is a
        // pit death, checked by the orchestrator, so the bottom is deliberately unclamped.
        val maxX = GameConstants.WORLD_W - w
        if (p.x < 0f) {
            p.x = 0f
            if (p.vx < 0f) p.vx = 0f
        } else if (p.x > maxX) {
            p.x = maxX
            if (p.vx > 0f) p.vx = 0f
        }

        // Resting contact. Once a landing zeroes `vy` the sweep above reports nothing, so
        // standing still is confirmed with a short probe under the feet. Skipped while rising
        // so the launch tick of a jump is never counted as grounded — otherwise coyote time
        // refreshes mid-jump and the player gets a free second one.
        p.onGround = landed || (p.vy >= 0f && groundedCheck(world, p.x, p.y, w, h))
    }

    /**
     * True when the box overlaps geometry that blocks from every direction: `SOLID`/`DECOR`
     * platforms, shut doors, and movers.
     *
     * ONE_WAY platforms are excluded by construction — whether one blocks depends on travel
     * direction and where the body started the tick, neither of which a bare position query
     * knows. [movePlayer] applies that rule itself.
     */
    fun solidOverlap(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean {
        var hit = false
        forEachBlocker(world, includeOneWay = false, prevBottom = 0f) { rx, ry, rw, rh ->
            if (!hit && overlaps(x, y, w, h, rx, ry, rw, rh)) hit = true
        }
        return hit
    }

    /**
     * True when something supports the box from directly below.
     *
     * A ONE_WAY ledge does support a body resting on it, so those are included here — passing
     * the feet line as `prevBottom` reduces the crossing test to "this platform's top edge is
     * at or below my feet", which is exactly the support condition.
     */
    fun groundedCheck(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean {
        val feet = y + h
        var hit = false
        forEachBlocker(world, includeOneWay = true, prevBottom = feet) { rx, ry, rw, rh ->
            if (!hit && overlaps(x, feet, w, GROUND_PROBE, rx, ry, rw, rh)) hit = true
        }
        return hit
    }

    /**
     * Visits every rect that can stop the player, as bare floats so no Rect is allocated.
     *
     * ONE_WAY platforms are only offered when [includeOneWay] is set (downward motion) *and*
     * the body's [prevBottom] started at or above the platform's top edge. That pair of
     * conditions is the whole affordance: jump up through a ledge, land on it coming down.
     */
    private inline fun forEachBlocker(
        world: WorldState,
        includeOneWay: Boolean,
        prevBottom: Float,
        action: (rx: Float, ry: Float, rw: Float, rh: Float) -> Unit,
    ) {
        val platforms = world.level.platforms
        for (i in platforms.indices) {
            val platform = platforms[i]
            val r = platform.rect
            if (platform.kind == PlatformKind.ONE_WAY) {
                if (includeOneWay && prevBottom <= r.y + EDGE_TOLERANCE) action(r.x, r.y, r.w, r.h)
            } else {
                // SOLID and DECOR are both fully solid; DECOR differs only in how it is drawn.
                action(r.x, r.y, r.w, r.h)
            }
        }

        val doors = world.doors
        for (i in doors.indices) {
            val door = doors[i]
            if (!door.solid) continue
            val r = door.spec.rect
            action(r.x, r.y, r.w, r.h)
        }

        val movers = world.movers
        for (i in movers.indices) {
            val mover = movers[i]
            val size = mover.spec.size
            action(mover.x, mover.y, size.w, size.h)
        }
    }

    /** Pieces the tick's motion into steps short enough that nothing can skip past geometry. */
    private fun substepCount(vx: Float, vy: Float, dt: Float): Int {
        val travel = max(abs(vx), abs(vy)) * dt
        if (travel <= SUBSTEP_DISTANCE) return 1
        return clamp(ceil(travel / SUBSTEP_DISTANCE).toInt(), 1, MAX_SUBSTEPS)
    }

    /**
     * Inherits a mover's motion before velocity is integrated, so a rider travels with the
     * platform instead of being left behind and re-caught by the sweep every tick.
     *
     * `updateMovers` runs earlier in the tick, so every mover has already advanced by the time
     * this sees it. The rider test therefore probes each mover's *previous* rect — that is
     * where the platform was while the player was resting on it. Rising bodies are excluded so
     * jumping off a lift does not drag the player up with it.
     */
    private fun applyCarrier(world: WorldState, p: PlayerBody) {
        val carrier = findCarrier(world, p)
        if (carrier == null) {
            p.carrierId = null
            return
        }
        p.carrierId = carrier.spec.id

        if (carrier.dx != 0f) {
            p.x += carrier.dx
            // A lift that would grind its rider into a wall loses the argument. Reverting the
            // carry leaves the body in a legal spot and the sweep below resolves the rest.
            if (penetrates(world, p.x, p.y, p.w, p.h)) p.x -= carrier.dx
        }
        if (carrier.dy != 0f) {
            p.y += carrier.dy
            if (penetrates(world, p.x, p.y, p.w, p.h)) p.y -= carrier.dy
        }
    }

    /** The mover the player is standing on, or null. Re-evaluated every tick by design. */
    private fun findCarrier(world: WorldState, p: PlayerBody): MoverState? {
        if (p.vy < 0f) return null
        val movers = world.movers
        val feet = p.y + p.h
        for (i in movers.indices) {
            val mover = movers[i]
            val size = mover.spec.size
            val band = CARRY_PROBE * 2f
            if (overlaps(p.x, feet - CARRY_PROBE, p.w, band, mover.prevX, mover.prevY, size.w, size.h)) {
                return mover
            }
        }
        return null
    }

    /** Overlap test with the body inset slightly, so merely touching a surface is not a hit. */
    private fun penetrates(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean =
        solidOverlap(
            world,
            x + EDGE_TOLERANCE,
            y + EDGE_TOLERANCE,
            w - EDGE_TOLERANCE * 2f,
            h - EDGE_TOLERANCE * 2f,
        )
}
