package com.echorunner.core

/** Top-level state of a level attempt. Pausing is orthogonal and tracked separately. */
enum class GamePhase {
    /** Showing the level's opening dialogue. Simulation is frozen. */
    INTRO,
    /** A take is running: the player acts while existing Echoes replay. */
    PLAYING,
    /** The take just ended and became an Echo. Brief, cosmetic, then back to PLAYING. */
    TAKE_TRANSITION,
    /** Player died. Freezes briefly, then restarts the take with Echoes intact. */
    DEAD,
    /** Player reached the exit. */
    COMPLETE,
    /** All takes spent without solving it. Restarts the level from take one. */
    OUT_OF_ECHOES,
}

/** Why the current take ended. Drives which cue plays and what happens next. */
enum class TakeEndReason { TIMER, DEATH, MANUAL }

/**
 * The player's physical body. Plain mutable fields, integrated in place by the physics step.
 *
 * `prevX`/`prevY` hold last tick's resolved position purely so rendering can interpolate at
 * display refresh rates above 60 Hz without the simulation caring.
 */
class PlayerBody {
    var x = 0f; var y = 0f
    var prevX = 0f; var prevY = 0f
    var vx = 0f; var vy = 0f

    var onGround = false
    var facingRight = true
    var alive = true

    /** Counts down while the dash is active; > 0 means velocity is dash-controlled. */
    var dashTimer = 0f
    var dashCooldown = 0f
    var dashesUsed = 0

    var coyoteTimer = 0f
    var jumpBuffer = 0f
    var jumpHeld = false

    /** Set for the single tick an action begins, so cues and particles fire once. */
    var startedJump = false
    var startedDash = false
    var interacting = false

    var anim = AnimState.IDLE

    /** Platform the body is standing on, so mover motion can be inherited. */
    var carrierId: String? = null

    val w: Float get() = GameConstants.PLAYER_W
    val h: Float get() = GameConstants.PLAYER_H

    fun resetTo(spawn: Vec2) {
        x = spawn.x; y = spawn.y
        prevX = x; prevY = y
        vx = 0f; vy = 0f
        onGround = false; facingRight = true; alive = true
        dashTimer = 0f; dashCooldown = 0f; dashesUsed = 0
        coyoteTimer = 0f; jumpBuffer = 0f; jumpHeld = false
        startedJump = false; startedDash = false; interacting = false
        anim = AnimState.IDLE
        carrierId = null
    }

    fun renderX(alpha: Float) = lerp(prevX, x, alpha)
    fun renderY(alpha: Float) = lerp(prevY, y, alpha)
}

/**
 * A materialised Echo: a replay track plus the cursor into it.
 *
 * Echoes are kinematic — position comes straight from [replay], never from physics. They
 * still occupy space for plates, switches, hazards and enemy perception, which is the whole
 * mechanic. See [EchoReplay]'s note for why this is state playback and not re-simulation.
 */
class EchoBody(val replay: EchoReplay) {
    var tick = 0
    var x = 0f; var y = 0f
    var prevX = 0f; var prevY = 0f
    var facingRight = true
    var anim = AnimState.IDLE
    /** False once the track is exhausted; the Echo holds its final pose and stops emitting cues. */
    var active = true

    val index: Int get() = replay.index
    val w: Float get() = GameConstants.PLAYER_W
    val h: Float get() = GameConstants.PLAYER_H

    fun rewind() {
        tick = 0
        x = replay.x(0); y = replay.y(0)
        prevX = x; prevY = y
        facingRight = replay.facingRight(0)
        anim = replay.animAt(0)
        active = replay.length > 0
    }

    fun renderX(alpha: Float) = replay.renderX(tick, alpha)
    fun renderY(alpha: Float) = replay.renderY(tick, alpha)
}

// ---- Runtime entity state ------------------------------------------------------------
// Each wraps its immutable spec and adds only what changes during play, so a level reset is
// a matter of rebuilding these from the same specs.

class PlateState(val spec: PlateSpec) {
    var pressed = false
    var latched = false
    /** Bodies currently on it, for the visual press depth. */
    var contacts = 0
    fun reset() { pressed = false; latched = false; contacts = 0 }
}

