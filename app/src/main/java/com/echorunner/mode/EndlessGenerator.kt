package com.echorunner.mode

import com.echorunner.core.DoorSpec
import com.echorunner.core.GameConstants
import com.echorunner.core.HazardKind
import com.echorunner.core.HazardSpec
import com.echorunner.core.LaserSpec
import com.echorunner.core.LevelSpec
import com.echorunner.core.MoverMode
import com.echorunner.core.MoverSpec
import com.echorunner.core.PlateSpec
import com.echorunner.core.PlatformKind
import com.echorunner.core.PlatformSpec
import com.echorunner.core.Rect
import com.echorunner.core.SignalCondition
import com.echorunner.core.SignalMode
import com.echorunner.core.TurretSpec
import com.echorunner.core.Vec2
import com.echorunner.core.WalkerSpec
import com.echorunner.core.clamp
import com.echorunner.core.lerp
import com.echorunner.core.overlaps
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Procedural room generator for Endless mode.
 *
 * ## Reachability invariant
 *
 * Every room is solvable **by construction**, never by luck:
 *
 * 1. A *route* is laid first — an ordered chain of landing pads from the spawn pad to the
 *    exit pad. Each step is sampled inside an envelope derived from [GameConstants] itself
 *    (see [maxGapForRise]), so every individual step is clearable with the game's real jump
 *    and run speed rather than with a hopeful constant.
 * 2. The volumes that route needs — the walking box above each pad, and the jump arc over
 *    each gap — are recorded in [Build.reserved].
 * 3. Everything after that is *decoration*, and every piece is geometrically tested against
 *    those volumes before it is emitted. A placement that lands on the route is discarded,
 *    never nudged into place.
 *
 * Three object kinds are deliberately exempt from rule 3, and each is made safe a different
 * way instead:
 *
 * - the **gate door**, because it *is* the puzzle. It retracts while its plates are held, and
 *   it is built full height so it cannot simply be jumped over;
 * - **lasers**, because they are timed rather than permanent. One is placed only in a gap
 *   wide enough that the beam misses both landing surfaces, and only with an off-window
 *   provably longer than the crossing takes;
 * - **turrets**, because they are not solid and so block nothing. Their bullets are dodgeable
 *   on foot and an Echo can body-block them, which is why they only appear once the room's
 *   Echo budget includes a spare.
 *
 * ## Determinism
 *
 * The only source of randomness is a [Random] seeded from `(roomIndex, seed)`. No wall clock,
 * no `Math.random`, no iteration over a hash container. The same pair always produces an
 * identical [LevelSpec], which is what lets a run store one seed and regenerate any room on
 * demand instead of persisting level data.
 */
object EndlessGenerator {

    // ---- Reach envelope, derived from the integrator rather than guessed ---------------

    /** Apex of a *tapped* jump (released immediately, full gravity): the pessimistic case. */
    private val TAP_APEX =
        (GameConstants.JUMP_VELOCITY * GameConstants.JUMP_VELOCITY) /
            (2f * GameConstants.GRAVITY)

    /** Apex of a *held* jump, which is what a player actually uses to cross a long gap. */
    private val HELD_APEX =
        (GameConstants.JUMP_VELOCITY * GameConstants.JUMP_VELOCITY) /
            (2f * GameConstants.GRAVITY_JUMP_HOLD)

    /**
     * Tallest step the route may climb. Taken from the *tapped* apex, so a step is clearable
     * even by a player who never learns to hold the button, and capped under the 3.4-unit
     * design ceiling.
     */
    private val MAX_RISE = min(3.05f, TAP_APEX * 0.84f)

    /** Hard ceiling on any gap, whatever the arc maths would otherwise allow. */
    private const val MAX_GAP = 6.2f
    private const val MIN_GAP = 0.9f

    /** Fraction of the theoretical horizontal reach the generator is willing to spend. */
    private const val REACH_SAFETY = 0.72f

    /** Largest drop a single step may take, so one mistimed step is never a long fall. */
    private const val MAX_DROP = 4.6f

    // ---- Room layout ------------------------------------------------------------------

    private const val FLOOR_H = 1.4f

    /** Top edge of the floor slab. A body standing on it has `y = FLOOR_TOP - PLAYER_H`. */
    private val FLOOR_TOP = GameConstants.WORLD_H - FLOOR_H

