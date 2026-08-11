package com.echorunner

import com.echorunner.core.GameConstants
import com.echorunner.core.PlatformKind
import com.echorunner.level.LevelParser
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract test between the hand-authored level JSON and the parser.
 *
 * These were written by different hands, so this is the seam most likely to break silently: a
 * level that fails to parse crashes on load, and one that parses into nonsense produces a room
 * the player cannot finish. Unit tests run with the module directory as the working directory,
 * so the shipping assets are read straight off disk.
 */
class LevelAssetTest {

    private val levelsDir = File("src/main/assets/levels")

    private fun manifestOrder(): List<String> {
        val root = JSONObject(File(levelsDir, "manifest.json").readText())
        val chapters = root.getJSONArray("chapters")
        val ids = ArrayList<String>()
        for (i in 0 until chapters.length()) {
            val levels = chapters.getJSONObject(i).getJSONArray("levels")
            for (j in 0 until levels.length()) ids.add(levels.getString(j))
        }
        return ids
    }

    @Test
    fun `every manifest level exists and parses`() {
        val ids = manifestOrder()
        assertTrue("manifest should list levels", ids.isNotEmpty())
        for (id in ids) {
            val file = File(levelsDir, "$id.json")
            assertTrue("missing asset $id.json", file.exists())
            val spec = LevelParser.parse(file.readText())
            assertTrue("id mismatch in $id.json: got ${spec.id}", spec.id == id)
            assertTrue("$id has no name", spec.name.isNotBlank())
            assertTrue("$id has no geometry", spec.platforms.isNotEmpty())
            assertTrue(
                "$id echo budget ${spec.maxEchoes} exceeds engine cap",
                spec.maxEchoes <= GameConstants.MAX_ECHOES,
            )
            assertTrue("$id take length ${spec.takeSeconds}s is not sane", spec.takeSeconds in 5f..45f)
        }
    }

    @Test
    fun `spawn and exit are inside the world and on solid ground`() {
        for (id in manifestOrder()) {
            val spec = LevelParser.parse(File(levelsDir, "$id.json").readText())

            assertTrue("$id spawn x out of world", spec.spawn.x in 0f..GameConstants.WORLD_W)
            assertTrue("$id spawn y out of world", spec.spawn.y in 0f..GameConstants.WORLD_H)
            assertTrue("$id exit x out of world", spec.exit.x in 0f..GameConstants.WORLD_W)

            // The player's feet must land on some platform's top edge, or they start falling
            // through the floor and the level is unplayable from tick zero.
            val feet = spec.spawn.y + GameConstants.PLAYER_H
            val standing = spec.platforms.any { p ->
                p.kind != PlatformKind.ONE_WAY &&
                    kotlin.math.abs(p.rect.y - feet) < 0.75f &&
                    spec.spawn.x >= p.rect.x - 0.5f &&
                    spec.spawn.x <= p.rect.right
            }
            assertTrue("$id spawn is not standing on anything (feet y=$feet)", standing)
        }
    }

    @Test
    fun `every referenced signal id is produced by something`() {
        for (id in manifestOrder()) {
            val spec = LevelParser.parse(File(levelsDir, "$id.json").readText())
            val produced = spec.plates.map { it.id }.toSet() + spec.switches.map { it.id }.toSet()

            val referenced = buildList {
                spec.doors.forEach { addAll(it.opensWhen.ids) }
                spec.movers.forEach { m -> m.activeWhen?.let { addAll(it.ids) } }
                spec.lasers.forEach { l -> l.disabledWhen?.let { addAll(it.ids) } }
                spec.turrets.forEach { t -> t.disabledWhen?.let { addAll(it.ids) } }
                spec.exitCondition?.let { addAll(it.ids) }
            }

            val dangling = referenced.filter { it !in produced }
            assertTrue(
                "$id references signals nothing produces: $dangling",
                dangling.isEmpty(),
            )
        }
    }

    @Test
    fun `levels that need cooperation actually budget for it`() {
        for (id in manifestOrder()) {
            val spec = LevelParser.parse(File(levelsDir, "$id.json").readText())
            // A door gated on N distinct plates needs at least N-1 Echoes plus the live player,
            // otherwise there are not enough bodies in existence to open it.
            spec.doors.forEach { door ->
                val plateGates = door.opensWhen.ids.count { gate -> spec.plates.any { it.id == gate } }
                if (plateGates > 1) {
                    assertTrue(
                        "$id door ${door.id} needs $plateGates plates held but allows only " +
                            "${spec.maxEchoes} Echoes",
                        spec.maxEchoes >= plateGates - 1,
                    )
                }
            }
            // Every level beyond the first must give at least one Echo, or the mechanic is absent.
            if (id != "1-1") {
                assertTrue("$id has no Echo budget", spec.maxEchoes >= 1)
            }
        }
    }

    @Test
    fun `each level records its intended solution`() {
        for (id in manifestOrder()) {
            val spec = LevelParser.parse(File(levelsDir, "$id.json").readText())
            assertTrue("$id is missing a hint / solution note", spec.hint.length > 40)
        }
    }
}
