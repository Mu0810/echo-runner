package com.echorunner.core

/**
 * The authored description of a level. Pure data, no behaviour.
 *
 * Levels are JSON under `assets/levels/` and are parsed into these types by
 * [com.echorunner.level.LevelParser]. Adding a level means adding a file and listing it in
 * the chapter manifest — no gameplay code changes. Adding a new *kind* of object is the only
 * thing that touches code, and it is deliberately three edits: a spec here, a runtime state
 * in WorldState, and a step in the simulation.
 *
 * Every object that participates in puzzle logic carries a string `id`, and every consumer
 * refers to producers by id through [SignalCondition]. Ids are level-scoped.
 */
data class LevelSpec(
    val id: String,
    val name: String,
    val chapter: Int,
    /** Seconds per take. The Echo memory window. */
    val takeSeconds: Float = GameConstants.DEFAULT_TAKE_SECONDS,
    /** Echoes the level permits. 0 means the level must be solved in a single take. */
    val maxEchoes: Int = 1,
    val spawn: Vec2,
    val exit: Rect,
    /** Exit stays sealed until this resolves true. Null means always open. */
    val exitCondition: SignalCondition? = null,
    val platforms: List<PlatformSpec> = emptyList(),
    val hazards: List<HazardSpec> = emptyList(),
    val plates: List<PlateSpec> = emptyList(),
    val switches: List<SwitchSpec> = emptyList(),
    val doors: List<DoorSpec> = emptyList(),
    val movers: List<MoverSpec> = emptyList(),
    val lasers: List<LaserSpec> = emptyList(),
    val turrets: List<TurretSpec> = emptyList(),
    val walkers: List<WalkerSpec> = emptyList(),
    val teleporters: List<TeleporterSpec> = emptyList(),
    /** Shown once, before the first take. Chapter openers use this for the cinematic beat. */
    val dialogue: List<DialogueLine> = emptyList(),
    /** Free-form hint surfaced in the pause menu. */
    val hint: String = "",
    /** Target take count for a "clean" solve, used for star ratings. */
    val parTakes: Int = 1,
)

/** How a consumer combines the producers it listens to. */
enum class SignalMode { ALL, ANY }

/**
 * A boolean expression over signal ids, e.g. "both plates held" or "either switch on".
 * [invert] turns a door into a portcullis that closes when the signal fires.
 */
data class SignalCondition(
    val ids: List<String>,
    val mode: SignalMode = SignalMode.ALL,
    val invert: Boolean = false,
) {
    companion object {
        val ALWAYS = SignalCondition(emptyList(), SignalMode.ALL, false)
        fun of(id: String) = SignalCondition(listOf(id))
    }
}

enum class PlatformKind {
    /** Blocks from every direction. */
    SOLID,
    /** Blocks only a downward crossing, so the player can jump up through it. */
    ONE_WAY,
    /** Solid, but visually marked as breakable-looking scenery. Behaviour is identical. */
    DECOR,
}

data class PlatformSpec(
    val rect: Rect,
    val kind: PlatformKind = PlatformKind.SOLID,
)

enum class HazardKind { SPIKE, PIT, CRUSHER }

/** Instant-death geometry. [kind] only affects how it is drawn. */
data class HazardSpec(
    val rect: Rect,
    val kind: HazardKind = HazardKind.SPIKE,
)

/**
 * Pressure plate: emits its signal while any body (player, Echo, or a walker) rests on it.
 * When [latching] is true the signal stays on permanently once triggered, which is how a
 * single Echo can "hold" a door open after walking away.
 */
data class PlateSpec(
    val id: String,
    val rect: Rect,
    val latching: Boolean = false,
    /** Only Echoes can trigger it. Used for puzzles that force delegation. */
    val echoOnly: Boolean = false,
)

/**
 * Switch: toggled by the interact action. [holdSeconds] > 0 makes it a timed switch that
 * falls back off, which is the basis of every "race the door" puzzle.
 */
data class SwitchSpec(
    val id: String,
    val rect: Rect,
    val holdSeconds: Float = 0f,
    val startsOn: Boolean = false,
)

/** Door: solid while shut, passable while open. Animates rather than popping. */
data class DoorSpec(
    val id: String,
    val rect: Rect,
    val opensWhen: SignalCondition,
    /** Seconds for a full open/close sweep. */
    val travelSeconds: Float = 0.35f,
)

enum class MoverMode {
    /** Runs its path forever. */
    LOOP,
    /** Advances only while its signal holds; reverses when it drops. Vertical = elevator. */
    SIGNAL,
    /** Runs once when the signal first fires, then stays put. */
    ONE_SHOT,
}

/**
 * Moving platform / elevator. Bodies resting on it are carried, which is what makes it
 * usable as an Echo-operated lift.
 */
data class MoverSpec(
    val id: String,
    val size: Rect,
    /** Waypoints in world units, visited in order. Two nodes is a shuttle. */
    val path: List<Vec2>,
    val speed: Float = 3f,
    val mode: MoverMode = MoverMode.LOOP,
    val activeWhen: SignalCondition? = null,
    /** Seconds paused at each waypoint. */
    val dwellSeconds: Float = 0.25f,
    val startNode: Int = 0,
)

/**
 * Laser: lethal beam on a fixed duty cycle. [phase] offsets the cycle so a corridor of
 * lasers can be authored into a rhythm the player must read.
 */
data class LaserSpec(
    val id: String,
    val rect: Rect,
    val onSeconds: Float = 1.6f,
    val offSeconds: Float = 1.4f,
    val phase: Float = 0f,
    /** While this holds, the beam is forced off regardless of its cycle. */
    val disabledWhen: SignalCondition? = null,
)

/** Turret: fires bullets along [dirX] on an interval when a target is within range. */
data class TurretSpec(
    val id: String,
    val rect: Rect,
    val dirX: Int = -1,
    val intervalSeconds: Float = 1.4f,
    val bulletSpeed: Float = 11f,
    val range: Float = 26f,
    /** While this holds, the turret is offline. */
    val disabledWhen: SignalCondition? = null,
)

/**
 * Walking enemy that patrols between [minX] and [maxX] and diverts toward any body inside
 * [alertRange]. Because an Echo counts as a body, distraction is a first-class mechanic
 * rather than a side effect.
 */
data class WalkerSpec(
    val id: String,
    val spawn: Vec2,
    val minX: Float,
    val maxX: Float,
    val speed: Float = 2.6f,
    val alertRange: Float = 6.5f,
    /** Speed multiplier while chasing. */
    val chaseMultiplier: Float = 1.7f,
)

/** Teleporter pair. Entering either pad emits from the other, with a re-entry cooldown. */
data class TeleporterSpec(
    val id: String,
    val a: Rect,
    val b: Rect,
    val cooldownSeconds: Float = 0.6f,
)

/** One line of the between-chapter cinematic. [speaker] is blank for narration. */
data class DialogueLine(
    val speaker: String,
    val text: String,
)