    /** Nothing the route uses climbs above this; the top of the room stays as headroom. */
    private const val CEILING_Y = 3.4f
    private const val EDGE_MARGIN = 0.6f

    /** Span held back on the right so the exit pad and its gate always fit. */
    private const val EXIT_RESERVE = 10f

    private const val PAD_H = 0.8f

    /** Landing shelf + gate + exit have to fit side by side on the final pad. */
    private const val EXIT_PAD_MIN_W = 5.8f
    private const val EXIT_PAD_MAX_W = 8.6f

    /** Distance from the exit pad's left edge to the gate, i.e. the landing shelf width. */
    private const val GATE_INSET = 2.4f

    private const val SPIKE_H = 0.5f
    private const val EXIT_W = 1.3f
    private const val EXIT_H = 2f

    /** Head clearance kept above every pad and inside every jump arc. */
    private const val HEAD_ROOM = GameConstants.PLAYER_H + 0.7f

    /** Vertical band kept clear inside a jump arc, measured from the higher of the two pads. */
    private const val ARC_CLEAR = 4.3f

    /** Hard cap on route length, so a lucky roll of tiny pads cannot run away. */
    private const val MAX_PADS = 8

    /**
     * Horizontal distance the player covers in one held jump that also climbs [rise] units
     * (a negative [rise] is a drop).
     *
     * Rise time uses [GameConstants.GRAVITY_JUMP_HOLD] because the button is held; the fall
     * back down to the landing height uses full [GameConstants.GRAVITY] — exactly the two
     * regimes the controller applies. Scaled by [REACH_SAFETY] and clamped to [MAX_GAP] so a
     * room never asks for a frame-perfect input.
     */
    private fun maxGapForRise(rise: Float): Float {
        val fall = HELD_APEX - rise
        if (fall <= 0f) return 0f
        val tUp = -GameConstants.JUMP_VELOCITY / GameConstants.GRAVITY_JUMP_HOLD
        val tDown = sqrt(2f * fall / GameConstants.GRAVITY)
        val reach = (tUp + tDown) * GameConstants.MOVE_SPEED * REACH_SAFETY
        return min(reach, MAX_GAP)
    }

    // ---- Generation -------------------------------------------------------------------

    /**
     * Builds room [roomIndex] (1-based) of the run identified by [seed].
     *
     * Order is load-bearing: the route is laid and reserved before a single decoration is
     * considered, so every later placement has something concrete to be rejected against.
     */
    fun generate(roomIndex: Int, seed: Long): LevelSpec {
        val room = max(1, roomIndex)
        val b = Build(Random(mixSeed(room, seed)))
        val tier = tierFor(room)
        // 0..1 progression ramp: shrinks pads and stretches gaps as the run goes deeper.
        val ramp = clamp((room - 1) / 16f, 0f, 1f)

        layRoute(b, tier, ramp)
        reserveRoute(b)

        val exitPad = b.pads[b.pads.size - 1]
        // Seal the column above the exit pad. Without this, a decorative ledge could become a
        // ladder that drops the player behind the gate and skips the room's puzzle entirely.
        b.reserved.add(Rect(exitPad.x - 0.6f, 0f, exitPad.w + 1.2f, exitPad.y + HEAD_ROOM))

        val plateIds = placePlates(b, plateCountFor(tier, room))
        val gate = if (plateIds.isEmpty()) null else placeGate(b, exitPad, plateIds)
        val exit = exitRect(exitPad, gate)

        emitPadPlatforms(b)
        emitFloor(b, tier)
        placeLedges(b, tier, ramp)
        placeSpikes(b, tier)
        if (tier >= 2) placeLasers(b, tier)
        if (tier >= 3) placeEnemies(b, tier)
        if (tier >= 4) placeLift(b, plateIds)

        val echoes = echoBudget(tier, room, plateIds.size)
        val spawnPad = b.pads[0]
        return LevelSpec(
            id = "endless-$room",
            name = "Room $room",
            // Endless rooms belong to no chapter; 0 keeps them out of chapter progress.
            chapter = 0,
            takeSeconds = if (tier == 0) 24f else GameConstants.DEFAULT_TAKE_SECONDS,
            maxEchoes = echoes,
            spawn = Vec2(spawnPad.x + 1.6f, spawnPad.y - GameConstants.PLAYER_H - 0.05f),
            exit = exit,
            // The gate door does the sealing, so the exit itself is always live.
            exitCondition = null,
            platforms = b.platforms,
            hazards = b.hazards,
            plates = b.plates,
            switches = emptyList(),
            doors = b.doors,
            movers = b.movers,
            lasers = b.lasers,
            turrets = b.turrets,
            walkers = b.walkers,
            teleporters = emptyList(),
            dialogue = emptyList(),
            hint = hintFor(b, plateIds),
            parTakes = echoes + 1,
        )
    }

