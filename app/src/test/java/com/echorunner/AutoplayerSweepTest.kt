package com.echorunner

import com.echorunner.level.LevelParser
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the planning [Autoplayer] across all 30 shipped levels and reports what it can finish.
 *
 * ## Why this is mostly informational
 *
 * Three earlier attempts to assert things about level geometry each flagged deliberate design as
 * breakage — 2-1's seven-unit shaft wall *is* the puzzle, and its hint says so outright ("nobody
 * who throws that switch ever leaves"). An unreachable position can be load-bearing. So the sweep
 * prints a table and asserts only two things that cannot be a matter of taste:
 *
 *  - a level whose required signals outnumber its Echo budget is arithmetically unsolvable;
 *  - the tutorial chapter must fall to an automated player, because if a bot cannot finish
 *    chapter 1 then the game has failed to teach anything a human could act on either.
 *
 * Everything else is a measurement, not a verdict.
 */
class AutoplayerSweepTest {

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

    private fun sweep(): List<Solve> = manifestIds().map { id ->
        val spec = LevelParser.parse(File(dir, "$id.json").readText())
        try {
            Autoplayer(spec).solve()
        } catch (t: Throwable) {
            Solve(id, false, 0, "threw ${t::class.simpleName}: ${t.message}")
        }
    }

    @Test
    fun `sweep every level and report what the planning agent can finish`() {
        val results = sweep()
        val cleared = results.filter { it.cleared }

        println("\n=== Autoplayer sweep: ${cleared.size}/${results.size} levels cleared ===")
        for (r in results) {
            val mark = if (r.cleared) "PASS" else "----"
            println("  $mark ${r.levelId.padEnd(4)} takes=${r.takesUsed}  ${r.note}")
        }
        println("=== cleared: ${cleared.joinToString(", ") { it.levelId }} ===\n")

        // The sweep must run to completion on every level. A crash is a real defect: it means the
        // engine can be driven into an invalid state by ordinary play.
        val crashes = results.filter { it.note.startsWith("threw") }
        assertTrue("engine crashed while playing:\n  " + crashes.joinToString("\n  ") { "${it.levelId}: ${it.note}" }, crashes.isEmpty())
    }

    @Test
    fun `no level demands more held signals than its Echo budget allows`() {
        val impossible = sweep().filter { it.note.startsWith("plan needs") }
        assertTrue(
            "these levels cannot be solved by any player, the arithmetic forbids it:\n  " +
                impossible.joinToString("\n  ") { "${it.levelId}: ${it.note}" },
            impossible.isEmpty(),
        )
    }

    @Test
    fun `the two teaching rooms are clearable by an automated player`() {
        // Scoped deliberately to 1-1 and 1-2. These two rooms teach the entire vocabulary —
        // walk, jump, bank a take, let the Echo hold a plate — so if a bot that can only walk,
        // jump and wait cannot finish them, the game has handed a first-time player a puzzle
        // before a language, and that is a real defect.
        //
        // It stops there on purpose. Later rooms need multi-step climbs the bot cannot plan, and
        // three previous attempts to assert broader claims about level geometry each flagged
        // deliberate design as breakage. A bot's inability is not evidence of a broken level.
        val taught = sweep().filter { it.levelId == "1-1" || it.levelId == "1-2" }
        val failed = taught.filterNot { it.cleared }
        assertTrue(
            "the teaching rooms must fall to an automated player:\n  " +
                failed.joinToString("\n  ") { "${it.levelId}: ${it.note}" },
            failed.isEmpty(),
        )
    }
}
