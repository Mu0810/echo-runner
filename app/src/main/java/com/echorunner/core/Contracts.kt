package com.echorunner.core

/**
 * Every sound the game can make.
 *
 * The enum is the contract: gameplay code emits a cue and never knows how it is produced. The
 * shipped implementation synthesises each one procedurally (no bundled audio assets), so
 * swapping in recorded audio later means changing only the mapping inside the audio module —
 * not a single call site.
 */
enum class SoundCue {
    FOOTSTEP,
    JUMP,
    LAND,
    DASH,
    SWITCH_ON,
    SWITCH_OFF,
    PLATE_PRESS,
    DOOR_OPEN,
    DOOR_CLOSE,
    ECHO_CREATED,
    ECHO_EXPIRE,
    ENEMY_ALERT,
    TURRET_FIRE,
    LASER_ON,
    TELEPORT,
    DEATH,
    LEVEL_COMPLETE,
    UI_TAP,
    UI_BACK,
    COUNTDOWN_TICK,
}

/** Playback surface for [SoundCue]s plus the ambient music bed. */
interface AudioController {
    fun play(cue: SoundCue, volume: Float = 1f)
    fun startAmbient()
    fun stopAmbient()
    fun setMusicEnabled(enabled: Boolean)
    fun setSfxEnabled(enabled: Boolean)
    fun release()
}

/** No-op audio, used by unit tests and when the user disables sound entirely. */
object SilentAudio : AudioController {
    override fun play(cue: SoundCue, volume: Float) = Unit
    override fun startAmbient() = Unit
    override fun stopAmbient() = Unit
    override fun setMusicEnabled(enabled: Boolean) = Unit
    override fun setSfxEnabled(enabled: Boolean) = Unit
    override fun release() = Unit
}

enum class HapticEvent { LIGHT_TAP, ECHO_CREATED, DEATH, LEVEL_COMPLETE }

interface Haptics {
    fun fire(event: HapticEvent)
    fun setEnabled(enabled: Boolean)
}

object NoHaptics : Haptics {
    override fun fire(event: HapticEvent) = Unit
    override fun setEnabled(enabled: Boolean) = Unit
}

// ---- Modes ---------------------------------------------------------------------------

enum class GameMode { STORY, ENDLESS, CHALLENGE }

/**
 * A Challenge Mode restriction, applied on top of an existing level.
 *
 * Modifiers are transformations over a [LevelSpec] plus a few engine-level flags, so a
 * challenge reuses a story level's geometry rather than duplicating it.
 */
data class ChallengeModifier(
    val id: String,
    val label: String,
    val description: String,
    /** Overrides the level's Echo allowance when non-null. */
    val maxEchoes: Int? = null,
    /** Overrides the take length when non-null, e.g. 15s memory. */
    val takeSeconds: Float? = null,
    val jumpDisabled: Boolean = false,
    /** Total dashes for the whole attempt; null means unlimited. */
    val dashBudget: Int? = null,
    /** Hazards render only when the player is very close. */
    val invisibleTraps: Boolean = false,
) {
    companion object {
        val NONE = ChallengeModifier("none", "Standard", "No restrictions")
    }
}

/** A Challenge entry: which level to run and which restriction to run it under. */
data class ChallengeSpec(
    val id: String,
    val name: String,
    val levelId: String,
    val modifier: ChallengeModifier,
)

// ---- Persistence ---------------------------------------------------------------------

/** Per-level record. [bestTakes] is the primary score: fewer Echoes is a better solve. */
data class LevelRecord(
    val levelId: String,
    val completed: Boolean = false,
    val bestTakes: Int = 0,
    val bestSeconds: Float = 0f,
    val deaths: Int = 0,
)

data class EndlessRecord(
    val bestScore: Int = 0,
    val bestRooms: Int = 0,
)

data class GameSettings(
    val musicEnabled: Boolean = true,
    val sfxEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    /** Mirrors the joystick and action cluster for left-handed play. */
    val leftHanded: Boolean = false,
    /** Scales the on-screen control cluster. */
    val controlScale: Float = 1f,
    val showEchoTrails: Boolean = true,
    /** Caps particles and disables fog for weaker devices. */
    val reducedEffects: Boolean = false,
)

data class GameProgress(
    val levels: Map<String, LevelRecord> = emptyMap(),
    val endless: EndlessRecord = EndlessRecord(),
    val challengesCompleted: Set<String> = emptySet(),
    val settings: GameSettings = GameSettings(),
    /** Chapters whose opening cinematic has been shown, so it is not replayed. */
    val seenCinematics: Set<Int> = emptySet(),
) {
    fun record(levelId: String): LevelRecord = levels[levelId] ?: LevelRecord(levelId)
    fun isCompleted(levelId: String): Boolean = levels[levelId]?.completed == true
    val completedCount: Int get() = levels.values.count { it.completed }
}

/**
 * Progress storage. Writes are expected to be atomic and cheap enough to call on every level
 * completion; implementations should not block the game loop.
 */
interface ProgressRepository {
    fun load(): GameProgress
    fun save(progress: GameProgress)
    fun updateLevel(levelId: String, transform: (LevelRecord) -> LevelRecord)
    fun updateSettings(transform: (GameSettings) -> GameSettings)
}