    /**
     * Folds the room number into the run seed.
     *
     * Two multiply-xorshift rounds rather than `Random(seed + roomIndex)`, because
     * [Random]'s first draws from adjacent seeds are visibly correlated — consecutive rooms
     * would open with near-identical pad heights.
     */
    private fun mixSeed(roomIndex: Int, seed: Long): Long {
        var h = seed * 6364136223846793005L + 1442695040888963407L
        h = h xor (roomIndex.toLong() * 0x9E3779B97F4A7L)
        h = h xor (h ushr 31)
        h *= 6364136223846793005L
        h = h xor (h ushr 29)
        return h
    }

    /**
     * Difficulty tier. Each step introduces one new *kind* of pressure rather than more of
     * the same: 0 movement only · 1 a plate-gated exit · 2 lasers · 3 enemies · 4 everything
     * combined, on shrinking pads.
     */
    private fun tierFor(room: Int): Int = when {
        room <= 2 -> 0
        room <= 4 -> 1
        room <= 7 -> 2
        room <= 11 -> 3
        else -> 4
    }

    /** Gate plates. Each has to be *held*, so each one costs an Echo. */
    private fun plateCountFor(tier: Int, room: Int): Int = when (tier) {
        0 -> 0
        1, 2 -> 1
        3 -> 2
        else -> clamp(2 + (room - 12) / 6, 2, 3)
    }

    /**
     * Echo allowance: one per plate, plus a spare from tier 3 on for baiting a walker or
     * body-blocking a bullet. Capped at [GameConstants.MAX_ECHOES].
     */
    private fun echoBudget(tier: Int, room: Int, plateCount: Int): Int {
        if (tier == 0) return 0
        val spare = when {
            tier < 3 -> 0
            tier == 3 -> 1
            else -> 1 + (room - 12) / 8
        }
        return clamp(plateCount + spare, 1, GameConstants.MAX_ECHOES)
    }

    // ---- The verified route -----------------------------------------------------------

    /**
     * Lays the guaranteed route: pad 0 sits on the floor under the spawn, intermediate pads
     * march rightward, and the exit pad takes the span held back by [EXIT_RESERVE].
     *
     * The gap for each step is sampled against [maxGapForRise] *for that step's own rise*, so
     * a tall step automatically gets a shorter gap. That coupling is the whole guarantee —
     * a flat 6.5-unit allowance would be unclearable at maximum climb.
     */
    private fun layRoute(b: Build, tier: Int, ramp: Float) {
        val rng = b.rng
        b.pads.add(Pad(EDGE_MARGIN, FLOOR_TOP, 7f))

        val padMin = lerp(4.2f, 2.4f, ramp)
        val padMax = lerp(6.4f, 3.6f, ramp)
        val gapCeiling = lerp(3.4f, MAX_GAP, ramp)
        // One deliberately wide "arena" pad from tier 3 on, so a walker or a turret lane has
        // room to be a fair fight instead of a coin flip on a two-unit ledge.
        val arenaIndex = if (tier >= 3) 1 + rng.nextInt(3) else -1

        while (b.pads.size < MAX_PADS - 1) {
            val prev = b.pads[b.pads.size - 1]
            val y = nextPadY(rng, prev.y, tier)
            val gap = rng.range(MIN_GAP, min(gapCeiling, maxGapForRise(prev.y - y)))
            val w = if (b.pads.size == arenaIndex) rng.range(5.6f, 6.8f) else rng.range(padMin, padMax)
            val x = prev.right + gap
            if (x + w > GameConstants.WORLD_W - EXIT_RESERVE) break
            b.pads.add(Pad(x, y, w))
        }

        val prev = b.pads[b.pads.size - 1]
        val y = nextPadY(rng, prev.y, tier)
        // The final gap is additionally bounded by the space the exit pad itself needs.
        val gapRoom = GameConstants.WORLD_W - EDGE_MARGIN - EXIT_PAD_MIN_W - prev.right
        val gap = rng.range(MIN_GAP, max(MIN_GAP, min(min(gapCeiling, maxGapForRise(prev.y - y)), gapRoom)))
        val x = prev.right + gap
        val w = min(EXIT_PAD_MAX_W, GameConstants.WORLD_W - EDGE_MARGIN - x)
        b.pads.add(Pad(x, y, w))
    }