class SwitchState(val spec: SwitchSpec) {
    var on = spec.startsOn
    var timer = 0f
    /** Blocks a single interact press from toggling every tick it is held. */
    var consumedThisTake = false
    fun reset() { on = spec.startsOn; timer = 0f; consumedThisTake = false }
}

class DoorState(val spec: DoorSpec) {
    /** 0 = fully shut and solid, 1 = fully open and passable. */
    var openAmount = 0f
    val solid: Boolean get() = openAmount < 0.85f
    fun reset() { openAmount = 0f }
}

class MoverState(val spec: MoverSpec) {
    var x = 0f; var y = 0f
    var prevX = 0f; var prevY = 0f
    /** Per-tick delta, applied to whatever is riding it. */
    var dx = 0f; var dy = 0f
    var node = 0
    var nodeProgress = 0f
    var dwell = 0f
    var forward = true
    var triggered = false

    fun reset() {
        node = spec.startNode.coerceIn(0, maxOf(0, spec.path.size - 1))
        val start = spec.path.getOrElse(node) { Vec2.ZERO }
        x = start.x; y = start.y
        prevX = x; prevY = y
        dx = 0f; dy = 0f
        nodeProgress = 0f; dwell = 0f; forward = true; triggered = false
    }

    fun renderX(alpha: Float) = lerp(prevX, x, alpha)
    fun renderY(alpha: Float) = lerp(prevY, y, alpha)
}

class LaserState(val spec: LaserSpec) {
    var on = true
    var cycle = 0f
    fun reset() { cycle = spec.phase; on = true }
}

class TurretState(val spec: TurretSpec) {
    var cooldown = 0f
    var alerted = false
    fun reset() { cooldown = 0f; alerted = false }
}

class WalkerState(val spec: WalkerSpec) {
    var x = 0f; var y = 0f
    var prevX = 0f; var prevY = 0f
    var dir = 1
    var alerted = false
    var distractionTimer = 0f
    val w = 0.9f
    val h = 1.3f

    fun reset() {
        x = spec.spawn.x; y = spec.spawn.y
        prevX = x; prevY = y
        dir = 1; alerted = false; distractionTimer = 0f
    }

    fun renderX(alpha: Float) = lerp(prevX, x, alpha)
    fun renderY(alpha: Float) = lerp(prevY, y, alpha)
}

class TeleporterState(val spec: TeleporterSpec) {
    var cooldown = 0f
    fun reset() { cooldown = 0f }
}

/**
 * Fixed-capacity bullet pool. Indices are reused; `alive` marks occupancy. Sized by
 * [GameConstants.MAX_BULLETS] so no allocation happens once the level is loaded.
 */
class BulletPool(capacity: Int = GameConstants.MAX_BULLETS) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val alive = BooleanArray(capacity)
    val size: Int get() = alive.size

    fun spawn(px: Float, py: Float, velX: Float): Boolean {
        for (i in alive.indices) {
            if (!alive[i]) {
                x[i] = px; y[i] = py; vx[i] = velX; alive[i] = true
                return true
            }
        }
        return false
    }

    fun clear() { for (i in alive.indices) alive[i] = false }
}

/** Fixed-capacity particle pool for dust, sparks, dash trails and death bursts. */
class ParticlePool(capacity: Int = GameConstants.MAX_PARTICLES) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val vx = FloatArray(capacity)
    val vy = FloatArray(capacity)
    val life = FloatArray(capacity)
    val maxLife = FloatArray(capacity)
    val size = FloatArray(capacity)
    /** Index into the renderer's palette rather than a packed colour, to keep this pure. */
    val tint = IntArray(capacity)
    private var cursor = 0

    val capacityCount: Int get() = life.size

    /** Overwrites the oldest slot when full: effects degrade rather than being dropped. */
    fun emit(px: Float, py: Float, velX: Float, velY: Float, ttl: Float, sz: Float, tintIndex: Int) {
        val i = cursor
        cursor = (cursor + 1) % life.size
        x[i] = px; y[i] = py; vx[i] = velX; vy[i] = velY
        life[i] = ttl; maxLife[i] = ttl; size[i] = sz; tint[i] = tintIndex
    }

    fun clear() { for (i in life.indices) life[i] = 0f }
}

