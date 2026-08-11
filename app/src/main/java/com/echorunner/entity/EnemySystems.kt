package com.echorunner.entity

import com.echorunner.core.AudioController
import com.echorunner.core.GameConstants
import com.echorunner.core.PlatformKind
import com.echorunner.core.Rect
import com.echorunner.core.SignalCondition
import com.echorunner.core.SoundCue
import com.echorunner.core.WalkerState
import com.echorunner.core.WorldState
import com.echorunner.core.clamp
import com.echorunner.core.overlaps
import com.echorunner.core.rectDistance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Turrets, bullets, walkers and the player's lethality test.
 *
 * ## Echoes are bodies
 *
 * Every perception test in here scans `{player} ∪ world.echoes`, never the player alone. A
 * walker chases whichever of those is nearest, and a bullet stops on an Echo. That is the
 * difference between an enemy being an obstacle and an enemy being a *puzzle piece*: the
 * intended solution to a guarded corridor is to spend take one walking an Echo into the
 * guard's view, then walk past on take two while it is busy.
 *
 * Expired Echoes still count. Once a replay runs out the body holds its final pose with
 * `active == false`, but it is still drawn and still occupies space, so treating it as absent
 * would make a plan that ends with "the Echo stands there" silently stop working.
 *
 * Called from the orchestrator at steps 10 (turrets), 11 (bullets) and 12 (walkers), i.e.
 * after Echoes have advanced this tick, so perception reads this tick's Echo positions.
 * Nothing here allocates: the bullet pool, particle pool and entity state all already exist,
 * and every list is walked with an indexed `for` so no iterator is created per tick.
 */
object EnemySystems {

    // ---- Tuning local to enemy behaviour ---------------------------------------------
    // GameConstants is frozen and owns cross-module tuning; these two numbers are read by
    // nothing else, so they live here rather than widening that contract.

    /**
     * Vertical slack between a turret's own span and a body's span that still counts as being
     * "in the lane". A turret is a fixed emplacement covering a corridor, not a sniper: it
     * must ignore a player two platforms above it, or a level's turrets become unreadable.
     */
    private const val LANE_TOLERANCE = 2.5f

    /** Below this horizontal delta a chasing walker stops nudging, so it cannot jitter on top of its target. */
    private const val CHASE_DEADBAND = 0.05f

    /**
     * Narrower than this and a patrol span counts as a single post: the walker stands still.
     * Without the guard, `minX == maxX` flips `dir` on every tick and the sprite strobes.
     */
    private const val MIN_PATROL_SPAN = 0.05f

    // Particle tint slots. 0..3 are the player's (jump/dash/land/death); Palette.particleTint
    // covers 0..7, so enemy effects take the amber end of that range.
    private const val TINT_MUZZLE = 4
    private const val TINT_IMPACT = 5

    // ---- Walker attention lock -------------------------------------------------------
    // A walker must commit to the body that first drew it (see updateWalkers). WalkerState is
    // frozen and has no target field, so the choice is parked here, keyed by walker index and
    // scoped to one WorldState. Allocated once per world, never per tick.

    private const val TARGET_NONE = Int.MIN_VALUE
    private const val TARGET_PLAYER = -1

    private var lockWorld: WorldState? = null
    private var lockedTargets = IntArray(0)

    // Scratch for "the body a walker is pursuing". Returning a body would need a common
    // supertype across PlayerBody and EchoBody, which core does not define; copying four
    // floats into fields keeps the resolution allocation-free.
    private var targetX = 0f
    private var targetY = 0f
    private var targetW = 0f
    private var targetH = 0f

    // ---- Turrets ---------------------------------------------------------------------