    /**
     * Samples the next pad height: mostly climbing, sometimes dropping back down so a room
     * reads as a space rather than a staircase. Clamped into the playable band, and the caller
     * re-derives the *actual* rise from the clamped result before budgeting the gap.
     */
    private fun nextPadY(rng: Random, prevY: Float, tier: Int): Float {
        val climbing = rng.chance(if (tier == 0) 0.45f else 0.6f)
        val dy = if (climbing) -rng.range(0.9f, MAX_RISE) else rng.range(0f, MAX_DROP)
        return clamp(prevY + dy, CEILING_Y, FLOOR_TOP)
    }

    /**
     * Records the volumes the route occupies: the walking box over each pad, and the jump arc
     * over each gap. [Build.blocksRoute] rejects any later placement that enters one, which is
     * how "decorate only where it does not block the route" is enforced mechanically instead
     * of by careful authoring.
     */
    private fun reserveRoute(b: Build) {
        for (i in b.pads.indices) {
            val p = b.pads[i]
            b.reserved.add(Rect(p.x - 0.25f, p.y - HEAD_ROOM, p.w + 0.5f, HEAD_ROOM + 0.45f))
        }
        for (i in 0 until b.pads.size - 1) {
            val a = b.pads[i]
            val c = b.pads[i + 1]
            val top = min(a.y, c.y) - ARC_CLEAR
            val bottom = max(a.y, c.y) + 0.6f
            val x0 = a.right - 0.8f
            val x1 = c.x + 0.8f
            b.reserved.add(Rect(x0, top, x1 - x0, bottom - top))
        }
    }

    /** Emits a slab per pad. A pad that landed on the floor needs no slab of its own. */
    private fun emitPadPlatforms(b: Build) {
        for (i in b.pads.indices) {
            val p = b.pads[i]
            if (p.y >= FLOOR_TOP - 0.01f) continue
            val r = Rect(p.x, p.y, p.w, PAD_H)
            b.platforms.add(PlatformSpec(r, PlatformKind.SOLID))
            b.occupied.add(r)
        }
    }

    /**
     * The floor, minus any pits.
     *
     * A pit is only cut where its (inflated) footprint touches no reserved volume, which means
     * it can never open under a route pad or inside a jump arc. Falling off the route is
     * punished; following the route never is.
     */
    private fun emitFloor(b: Build, tier: Int) {
        if (tier >= 2) {
            val budget = 1 + tier / 2
            var placed = 0
            var attempt = 0
            while (placed < budget && attempt < 24) {
                attempt++
                val w = b.rng.range(1.8f, 4.2f)
                val x = b.rng.range(2f, GameConstants.WORLD_W - 2f - w)
                // Inflated: a pit flush against a pad edge reads as a bug, not a decision.
                if (b.blocksRoute(x - 0.9f, FLOOR_TOP - 0.2f, w + 1.8f, FLOOR_H + 0.2f)) continue
                if (spansOverlap(b.floorCuts, x - 1.5f, w + 3f)) continue
                b.floorCuts.add(Rect(x, FLOOR_TOP, w, FLOOR_H))
                placed++
            }
            b.floorCuts.sortBy { it.x }
        }
        var cursor = 0f
        for (i in b.floorCuts.indices) {
            val cut = b.floorCuts[i]
            if (cut.x > cursor) {
                b.platforms.add(PlatformSpec(Rect(cursor, FLOOR_TOP, cut.x - cursor, FLOOR_H)))
            }
            b.hazards.add(
                HazardSpec(Rect(cut.x, FLOOR_TOP + 0.3f, cut.w, FLOOR_H - 0.3f), HazardKind.PIT)
            )
            cursor = cut.right
        }
        if (cursor < GameConstants.WORLD_W) {
            b.platforms.add(
                PlatformSpec(Rect(cursor, FLOOR_TOP, GameConstants.WORLD_W - cursor, FLOOR_H))
            )
        }
    }

