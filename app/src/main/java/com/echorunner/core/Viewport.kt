package com.echorunner.core

import kotlin.math.max
import kotlin.math.min

/**
 * A rectangle of **screen**, in pixels. Used for two related jobs that must agree exactly:
 * the box the world is allowed to draw into, and the boxes the on-screen controls occupy.
 *
 * Keeping both in the same type is the point. Before this existed, the renderer sized the
 * world to the full canvas and the controls placed themselves against the same canvas
 * independently, so nothing in the codebase could answer "does a button cover the exit?".
 * It did — on 25 of the 30 levels. Expressing both as rectangles makes the question
 * answerable, and [overlaps] makes it testable.
 */
data class Viewport(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /**
     * Overlap in the geometric sense, with a sub-pixel tolerance.
     *
     * [EPSILON] exists because the playfield edge and the control edge are computed by different
     * expressions that are algebraically identical — `pad + size` on one side, `screen - reserve`
     * on the other — and float arithmetic makes them disagree by around 4e-5 px. Without a
     * tolerance the layout tests fail on a rectangle that is exactly adjacent, which is the
     * correct layout. A hundredth of a pixel is well below anything a display can show.
     */
    fun overlaps(other: Viewport): Boolean =
        left < other.right - EPSILON && other.left < right - EPSILON &&
            top < other.bottom - EPSILON && other.top < bottom - EPSILON

    companion object {
        const val EPSILON = 0.01f
        /**
         * Fits the whole world uniformly inside [box] and centres it there.
         *
         * Uniform (`min` of the two axis fits) rather than stretch-to-fill: a level's jump arcs
         * are authored in world units, and a non-square pixel would make the same jump read as
         * a different shape on every device.
         *
         * The **whole** world always fits, so there is nothing off-screen to scroll to. That is
         * a property of the game, not an accident of this function: every Echo Runner level is
         * exactly [GameConstants.WORLD_W] x [GameConstants.WORLD_H] — one room, seen at once.
         * A puzzle you have to pan around is a puzzle you cannot plan, and planning is the game.
         */
        fun fitWorld(box: Viewport): Viewport {
            val scale = min(box.width / GameConstants.WORLD_W, box.height / GameConstants.WORLD_H)
            // Clamped to the box because `WORLD_W * (box.width / WORLD_W)` can land a fraction of
            // a pixel *over* box.width in float, which then centres the world a hair outside its
            // own box and reads as a real overlap to anything checking rectangles.
            val w = min(GameConstants.WORLD_W * scale, box.width)
            val h = min(GameConstants.WORLD_H * scale, box.height)
            return Viewport(
                left = box.left + max(0f, box.width - w) * 0.5f,
                top = box.top + max(0f, box.height - h) * 0.5f,
                width = w,
                height = h,
            )
        }
    }
}
