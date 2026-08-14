package com.echorunner

import com.echorunner.core.GameConstants
import com.echorunner.core.LevelSpec
import com.echorunner.core.Rect
import com.echorunner.core.SignalMode
import com.echorunner.level.LevelParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Decides whether a level is *possible*, independent of whether any bot can play it.
 *
 * ## Why this exists
 *
 * The [Autoplayer] finishes 10 of 30 levels. That number says something about the bot and almost
 * nothing about the levels: it fails mostly by not planning multi-step climbs. Asking "is this
 * room broken?" needs an oracle that does not depend on play skill at all, so this builds a graph
 * of every standable surface, connects two surfaces when a jump can actually cross between them,
 * and asks reachability questions on the graph.
 *
 * ## The jump envelope is derived, not guessed
 *
 * The level hints quote a "6.0 across / 3.4 up" budget. That is the authors' conservative rule of
 * thumb; the engine is more generous. Rising uses [GameConstants.GRAVITY_JUMP_HOLD] (40) and
 * falling uses [GameConstants.GRAVITY] (62), so from a 21.2 u/s launch the apex is 5.62 units and
 * a body is still above +3.2 units at t=0.878 s — which at 9.2 u/s is 8.08 units of travel, not
 * 6.0. Using the real envelope matters: with the hint's numbers this checker would report
 * perfectly good jumps as impossible and send someone off to "fix" a level that works.
 *
 * Doors are treated as open and signalled lifts as running — whether the choreography can
 * arrange either is a separate question
 * (that is what the Echo budget check answers); this is purely about whether the geometry admits
 * a route at all.
 */
class ReachabilityTest {

    private val dir = File("src/main/assets/levels")

    private fun manifestIds(): List<String> {
        val root = org.json.JSONObject(File(dir, "manifest.json").readText())
        val chapters = root.getJSONArray("chapters")
        return buildList {
            for (i in 0 until chapters.length()) {
                val l = chapters.getJSONObject(i).getJSONArray("levels")
                for (j in 0 until l.length()) add(l.getString(j))
            }
        }
    }

    // ---- jump envelope ----------------------------------------------------------------

    private companion object {
        const val V = -GameConstants.JUMP_VELOCITY            // 21.2 upward
        const val G_UP = GameConstants.GRAVITY_JUMP_HOLD      // 40 while the button is held
        const val G_DOWN = GameConstants.GRAVITY              // 62 once falling
        const val RUN = 9.2f
        val APEX = V * V / (2f * G_UP)                        // 5.618 units
        val T_APEX = V / G_UP                                 // 0.53 s

        /** Shaved off both axes so a jump that only works to the pixel is not called routine. */
        const val MARGIN_X = 0.45f
        const val MARGIN_Y = 0.20f

        /** A rider usually has to let the lift come to them; half a shuttle is a fair average. */
        const val WAIT_FOR_LIFT = 2.0f
    }

    /**
     * Longest horizontal distance a running jump covers while still at least [climb] units above
     * its launch height. Negative [climb] is a drop.
     */
    private fun reach(climb: Float): Float {
        if (climb > APEX - MARGIN_Y) return -1f
        val t = if (climb >= 0f) {
            // Descending branch of the rise parabola: V t - G_UP t^2 / 2 = climb.
            val disc = V * V - 2f * G_UP * climb
            (V + sqrt(disc)) / G_UP
        } else {
            // Up to the apex, then fall the extra distance under the heavier gravity.
            T_APEX + sqrt(2f * (APEX - climb) / G_DOWN)
        }
        return RUN * t - MARGIN_X
    }

    // ---- surface graph ----------------------------------------------------------------

    /** A walkable ledge: everything between [x0] and [x1] at height [y] is standable. */
    private data class Surface(
        val x0: Float,
        val x1: Float,
        val y: Float,
        val label: String,
        /** Non-null when this ledge is a lift stop: every stop of one lift is mutually reachable. */
        val moverId: String? = null,
    )

    private fun surfaces(spec: LevelSpec): List<Surface> = buildList {
        spec.platforms.forEach { add(Surface(it.rect.x, it.rect.right, it.rect.y, "platform")) }
        // Every waypoint a lift visits is somewhere a body can be standing, so each one is a
        // surface. This is how a signalled lift bridges a gap that no jump could cross.
        spec.movers.forEach { m ->
            m.path.forEach { wp -> add(Surface(wp.x, wp.x + m.spec_w(), wp.y, "lift ${m.id}", m.id)) }
        }
    }

    private fun com.echorunner.core.MoverSpec.spec_w(): Float = size.w

    /** Horizontal gap between two ledges: zero when they overlap. */
    private fun gap(a: Surface, b: Surface): Float = when {
        b.x1 < a.x0 -> a.x0 - b.x1
        b.x0 > a.x1 -> b.x0 - a.x1
        else -> 0f
    }

    private fun connected(a: Surface, b: Surface): Boolean {
        val climb = a.y - b.y              // positive: b is higher than a
        val r = reach(climb)
        return r >= 0f && gap(a, b) <= r
    }