    // ---- The puzzle -------------------------------------------------------------------

    /**
     * Puts each gate plate on a route pad, so *reaching* it is already proven by the route
     * itself. Non-latching on purpose: the plate has to be held, which is exactly what forces
     * one Echo per plate and one final take for the player.
     */
    private fun placePlates(b: Build, count: Int): List<String> {
        val ids = ArrayList<String>(count)
        if (count <= 0) return ids
        // Any pad but the exit pad: the plate must be somewhere the player has to abandon.
        val candidates = ArrayList<Int>(b.pads.size)
        for (i in 0 until b.pads.size - 1) candidates.add(i)
        shuffle(candidates, b.rng)
        var n = 0
        for (k in candidates.indices) {
            if (n >= count) break
            val pad = b.pads[candidates[k]]
            val pw = min(1.8f, pad.w - 0.6f)
            if (pw < 1f) continue
            val px = clamp(pad.centerX - pw * 0.5f, pad.x + 0.3f, pad.right - pw - 0.3f)
            val id = "plate-$n"
            b.plates.add(PlateSpec(id, Rect(px, pad.y - 0.28f, pw, 0.28f)))
            ids.add(id)
            n++
        }
        return ids
    }

    /**
     * The gate: a door across the exit pad, opening only while every plate is held.
     *
     * It runs the **full height of the room** rather than a plausible-looking two metres,
     * because a held jump reaches [HELD_APEX] and any gate shorter than the room could be
     * cleared from a high approach pad — which would quietly delete the puzzle. Set inward by
     * [GATE_INSET] so there is a real landing shelf in front of it.
     */
    private fun placeGate(b: Build, exitPad: Pad, plateIds: List<String>): Rect {
        val rect = Rect(exitPad.x + GATE_INSET, 0.4f, 0.5f, exitPad.y - 0.4f)
        b.doors.add(
            DoorSpec(
                id = "gate",
                rect = rect,
                opensWhen = SignalCondition(plateIds, SignalMode.ALL),
                travelSeconds = 0.4f,
            )
        )
        b.occupied.add(rect)
        return rect
    }

    /** Exit on the last pad, always to the right of the gate when there is one. */
    private fun exitRect(pad: Pad, gate: Rect?): Rect {
        val minX = if (gate != null) gate.right + 0.6f else pad.x + 0.5f
        val maxX = max(minX, pad.right - EXIT_W - 0.2f)
        val x = clamp(pad.right - EXIT_W - 0.6f, minX, maxX)
        return Rect(x, pad.y - EXIT_H, EXIT_W, EXIT_H)
    }

    // ---- Decoration -------------------------------------------------------------------

    /** Visual layering and optional shortcuts. Discarded on any contact with the route. */
    private fun placeLedges(b: Build, tier: Int, ramp: Float) {
        val budget = 2 + tier
        var placed = 0
        var attempt = 0
        while (placed < budget && attempt < budget * 8) {
            attempt++
            val oneWay = b.rng.chance(0.35f)
            val h = if (oneWay) 0.32f else 0.7f
            val w = b.rng.range(2f, lerp(5.2f, 3.4f, ramp))
            val x = b.rng.range(EDGE_MARGIN, GameConstants.WORLD_W - EDGE_MARGIN - w)
            val y = b.rng.range(CEILING_Y, FLOOR_TOP - 3.2f)
            if (b.blocksRoute(x - 0.35f, y - 0.35f, w + 0.7f, h + 0.7f)) continue
            if (b.collides(x, y, w, h, 0.7f)) continue
            val r = Rect(x, y, w, h)
            b.platforms.add(
                PlatformSpec(r, if (oneWay) PlatformKind.ONE_WAY else PlatformKind.SOLID)
            )
            b.occupied.add(r)
            b.ledges.add(r)
            placed++
        }
    }

