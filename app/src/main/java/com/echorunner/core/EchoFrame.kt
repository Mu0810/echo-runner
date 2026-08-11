package com.echorunner.core

/** Visual pose of a body, recorded per tick so an Echo animates exactly as the player did. */
enum class AnimState {
    IDLE, RUN, RISE, FALL, DASH, DEAD;

    val code: Byte get() = ordinal.toByte()

    companion object {
        private val VALUES = entries.toTypedArray()
        fun fromCode(b: Byte): AnimState = VALUES[b.toInt().coerceIn(0, VALUES.size - 1)]
    }
}

/** Bit positions inside the per-tick flag byte. */
object EchoFlags {
    const val JUMP = 1
    const val DASH = 1 shl 1
    const val INTERACT = 1 shl 2
    const val GROUNDED = 1 shl 3
    const val FACING_RIGHT = 1 shl 4

    fun pack(
        jump: Boolean, dash: Boolean, interact: Boolean,
        grounded: Boolean, facingRight: Boolean,
    ): Byte {
        var v = 0
        if (jump) v = v or JUMP
        if (dash) v = v or DASH
        if (interact) v = v or INTERACT
        if (grounded) v = v or GROUNDED
        if (facingRight) v = v or FACING_RIGHT
        return v.toByte()
    }

    fun has(flags: Byte, mask: Int): Boolean = (flags.toInt() and mask) != 0
}

/**
 * Growable buffer that captures the player's motion for the current take.
 *
 * ## Why this records state, not input
 *
 * The obvious alternative is to record button presses and re-simulate them. That is smaller
 * and self-evidently deterministic in isolation, but it makes an Echo's path depend on the
 * world it replays into: the moment a door is shut that was open during recording, the
 * re-simulated Echo collides differently and diverges — and because Echoes open doors for
 * each other, divergence is the normal case, not an edge case. A puzzle built on that is not
 * solvable by planning, which is the entire point of the game.
 *
 * Recording resolved positions makes an Echo a *fixed* replay of what the player did. Echoes
 * are kinematic: they never collide with geometry (their path is already known to be valid)
 * but they retain full presence for plates, switches, enemies and traps. That is what makes
 * a plan the player made two takes ago still true now.
 *
 * ## Layout
 *
 * Parallel primitive arrays, preallocated once to [GameConstants.MAX_TAKE_TICKS]. A list of
 * per-tick objects would allocate ~1800 objects per take and hand the GC a sawtooth during
 * play. Cost here is ~34 KB per Echo, so five Echoes fit in under 200 KB.
 */
class EchoRecording(private val capacity: Int = GameConstants.MAX_TAKE_TICKS) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val vxs = FloatArray(capacity)
    private val vys = FloatArray(capacity)
    private val flags = ByteArray(capacity)
    private val anim = ByteArray(capacity)

    var length: Int = 0
        private set

    fun reset() { length = 0 }

    /** Appends one tick. Silently ignores overflow past capacity rather than crashing mid-run. */
    fun push(
        x: Float, y: Float, vx: Float, vy: Float,
        jump: Boolean, dash: Boolean, interact: Boolean,
        grounded: Boolean, facingRight: Boolean,
        state: AnimState,
    ) {
        val i = length
        if (i >= capacity) return
        xs[i] = x; ys[i] = y; vxs[i] = vx; vys[i] = vy
        flags[i] = EchoFlags.pack(jump, dash, interact, grounded, facingRight)
        anim[i] = state.code
        length = i + 1
    }

    /** Freezes the take into an immutable replay, trimmed to the ticks actually recorded. */
    fun freeze(index: Int): EchoReplay = EchoReplay(
        index = index,
        xs = xs.copyOf(length),
        ys = ys.copyOf(length),
        vxs = vxs.copyOf(length),
        vys = vys.copyOf(length),
        flags = flags.copyOf(length),
        anim = anim.copyOf(length),
    )
}

/**
 * An immutable, finished Echo track.
 *
 * Reads are clamped rather than wrapped: once an Echo runs out of recorded ticks it holds its
 * final pose instead of looping. A wrap would make a short take silently repeat mid-puzzle
 * and break any plan built around "the Echo stands on the plate and stays there".
 */
class EchoReplay(
    /** 0-based creation order. Drives the Echo's hue so the player can tell them apart. */
    val index: Int,
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val vxs: FloatArray,
    private val vys: FloatArray,
    private val flags: ByteArray,
    private val anim: ByteArray,
) {
    val length: Int get() = xs.size
    val isEmpty: Boolean get() = xs.isEmpty()
    val durationSeconds: Float get() = length * GameConstants.DT

    private fun idx(tick: Int): Int = if (length == 0) 0 else clamp(tick, 0, length - 1)

    fun x(tick: Int): Float = if (isEmpty) 0f else xs[idx(tick)]
    fun y(tick: Int): Float = if (isEmpty) 0f else ys[idx(tick)]
    fun vx(tick: Int): Float = if (isEmpty) 0f else vxs[idx(tick)]
    fun vy(tick: Int): Float = if (isEmpty) 0f else vys[idx(tick)]

    fun animAt(tick: Int): AnimState =
        if (isEmpty) AnimState.IDLE else AnimState.fromCode(anim[idx(tick)])

    fun facingRight(tick: Int): Boolean =
        if (isEmpty) true else EchoFlags.has(flags[idx(tick)], EchoFlags.FACING_RIGHT)

    fun grounded(tick: Int): Boolean =
        if (isEmpty) false else EchoFlags.has(flags[idx(tick)], EchoFlags.GROUNDED)

    /** True only on the tick the action began, so one-shot cues fire once. */
    fun startedDash(tick: Int): Boolean = edge(tick, EchoFlags.DASH)
    fun startedJump(tick: Int): Boolean = edge(tick, EchoFlags.JUMP)
    fun interacting(tick: Int): Boolean =
        !isEmpty && EchoFlags.has(flags[idx(tick)], EchoFlags.INTERACT)

    private fun edge(tick: Int, mask: Int): Boolean {
        if (isEmpty || tick <= 0 || tick >= length) return false
        return EchoFlags.has(flags[tick], mask) && !EchoFlags.has(flags[tick - 1], mask)
    }

    /**
     * Position for *rendering*, interpolated between the current and next recorded tick.
     *
     * The simulation and the recording both run at 60 Hz, so replay is exact tick-for-tick;
     * `alpha` exists because the display may refresh at 90/120 Hz, and sampling the same
     * tick repeatedly would show a stair-step. This is the "no visible teleporting"
     * requirement: it is a render-time concern only and never feeds back into simulation.
     */
    fun renderX(tick: Int, alpha: Float): Float {
        if (isEmpty) return 0f
        val a = xs[idx(tick)]
        val b = xs[idx(tick + 1)]
        return lerp(a, b, clamp(alpha, 0f, 1f))
    }

    fun renderY(tick: Int, alpha: Float): Float {
        if (isEmpty) return 0f
        val a = ys[idx(tick)]
        val b = ys[idx(tick + 1)]
        return lerp(a, b, clamp(alpha, 0f, 1f))
    }

    /** Approximate retained heap, surfaced in the debug overlay. */
    fun approxBytes(): Int = length * (4 * 4 + 2)
}