    /** Surfaces reachable from the spawn, plus teleporter pads treated as two-way edges. */
    private fun reachableFrom(spec: LevelSpec, all: List<Surface>): Set<Int> {
        val start = all.indices.minByOrNull { i ->
            val s = all[i]
            val onIt = spec.spawn.x >= s.x0 - 0.6f && spec.spawn.x <= s.x1 + 0.6f
            if (onIt) abs(s.y - (spec.spawn.y + GameConstants.PLAYER_H)) else Float.MAX_VALUE
        } ?: return emptySet()

        // Teleporter pads link the surfaces beneath them in both directions.
        val links = ArrayList<Pair<Int, Int>>()
        for (t in spec.teleporters) {
            val ia = all.indexOfFirst { supports(it, t.a) }
            val ib = all.indexOfFirst { supports(it, t.b) }
            if (ia >= 0 && ib >= 0) { links += ia to ib; links += ib to ia }
        }

        val seen = HashSet<Int>()
        val queue = ArrayDeque<Int>()
        seen += start; queue += start
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for (j in all.indices) {
                if (j in seen) continue
                // Riding is travel, not a jump. A lift exists precisely to carry a body between
                // its own stops, and omitting these edges reported six perfectly good levels as
                // having unreachable exits — 5-1's exit ledge is a one-unit hop from the top of
                // its lift, but nine units above the bottom, so only the ride connects them.
                val sameLift = all[i].moverId != null && all[i].moverId == all[j].moverId
                if (sameLift || connected(all[i], all[j])) { seen += j; queue += j }
            }
            for ((from, to) in links) if (from == i && to !in seen) { seen += to; queue += to }
        }
        return seen
    }

    /** Does this ledge hold up the given rect — i.e. is the rect resting on it? */
    private fun supports(s: Surface, r: Rect): Boolean =
        r.centerX >= s.x0 - 0.4f && r.centerX <= s.x1 + 0.4f && abs(s.y - r.bottom) < 0.75f

    private fun standOn(all: List<Surface>, reachable: Set<Int>, r: Rect): Boolean =
        all.indices.any { it in reachable && supports(all[it], r) }

    // ---- the questions ----------------------------------------------------------------

    private data class Verdict(val id: String, val exitOk: Boolean, val missing: List<String>)

    private fun check(spec: LevelSpec): Verdict {
        val all = surfaces(spec)
        val reachable = reachableFrom(spec, all)
        val exitOk = standOn(all, reachable, spec.exit)

        val needed = LinkedHashSet<String>()
        spec.exitCondition?.let { c -> if (c.mode == SignalMode.ALL) needed.addAll(c.ids) else c.ids.firstOrNull()?.let { needed.add(it) } }
        spec.doors.forEach { d -> if (!d.opensWhen.invert) needed.addAll(d.opensWhen.ids) }

        val missing = needed.mapNotNull { id ->
            val r = spec.plates.firstOrNull { it.id == id }?.rect
                ?: spec.switches.firstOrNull { it.id == id }?.rect
                ?: return@mapNotNull "$id (no such producer)"
            if (standOn(all, reachable, r)) null else id
        }
        return Verdict(spec.id, exitOk, missing)
    }

    // ---- take-time feasibility --------------------------------------------------------

    /** Seconds of airtime for a jump that climbs [climb] units. */
    private fun airtime(climb: Float): Float =
        if (climb >= 0f) (V + sqrt(V * V - 2f * G_UP * climb)) / G_UP
        else T_APEX + sqrt(2f * (APEX - climb) / G_DOWN)

    /** Cheapest travel time between two ledges, or null when no move connects them. */
    private fun cost(a: Surface, b: Surface, spec: LevelSpec): Float? {
        if (a.moverId != null && a.moverId == b.moverId) {
            val m = spec.movers.first { it.id == a.moverId }
            return abs(a.y - b.y) / m.speed + m.dwellSeconds + WAIT_FOR_LIFT
        }
        if (!connected(a, b)) return null
        val climb = a.y - b.y
        // Walking to the launch point, then the flight itself.
        return gap(a, b) / RUN + airtime(climb)
    }

    /** Dijkstra over ledges, returning seconds from the spawn ledge to every other. */
    private fun times(spec: LevelSpec, all: List<Surface>): FloatArray {
        val start = all.indices.minByOrNull { i ->
            val s = all[i]
            if (spec.spawn.x >= s.x0 - 0.6f && spec.spawn.x <= s.x1 + 0.6f)
                abs(s.y - (spec.spawn.y + GameConstants.PLAYER_H)) else Float.MAX_VALUE
        } ?: return FloatArray(all.size) { Float.MAX_VALUE }

        val dist = FloatArray(all.size) { Float.MAX_VALUE }
        dist[start] = 0f
        val done = BooleanArray(all.size)
        repeat(all.size) {
            val i = all.indices.filter { !done[it] }.minByOrNull { dist[it] } ?: return@repeat
            if (dist[i] == Float.MAX_VALUE) return@repeat
            done[i] = true
            for (j in all.indices) {
                if (done[j]) continue
                val c = cost(all[i], all[j], spec) ?: continue
                if (dist[i] + c < dist[j]) dist[j] = dist[i] + c
            }
        }
        return dist
    }

    /** Seconds to stand on [r], including the walk along the final ledge. */
    private fun timeTo(spec: LevelSpec, all: List<Surface>, dist: FloatArray, r: Rect): Float {
        var best = Float.MAX_VALUE
        for (i in all.indices) {
            if (dist[i] == Float.MAX_VALUE || !supports(all[i], r)) continue
            val walk = maxOf(0f, minOf(abs(r.centerX - all[i].x0), abs(r.centerX - all[i].x1))) / RUN
            best = minOf(best, dist[i] + walk)
        }
        return best
    }

    @Test
    fun `every required route fits inside a single take`() {
        println("\n=== Take-time feasibility (route seconds vs take length) ===")
        val tight = ArrayList<String>()
        val impossible = ArrayList<String>()

        for (id in manifestIds()) {
            val spec = LevelParser.parse(File(dir, "$id.json").readText())
            val all = surfaces(spec)
            val dist = times(spec, all)

            // Each plain plate is its own take: spawn -> plate. The last take is spawn -> exit.
            val legs = ArrayList<Pair<String, Float>>()
            spec.plates.filter { !it.latching }.forEach { legs += it.id to timeTo(spec, all, dist, it.rect) }
            spec.switches.forEach { legs += it.id to timeTo(spec, all, dist, it.rect) }
            legs += "exit" to timeTo(spec, all, dist, spec.exit)

            val worst = legs.filter { it.second != Float.MAX_VALUE }.maxByOrNull { it.second }
            if (worst == null) continue
            val margin = spec.takeSeconds - worst.second
            val flag = when {
                margin < 0f -> { impossible += "$id: ${worst.first} needs ${fmt(worst.second)}s of a ${fmt(spec.takeSeconds)}s take"; "IMPOSSIBLE" }
                margin < spec.takeSeconds * 0.25f -> { tight += "$id (${fmt(margin)}s spare)"; "tight" }
                else -> "ok"
            }
            println("  ${flag.padEnd(10)} ${id.padEnd(4)} worst leg ${worst.first.padEnd(5)} ${fmt(worst.second)}s / ${fmt(spec.takeSeconds)}s")
        }
        if (tight.isNotEmpty()) println("  NOTE tight rooms (under 25% spare): ${tight.joinToString(", ")}")
        println()

        assertTrue(
            "these levels ask for more travel than a take allows, so nobody can finish them:\n  " +
                impossible.joinToString("\n  "),
            impossible.isEmpty(),
        )
    }

    private fun fmt(v: Float) = "%.1f".format(v)

    @Test
    fun `every level admits a physical route to its exit and to every signal it needs`() {
        val verdicts = manifestIds().map { check(LevelParser.parse(File(dir, "$it.json").readText())) }

        println("\n=== Reachability (doors open, real jump envelope) ===")
        println("  jump reach: level ${"%.2f".format(reach(0f))}u, +3.2 climb ${"%.2f".format(reach(3.2f))}u, " +
            "+5.0 climb ${"%.2f".format(reach(5.0f))}u, apex ${"%.2f".format(APEX)}u")
        for (v in verdicts) {
            val bad = !v.exitOk || v.missing.isNotEmpty()
            val detail = buildList {
                if (!v.exitOk) add("EXIT unreachable")
                if (v.missing.isNotEmpty()) add("unreachable signals: ${v.missing.joinToString("+")}")
            }
            println("  ${if (bad) "BROKEN" else "ok    "} ${v.id.padEnd(4)} ${detail.joinToString("; ")}")
        }
        println()

        val broken = verdicts.filter { !it.exitOk || it.missing.isNotEmpty() }
        assertTrue(
            "these levels cannot be finished by anyone — the geometry admits no route:\n  " +
                broken.joinToString("\n  ") {
                    "${it.id}: ${if (!it.exitOk) "exit unreachable " else ""}${it.missing.joinToString("+")}"
                },
            broken.isEmpty(),
        )
    }

    @Test
    fun `the checker detects a level that really is broken`() {
        // A negative control. An oracle that reports every level fine is worthless unless it can
        // be shown to fail on something that genuinely is unreachable — otherwise "all 30 pass"
        // might just mean the graph connects everything to everything.
        val sound = LevelParser.parse(File(dir, "1-1.json").readText())
        assertTrue("1-1 should be reachable to begin with", check(sound).let { it.exitOk && it.missing.isEmpty() })

        // Lift the exit twelve units into the air: above the 5.62-unit apex from any ledge below.
        val floating = sound.copy(exit = Rect(sound.exit.x, sound.exit.y - 12f, sound.exit.w, sound.exit.h))
        val verdict = check(floating)
        assertTrue("an exit floating 12 units up must be reported unreachable", !verdict.exitOk)

        // And a required signal that points at nothing must be named, not silently ignored.
        val dangling = sound.copy(
            exitCondition = com.echorunner.core.SignalCondition.of("ghost"),
        )
        assertTrue(
            "a condition naming a producer that does not exist must be reported",
            check(dangling).missing.any { it.contains("ghost") },
        )
    }
}