    /**
     * Spike beds on exposed floor, and from tier 3 on the decorative ledges too. Never over a
     * pit (nothing may float) and never inside a reserved volume.
     */
    private fun placeSpikes(b: Build, tier: Int) {
        if (tier < 1) return
        val budget = 1 + tier * 2
        var placed = 0
        var attempt = 0
        while (placed < budget && attempt < budget * 6) {
            attempt++
            val w = b.rng.range(1f, 2.8f)
            if (tier >= 3 && b.ledges.isNotEmpty() && b.rng.chance(0.35f)) {
                val ledge = b.ledges[b.rng.nextInt(b.ledges.size)]
                if (ledge.w < w + 0.8f) continue
                val lx = b.rng.range(ledge.x + 0.2f, ledge.right - w - 0.2f)
                val ly = ledge.y - SPIKE_H
                if (b.blocksRoute(lx - 0.4f, ly - 0.35f, w + 0.8f, SPIKE_H + 0.35f)) continue
                b.hazards.add(HazardSpec(Rect(lx, ly, w, SPIKE_H), HazardKind.SPIKE))
                placed++
                continue
            }
            val x = b.rng.range(1f, GameConstants.WORLD_W - 1f - w)
            val y = FLOOR_TOP - SPIKE_H
            if (spansOverlap(b.floorCuts, x - 0.2f, w + 0.4f)) continue
            if (b.blocksRoute(x - 0.5f, y - 0.35f, w + 1f, SPIKE_H + 0.35f)) continue
            if (b.collides(x, y, w, SPIKE_H, 0.1f)) continue
            b.hazards.add(HazardSpec(Rect(x, y, w, SPIKE_H), HazardKind.SPIKE))
            placed++
        }
    }

    /**
     * Vertical beams standing in a gap.
     *
     * A laser is allowed to cross the route because it is *timed*, and two checks keep that
     * honest: the beam must miss both landing surfaces (so waiting on a pad is never lethal),
     * and its off-window must exceed the time to run the gap at [GameConstants.MOVE_SPEED]
     * with margin. The last gap is skipped so the exit approach stays clean.
     */
    private fun placeLasers(b: Build, tier: Int) {
        val budget = tier - 1
        var placed = 0
        for (i in 0 until b.pads.size - 2) {
            if (placed >= budget) break
            val a = b.pads[i]
            val c = b.pads[i + 1]
            val gap = c.x - a.right
            if (gap < 2.6f) continue
            if (!b.rng.chance(0.7f)) continue
            val bw = 0.32f
            val bx = a.right + gap * 0.5f - bw * 0.5f
            // Tall enough that jumping over it is not an option: waiting for the gap is.
            val top = max(0.2f, min(a.y, c.y) - (HELD_APEX + 0.6f))
            val bottom = max(a.y, c.y) + 0.4f
            val bh = bottom - top
            if (overlaps(bx, top, bw, bh, a.x, a.y - 0.1f, a.w, 0.6f)) continue
            if (overlaps(bx, top, bw, bh, c.x, c.y - 0.1f, c.w, 0.6f)) continue
            val crossSeconds = (gap + GameConstants.PLAYER_W + 2.2f) / GameConstants.MOVE_SPEED
            val offSeconds = max(1.5f, crossSeconds + 0.75f)
            val onSeconds = clamp(0.8f + tier * 0.25f, 0.8f, 1.9f)
            b.lasers.add(
                LaserSpec(
                    id = "laser-$placed",
                    rect = Rect(bx, top, bw, bh),
                    onSeconds = onSeconds,
                    offSeconds = offSeconds,
                    phase = b.rng.range(0f, onSeconds + offSeconds),
                )
            )
            placed++
        }
    }