/** Camera with follow damping and decaying shake. Shake is render-only; it never moves bodies. */
class Camera {
    var x = GameConstants.WORLD_W * 0.5f
    var y = GameConstants.WORLD_H * 0.5f
    var shake = 0f
    var shakeOffsetX = 0f
    var shakeOffsetY = 0f

    fun kick(amount: Float) { shake = maxOf(shake, amount) }

    fun reset() {
        x = GameConstants.WORLD_W * 0.5f
        y = GameConstants.WORLD_H * 0.5f
        shake = 0f; shakeOffsetX = 0f; shakeOffsetY = 0f
    }
}

/**
 * Everything mutable about a level in progress.
 *
 * Built once per level load from a [LevelSpec]; [resetTake] returns it to the level's initial
 * conditions without reallocating, which is what makes restarting a take cheap enough to feel
 * instant. Nothing in here knows about Compose, Android or audio.
 */
class WorldState(val level: LevelSpec) {
    val player = PlayerBody()
    val echoes = ArrayList<EchoBody>(GameConstants.MAX_ECHOES)

    val plates = level.plates.map(::PlateState)
    val switches = level.switches.map(::SwitchState)
    val doors = level.doors.map(::DoorState)
    val movers = level.movers.map(::MoverState)
    val lasers = level.lasers.map(::LaserState)
    val turrets = level.turrets.map(::TurretState)
    val walkers = level.walkers.map(::WalkerState)
    val teleporters = level.teleporters.map(::TeleporterState)

    val bullets = BulletPool()
    val particles = ParticlePool()
    val camera = Camera()

    /** Resolved signal values for this tick, keyed by producer id. */
    val signals = HashMap<String, Boolean>(16)

    var phase = GamePhase.INTRO
    var paused = false

    /** 0-based index of the take in progress. Take 0 runs with no Echoes. */
    var takeIndex = 0
    /** Ticks elapsed in the current take. */
    var tick = 0
    var deaths = 0
    var totalSeconds = 0f
    /** Counts down during death and completion holds. */
    var phaseTimer = 0f
    var lastTakeEnd: TakeEndReason? = null

    val takeTicks: Int get() = (level.takeSeconds * GameConstants.TICK_HZ).toInt()
    val secondsLeft: Float get() = maxOf(0f, (takeTicks - tick) * GameConstants.DT)
    val echoCount: Int get() = echoes.size
    val echoesRemaining: Int get() = maxOf(0, level.maxEchoes - echoes.size)

    fun signal(id: String): Boolean = signals[id] == true

    /** Evaluates a consumer's condition. A null or empty condition means "always on". */
    fun evaluate(cond: SignalCondition?): Boolean {
        if (cond == null || cond.ids.isEmpty()) return true
        val raw = when (cond.mode) {
            SignalMode.ALL -> cond.ids.all { signal(it) }
            SignalMode.ANY -> cond.ids.any { signal(it) }
        }
        return raw != cond.invert
    }

    /** Returns the world to its authored initial state for a fresh take. Keeps Echoes. */
    fun resetTake() {
        player.resetTo(level.spawn)
        plates.forEach(PlateState::reset)
        switches.forEach(SwitchState::reset)
        doors.forEach(DoorState::reset)
        movers.forEach(MoverState::reset)
        lasers.forEach(LaserState::reset)
        turrets.forEach(TurretState::reset)
        walkers.forEach(WalkerState::reset)
        teleporters.forEach(TeleporterState::reset)
        bullets.clear()
        particles.clear()
        signals.clear()
        echoes.forEach(EchoBody::rewind)
        camera.reset()
        tick = 0
    }

    /** Full level restart: drops every Echo and returns to take one. */
    fun resetLevel() {
        echoes.clear()
        takeIndex = 0
        deaths = 0
        totalSeconds = 0f
        lastTakeEnd = null
        resetTake()
        phase = GamePhase.PLAYING
    }
}