    /**
     * Counts down each turret's cooldown and fires along its lane at the first body it sees.
     *
     * A disabled turret is skipped whole, cooldown included, so cutting its signal makes it
     * inert and restoring the signal makes it dangerous again immediately.
     */
    fun updateTurrets(world: WorldState, dt: Float, audio: AudioController) {
        val turrets = world.turrets
        for (i in 0 until turrets.size) {
            val turret = turrets[i]
            val spec = turret.spec

            if (signalHolds(world, spec.disabledWhen)) {
                turret.alerted = false
                continue
            }

            if (turret.cooldown > 0f) turret.cooldown -= dt

            val dir = if (spec.dirX > 0) 1 else -1
            val target = laneOccupied(world, spec.rect, dir, spec.range)
            turret.alerted = target

            if (!target || turret.cooldown > 0f) continue

            val muzzleX = if (dir > 0) spec.rect.right else spec.rect.x - GameConstants.BULLET_W
            val muzzleY = spec.rect.centerY - GameConstants.BULLET_H * 0.5f
            if (world.bullets.spawn(muzzleX, muzzleY, dir * spec.bulletSpeed)) {
                audio.play(SoundCue.TURRET_FIRE, 0.7f)
                world.particles.emit(
                    muzzleX + dir * 0.1f, muzzleY + GameConstants.BULLET_H * 0.5f,
                    dir * 4.5f, 0f, 0.1f, 0.16f, TINT_MUZZLE,
                )
            }
            // Reset even when the pool was full: retrying every tick would machine-gun the cue.
            turret.cooldown = spec.intervalSeconds
        }
    }

    /** True when the player or any Echo sits on [dir]'s side of the turret, in range and in its lane. */
    private fun laneOccupied(world: WorldState, rect: Rect, dir: Int, range: Float): Boolean {
        val player = world.player
        if (player.alive && inLane(rect, dir, range, player.x, player.y, player.w, player.h)) return true
        val echoes = world.echoes
        for (i in 0 until echoes.size) {
            val echo = echoes[i]
            if (inLane(rect, dir, range, echo.x, echo.y, echo.w, echo.h)) return true
        }
        return false
    }

    private fun inLane(
        rect: Rect, dir: Int, range: Float,
        bx: Float, by: Float, bw: Float, bh: Float,
    ): Boolean {
        val bodyCenterX = bx + bw * 0.5f
        val onFiringSide = if (dir > 0) bodyCenterX > rect.centerX else bodyCenterX < rect.centerX
        if (!onFiringSide) return false
        // Negative when the body is already overlapping the housing, which still counts.
        val horizontalGap = if (dir > 0) bx - rect.right else rect.x - (bx + bw)
        if (horizontalGap > range) return false
        val verticalGap = max(0f, max(rect.y - (by + bh), by - rect.bottom))
        return verticalGap <= LANE_TOLERANCE
    }

    // ---- Bullets ---------------------------------------------------------------------

    /**
     * Integrates live bullets and retires them on the world edge, on solid geometry, or on an
     * Echo.
     *
     * Stopping on an Echo is a mechanic, not a side effect: a body parked in a firing line is
     * how the player crosses one. The player is deliberately *not* tested here — death is a
     * single decision made once per tick by [lethalForPlayer], so a bullet cannot both kill
     * the player and vanish before the death check runs.
     */
    fun updateBullets(world: WorldState, dt: Float) {
        val bullets = world.bullets
        val bw = GameConstants.BULLET_W
        val bh = GameConstants.BULLET_H
        for (i in 0 until bullets.size) {
            if (!bullets.alive[i]) continue

            val x = bullets.x[i] + bullets.vx[i] * dt
            val y = bullets.y[i]
            bullets.x[i] = x

            val offWorld = x + bw < 0f || x > GameConstants.WORLD_W
            if (offWorld || blocksBullet(world, x, y, bw, bh) || echoBlocks(world, x, y, bw, bh)) {
                bullets.alive[i] = false
                val recoil = if (bullets.vx[i] > 0f) -1f else 1f
                world.particles.emit(x + bw * 0.5f, y + bh * 0.5f, recoil * 3.2f, -1.6f, 0.16f, 0.12f, TINT_IMPACT)
                world.particles.emit(x + bw * 0.5f, y + bh * 0.5f, recoil * 2.1f, 1.9f, 0.14f, 0.1f, TINT_IMPACT)
            }
        }
    }

