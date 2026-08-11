package com.echorunner.level

import com.echorunner.core.DialogueLine
import com.echorunner.core.DoorSpec
import com.echorunner.core.GameConstants
import com.echorunner.core.HazardKind
import com.echorunner.core.HazardSpec
import com.echorunner.core.LaserSpec
import com.echorunner.core.LevelSpec
import com.echorunner.core.MoverMode
import com.echorunner.core.MoverSpec
import com.echorunner.core.PlatformKind
import com.echorunner.core.PlatformSpec
import com.echorunner.core.PlateSpec
import com.echorunner.core.Rect
import com.echorunner.core.SignalCondition
import com.echorunner.core.SignalMode
import com.echorunner.core.SwitchSpec
import com.echorunner.core.TeleporterSpec
import com.echorunner.core.TurretSpec
import com.echorunner.core.Vec2
import com.echorunner.core.WalkerSpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a level JSON document into a [LevelSpec].
 *
 * Uses `org.json`, which ships with Android, so the game pulls in no serialization library.
 *
 * ## Failure policy
 *
 * Optional fields fall back to the data-class defaults. A **required** field that is missing
 * or malformed throws [IllegalArgumentException] naming the level and the field. That
 * asymmetry is deliberate: a level missing its spawn point would otherwise load "fine" and
 * strand the player inside a wall, which is far harder to diagnose than a loud failure at
 * load time.
 *
 * ## Accepted shorthands
 *
 * A rect may be `{"x":..,"y":..,"w":..,"h":..}` or `[x,y,w,h]`; a point may be
 * `{"x":..,"y":..}` or `[x,y]`; a signal condition may be a bare id string or
 * `{"ids":[..],"mode":"ALL|ANY","invert":bool}`. The array forms keep hand-authored level
 * files readable — a room is mostly rectangles, and forcing four keys onto each one triples
 * the file size for no gain.
 */
object LevelParser {

    fun parse(json: String): LevelSpec {
        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            throw IllegalArgumentException("level JSON is not a valid object: ${t.message}", t)
        }

        val id = root.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("level is missing required field 'id'")