    /**
     * A walker on the widest intermediate pad, and a turret firing back along that pad's lane.
     *
     * Both are answerable two ways — dodge on foot, or spend an Echo as bait / as a shield —
     * which is why [echoBudget] hands out a spare from this tier on. The walker's patrol span
     * is inset from the pad edges so Module D's clamp keeps it on the ledge.
     */
    private fun placeEnemies(b: Build, tier: Int) {
        var host = -1
        for (i in 1 until b.pads.size - 1) {
            val p = b.pads[i]
            if (p.w < 4.6f) continue
            if (host < 0 || p.w > b.pads[host].w) host = i
        }
        if (host < 0) return
        val pad = b.pads[host]
        val walkerH = 1.3f
        val minX = pad.x + 0.5f
        val maxX = pad.right - 1.4f
        if (maxX - minX >= 2.2f) {
            b.walkers.add(
                WalkerSpec(
                    id = "walker-0",
                    spawn = Vec2(clamp(pad.centerX - 0.45f, minX, maxX), pad.y - walkerH),
                    minX = minX,
                    maxX = maxX,
                    speed = clamp(2.2f + tier * 0.2f, 2.2f, 3.2f),
                    chaseMultiplier = 1.6f,
                )
            )
        }
        if (tier >= 4 || b.rng.chance(0.5f)) {
            // Mounted just past the pad's right edge at body height, firing left down the lane.
            // Turrets are not solid, so the mount itself never blocks anything.
            val tw = 0.9f
            val th = 1.2f
            val tx = min(pad.right + 0.35f, GameConstants.WORLD_W - EDGE_MARGIN - tw)
            val ty = pad.y - th - 0.15f
            b.turrets.add(
                TurretSpec(
                    id = "turret-0",
                    rect = Rect(tx, ty, tw, th),
                    dirX = -1,
                    intervalSeconds = clamp(1.9f - tier * 0.12f, 1.2f, 1.9f),
                    bulletSpeed = 10f + tier,
                )
            )
        }
    }

    /**
     * An Echo-operated lift wired to the first gate plate.
     *
     * Deliberately placed *off* the route — it is a set-piece and a shortcut, never a
     * dependency — so its entire swept volume is route-tested before it is emitted. A mover is
     * solid, and a solid body that intersects the route is exactly how a generated room becomes
     * unsolvable.
     */
    private fun placeLift(b: Build, plateIds: List<String>) {
        if (plateIds.isEmpty()) return
        if (!b.rng.chance(0.65f)) return
        val w = 3f
        val h = 0.6f
        val x = b.rng.range(2f, GameConstants.WORLD_W - 2f - w)
        val top = b.rng.range(CEILING_Y + 1f, FLOOR_TOP - 8f)
        val bottom = FLOOR_TOP - 1.4f
        if (bottom - top < 4f) return
        val sweptH = (bottom + h) - top
        if (b.blocksRoute(x - 0.3f, top - 0.3f, w + 0.6f, sweptH + 0.6f)) return
        if (b.collides(x, top, w, sweptH, 0.4f)) return
        b.movers.add(
            MoverSpec(
                id = "lift",
                size = Rect(0f, 0f, w, h),
                path = listOf(Vec2(x, bottom), Vec2(x, top)),
                speed = 3.2f,
                mode = MoverMode.SIGNAL,
                activeWhen = SignalCondition.of(plateIds[0]),
                dwellSeconds = 0.2f,
            )
        )
        b.occupied.add(Rect(x, top, w, sweptH))
    }

    /** Describes the route the generator actually laid, rather than a canned string. */
    private fun hintFor(b: Build, plateIds: List<String>): String {
        val sb = StringBuilder()
        sb.append(b.pads.size).append(" landing pads, left to right.")
        if (plateIds.isEmpty()) {
            sb.append(" The exit is open — one clean take.")
        } else {
            sb.append(" The gate needs ").append(plateIds.size)
                .append(if (plateIds.size == 1) " plate held" else " plates held together")
                .append(": park an Echo on each, then walk out on take ")
                .append(plateIds.size + 1).append('.')
        }
        if (b.lasers.isNotEmpty()) sb.append(" Cross the beams on their off-beat.")
        if (b.walkers.isNotEmpty()) sb.append(" Bait the walker with an Echo.")
        if (b.turrets.isNotEmpty()) sb.append(" An Echo can body-block the turret.")
        if (b.movers.isNotEmpty()) sb.append(" The lift only runs while a plate is held.")
        return sb.toString()
    }

    // ---- Scoring ----------------------------------------------------------------------

    private const val ROOM_BASE = 120
    private const val ROOM_STEP = 40
    private const val PAR_SECONDS = 45f
    private const val SPEED_WEIGHT = 7f
    private const val ELEGANCE_WEIGHT = 45
    private const val DEATH_WEIGHT = 60
    private const val MIN_SCORE = 15

