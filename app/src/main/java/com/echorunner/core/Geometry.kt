package com.echorunner.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Immutable 2D point. Used for *configuration* data (spawn points, path nodes) only.
 *
 * Runtime bodies deliberately store bare `var x: Float` / `var y: Float` fields instead of
 * holding a Vec2, because the simulation steps 60x per second over every entity and an
 * allocation per entity per tick is exactly the pressure that causes GC hitches on
 * mid-range phones. Treat this type as config-time sugar, never as hot-loop state.
 */
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Float) = Vec2(x * s, y * s)

    companion object {
        val ZERO = Vec2(0f, 0f)
    }
}

/**
 * Axis-aligned bounding box in world units, anchored at its top-left corner.
 *
 * The world uses **y-down** to match the rendering surface: y increases toward the bottom
 * of the screen, so gravity is a *positive* y acceleration and a jump is *negative*. This
 * removes the flip that would otherwise sit between simulation and Canvas and be a
 * permanent source of sign bugs.
 */
data class Rect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right: Float get() = x + w
    val bottom: Float get() = y + h
    val centerX: Float get() = x + w * 0.5f
    val centerY: Float get() = y + h * 0.5f
}

/** Allocation-free AABB overlap test. Touching edges do **not** count as overlapping. */
fun overlaps(
    ax: Float, ay: Float, aw: Float, ah: Float,
    bx: Float, by: Float, bw: Float, bh: Float,
): Boolean = ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by

/** Overlap test with a symmetric tolerance, for forgiving gameplay checks (plates, exits). */
fun overlapsPadded(
    ax: Float, ay: Float, aw: Float, ah: Float,
    bx: Float, by: Float, bw: Float, bh: Float,
    pad: Float,
): Boolean = overlaps(ax - pad, ay - pad, aw + pad * 2, ah + pad * 2, bx, by, bw, bh)

fun pointIn(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Boolean =
    px >= x && px <= x + w && py >= y && py <= y + h

/** Shortest distance between two AABBs; 0 when they overlap. Used for enemy aggro ranges. */
fun rectDistance(
    ax: Float, ay: Float, aw: Float, ah: Float,
    bx: Float, by: Float, bw: Float, bh: Float,
): Float {
    val dx = max(0f, max(bx - (ax + aw), ax - (bx + bw)))
    val dy = max(0f, max(by - (ay + ah), ay - (by + bh)))
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

fun clamp(v: Float, lo: Float, hi: Float): Float = if (v < lo) lo else if (v > hi) hi else v
fun clamp(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Frame-rate-independent exponential smoothing. `rate` is roughly "units per second". */
fun damp(current: Float, target: Float, rate: Float, dt: Float): Float {
    val t = 1f - kotlin.math.exp(-rate * dt)
    return current + (target - current) * t
}

fun approach(current: Float, target: Float, maxDelta: Float): Float {
    val d = target - current
    return if (abs(d) <= maxDelta) target else current + if (d > 0) maxDelta else -maxDelta
}

fun signOf(v: Float): Int = if (v > 0f) 1 else if (v < 0f) -1 else 0

fun minOf3(a: Float, b: Float, c: Float): Float = min(a, min(b, c))