    /** Solid geometry from a bullet's point of view. ONE_WAY platforms are shot straight through. */
    private fun blocksBullet(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean {
        val platforms = world.level.platforms
        for (i in 0 until platforms.size) {
            val platform = platforms[i]
            if (platform.kind == PlatformKind.ONE_WAY) continue
            val r = platform.rect
            if (overlaps(x, y, w, h, r.x, r.y, r.w, r.h)) return true
        }
        val doors = world.doors
        for (i in 0 until doors.size) {
            val door = doors[i]
            if (!door.solid) continue
            val r = door.spec.rect
            if (overlaps(x, y, w, h, r.x, r.y, r.w, r.h)) return true
        }
        val movers = world.movers
        for (i in 0 until movers.size) {
            val mover = movers[i]
            val size = mover.spec.size
            if (overlaps(x, y, w, h, mover.x, mover.y, size.w, size.h)) return true
        }
        return false
    }

    private fun echoBlocks(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean {
        val echoes = world.echoes
        for (i in 0 until echoes.size) {
            val echo = echoes[i]
            if (overlaps(x, y, w, h, echo.x, echo.y, echo.w, echo.h)) return true
        }
        return false
    }

    // ---- Walkers ---------------------------------------------------------------------

    /**
     * Patrols each walker, or chases the nearest body it noticed.
     *
     * Target selection is *committed*, not re-evaluated per tick: once a walker locks on, it
     * pursues that same body for [GameConstants.DISTRACTION_SECONDS] after the body leaves its
     * alert range, and the timer is refreshed for as long as the body stays inside it. Picking
     * the nearest body afresh every tick would let the player walk past a "distracted" walker
     * and instantly steal its attention back, which would make Echo-as-bait useless.
     *
     * Walkers are ground units on an authored ledge, so `x` is clamped into the patrol span
     * even mid-chase. A walker that chased the player off its platform would strand itself and
     * can leave a level unsolvable after a restart.
     */
    fun updateWalkers(world: WorldState, dt: Float, audio: AudioController) {
        val walkers = world.walkers
        // Bind first even for a walkerless level: rebinding drops the reference to the previous
        // world, so a finished level's pools are not held alive by this table.
        val locks = locks(world)
        if (walkers.isEmpty()) return

        for (i in 0 until walkers.size) {
            val walker = walkers[i]
            val spec = walker.spec
            walker.prevX = walker.x
            walker.prevY = walker.y

            val lo = min(spec.minX, spec.maxX)
            val hi = max(spec.minX, spec.maxX)

            var chasing = false
            if (walker.distractionTimer > 0f && resolveTarget(world, locks[i])) {
                val distance = rectDistance(
                    walker.x, walker.y, walker.w, walker.h,
                    targetX, targetY, targetW, targetH,
                )
                if (distance <= spec.alertRange) {
                    walker.distractionTimer = GameConstants.DISTRACTION_SECONDS
                } else {
                    walker.distractionTimer -= dt
                }
                chasing = true
            } else {
                // No lock, or the locked body is gone (level restarted, Echoes dropped).
                walker.distractionTimer = 0f
                locks[i] = TARGET_NONE
                val found = nearestBody(world, walker, spec.alertRange)
                if (found != TARGET_NONE) {
                    locks[i] = found
                    walker.distractionTimer = GameConstants.DISTRACTION_SECONDS
                    chasing = true
                }
            }

            val wasAlerted = walker.alerted
            walker.alerted = chasing
            if (chasing && !wasAlerted) audio.play(SoundCue.ENEMY_ALERT, 0.85f)

            if (chasing) {
                val delta = (targetX + targetW * 0.5f) - (walker.x + walker.w * 0.5f)
                if (abs(delta) > CHASE_DEADBAND) {
                    walker.dir = if (delta > 0f) 1 else -1
                    val step = spec.speed * spec.chaseMultiplier * dt
                    walker.x += clamp(delta, -step, step)
                }
            } else if (hi - lo > MIN_PATROL_SPAN) {
                walker.x += walker.dir * spec.speed * dt
                if (walker.x <= lo) {
                    walker.x = lo
                    walker.dir = 1
                } else if (walker.x >= hi) {
                    walker.x = hi
                    walker.dir = -1
                }
            }

            walker.x = clamp(walker.x, lo, hi)
        }
    }

    /**
     * Nearest body within [range], as a target code, filling the target scratch fields.
     * Returns [TARGET_NONE] when nothing is close enough.
     */
    private fun nearestBody(world: WorldState, walker: WalkerState, range: Float): Int {
        var bestCode = TARGET_NONE
        var bestDistance = Float.MAX_VALUE

        val player = world.player
        if (player.alive) {
            val distance = rectDistance(
                walker.x, walker.y, walker.w, walker.h,
                player.x, player.y, player.w, player.h,
            )
            if (distance <= range) {
                bestDistance = distance
                bestCode = TARGET_PLAYER
            }
        }

        val echoes = world.echoes
        for (i in 0 until echoes.size) {
            val echo = echoes[i]
            val distance = rectDistance(
                walker.x, walker.y, walker.w, walker.h,
                echo.x, echo.y, echo.w, echo.h,
            )
            if (distance <= range && distance < bestDistance) {
                bestDistance = distance
                bestCode = i
            }
        }

        if (bestCode != TARGET_NONE) resolveTarget(world, bestCode)
        return bestCode
    }

    /** Copies the coded body's rect into the target scratch. False when the code no longer resolves. */
    private fun resolveTarget(world: WorldState, code: Int): Boolean {
        if (code == TARGET_PLAYER) {
            val player = world.player
            if (!player.alive) return false
            targetX = player.x; targetY = player.y
            targetW = player.w; targetH = player.h
            return true
        }
        if (code < 0 || code >= world.echoes.size) return false
        val echo = world.echoes[code]
        targetX = echo.x; targetY = echo.y
        targetW = echo.w; targetH = echo.h
        return true
    }

    /** Per-world lock table, rebuilt when the world changes so a new level starts with no aggro. */
    private fun locks(world: WorldState): IntArray {
        if (lockWorld !== world || lockedTargets.size < world.walkers.size) {
            lockedTargets = IntArray(world.walkers.size)
            lockedTargets.fill(TARGET_NONE)
            lockWorld = world
        }
        return lockedTargets
    }

    // ---- Lethality -------------------------------------------------------------------

    /** True when the AABB touches any authored hazard. Shape only affects how it is drawn. */
    fun hazardLethalAt(world: WorldState, x: Float, y: Float, w: Float, h: Float): Boolean {
        val hazards = world.level.hazards
        for (i in 0 until hazards.size) {
            val r = hazards[i].rect
            if (overlaps(x, y, w, h, r.x, r.y, r.w, r.h)) return true
        }
        return false
    }

    /**
     * The tick's death test for hazards, bullets, walkers and pits.
     *
     * Lasers are absent on purpose: their beam state lives in the puzzle module, and the
     * orchestrator ORs this with `PuzzleSystems.laserLethalAt`. Splitting it keeps enemy code
     * free of any dependency on signal-driven puzzle objects.
     */
    fun lethalForPlayer(world: WorldState): Boolean {
        val player = world.player

        // Pit death. Physics deliberately does not clamp the bottom edge, so anything past the
        // world floor has fallen out of the level.
        if (player.y > GameConstants.WORLD_H) return true

        if (hazardLethalAt(world, player.x, player.y, player.w, player.h)) return true

        val bullets = world.bullets
        val bw = GameConstants.BULLET_W
        val bh = GameConstants.BULLET_H
        for (i in 0 until bullets.size) {
            if (!bullets.alive[i]) continue
            if (overlaps(player.x, player.y, player.w, player.h, bullets.x[i], bullets.y[i], bw, bh)) return true
        }

        val walkers = world.walkers
        for (i in 0 until walkers.size) {
            val walker = walkers[i]
            if (overlaps(player.x, player.y, player.w, player.h, walker.x, walker.y, walker.w, walker.h)) return true
        }

        return false
    }

    /**
     * Whether a *gate* condition is currently holding.
     *
     * [WorldState.evaluate] treats a null or empty condition as "always true", which is right
     * for `opensWhen` (no condition means an open door) and exactly wrong for `disabledWhen`
     * (no condition must mean *never* disabled). Every negative gate in this module goes
     * through here so an unauthored field cannot silently switch a turret off forever.
     */
    private fun signalHolds(world: WorldState, cond: SignalCondition?): Boolean =
        cond != null && cond.ids.isNotEmpty() && world.evaluate(cond)
}