    /**
     * `score = depth + speed + elegance − deaths`, floored at [MIN_SCORE] so even a scrappy
     * clear registers as progress:
     *
     * - **depth** `ROOM_BASE + roomIndex * ROOM_STEP` — deeper rooms are worth more, which is
     *   what makes pushing on rather than farming room 1 the higher-scoring play.
     * - **speed** `max(0, PAR_SECONDS − secondsTaken) * SPEED_WEIGHT` — beating par pays, but
     *   running over it never subtracts.
     * - **elegance** `(MAX_ECHOES − echoesUsed) * ELEGANCE_WEIGHT` — the bonus rewards using
     *   *fewer* timelines, so a two-Echo solve of a room that allowed four scores higher.
     * - **deaths** `deaths * DEATH_WEIGHT`.
     */
    fun scoreFor(roomIndex: Int, secondsTaken: Float, deaths: Int, echoesUsed: Int): Int {
        val room = max(1, roomIndex)
        val depth = ROOM_BASE + room * ROOM_STEP
        val speed = (max(0f, PAR_SECONDS - max(0f, secondsTaken)) * SPEED_WEIGHT).toInt()
        val elegance =
            (GameConstants.MAX_ECHOES - clamp(echoesUsed, 0, GameConstants.MAX_ECHOES)) *
                ELEGANCE_WEIGHT
        val penalty = max(0, deaths) * DEATH_WEIGHT
        return max(MIN_SCORE, depth + speed + elegance - penalty)
    }

    // ---- Small helpers ----------------------------------------------------------------

    private fun spansOverlap(list: List<Rect>, x: Float, w: Float): Boolean {
        for (i in list.indices) {
            val r = list[i]
            if (x < r.right && x + w > r.x) return true
        }
        return false
    }

    /** Fisher-Yates against the room's own [Random], so the pick order is part of the seed. */
    private fun shuffle(list: MutableList<Int>, rng: Random) {
        for (i in list.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val t = list[i]
            list[i] = list[j]
            list[j] = t
        }
    }

    private fun Random.range(lo: Float, hi: Float): Float =
        if (hi <= lo) lo else lo + nextFloat() * (hi - lo)

    private fun Random.chance(p: Float): Boolean = nextFloat() < p
}

/** One landing surface on the verified route. [y] is its **top edge**, as everywhere else. */
private class Pad(val x: Float, val y: Float, val w: Float) {
    val right: Float get() = x + w
    val centerX: Float get() = x + w * 0.5f
}

/**
 * Scratch state for a single room build.
 *
 * Held in a per-call object rather than in the generator object's fields so `generate` stays
 * re-entrant — the UI may prefetch the next room on a background thread while the current one
 * is being played.
 */
private class Build(val rng: Random) {
    val pads = ArrayList<Pad>(8)
    val platforms = ArrayList<PlatformSpec>(24)
    val hazards = ArrayList<HazardSpec>(12)
    val plates = ArrayList<PlateSpec>(3)
    val doors = ArrayList<DoorSpec>(1)
    val movers = ArrayList<MoverSpec>(1)
    val lasers = ArrayList<LaserSpec>(4)
    val turrets = ArrayList<TurretSpec>(2)
    val walkers = ArrayList<WalkerSpec>(2)

    /** Decorative ledges, kept apart from [platforms] so spikes can be mounted on them. */
    val ledges = ArrayList<Rect>(8)

    /** Floor spans replaced by pits, so nothing is ever left floating over one. */
    val floorCuts = ArrayList<Rect>(4)

    /** Volumes the verified route depends on. */
    val reserved = ArrayList<Rect>(24)

    /** Solid slabs already emitted, so decoration does not grow into itself. */
    val occupied = ArrayList<Rect>(24)

    /** True when the box touches any volume the verified route depends on. */
    fun blocksRoute(x: Float, y: Float, w: Float, h: Float): Boolean {
        for (i in reserved.indices) {
            val r = reserved[i]
            if (overlaps(x, y, w, h, r.x, r.y, r.w, r.h)) return true
        }
        return false
    }

    /** True when the box, grown by [pad], touches something solid that is already placed. */
    fun collides(x: Float, y: Float, w: Float, h: Float, pad: Float): Boolean {
        for (i in occupied.indices) {
            val r = occupied[i]
            if (overlaps(x - pad, y - pad, w + pad * 2, h + pad * 2, r.x, r.y, r.w, r.h)) return true
        }
        return false
    }
}

