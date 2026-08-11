package com.echorunner

import com.echorunner.core.Rating
import com.echorunner.level.LevelParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Rating thresholds, and the guarantee that every shipped level is three-star achievable.
 *
 * That second property is the one worth testing: a level whose authored par is lower than the
 * number of takes its own solution needs would be permanently stuck at two stars, and nothing
 * about playing it would reveal why.
 */
class RatingTest {

    @Test
    fun `three stars needs par and a clean run`() {
        assertEquals(3, Rating.starsFor(takesUsed = 2, parTakes = 2, deaths = 0))
        assertEquals(3, Rating.starsFor(takesUsed = 1, parTakes = 2, deaths = 0))
    }

    @Test
    fun `dying costs the third star even at par`() {
        assertEquals(2, Rating.starsFor(takesUsed = 2, parTakes = 2, deaths = 1))
    }

    @Test
    fun `one take over par is still two stars when clean`() {
        assertEquals(2, Rating.starsFor(takesUsed = 3, parTakes = 2, deaths = 0))
        // ...but not when it also cost a life.
        assertEquals(1, Rating.starsFor(takesUsed = 3, parTakes = 2, deaths = 2))
    }

    @Test
    fun `a scrappy solve still earns one star`() {
        assertEquals(1, Rating.starsFor(takesUsed = 6, parTakes = 2, deaths = 9))
    }

    @Test
    fun `no takes means no rating`() {
        assertEquals(0, Rating.starsFor(takesUsed = 0, parTakes = 2, deaths = 0))
    }

    @Test
    fun `a missing par does not make every solve look over budget`() {
        // parTakes 0 would otherwise fail the `takesUsed <= par` test for every possible run.
        assertEquals(3, Rating.starsFor(takesUsed = 1, parTakes = 0, deaths = 0))
    }

    @Test
    fun `glyphs render all three positions`() {
        assertEquals("★★★", Rating.glyphs(3))
        assertEquals("★☆☆", Rating.glyphs(1))
        assertEquals("☆☆☆", Rating.glyphs(0))
        assertEquals("★★★", Rating.glyphs(99))
    }

    @Test
    fun `every shipped level can be three-starred`() {
        val dir = File("src/main/assets/levels")
        val manifest = JSONObject(File(dir, "manifest.json").readText())
        val chapters = manifest.getJSONArray("chapters")
        for (i in 0 until chapters.length()) {
            val levels = chapters.getJSONObject(i).getJSONArray("levels")
            for (j in 0 until levels.length()) {
                val id = levels.getString(j)
                val spec = LevelParser.parse(File(dir, "$id.json").readText())

                // par must be PLAYABLE: a level allows maxEchoes + 1 takes, so a par above that
                // could never be met and the level would be capped at two stars forever.
                // Note par is NOT required to equal maxEchoes + 1 — several levels grant a spare
                // Echo as slack (5-1 says so in its hint), and that generosity is deliberate.
                val budget = spec.maxEchoes + 1
                assertTrue("$id: par ${spec.parTakes} must be at least 1", spec.parTakes >= 1)
                assertTrue(
                    "$id: par ${spec.parTakes} exceeds the take budget of $budget, so three " +
                        "stars would be unreachable",
                    spec.parTakes <= budget,
                )
                assertEquals(
                    "$id should be three-star achievable by a clean par run",
                    3,
                    Rating.starsFor(spec.parTakes, spec.parTakes, 0),
                )
            }
        }
    }
}