        return LevelSpec(
            id = id,
            name = root.optString("name").ifBlank { id },
            chapter = requireInt(root, "chapter", id),
            takeSeconds = root.optDouble("takeSeconds", GameConstants.DEFAULT_TAKE_SECONDS.toDouble())
                .toFloat(),
            maxEchoes = root.optInt("maxEchoes", 1).coerceIn(0, GameConstants.MAX_ECHOES),
            spawn = requirePoint(root.opt("spawn"), id, "spawn"),
            exit = requireRect(root.opt("exit"), id, "exit"),
            exitCondition = parseSignal(root.opt("exitCondition")),
            platforms = mapArray(root, "platforms") { parsePlatform(it, id) },
            hazards = mapArray(root, "hazards") { parseHazard(it, id) },
            plates = mapArray(root, "plates") { parsePlate(it, id) },
            switches = mapArray(root, "switches") { parseSwitch(it, id) },
            doors = mapArray(root, "doors") { parseDoor(it, id) },
            movers = mapArray(root, "movers") { parseMover(it, id) },
            lasers = mapArray(root, "lasers") { parseLaser(it, id) },
            turrets = mapArray(root, "turrets") { parseTurret(it, id) },
            walkers = mapArray(root, "walkers") { parseWalker(it, id) },
            teleporters = mapArray(root, "teleporters") { parseTeleporter(it, id) },
            dialogue = mapArray(root, "dialogue") { parseDialogue(it) },
            hint = root.optString("hint", ""),
            parTakes = root.optInt("parTakes", 1),
        )
    }

    /**
     * Parses a signal condition from any of its accepted spellings.
     *
     * Returns null for absent/`null`, which every consumer reads as "always true".
     */
    fun parseSignal(value: Any?): SignalCondition? {
        if (value == null || value === JSONObject.NULL) return null
        if (value is String) {
            return if (value.isBlank()) null else SignalCondition.of(value)
        }
        if (value !is JSONObject) return null

        val idsArray = value.optJSONArray("ids") ?: run {
            // Tolerate {"id":"p1"} as well; it is an easy thing to write by mistake.
            val single = value.optString("id")
            return if (single.isBlank()) null else SignalCondition.of(single)
        }
        val ids = ArrayList<String>(idsArray.length())
        for (i in 0 until idsArray.length()) {
            idsArray.optString(i).takeIf { it.isNotBlank() }?.let(ids::add)
        }
        if (ids.isEmpty()) return null

        val mode = when (value.optString("mode", "ALL").uppercase()) {
            "ANY" -> SignalMode.ANY
            else -> SignalMode.ALL
        }
        return SignalCondition(ids, mode, value.optBoolean("invert", false))
    }

    // ---- entities --------------------------------------------------------------------

    private fun parsePlatform(o: JSONObject, level: String) = PlatformSpec(
        rect = requireRect(o.opt("rect"), level, "platforms[].rect"),
        kind = when (o.optString("kind", "SOLID").uppercase()) {
            "ONE_WAY", "ONEWAY" -> PlatformKind.ONE_WAY
            "DECOR" -> PlatformKind.DECOR
            else -> PlatformKind.SOLID
        },
    )

    private fun parseHazard(o: JSONObject, level: String) = HazardSpec(
        rect = requireRect(o.opt("rect"), level, "hazards[].rect"),
        kind = when (o.optString("kind", "SPIKE").uppercase()) {
            "PIT" -> HazardKind.PIT
            "CRUSHER" -> HazardKind.CRUSHER
            else -> HazardKind.SPIKE
        },
    )

    private fun parsePlate(o: JSONObject, level: String) = PlateSpec(
        id = requireId(o, level, "plates"),
        rect = requireRect(o.opt("rect"), level, "plates[].rect"),
        latching = o.optBoolean("latching", false),
        echoOnly = o.optBoolean("echoOnly", false),
    )

    private fun parseSwitch(o: JSONObject, level: String) = SwitchSpec(
        id = requireId(o, level, "switches"),
        rect = requireRect(o.opt("rect"), level, "switches[].rect"),
        holdSeconds = o.optDouble("holdSeconds", 0.0).toFloat(),
        startsOn = o.optBoolean("startsOn", false),
    )

    private fun parseDoor(o: JSONObject, level: String) = DoorSpec(
        id = requireId(o, level, "doors"),
        rect = requireRect(o.opt("rect"), level, "doors[].rect"),
        // A door with no condition would be permanently shut and silently wall the level off,
        // so treat a missing condition as "always open" instead.
        opensWhen = parseSignal(o.opt("opensWhen")) ?: SignalCondition.ALWAYS,
        travelSeconds = o.optDouble("travelSeconds", 0.35).toFloat().coerceAtLeast(0.01f),
    )

    private fun parseMover(o: JSONObject, level: String): MoverSpec {
        val pathArray = o.optJSONArray("path")
            ?: throw IllegalArgumentException("level '$level': movers[] requires 'path'")
        val path = ArrayList<Vec2>(pathArray.length())
        for (i in 0 until pathArray.length()) {
            path.add(requirePoint(pathArray.opt(i), level, "movers[].path[$i]"))
        }
        return MoverSpec(
            id = requireId(o, level, "movers"),
            size = requireSize(o.opt("size"), level),
            path = path,
            speed = o.optDouble("speed", 3.0).toFloat(),
            mode = when (o.optString("mode", "LOOP").uppercase()) {
                "SIGNAL" -> MoverMode.SIGNAL
                "ONE_SHOT", "ONESHOT" -> MoverMode.ONE_SHOT
                else -> MoverMode.LOOP
            },
            activeWhen = parseSignal(o.opt("activeWhen")),
            dwellSeconds = o.optDouble("dwellSeconds", 0.25).toFloat(),
            startNode = o.optInt("startNode", 0),
        )
    }

    private fun parseLaser(o: JSONObject, level: String) = LaserSpec(
        id = requireId(o, level, "lasers"),
        rect = requireRect(o.opt("rect"), level, "lasers[].rect"),
        onSeconds = o.optDouble("onSeconds", 1.6).toFloat(),
        offSeconds = o.optDouble("offSeconds", 1.4).toFloat(),
        phase = o.optDouble("phase", 0.0).toFloat(),
        disabledWhen = parseSignal(o.opt("disabledWhen")),
    )

    private fun parseTurret(o: JSONObject, level: String) = TurretSpec(
        id = requireId(o, level, "turrets"),
        rect = requireRect(o.opt("rect"), level, "turrets[].rect"),
        dirX = if (o.optInt("dirX", -1) >= 0) 1 else -1,
        intervalSeconds = o.optDouble("intervalSeconds", 1.4).toFloat(),
        bulletSpeed = o.optDouble("bulletSpeed", 11.0).toFloat(),
        range = o.optDouble("range", 26.0).toFloat(),
        disabledWhen = parseSignal(o.opt("disabledWhen")),
    )

    private fun parseWalker(o: JSONObject, level: String): WalkerSpec {
        val spawn = requirePoint(o.opt("spawn"), level, "walkers[].spawn")
        // Default the patrol span to the spawn point so a walker missing bounds stands still
        // rather than sliding to x=0 and out of the room.
        val minX = o.optDouble("minX", spawn.x.toDouble()).toFloat()
        val maxX = o.optDouble("maxX", spawn.x.toDouble()).toFloat()
        return WalkerSpec(
            id = requireId(o, level, "walkers"),
            spawn = spawn,
            minX = minOf(minX, maxX),
            maxX = maxOf(minX, maxX),
            speed = o.optDouble("speed", 2.6).toFloat(),
            alertRange = o.optDouble("alertRange", 6.5).toFloat(),
            chaseMultiplier = o.optDouble("chaseMultiplier", 1.7).toFloat(),
        )
    }

    private fun parseTeleporter(o: JSONObject, level: String) = TeleporterSpec(
        id = requireId(o, level, "teleporters"),
        a = requireRect(o.opt("a"), level, "teleporters[].a"),
        b = requireRect(o.opt("b"), level, "teleporters[].b"),
        cooldownSeconds = o.optDouble("cooldownSeconds", 0.6).toFloat(),
    )

    private fun parseDialogue(o: JSONObject) = DialogueLine(
        speaker = o.optString("speaker", ""),
        text = o.optString("text", ""),
    )

    // ---- primitives ------------------------------------------------------------------

    private inline fun <T> mapArray(
        root: JSONObject,
        key: String,
        parse: (JSONObject) -> T,
    ): List<T> {
        val array = root.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<T>(array.length())
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { out.add(parse(it)) }
        }
        return out
    }

    private fun requireRect(value: Any?, level: String, field: String): Rect = when (value) {
        is JSONArray -> {
            if (value.length() < 4) {
                throw IllegalArgumentException("level '$level': $field array needs [x,y,w,h]")
            }
            Rect(
                value.optDouble(0).toFloat(), value.optDouble(1).toFloat(),
                value.optDouble(2).toFloat(), value.optDouble(3).toFloat(),
            )
        }
        is JSONObject -> Rect(
            value.optDouble("x", 0.0).toFloat(), value.optDouble("y", 0.0).toFloat(),
            value.optDouble("w", 0.0).toFloat(), value.optDouble("h", 0.0).toFloat(),
        )
        else -> throw IllegalArgumentException("level '$level': missing or invalid '$field'")
    }

    /** A mover's size uses only w/h; its position comes from the path. */
    private fun requireSize(value: Any?, level: String): Rect = when (value) {
        is JSONArray -> {
            if (value.length() >= 4) {
                Rect(0f, 0f, value.optDouble(2).toFloat(), value.optDouble(3).toFloat())
            } else {
                Rect(0f, 0f, value.optDouble(0).toFloat(), value.optDouble(1).toFloat())
            }
        }
        is JSONObject -> Rect(
            0f, 0f,
            value.optDouble("w", 0.0).toFloat(),
            value.optDouble("h", 0.0).toFloat(),
        )
        else -> throw IllegalArgumentException("level '$level': movers[] missing 'size'")
    }

    private fun requirePoint(value: Any?, level: String, field: String): Vec2 = when (value) {
        is JSONArray -> {
            if (value.length() < 2) {
                throw IllegalArgumentException("level '$level': $field array needs [x,y]")
            }
            Vec2(value.optDouble(0).toFloat(), value.optDouble(1).toFloat())
        }
        is JSONObject -> Vec2(
            value.optDouble("x", 0.0).toFloat(),
            value.optDouble("y", 0.0).toFloat(),
        )
        else -> throw IllegalArgumentException("level '$level': missing or invalid '$field'")
    }

    private fun requireId(o: JSONObject, level: String, group: String): String =
        o.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("level '$level': $group[] entry is missing 'id'")

    private fun requireInt(root: JSONObject, field: String, level: String): Int =
        if (root.has(field)) root.optInt(field)
        else throw IllegalArgumentException("level '$level': missing required field '$field'")
}
